package com.docreader.oficina;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado de documentos de Office según [MS-OFFCRYPTO]:
 * - Office 2007+ (.docx/.xlsx/.pptx cifrados): contenedor CFB con EncryptionInfo + EncryptedPackage,
 *   esquemas «Standard» (AES, SHA-1) y «Agile» (AES, SHA-1/256/384/512).
 * - Office 97–2003: RC4 clásico (MD5) y RC4 CryptoAPI (SHA-1), con las piezas comunes a .doc/.xls/.ppt.
 * Solo usa javax.crypto y java.security (disponibles en Android), sin dependencias de Android.
 */
final class Cripto {
    /** Contraseña que Excel usa cuando el libro solo está protegido contra cambios: se prueba sola. */
    static final String CLAVE_EXCEL_POR_DEFECTO = "VelvetSweatshop";

    private Cripto() { }

    // ===================================================================================
    // Office 2007+ (OOXML cifrado)
    // ===================================================================================

    /** ¿Es un OOXML cifrado (CFB con EncryptionInfo y EncryptedPackage)? */
    static boolean esOoxmlCifrado(Cfb cfb) {
        return cfb.existe("EncryptionInfo") && cfb.existe("EncryptedPackage");
    }

    /**
     * Descifra y devuelve el zip original. Sin contraseña (null) prueba la de Excel por defecto y,
     * si no vale, lanza FalloClave para que el visor la pida.
     */
    static byte[] descifrarOoxml(Cfb cfb, String clave) throws Fallo {
        byte[] info = cfb.flujo("EncryptionInfo"), paquete = cfb.flujo("EncryptedPackage");
        if (info == null || paquete == null || info.length < 8 || paquete.length < 8) {
            throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        }
        int mayor = Cfb.u16(info, 0), menor = Cfb.u16(info, 2);
        try {
            if (mayor == 4 && menor == 4) return agile(info, paquete, clave);
            if ((mayor == 3 || mayor == 4) && menor == 2) return estandar(info, paquete, clave);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new Fallo("No se pudo descifrar el documento: " + e.getMessage());
        }
        throw new Fallo("Este documento usa un tipo de cifrado que el visor no admite.");
    }

    // --- Standard (Office 2007): AES-ECB, clave derivada con SHA-1 y 50 000 vueltas ---

