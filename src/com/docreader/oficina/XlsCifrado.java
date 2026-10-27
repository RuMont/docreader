package com.docreader.oficina;

/**
 * Libros .xls protegidos con contraseña ([MS-XLS] 2.2.10, [MS-OFFCRYPTO] 2.3.6–2.3.7): RC4
 * (Office 97/2000), RC4 CryptoAPI y la ofuscación XOR de Excel 95. Se descifra una copia del flujo
 * entero recorriendo los registros: las cabeceras (tipo y longitud) nunca van cifradas, y algunos
 * registros tampoco. La posición en el cifrado es el desplazamiento del byte en el flujo.
 * Sin dependencias de Android.
 */
final class XlsCifrado {
    private static final int FILEPASS = 0x002F, BOF = 0x0809, BOUNDSHEET = 0x0085;
    private static final int TAM_BLOQUE = 1024;

    private XlsCifrado() { }

    /** Registros que nunca se cifran: BOF, FILEPASS, INTERFACEHDR, USREXCL, FILELOCK, RRDINFO, RRDHEAD. */
    private static boolean enClaro(int t) {
        return t == BOF || t == FILEPASS || t == 0x00E1 || t == 0x0194 || t == 0x0195 || t == 0x0196 || t == 0x0138;
    }

    /**
     * Devuelve el flujo descifrado, o el mismo si no está cifrado. Sin contraseña se prueba la de
     * Excel por defecto (libros protegidos solo contra cambios); si no vale, {@link FalloClave}.
     */
    static byte[] descifrar(byte[] d, boolean biff8, String clave) throws Fallo {
        // FILEPASS va al principio de los globales, antes de fuentes, formatos y hojas.
        int fp = -1, largo = 0, pos = 0, vistos = 0;
        while (pos <= d.length - 4 && vistos++ < 64) {
            int t = Cfb.u16(d, pos), l = Cfb.u16(d, pos + 2);
            if (t == FILEPASS) {
                fp = pos + 4;
                largo = Math.min(l, d.length - fp);
                break;
            }
            if (t == 0x000A || t == 0x0031 || t == 0x00E0 || t == 0x041E || t == BOUNDSHEET) break;
            pos += 4 + l;
        }
        if (fp < 0) return d;

        boolean xor = !biff8 || largo < 6 || Cfb.u16(d, fp) == 0;
        if (xor) {
            int o = biff8 && largo >= 6 ? fp + 2 : fp; // en BIFF5 no hay tipo de cifrado
            if (o + 4 > d.length) throw new Fallo("El libro está dañado y no se puede leer.");
            for (String c : Cripto.intentos(clave)) {
                byte[] tabla = tablaXor(c, Cfb.u16(d, o), Cfb.u16(d, o + 2));
                if (tabla != null) return aplicar(d, null, tabla);
            }
            throw FalloClave.para(clave);
        }
        if (Cfb.u16(d, fp) != 1 || largo < 6) {
            throw new Fallo("Este libro usa un cifrado que no se puede abrir.");
        }
        int mayor = Cfb.u16(d, fp + 2), menor = Cfb.u16(d, fp + 4);
        boolean clasico = mayor == 1 && menor == 1;
        if (!clasico && !(menor == 2 && mayor >= 2 && mayor <= 4)) {
            throw new Fallo("Este libro usa un cifrado que no se puede abrir.");
        }
        // Copia del FILEPASS: los lectores de Cripto no conocen el final del registro.
        byte[] cab = java.util.Arrays.copyOfRange(d, fp + 6, fp + largo);
        if (clasico ? cab.length < 48 : !cabeceraCryptoApi(cab)) throw new Fallo("El libro está dañado y no se puede leer.");
        for (String c : Cripto.intentos(clave)) {
            Cripto.ClaveRc4 k = clasico ? Cripto.rc4Clasico(cab, 0, c) : Cripto.rc4CryptoApi(cab, 0, c);
            if (k != null) return aplicar(d, k, null);
        }
        throw FalloClave.para(clave);
    }

    /**
     * ¿Caben en el registro la cabecera y el verificador de CryptoAPI? (flags, tamaño de la
     * cabecera, cabecera, tamaño de la sal, sal, verificador de 16, tamaño del hash, hash), y
     * es razonable el tamaño de la clave? Cripto no comprueba esos tamaños.
     */
    private static boolean cabeceraCryptoApi(byte[] c) {
        if (c.length < 8) return false;
        long v = 8L + (Cfb.i32(c, 4) & 0xFFFFFFFFL);
        if (v + 4 > c.length || v < 28) return false;
        int bits = Cfb.i32(c, 24); // tamaño de la clave: 0 (= 40) o de 40 a 128 bits
        if (bits != 0 && (bits < 40 || bits > 128 || bits % 8 != 0)) return false;
        long tamSal = Cfb.i32(c, (int) v) & 0xFFFFFFFFL;
        long h = v + 4 + tamSal + 16;
        if (h + 4 > c.length) return false;
        return h + 4 + (Cfb.i32(c, (int) h) & 0xFFFFFFFFL) <= c.length;
    }

