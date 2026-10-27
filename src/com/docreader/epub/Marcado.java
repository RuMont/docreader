package com.docreader.epub;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lector tolerante de XHTML/XML: recorre etiquetas, texto y comentarios sin validar nada.
 * Sirve tanto para los capítulos (que a menudo traen DOCTYPE, entidades HTML o errores) como
 * para container.xml, el OPF, el NCX y el documento de navegación. Nunca expande DTD.
 *
 * Uso: {@code while (m.siguiente() != Marcado.FIN) { switch (m.tipo) ... }}
 */
final class Marcado {
    static final int FIN = 0, TEXTO = 1, ABRE = 2, CIERRA = 3, OTRO = 4;

    /** Elementos cuyo contenido es texto sin marcas hasta su etiqueta de cierre. */
    private static final String[] CRUDOS = {"script", "style", "textarea", "title", "xmp", "iframe",
            "noembed", "noframes"};

    private final String s;
    private int p;
    private String crudoPendiente;

    /** Tipo del último elemento leído. */
    int tipo;
    /** Nombre tal cual aparece (con prefijo y mayúsculas, que importan en SVG). */
    String nombre;
    /** Nombre local en minúsculas, sin prefijo ("dc:title" → "title"). */
    String local;
    /** Atributos en pares nombre, valor; los valores ya tienen las entidades resueltas. */
    final ArrayList<String> atributos = new ArrayList<>();
    boolean autoCierre;
    /** Texto: sin procesar (con entidades) salvo si {@link #crudo} es true (CDATA, script, style). */
    String texto;
    boolean crudo;

    Marcado(String s) {
        this.s = s == null ? "" : s;
    }

