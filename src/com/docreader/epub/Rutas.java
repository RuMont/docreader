package com.docreader.epub;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Rutas dentro del zip del libro y su relación con las URL relativas de los enlaces. */
final class Rutas {
    private Rutas() { }

    /** Dominio ficticio con el que se carga cada capítulo; no existe y la red está bloqueada. */
    static final String HOST = "epub.invalid";
    static final String BASE = "https://" + HOST + "/";

    /** Quita ".", "..", barras repetidas o iniciales y barras invertidas. */
    static String normalizar(String ruta) {
        if (ruta == null) return "";
        String[] partes = ruta.replace('\\', '/').split("/");
        List<String> pila = new ArrayList<>();
        for (String s : partes) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!pila.isEmpty()) pila.remove(pila.size() - 1);
                continue;
            }
            pila.add(s);
        }
        return String.join("/", pila);
    }

    /** Carpeta (con barra final) del archivo indicado, o "" si está en la raíz. */
    static String carpeta(String archivo) {
        if (archivo == null) return "";
        int i = archivo.lastIndexOf('/');
        return i >= 0 ? archivo.substring(0, i + 1) : "";
    }

    /** true si la URL lleva esquema (http:, mailto:, data:, javascript:…) o empieza por "//". */
    static boolean tieneEsquema(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.startsWith("//")) return true;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == ':') return i > 0;
            boolean valido = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!valido) return false;
        }
        return false;
    }

    /**
     * Resuelve un href relativo (codificado como URL) respecto al archivo que lo contiene.
     * Devuelve {ruta, fragmento} con ambos ya decodificados (el fragmento puede ser null),
     * o null si el enlace es externo. Un href que solo es "#algo" devuelve la ruta de origen.
     */
    static String[] resolver(String origen, String href) {
        if (href == null) return null;
        String h = href.trim();
        if (tieneEsquema(h)) return null;
        String fragmento = null;
        int q = h.indexOf('#');
        if (q >= 0) {
            fragmento = decodificar(h.substring(q + 1));
            if (fragmento.isEmpty()) fragmento = null;
            h = h.substring(0, q);
        }
        int c = h.indexOf('?');
        if (c >= 0) h = h.substring(0, c);
        String ruta;
        if (h.isEmpty()) ruta = normalizar(origen);
        else if (h.startsWith("/")) ruta = normalizar(decodificar(h));
        else ruta = normalizar(carpeta(origen) + decodificar(h));
        return new String[] {ruta, fragmento};
    }

    /** Solo la ruta de {@link #resolver}, o null si el enlace es externo o está vacío. */
    static String ruta(String origen, String href) {
        String[] r = resolver(origen, href);
        return r == null || r[0].isEmpty() ? null : r[0];
    }

    /** Decodifica %XX como UTF-8; las secuencias mal formadas se dejan tal cual. */
    static String decodificar(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int a = Character.digit(s.charAt(i + 1), 16), b = Character.digit(s.charAt(i + 2), 16);
                if (a >= 0 && b >= 0) {
                    out.write(a * 16 + b);
                    i += 2;
                    continue;
                }
            }
            byte[] bs = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                bs = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                i++;
            }
            out.write(bs, 0, bs.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final String SEGURO =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@";

    /** Codifica un segmento de ruta o un fragmento para ponerlo en una URL. */
    static String codificar(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v < 128 && SEGURO.indexOf(v) >= 0) sb.append((char) v);
            else sb.append('%').append(Character.toUpperCase(Character.forDigit(v >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(v & 15, 16)));
        }
        return sb.toString();
    }

    /** URL base ficticia de un archivo del libro, p. ej. https://epub.invalid/OEBPS/cap%201.xhtml */
    static String url(String ruta, String fragmento) {
        StringBuilder sb = new StringBuilder(BASE);
        String[] partes = normalizar(ruta).split("/");
        for (int i = 0; i < partes.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(codificar(partes[i]));
        }
        if (fragmento != null && !fragmento.isEmpty()) sb.append('#').append(codificar(fragmento));
        return sb.toString();
    }

    /** Extensión en minúsculas, sin el punto. */
    static String extension(String ruta) {
        if (ruta == null) return "";
        int b = ruta.lastIndexOf('/');
        int i = ruta.lastIndexOf('.');
        return i > b ? ruta.substring(i + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }
}