    private static byte[] estandar(byte[] info, byte[] paquete, String clave) throws Fallo, GeneralSecurityException {
        int largoCabecera = Cfb.i32(info, 8);
        if (largoCabecera < 32 || largoCabecera > 4096 || 12 + largoCabecera + 76 > info.length) {
            throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        }
        int h = 12; // EncryptionHeader
        int algId = Cfb.i32(info, h + 8), bitsClave = Cfb.i32(info, h + 16);
        if (algId != 0x660E && algId != 0x660F && algId != 0x6610) {
            throw new Fallo("Este documento usa un tipo de cifrado que el visor no admite.");
        }
        if (bitsClave == 0) bitsClave = 128;
        int v = h + largoCabecera; // EncryptionVerifier
        int tamSal = Cfb.i32(info, v);
        if (tamSal != 16) throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        byte[] sal = Arrays.copyOfRange(info, v + 4, v + 4 + tamSal);
        byte[] verificador = Arrays.copyOfRange(info, v + 20, v + 36);
        int tamHash = Cfb.i32(info, v + 36);
        if (tamHash < 16 || tamHash > 32) throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        byte[] hashVerif = Arrays.copyOfRange(info, v + 40, v + 40 + 32);

        byte[] k = null;
        for (String intento : intentos(clave)) {
            byte[] candidata = claveEstandar(intento, sal, bitsClave / 8);
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(candidata, "AES"));
            byte[] ver = c.doFinal(verificador);
            byte[] hv = c.doFinal(hashVerif);
            if (MessageDigest.isEqual(Arrays.copyOf(sha1(ver), tamHash), Arrays.copyOf(hv, tamHash))) {
                k = candidata;
                break;
            }
        }
        if (k == null) throw FalloClave.para(clave);
        long tam = u64(paquete, 0);
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"));
        int n = (paquete.length - 8) / 16 * 16;
        byte[] plano = c.doFinal(paquete, 8, n);
        return Arrays.copyOf(plano, (int) Math.min(tam, plano.length));
    }

    private static byte[] claveEstandar(String clave, byte[] sal, int bytesClave) throws GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(sal);
        byte[] hh = md.digest(utf16(clave));
        for (int i = 0; i < 50000; i++) {
            md.update(le32(i));
            hh = md.digest(hh);
        }
        md.update(hh);
        byte[] fin = md.digest(le32(0));
        byte[] b1 = new byte[64], b2 = new byte[64];
        Arrays.fill(b1, (byte) 0x36);
        Arrays.fill(b2, (byte) 0x5C);
        for (int i = 0; i < fin.length; i++) {
            b1[i] ^= fin[i];
            b2[i] ^= fin[i];
        }
        byte[] x = concat(md.digest(b1), md.digest(b2));
        return Arrays.copyOf(x, bytesClave);
    }

    // --- Agile (Office 2010+): AES-CBC, descripción en XML dentro de EncryptionInfo ---

    private static final byte[] BLOQUE_VERIF_ENTRADA = {(byte) 0xfe, (byte) 0xa7, (byte) 0xd2, 0x76, 0x3b, 0x4b, (byte) 0x9e, 0x79};
    private static final byte[] BLOQUE_VERIF_VALOR = {(byte) 0xd7, (byte) 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e};
    private static final byte[] BLOQUE_CLAVE = {0x14, 0x6e, 0x0b, (byte) 0xe7, (byte) 0xab, (byte) 0xac, (byte) 0xd0, (byte) 0xd6};

    private static byte[] agile(byte[] info, byte[] paquete, String clave) throws Fallo, GeneralSecurityException {
        String xml = new String(info, 8, info.length - 8, StandardCharsets.UTF_8);
        String keyData = elemento(xml, "keyData");
        String encKey = elemento(xml, "encryptedKey");
        if (keyData == null || encKey == null) throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        if (!"AES".equalsIgnoreCase(atr(encKey, "cipherAlgorithm")) || !"AES".equalsIgnoreCase(atr(keyData, "cipherAlgorithm"))) {
            throw new Fallo("Este documento usa un tipo de cifrado que el visor no admite.");
        }
        String hashClave = hash(atr(encKey, "hashAlgorithm"));
        int vueltas = Integer.parseInt(atr(encKey, "spinCount"));
        if (vueltas < 0 || vueltas > 10_000_000) throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        int bitsClave = Integer.parseInt(atr(encKey, "keyBits"));
        int tamHash = Integer.parseInt(atr(encKey, "hashSize"));
        byte[] sal = b64(atr(encKey, "saltValue"));
        byte[] eEntrada = b64(atr(encKey, "encryptedVerifierHashInput"));
        byte[] eValor = b64(atr(encKey, "encryptedVerifierHashValue"));
        byte[] eClave = b64(atr(encKey, "encryptedKeyValue"));

        byte[] secreto = null;
        for (String intento : intentos(clave)) {
            MessageDigest md = MessageDigest.getInstance(hashClave);
            md.update(sal);
            byte[] hh = md.digest(utf16(intento));
            for (int i = 0; i < vueltas; i++) {
                md.update(le32(i));
                hh = md.digest(hh);
            }
            byte[] entrada = aesCbc(derivar(md, hh, BLOQUE_VERIF_ENTRADA, bitsClave / 8), sal, eEntrada);
            byte[] valor = aesCbc(derivar(md, hh, BLOQUE_VERIF_VALOR, bitsClave / 8), sal, eValor);
            byte[] esperado = MessageDigest.getInstance(hashClave).digest(Arrays.copyOf(entrada, sal.length));
            if (MessageDigest.isEqual(Arrays.copyOf(esperado, tamHash), Arrays.copyOf(valor, tamHash))) {
                // La clave secreta tiene el tamaño de keyData (puede ser menor que la de la contraseña).
                int bitsDatos = Integer.parseInt(atr(keyData, "keyBits"));
                secreto = Arrays.copyOf(aesCbc(derivar(md, hh, BLOQUE_CLAVE, bitsClave / 8), sal, eClave), bitsDatos / 8);
                break;
            }
        }
        if (secreto == null) throw FalloClave.para(clave);

        // Paquete: tamaño (8 bytes) y segmentos de 4096 bytes, cada uno con su IV.
        String hashDatos = hash(atr(keyData, "hashAlgorithm"));
        byte[] salDatos = b64(atr(keyData, "saltValue"));
        int bloqueDatos = Integer.parseInt(atr(keyData, "blockSize"));
        long tam = u64(paquete, 0);
        byte[] salida = new byte[(int) Math.min(tam, Math.max(0, paquete.length - 8))];
        MessageDigest md = MessageDigest.getInstance(hashDatos);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        SecretKeySpec ks = new SecretKeySpec(secreto, "AES");
        int escrito = 0;
        for (int seg = 0, off = 8; off < paquete.length && escrito < salida.length; seg++, off += 4096) {
            int n = Math.min(4096, paquete.length - off) / 16 * 16;
            if (n <= 0) break;
            md.update(salDatos);
            byte[] iv = ajustar(md.digest(le32(seg)), bloqueDatos);
            c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            byte[] p = c.doFinal(paquete, off, n);
            int copiar = Math.min(p.length, salida.length - escrito);
            System.arraycopy(p, 0, salida, escrito, copiar);
            escrito += copiar;
        }
        return salida;
    }

    private static byte[] derivar(MessageDigest md, byte[] hh, byte[] bloque, int bytes) {
        md.update(hh);
        return ajustar(md.digest(bloque), bytes);
    }

    private static byte[] aesCbc(byte[] clave, byte[] iv, byte[] datos) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"), new IvParameterSpec(ajustar(iv, 16)));
        return c.doFinal(datos, 0, datos.length / 16 * 16);
    }

    /** Recorta o rellena con 0x36 hasta el tamaño pedido (regla de MS-OFFCRYPTO). */
    private static byte[] ajustar(byte[] b, int n) {
        byte[] r = Arrays.copyOf(b, n);
        for (int i = b.length; i < n; i++) r[i] = 0x36;
        return r;
    }

    private static String hash(String nombre) throws Fallo {
        if (nombre == null) throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        switch (nombre.toUpperCase().replace("-", "")) {
            case "SHA1": return "SHA-1";
            case "SHA256": return "SHA-256";
            case "SHA384": return "SHA-384";
            case "SHA512": return "SHA-512";
            case "MD5": return "MD5";
            default: throw new Fallo("Este documento usa un tipo de cifrado que el visor no admite.");
        }
    }

    /** Atributos de la primera etiqueta con ese nombre local (el XML es pequeño y fijo). */
    private static String elemento(String xml, String local) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + local + "\\b([^>]*)>").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String atr(String atributos, String nombre) {
        Matcher m = Pattern.compile("\\b" + nombre + "\\s*=\\s*\"([^\"]*)\"").matcher(atributos);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] b64(String s) throws Fallo {
        if (s == null) throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
        return java.util.Base64.getMimeDecoder().decode(s);
    }

    // --- Proteger: cifrado Agile (AES-256, SHA-512), como Office 2010+ ---

    private static final byte[] BLOQUE_HMAC_CLAVE = {0x5f, (byte) 0xb2, (byte) 0xad, 0x01, 0x0c, (byte) 0xb9, (byte) 0xe1, (byte) 0xf6};
    private static final byte[] BLOQUE_HMAC_VALOR = {(byte) 0xa0, 0x67, 0x7f, 0x02, (byte) 0xb2, 0x2c, (byte) 0x84, 0x33};
    private static final int VUELTAS = 100_000;

    /** Flujos fijos de "\u0006DataSpaces" (idénticos en todos los archivos cifrados por Office). */
    private static final String DS_VERSION = "3c0000004d006900630072006f0073006f00660074002e0043006f006e007400610069006e00650072002e004400610074006100530070006100630065007300010000000100000001000000";
    private static final String DS_MAPA = "08000000010000006800000001000000000000002000000045006e0063007200790070007400650064005000610063006b00610067006500320000005300740072006f006e00670045006e006300720079007000740069006f006e004400610074006100530070006100630065000000";
    private static final String DS_INFO = "0800000001000000320000005300740072006f006e00670045006e006300720079007000740069006f006e005400720061006e00730066006f0072006d000000";
    private static final String DS_PRIMARIO = "58000000010000004c0000007b00460046003900410033004600300033002d0035003600450046002d0034003600310033002d0042004400440035002d003500410034003100430031004400300037003200340036007d004e0000004d006900630072006f0073006f00660074002e0043006f006e007400610069006e00650072002e0045006e006300720079007000740069006f006e005400720061006e00730066006f0072006d00000001000000010000000100000000000000000000000000000004000000";

    /**
     * Cifra un .docx/.xlsx/.pptx (los bytes del zip) con contraseña. Devuelve el contenedor CFB
     * que Office, LibreOffice y este visor abren pidiendo la contraseña.
     */
    static byte[] protegerOoxml(byte[] zip, String clave) throws Fallo {
        try {
            java.security.SecureRandom azar = new java.security.SecureRandom();
            byte[] salDatos = aleatorio(azar, 16), salClave = aleatorio(azar, 16);
            byte[] secreto = aleatorio(azar, 32), verifEntrada = aleatorio(azar, 16), claveHmac = aleatorio(azar, 64);

            // Clave derivada de la contraseña.
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(salClave);
            byte[] hh = md.digest(utf16(clave));
            for (int i = 0; i < VUELTAS; i++) {
                md.update(le32(i));
                hh = md.digest(hh);
            }
            byte[] eEntrada = aesCbcCifrar(derivar(md, hh, BLOQUE_VERIF_ENTRADA, 32), salClave, verifEntrada);
            byte[] eValor = aesCbcCifrar(derivar(md, hh, BLOQUE_VERIF_VALOR, 32), salClave,
                    MessageDigest.getInstance("SHA-512").digest(verifEntrada));
            byte[] eClave = aesCbcCifrar(derivar(md, hh, BLOQUE_CLAVE, 32), salClave, secreto);

            // Paquete: tamaño + segmentos de 4096 bytes, cada uno con su IV.
            java.io.ByteArrayOutputStream paquete = new java.io.ByteArrayOutputStream(zip.length + 4096 + 8);
            byte[] tam = new byte[8];
            for (int i = 0; i < 8; i++) tam[i] = (byte) ((long) zip.length >> (8 * i));
            paquete.write(tam, 0, 8);
            SecretKeySpec ks = new SecretKeySpec(secreto, "AES");
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            MessageDigest mdDatos = MessageDigest.getInstance("SHA-512");
            for (int seg = 0, off = 0; off < zip.length; seg++, off += 4096) {
                int n = Math.min(4096, zip.length - off);
                byte[] bloque = Arrays.copyOfRange(zip, off, off + (n + 15) / 16 * 16); // relleno con ceros
                mdDatos.update(salDatos);
                c.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(ajustar(mdDatos.digest(le32(seg)), 16)));
                byte[] cifrado = c.doFinal(bloque);
                paquete.write(cifrado, 0, cifrado.length);
            }
            byte[] encPaquete = paquete.toByteArray();

            // Integridad (dataIntegrity): HMAC-SHA512 del flujo EncryptedPackage completo.
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(claveHmac, "HmacSHA512"));
            byte[] valorHmac = mac.doFinal(encPaquete);
            mdDatos.update(salDatos);
            byte[] eHmacClave = aesCbcCifrar(secreto, ajustar(mdDatos.digest(BLOQUE_HMAC_CLAVE), 16), claveHmac);
            mdDatos.update(salDatos);
            byte[] eHmacValor = aesCbcCifrar(secreto, ajustar(mdDatos.digest(BLOQUE_HMAC_VALOR), 16), valorHmac);

            java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
            String comun = "saltSize=\"16\" blockSize=\"16\" keyBits=\"256\" hashSize=\"64\" cipherAlgorithm=\"AES\" "
                    + "cipherChaining=\"ChainingModeCBC\" hashAlgorithm=\"SHA512\" ";
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
                    + "<encryption xmlns=\"http://schemas.microsoft.com/office/2006/encryption\" "
                    + "xmlns:p=\"http://schemas.microsoft.com/office/2006/keyEncryptor/password\" "
                    + "xmlns:c=\"http://schemas.microsoft.com/office/2006/keyEncryptor/certificate\">"
                    + "<keyData " + comun + "saltValue=\"" + b64.encodeToString(salDatos) + "\"/>"
                    + "<dataIntegrity encryptedHmacKey=\"" + b64.encodeToString(eHmacClave)
                    + "\" encryptedHmacValue=\"" + b64.encodeToString(eHmacValor) + "\"/>"
                    + "<keyEncryptors><keyEncryptor uri=\"http://schemas.microsoft.com/office/2006/keyEncryptor/password\">"
                    + "<p:encryptedKey spinCount=\"" + VUELTAS + "\" " + comun
                    + "saltValue=\"" + b64.encodeToString(salClave) + "\" "
                    + "encryptedVerifierHashInput=\"" + b64.encodeToString(eEntrada) + "\" "
                    + "encryptedVerifierHashValue=\"" + b64.encodeToString(eValor) + "\" "
                    + "encryptedKeyValue=\"" + b64.encodeToString(eClave) + "\"/>"
                    + "</keyEncryptor></keyEncryptors></encryption>";
            byte[] cuerpo = xml.getBytes(StandardCharsets.UTF_8);
            byte[] info = new byte[8 + cuerpo.length];
            info[0] = 4; // versión 4.4
            info[2] = 4;
            info[4] = 0x40; // reservado
            System.arraycopy(cuerpo, 0, info, 8, cuerpo.length);

            CfbEscritor w = new CfbEscritor();
            w.flujo("\u0006DataSpaces/Version", CfbEscritor.hex(DS_VERSION));
            w.flujo("\u0006DataSpaces/DataSpaceMap", CfbEscritor.hex(DS_MAPA));
            w.flujo("\u0006DataSpaces/DataSpaceInfo/StrongEncryptionDataSpace", CfbEscritor.hex(DS_INFO));
            w.flujo("\u0006DataSpaces/TransformInfo/StrongEncryptionTransform/\u0006Primary", CfbEscritor.hex(DS_PRIMARIO));
            w.flujo("EncryptionInfo", info);
            w.flujo("EncryptedPackage", encPaquete);
            return w.bytes();
        } catch (GeneralSecurityException e) {
            throw new Fallo("No se pudo cifrar el documento: " + e.getMessage());
        }
    }

    private static byte[] aesCbcCifrar(byte[] clave, byte[] iv, byte[] datos) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clave, "AES"), new IvParameterSpec(ajustar(iv, 16)));
        return c.doFinal(Arrays.copyOf(datos, (datos.length + 15) / 16 * 16));
    }

    private static byte[] aleatorio(java.security.SecureRandom r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    // ===================================================================================
    // Office 97–2003: RC4
    // ===================================================================================

    /** RC4 en flujo (pocas líneas; no depende de que el proveedor de Android traiga ARC4). */
    static final class Rc4 {
        private final int[] s = new int[256];
        private int i, j;

        Rc4(byte[] clave) {
            for (int k = 0; k < 256; k++) s[k] = k;
            for (int k = 0, m = 0; k < 256; k++) {
                m = (m + s[k] + (clave[k % clave.length] & 0xFF)) & 0xFF;
                int t = s[k];
                s[k] = s[m];
                s[m] = t;
            }
        }

        /** Descifra (o cifra: es lo mismo) en el sitio. */
        void aplicar(byte[] b, int off, int n) {
            for (int k = off; k < off + n; k++) {
                i = (i + 1) & 0xFF;
                j = (j + s[i]) & 0xFF;
                int t = s[i];
                s[i] = s[j];
                s[j] = t;
                b[k] ^= s[(s[i] + s[j]) & 0xFF];
            }
        }

        /** Avanza el flujo sin datos (para empezar a mitad de un bloque). */
        void saltar(int n) {
            byte[] basura = new byte[Math.min(n, 4096)];
            while (n > 0) {
                int k = Math.min(n, basura.length);
                aplicar(basura, 0, k);
                n -= k;
            }
        }
    }

    /**
     * Clave de un esquema RC4 de Office 97–2003, ya validada con el verificador. Da el RC4 de
     * cada bloque; quien descifra cambia de bloque cada {@code tamBloque} bytes (512 en .doc,
     * 1024 en .xls) o usa el número que indique su formato (.ppt: el id de cada objeto).
     */
    static final class ClaveRc4 {
        private final byte[] base;
        private final boolean cryptoApi;
        private final int bytesClave;

        private ClaveRc4(byte[] base, boolean cryptoApi, int bytesClave) {
            this.base = base;
            this.cryptoApi = cryptoApi;
            this.bytesClave = bytesClave;
        }

        Rc4 bloque(int numero) {
            try {
                if (cryptoApi) {
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    md.update(base);
                    byte[] h = md.digest(le32(numero));
                    byte[] k = Arrays.copyOf(h, bytesClave);
                    // 40 bits: la clave de 5 bytes se rellena con ceros hasta 16 (como CryptoAPI).
                    return new Rc4(bytesClave == 5 ? Arrays.copyOf(k, 16) : k);
                }
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(base);
                return new Rc4(md.digest(le32(numero)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Descifra "datos" en el sitio, sabiendo que datos[0] está en la posición "posicion" del
         * flujo cifrado y que el RC4 se reinicia cada "tamBloque" bytes.
         */
        void descifrar(byte[] datos, int off, int n, long posicion, int tamBloque) {
            int hecho = 0;
            while (hecho < n) {
                long p = posicion + hecho;
                int bloque = (int) (p / tamBloque), dentro = (int) (p % tamBloque);
                int k = Math.min(n - hecho, tamBloque - dentro);
                Rc4 r = bloque(bloque);
                r.saltar(dentro);
                r.aplicar(datos, off + hecho, k);
                hecho += k;
            }
        }
    }

    /**
     * RC4 clásico (Office 97/2000, MD5). Cabecera: versión 1.1, luego sal (16), verificador
     * cifrado (16) y su hash MD5 cifrado (16), a partir de "off". null si la clave no vale.
     */
    static ClaveRc4 rc4Clasico(byte[] d, int off, String clave) {
        try {
            if (off < 0 || off + 48 > d.length) return null;
            byte[] sal = Arrays.copyOfRange(d, off, off + 16);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h0 = Arrays.copyOf(md.digest(utf16(clave)), 5);
            for (int i = 0; i < 16; i++) {
                md.update(h0);
                md.update(sal);
            }
            byte[] base = Arrays.copyOf(md.digest(), 5);
            ClaveRc4 k = new ClaveRc4(base, false, 16);
            byte[] v = Arrays.copyOfRange(d, off + 16, off + 48);
            k.bloque(0).aplicar(v, 0, 32);
            byte[] esperado = MessageDigest.getInstance("MD5").digest(Arrays.copyOf(v, 16));
            return MessageDigest.isEqual(esperado, Arrays.copyOfRange(v, 16, 32)) ? k : null;
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /**
     * RC4 CryptoAPI (SHA-1). "off" apunta a EncryptionHeaderFlags tras la versión: flags (4),
     * tamaño de la cabecera (4), EncryptionHeader, EncryptionVerifier. null si la clave no vale.
     */
    static ClaveRc4 rc4CryptoApi(byte[] d, int off, String clave) {
        try {
            // Tamaños leídos del archivo: se validan antes de usarlos (un archivo dañado no debe
            // provocar reservas de memoria enormes).
            int largoCab = Cfb.i32(d, off + 4);
            if (largoCab < 32 || largoCab > 4096) return null;
            int h = off + 8;
            int bits = Cfb.i32(d, h + 16);
            if (bits == 0) bits = 40;
            if (bits < 40 || bits > 128 || bits % 8 != 0) return null;
            int v = h + largoCab;
            int tamSal = Cfb.i32(d, v);
            if (tamSal != 16 || v + 24 + tamSal + 20 > d.length) return null;
            int tamHashLeido = Cfb.i32(d, v + 20 + tamSal);
            if (tamHashLeido != 20 && tamHashLeido != 32) return null;
            byte[] sal = Arrays.copyOfRange(d, v + 4, v + 4 + tamSal);
            byte[] verif = Arrays.copyOfRange(d, v + 4 + tamSal, v + 20 + tamSal);
            int tamHash = Cfb.i32(d, v + 20 + tamSal);
            byte[] hv = Arrays.copyOfRange(d, v + 24 + tamSal, v + 24 + tamSal + tamHash);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(sal);
            byte[] base = md.digest(utf16(clave));
            ClaveRc4 k = new ClaveRc4(base, true, bits / 8);
            byte[] todo = concat(verif, hv);
            k.bloque(0).aplicar(todo, 0, todo.length);
            byte[] esperado = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOf(todo, 16));
            return MessageDigest.isEqual(esperado, Arrays.copyOfRange(todo, 16, 16 + Math.min(20, tamHash))) ? k : null;
        } catch (GeneralSecurityException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Contraseñas a probar: la del usuario, o si no hay, la de Excel por defecto. */
    static String[] intentos(String clave) {
        return clave != null ? new String[] {clave} : new String[] {CLAVE_EXCEL_POR_DEFECTO};
    }

    // --- Utilidades ---

    static byte[] utf16(String s) { return s.getBytes(StandardCharsets.UTF_16LE); }

    static byte[] le32(int v) { return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }

    private static long u64(byte[] b, int off) {
        return (Cfb.i32(b, off) & 0xFFFFFFFFL) | ((long) Cfb.i32(b, off + 4) << 32);
    }

    private static byte[] sha1(byte[] b) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-1").digest(b);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
