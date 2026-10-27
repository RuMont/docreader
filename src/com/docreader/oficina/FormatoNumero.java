package com.docreader.oficina;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formatos numéricos de Excel (fechas, horas, porcentajes, moneda, miles, científico y
 * secciones), compartidos por los lectores de .xlsx y .xls. Sin dependencias de Android.
 */
final class FormatoNumero {
    private static final Map<Integer, String> INTEGRADOS = new HashMap<>();

    static {
        String[] f = {"1", "0", "2", "0.00", "3", "#,##0", "4", "#,##0.00",
                "5", "#,##0;-#,##0", "6", "#,##0;-#,##0", "7", "#,##0.00;-#,##0.00", "8", "#,##0.00;-#,##0.00",
                "9", "0%", "10", "0.00%", "11", "0.00E+00", "12", "# ?/?", "13", "# ??/??",
                "14", "dd/mm/yyyy", "15", "d-mmm-yy", "16", "d-mmm", "17", "mmm-yy",
                "18", "h:mm AM/PM", "19", "h:mm:ss AM/PM", "20", "h:mm", "21", "h:mm:ss", "22", "dd/mm/yyyy h:mm",
                "37", "#,##0 ;(#,##0)", "38", "#,##0 ;(#,##0)", "39", "#,##0.00;(#,##0.00)",
                "40", "#,##0.00;(#,##0.00)", "45", "mm:ss", "46", "[h]:mm:ss", "47", "mm:ss.0",
                "48", "##0.0E+0", "49", "@"};
        for (int i = 0; i < f.length; i += 2) INTEGRADOS.put(Integer.parseInt(f[i]), f[i + 1]);
    }

    private final DecimalFormatSymbols simbolos = DecimalFormatSymbols.getInstance();
    /** Sistema de fechas 1904 (libros de Mac) en lugar del de 1900. */
    boolean fecha1904;

    /**
     * Texto del número "v" con el formato "id". "propio" es el código que el libro define para
     * ese id (o null): tiene prioridad sobre los formatos integrados de Excel.
     */
    String numero(double v, int id, String propio) {
        String codigo = propio;
        if (codigo == null) codigo = INTEGRADOS.get(id);
        if (codigo == null || codigo.trim().isEmpty()
                || sinLiterales(secciones(codigo).get(0)).trim().equals("general")) {
            return general(v);
        }
        try {
            if ((id >= 14 && id <= 22) || (id >= 45 && id <= 47) || esFecha(codigo)) {
                String f = fecha(v, codigo);
                if (f != null) return f;
            }
            return conFormato(v, codigo);
        } catch (RuntimeException e) {
            return general(v);
        }
    }

    /** Número sin ruido de coma flotante (15 cifras significativas como mucho). */
    private String general(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "#NUM!";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        BigDecimal b = new BigDecimal(v).round(new MathContext(15)).stripTrailingZeros();
        double a = Math.abs(v);
        String r = a >= 1e15 || a < 1e-9 ? b.round(new MathContext(6)).toString() : b.toPlainString();
        return r.replace('.', simbolos.getDecimalSeparator());
    }

