package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Descifrado de documentos OpenDocument protegidos con contraseña (ODF 1.2/1.3 §3.4 y las
 * variantes de LibreOffice):
 * <ul>
 * <li>clave inicial: SHA-1 o SHA-256 de la contraseña en UTF-8 (y SHA-1 en windows-1252, como
 * hacía StarOffice);</li>
 * <li>derivación: PBKDF2 con HMAC-SHA1, o Argon2id (LibreOffice 24.2 y posteriores);</li>
 * <li>cifrado: Blowfish CFB (OpenOffice antiguo), AES-CBC con relleno W3C, o AES-GCM
 * (LibreOffice 24.2: todo el paquete va en una sola entrada "encrypted-package").</li>
 * </ul>
 * Lo descifrado está comprimido con deflate sin cabecera. La contraseña se comprueba con la suma
 * del manifiesto (SHA-1 o SHA-256 del primer KiB descifrado), con la etiqueta de GCM o, si no
 * hay nada de eso, porque se descomprime y empieza como un XML.
 */
final class OdfCifrado {
    private OdfCifrado() { }

    private static final String MANIFIESTO = "META-INF/manifest.xml";
    private static final String PAQUETE_CIFRADO = "encrypted-package";
    /** Manifiesto sin cifrado que se pone en el paquete ya descifrado. */
    private static final String MANIFIESTO_LIMPIO =
            "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"/>";
    private static final int MAX_ITERACIONES = 2_000_000;

    private static final int BLOWFISH = 1, AES_CBC = 2, AES_GCM = 3;
    private static final int SHA1 = 1, SHA256 = 2;
    private static final int PBKDF2 = 1, ARGON2ID = 2, PGP = 3;

    /** Datos de cifrado de una entrada del manifiesto. */
    private static final class Entrada {
        String ruta;
        int algoritmo, inicio = SHA1, derivacion = PBKDF2, suma;
        byte[] iv, sal, comprobacion;
        int iteraciones = 1024, tamClave = -1;
        int argonT, argonM, argonP;
        boolean desconocido;
    }

    /**
     * true si el documento en sí está cifrado (content.xml o el paquete entero). Las bibliotecas
     * de macros con contraseña cifran solo sus propias partes y no cuentan.
     */
    static boolean cifrado(Paquete pq) {
        return cifradoManifiesto(pq.bytes(MANIFIESTO));
    }

    /**
     * Igual que {@link #cifrado(Paquete)} pero sobre el archivo en bruto, leyendo solo el
     * manifiesto: sirve para decidir si se ofrece poner o quitar la contraseña.
     */
    static boolean estaCifrado(byte[] zip) {
        return zip != null && cifradoManifiesto(Paquete.leerEntrada(zip, MANIFIESTO));
    }

    private static boolean cifradoManifiesto(byte[] man) {
        if (man == null || !new String(man, StandardCharsets.ISO_8859_1).contains("encryption-data")) return false;
        try {
            for (Entrada e : leerManifiesto(man)) {
                if (e.ruta.equals("content.xml") || e.ruta.equals(PAQUETE_CIFRADO)) return true;
            }
        } catch (Fallo f) {
            return true; // manifiesto ilegible: que lo diga descifrar()
        }
        return false;
    }

    /**
     * Devuelve un paquete con las partes necesarias ya descifradas (content.xml, styles.xml e
     * imágenes). Lanza FalloClave si la contraseña no es la buena.
     */
    static Paquete descifrar(Paquete pq, String clave) throws Fallo {
        return descifrar(pq, clave, false);
    }

    /**
     * Con "todas" se descifran todas las partes (para quitar la contraseña) y se deja el
     * manifiesto original; sin él, solo las que hacen falta para mostrar el documento.
     */
    private static Paquete descifrar(Paquete pq, String clave, boolean todas) throws Fallo {
        List<Entrada> entradas = leerManifiesto(pq);
        Entrada principal = principal(pq, entradas);

        // Claves iniciales posibles: la que dice el manifiesto primero y luego las demás variantes.
        List<byte[]> iniciales = clavesIniciales(clave, principal.inicio);
        AtomicLong total = new AtomicLong();
        byte[] datos = null;
        byte[] inicial = null;
        for (byte[] k : iniciales) {
            datos = descifrarEntrada(pq.bytes(principal.ruta), principal, k, true, total);
            if (datos != null) {
                inicial = k;
                break;
            }
        }
        if (datos == null) throw FalloClave.para(clave);

        if (principal.ruta.equals(PAQUETE_CIFRADO)) {
            // Todo el documento iba dentro: es otro zip.
            Paquete interior;
            try {
                interior = Paquete.leer(new ByteArrayInputStream(datos));
            } catch (IOException e) {
                throw new Fallo("El documento cifrado está dañado y no se puede abrir.");
            }
            if (!todas && cifrado(interior)) interior.poner(MANIFIESTO, MANIFIESTO_LIMPIO.getBytes(StandardCharsets.UTF_8));
            return interior;
        }

        pq.poner(principal.ruta, datos);
        // El resto de partes, en paralelo (cada una tiene su sal, así que hay que derivar otra vez).
        List<Entrada> resto = new ArrayList<>();
        for (Entrada e : entradas) {
            if (e == principal || !pq.existe(e.ruta)) continue;
            if (todas && e.desconocido) throw new Fallo("Una parte del documento usa un cifrado que el visor no conoce.");
            if (todas || (necesaria(e.ruta) && !e.desconocido)) resto.add(e);
            else pq.poner(e.ruta, null);
        }
        final byte[] k = inicial;
        List<byte[]> res = enParalelo(resto.size(), i -> descifrarEntrada(pq.bytes(resto.get(i).ruta), resto.get(i), k,
                false, total));
        for (int i = 0; i < resto.size(); i++) {
            if (res.get(i) == null && todas) {
                throw new Fallo("Una parte del documento cifrado está dañada y no se puede descifrar.");
            }
            pq.poner(resto.get(i).ruta, res.get(i));
        }
        if (total.get() > Paquete.LIMITE) throw new Fallo(Paquete.MENSAJE_GRANDE);
        if (!todas) pq.poner(MANIFIESTO, MANIFIESTO_LIMPIO.getBytes(StandardCharsets.UTF_8));
        return pq;
    }

    /** Tarea que puede lanzar Fallo, para {@link #enParalelo}. */
    private interface Tarea {
        byte[] hacer(int i) throws Fallo;
    }

