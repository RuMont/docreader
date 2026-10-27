package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Piezas comunes de los documentos OpenDocument (.odt, .ods, .odp, .odg): nombres con prefijo,
 * unidades de longitud, colores, bordes y la lectura de estilos con su herencia.
 */
final class Odf {
    private Odf() { }

    /** Espacio de nombres → prefijo habitual, para poder comparar con "text:p", "fo:color"… */
    private static final Map<String, String> PREFIJOS = new HashMap<>();

    static {
        String b = "urn:oasis:names:tc:opendocument:xmlns:";
        String[] m = {
                "office", b + "office:1.0", "style", b + "style:1.0", "text", b + "text:1.0",
                "table", b + "table:1.0", "draw", b + "drawing:1.0", "fo", b + "xsl-fo-compatible:1.0",
                "svg", b + "svg-compatible:1.0", "presentation", b + "presentation:1.0",
                "number", b + "datastyle:1.0", "manifest", b + "manifest:1.0", "dr3d", b + "dr3d:1.0",
                "chart", b + "chart:1.0", "anim", b + "animation:1.0", "smil", b + "smil-compatible:1.0",
                "meta", b + "meta:1.0", "form", b + "form:1.0", "script", b + "script:1.0",
                "xlink", "http://www.w3.org/1999/xlink", "xml", "http://www.w3.org/XML/1998/namespace",
                "loext", "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0",
                "calcext", "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0"};
        for (int i = 0; i < m.length; i += 2) PREFIJOS.put(m[i + 1], m[i]);
    }

    // --- Nombres y atributos ---

    /** Nombre del elemento actual con su prefijo habitual ("text:p"), sea cual sea el del archivo. */
    static String nombre(XmlPullParser p) {
        String n = p.getName();
        if (n == null) return "";
        String ns = p.getNamespace();
        if (ns != null && !ns.isEmpty()) {
            String pre = PREFIJOS.get(ns);
            int i = n.indexOf(':');
            return (pre != null ? pre : "?") + ":" + (i >= 0 ? n.substring(i + 1) : n);
        }
        return n;
    }

    /** Nombre del atributo i con su prefijo habitual. */
    static String nombreAtr(XmlPullParser p, int i) {
        String n = p.getAttributeName(i);
        if (n == null) return "";
        String ns = p.getAttributeNamespace(i);
        if (ns != null && !ns.isEmpty()) {
            String pre = PREFIJOS.get(ns);
            int k = n.indexOf(':');
            return (pre != null ? pre : "?") + ":" + (k >= 0 ? n.substring(k + 1) : n);
        }
        return n;
    }

    /** Atributo por nombre con prefijo habitual, p. ej. "text:style-name"; null si no está. */
    static String atr(XmlPullParser p, String q) {
        int n = p.getAttributeCount();
        for (int i = 0; i < n; i++) {
            if (q.equals(nombreAtr(p, i))) return p.getAttributeValue(i);
        }
        return null;
    }

    // --- Unidades y valores ---

    /** Longitud ("2.5cm", "12pt", "0.3in"…) en px CSS; NaN si no es una longitud. */
    static double px(String v) {
        if (v == null) return Double.NaN;
        v = v.trim();
        int i = 0, n = v.length();
        while (i < n) {
            char c = v.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') i++;
            else break;
        }
        if (i == 0) return Double.NaN;
        double num = Lectura.real(v.substring(0, i), Double.NaN);
        if (Double.isNaN(num)) return Double.NaN;
        String u = v.substring(i).trim().toLowerCase(Locale.ROOT);
        double r;
        switch (u) {
            case "cm": r = num * 96 / 2.54; break;
            case "mm": r = num * 96 / 25.4; break;
            case "in": case "inch": r = num * 96; break;
            case "pt": r = num * 96 / 72; break;
            case "pc": r = num * 16; break;
            case "px": case "": r = num; break;
            case "em": r = num * 16; break;
            default: return Double.NaN;
        }
        return Double.isInfinite(r) ? Double.NaN : r;
    }

    /** "75%" → 0.75; NaN si no es un porcentaje. */
    static double porcentaje(String v) {
        if (v == null) return Double.NaN;
        v = v.trim();
        if (!v.endsWith("%")) return Double.NaN;
        double d = Lectura.real(v.substring(0, v.length() - 1), Double.NaN);
        return Double.isNaN(d) ? Double.NaN : d / 100;
    }