    /** Partes de un código de formato separadas por ';' (fuera de comillas y corchetes). */
    private static List<String> secciones(String codigo) {
        List<String> r = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false, corchete = false;
        for (int i = 0; i < codigo.length(); i++) {
            char c = codigo.charAt(i);
            if (c == '\\' && !comillas && i + 1 < codigo.length()) {
                sb.append(c).append(codigo.charAt(++i));
                continue;
            }
            if (c == '"' && !corchete) comillas = !comillas;
            else if (c == '[' && !comillas) corchete = true;
            else if (c == ']' && !comillas) corchete = false;
            if (c == ';' && !comillas && !corchete) {
                r.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        r.add(sb.toString());
        return r;
    }

    /** Códigos del formato sin texto literal, en minúsculas; los corchetes [h] [m] [s] se conservan. */
    private static String sinLiterales(String codigo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codigo.length(); i++) {
            char c = codigo.charAt(i);
            if (c == '"') {
                int j = codigo.indexOf('"', i + 1);
                i = j < 0 ? codigo.length() : j;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else if (c == '[') {
                int j = codigo.indexOf(']', i);
                if (j < 0) break;
                String b = codigo.substring(i + 1, j).toLowerCase(Locale.ROOT);
                if (b.matches("h+|m+|s+")) sb.append('[').append(b.charAt(0)).append(']');
                i = j;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static boolean esFecha(String codigo) {
        String s = sinLiterales(secciones(codigo).get(0));
        if (s.indexOf('@') >= 0) return false;
        if (s.indexOf('d') >= 0 || s.indexOf('y') >= 0 || s.indexOf('h') >= 0 || s.indexOf('s') >= 0) return true;
        if (s.indexOf('[') >= 0) return true;
        return s.indexOf('m') >= 0 && s.indexOf('0') < 0 && s.indexOf('#') < 0 && s.indexOf('?') < 0;
    }

    private String fecha(double v, String codigo) {
        String s = sinLiterales(secciones(codigo).get(0));
        boolean transcurrido = s.contains("[h]") || s.contains("[m]") || s.contains("[s]");
        boolean hora = s.indexOf('h') >= 0 || s.indexOf('s') >= 0 || transcurrido;
        boolean conFecha = s.indexOf('d') >= 0 || s.indexOf('y') >= 0 || (s.indexOf('m') >= 0 && !hora);
        boolean segundos = s.indexOf('s') >= 0;
        if (v < 0) return null;
        if (transcurrido) {
            long seg = Math.round(v * 86400);
            return (seg / 3600) + String.format(Locale.ROOT, ":%02d", (seg / 60) % 60)
                    + (segundos ? String.format(Locale.ROOT, ":%02d", seg % 60) : "");
        }
        double serie = v;
        if (fecha1904) serie += 1462;
        else if (serie < 61) serie += 1; // Excel cree que 1900 fue bisiesto
        long seg = Math.round(serie * 86400);
        LocalDateTime t = LocalDateTime.of(1899, 12, 30, 0, 0).plusSeconds(seg);
        if (t.getYear() > 9999) return null;
        StringBuilder r = new StringBuilder();
        if (conFecha || !hora) {
            r.append(String.format(Locale.ROOT, "%02d/%02d/%04d", t.getDayOfMonth(), t.getMonthValue(), t.getYear()));
        }
        if (hora) {
            if (r.length() > 0) r.append(' ');
            r.append(String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute()));
            if (segundos) r.append(String.format(Locale.ROOT, ":%02d", t.getSecond()));
        }
        return r.toString();
    }

    /** Formato numérico básico: decimales, miles, porcentaje, científico y texto fijo (€, $…). */
    private String conFormato(double v, String codigo) {
        List<String> secs = secciones(codigo);
        String sec;
        boolean primera = false;
        if (v < 0 && secs.size() > 1 && !secs.get(1).isEmpty()) {
            sec = secs.get(1);
            v = -v;
        } else if (v == 0 && secs.size() > 2 && !secs.get(2).isEmpty()) {
            sec = secs.get(2);
        } else {
            sec = secs.get(0);
            primera = true;
        }
        StringBuilder prefijo = new StringBuilder(), sufijo = new StringBuilder();
        int enteros = 0, decimales = 0, opcionales = 0;
        boolean punto = false, miles = false, porcentaje = false, cientifica = false, digitos = false;
        int n = sec.length();
        for (int i = 0; i < n; i++) {
            char ch = sec.charAt(i);
            StringBuilder lit = digitos ? sufijo : prefijo;
            if (ch == '"') {
                int j = sec.indexOf('"', i + 1);
                if (j < 0) j = n;
                lit.append(sec, i + 1, j);
                i = j;
            } else if (ch == '\\') {
                if (i + 1 < n) lit.append(sec.charAt(++i));
            } else if (ch == '_') {
                i++;
                lit.append(' ');
            } else if (ch == '*') {
                i++;
            } else if (ch == '[') {
                int j = sec.indexOf(']', i);
                if (j < 0) break;
                String b = sec.substring(i + 1, j);
                if (b.startsWith("$")) {
                    int g = b.indexOf('-');
                    lit.append(g >= 0 ? b.substring(1, g) : b.substring(1));
                }
                i = j;
            } else if (ch == '0' || ch == '#' || ch == '?') {
                if (cientifica) continue;
                digitos = true;
                if (punto) {
                    if (ch == '0') decimales++;
                    else opcionales++;
                } else if (ch == '0') {
                    enteros++;
                }
            } else if (ch == '.' && !punto && !cientifica) {
                punto = true;
                digitos = true;
            } else if (ch == ',') {
                if (digitos && !punto) miles = true;
            } else if (ch == '%') {
                porcentaje = true;
                lit.append('%');
            } else if ((ch == 'E' || ch == 'e') && i + 1 < n && (sec.charAt(i + 1) == '+' || sec.charAt(i + 1) == '-')) {
                cientifica = true;
                i++;
            } else if (ch == '@') {
                return general(v);
            } else if (ch == '/' && digitos) {
                return general(primera ? v : -v);
            } else {
                lit.append(ch);
            }
        }
        if (!digitos) return (prefijo.toString() + sufijo).trim();
        if (porcentaje) v *= 100;
        String num;
        if (v == 0 && enteros == 0 && decimales == 0) {
            num = ""; // "#" o "?" sin ceros obligatorios: el cero no se escribe
        } else if (cientifica) {
            num = String.format(Locale.ROOT, "%." + (decimales + opcionales) + "E", v)
                    .replace('.', simbolos.getDecimalSeparator());
        } else {
            StringBuilder pat = new StringBuilder();
            if (miles) pat.append("#,##0");
            else for (int k = 0; k < Math.max(1, Math.min(enteros, 20)); k++) pat.append('0');
            if (decimales + opcionales > 0) {
                pat.append('.');
                for (int k = 0; k < Math.min(decimales, 20); k++) pat.append('0');
                for (int k = 0; k < Math.min(opcionales, 20); k++) pat.append('#');
            }
            DecimalFormat df = new DecimalFormat(pat.toString(), simbolos);
            df.setRoundingMode(RoundingMode.HALF_UP);
            num = df.format(new BigDecimal(v).round(new MathContext(15)));
        }
        if (primera && num.startsWith("-")) return ("-" + prefijo + num.substring(1) + sufijo).trim();
        return (prefijo + num + sufijo).trim();
    }
}
