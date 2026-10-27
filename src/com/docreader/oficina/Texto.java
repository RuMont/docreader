package com.docreader.oficina;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Texto plano (.txt, .log) y valores separados por comas (.csv). Markdown y código van aparte. */
final class Texto {
    static final int MAX_CARACTERES = 4_000_000;
    private static final int MAX_FILAS = Xlsx.MAX_FILAS, MAX_COLS = Xlsx.MAX_COLS;

    private static final String CSS_TXT =
            "pre.txt{white-space:pre-wrap;overflow-wrap:break-word;font-family:monospace;font-size:14px;"
            + "line-height:1.45;margin:0;tab-size:4}";
    private static final String CSS_CSV = "body.csv{padding:8px 10px 30px}" + Html.CSS_HOJA;

    private Texto() { }

    static Html.Documento texto(byte[] datos) {
        String s = decodificar(datos);
        boolean cortado = s.length() > MAX_CARACTERES;
        if (cortado) s = s.substring(0, MAX_CARACTERES);
        StringBuilder sb = new StringBuilder(s.length() + 256);
        sb.append("<pre class=txt>");
        Html.esc(sb, s);
        sb.append("</pre>");
        if (cortado) sb.append("<p class=nota>El archivo es muy largo: se muestra solo el principio.</p>");
        if (s.isEmpty()) sb.append("<p class=nota>El archivo está vacío.</p>");
        return new Html.Documento(sb.toString(), CSS_TXT, Html.VIEWPORT_MOVIL, "txt");
    }

    static Html.Documento csv(byte[] datos, String nombre) {
        String s = decodificar(datos);
        String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        char sep = n.endsWith(".tsv") || n.endsWith(".tab") ? '\t' : separador(s);

        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean comillas = false, recortado = false;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (comillas) {
                if (ch == '"') {
                    if (i + 1 < len && s.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        comillas = false;
                    }
                } else {
                    campo.append(ch);
                }
            } else if (ch == '"' && campo.length() == 0) {
                comillas = true;
            } else if (ch == sep) {
                fila.add(campo.toString());
                campo.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < len && s.charAt(i + 1) == '\n') i++;
                fila.add(campo.toString());
                campo.setLength(0);
                filas.add(fila);
                fila = new ArrayList<>();
                if (filas.size() >= MAX_FILAS) {
                    recortado = i + 1 < len;
                    break;
                }
            } else {
                campo.append(ch);
            }
        }
        if (!recortado && (campo.length() > 0 || !fila.isEmpty())) {
            fila.add(campo.toString());
            filas.add(fila);
        }

        int cols = 0;
        for (List<String> f : filas) cols = Math.max(cols, f.size());
        if (cols > MAX_COLS) {
            cols = MAX_COLS;
            recortado = true;
        }
        StringBuilder sb = new StringBuilder(s.length() * 2 + 1024);
        if (filas.isEmpty() || cols == 0) {
            sb.append("<p class=nota>El archivo está vacío.</p>");
            return new Html.Documento(sb.toString(), CSS_CSV, Html.VIEWPORT_MOVIL, "csv");
        }
        sb.append("<table class=hoja><thead><tr><th class=esq></th>");
        for (int c = 1; c <= cols; c++) sb.append("<th>").append(Html.letras(c)).append("</th>");
        sb.append("</tr></thead><tbody>");
        for (int r = 0; r < filas.size(); r++) {
            List<String> f = filas.get(r);
            sb.append(r == 0 ? "<tr class=cab>" : "<tr>").append("<th>").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                String v = c < f.size() ? f.get(c) : "";
                sb.append("<td");
                if (pareceNumero(v)) sb.append(" class=n");
                else if (v.length() > 60 || v.indexOf('\n') >= 0) sb.append(" class=w");
                sb.append('>');
                Html.esc(sb, v);
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (recortado) {
            sb.append("<p class=nota>El archivo es muy grande: se muestran solo las primeras ")
                    .append(filas.size()).append(" filas y ").append(cols).append(" columnas.</p>");
        }
        return new Html.Documento(sb.toString(), CSS_CSV, Html.VIEWPORT_MOVIL, "csv");
    }

    private static boolean pareceNumero(String v) {
        return !v.isEmpty() && v.length() < 30 && v.trim().matches("[-+]?[0-9][0-9.,\\s]*%?|[-+]?[.,][0-9]+");
    }

    /** Elige entre coma, punto y coma, tabulador y barra según las primeras líneas. */
    private static char separador(String s) {
        char[] candidatos = {',', ';', '\t', '|'};
        char mejor = ',';
        long mejorPuntos = 0;
        for (char c : candidatos) {
            int[] cuentas = new int[10];
            int lineas = 0, cuenta = 0;
            boolean comillas = false;
            for (int i = 0; i < s.length() && lineas < cuentas.length; i++) {
                char ch = s.charAt(i);
                if (ch == '"') comillas = !comillas;
                else if (!comillas && ch == c) cuenta++;
                else if (!comillas && ch == '\n') {
                    cuentas[lineas++] = cuenta;
                    cuenta = 0;
                }
            }
            if (lineas < cuentas.length && cuenta > 0) cuentas[lineas++] = cuenta;
            if (lineas == 0 || cuentas[0] == 0) continue;
            int iguales = 0;
            for (int i = 0; i < lineas; i++) if (cuentas[i] == cuentas[0]) iguales++;
            long puntos = iguales * 1000L + cuentas[0];
            if (puntos > mejorPuntos) {
                mejorPuntos = puntos;
                mejor = c;
            }
        }
        return mejor;
    }

    /** UTF-8 (o UTF-16 con BOM); si no es UTF-8 válido, Windows-1252 o ISO-8859-1. */
    static String decodificar(byte[] d) {
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) {
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) {
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        }
        int ini = d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d, ini, d.length - ini))
                    .toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(d, Charset.forName("windows-1252"));
            } catch (RuntimeException e2) {
                return new String(d, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /** true si los primeros bytes no tienen pinta de binario. */
    static boolean pareceTexto(byte[] d) {
        if (d.length >= 2 && ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE
                || (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF)) {
            return true;
        }
        int n = Math.min(d.length, 8192);
        for (int i = 0; i < n; i++) if (d[i] == 0) return false;
        return true;
    }
}
