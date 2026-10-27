package com.docreader.epub;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Limpieza de las hojas de estilo del libro: se integran los @import locales, se quitan los
 * remotos y cada url() se cambia por una URI data: del archivo del libro o por "none".
 */
final class Css {
    private Css() { }

    /** Quién resuelve los recursos de las url() y el contenido de las hojas importadas. */
    interface Recursos {
        /** URI data: del recurso (ruta ya resuelta dentro del libro), o null si no se puede incluir. */
        String datos(String ruta);

        /** Texto de una hoja de estilo del libro, o null si no existe. */
        String hoja(String ruta);
    }

    private static final int MAX_PROFUNDIDAD = 4;

    /** Procesa una hoja completa situada en {@code ruta} (para resolver sus url() relativas). */
    static String hoja(String css, String ruta, Recursos r) {
        Set<String> vistas = new HashSet<>();
        vistas.add(ruta.toLowerCase(Locale.ROOT));
        return seguro(procesar(css, ruta, r, 0, vistas));
    }

    /** Procesa el contenido de un atributo style (no admite @import). */
    static String estilo(String css, String rutaDocumento, Recursos r) {
        return procesar(css, rutaDocumento, r, MAX_PROFUNDIDAD, new HashSet<>());
    }

    /** Evita que el texto cierre la etiqueta <style> en la que se incrusta. */
    private static String seguro(String css) {
        if (css.indexOf("</") < 0) return css;
        return css.replace("</", "<\\/");
    }

    private static String procesar(String css, String base, Recursos r, int profundidad, Set<String> vistas) {
        if (css == null) return "";
        css = css.replace("<![CDATA[", "").replace("]]>", "");
        StringBuilder out = new StringBuilder(css.length() + 64);
        int n = css.length();
        int i = 0;
        while (i < n) {
            char c = css.charAt(i);
            if (c == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int fin = css.indexOf("*/", i + 2);
                i = fin < 0 ? n : fin + 2;
                out.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                int fin = finCadena(css, i);
                out.append(css, i, fin);
                i = fin;
                continue;
            }
            if (c == '<' && css.startsWith("<!--", i)) {
                i += 4;
                continue;
            }
            if (c == '-' && css.startsWith("-->", i)) {
                i += 3;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                out.append(c).append(css.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '@' && css.regionMatches(true, i, "@import", 0, 7)) {
                int fin = finRegla(css, i);
                String regla = css.substring(i + 7, fin).trim();
                if (regla.endsWith(";")) regla = regla.substring(0, regla.length() - 1).trim();
                i = Math.min(n, fin + 1);
                if (profundidad >= MAX_PROFUNDIDAD) continue;
                String[] um = urlYResto(regla);
                if (um == null) continue;
                String ruta = Rutas.ruta(base, um[0]);
                if (ruta == null || !vistas.add(ruta.toLowerCase(Locale.ROOT))) continue;
                String importada = r.hoja(ruta);
                if (importada == null) continue;
                String hecha = procesar(importada, ruta, r, profundidad + 1, vistas);
                String medios = um[1].trim();
                if (medios.isEmpty()) out.append(hecha).append('\n');
                else out.append("@media ").append(medios).append('{').append(hecha).append("}\n");
                continue;
            }
            if ((c == 'u' || c == 'U') && css.regionMatches(true, i, "url(", 0, 4)
                    && (i == 0 || !esIdent(css.charAt(i - 1)))) {
                int j = i + 4;
                while (j < n && Character.isWhitespace(css.charAt(j))) j++;
                String url;
                if (j < n && (css.charAt(j) == '"' || css.charAt(j) == '\'')) {
                    int fin = finCadena(css, j);
                    url = sinEscapes(css.substring(j + 1, Math.max(j + 1, fin - 1)));
                    j = fin;
                    while (j < n && css.charAt(j) != ')') j++;
                } else {
                    int fin = css.indexOf(')', j);
                    if (fin < 0) fin = n;
                    url = sinEscapes(css.substring(j, fin).trim());
                    j = fin;
                }
                i = Math.min(n, j + 1);
                out.append(sustituir(url, base, r));
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String sustituir(String url, String base, Recursos r) {
        String u = url.trim();
        if (u.regionMatches(true, 0, "data:", 0, 5)) {
            // Los datos ya incrustados se mantienen (la CSP decide si se usan), salvo comillas sueltas.
            if (u.indexOf('"') < 0 && u.indexOf('\\') < 0) return "url(\"" + u + "\")";
            return "none";
        }
        if (u.startsWith("#")) return "url(\"" + u.replace("\"", "") + "\")"; // referencias a filtros SVG del propio documento
        String ruta = Rutas.ruta(base, u);
        if (ruta == null) return "none";
        String d = r.datos(ruta);
        return d == null ? "none" : "url(\"" + d + "\")";
    }

    /** Extrae la URL de un @import (url(...) o "cadena") y lo que queda detrás (los medios). */
    private static String[] urlYResto(String regla) {
        String s = regla.trim();
        if (s.regionMatches(true, 0, "url(", 0, 4)) {
            int fin = s.indexOf(')');
            if (fin < 0) return null;
            String u = s.substring(4, fin).trim();
            if (u.length() >= 2 && (u.charAt(0) == '"' || u.charAt(0) == '\'')) u = u.substring(1, u.length() - 1);
            return new String[] {sinEscapes(u), s.substring(fin + 1)};
        }
        if (!s.isEmpty() && (s.charAt(0) == '"' || s.charAt(0) == '\'')) {
            int fin = finCadena(s, 0);
            return new String[] {sinEscapes(s.substring(1, Math.max(1, fin - 1))), s.substring(fin)};
        }
        return null;
    }

    /** Posición siguiente al cierre de la cadena que empieza en i. */
    private static int finCadena(String s, int i) {
        char q = s.charAt(i);
        int n = s.length();
        int k = i + 1;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '\\') {
                k += 2;
                continue;
            }
            if (c == q || c == '\n') return k + 1;
            k++;
        }
        return n;
    }

    /** Fin de una regla @ sin bloque (el ";"), respetando cadenas. */
    private static int finRegla(String s, int i) {
        int n = s.length();
        int k = i;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '"' || c == '\'') {
                k = finCadena(s, k);
                continue;
            }
            if (c == ';') return k;
            if (c == '{' || c == '}') return k - 1;
            k++;
        }
        return n;
    }

    private static boolean esIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    /** Quita los escapes CSS sencillos (\" o \)) de una URL. */
    private static String sinEscapes(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                if (Character.digit(d, 16) >= 0) {
                    int j = i + 1;
                    while (j < s.length() && j < i + 7 && Character.digit(s.charAt(j), 16) >= 0) j++;
                    try {
                        sb.appendCodePoint(Integer.parseInt(s.substring(i + 1, j), 16));
                    } catch (RuntimeException e) {
                        sb.append('?');
                    }
                    if (j < s.length() && s.charAt(j) == ' ') j++;
                    i = j - 1;
                } else {
                    sb.append(d);
                    i++;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
