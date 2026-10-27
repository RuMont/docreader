package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Colores en hexadecimal RRGGBB (sin "#"): validación, ajustes de luminosidad y temas. */
final class Colores {
    private Colores() { }

    /** Normaliza "RRGGBB", "#RRGGBB" o "AARRGGBB"; null si no es un color válido. */
    static String hex6(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 8) v = v.substring(2);
        if (v.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(v.charAt(i), 16) < 0) return null;
        }
        return v.toUpperCase(Locale.ROOT);
    }

    private static float[] hsl(String hex) {
        int c = Integer.parseInt(hex, 16);
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float h = 0, s = 0, l = (max + min) / 2;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[] {h, s, l};
    }

    private static float canal(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    private static String desdeHsl(float h, float s, float l) {
        l = Math.max(0, Math.min(1, l));
        float r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = canal(p, q, h + 1f / 3);
            g = canal(p, q, h);
            b = canal(p, q, h - 1f / 3);
        }
        return rgb(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    static String rgb(int r, int g, int b) {
        return String.format(Locale.ROOT, "%02X%02X%02X",
                Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }

    /** lumMod / lumOff de DrawingML. */
    static String luminancia(String hex, double mod, double off) {
        float[] h = hsl(hex);
        return desdeHsl(h[0], h[1], (float) (h[2] * mod + off));
    }

    /** Atributo tint de Excel: negativo oscurece, positivo aclara. */
    static String tinteExcel(String hex, double tinte) {
        float[] h = hsl(hex);
        float l = h[2];
        l = tinte < 0 ? (float) (l * (1 + tinte)) : (float) (l * (1 - tinte) + tinte);
        return desdeHsl(h[0], h[1], l);
    }

    /** tint de DrawingML: mezcla con blanco (f = proporción del color original). */
    static String tinte(String hex, double f) {
        int c = Integer.parseInt(hex, 16);
        return rgb((int) Math.round(255 - (255 - ((c >> 16) & 255)) * f),
                (int) Math.round(255 - (255 - ((c >> 8) & 255)) * f),
                (int) Math.round(255 - (255 - (c & 255)) * f));
    }

    /** shade de DrawingML: mezcla con negro. */
    static String sombra(String hex, double f) {
        int c = Integer.parseInt(hex, 16);
        return rgb((int) Math.round(((c >> 16) & 255) * f), (int) Math.round(((c >> 8) & 255) * f),
                (int) Math.round((c & 255) * f));
    }

    /** Colores del esquema de un tema (dk1, lt1, accent1…), por nombre. */
    static Map<String, String> tema(Paquete pq, String ruta) {
        Map<String, String> m = new HashMap<>();
        try {
            XmlPullParser p = pq.parser(ruta);
            if (p == null) return m;
            String actual = null;
            boolean enEsquema = false;
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                String n = Lectura.nombre(p);
                if (t == XmlPullParser.END_TAG) {
                    if (n.equals("clrScheme")) break;
                    continue;
                }
                if (t != XmlPullParser.START_TAG) continue;
                if (n.equals("clrScheme")) {
                    enEsquema = true;
                } else if (enEsquema && actual == null) {
                    actual = n;
                } else if (actual != null && n.equals("srgbClr")) {
                    String v = hex6(Lectura.atr(p, "val"));
                    if (v != null) m.put(actual, v);
                    actual = null;
                } else if (actual != null && n.equals("sysClr")) {
                    String v = hex6(Lectura.atr(p, "lastClr"));
                    if (v == null) v = "windowText".equals(Lectura.atr(p, "val")) ? "000000" : "FFFFFF";
                    m.put(actual, v);
                    actual = null;
                }
            }
        } catch (Exception ignorado) {
            // Tema ilegible: se usan los colores por defecto.
        }
        return m;
    }
}
