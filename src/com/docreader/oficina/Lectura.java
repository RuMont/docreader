package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Ayudas para recorrer XML con XmlPullParser sin perderse en elementos desconocidos.
 *
 * Patrón de uso: {@code int d = p.getDepth(); while (Lectura.dentro(p, d)) { ... }} recorre todo
 * lo que hay dentro del elemento actual. Si un elemento no se consume (no se llama a
 * {@link #saltar}), el bucle sigue por sus hijos, así que los contenedores desconocidos se
 * atraviesan solos.
 */
final class Lectura {
    private Lectura() { }

    /** Nombre local del elemento actual, sin prefijo de espacio de nombres. */
    static String nombre(XmlPullParser p) {
        String n = p.getName();
        if (n == null) return "";
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** Avanza un evento; devuelve false al cerrar el elemento de la profundidad dada. */
    static boolean dentro(XmlPullParser p, int profundidad) throws XmlPullParserException, IOException {
        int t = p.next();
        if (t == XmlPullParser.END_DOCUMENT) return false;
        return !(t == XmlPullParser.END_TAG && p.getDepth() <= profundidad);
    }

    static boolean inicio(XmlPullParser p) throws XmlPullParserException {
        return p.getEventType() == XmlPullParser.START_TAG;
    }

    /** Se salta el elemento actual entero (hay que estar en su etiqueta de apertura). */
    static void saltar(XmlPullParser p) throws XmlPullParserException, IOException {
        if (p.getEventType() != XmlPullParser.START_TAG) return;
        int d = p.getDepth();
        while (dentro(p, d)) { }
    }

    /** Todo el texto que hay dentro del elemento actual, que queda consumido. */
    static String texto(XmlPullParser p) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder();
        int d = p.getDepth();
        while (dentro(p, d)) {
            if (p.getEventType() == XmlPullParser.TEXT) sb.append(p.getText());
        }
        return sb.toString();
    }

    private static boolean esRelacion(XmlPullParser p, int i) {
        String ns = p.getAttributeNamespace(i);
        if (ns != null && ns.endsWith("relationships")) return true;
        String n = p.getAttributeName(i);
        return n != null && n.startsWith("r:");
    }

    private static String local(String n) {
        if (n == null) return "";
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** Atributo por nombre local (prefiere el que no es del espacio de relaciones). */
    static String atr(XmlPullParser p, String nombre) {
        String otro = null;
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (!nombre.equals(local(p.getAttributeName(i)))) continue;
            if (!esRelacion(p, i)) return p.getAttributeValue(i);
            otro = p.getAttributeValue(i);
        }
        return otro;
    }

    /** Atributo del espacio de nombres de relaciones, como r:id o r:embed. */
    static String rid(XmlPullParser p, String nombre) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (nombre.equals(local(p.getAttributeName(i))) && esRelacion(p, i)) return p.getAttributeValue(i);
        }
        return null;
    }

    static int entero(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            double d = real(s, Double.NaN);
            return Double.isNaN(d) ? def : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
        }
    }

    static long largo(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            double d = real(s, Double.NaN);
            return Double.isNaN(d) ? def : (long) d;
        }
    }

    static double real(String s, double def) {
        if (s == null) return def;
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? def : d;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Valor booleano de OOXML ("1", "true", "on"); null si no viene. */
    static Boolean booleano(String v) {
        if (v == null) return null;
        v = v.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    /** Para propiedades como &lt;w:b/&gt;: activas salvo que val diga lo contrario. */
    static boolean activo(XmlPullParser p) {
        String v = atr(p, "val");
        if (v == null) return true;
        v = v.trim();
        return !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")
                || v.equalsIgnoreCase("none"));
    }
}