    /** Color "#RRGGBB" → "RRGGBB"; null si es "transparent" o no es válido. */
    static String color(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("transparent") || v.equalsIgnoreCase("none")) return null;
        return Colores.hex6(v);
    }

    /** Borde de FO ("0.5pt solid #000000") como valor CSS seguro; null si no hay borde. */
    static String borde(String v) {
        if (v == null) return null;
        String estilo = null, color = "000000";
        double ancho = 1;
        for (String t : v.trim().split("\\s+")) {
            String s = t.toLowerCase(Locale.ROOT);
            switch (s) {
                case "none": case "hidden": return null;
                case "solid": case "dotted": case "dashed": case "double": case "groove": case "ridge":
                case "inset": case "outset":
                    estilo = s;
                    break;
                case "thin": ancho = 1; break;
                case "medium": ancho = 2; break;
                case "thick": ancho = 3; break;
                default:
                    if (s.startsWith("#")) {
                        String c = color(s);
                        if (c != null) color = c;
                    } else {
                        double a = px(s);
                        if (!Double.isNaN(a)) ancho = a;
                    }
            }
        }
        if (estilo == null) estilo = "solid";
        if (ancho <= 0) return null;
        ancho = Math.max(1, Math.min(12, Math.round(ancho)));
        if (estilo.equals("double")) ancho = Math.max(ancho, 3);
        return Html.px(ancho) + "px " + estilo + " #" + color;
    }

    /** Decodifica %XX (UTF-8) en un enlace. */
    static String decodificar(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // --- Tipo de documento ---

    /**
     * Deja el paquete listo para leer: si está cifrado lo descifra con la contraseña (sin ella
     * lanza {@link FalloClave}) y comprueba que tiene content.xml. Puede devolver otro paquete.
     */
    static Paquete abrir(Paquete pq, String clave) throws Fallo {
        if (OdfCifrado.cifrado(pq)) {
            OdfCifrado.comprobar(pq);
            if (clave == null) throw FalloClave.para(null);
            pq = OdfCifrado.descifrar(pq, clave);
        }
        if (!pq.existe("content.xml")) {
            throw new Fallo("No se encontró el contenido del documento. "
                    + "Puede que el archivo esté dañado o que no sea un documento OpenDocument.");
        }
        return pq;
    }

    /** Comprueba que el paquete se puede leer sin contraseña y tiene content.xml. */
    static void comprobar(Paquete pq) throws Fallo {
        abrir(pq, null);
    }

    /**
     * Clase de documento OpenDocument: "odt", "ods", "odp" u "odg"; null si no lo parece.
     * Mira primero el archivo mimetype y, si falta, el cuerpo de content.xml.
     */
    static String tipo(Paquete pq) {
        byte[] m = pq.bytes("mimetype");
        if (m != null) {
            String s = new String(m, 0, Math.min(m.length, 200), StandardCharsets.ISO_8859_1)
                    .trim().toLowerCase(Locale.ROOT);
            String pre = "application/vnd.oasis.opendocument.";
            if (s.startsWith(pre)) {
                String r = s.substring(pre.length());
                if (r.startsWith("text")) return "odt";
                if (r.startsWith("spreadsheet")) return "ods";
                if (r.startsWith("presentation")) return "odp";
                if (r.startsWith("graphics")) return "odg";
            }
        }
        try {
            XmlPullParser p = pq.parser("content.xml");
            int t;
            while (p != null && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (nombre(p)) {
                    case "office:text": return "odt";
                    case "office:spreadsheet": return "ods";
                    case "office:presentation": return "odp";
                    case "office:drawing": return "odg";
                    case "office:automatic-styles": case "office:font-face-decls": case "office:scripts":
                        Lectura.saltar(p);
                        break;
                    default: break;
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException ignorado) {
            // Contenido ilegible: no se sabe qué es.
        }
        return null;
    }

    /** Convierte cualquier documento OpenDocument con el conversor que le corresponde. */
    static Html.Documento convertir(Paquete pq) throws Fallo {
        return convertir(pq, null);
    }

    /**
     * Igual, con la contraseña para los documentos cifrados (null si no se tiene). Sin ella, o
     * si no es la buena, lanza {@link FalloClave}.
     */
    static Html.Documento convertir(Paquete pq, String clave) throws Fallo {
        pq = abrir(pq, clave);
        String t = tipo(pq);
        if (t == null) {
            byte[] m = pq.bytes("mimetype");
            if (m != null && new String(m, StandardCharsets.ISO_8859_1).startsWith("application/vnd.sun.xml.")) {
                throw new Fallo("Este documento tiene el formato antiguo de OpenOffice.org 1 (.sxw, .sxc, .sxi), "
                        + "que el visor no admite.\n\nÁbrelo en LibreOffice y guárdalo como .odt, .ods u .odp.");
            }
            throw new Fallo("El archivo no es un documento OpenDocument compatible o está dañado.");
        }
        switch (t) {
            case "odt": return Odt.convertir(pq);
            case "ods": return Ods.convertir(pq);
            default: return Odp.convertir(pq);
        }
    }

    // --- Estilos ---

    static final int TEXTO = 0, PARRAFO = 1, OTRAS = 2;

    /** Un estilo (style:style, style:default-style o style:page-layout) con sus propiedades. */
    static final class Estilo {
        final String familia, nombre;
        String padre, lista, maestra;
        int nivelEsquema;
        /** style:text-properties, style:paragraph-properties y el resto (celda, gráfico, página…). */
        final Map<String, String> texto = new HashMap<>(), parrafo = new HashMap<>(), otras = new HashMap<>();
        /** text:list-style metido dentro del estilo (estilos de presentación y gráficos). */
        ListaEstilo listaPropia;

        Estilo(String familia, String nombre) {
            this.familia = familia == null ? "" : familia;
            this.nombre = nombre;
        }

        Map<String, String> mapa(int tipo) {
            return tipo == TEXTO ? texto : tipo == PARRAFO ? parrafo : otras;
        }
    }

    /** Un nivel de un estilo de lista. */
    static final class NivelLista {
        /** 0 viñeta, 1 número, 2 imagen. */
        int tipo;
        String formato, vineta;
        int inicio = 1;
        /** Sangría total del nivel en px (NaN si no se sabe). */
        double sangria = Double.NaN;
    }

    static final class ListaEstilo {
        final NivelLista[] niveles = new NivelLista[11];

        /** Nivel 1–10 (los más profundos usan el 10). */
        NivelLista nivel(int n) {
            return niveles[Math.max(1, Math.min(10, n))];
        }
    }

    static final class Degradado {
        String estilo, inicio, fin;
        double angulo;
    }

    /** Recibe cada style:master-page de styles.xml; debe consumir el elemento entero. */
    interface Maestra {
        void leer(XmlPullParser p, String nombre, String disposicion, String estiloPagina)
                throws XmlPullParserException, IOException;
    }

    static final int AUTO_CONTENIDO = 0, COMUNES = 1, AUTO_ESTILOS = 2;
    private static final int[] ORDEN_CONTENIDO = {AUTO_CONTENIDO, COMUNES, AUTO_ESTILOS};
    private static final int[] ORDEN_MAESTRAS = {AUTO_ESTILOS, COMUNES};
    private static final int[] ORDEN_PADRES = {COMUNES, AUTO_CONTENIDO, AUTO_ESTILOS};

    /**
     * Todos los estilos del documento. Los automáticos de content.xml y de styles.xml van por
     * separado porque suelen repetir nombres (P1, gr1…): mientras se pintan las páginas maestras
     * ({@link #enMaestras}) mandan los de styles.xml.
     */
    static final class Estilos {
        @SuppressWarnings("unchecked")
        private final Map<String, Estilo>[] estilos = new Map[] {new HashMap<>(), new HashMap<>(), new HashMap<>()};
        @SuppressWarnings("unchecked")
        private final Map<String, ListaEstilo>[] listas = new Map[] {new HashMap<>(), new HashMap<>(), new HashMap<>()};
        private final Map<String, Estilo> defectos = new HashMap<>();
        final Map<String, Degradado> degradados = new HashMap<>();
        final Map<String, String> imagenesRelleno = new HashMap<>();
        final Map<String, String> tramas = new HashMap<>();
        /** Páginas maestras: nombre → {disposición de página, estilo de página de dibujo}. */
        final Map<String, String[]> maestras = new HashMap<>();
        String primeraMaestra;
        boolean enMaestras;

        /** Lee styles.xml; si falta o está dañado se sigue con lo leído. */
        static Estilos leer(Paquete pq, Maestra m) {
            Estilos e = new Estilos();
            try {
                e.leerArchivo(pq, m);
            } catch (Exception ignorado) {
                // Estilos dañados: se muestra el documento con lo que se haya leído.
            }
            return e;
        }

        void leerArchivo(Paquete pq, Maestra m) throws XmlPullParserException, IOException {
            XmlPullParser p = pq.parser("styles.xml");
            if (p == null) return;
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (nombre(p)) {
                    case "office:styles": leerBloque(p, COMUNES); break;
                    case "office:automatic-styles": leerBloque(p, AUTO_ESTILOS); break;
                    case "office:master-styles": leerMaestras(p, m); break;
                    case "office:font-face-decls": case "office:scripts": Lectura.saltar(p); break;
                    default: break;
                }
            }
        }

        private void leerMaestras(XmlPullParser p, Maestra m) throws XmlPullParserException, IOException {
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                if (!"style:master-page".equals(nombre(p))) {
                    Lectura.saltar(p);
                    continue;
                }
                String n = atr(p, "style:name");
                String disp = atr(p, "style:page-layout-name"), pag = atr(p, "draw:style-name");
                if (n != null) {
                    maestras.put(n, new String[] {disp, pag});
                    if (primeraMaestra == null) primeraMaestra = n;
                }
                if (m == null || n == null) {
                    Lectura.saltar(p);
                    continue;
                }
                try {
                    m.leer(p, n, disp, pag);
                } catch (RuntimeException ignorado) {
                    // Página maestra rara: se sigue con las demás.
                }
            }
        }

        /** Lee el contenido de office:styles u office:automatic-styles (el elemento queda consumido). */
        void leerBloque(XmlPullParser p, int origen) throws XmlPullParserException, IOException {
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                try {
                    switch (nombre(p)) {
                        case "style:style": {
                            Estilo e = leerEstilo(p, atr(p, "style:family"), atr(p, "style:name"));
                            if (e.nombre != null) estilos[origen].put(e.familia + "/" + e.nombre, e);
                            break;
                        }
                        case "style:default-style": {
                            Estilo e = leerEstilo(p, atr(p, "style:family"), null);
                            if (!e.familia.isEmpty()) defectos.put(e.familia, e);
                            break;
                        }
                        case "style:page-layout": {
                            Estilo e = leerEstilo(p, "page-layout", atr(p, "style:name"));
                            if (e.nombre != null) estilos[origen].put(e.familia + "/" + e.nombre, e);
                            break;
                        }
                        case "text:list-style": {
                            String n = atr(p, "style:name");
                            ListaEstilo l = leerLista(p);
                            if (n != null) listas[origen].put(n, l);
                            break;
                        }
                        case "draw:gradient": {
                            Degradado g = new Degradado();
                            g.estilo = atr(p, "draw:style");
                            g.inicio = color(atr(p, "draw:start-color"));
                            g.fin = color(atr(p, "draw:end-color"));
                            g.angulo = angulo(atr(p, "draw:angle"));
                            String n = atr(p, "draw:name");
                            if (n != null) degradados.put(n, g);
                            Lectura.saltar(p);
                            break;
                        }
                        case "draw:fill-image": {
                            String n = atr(p, "draw:name"), h = atr(p, "xlink:href");
                            if (n != null && h != null) imagenesRelleno.put(n, h);
                            Lectura.saltar(p);
                            break;
                        }
                        case "draw:hatch": {
                            String n = atr(p, "draw:name"), c = color(atr(p, "draw:color"));
                            if (n != null && c != null) tramas.put(n, c);
                            Lectura.saltar(p);
                            break;
                        }
                        default: Lectura.saltar(p); break;
                    }
                } catch (RuntimeException ignorado) {
                    // Estilo raro: se ignora.
                }
            }
        }

        /** Ángulo de ODF en grados: "45deg", "0.5rad" o, sin unidad, décimas de grado. */
        private static double angulo(String v) {
            if (v == null) return 0;
            v = v.trim().toLowerCase(Locale.ROOT);
            if (v.endsWith("deg")) return Lectura.real(v.substring(0, v.length() - 3), 0);
            if (v.endsWith("grad")) return Lectura.real(v.substring(0, v.length() - 4), 0) * 0.9;
            if (v.endsWith("rad")) return Math.toDegrees(Lectura.real(v.substring(0, v.length() - 3), 0));
            return Lectura.real(v, 0) / 10;
        }

        private static Estilo leerEstilo(XmlPullParser p, String familia, String nombre)
                throws XmlPullParserException, IOException {
            Estilo e = new Estilo(familia, nombre);
            e.padre = atr(p, "style:parent-style-name");
            e.lista = atr(p, "style:list-style-name");
            e.maestra = atr(p, "style:master-page-name");
            e.nivelEsquema = Lectura.entero(atr(p, "style:default-outline-level"), 0);
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                String n = nombre(p);
                if (n.equals("text:list-style")) {
                    e.listaPropia = leerLista(p);
                } else if (n.endsWith("-properties") && !n.startsWith("loext:")) {
                    Map<String, String> m = n.equals("style:text-properties") ? e.texto
                            : n.equals("style:paragraph-properties") ? e.parrafo : e.otras;
                    for (int i = 0; i < p.getAttributeCount(); i++) m.put(nombreAtr(p, i), p.getAttributeValue(i));
                } else if (n.equals("style:map") || n.startsWith("loext:")) {
                    Lectura.saltar(p);
                }
            }
            return e;
        }

        private static ListaEstilo leerLista(XmlPullParser p) throws XmlPullParserException, IOException {
            ListaEstilo l = new ListaEstilo();
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                String n = nombre(p);
                int tipo = n.endsWith("list-level-style-number") ? 1 : n.endsWith("list-level-style-bullet") ? 0
                        : n.endsWith("list-level-style-image") ? 2 : -1;
                int nivel = Lectura.entero(atr(p, "text:level"), 1);
                if (tipo < 0 || nivel < 1 || nivel > 10) {
                    Lectura.saltar(p);
                    continue;
                }
                NivelLista nv = new NivelLista();
                nv.tipo = tipo;
                nv.formato = atr(p, "style:num-format");
                if (tipo == 1 && nv.formato == null) nv.formato = "1";
                nv.vineta = atr(p, "text:bullet-char");
                nv.inicio = Lectura.entero(atr(p, "text:start-value"), 1);
                double antes = Double.NaN, etiqueta = Double.NaN, margen = Double.NaN;
                int dn = p.getDepth();
                while (Lectura.dentro(p, dn)) {
                    if (!Lectura.inicio(p)) continue;
                    String m = nombre(p);
                    if (m.equals("style:list-level-properties")) {
                        antes = px(atr(p, "text:space-before"));
                        etiqueta = px(atr(p, "text:min-label-width"));
                    } else if (m.equals("style:list-level-label-alignment")) {
                        margen = px(atr(p, "fo:margin-left"));
                    }
                }
                if (!Double.isNaN(margen)) nv.sangria = margen;
                else if (!Double.isNaN(antes) || !Double.isNaN(etiqueta)) {
                    nv.sangria = (Double.isNaN(antes) ? 0 : antes) + (Double.isNaN(etiqueta) ? 0 : etiqueta);
                }
                l.niveles[nivel] = nv;
            }
            return l;
        }

        // --- Búsquedas ---

        Estilo estilo(String familia, String nombre) {
            if (nombre == null || nombre.isEmpty()) return null;
            String k = familia + "/" + nombre;
            for (int o : enMaestras ? ORDEN_MAESTRAS : ORDEN_CONTENIDO) {
                Estilo e = estilos[o].get(k);
                if (e != null) return e;
            }
            return null;
        }

        private Estilo padreDe(Estilo e) {
            if (e.padre == null || e.padre.isEmpty()) return null;
            String k = e.familia + "/" + e.padre;
            for (int o : ORDEN_PADRES) {
                Estilo r = estilos[o].get(k);
                if (r != null) return r;
            }
            return null;
        }

        /** Estilo por defecto de una familia (los de presentación heredan los de gráficos). */
        Estilo defecto(String familia) {
            Estilo d = defectos.get(familia);
            if (d == null && "presentation".equals(familia)) d = defectos.get("graphic");
            return d;
        }

        /** El estilo, sus padres y, si se pide, el estilo por defecto de la familia. */
        List<Estilo> cadena(Estilo e, String familia, boolean conDefecto) {
            if (e == null && !conDefecto) return Collections.emptyList();
            List<Estilo> r = new ArrayList<>(4);
            for (Estilo x = e; x != null && r.size() < 20 && !r.contains(x); x = padreDe(x)) r.add(x);
            if (conDefecto) {
                Estilo d = defecto(e != null ? e.familia : familia);
                if (d != null && !r.contains(d)) r.add(d);
            }
            return r;
        }

        ListaEstilo lista(String nombre) {
            if (nombre == null || nombre.isEmpty()) return null;
            for (int o : enMaestras ? ORDEN_MAESTRAS : ORDEN_CONTENIDO) {
                ListaEstilo l = listas[o].get(nombre);
                if (l != null) return l;
            }
            return null;
        }
    }

    /** Primer valor de la propiedad en la cadena de estilos. */
    static String prop(List<Estilo> cadena, int tipo, String clave) {
        for (Estilo e : cadena) {
            String v = e.mapa(tipo).get(clave);
            if (v != null) return v;
        }
        return null;
    }
}