    /** Copia descifrada del flujo, con RC4 ("k") o con la tabla XOR. */
    private static byte[] aplicar(byte[] d, Cripto.ClaveRc4 k, byte[] tabla) {
        byte[] r = d.clone();
        byte[] flujo = new byte[TAM_BLOQUE];
        int bloque = -1, pos = 0;
        while (pos <= r.length - 4) {
            int t = Cfb.u16(r, pos), l = Cfb.u16(r, pos + 2);
            int ini = pos + 4, fin = Math.min(r.length, ini + l);
            if (!enClaro(t)) {
                int desde = t == BOUNDSHEET ? ini + 4 : ini; // lbPlyPos va en claro
                for (int p = desde; p < fin; p++) {
                    if (tabla != null) {
                        // Índice = (posición en el flujo + longitud de los datos) mod 16; después del
                        // XOR, el byte se rota 3 bits a la izquierda (deshace la rotación del cifrado).
                        int v = (r[p] & 0xFF) ^ (tabla[(p + l) & 0x0F] & 0xFF);
                        r[p] = (byte) ((v >>> 5) | (v << 3));
                    } else {
                        if (p / TAM_BLOQUE != bloque) {
                            bloque = p / TAM_BLOQUE;
                            java.util.Arrays.fill(flujo, (byte) 0);
                            k.bloque(bloque).aplicar(flujo, 0, TAM_BLOQUE);
                        }
                        r[p] ^= flujo[p % TAM_BLOQUE];
                    }
                }
            }
            pos = ini + l;
        }
        return r;
    }

    // --- Ofuscación XOR, método 1 ([MS-OFFCRYPTO] 2.3.7.1–2.3.7.4) ---

    private static final int[] CODIGO_INICIAL = {0xE1F0, 0x1D0F, 0xCC9C, 0x84C0, 0x110C, 0x0E10, 0xF1CE,
            0x313E, 0x1872, 0xE139, 0xD40F, 0x84F9, 0x280C, 0xA96A, 0x4EC3};

    private static final int[] MATRIZ = {
            0xAEFC, 0x4DD9, 0x9BB2, 0x2745, 0x4E8A, 0x9D14, 0x2A09, 0x7B61, 0xF6C2, 0xFDA5, 0xEB6B, 0xC6F7,
            0x9DCF, 0x2BBF, 0x4563, 0x8AC6, 0x05AD, 0x0B5A, 0x16B4, 0x2D68, 0x5AD0, 0x0375, 0x06EA, 0x0DD4,
            0x1BA8, 0x3750, 0x6EA0, 0xDD40, 0xD849, 0xA0B3, 0x5147, 0xA28E, 0x553D, 0xAA7A, 0x44D5, 0x6F45,
            0xDE8A, 0xAD35, 0x4A4B, 0x9496, 0x390D, 0x721A, 0xEB23, 0xC667, 0x9CEF, 0x29FF, 0x53FE, 0xA7FC,
            0x5FD9, 0x47D3, 0x8FA6, 0x0F6D, 0x1EDA, 0x3DB4, 0x7B68, 0xF6D0, 0xB861, 0x60E3, 0xC1C6, 0x93AD,
            0x377B, 0x6EF6, 0xDDEC, 0x45A0, 0x8B40, 0x06A1, 0x0D42, 0x1A84, 0x3508, 0x6A10, 0xAA51, 0x4483,
            0x8906, 0x022D, 0x045A, 0x08B4, 0x1168, 0x76B4, 0xED68, 0xCAF1, 0x85C3, 0x1BA7, 0x374E, 0x6E9C,
            0x3730, 0x6E60, 0xDCC0, 0xA9A1, 0x4363, 0x86C6, 0x1DAD, 0x3331, 0x6662, 0xCCC4, 0x89A9, 0x0373,
            0x06E6, 0x0DCC, 0x1021, 0x2042, 0x4084, 0x8108, 0x1231, 0x2462, 0x48C4};

    private static final int[] RELLENO = {0xBB, 0xFF, 0xFF, 0xBA, 0xFF, 0xFF, 0xB9, 0x80, 0x00, 0xBE, 0x0F,
            0x00, 0xBF, 0x0F, 0x00};

    /** Tabla de 16 bytes para la contraseña, o null si no coincide con la clave y el verificador. */
    private static byte[] tablaXor(String clave, int claveArchivo, int verifArchivo) {
        // La contraseña se usa en 8 bits (el byte bajo, o el alto si el bajo es 0), 15 como mucho.
        int n = Math.min(clave.length(), 15);
        if (n == 0) return null;
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            char ch = clave.charAt(i);
            c[i] = (ch & 0xFF) != 0 ? ch & 0xFF : (ch >> 8) & 0xFF;
        }
        int k = CODIGO_INICIAL[n - 1], e = 0x68;
        for (int i = n - 1; i >= 0; i--) {
            int ch = c[i];
            for (int j = 0; j < 7; j++, e--, ch <<= 1) {
                if ((ch & 0x40) != 0) k ^= MATRIZ[e];
            }
        }
        int v = 0;
        for (int i = n; i >= 0; i--) {
            int b = i == 0 ? n : c[i - 1];
            v = ((((v & 0x4000) != 0 ? 1 : 0) | ((v << 1) & 0x7FFF)) ^ b);
        }
        v ^= 0xCE4B;
        if (k != claveArchivo || v != verifArchivo) return null;

        byte[] t = new byte[16];
        int alto = k >>> 8, bajo = k & 0xFF, i = n;
        if ((i & 1) == 1) {
            t[i] = rotar(RELLENO[0] ^ alto);
            i--;
            t[i] = rotar(c[n - 1] ^ bajo);
        }
        while (i > 0) {
            i--;
            t[i] = rotar(c[i] ^ alto);
            i--;
            t[i] = rotar(c[i] ^ bajo);
        }
        i = 15;
        for (int r = 15 - n; r > 0; ) {
            t[i--] = rotar(RELLENO[r--] ^ alto);
            t[i--] = rotar(RELLENO[r--] ^ bajo);
        }
        return t;
    }

    /** Rotación de un byte un bit a la derecha. */
    private static byte rotar(int b) {
        b &= 0xFF;
        return (byte) ((b >>> 1) | (b << 7));
    }
}
