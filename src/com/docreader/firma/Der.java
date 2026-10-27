package com.docreader.firma;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Codificador DER mínimo (lo justo para construir un CMS) y un lector de elementos. */
final class Der {
    private Der() { }

    static byte[] seq(byte[]... partes) { return tlv(0x30, concat(partes)); }

    /** SET OF en DER: los elementos van ordenados por su codificación. */
    static byte[] set(byte[]... partes) {
        byte[][] orden = partes.clone();
        Arrays.sort(orden, Der::comparar);
        return tlv(0x31, concat(orden));
    }

    /** Elemento con su etiqueta y longitud; el contenido ya va codificado. */
    static byte[] tlv(int etiqueta, byte[] contenido) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(contenido.length + 6);
        o.write(etiqueta);
        int n = contenido.length;
        if (n < 0x80) {
            o.write(n);
        } else {
            int bytes = 0;
            for (int t = n; t > 0; t >>>= 8) bytes++;
            o.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) o.write((n >>> (8 * i)) & 0xFF);
        }
        o.write(contenido, 0, n);
        return o.toByteArray();
    }

    static byte[] entero(BigInteger v) { return tlv(0x02, v.toByteArray()); }

    static byte[] entero(long v) { return entero(BigInteger.valueOf(v)); }

    static byte[] octetos(byte[] v) { return tlv(0x04, v); }

    static byte[] nulo() { return new byte[] {0x05, 0x00}; }

    static byte[] oid(String puntos) {
        String[] p = puntos.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int k = tmp.length;
            tmp[--k] = (byte) (v & 0x7F);
            for (v >>>= 7; v > 0; v >>>= 7) tmp[--k] = (byte) (0x80 | (v & 0x7F));
            o.write(tmp, k, tmp.length - k);
        }
        return tlv(0x06, o.toByteArray());
    }

    static byte[] concat(byte[]... partes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : partes) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static int comparar(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }

    // --- Lectura ---

    /** Elementos hijos (codificación completa de cada uno) de una SEQUENCE/SET. */
    static List<byte[]> hijos(byte[] elemento) {
        int[] cab = cabecera(elemento, 0);
        int pos = cab[0], fin = cab[0] + cab[1];
        List<byte[]> r = new ArrayList<>();
        while (pos < fin) {
            int[] c = cabecera(elemento, pos);
            int total = c[0] - pos + c[1];
            r.add(Arrays.copyOfRange(elemento, pos, pos + total));
            pos += total;
        }
        return r;
    }

    /** {inicio del contenido, longitud del contenido} del elemento que empieza en pos. */
    private static int[] cabecera(byte[] d, int pos) {
        int i = pos + 1;
        int n = d[i++] & 0xFF;
        if (n >= 0x80) {
            int bytes = n & 0x7F;
            n = 0;
            for (int k = 0; k < bytes; k++) n = (n << 8) | (d[i++] & 0xFF);
        }
        if (n < 0 || i + n > d.length) throw new IllegalArgumentException("DER inválido");
        return new int[] {i, n};
    }

    /** Nombre del emisor tal cual viene codificado en el TBSCertificate. */
    static byte[] emisor(byte[] tbs) {
        List<byte[]> h = hijos(tbs);
        int i = (h.get(0)[0] & 0xFF) == 0xA0 ? 3 : 2; // [0] versión es opcional
        return h.get(i);
    }
}