    /** Ejecuta n tareas en hasta 4 hilos; las que fallan dan null (salvo Fallo, que se relanza). */
    private static List<byte[]> enParalelo(int n, Tarea t) throws Fallo {
        List<byte[]> r = new ArrayList<>();
        if (n == 0) return r;
        int hilos = Math.max(1, Math.min(4, Math.min(n, Runtime.getRuntime().availableProcessors())));
        ExecutorService ex = Executors.newFixedThreadPool(hilos);
        try {
            List<Future<byte[]>> fut = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int j = i;
                fut.add(ex.submit(() -> t.hacer(j)));
            }
            for (Future<byte[]> f : fut) {
                try {
                    r.add(f.get());
                } catch (java.util.concurrent.ExecutionException err) {
                    if (err.getCause() instanceof Fallo) throw (Fallo) err.getCause();
                    if (err.getCause() instanceof OutOfMemoryError) {
                        throw new Fallo("No hay memoria suficiente para este documento.");
                    }
                    r.add(null);
                } catch (InterruptedException err) {
                    Thread.currentThread().interrupt();
                    throw new Fallo("Se ha interrumpido el proceso.");
                }
            }
        } finally {
            ex.shutdownNow();
        }
        return r;
    }

    /**
     * Comprueba, antes de pedir la contraseña, que el cifrado es de un tipo que se sabe abrir
     * (no OpenPGP ni algoritmos desconocidos). Lanza Fallo si no.
     */
    static void comprobar(Paquete pq) throws Fallo {
        principal(pq, leerManifiesto(pq));
    }

    /** Entrada con la que se comprueba la contraseña: el paquete cifrado entero o content.xml. */
    private static Entrada principal(Paquete pq, List<Entrada> entradas) throws Fallo {
        if (entradas.isEmpty()) throw new Fallo("No se entienden los datos de cifrado del documento.");
        for (Entrada e : entradas) {
            if (e.derivacion == PGP) {
                throw new Fallo("Este documento está cifrado con una clave OpenPGP (no con contraseña) "
                        + "y el visor no puede abrirlo.");
            }
        }
        Entrada principal = null;
        for (Entrada e : entradas) {
            if (e.ruta.equals(PAQUETE_CIFRADO) && pq.existe(e.ruta)) principal = e;
        }
        if (principal == null) {
            for (Entrada e : entradas) if (e.ruta.equals("content.xml") && pq.existe(e.ruta)) principal = e;
        }
        if (principal == null) throw new Fallo("Falta el contenido cifrado del documento. Puede que el archivo esté dañado.");
        if (principal.desconocido) throw new Fallo("Este documento usa un tipo de cifrado que el visor no conoce.");
        return principal;
    }

    /** Partes que hacen falta para mostrar el documento; las demás (ajustes, macros…) se quitan. */
    private static boolean necesaria(String ruta) {
        String r = ruta.toLowerCase(Locale.ROOT);
        if (r.equals("content.xml") || r.equals("styles.xml")) return true;
        if (r.startsWith("configurations2/") || r.startsWith("thumbnails/") || r.startsWith("basic/")
                || r.startsWith("dialogs/") || r.startsWith("meta-inf/") || r.startsWith("scripts/")) {
            return false;
        }
        return !r.endsWith(".xml") && !r.endsWith(".rdf");
    }

    // --- Manifiesto ---

    private static List<Entrada> leerManifiesto(Paquete pq) throws Fallo {
        return leerManifiesto(pq.bytes(MANIFIESTO));
    }

    private static List<Entrada> leerManifiesto(byte[] manifiesto) throws Fallo {
        List<Entrada> r = new ArrayList<>();
        try {
            XmlPullParser p = parserManifiesto(manifiesto);
            if (p == null) throw new Fallo("Los datos de cifrado del documento están dañados.");
            Entrada e = null;
            String ruta = null;
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Lectura.nombre(p)) {
                    case "file-entry": ruta = Lectura.atr(p, "full-path"); e = null; break;
                    case "encryption-data":
                        if (ruta == null) break;
                        e = new Entrada();
                        e.ruta = Paquete.normalizar(ruta);
                        e.suma = tipoSuma(Lectura.atr(p, "checksum-type"));
                        e.comprobacion = base64(Lectura.atr(p, "checksum"));
                        r.add(e);
                        break;
                    case "algorithm":
                        if (e != null) {
                            e.algoritmo = algoritmo(Lectura.atr(p, "algorithm-name"), e);
                            e.iv = base64(Lectura.atr(p, "initialisation-vector"));
                        }
                        break;
                    case "start-key-generation":
                        if (e != null) {
                            String n = Lectura.atr(p, "start-key-generation-name");
                            if (n != null && n.toLowerCase(Locale.ROOT).contains("sha256")) e.inicio = SHA256;
                            else if (n != null && !n.toLowerCase(Locale.ROOT).contains("sha1")) e.desconocido = true;
                        }
                        break;
                    case "key-derivation":
                        if (e != null) derivacion(p, e);
                        break;
                    default: break;
                }
            }
        } catch (Fallo f) {
            throw f;
        } catch (Exception err) {
            throw new Fallo("Los datos de cifrado del documento están dañados.");
        }
        return r;
    }

    /**
     * Parser del manifiesto. Los de OpenOffice traen una declaración DOCTYPE sin subconjunto
     * interno: se quita (sin ella no hay entidades que expandir); con subconjunto se rechaza.
     */
    private static XmlPullParser parserManifiesto(byte[] b) throws Exception {
        if (b == null) return null;
        String inicio = new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1);
        int i = inicio.indexOf("<!DOCTYPE");
        if (inicio.contains("<!ENTITY")) return null;
        if (i >= 0) {
            int j = inicio.indexOf('>', i);
            if (j < 0 || inicio.substring(i, j).indexOf('[') >= 0) return null;
            byte[] c = new byte[b.length - (j + 1 - i)];
            System.arraycopy(b, 0, c, 0, i);
            System.arraycopy(b, j + 1, c, i, b.length - j - 1);
            b = c;
        }
        XmlPullParser p = android.util.Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new ByteArrayInputStream(b), null);
        return p;
    }

    private static int tipoSuma(String n) {
        if (n == null) return 0;
        String s = n.toLowerCase(Locale.ROOT);
        if (s.contains("sha256")) return SHA256;
        if (s.contains("sha1")) return SHA1;
        return 0;
    }

    private static int algoritmo(String n, Entrada e) {
        if (n == null) {
            e.desconocido = true;
            return 0;
        }
        String s = n.toLowerCase(Locale.ROOT);
        int tam = s.contains("aes128") ? 16 : s.contains("aes192") ? 24 : 32;
        if (s.contains("blowfish")) return BLOWFISH;
        if (s.endsWith("-gcm")) {
            if (e.tamClave < 0) e.tamClave = tam;
            return AES_GCM;
        }
        if (s.endsWith("-cbc")) {
            if (e.tamClave < 0) e.tamClave = tam;
            return AES_CBC;
        }
        e.desconocido = true;
        return 0;
    }

    private static void derivacion(XmlPullParser p, Entrada e) {
        String n = Lectura.atr(p, "key-derivation-name");
        String s = n == null ? "" : n.toLowerCase(Locale.ROOT);
        if (s.equals("pgp") || s.contains("rsa")) {
            e.derivacion = PGP;
            return;
        }
        if (s.endsWith("argon2id")) {
            e.derivacion = ARGON2ID;
            e.argonT = Lectura.entero(Lectura.atr(p, "argon2-iterations"), 0);
            e.argonM = Lectura.entero(Lectura.atr(p, "argon2-memory"), 0);
            e.argonP = Lectura.entero(Lectura.atr(p, "argon2-lanes"), 0);
        } else if (s.equals("pbkdf2") || s.endsWith("#pbkdf2")) {
            e.derivacion = PBKDF2;
            e.iteraciones = Lectura.entero(Lectura.atr(p, "iteration-count"), 1024);
        } else {
            e.desconocido = true;
        }
        e.sal = base64(Lectura.atr(p, "salt"));
        String k = Lectura.atr(p, "key-size");
        if (k != null) e.tamClave = Lectura.entero(k, -1);
    }

    private static byte[] base64(String v) {
        if (v == null) return null;
        try {
            return Base64.getMimeDecoder().decode(v.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Claves ---

    private static List<byte[]> clavesIniciales(String clave, int declarada) {
        byte[] utf8 = clave.getBytes(StandardCharsets.UTF_8);
        byte[] win;
        try {
            win = clave.getBytes(Charset.forName("windows-1252"));
        } catch (RuntimeException e) {
            win = clave.getBytes(StandardCharsets.ISO_8859_1);
        }
        List<byte[]> r = new ArrayList<>();
        byte[] s256 = resumen("SHA-256", utf8), s1 = resumen("SHA-1", utf8), s1w = resumen("SHA-1", win);
        if (declarada == SHA256) {
            r.add(s256);
            r.add(s1);
        } else {
            r.add(s1);
            r.add(s256);
        }
        if (!Arrays.equals(s1w, s1)) r.add(s1w);
        return r;
    }

    private static byte[] resumen(String alg, byte[] datos) {
        try {
            return MessageDigest.getInstance(alg).digest(datos);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derivar(Entrada e, byte[] inicial) throws Fallo {
        int tam = e.tamClave > 0 ? e.tamClave : 16;
        if (tam > 64 || e.sal == null) return null;
        if (e.derivacion == ARGON2ID) {
            if (e.argonT < 1 || e.argonT > 64 || e.argonP < 1 || e.argonP > 64 || e.argonM < 8 * e.argonP) return null;
            long bytes = (long) e.argonM * 1024;
            if (e.argonM > 4 * 1024 * 1024 || bytes > Runtime.getRuntime().maxMemory() * 3 / 4) {
                throw new Fallo("No hay memoria suficiente para descifrar este documento.");
            }
            try {
                return Argon2.argon2id(inicial, e.sal, null, null, e.argonT, e.argonM, e.argonP, tam);
            } catch (OutOfMemoryError err) {
                throw new Fallo("No hay memoria suficiente para descifrar este documento.");
            }
        }
        if (e.iteraciones < 1 || e.iteraciones > MAX_ITERACIONES) return null;
        return Pbkdf2.sha1(inicial, e.sal, e.iteraciones, tam);
    }

    /**
     * Descifra y descomprime una entrada. Con "comprobar" devuelve null si la contraseña no
     * cuadra; sin él devuelve null si la entrada está dañada.
     */
    private static byte[] descifrarEntrada(byte[] cifrado, Entrada e, byte[] inicial, boolean comprobar,
                                           AtomicLong total) throws Fallo {
        if (cifrado == null || e.desconocido || (e.iv == null && e.algoritmo != AES_GCM)) return null;
        byte[] clave = derivar(e, inicial);
        if (clave == null) return null;
        byte[] plano;
        try {
            switch (e.algoritmo) {
                case BLOWFISH:
                    if (e.iv.length != 8) return null;
                    plano = new Blowfish(clave).cfb(cifrado, e.iv);
                    break;
                case AES_CBC: {
                    if (e.iv.length != 16 || cifrado.length % 16 != 0 || cifrado.length == 0) return null;
                    Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"), new IvParameterSpec(e.iv));
                    plano = c.doFinal(cifrado);
                    // Relleno W3C: el último byte dice cuántos sobran (el resto pueden ser aleatorios).
                    int n = plano[plano.length - 1] & 0xFF;
                    if (n < 1 || n > 16) return null;
                    plano = Arrays.copyOf(plano, plano.length - n);
                    break;
                }
                case AES_GCM: {
                    // Como en XML Encryption 1.1, el IV de 12 bytes va delante de los datos y la
                    // etiqueta de 16 detrás; la etiqueta mala (contraseña mala) lanza excepción.
                    if (cifrado.length < 12 + 16) return null;
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"),
                            new GCMParameterSpec(128, Arrays.copyOf(cifrado, 12)));
                    plano = c.doFinal(cifrado, 12, cifrado.length - 12);
                    break;
                }
                default: return null;
            }
        } catch (GeneralSecurityException | RuntimeException err) {
            return null;
        }
        if (comprobar && e.algoritmo != AES_GCM && e.suma != 0 && e.comprobacion != null) {
            byte[] k = Arrays.copyOf(plano, Math.min(1024, plano.length));
            byte[] r = resumen(e.suma == SHA256 ? "SHA-256" : "SHA-1", k);
            if (!MessageDigest.isEqual(r, e.comprobacion)) {
                // Algunas versiones calculaban mal el SHA-1: se acepta si descomprime en un XML.
                if (e.suma != SHA1) return null;
                byte[] d = inflar(plano, total);
                return d != null && pareceXml(d) ? d : null;
            }
        }
        byte[] d = inflar(plano, total);
        if (d == null) {
            // Entrada guardada sin comprimir antes de cifrar.
            if (comprobar && (e.suma == 0 || e.comprobacion == null) && e.algoritmo != AES_GCM) return null;
            d = plano;
            total.addAndGet(d.length);
        }
        if (comprobar && e.suma == 0 && e.algoritmo != AES_GCM && !pareceXml(d)) return null;
        return d;
    }

    private static boolean pareceXml(byte[] d) {
        int i = 0;
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) i = 3;
        while (i < d.length && (d[i] == ' ' || d[i] == '\n' || d[i] == '\r' || d[i] == '\t')) i++;
        return i < d.length && d[i] == '<';
    }

    /** deflate sin cabecera; null si no lo es o si pasa del límite de tamaño. */
    private static byte[] inflar(byte[] datos, AtomicLong total) {
        Inflater inf = new Inflater(true);
        try {
            byte[] entrada = Arrays.copyOf(datos, datos.length + 1); // byte de relleno que pide "nowrap"
            inf.setInput(entrada);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, datos.length * 3));
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) return null;
                    continue;
                }
                out.write(buf, 0, n);
                if (total.addAndGet(n) > Paquete.LIMITE) return null;
            }
            return out.toByteArray();
        } catch (DataFormatException | RuntimeException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // --- Poner y quitar la contraseña ---

    private static final String NS_MANIFIESTO = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
    private static final int ITERACIONES_NUEVAS = 100_000;

    /** Una línea del manifiesto: ruta y atributos manifest:* (sin size), en orden. */
    private static final class Fichero {
        String ruta;
        final java.util.LinkedHashMap<String, String> atributos = new java.util.LinkedHashMap<>();
    }

    /** Manifiesto ya leído: espacio de nombres, versión y líneas. */
    private static final class Manifiesto {
        String ns = NS_MANIFIESTO, version;
        final List<Fichero> ficheros = new ArrayList<>();

        Fichero buscar(String ruta) {
            for (Fichero f : ficheros) if (f.ruta.equals(ruta)) return f;
            return null;
        }
    }

    /** Datos de cifrado de una parte ya cifrada, para escribirlos en el manifiesto. */
    private static final class Cifra {
        byte[] datos, iv, sal, suma;
        long tam;
    }

    /**
     * Cifra un documento OpenDocument sin contraseña como lo hace LibreOffice en ODF 1.2/1.3 (lo
     * abren todos los LibreOffice desde 3.x y OpenOffice): cada parte, salvo mimetype y META-INF,
     * se comprime con deflate y se cifra con AES-256-CBC (IV propio), con la clave sacada por
     * PBKDF2-HMAC-SHA1 (100 000 vueltas, sal propia) del SHA-256 de la contraseña. Las miniaturas
     * se quitan, como hace LibreOffice. Devuelve el archivo nuevo.
     */
    static byte[] proteger(byte[] odfSinCifrar, String clave) throws Fallo {
        if (clave == null || clave.isEmpty()) throw new Fallo("La contraseña no puede estar vacía.");
        Paquete pq = leerZip(odfSinCifrar);
        byte[] man = pq.bytes(MANIFIESTO);
        if (man != null && new String(man, StandardCharsets.ISO_8859_1).contains("encryption-data")) {
            throw new Fallo(cifrado(pq) ? "El documento ya tiene contraseña."
                    : "El documento tiene partes cifradas (por ejemplo, macros con contraseña) y no se puede proteger.");
        }
        String mime = tipoMime(pq);
        Manifiesto m = leerManifiestoCompleto(man, mime);

        byte[] inicial = resumen("SHA-256", clave.getBytes(StandardCharsets.UTF_8));
        List<String> rutas = new ArrayList<>(), sinCifrar = new ArrayList<>();
        for (String r : pq.rutas()) {
            String b = r.toLowerCase(Locale.ROOT);
            if (r.equals("mimetype") || r.equals(MANIFIESTO)) continue;
            if (b.startsWith("meta-inf/")) {
                sinCifrar.add(r); // firmas y demás: LibreOffice tampoco las cifra
                continue;
            }
            if (b.startsWith("thumbnails/")) continue; // LibreOffice no guarda miniatura en los cifrados
            rutas.add(r);
        }
        SecureRandomHolder.iniciar();
        List<Cifra> cifras = new ArrayList<>();
        List<byte[]> listos = enParalelo(rutas.size(), i -> serializar(cifrar(pq.bytes(rutas.get(i)), inicial)));
        for (byte[] b : listos) {
            if (b == null) throw new Fallo("No se ha podido cifrar el documento.");
            cifras.add(deserializar(b));
        }

        // Manifiesto: las líneas que había (menos las miniaturas) y las partes que faltaban.
        Map<String, Cifra> porRuta = new java.util.HashMap<>();
        for (int i = 0; i < rutas.size(); i++) porRuta.put(rutas.get(i), cifras.get(i));
        m.ficheros.removeIf(f -> f.ruta.toLowerCase(Locale.ROOT).startsWith("thumbnails/"));
        for (String r : rutas) {
            if (m.buscar(r) == null) {
                Fichero f = new Fichero();
                f.ruta = r;
                f.atributos.put("media-type", r.endsWith(".xml") ? "text/xml" : "");
                m.ficheros.add(f);
            }
        }
        if (m.version == null || m.version.compareTo("1.2") < 0) m.version = "1.2";

        List<Object[]> salida = new ArrayList<>();
        for (String r : sinCifrar) salida.add(new Object[] {r, pq.bytes(r), false});
        for (int i = 0; i < rutas.size(); i++) salida.add(new Object[] {rutas.get(i), cifras.get(i).datos, true});
        salida.add(new Object[] {MANIFIESTO, escribirManifiesto(m, porRuta), false});
        return escribirZip(mime, salida);
    }

    /**
     * Quita la contraseña: devuelve el documento como un OpenDocument normal (mimetype primero y
     * sin comprimir, el resto con deflate y el manifiesto sin datos de cifrado). Sirve para los
     * cifrados por partes y para el paquete entero de LibreOffice 24.2. FalloClave si la
     * contraseña no es la buena.
     */
    static byte[] quitarClave(byte[] odfCifrado, String clave) throws Fallo {
        Paquete pq = leerZip(odfCifrado);
        if (!cifrado(pq)) throw new Fallo("El documento no tiene contraseña.");
        if (clave == null) throw FalloClave.para(null);
        comprobar(pq);
        Paquete limpio = descifrar(pq, clave, true);
        String mime = tipoMime(limpio);
        Manifiesto m = leerManifiestoCompleto(limpio.bytes(MANIFIESTO), mime);
        List<Object[]> salida = new ArrayList<>();
        for (String r : limpio.rutas()) {
            if (r.equals("mimetype") || r.equals(MANIFIESTO)) continue;
            salida.add(new Object[] {r, limpio.bytes(r), false});
        }
        salida.add(new Object[] {MANIFIESTO, escribirManifiesto(m, java.util.Collections.emptyMap()), false});
        return escribirZip(mime, salida);
    }

    private static Paquete leerZip(byte[] zip) throws Fallo {
        if (zip == null || zip.length == 0) throw new Fallo("El archivo está vacío.");
        try {
            return Paquete.leer(new ByteArrayInputStream(zip));
        } catch (IOException e) {
            throw new Fallo("El archivo está dañado o incompleto y no se puede abrir.");
        }
    }

    /** Tipo MIME del paquete (archivo mimetype); Fallo si no es OpenDocument. */
    private static String tipoMime(Paquete pq) throws Fallo {
        byte[] b = pq.bytes("mimetype");
        String m = b == null ? "" : new String(b, StandardCharsets.US_ASCII).trim();
        if (!m.startsWith("application/vnd.oasis.opendocument.") || m.length() > 200) {
            throw new Fallo("El archivo no es un documento OpenDocument (.odt, .ods, .odp, .odg).");
        }
        return m;
    }

    /** Cifra una parte: deflate, suma SHA-256 del primer KiB, PBKDF2 y AES-256-CBC. */
    private static Cifra cifrar(byte[] plano, byte[] inicial) throws Fallo {
        Cifra c = new Cifra();
        c.tam = plano.length;
        byte[] comprimido = deflar(plano);
        c.suma = resumen("SHA-256", Arrays.copyOf(comprimido, Math.min(1024, comprimido.length)));
        c.iv = SecureRandomHolder.bytes(16);
        c.sal = SecureRandomHolder.bytes(16);
        byte[] k = Pbkdf2.sha1(inicial, c.sal, ITERACIONES_NUEVAS, 32);
        try {
            Cipher ci = Cipher.getInstance("AES/CBC/PKCS5Padding"); // PKCS#7 es un relleno W3C válido
            ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(c.iv));
            c.datos = ci.doFinal(comprimido);
        } catch (GeneralSecurityException e) {
            throw new Fallo("Este dispositivo no permite cifrar con AES.");
        }
        return c;
    }

    // Las tareas en paralelo devuelven byte[]: la Cifra viaja empaquetada.
    private static byte[] serializar(Cifra c) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 16 + 16 + 32 + c.datos.length);
        b.putLong(c.tam).put(c.iv).put(c.sal).put(c.suma).put(c.datos);
        return b.array();
    }

    private static Cifra deserializar(byte[] a) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(a);
        Cifra c = new Cifra();
        c.tam = b.getLong();
        c.iv = new byte[16];
        c.sal = new byte[16];
        c.suma = new byte[32];
        b.get(c.iv).get(c.sal).get(c.suma);
        c.datos = new byte[b.remaining()];
        b.get(c.datos);
        return c;
    }

    private static byte[] deflar(byte[] d) {
        java.util.zip.Deflater df = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        try {
            df.setInput(d);
            df.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, d.length / 2));
            byte[] b = new byte[65536];
            while (!df.finished()) o.write(b, 0, df.deflate(b));
            return o.toByteArray();
        } finally {
            df.end();
        }
    }

    /** Generador aleatorio seguro compartido. */
    private static final class SecureRandomHolder {
        private static java.security.SecureRandom azar;

        static synchronized void iniciar() {
            if (azar == null) azar = new java.security.SecureRandom();
        }

        static synchronized byte[] bytes(int n) {
            iniciar();
            byte[] b = new byte[n];
            azar.nextBytes(b);
            return b;
        }
    }

    /** Lee las líneas del manifiesto (o crea uno mínimo si falta o no se entiende). */
    private static Manifiesto leerManifiestoCompleto(byte[] b, String mime) {
        Manifiesto m = new Manifiesto();
        try {
            XmlPullParser p = parserManifiesto(b);
            int t;
            while (p != null && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Lectura.nombre(p);
                if (n.equals("manifest")) {
                    String ns = p.getNamespace();
                    if (ns != null && !ns.isEmpty()) m.ns = ns;
                    m.version = Lectura.atr(p, "version");
                } else if (n.equals("file-entry")) {
                    Fichero f = new Fichero();
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String an = p.getAttributeName(i);
                        int k = an.indexOf(':');
                        String local = k >= 0 ? an.substring(k + 1) : an;
                        String ns = p.getAttributeNamespace(i);
                        if (ns != null && !ns.isEmpty() && !ns.equals(m.ns)) continue; // loext y demás
                        if (local.equals("full-path")) f.ruta = p.getAttributeValue(i);
                        else if (!local.equals("size")) f.atributos.put(local, p.getAttributeValue(i));
                    }
                    if (f.ruta != null) m.ficheros.add(f);
                }
            }
        } catch (Exception e) {
            m.ficheros.clear();
        }
        if (m.buscar("/") == null) {
            Fichero f = new Fichero();
            f.ruta = "/";
            f.atributos.put("media-type", mime);
            m.ficheros.add(0, f);
        }
        return m;
    }

    /** Escribe el manifiesto; las partes de "cifradas" llevan su manifest:encryption-data. */
    private static byte[] escribirManifiesto(Manifiesto m, Map<String, Cifra> cifradas) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<manifest:manifest xmlns:manifest=\"");
        Html.esc(sb, m.ns);
        sb.append('"');
        if (m.version != null) {
            sb.append(" manifest:version=\"");
            Html.esc(sb, m.version);
            sb.append('"');
        }
        sb.append(">\n");
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        for (Fichero f : m.ficheros) {
            sb.append(" <manifest:file-entry manifest:full-path=\"");
            Html.esc(sb, f.ruta);
            sb.append('"');
            for (Map.Entry<String, String> a : f.atributos.entrySet()) {
                sb.append(" manifest:").append(a.getKey()).append("=\"");
                Html.esc(sb, a.getValue());
                sb.append('"');
            }
            Cifra c = cifradas.get(f.ruta);
            if (c == null) {
                sb.append("/>\n");
                continue;
            }
            sb.append(" manifest:size=\"").append(c.tam).append("\">\n")
                    .append("  <manifest:encryption-data manifest:checksum-type=\"")
                    .append("urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha256-1k\" manifest:checksum=\"")
                    .append(b64.encodeToString(c.suma)).append("\">\n")
                    .append("   <manifest:algorithm manifest:algorithm-name=\"http://www.w3.org/2001/04/xmlenc#aes256-cbc\"")
                    .append(" manifest:initialisation-vector=\"").append(b64.encodeToString(c.iv)).append("\"/>\n")
                    .append("   <manifest:start-key-generation manifest:start-key-generation-name=\"")
                    .append("http://www.w3.org/2000/09/xmldsig#sha256\" manifest:key-size=\"32\"/>\n")
                    .append("   <manifest:key-derivation manifest:key-derivation-name=\"PBKDF2\" manifest:key-size=\"32\"")
                    .append(" manifest:iteration-count=\"").append(ITERACIONES_NUEVAS).append("\" manifest:salt=\"")
                    .append(b64.encodeToString(c.sal)).append("\"/>\n")
                    .append("  </manifest:encryption-data>\n </manifest:file-entry>\n");
        }
        sb.append("</manifest:manifest>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Escribe el zip: mimetype primero, sin comprimir y sin campos extra; después cada parte
     * ({ruta, datos, true si va STORED}); las demás con deflate.
     */
    private static byte[] escribirZip(String mime, List<Object[]> partes) throws Fallo {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(out, StandardCharsets.UTF_8)) {
            guardar(zo, "mimetype", mime.getBytes(StandardCharsets.US_ASCII), true);
            for (Object[] p : partes) guardar(zo, (String) p[0], (byte[]) p[1], (Boolean) p[2]);
        } catch (IOException | RuntimeException e) {
            throw new Fallo("No se ha podido escribir el documento.");
        }
        return out.toByteArray();
    }

    private static void guardar(java.util.zip.ZipOutputStream zo, String ruta, byte[] d, boolean sinComprimir)
            throws IOException {
        java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(ruta);
        if (sinComprimir) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(d);
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(d.length);
            e.setCompressedSize(d.length);
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(java.util.zip.ZipEntry.DEFLATED);
        }
        zo.putNextEntry(e);
        zo.write(d);
        zo.closeEntry();
    }

    // --- PBKDF2 con HMAC-SHA1 (SHA-1 propio: es mucho más rápido que Mac para 100 000 vueltas) ---

    static final class Pbkdf2 {
        private Pbkdf2() { }

        static byte[] sha1(byte[] clave, byte[] sal, int iteraciones, int largo) {
            byte[] k = clave.length > 64 ? resumen("SHA-1", clave) : clave;
            int[] ipad = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
            int[] opad = ipad.clone();
            int[] w = new int[80];
            byte[] bloque = new byte[64];
            for (int i = 0; i < 64; i++) bloque[i] = (byte) ((i < k.length ? k[i] : 0) ^ 0x36);
            comprimir(ipad, palabras(bloque, w), w);
            for (int i = 0; i < 64; i++) bloque[i] = (byte) ((i < k.length ? k[i] : 0) ^ 0x5C);
            comprimir(opad, palabras(bloque, w), w);

            byte[] r = new byte[largo];
            int[] u = new int[5], t = new int[5], h = new int[5];
            for (int b = 1, pos = 0; pos < largo; b++) {
                // U1 = HMAC(sal || INT(b)), con el estado de ipad y opad ya calculado.
                byte[] msg = Arrays.copyOf(sal, sal.length + 4);
                msg[sal.length] = (byte) (b >>> 24);
                msg[sal.length + 1] = (byte) (b >>> 16);
                msg[sal.length + 2] = (byte) (b >>> 8);
                msg[sal.length + 3] = (byte) b;
                interno(ipad, msg, h, w);
                externo(opad, h, u, w);
                System.arraycopy(u, 0, t, 0, 5);
                for (int i = 1; i < iteraciones; i++) {
                    externo(ipad, u, h, w); // HMAC de 20 bytes: mismo relleno que el paso externo
                    externo(opad, h, u, w);
                    for (int j = 0; j < 5; j++) t[j] ^= u[j];
                }
                for (int j = 0; j < 20 && pos < largo; j++, pos++) r[pos] = (byte) (t[j >> 2] >>> (24 - 8 * (j & 3)));
            }
            return r;
        }

        /** SHA-1 de (bloque ya procesado en "estado") + mensaje de longitud arbitraria. */
        private static void interno(int[] estado, byte[] msg, int[] out, int[] w) {
            int[] s = estado.clone();
            long bits = (64L + msg.length) * 8;
            int n = ((msg.length + 8) / 64 + 1) * 64;
            byte[] m = Arrays.copyOf(msg, n);
            m[msg.length] = (byte) 0x80;
            for (int i = 0; i < 8; i++) m[n - 1 - i] = (byte) (bits >>> (8 * i));
            byte[] blq = new byte[64];
            for (int off = 0; off < n; off += 64) {
                System.arraycopy(m, off, blq, 0, 64);
                comprimir(s, palabras(blq, w), w);
            }
            System.arraycopy(s, 0, out, 0, 5);
        }

        /** SHA-1 de (bloque ya procesado en "estado") + 20 bytes dados como 5 palabras. */
        private static void externo(int[] estado, int[] dato, int[] out, int[] w) {
            System.arraycopy(dato, 0, w, 0, 5);
            w[5] = 0x80000000;
            for (int i = 6; i < 15; i++) w[i] = 0;
            w[15] = (64 + 20) * 8;
            int[] s = {estado[0], estado[1], estado[2], estado[3], estado[4]};
            comprimir(s, w, w);
            System.arraycopy(s, 0, out, 0, 5);
        }

        private static int[] palabras(byte[] b, int[] w) {
            for (int i = 0; i < 16; i++) {
                w[i] = (b[4 * i] & 0xFF) << 24 | (b[4 * i + 1] & 0xFF) << 16 | (b[4 * i + 2] & 0xFF) << 8 | (b[4 * i + 3] & 0xFF);
            }
            return w;
        }

        private static void comprimir(int[] s, int[] x, int[] w) {
            if (x != w) System.arraycopy(x, 0, w, 0, 16);
            for (int i = 16; i < 80; i++) w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            int a = s[0], b = s[1], c = s[2], d = s[3], e = s[4];
            for (int i = 0; i < 80; i++) {
                int f, k;
                if (i < 20) {
                    f = (b & c) | (~b & d);
                    k = 0x5A827999;
                } else if (i < 40) {
                    f = b ^ c ^ d;
                    k = 0x6ED9EBA1;
                } else if (i < 60) {
                    f = (b & c) | (b & d) | (c & d);
                    k = 0x8F1BBCDC;
                } else {
                    f = b ^ c ^ d;
                    k = 0xCA62C1D6;
                }
                int t = Integer.rotateLeft(a, 5) + f + e + k + w[i];
                e = d;
                d = c;
                c = Integer.rotateLeft(b, 30);
                b = a;
                a = t;
            }
            s[0] += a;
            s[1] += b;
            s[2] += c;
            s[3] += d;
            s[4] += e;
        }
    }

    // --- Blowfish (no todos los Android lo traen) ---

    static final class Blowfish {
        /** Dígitos hexadecimales de la parte fraccionaria de pi: P (18 palabras) y S (4 × 256). */
        private static final String PI = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89452821e638d01377be5466cf34e90c6c"
                + "c0ac29b7c97c50dd3f84d5b5b54709179216d5d98979fb1bd1310ba698dfb5ac2ffd72dbd01adfb7b8e1afed6a267e96"
                + "ba7c9045f12c7f9924a19947b3916cf70801f2e2858efc16636920d871574e69a458fea3f4933d7e0d95748f728eb658"
                + "718bcd5882154aee7b54a41dc25a59b59c30d5392af26013c5d1b023286085f0ca417918b8db38ef8e79dcb0603a180e"
                + "6c9e0e8bb01e8a3ed71577c1bd314b2778af2fda55605c60e65525f3aa55ab945748986263e8144055ca396a2aab10b6"
                + "b4cc5c341141e8cea15486af7c72e993b3ee1411636fbc2a2ba9c55d741831f6ce5c3e169b87931eafd6ba336c24cf5c"
                + "7a325381289586773b8f48986b4bb9afc4bfe81b6628219361d809ccfb21a991487cac605dec8032ef845d5de98575b1"
                + "dc262302eb651b8823893e81d396acc50f6d6ff383f442392e0b4482a484200469c8f04a9e1f9b5e21c66842f6e96c9a"
                + "670c9c61abd388f06a51a0d2d8542f68960fa728ab5133a36eef0b6c137a3be4ba3bf0507efb2a98a1f1651d39af0176"
                + "66ca593e82430e888cee8619456f9fb47d84a5c33b8b5ebee06f75d885c12073401a449f56c16aa64ed3aa62363f7706"
                + "1bfedf72429b023d37d0d724d00a1248db0fead349f1c09b075372c980991b7b25d479d8f6e8def7e3fe501ab6794c3b"
                + "976ce0bd04c006bac1a94fb6409f60c45e5c9ec2196a246368fb6faf3e6c53b51339b2eb3b52ec6f6dfc511f9b30952c"
                + "cc814544af5ebd09bee3d004de334afd660f2807192e4bb3c0cba85745c8740fd20b5f39b9d3fbdb5579c0bd1a60320a"
                + "d6a100c6402c7279679f25fefb1fa3cc8ea5e9f8db3222f83c7516dffd616b152f501ec8ad0552ab323db5fafd238760"
                + "53317b483e00df829e5c57bbca6f8ca01a87562edf1769dbd542a8f6287effc3ac6732c68c4f5573695b27b0bbca58c8"
                + "e1ffa35db8f011a010fa3d98fd2183b84afcb56c2dd1d35b9a53e479b6f84565d28e49bc4bfb9790e1ddf2daa4cb7e33"
                + "62fb1341cee4c6e8ef20cada36774c01d07e9efe2bf11fb495dbda4dae909198eaad8e716b93d5a0d08ed1d0afc725e0"
                + "8e3c5b2f8e7594b78ff6e2fbf2122b648888b812900df01c4fad5ea0688fc31cd1cff191b3a8c1ad2f2f2218be0e1777"
                + "ea752dfe8b021fa1e5a0cc0fb56f74e818acf3d6ce89e299b4a84fe0fd13e0b77cc43b81d2ada8d9165fa26680957705"
                + "93cc7314211a1477e6ad206577b5fa86c75442f5fb9d35cfebcdaf0c7b3e89a0d6411bd3ae1e7e4900250e2d2071b35e"
                + "226800bb57b8e0af2464369bf009b91e5563911d59dfa6aa78c14389d95a537f207d5ba202e5b9c5832603766295cfa9"
                + "11c819684e734a41b3472dca7b14a94a1b5100529a532915d60f573fbc9bc6e42b60a47681e6740008ba6fb5571be91f"
                + "f296ec6b2a0dd915b6636521e7b9f9b6ff34052ec585566453b02d5da99f8fa108ba47996e85076a4b7a70e9b5b32944"
                + "db75092ec4192623ad6ea6b049a7df7d9cee60b88fedb266ecaa8c71699a17ff5664526cc2b19ee1193602a575094c29"
                + "a0591340e4183a3e3f54989a5b429d656b8fe4d699f73fd6a1d29c07efe830f54d2d38e6f0255dc14cdd20868470eb26"
                + "6382e9c6021ecc5e09686b3f3ebaefc93c9718146b6a70a1687f358452a0e286b79c5305aa5007373e07841c7fdeae5c"
                + "8e7d44ec5716f2b8b03ada37f0500c0df01c1f040200b3ffae0cf51a3cb574b225837a58dc0921bdd19113f97ca92ff6"
                + "9432477322f547013ae5e58137c2dadcc8b576349af3dda7a94461460fd0030eecc8c73ea4751e41e238cd993bea0e2f"
                + "3280bba1183eb3314e548b384f6db9086f420d03f60a04bf2cb8129024977c795679b072bcaf89afde9a771fd9930810"
                + "b38bae12dccf3f2e5512721f2e6b7124501adde69f84cd877a5847187408da17bc9f9abce94b7d8cec7aec3adb851dfa"
                + "63094366c464c3d2ef1c18473215d908dd433b3724c2ba1612a14d432a65c45150940002133ae4dd71dff89e10314e55"
                + "81ac77d65f11199b043556f1d7a3c76b3c11183b5924a509f28fe6ed97f1fbfa9ebabf2c1e153c6e86e34570eae96fb1"
                + "860e5e0a5a3e2ab3771fe71c4e3d06fa2965dcb999e71d0f803e89d65266c8252e4cc9789c10b36ac6150eba94e2ea78"
                + "a5fc3c531e0a2df4f2f74ea7361d2b3d1939260f19c279605223a708f71312b6ebadfe6eeac31f66e3bc4595a67bc883"
                + "b17f37d1018cff28c332ddefbe6c5aa56558218568ab9802eecea50fdb2f953b2aef7dad5b6e2f841521b62829076170"
                + "ecdd4775619f151013cca830eb61bd960334fe1eaa0363cfb5735c904c70a239d59e9e0bcbaade14eecc86bc60622ca7"
                + "9cab5cabb2f3846e648b1eaf19bdf0caa02369b9655abb5040685a323c2ab4b3319ee9d5c021b8f79b540b19875fa099"
                + "95f7997e623d7da8f837889a97e32d7711ed935f166812810e358829c7e61fd696dedfa17858ba9957f584a51b227263"
                + "9b83c3ff1ac24696cdb30aeb532e30548fd948e46dbc312858ebf2ef34c6ffeafe28ed61ee7c3c735d4a14d9e864b7e3"
                + "42105d14203e13e045eee2b6a3aaabeadb6c4f15facb4fd0c742f442ef6abbb5654f3b1d41cd2105d81e799e86854dc7"
                + "e44b476a3d816250cf62a1f25b8d2646fc8883a0c1c7b6a37f1524c369cb749247848a0b5692b285095bbf00ad19489d"
                + "1462b17423820e0058428d2a0c55f5ea1dadf43e233f70613372f0928d937e41d65fecf16c223bdb7cde3759cbee7460"
                + "4085f2a7ce77326ea607808419f8509ee8efd85561d99735a969a7aac50c06c25a04abfc800bcadc9e447a2ec3453484"
                + "fdd567050e1e9ec9db73dbd3105588cd675fda79e3674340c5c43465713e38d83d28f89ef16dff20153e21e78fb03d4a"
                + "e6e39f2bdb83adf7e93d5a68948140f7f64c261c94692934411520f77602d4f7bcf46b2ed4a20068d40824713320f46a"
                + "43b7d4b7500061af1e39f62e9724454614214f74bf8b88404d95fc1d96b591af70f4ddd366a02f45bfbc09ec03bd9785"
                + "7fac6dd031cb850496eb27b355fd3941da2547e6abca0a9a28507825530429f40a2c86dae9b66dfb68dc1462d7486900"
                + "680ec0a427a18dee4f3ffea2e887ad8cb58ce0067af4d6b6aace1e7cd3375fecce78a399406b2a4220fe9e35d9f385b9"
                + "ee39d7ab3b124e8b1dc9faf74b6d185626a36631eae397b23a6efa74dd5b43326841e7f7ca7820fbfb0af54ed8feb397"
                + "454056acba48952755533a3a20838d87fe6ba9b7d096954b55a867bca1159a58cca9296399e1db33a62a4a563f3125f9"
                + "5ef47e1c9029317cfdf8e80204272f7080bb155c05282ce395c11548e4c66d2248c1133fc70f86dc07f9c9ee41041f0f"
                + "404779a45d886e17325f51ebd59bc0d1f2bcc18f41113564257b7834602a9c60dff8e8a31f636c1b0e12b4c202e1329e"
                + "af664fd1cad181156b2395e0333e92e13b240b62eebeb92285b2a20ee6ba0d99de720c8c2da2f728d012784595b794fd"
                + "647d0862e7ccf5f05449a36f877d48fac39dfd27f33e8d1e0a476341992eff743a6f6eabf4f8fd37a812dc60a1ebddf8"
                + "991be14cdb6e6b0dc67b55106d672c372765d43bdcd0e804f1290dc7cc00ffa3b5390f92690fed0b667b9ffbcedb7d9c"
                + "a091cf0bd9155ea3bb132f88515bad247b9479bf763bd6eb37392eb3cc1159798026e297f42e312d6842ada7c66a2b3b"
                + "12754ccc782ef11c6a124237b79251e706a1bbe64bfb63501a6b101811caedfa3d25bdd8e2e1c3c9444216590a121386"
                + "d90cec6ed5abea2a64af674eda86a85fbebfe98864e4c3fe9dbc8057f0f7c08660787bf86003604dd1fd8346f6381fb0"
                + "7745ae04d736fccc83426b33f01eab71b08041873c005e5f77a057bebde8ae2455464299bf582e614e58f48ff2ddfda2"
                + "f474ef388789bdc25366f9c3c8b38e74b475f25546fcd9b97aeb26618b1ddf84846a0e79915f95e2466e598e20b45770"
                + "8cd55591c902de4cb90bace1bb8205d011a862487574a99eb77f19b6e0a9dc09662d09a1c4324633e85a1f0209f0be8c"
                + "4a99a0251d6efe101ab93d1d0ba5a4dfa186f20f2868f169dcb7da83573906fea1e2ce9b4fcd7f5250115e01a70683fa"
                + "a002b5c40de6d0279af88c27773f8641c3604c0661a806b5f0177a28c0f586e0006058aa30dc7d6211e69ed72338ea63"
                + "53c2dd94c2c21634bbcbee5690bcb6deebfc7da1ce591d766f05e4094b7c018839720a3d7c927c2486e3725f724d9db9"
                + "1ac15bb4d39eb8fced54557808fca5b5d83d7cd34dad0fc41e50ef5eb161e6f8a28514d96c51133c6fd5c7e756e14ec4"
                + "362abfceddc6c837d79a323492638212670efa8e406000e03a39ce37d3faf5cfabc277375ac52d1b5cb0679e4fa33742"
                + "d382274099bc9bbed5118e9dbf0f7315d62d1c7ec700c47bb78c1b6b21a19045b26eb1be6a366eb45748ab2fbc946e79"
                + "c6a376d26549c2c8530ff8ee468dde7dd5730a1d4cd04dc62939bbdba9ba4650ac9526e8be5ee304a1fad5f06a2d519a"
                + "63ef8ce29a86ee22c089c2b843242ef6a51e03aa9cf2d0a483c061ba9be96a4d8fe51550ba645bd62826a2f9a73a3ae1"
                + "4ba99586ef5562e9c72fefd3f752f7da3f046f6977fa0a5980e4a91587b086019b09e6ad3b3ee593e990fd5a9e34d797"
                + "2cf0b7d9022b8b5196d5ac3a017da67dd1cf3ed67c7d2d281f9f25cfadf2b89b5ad6b4725a88f54ce029ac71e019a5e6"
                + "47b0acfded93fa9be8d3c48d283b57ccf8d5662979132e28785f0191ed756055f7960e44e3d35e8c15056dd488f46dba"
                + "03a161250564f0bdc3eb9e153c9057a297271aeca93a072a1b3f6d9b1e6321f5f59c66fb26dcf3197533d928b155fdf5"
                + "035634828aba3cbb28517711c20ad9f8abcc5167ccad925f4de817513830dc8e379d58629320f991ea7a90c2fb3e7bce"
                + "5121ce64774fbe32a8b6e37ec3293d4648de53696413e680a2ae0810dd6db22469852dfd09072166b39a460a6445c0dd"
                + "586cdecf1c20c8ae5bbef7dd1b588d40ccd2017f6bb4e3bbdda26a7e3a59ff453e350a44bcb4cdd572eacea8fa6484bb"
                + "8d6612aebf3c6f47d29be463542f5d9eaec2771bf64e6370740e0d8de75b1357f8721671af537d5d4040cb084eb4e2cc"
                + "34d2466a0115af84e1b0042895983a1d06b89fb4ce6ea0486f3f3b823520ab82011a1d4b277227f8611560b1e7933fdc"
                + "bb3a792b344525bda08839e151ce794b2f32c9b7a01fbac9e01cc87ebcc7d1f6cf0111c3a1e8aac71a908749d44fbd9a"
                + "d0dadecbd50ada380339c32ac69136678df9317ce0b12b4ff79e59b743f5bb3af2d519ff27d9459cbf97222c15e6fc2a"
                + "0f91fc719b941525fae59361ceb69cebc2a8645912baa8d1b6c1075ee3056a0c10d25065cb03a442e0ec6e0e1698db3b"
                + "4c98a0be3278e9649f1f9532e0d392dfd3a0342b8971f21e1b0a74414ba3348cc5be7120c37632d8df359f8d9b992f2e"
                + "e60b6f470fe3f11de54cda541edad891ce6279cfcd3e7e6f1618b166fd2c1d05848fd2c5f6fb2299f523f357a6327623"
                + "93a8353156cccd02acf081625a75ebb56e16369788d273ccde96629281b949d04c50901b71c65614e6c6c7bd327a140a"
                + "45e1d006c3f27b9ac9aa53fd62a80f00bb25bfe235bdd2f671126905b2040222b6cbcf7ccd769c2b53113ec01640e3d3"
                + "38abbd602547adf0ba38209cf746ce7677afa1c52075606085cbfe4e8ae88dd87aaaf9b04cf9aa7e1948c25c02fb8a8c"
                + "01c36ae4d6ebe1f990d4f869a65cdea03f09252dc208e69fb74e6132ce77e25b578fdfe33ac372e6";

        private static final int[] PI_P = new int[18];
        private static final int[][] PI_S = new int[4][256];

        static {
            int k = 0;
            for (int i = 0; i < 18; i++, k++) PI_P[i] = (int) Long.parseLong(PI.substring(8 * k, 8 * k + 8), 16);
            for (int j = 0; j < 4; j++) {
                for (int i = 0; i < 256; i++, k++) PI_S[j][i] = (int) Long.parseLong(PI.substring(8 * k, 8 * k + 8), 16);
            }
        }

        private final int[] p = PI_P.clone();
        private final int[][] s = {PI_S[0].clone(), PI_S[1].clone(), PI_S[2].clone(), PI_S[3].clone()};

        Blowfish(byte[] clave) {
            if (clave.length == 0) throw new IllegalArgumentException("clave vacía");
            int pos = 0;
            for (int i = 0; i < 18; i++) {
                int d = 0;
                for (int j = 0; j < 4; j++) {
                    d = (d << 8) | (clave[pos] & 0xFF);
                    pos = (pos + 1) % clave.length;
                }
                p[i] ^= d;
            }
            int[] lr = {0, 0};
            for (int i = 0; i < 18; i += 2) {
                cifrar(lr);
                p[i] = lr[0];
                p[i + 1] = lr[1];
            }
            for (int j = 0; j < 4; j++) {
                for (int i = 0; i < 256; i += 2) {
                    cifrar(lr);
                    s[j][i] = lr[0];
                    s[j][i + 1] = lr[1];
                }
            }
        }

        private int f(int x) {
            return ((s[0][x >>> 24] + s[1][(x >>> 16) & 0xFF]) ^ s[2][(x >>> 8) & 0xFF]) + s[3][x & 0xFF];
        }

        void cifrar(int[] lr) {
            int l = lr[0], r = lr[1];
            for (int i = 0; i < 16; i += 2) {
                l ^= p[i];
                r ^= f(l);
                r ^= p[i + 1];
                l ^= f(r);
            }
            lr[0] = r ^ p[17];
            lr[1] = l ^ p[16];
        }

        /**
         * Descifrado CFB con realimentación de bloque entero (64 bits) byte a byte, que es lo que
         * hace rtl_cipher de OpenOffice/LibreOffice aunque el manifiesto lo llame "CFB 8".
         */
        byte[] cfb(byte[] datos, byte[] iv) {
            byte[] reg = iv.clone();
            byte[] r = new byte[datos.length];
            int[] lr = new int[2];
            for (int n = 0; n < datos.length; n++) {
                int k = n & 7;
                if (k == 0) {
                    lr[0] = (reg[0] & 0xFF) << 24 | (reg[1] & 0xFF) << 16 | (reg[2] & 0xFF) << 8 | (reg[3] & 0xFF);
                    lr[1] = (reg[4] & 0xFF) << 24 | (reg[5] & 0xFF) << 16 | (reg[6] & 0xFF) << 8 | (reg[7] & 0xFF);
                    cifrar(lr);
                    for (int i = 0; i < 4; i++) {
                        reg[i] = (byte) (lr[0] >>> (24 - 8 * i));
                        reg[4 + i] = (byte) (lr[1] >>> (24 - 8 * i));
                    }
                }
                byte c = datos[n];
                r[n] = (byte) (c ^ reg[k]);
                reg[k] = c;
            }
            return r;
        }
    }

    // --- Argon2id (RFC 9106) y BLAKE2b ---

    static final class Argon2 {
        private Argon2() { }

        private static final int SINC = 4, PALABRAS = 128;

        static byte[] argon2id(byte[] clave, byte[] sal, byte[] secreto, byte[] asociado, int t, int mKiB, int carriles,
                               int largo) {
            Blake2b h = new Blake2b(64);
            h.int32(carriles).int32(largo).int32(mKiB).int32(t).int32(0x13).int32(2);
            h.bloque(clave).bloque(sal).bloque(secreto).bloque(asociado);
            byte[] h0 = h.fin();

            int segmento = mKiB / (SINC * carriles);
            int carril = segmento * SINC;
            long[][] mem = new long[carriles][carril * PALABRAS];
            byte[] semilla = Arrays.copyOf(h0, 72);
            for (int l = 0; l < carriles; l++) {
                for (int i = 0; i < 2; i++) {
                    le32(semilla, 64, i);
                    le32(semilla, 68, l);
                    byte[] b = hashLargo(semilla, 1024);
                    for (int w = 0; w < PALABRAS; w++) mem[l][i * PALABRAS + w] = le64(b, 8 * w);
                }
            }
            for (int pasada = 0; pasada < t; pasada++) {
                for (int corte = 0; corte < SINC; corte++) {
                    final int ps = pasada, cs = corte;
                    if (carriles == 1) {
                        segmento(mem, 0, ps, cs, carriles, carril, segmento, t, mKiB);
                        continue;
                    }
                    Thread[] hilos = new Thread[carriles];
                    RuntimeException[] error = new RuntimeException[1];
                    for (int l = 0; l < carriles; l++) {
                        final int lc = l;
                        hilos[l] = new Thread(() -> {
                            try {
                                segmento(mem, lc, ps, cs, carriles, carril, segmento, t, mKiB);
                            } catch (RuntimeException err) {
                                error[0] = err;
                            }
                        });
                        hilos[l].start();
                    }
                    for (Thread th : hilos) {
                        try {
                            th.join();
                        } catch (InterruptedException err) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(err);
                        }
                    }
                    if (error[0] != null) throw error[0];
                }
            }
            long[] c = Arrays.copyOfRange(mem[0], (carril - 1) * PALABRAS, carril * PALABRAS);
            for (int l = 1; l < carriles; l++) {
                for (int w = 0; w < PALABRAS; w++) c[w] ^= mem[l][(carril - 1) * PALABRAS + w];
            }
            byte[] cb = new byte[1024];
            for (int w = 0; w < PALABRAS; w++) le64(cb, 8 * w, c[w]);
            return hashLargo(cb, largo);
        }

        private static void segmento(long[][] mem, int carrilAct, int pasada, int corte, int carriles, int carril,
                                     int segmento, int t, int bloquesTotales) {
            boolean independiente = pasada == 0 && corte < SINC / 2;
            long[] direcciones = new long[PALABRAS], entrada = new long[PALABRAS], cero = new long[PALABRAS];
            long[] tmp = new long[PALABRAS], z = new long[PALABRAS];
            if (independiente) {
                entrada[0] = pasada;
                entrada[1] = carrilAct;
                entrada[2] = corte;
                entrada[3] = (long) carril * carriles;
                entrada[4] = t;
                entrada[5] = 2;
            }
            int inicio = 0;
            if (pasada == 0 && corte == 0) {
                inicio = 2;
                if (independiente) siguientesDirecciones(direcciones, entrada, cero, tmp, z);
            }
            long[] m = mem[carrilAct];
            int actual = corte * segmento + inicio;
            int previo = actual == 0 ? carril - 1 : actual - 1;
            for (int i = inicio; i < segmento; i++, actual++, previo++) {
                if (actual % carril == 1) previo = actual - 1;
                long aleatorio;
                if (independiente) {
                    if (i % PALABRAS == 0) siguientesDirecciones(direcciones, entrada, cero, tmp, z);
                    aleatorio = direcciones[i % PALABRAS];
                } else {
                    aleatorio = m[previo * PALABRAS];
                }
                int refCarril = (int) ((aleatorio >>> 32) % carriles);
                if (pasada == 0 && corte == 0) refCarril = carrilAct;
                boolean mismo = refCarril == carrilAct;
                long area;
                if (pasada == 0) {
                    if (corte == 0) area = i - 1;
                    else if (mismo) area = (long) corte * segmento + i - 1;
                    else area = (long) corte * segmento + (i == 0 ? -1 : 0);
                } else {
                    if (mismo) area = carril - segmento + i - 1;
                    else area = carril - segmento + (i == 0 ? -1 : 0);
                }
                long rel = aleatorio & 0xFFFFFFFFL;
                rel = (rel * rel) >>> 32;
                rel = area - 1 - ((area * rel) >>> 32);
                long origen = pasada == 0 ? 0 : (corte == SINC - 1 ? 0 : (long) (corte + 1) * segmento);
                int ref = (int) ((origen + rel) % carril);
                rellenar(m, previo * PALABRAS, mem[refCarril], ref * PALABRAS, m, actual * PALABRAS, pasada > 0, tmp, z);
            }
        }

        private static void siguientesDirecciones(long[] direcciones, long[] entrada, long[] cero, long[] tmp, long[] z) {
            entrada[6]++;
            rellenar(cero, 0, entrada, 0, direcciones, 0, false, tmp, z);
            rellenar(cero, 0, direcciones, 0, direcciones, 0, false, tmp, z);
        }

        /** next = G(prev, ref) (o XOR con el bloque anterior en las pasadas siguientes). */
        private static void rellenar(long[] a, int ao, long[] b, int bo, long[] d, int doff, boolean xor, long[] r,
                                     long[] z) {
            for (int i = 0; i < PALABRAS; i++) {
                r[i] = a[ao + i] ^ b[bo + i];
                z[i] = xor ? r[i] ^ d[doff + i] : r[i];
            }
            for (int i = 0; i < 8; i++) {
                ronda(r, 16 * i, 16 * i + 1, 16 * i + 2, 16 * i + 3, 16 * i + 4, 16 * i + 5, 16 * i + 6, 16 * i + 7,
                        16 * i + 8, 16 * i + 9, 16 * i + 10, 16 * i + 11, 16 * i + 12, 16 * i + 13, 16 * i + 14, 16 * i + 15);
            }
            for (int i = 0; i < 8; i++) {
                ronda(r, 2 * i, 2 * i + 1, 2 * i + 16, 2 * i + 17, 2 * i + 32, 2 * i + 33, 2 * i + 48, 2 * i + 49,
                        2 * i + 64, 2 * i + 65, 2 * i + 80, 2 * i + 81, 2 * i + 96, 2 * i + 97, 2 * i + 112, 2 * i + 113);
            }
            for (int i = 0; i < PALABRAS; i++) d[doff + i] = z[i] ^ r[i];
        }

        private static void ronda(long[] v, int v0, int v1, int v2, int v3, int v4, int v5, int v6, int v7, int v8,
                                  int v9, int v10, int v11, int v12, int v13, int v14, int v15) {
            g(v, v0, v4, v8, v12);
            g(v, v1, v5, v9, v13);
            g(v, v2, v6, v10, v14);
            g(v, v3, v7, v11, v15);
            g(v, v0, v5, v10, v15);
            g(v, v1, v6, v11, v12);
            g(v, v2, v7, v8, v13);
            g(v, v3, v4, v9, v14);
        }

        private static long bla(long x, long y) {
            return x + y + 2 * (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL);
        }

        private static void g(long[] v, int a, int b, int c, int d) {
            v[a] = bla(v[a], v[b]);
            v[d] = Long.rotateRight(v[d] ^ v[a], 32);
            v[c] = bla(v[c], v[d]);
            v[b] = Long.rotateRight(v[b] ^ v[c], 24);
            v[a] = bla(v[a], v[b]);
            v[d] = Long.rotateRight(v[d] ^ v[a], 16);
            v[c] = bla(v[c], v[d]);
            v[b] = Long.rotateRight(v[b] ^ v[c], 63);
        }

        /** H' de Argon2: BLAKE2b de longitud variable. */
        static byte[] hashLargo(byte[] x, int largo) {
            byte[] pre = new byte[4];
            le32(pre, 0, largo);
            if (largo <= 64) return new Blake2b(largo).mas(pre).mas(x).fin();
            byte[] r = new byte[largo];
            byte[] v = new Blake2b(64).mas(pre).mas(x).fin();
            System.arraycopy(v, 0, r, 0, 32);
            int pos = 32;
            while (largo - pos > 64) {
                v = new Blake2b(64).mas(v).fin();
                System.arraycopy(v, 0, r, pos, 32);
                pos += 32;
            }
            v = new Blake2b(largo - pos).mas(v).fin();
            System.arraycopy(v, 0, r, pos, largo - pos);
            return r;
        }

        static void le32(byte[] b, int o, int v) {
            b[o] = (byte) v;
            b[o + 1] = (byte) (v >>> 8);
            b[o + 2] = (byte) (v >>> 16);
            b[o + 3] = (byte) (v >>> 24);
        }

        static long le64(byte[] b, int o) {
            long r = 0;
            for (int i = 7; i >= 0; i--) r = (r << 8) | (b[o + i] & 0xFF);
            return r;
        }

        static void le64(byte[] b, int o, long v) {
            for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i));
        }
    }

    /** BLAKE2b sin clave (RFC 7693). */
    static final class Blake2b {
        private static final long[] IV = {
                0x6A09E667F3BCC908L, 0xBB67AE8584CAA73BL, 0x3C6EF372FE94F82BL, 0xA54FF53A5F1D36F1L,
                0x510E527FADE682D1L, 0x9B05688C2B3E6C1FL, 0x1F83D9ABFB41BD6BL, 0x5BE0CD19137E2179L};
        private static final byte[][] SIGMA = {
                {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
                {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
                {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
                {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
                {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
                {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
                {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
                {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
                {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

        private final long[] h = IV.clone();
        private final byte[] buf = new byte[128];
        private final long[] m = new long[16], v = new long[16];
        private int n;
        private long contador;
        private final int largo;

        Blake2b(int largo) {
            this.largo = largo;
            h[0] ^= 0x01010000L ^ largo;
        }

        Blake2b mas(byte[] d) {
            for (byte x : d) {
                if (n == 128) {
                    contador += 128;
                    comprimir(false);
                    n = 0;
                }
                buf[n++] = x;
            }
            return this;
        }

        Blake2b int32(int x) {
            byte[] b = new byte[4];
            Argon2.le32(b, 0, x);
            return mas(b);
        }

        /** Longitud (LE32) y contenido; null cuenta como vacío. */
        Blake2b bloque(byte[] d) {
            if (d == null) return int32(0);
            return int32(d.length).mas(d);
        }

        byte[] fin() {
            contador += n;
            Arrays.fill(buf, n, 128, (byte) 0);
            comprimir(true);
            byte[] r = new byte[largo];
            for (int i = 0; i < largo; i++) r[i] = (byte) (h[i >> 3] >>> (8 * (i & 7)));
            return r;
        }

        private void comprimir(boolean ultimo) {
            for (int i = 0; i < 16; i++) m[i] = Argon2.le64(buf, 8 * i);
            System.arraycopy(h, 0, v, 0, 8);
            System.arraycopy(IV, 0, v, 8, 8);
            v[12] ^= contador;
            if (ultimo) v[14] = ~v[14];
            for (int r = 0; r < 12; r++) {
                byte[] s = SIGMA[r % 10];
                mezclar(0, 4, 8, 12, m[s[0]], m[s[1]]);
                mezclar(1, 5, 9, 13, m[s[2]], m[s[3]]);
                mezclar(2, 6, 10, 14, m[s[4]], m[s[5]]);
                mezclar(3, 7, 11, 15, m[s[6]], m[s[7]]);
                mezclar(0, 5, 10, 15, m[s[8]], m[s[9]]);
                mezclar(1, 6, 11, 12, m[s[10]], m[s[11]]);
                mezclar(2, 7, 8, 13, m[s[12]], m[s[13]]);
                mezclar(3, 4, 9, 14, m[s[14]], m[s[15]]);
            }
            for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
        }

        private void mezclar(int a, int b, int c, int d, long x, long y) {
            v[a] = v[a] + v[b] + x;
            v[d] = Long.rotateRight(v[d] ^ v[a], 32);
            v[c] = v[c] + v[d];
            v[b] = Long.rotateRight(v[b] ^ v[c], 24);
            v[a] = v[a] + v[b] + y;
            v[d] = Long.rotateRight(v[d] ^ v[a], 16);
            v[c] = v[c] + v[d];
            v[b] = Long.rotateRight(v[b] ^ v[c], 63);
        }
    }
}
