package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte un documento de texto de OpenDocument (.odt) en HTML.
 *
 * La misma clase pinta el texto con formato (párrafos, listas, tablas, marcos) de las celdas de
 * Ods y de los cuadros de texto de Odp; {@link #modo} decide cómo se escriben los tamaños.
 */
final class Odt {
    static final int MODO_TEXTO = 0, MODO_HOJA = 1, MODO_DIAPO = 2;

    private static final int MAX_ANIDADO = 40, MAX_FILAS_TABLA = 3000, MAX_COLS_TABLA = 200, MAX_ESPACIOS = 500;

    private static final String CSS =
            "body.odt{max-width:48em;margin:0 auto;padding:16px 18px 40px}"
            + "p,li,h1,h2,h3,h4,h5,h6{white-space:pre-wrap;tab-size:4}"
            + "p{margin:0 0 .55em}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1em 0 .45em}"
            + "h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.22em}h4{font-size:1.1em}h5,h6{font-size:1em}"
            + "ul,ol{margin:0 0 .55em;padding-left:1.7em}"
            + "li{margin:.15em 0}"
            + "li>p,li>h1,li>h2,li>h3,li>h4,li>h5,li>h6{margin:0}"
            + "li>ul,li>ol{margin:0}"
            + ".tabla{overflow-x:auto;margin:.6em 0}"
            + ".tabla table{border-collapse:collapse}"
            + ".tabla td{vertical-align:top;padding:4px 7px}"
            + ".tabla td>p:last-child{margin-bottom:0}"
            + ".salto{display:block;border-top:1px dashed #b5b5b5;margin:1.2em 0}"
            + ".caja{border:1px solid #c8c8c8;border-radius:4px;padding:6px 10px;margin:.5em 0}"
            + ".marco{margin:.5em 0}"
            + "sup.ref{line-height:0}sup.ref a{text-decoration:none}"
            + ".notas{border-top:1px solid #bdbdbd;margin-top:2em;padding-top:.6em;font-size:.92em}"
            + ".nt{display:flex;gap:.5em;margin:0 0 .4em}.nt>a{flex:none;text-decoration:none}"
            + ".nt>div{flex:1;min-width:0}.nt p{margin:0 0 .3em}";

    private static final Pattern MARCA_TITULO = Pattern.compile("\u0002(\\d+)\u0003");

    /** Estado de un párrafo (o de cualquier trozo de texto en línea) mientras se lee. */
    static final class Linea {
        final StringBuilder sb = new StringBuilder(), plano = new StringBuilder();
        /** Cuadros y marcos que se escriben detrás del párrafo. */
        final StringBuilder despues = new StringBuilder();
        boolean espacio = true, hay, titulo;
    }

    private final Paquete pq;
    final Odf.Estilos est;
    final int modo;
    /** px de la página por px del documento (Odp escala la diapositiva). */
    double escala = 1;
    /** Tamaño de letra normal del documento, en pt. */
    double base = 12;
    /** Enlaces internos ya resueltos (Odp: nombre de diapositiva → "#dN"). */
    Map<String, String> destinos;
    /** Estilo de lista para las listas que no dicen el suyo (Odp: el del marco). */
    Odf.ListaEstilo listaDefecto;
    /** Nombre base de los estilos de esquema ("Default-outline") para los niveles de lista en Odp. */
    String baseEsquema;
    /** Caracteres visibles escritos hasta ahora (para saber si un cuadro tenía texto). */
    int caracteres;
    /** Se encontraron imágenes o dibujos que no se muestran (celdas de Ods). */
    boolean hayDibujos;
    final List<Html.Titulo> indice = new ArrayList<>();

    private final StringBuilder notas = new StringBuilder(), notasFinales = new StringBuilder();
    private int numNotas, numTitulos, profundidad, contenedores;
    /** Columnas declaradas en la tabla que se está leyendo. */
    private int columnas = MAX_COLS_TABLA;
    private boolean escrito;
    private final Map<String, String> titulos = new HashMap<>();
    private final List<String> pendientes = new ArrayList<>();
    private final Map<String, Integer> contadores = new HashMap<>();

    Odt(Paquete pq, Odf.Estilos est, int modo) {
        this.pq = pq;
        this.est = est;
        this.modo = modo;
    }

    static Html.Documento convertir(Paquete pq) throws Fallo {
        Odf.comprobar(pq);
        Odt o = new Odt(pq, Odf.Estilos.leer(pq, null), MODO_TEXTO);
        o.base = o.tamanoBase();
        StringBuilder sb = new StringBuilder();
        o.documento(sb);
        return new Html.Documento(sb.toString(), CSS, Html.VIEWPORT_MOVIL, "odt").conIndice(o.indice);
    }

    private double tamanoBase() {
        Odf.Estilo e = est.estilo("paragraph", "Standard");
        double[] t = tamano(est.cadena(e, "paragraph", true));
        return !Double.isNaN(t[0]) && t[0] >= 4 && t[0] <= 72 ? t[0] : 12;
    }

    private void documento(StringBuilder sb) throws Fallo {
        XmlPullParser p;
        try {
            p = pq.parser("content.xml");
        } catch (XmlPullParserException e) {
            p = null;
        }
        if (p == null) throw new Fallo("No se puede leer el texto del documento. Puede que el archivo esté dañado.");
        try {
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Odf.nombre(p)) {
                    case "office:automatic-styles": est.leerBloque(p, Odf.AUTO_CONTENIDO); break;
                    case "office:font-face-decls": case "office:scripts": Lectura.saltar(p); break;
                    case "office:text": bloques(p, sb, 0, null); break;
                    default: break;
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException | StackOverflowError e) {
            if (sb.length() == 0) throw new Fallo("El documento está dañado y no se puede leer.");
            sb.append(Html.NOTA_DANADA);
        }
        if (notas.length() > 0) sb.append("<div class=notas>").append(notas).append("</div>");
        if (notasFinales.length() > 0) sb.append("<div class=notas>").append(notasFinales).append("</div>");
        if (!pendientes.isEmpty()) resolverTitulos(sb);
        if (sb.length() == 0) sb.append("<p class=nota>El documento está vacío.</p>");
    }

    // --- Bloques ---

    /**
     * Lee párrafos, listas, tablas y marcos hasta cerrar el elemento actual.
     * nivelLista y estiloLista dan el contexto para las listas anidadas dentro de un elemento de lista.
     */
    void bloques(XmlPullParser p, StringBuilder sb, int nivelLista, String estiloLista)
            throws XmlPullParserException, IOException {
        if (!entrar(p)) return;
        try {
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                try {
                    bloque(p, sb, nivelLista, estiloLista);
                } catch (RuntimeException e) {
                    // Un elemento raro no debe tumbar todo el documento.
                }
            }
        } finally {
            profundidad--;
        }
    }

    private void bloque(XmlPullParser p, StringBuilder sb, int nivelLista, String estiloLista)
            throws XmlPullParserException, IOException {
        String n = Odf.nombre(p);
        switch (n) {
            case "text:p": parrafo(p, sb, false, nivelLista > 0); break;
            case "text:h": parrafo(p, sb, true, nivelLista > 0); break;
            case "text:list": lista(p, sb, nivelLista, estiloLista); break;
            case "table:table": tabla(p, sb); break;
            case "draw:frame": case "draw:a": case "draw:rect": case "draw:ellipse": case "draw:circle":
            case "draw:custom-shape": case "draw:polygon": case "draw:polyline": case "draw:path":
            case "draw:regular-polygon": case "draw:caption": case "draw:line": case "draw:connector":
            case "draw:measure": case "dr3d:scene": {
                Linea l = new Linea();
                elemento(p, l);
                if (l.sb.length() > 0) sb.append("<p>").append(l.sb).append("</p>");
                sb.append(l.despues);
                break;
            }
            case "text:bookmark": case "text:bookmark-start": case "text:reference-mark":
            case "text:reference-mark-start":
                ancla(p, sb);
                break;
            case "text:tracked-changes": case "text:sequence-decls": case "text:variable-decls":
            case "text:user-field-decls": case "text:dde-connection-decls": case "office:forms":
            case "office:annotation": case "draw:enhanced-geometry": case "svg:title": case "svg:desc":
            case "office:event-listeners": case "draw:glue-point": case "text:number":
            case "table:table-source": case "office:dde-source": case "table:named-expressions":
            case "table:calculation-settings": case "table:content-validations": case "table:database-ranges":
            case "presentation:notes": case "draw:contour-polygon": case "draw:contour-path":
                Lectura.saltar(p);
                break;
            default:
                // Fuentes de índices (text:table-of-content-source…): solo plantillas, no texto.
                if (n.endsWith("-source") && n.startsWith("text:")) Lectura.saltar(p);
                // Secciones, índices, text:numbered-paragraph…: se recorren sus hijos.
                break;
        }
    }

    /** Controla la profundidad para no desbordar la pila con documentos anidados sin fin. */
    private boolean entrar(XmlPullParser p) throws XmlPullParserException, IOException {
        if (profundidad >= MAX_ANIDADO) {
            Lectura.saltar(p);
            return false;
        }
        profundidad++;
        return true;
    }

    private static void ancla(XmlPullParser p, StringBuilder sb) {
        String n = Odf.atr(p, "text:name");
        if (n != null && !n.isEmpty()) sb.append("<a id=\"").append(Html.esc(n)).append("\"></a>");
    }

    /** Escribe un text:p o text:h completo. */
    void parrafo(XmlPullParser p, StringBuilder sb, boolean titulo, boolean enLista)
            throws XmlPullParserException, IOException {
        Odf.Estilo e = est.estilo("paragraph", Odf.atr(p, "text:style-name"));
        List<Odf.Estilo> cad = est.cadena(e, "paragraph", modo != MODO_DIAPO);
        int nivel = 1;
        if (titulo) {
            nivel = Lectura.entero(Odf.atr(p, "text:outline-level"), 0);
            for (int i = 0; nivel <= 0 && i < cad.size(); i++) nivel = cad.get(i).nivelEsquema;
            nivel = Math.max(1, Math.min(6, nivel));
        }
        boolean principal = modo == MODO_TEXTO && contenedores == 0;
        if (principal && escrito && (salto(cad, "fo:break-before")
                || (e != null && e.maestra != null && !e.maestra.isEmpty()))) {
            sb.append("<span class=salto></span>");
        }

        Linea l = new Linea();
        l.titulo = titulo;
        enLinea(p, l);

        StringBuilder css = new StringBuilder();
        cssParrafo(css, cad, enLista);
        cssTexto(css, cad, !(titulo && modo == MODO_TEXTO));
        String et = titulo && modo == MODO_TEXTO ? "h" + nivel : "p";
        sb.append('<').append(et);
        if (titulo && modo == MODO_TEXTO) {
            String id = "t" + (++numTitulos);
            sb.append(" id=").append(id);
            String texto = l.plano.toString().replaceAll("\\s+", " ").trim();
            if (!texto.isEmpty()) {
                indice.add(new Html.Titulo(nivel, texto, id));
                String clave = texto.toLowerCase(Locale.ROOT);
                if (!titulos.containsKey(clave)) titulos.put(clave, id);
            }
        }
        if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
        sb.append('>');
        if (l.hay) sb.append(l.sb);
        else sb.append("<br>");
        sb.append("</").append(et).append('>');
        sb.append(l.despues);
        if (principal) {
            if (salto(cad, "fo:break-after")) sb.append("<span class=salto></span>");
            escrito = true;
        }
    }

    private static boolean salto(List<Odf.Estilo> cad, String clave) {
        return "page".equals(Odf.prop(cad, Odf.PARRAFO, clave));
    }

    // --- Formato ---

    /** Tamaño de letra de la cadena: {pt absoluto o NaN, producto de los porcentajes previos}. */
    static double[] tamano(List<Odf.Estilo> cad) {
        double f = 1;
        for (Odf.Estilo e : cad) {
            String v = e.texto.get("fo:font-size");
            if (v == null) continue;
            double pc = Odf.porcentaje(v);
            if (!Double.isNaN(pc)) {
                if (pc > 0) f *= pc;
                continue;
            }
            double px = Odf.px(v);
            if (!Double.isNaN(px) && px > 0) return new double[] {px * 0.75 * f, f};
        }
        return new double[] {Double.NaN, f};
    }

    /** CSS de las propiedades de texto; devuelve 1 si es superíndice, -1 si subíndice y 0 si no. */
    int cssTexto(StringBuilder css, List<Odf.Estilo> cad, boolean tamanos) {
        if (cad.isEmpty()) return 0;
        boolean normales = modo != MODO_HOJA;
        String v = Odf.prop(cad, Odf.TEXTO, "fo:font-weight");
        if (v != null) {
            boolean negrita = v.equals("bold") || Lectura.entero(v, 400) >= 600;
            if (negrita) css.append("font-weight:bold;");
            else if (normales) css.append("font-weight:normal;");
        }
        v = Odf.prop(cad, Odf.TEXTO, "fo:font-style");
        if (v != null) {
            if (v.equals("italic") || v.equals("oblique")) css.append("font-style:italic;");
            else if (normales) css.append("font-style:normal;");
        }
        boolean u = linea(cad, "style:text-underline-style", "style:text-underline-type");
        boolean s = linea(cad, "style:text-line-through-style", "style:text-line-through-type");
        if (u || s) css.append("text-decoration:").append(u ? "underline " : "").append(s ? "line-through" : "").append(';');
        if (!"true".equals(Odf.prop(cad, Odf.TEXTO, "style:use-window-font-color"))) {
            String c = Odf.color(Odf.prop(cad, Odf.TEXTO, "fo:color"));
            if (c != null && (modo == MODO_DIAPO || !c.equals("000000"))) css.append("color:#").append(c).append(';');
        }
        String fondo = Odf.color(Odf.prop(cad, Odf.TEXTO, "fo:background-color"));
        if (fondo != null) css.append("background:#").append(fondo).append(';');
        v = Odf.prop(cad, Odf.TEXTO, "fo:text-transform");
        if (v != null && (v.equals("uppercase") || v.equals("lowercase") || v.equals("capitalize"))) {
            css.append("text-transform:").append(v).append(';');
        }
        if ("small-caps".equals(Odf.prop(cad, Odf.TEXTO, "fo:font-variant"))) css.append("font-variant:small-caps;");
        if (tamanos && modo != MODO_HOJA) {
            double[] t = tamano(cad);
            if (!Double.isNaN(t[0])) {
                if (modo == MODO_DIAPO) {
                    css.append("font-size:").append(Html.px(Math.max(1, Math.min(400, t[0] / 0.75 * escala))))
                            .append("px;");
                } else if (Math.abs(t[0] - base) > 0.01) {
                    css.append("font-size:").append(Html.px(Math.max(0.5, Math.min(4, t[0] / base)))).append("rem;");
                }
            } else if (Math.abs(t[1] - 1) > 0.001) {
                css.append("font-size:").append(Html.px(Math.max(20, Math.min(400, t[1] * 100)))).append("%;");
            }
        }
        return vertical(Odf.prop(cad, Odf.TEXTO, "style:text-position"));
    }

    private static boolean linea(List<Odf.Estilo> cad, String estilo, String tipo) {
        String a = Odf.prop(cad, Odf.TEXTO, estilo), b = Odf.prop(cad, Odf.TEXTO, tipo);
        if ("none".equals(b)) return false;
        if (a != null) return !a.equals("none");
        return b != null;
    }

    private static int vertical(String v) {
        if (v == null) return 0;
        v = v.trim();
        int i = v.indexOf(' ');
        String t = i >= 0 ? v.substring(0, i) : v;
        if (t.equals("super")) return 1;
        if (t.equals("sub")) return -1;
        double pc = Odf.porcentaje(t);
        if (Double.isNaN(pc) || pc == 0) return 0;
        return pc > 0 ? 1 : -1;
    }

    private static boolean oculto(List<Odf.Estilo> cad) {
        return "none".equals(Odf.prop(cad, Odf.TEXTO, "text:display"));
    }

    /** CSS de las propiedades de párrafo: alineación, sangrías, fondo y, en Odp, espaciado. */
    void cssParrafo(StringBuilder css, List<Odf.Estilo> cad, boolean enLista) {
        if (cad.isEmpty()) return;
        String v = Odf.prop(cad, Odf.PARRAFO, "fo:text-align");
        if (v != null) {
            switch (v) {
                case "center": css.append("text-align:center;"); break;
                case "end": case "right": css.append("text-align:right;"); break;
                case "justify": css.append("text-align:justify;"); break;
                case "left": css.append("text-align:left;"); break;
                default: break;
            }
        }
        if (!enLista) {
            double ml = Odf.px(Odf.prop(cad, Odf.PARRAFO, "fo:margin-left"));
            if (!Double.isNaN(ml) && Math.abs(ml) > 0.5) {
                css.append("margin-left:").append(Html.px(Math.max(-200, Math.min(800, ml)) * escala)).append("px;");
            }
            double ti = Odf.px(Odf.prop(cad, Odf.PARRAFO, "fo:text-indent"));
            if (!Double.isNaN(ti) && Math.abs(ti) > 0.5) {
                css.append("text-indent:").append(Html.px(Math.max(-400, Math.min(400, ti)) * escala)).append("px;");
            }
        }
        String fondo = Odf.color(Odf.prop(cad, Odf.PARRAFO, "fo:background-color"));
        if (fondo != null) css.append("background:#").append(fondo).append(';');
        v = Odf.prop(cad, Odf.PARRAFO, "style:writing-mode");
        if (v != null && v.startsWith("rl")) css.append("direction:rtl;");
        if (modo == MODO_DIAPO) {
            double a = Odf.px(Odf.prop(cad, Odf.PARRAFO, "fo:margin-top"));
            if (!Double.isNaN(a) && a > 0) css.append("margin-top:").append(Html.px(Math.min(a, 500) * escala)).append("px;");
            a = Odf.px(Odf.prop(cad, Odf.PARRAFO, "fo:margin-bottom"));
            if (!Double.isNaN(a) && a > 0) css.append("margin-bottom:").append(Html.px(Math.min(a, 500) * escala)).append("px;");
            v = Odf.prop(cad, Odf.PARRAFO, "fo:line-height");
            double pc = Odf.porcentaje(v);
            if (!Double.isNaN(pc) && pc > 0) {
                css.append("line-height:").append(Html.px(Math.max(0.5, Math.min(5, 1.2 * pc)))).append(';');
            } else {
                double lh = Odf.px(v);
                if (!Double.isNaN(lh) && lh > 0) css.append("line-height:").append(Html.px(Math.min(lh, 500) * escala)).append("px;");
            }
        }
    }

    // --- Texto en línea ---

    /** Lee el contenido en línea del elemento actual (que queda consumido). */
    void enLinea(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        if (!entrar(p)) return;
        try {
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                int t = p.getEventType();
                if (t == XmlPullParser.TEXT) {
                    texto(l, p.getText());
                } else if (t == XmlPullParser.START_TAG) {
                    try {
                        elemento(p, l);
                    } catch (RuntimeException e) {
                        // Se ignora el elemento que no se ha podido interpretar.
                    }
                }
            }
        } finally {
            profundidad--;
        }
    }

    /** Texto con los blancos agrupados como dice ODF (varios seguidos cuentan como uno). */
    private void texto(Linea l, String s) {
        if (s == null || s.isEmpty()) return;
        StringBuilder t = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!l.espacio) {
                    t.append(' ');
                    l.espacio = true;
                }
            } else {
                t.append(c);
                l.espacio = false;
                l.hay = true;
                caracteres++;
            }
        }
        Html.esc(l.sb, t.toString());
        l.plano.append(t);
    }

    private void literal(Linea l, String html, String plano) {
        l.sb.append(html);
        l.plano.append(plano);
        l.espacio = false;
        l.hay = true;
        caracteres++;
    }

    private void elemento(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        switch (Odf.nombre(p)) {
            case "text:span": span(p, l); break;
            case "text:a": enlace(p, l); break;
            case "draw:a": enlace(p, l); break;
            case "text:s": {
                int c = Math.max(1, Math.min(MAX_ESPACIOS, Lectura.entero(Odf.atr(p, "text:c"), 1)));
                StringBuilder s = new StringBuilder(c);
                for (int i = 0; i < c; i++) s.append(' ');
                literal(l, s.toString(), s.toString());
                break;
            }
            case "text:tab": literal(l, "\t", "\t"); break;
            case "text:line-break":
                l.sb.append("<br>");
                l.plano.append(' ');
                l.espacio = true;
                l.hay = true;
                break;
            case "text:bookmark": case "text:bookmark-start": case "text:reference-mark":
            case "text:reference-mark-start":
                ancla(p, l.sb);
                break;
            case "text:note": nota(p, l); break;
            case "text:number": {
                String n = Lectura.texto(p).trim();
                if (!n.isEmpty()) {
                    Html.esc(l.sb, n + " ");
                    l.plano.append(n).append(' ');
                    l.hay = true;
                    l.espacio = true;
                }
                break;
            }
            case "draw:frame": marco(p, l); break;
            case "draw:rect": case "draw:ellipse": case "draw:circle": case "draw:custom-shape":
            case "draw:polygon": case "draw:polyline": case "draw:path": case "draw:regular-polygon":
            case "draw:caption":
                forma(p, l);
                break;
            case "draw:line": case "draw:connector": case "draw:measure": case "dr3d:scene":
                if (modo == MODO_HOJA) hayDibujos = true;
                Lectura.saltar(p);
                break;
            case "office:annotation": case "text:ruby-text": case "svg:title": case "svg:desc":
            case "text:tracked-changes": case "office:event-listeners": case "draw:enhanced-geometry":
            case "text:note-citation": case "text:note-body":
                Lectura.saltar(p);
                break;
            default: break; // campos, text:meta, text:ruby, draw:g…: se recorren sus hijos
        }
    }

    private void span(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        Odf.Estilo e = est.estilo("text", Odf.atr(p, "text:style-name"));
        List<Odf.Estilo> cad = est.cadena(e, "text", false);
        if (oculto(cad)) {
            Lectura.saltar(p);
            return;
        }
        StringBuilder css = new StringBuilder();
        int v = cssTexto(css, cad, !(l.titulo && modo == MODO_TEXTO));
        if (css.length() > 0) l.sb.append("<span style=\"").append(css).append("\">");
        if (v > 0) l.sb.append("<sup>");
        else if (v < 0) l.sb.append("<sub>");
        enLinea(p, l);
        if (v > 0) l.sb.append("</sup>");
        else if (v < 0) l.sb.append("</sub>");
        if (css.length() > 0) l.sb.append("</span>");
    }

    private void enlace(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        String href = destino(Odf.atr(p, "xlink:href"));
        if (href != null) l.sb.append("<a href=\"").append(Html.esc(href)).append("\">");
        enLinea(p, l);
        if (href != null) l.sb.append("</a>");
    }

    /** Destino seguro de un enlace: externo con esquema conocido o ancla interna; null si no vale. */
    String destino(String href) {
        if (href == null) return null;
        String h = href.trim();
        if (h.isEmpty()) return null;
        if (h.startsWith("#")) {
            String n = Odf.decodificar(h.substring(1));
            if (destinos != null) {
                String r = destinos.get(n);
                if (r != null) return r;
            }
            int barra = n.lastIndexOf('|');
            if (barra >= 0) {
                String tipo = n.substring(barra + 1);
                n = n.substring(0, barra);
                if (tipo.equals("outline")) {
                    if (modo != MODO_TEXTO) return null;
                    pendientes.add(n);
                    return "#\u0002" + (pendientes.size() - 1) + "\u0003";
                }
            }
            return n.isEmpty() ? null : "#" + n;
        }
        String m = h.toLowerCase(Locale.ROOT);
        if (m.startsWith("http://") || m.startsWith("https://") || m.startsWith("mailto:") || m.startsWith("tel:")
                || m.startsWith("ftp://") || m.startsWith("geo:")) {
            return h;
        }
        return null;
    }

    /** Sustituye los enlaces a títulos ("#1.2.Título|outline") por los id de los títulos. */
    private void resolverTitulos(StringBuilder sb) {
        Matcher m = MARCA_TITULO.matcher(sb);
        StringBuilder r = new StringBuilder(sb.length());
        int ultimo = 0;
        while (m.find()) {
            r.append(sb, ultimo, m.start());
            int i = Lectura.entero(m.group(1), -1);
            String id = i >= 0 && i < pendientes.size() ? buscarTitulo(pendientes.get(i)) : null;
            r.append(id != null ? id : "");
            ultimo = m.end();
        }
        r.append(sb, ultimo, sb.length());
        sb.setLength(0);
        sb.append(r);
    }

    private String buscarTitulo(String nombre) {
        String n = nombre.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        String id = titulos.get(n);
        if (id != null) return id;
        // LibreOffice antepone la numeración: "1.2.Título".
        String sinNumero = n.replaceFirst("^[0-9.]+\\s*", "");
        id = titulos.get(sinNumero);
        if (id != null) return id;
        for (Map.Entry<String, String> e : titulos.entrySet()) {
            if (e.getKey().endsWith(sinNumero) && !sinNumero.isEmpty()) return e.getValue();
        }
        return null;
    }

    private void nota(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        if (modo != MODO_TEXTO) {
            Lectura.saltar(p);
            return;
        }
        boolean finales = "endnote".equals(Odf.atr(p, "text:note-class"));
        String cita = null;
        StringBuilder cuerpo = new StringBuilder();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Odf.nombre(p);
            if (n.equals("text:note-citation")) {
                cita = Lectura.texto(p).trim();
            } else if (n.equals("text:note-body")) {
                contenedores++;
                try {
                    bloques(p, cuerpo, 0, null);
                } finally {
                    contenedores--;
                }
            } else {
                Lectura.saltar(p);
            }
        }
        int k = ++numNotas;
        if (cita == null || cita.isEmpty()) cita = Integer.toString(k);
        String c = Html.esc(cita);
        l.sb.append("<sup class=ref><a id=\"ref").append(k).append("\" href=\"#nota").append(k).append("\">")
                .append(c).append("</a></sup>");
        l.hay = true;
        l.espacio = false;
        (finales ? notasFinales : notas).append("<div class=nt id=\"nota").append(k).append("\"><a href=\"#ref")
                .append(k).append("\">").append(c).append("</a><div>").append(cuerpo).append("</div></div>");
    }

    // --- Imágenes, marcos y formas ---

    /** Imagen de un draw:image como URI data: (o null); el elemento queda consumido. */
    String uriImagen(XmlPullParser p) throws XmlPullParserException, IOException {
        String href = Odf.atr(p, "xlink:href");
        String uri = null;
        if (href != null && !href.trim().isEmpty()) {
            uri = imagenPaquete(href);
            Lectura.saltar(p);
            return uri;
        }
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (Lectura.inicio(p) && uri == null && "office:binary-data".equals(Odf.nombre(p))) {
                uri = binaria(Lectura.texto(p));
            }
        }
        return uri;
    }

    /** Imagen del paquete por su xlink:href ("Pictures/x.png"); null si es externa o no se puede ver. */
    String imagenPaquete(String href) {
        String h = href.trim();
        if (h.contains("://") || h.toLowerCase(Locale.ROOT).startsWith("data:")) return null;
        String uri = pq.imagen(h);
        if (uri == null && h.indexOf('%') >= 0) uri = pq.imagen(Odf.decodificar(h));
        return uri;
    }

    /** office:binary-data (base64) como URI data:, si es un formato que el WebView sabe mostrar. */
    private static String binaria(String b64) {
        StringBuilder s = new StringBuilder(b64.length());
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'
                    || c == '=') {
                s.append(c);
            } else if (c > ' ') {
                return null;
            }
        }
        if (s.length() < 16) return null;
        byte[] b;
        try {
            b = Base64.getDecoder().decode(s.substring(0, 24));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String mime = null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') mime = "image/png";
        else if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) mime = "image/jpeg";
        else if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') mime = "image/gif";
        else if (b[0] == 'B' && b[1] == 'M') mime = "image/bmp";
        if (mime == null) return null;
        return "data:" + mime + ";base64," + s;
    }

    /** draw:frame dentro del texto: imagen en línea; cuadro de texto detrás del párrafo. */
    private void marco(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        if (modo == MODO_HOJA) {
            hayDibujos = true;
            Lectura.saltar(p);
            return;
        }
        double w = Odf.px(Odf.atr(p, "svg:width"));
        String relativo = Odf.atr(p, "style:rel-width");
        String nombre = Odf.atr(p, "draw:name");
        Odf.Estilo ge = est.estilo("graphic", Odf.atr(p, "draw:style-name"));
        String src = null, alt = null;
        boolean imagen = false, objeto = false;
        StringBuilder caja = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Odf.nombre(p)) {
                case "draw:image":
                    imagen = true;
                    if (src == null) src = uriImagen(p);
                    else Lectura.saltar(p);
                    break;
                case "draw:text-box": {
                    if (caja == null) caja = new StringBuilder();
                    contenedores++;
                    try {
                        bloques(p, caja, 0, null);
                    } finally {
                        contenedores--;
                    }
                    break;
                }
                case "draw:object": case "draw:object-ole": case "draw:plugin": case "draw:applet":
                case "draw:floating-frame":
                    objeto = true;
                    Lectura.saltar(p);
                    break;
                case "svg:title": case "svg:desc": {
                    String t = Lectura.texto(p).trim();
                    if (alt == null && !t.isEmpty()) alt = t;
                    break;
                }
                default: Lectura.saltar(p); break;
            }
        }
        if (src != null) {
            l.sb.append("<img src=\"").append(src).append('"');
            if (nombre != null && !nombre.isEmpty()) l.sb.append(" id=\"").append(Html.esc(nombre)).append('"');
            if (alt != null) l.sb.append(" alt=\"").append(Html.esc(alt)).append('"');
            double pc = Odf.porcentaje(relativo);
            if (!Double.isNaN(pc) && pc > 0 && pc <= 1) {
                l.sb.append(" style=\"width:").append(Html.px(pc * 100)).append("%\"");
            } else if (!Double.isNaN(w) && w > 0) {
                l.sb.append(" style=\"width:").append(Html.px(Math.min(w, 4000) * escala)).append("px\"");
            }
            l.sb.append('>');
            l.hay = true;
            l.espacio = false;
        } else if (objeto) {
            l.sb.append("<span class=nota>[Objeto incrustado]</span>");
            l.hay = true;
            l.espacio = false;
        } else if (imagen) {
            l.sb.append("<span class=nota>[imagen]</span>");
            l.hay = true;
            l.espacio = false;
        }
        if (caja != null && caja.length() > 0) {
            List<Odf.Estilo> cad = est.cadena(ge, "graphic", false);
            boolean borde = false;
            for (String lado : new String[] {"fo:border", "fo:border-top", "fo:border-left"}) {
                if (Odf.borde(Odf.prop(cad, Odf.OTRAS, lado)) != null) borde = true;
            }
            l.despues.append(borde ? "<div class=caja" : "<div class=marco");
            if (nombre != null && !nombre.isEmpty() && src == null) {
                l.despues.append(" id=\"").append(Html.esc(nombre)).append('"');
            }
            String fondo = Odf.color(Odf.prop(cad, Odf.OTRAS, "fo:background-color"));
            if (fondo != null) l.despues.append(" style=\"background:#").append(fondo).append('"');
            l.despues.append('>').append(caja).append("</div>");
        }
    }

    /** Forma de dibujo dentro del texto: solo se muestra su texto, en un recuadro. */
    private void forma(XmlPullParser p, Linea l) throws XmlPullParserException, IOException {
        if (modo == MODO_HOJA) {
            hayDibujos = true;
            Lectura.saltar(p);
            return;
        }
        StringBuilder c = new StringBuilder();
        int antes = caracteres;
        contenedores++;
        try {
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                switch (Odf.nombre(p)) {
                    case "text:p": parrafo(p, c, false, false); break;
                    case "text:h": parrafo(p, c, true, false); break;
                    case "text:list": lista(p, c, 0, null); break;
                    default: Lectura.saltar(p); break;
                }
            }
        } finally {
            contenedores--;
        }
        if (caracteres > antes) l.despues.append("<div class=caja>").append(c).append("</div>");
    }

    // --- Listas ---

    /** Escribe un text:list; nivel es 0 para la lista exterior. */
    void lista(XmlPullParser p, StringBuilder sb, int nivel, String heredado)
            throws XmlPullParserException, IOException {
        if (!entrar(p)) return;
        contenedores++;
        try {
            String nombre = Odf.atr(p, "text:style-name");
            if (nombre == null) nombre = heredado;
            Odf.ListaEstilo le = est.lista(nombre);
            if (le == null) le = listaDefecto;
            Odf.NivelLista nv = le != null ? le.nivel(nivel + 1) : null;
            boolean numerada = nv != null && nv.tipo == 1;
            boolean sinMarca = numerada && nv.formato.trim().isEmpty();
            String clave = (nombre == null ? "" : nombre) + "/" + nivel;
            boolean continuar = "true".equals(Odf.atr(p, "text:continue-numbering"))
                    || Odf.atr(p, "text:continue-list") != null;
            int inicio = numerada ? nv.inicio : 1;
            Integer previo = contadores.get(clave);
            if (continuar && previo != null) inicio = previo + 1;
            String et = numerada && !sinMarca ? "ol" : "ul";

            StringBuilder css = new StringBuilder();
            if (le != null) {
                double s = sangria(le, nivel + 1), s0 = nivel == 0 ? 0 : sangria(le, nivel);
                if (!Double.isNaN(s) && !Double.isNaN(s0) && s - s0 > 1) {
                    css.append("padding-left:").append(Html.px(Math.min(s - s0, 400) * escala)).append("px;");
                }
            }
            if (sinMarca) css.append("list-style:none;");
            else if (nv != null && nv.tipo == 0) css.append(tipoVineta(nv.vineta));
            sb.append('<').append(et);
            if (et.equals("ol")) {
                String tipo = tipoNumero(nv.formato);
                if (!tipo.equals("1")) sb.append(" type=").append(tipo);
                if (inicio != 1) sb.append(" start=").append(inicio);
            }
            if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
            sb.append('>');

            String cssNivel = "";
            if (modo == MODO_DIAPO && baseEsquema != null) {
                Odf.Estilo oe = est.estilo("presentation", baseEsquema + (nivel + 1));
                if (oe != null) {
                    StringBuilder c = new StringBuilder();
                    cssTexto(c, est.cadena(oe, "presentation", false), true);
                    cssNivel = c.toString();
                }
            }

            int numero = inicio - 1;
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                String n = Odf.nombre(p);
                boolean cabecera = n.equals("text:list-header");
                if (!cabecera && !n.equals("text:list-item")) {
                    Lectura.saltar(p);
                    continue;
                }
                String valor = null;
                if (!cabecera) {
                    String sv = Odf.atr(p, "text:start-value");
                    if (sv != null) {
                        numero = Lectura.entero(sv, numero + 1) - 1;
                        valor = Integer.toString(numero + 1);
                    }
                    numero++;
                }
                StringBuilder c = new StringBuilder();
                try {
                    bloques(p, c, nivel + 1, nombre);
                } catch (RuntimeException e) {
                    // Elemento raro: se deja lo leído.
                }
                boolean sinVineta = cabecera || c.length() == 0 || c.indexOf("<ul") == 0 || c.indexOf("<ol") == 0;
                sb.append("<li");
                if (valor != null && et.equals("ol")) sb.append(" value=").append(valor);
                if (sinVineta || !cssNivel.isEmpty()) {
                    sb.append(" style=\"").append(sinVineta ? "list-style:none;" : "").append(cssNivel).append('"');
                }
                sb.append('>').append(c).append("</li>");
            }
            sb.append("</").append(et).append('>');
            if (numerada) contadores.put(clave, numero);
        } finally {
            contenedores--;
            profundidad--;
        }
    }

    private static double sangria(Odf.ListaEstilo le, int n) {
        Odf.NivelLista nv = le.nivel(n);
        return nv != null ? nv.sangria : Double.NaN;
    }

    private static String tipoNumero(String f) {
        if (f == null) return "1";
        switch (f.trim()) {
            case "a": return "a";
            case "A": return "A";
            case "i": return "i";
            case "I": return "I";
            default: return "1";
        }
    }

    /** CSS para el carácter de viñeta (los de fuentes de símbolos se cambian por el punto normal). */
    private static String tipoVineta(String v) {
        if (v == null || v.isEmpty()) return "";
        int c = v.codePointAt(0);
        if (c >= 0xE000 && c <= 0xF8FF) return "list-style-type:disc;";
        switch (c) {
            case '•': case '●': return "list-style-type:disc;";
            case '◦': case '○': return "list-style-type:circle;";
            case '▪': case '■': return "list-style-type:square;";
            case '"': case '\\': return "";
            default: break;
        }
        if (c < 0x20) return "";
        return "list-style-type:&quot;" + Html.esc(new String(Character.toChars(c))) + " &quot;;";
    }

    // --- Tablas ---

    /** Escribe un table:table de texto o de diapositiva. */
    void tabla(XmlPullParser p, StringBuilder sb) throws XmlPullParserException, IOException {
        if (!entrar(p)) return;
        contenedores++;
        int columnasFuera = columnas;
        try {
            String nombre = Odf.atr(p, "table:name");
            Odf.Estilo te = est.estilo("table", Odf.atr(p, "table:style-name"));
            List<Double> anchos = new ArrayList<>();
            StringBuilder filas = new StringBuilder();
            int[] num = {0};
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                try {
                    switch (Odf.nombre(p)) {
                        case "table:table-column": {
                            int rep = Math.max(1, Math.min(MAX_COLS_TABLA - anchos.size(),
                                    Lectura.entero(Odf.atr(p, "table:number-columns-repeated"), 1)));
                            Odf.Estilo ce = est.estilo("table-column", Odf.atr(p, "table:style-name"));
                            double w = Odf.px(Odf.prop(est.cadena(ce, "table-column", false), Odf.OTRAS,
                                    "style:column-width"));
                            for (int i = 0; i < rep && anchos.size() < MAX_COLS_TABLA; i++) anchos.add(w);
                            break;
                        }
                        case "table:table-row":
                            columnas = anchos.isEmpty() ? MAX_COLS_TABLA : anchos.size();
                            fila(p, filas, num);
                            break;
                        case "table:table-header-rows": case "table:table-rows": case "table:table-row-group":
                        case "table:table-columns": case "table:table-header-columns":
                        case "table:table-column-group": case "text:soft-page-break":
                            break;
                        default: Lectura.saltar(p); break;
                    }
                } catch (RuntimeException e) {
                    // Fila o columna rara: se ignora.
                }
            }

            List<Odf.Estilo> cad = est.cadena(te, "table", false);
            StringBuilder css = new StringBuilder();
            if (modo == MODO_TEXTO) {
                double pc = Odf.porcentaje(Odf.prop(cad, Odf.OTRAS, "style:rel-width"));
                double w = Odf.px(Odf.prop(cad, Odf.OTRAS, "style:width"));
                if (!Double.isNaN(pc) && pc > 0 && pc <= 1) css.append("width:").append(Html.px(pc * 100)).append("%;");
                else if (!Double.isNaN(w) && w > 0) css.append("width:").append(Html.px(Math.min(w, 4000))).append("px;");
            }
            String fondo = Odf.color(Odf.prop(cad, Odf.OTRAS, "fo:background-color"));
            if (fondo != null) css.append("background:#").append(fondo).append(';');

            if (modo != MODO_DIAPO) sb.append("<div class=tabla>");
            sb.append(modo == MODO_DIAPO ? "<table class=pt" : "<table");
            if (modo == MODO_TEXTO && nombre != null && !nombre.isEmpty()) {
                sb.append(" id=\"").append(Html.esc(nombre)).append('"');
            }
            if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
            sb.append('>');
            boolean hayAnchos = false;
            for (double w : anchos) if (!Double.isNaN(w) && w > 0) hayAnchos = true;
            if (hayAnchos) {
                sb.append("<colgroup>");
                for (double w : anchos) {
                    if (!Double.isNaN(w) && w > 0) {
                        sb.append("<col style=\"width:").append(Html.px(Math.min(w, 4000) * escala)).append("px\">");
                    } else {
                        sb.append("<col>");
                    }
                }
                sb.append("</colgroup>");
            }
            sb.append(filas).append("</table>");
            if (modo != MODO_DIAPO) sb.append("</div>");
        } finally {
            columnas = columnasFuera;
            contenedores--;
            profundidad--;
        }
    }

    private void fila(XmlPullParser p, StringBuilder filas, int[] num) throws XmlPullParserException, IOException {
        if (num[0] >= MAX_FILAS_TABLA) {
            Lectura.saltar(p);
            return;
        }
        int rep = Math.max(1, Lectura.entero(Odf.atr(p, "table:number-rows-repeated"), 1));
        StringBuilder f = new StringBuilder("<tr");
        if (modo == MODO_DIAPO) {
            Odf.Estilo re = est.estilo("table-row", Odf.atr(p, "table:style-name"));
            List<Odf.Estilo> cad = est.cadena(re, "table-row", false);
            double h = Odf.px(Odf.prop(cad, Odf.OTRAS, "style:row-height"));
            if (Double.isNaN(h)) h = Odf.px(Odf.prop(cad, Odf.OTRAS, "style:min-row-height"));
            if (!Double.isNaN(h) && h > 0) f.append(" style=\"height:").append(Html.px(Math.min(h, 2000) * escala)).append("px\"");
        }
        f.append('>');
        int antes = caracteres, inicioCeldas = f.length();
        int[] celdas = {0};
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            if ("table:table-cell".equals(Odf.nombre(p))) {
                try {
                    celda(p, f, celdas);
                } catch (RuntimeException e) {
                    // Celda rara: se ignora.
                }
            } else {
                Lectura.saltar(p); // celdas cubiertas por una fusión y demás
            }
        }
        f.append("</tr>");
        // Las filas vacías repetidas (relleno hasta el final de la tabla) se escriben una sola vez.
        boolean vacia = caracteres == antes && f.indexOf("<img", inicioCeldas) < 0 && f.indexOf("background", inicioCeldas) < 0;
        int veces = Math.min(vacia ? 1 : rep, MAX_FILAS_TABLA - num[0]);
        for (int i = 0; i < veces; i++) filas.append(f);
        num[0] += veces;
    }

    private void celda(XmlPullParser p, StringBuilder f, int[] celdas) throws XmlPullParserException, IOException {
        if (celdas[0] >= MAX_COLS_TABLA) {
            Lectura.saltar(p);
            return;
        }
        int cs = Math.max(1, Math.min(1000, Lectura.entero(Odf.atr(p, "table:number-columns-spanned"), 1)));
        int rs = Math.max(1, Math.min(1000, Lectura.entero(Odf.atr(p, "table:number-rows-spanned"), 1)));
        int rep = Math.max(1, Math.min(MAX_COLS_TABLA, Lectura.entero(Odf.atr(p, "table:number-columns-repeated"), 1)));
        Odf.Estilo ce = est.estilo("table-cell", Odf.atr(p, "table:style-name"));
        List<Odf.Estilo> cad = est.cadena(ce, "table-cell", false);
        StringBuilder css = new StringBuilder();
        String fondo = Odf.color(Odf.prop(cad, Odf.OTRAS, "fo:background-color"));
        if (fondo != null) css.append("background:#").append(fondo).append(';');
        String b = Odf.prop(cad, Odf.OTRAS, "fo:border");
        if (b != null) {
            String v = Odf.borde(b);
            css.append("border:").append(v != null ? v : "none").append(';');
        }
        for (String lado : new String[] {"top", "right", "bottom", "left"}) {
            String v = Odf.prop(cad, Odf.OTRAS, "fo:border-" + lado);
            if (v == null) continue;
            String r = Odf.borde(v);
            css.append("border-").append(lado).append(':').append(r != null ? r : "none").append(';');
        }
        double pad = Odf.px(Odf.prop(cad, Odf.OTRAS, "fo:padding"));
        if (!Double.isNaN(pad) && pad >= 0) css.append("padding:").append(Html.px(Math.min(pad, 100) * escala)).append("px;");
        String va = Odf.prop(cad, Odf.OTRAS, "style:vertical-align");
        if ("middle".equals(va) || "bottom".equals(va) || "top".equals(va)) {
            css.append("vertical-align:").append(va).append(';');
        }
        cssTexto(css, cad, true);
        StringBuilder c = new StringBuilder();
        bloques(p, c, 0, null);
        StringBuilder td = new StringBuilder("<td");
        if (cs > 1) td.append(" colspan=").append(cs);
        if (rs > 1) td.append(" rowspan=").append(rs);
        if (css.length() > 0) td.append(" style=\"").append(css).append('"');
        td.append('>').append(c).append("</td>");
        // Las celdas vacías repetidas se limitan a las columnas declaradas.
        if (c.length() == 0 && css.length() == 0) rep = Math.min(rep, Math.max(1, columnas - celdas[0]));
        rep = Math.min(rep, MAX_COLS_TABLA - celdas[0]);
        for (int i = 0; i < rep; i++) f.append(td);
        celdas[0] += rep;
    }
}