    int siguiente() {
        atributos.clear();
        autoCierre = false;
        crudo = false;
        nombre = local = texto = null;
        if (crudoPendiente != null) {
            String n = crudoPendiente;
            crudoPendiente = null;
            // <style><![CDATA[ … ]]></style>: el cierre se busca después de la sección CDATA.
            int desde = p;
            while (desde < s.length() && Character.isWhitespace(s.charAt(desde))) desde++;
            if (s.startsWith("<![CDATA[", desde)) {
                int finCdata = s.indexOf("]]>", desde + 9);
                desde = finCdata < 0 ? s.length() : finCdata + 3;
            }
            int fin = buscarCierre(n, desde);
            if (fin > p) {
                texto = s.substring(p, fin);
                // title y textarea admiten entidades (RCDATA); el resto es texto literal.
                crudo = !n.equals("title") && !n.equals("textarea");
                p = fin;
                return tipo = TEXTO;
            }
            p = fin;
        }
        int n = s.length();
        if (p >= n) return tipo = FIN;
        if (s.charAt(p) != '<' || !esInicioMarca(p)) {
            int ini = p;
            p = s.indexOf('<', p + 1);
            while (p >= 0 && !esInicioMarca(p)) p = s.indexOf('<', p + 1);
            if (p < 0) p = n;
            texto = s.substring(ini, p);
            return tipo = TEXTO;
        }
        char c = s.charAt(p + 1);
        if (c == '!') {
            if (s.startsWith("<!--", p)) {
                int fin = s.indexOf("-->", p + 4);
                p = fin < 0 ? n : fin + 3;
                return tipo = OTRO;
            }
            if (s.startsWith("<![CDATA[", p)) {
                int fin = s.indexOf("]]>", p + 9);
                texto = s.substring(p + 9, fin < 0 ? n : fin);
                crudo = true;
                p = fin < 0 ? n : fin + 3;
                return tipo = TEXTO;
            }
            p = finDeclaracion(p);
            return tipo = OTRO;
        }
        if (c == '?') {
            int fin = s.indexOf("?>", p + 2);
            p = fin < 0 ? n : fin + 2;
            return tipo = OTRO;
        }
        if (c == '/') {
            int i = p + 2;
            int ini = i;
            while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '>') i++;
            nombre = s.substring(ini, i);
            local = local(nombre);
            int fin = s.indexOf('>', i);
            p = fin < 0 ? n : fin + 1;
            return tipo = CIERRA;
        }
        // Etiqueta de apertura.
        int i = p + 1;
        int ini = i;
        while (i < n) {
            char d = s.charAt(i);
            if (Character.isWhitespace(d) || d == '>' || d == '/') break;
            i++;
        }
        nombre = s.substring(ini, i);
        local = local(nombre);
        while (i < n) {
            char d = s.charAt(i);
            if (Character.isWhitespace(d)) {
                i++;
                continue;
            }
            if (d == '>') {
                i++;
                break;
            }
            if (d == '/') {
                if (i + 1 < n && s.charAt(i + 1) == '>') {
                    autoCierre = true;
                    i += 2;
                    break;
                }
                i++;
                continue;
            }
            int ia = i;
            while (i < n) {
                char e = s.charAt(i);
                if (Character.isWhitespace(e) || e == '=' || e == '>' || (e == '/' && i + 1 < n && s.charAt(i + 1) == '>')) break;
                i++;
            }
            if (i == ia) { // carácter suelto como "=" o comillas: se ignora
                i++;
                continue;
            }
            String an = s.substring(ia, i);
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            String valor = "";
            if (i < n && s.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                if (i < n && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char q = s.charAt(i);
                    int fin = s.indexOf(q, i + 1);
                    if (fin < 0) fin = n;
                    valor = s.substring(i + 1, fin);
                    i = Math.min(n, fin + 1);
                } else {
                    int iv = i;
                    while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '>') i++;
                    valor = s.substring(iv, i);
                }
            }
            if (atr(an) == null) {
                atributos.add(an);
                atributos.add(decodificar(valor));
            }
        }
        p = Math.min(n, i);
        if (!autoCierre) {
            for (String cr : CRUDOS) {
                if (cr.equals(local)) {
                    crudoPendiente = local;
                    break;
                }
            }
        }
        return tipo = ABRE;
    }

    /** "<" que realmente empieza una marca (y no un "<" suelto en el texto). */
    private boolean esInicioMarca(int i) {
        if (i + 1 >= s.length()) return false;
        char c = s.charAt(i + 1);
        if (c == '!' || c == '?') return true;
        if (c == '/') return i + 2 < s.length() && Character.isLetter(s.charAt(i + 2));
        return Character.isLetter(c) || c == '_';
    }

    /** Fin de <!DOCTYPE …>, teniendo en cuenta un posible subconjunto interno [ … ]. */
    private int finDeclaracion(int i) {
        int n = s.length();
        int nivel = 0;
        for (int k = i + 2; k < n; k++) {
            char c = s.charAt(k);
            if (c == '[') nivel++;
            else if (c == ']') nivel = Math.max(0, nivel - 1);
            else if (c == '>' && nivel == 0) return k + 1;
        }
        return n;
    }

    /** Posición de "</nombre" (sin distinguir mayúsculas) a partir de i, o el final. */
    private int buscarCierre(String nombreLocal, int i) {
        int n = s.length();
        int k = i;
        while (true) {
            k = s.indexOf("</", k);
            if (k < 0) return n;
            int j = k + 2;
            int dos = s.indexOf(':', j);
            // Admite prefijo de espacio de nombres: </h:style>
            if (dos > j && dos - j < 12 && s.regionMatches(true, dos + 1, nombreLocal, 0, nombreLocal.length())
                    && sinLetra(dos + 1 + nombreLocal.length())) return k;
            if (s.regionMatches(true, j, nombreLocal, 0, nombreLocal.length())
                    && sinLetra(j + nombreLocal.length())) return k;
            k += 2;
        }
    }

    private boolean sinLetra(int i) {
        return i >= s.length() || !Character.isLetterOrDigit(s.charAt(i));
    }

    static String local(String nombre) {
        if (nombre == null) return "";
        int i = nombre.indexOf(':');
        return (i >= 0 ? nombre.substring(i + 1) : nombre).toLowerCase(Locale.ROOT);
    }

    /** Atributo por nombre exacto (sin distinguir mayúsculas), p. ej. "href" o "xlink:href". */
    String atr(String n) {
        for (int i = 0; i < atributos.size(); i += 2) {
            if (atributos.get(i).equalsIgnoreCase(n)) return atributos.get(i + 1);
        }
        return null;
    }

    /** Atributo por nombre local, con cualquier prefijo ("type" encuentra "epub:type"). */
    String atrLocal(String n) {
        String r = atr(n);
        if (r != null) return r;
        for (int i = 0; i < atributos.size(); i += 2) {
            if (local(atributos.get(i)).equals(n)) return atributos.get(i + 1);
        }
        return null;
    }

    /** Lee el texto que hay dentro del elemento recién abierto, que queda consumido. */
    String textoDentro() {
        if (tipo != ABRE || autoCierre) return "";
        String l = local;
        int nivel = 1;
        StringBuilder sb = new StringBuilder();
        while (siguiente() != FIN) {
            if (tipo == TEXTO) sb.append(crudo ? texto : decodificar(texto));
            else if (tipo == ABRE && !autoCierre && l.equals(local)) nivel++;
            else if (tipo == CIERRA && l.equals(local) && --nivel == 0) break;
        }
        return sb.toString();
    }

    /** Se salta el elemento recién abierto con todo su contenido. */
    void saltar() {
        if (tipo != ABRE || autoCierre) return;
        String l = local;
        int nivel = 1;
        while (siguiente() != FIN) {
            if (tipo == ABRE && !autoCierre && l.equals(local)) nivel++;
            else if (tipo == CIERRA && l.equals(local) && --nivel == 0) return;
        }
    }

    // --- Texto y entidades ---

    /** Colapsa espacios en blanco y recorta. */
    static String limpio(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static final Map<String, Integer> ENTIDADES = new HashMap<>();

    static {
        String latin1 = "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg "
                + "plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest "
                + "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute "
                + "Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml "
                + "Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc "
                + "euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash "
                + "ugrave uacute ucirc uuml yacute thorn yuml";
        int cp = 160;
        for (String e : latin1.split(" ")) ENTIDADES.put(e, cp++);
        Object[] otras = {"amp", 38, "lt", 60, "gt", 62, "quot", 34, "apos", 39, "ndash", 0x2013,
                "mdash", 0x2014, "lsquo", 0x2018, "rsquo", 0x2019, "sbquo", 0x201A, "ldquo", 0x201C,
                "rdquo", 0x201D, "bdquo", 0x201E, "hellip", 0x2026, "bull", 0x2022, "dagger", 0x2020,
                "Dagger", 0x2021, "prime", 0x2032, "Prime", 0x2033, "lsaquo", 0x2039, "rsaquo", 0x203A,
                "euro", 0x20AC, "trade", 0x2122, "thinsp", 0x2009, "ensp", 0x2002, "emsp", 0x2003,
                "zwnj", 0x200C, "zwj", 0x200D, "lrm", 0x200E, "rlm", 0x200F, "OElig", 0x152, "oelig", 0x153,
                "Scaron", 0x160, "scaron", 0x161, "Yuml", 0x178, "fnof", 0x192, "circ", 0x2C6, "tilde", 0x2DC,
                "larr", 0x2190, "rarr", 0x2192, "uarr", 0x2191, "darr", 0x2193, "minus", 0x2212,
                "infin", 0x221E, "ne", 0x2260, "le", 0x2264, "ge", 0x2265, "asymp", 0x2248};
        for (int i = 0; i < otras.length; i += 2) ENTIDADES.put((String) otras[i], (Integer) otras[i + 1]);
    }

    /** Resuelve referencias numéricas y las entidades con nombre más comunes. */
    static String decodificar(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            int fin = s.indexOf(';', i + 1);
            if (fin < 0 || fin - i > 12) {
                sb.append(c);
                continue;
            }
            String ref = s.substring(i + 1, fin);
            int cp = -1;
            if (ref.startsWith("#x") || ref.startsWith("#X")) {
                try {
                    cp = Integer.parseInt(ref.substring(2), 16);
                } catch (NumberFormatException ignorado) { }
            } else if (ref.startsWith("#")) {
                try {
                    cp = Integer.parseInt(ref.substring(1));
                } catch (NumberFormatException ignorado) { }
            } else {
                Integer v = ENTIDADES.get(ref);
                if (v != null) cp = v;
            }
            if (cp > 0 && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) {
                sb.appendCodePoint(cp);
                i = fin;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escapa texto para HTML (contenido o valor de atributo entre comillas dobles). */
    static void esc(StringBuilder sb, String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case 0: break;
                default: sb.append(c);
            }
        }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s == null ? 0 : s.length() + 8);
        esc(sb, s);
        return sb.toString();
    }

    // --- Codificación de caracteres ---

    /**
     * Convierte los bytes de un documento a texto: BOM, luego la declaración XML o el meta charset
     * y, si no hay nada, UTF-8 (lo que exige EPUB).
     */
    static String texto(byte[] b) {
        if (b == null) return "";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 4 && b[0] == 0 && b[1] == '<') return new String(b, StandardCharsets.UTF_16BE);
        if (b.length >= 4 && b[0] == '<' && b[1] == 0) return new String(b, StandardCharsets.UTF_16LE);
        String inicio = new String(b, 0, Math.min(b.length, 1024), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        String cs = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:encoding\\s*=\\s*[\"']|charset\\s*=\\s*[\"']?)([a-z0-9_\\-:.]+)").matcher(inicio);
        if (m.find()) cs = m.group(1);
        Charset charset = StandardCharsets.UTF_8;
        if (cs != null && !cs.startsWith("utf-8") && !cs.equals("utf8")) {
            try {
                Charset c = Charset.forName(cs);
                // Un UTF-16 sin BOM declarado en ASCII es contradictorio: se ignora.
                if (!c.name().startsWith("UTF-16") && !c.name().startsWith("UTF-32")) charset = c;
            } catch (RuntimeException ignorado) { }
        }
        return new String(b, charset);
    }
}
