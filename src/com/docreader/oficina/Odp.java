package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte una presentación (.odp) o un dibujo (.odg) de OpenDocument en HTML: cada página es
 * una caja de 960 px de ancho con sus formas en posición absoluta, como en Pptx.
 */
final class Odp {
    private static final double ANCHO = 960;
    private static final String VIEWPORT = "width=1000";

    private static final String CSS =
            "body.odp{padding:6px 20px 30px;background:#e4e4e4}"
            + ".num{font-size:15px;color:#555;margin:16px 0 6px}"
            + ".diapo{position:relative;width:960px;overflow:hidden;background:#fff;"
            + "border:1px solid #c4c4c4;line-height:1.2;color:#000}"
            + ".f{position:absolute;box-sizing:border-box}"
            + ".tx{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;"
            + "box-sizing:border-box;overflow-wrap:break-word}"
            + ".tx p,.tx li{white-space:pre-wrap;margin:0}"
            + ".tx ul,.tx ol{margin:0;padding-left:1.3em}"
            + ".tx img{max-width:100%}"
            + ".im{position:absolute;left:0;top:0;right:0;bottom:0;background-size:100% 100%;"
            + "background-repeat:no-repeat;background-position:center}"
            + ".im.mosaico{background-size:auto;background-repeat:repeat}"
            + ".fijo{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;"
            + "justify-content:center;border:1px dashed #999;color:#666;font-size:16px;"
            + "background:rgba(240,240,240,.6)}"
            + ".pt{width:100%;height:100%;border-collapse:collapse;table-layout:fixed}"
            + ".pt td{border:1px solid #8c8c8c;vertical-align:top;overflow:hidden;padding:3px 6px}"
            + ".pt td p{white-space:pre-wrap;margin:0}"
            + "svg.f{overflow:visible}"
            + "svg.fg{position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible}";

    private static final Pattern TRANSFORMACION = Pattern.compile(
            "(rotate|translate|scale|skewx|skewy)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMEROS = Pattern.compile("^[-+0-9.eE,\\s]*$");
    private static final Pattern CAMINO = Pattern.compile("^[MmLlHhVvCcSsQqTtAaZz0-9.,\\-+eE\\s]*$");

    /** Polígonos (en una caja de 100 × 100) de las formas personalizadas más comunes. */
    private static final Map<String, String> POLIGONOS = new HashMap<>();

    static {
        String[] f = {
                "diamond", "50,0 100,50 50,100 0,50",
                "flowchart-decision", "50,0 100,50 50,100 0,50",
                "isosceles-triangle", "50,0 100,100 0,100",
                "right-triangle", "0,0 100,100 0,100",
                "parallelogram", "25,0 100,0 75,100 0,100",
                "flowchart-data", "20,0 100,0 80,100 0,100",
                "trapezoid", "25,0 75,0 100,100 0,100",
                "pentagon", "50,0 100,38 81,100 19,100 0,38",
                "hexagon", "25,0 75,0 100,50 75,100 25,100 0,50",
                "octagon", "29,0 71,0 100,29 100,71 71,100 29,100 0,71 0,29",
                "right-arrow", "0,25 70,25 70,0 100,50 70,100 70,75 0,75",
                "left-arrow", "100,25 30,25 30,0 0,50 30,100 30,75 100,75",
                "up-arrow", "25,100 25,30 0,30 50,0 100,30 75,30 75,100",
                "down-arrow", "25,0 75,0 75,70 100,70 50,100 0,70 25,70",
                "left-right-arrow", "0,50 20,0 20,25 80,25 80,0 100,50 80,100 80,75 20,75 20,100",
                "star5", "50,0 61,35 98,35 68,57 79,91 50,70 21,91 32,57 2,35 39,35",
                "chevron", "0,0 75,0 100,50 75,100 0,100 25,50",
                "pentagon-right", "0,0 75,0 100,50 75,100 0,100",
                "cross", "33,0 67,0 67,33 100,33 100,67 67,67 67,100 33,100 33,67 0,67 0,33 33,33",
                "flowchart-merge", "0,0 100,0 50,100",
                "flowchart-extract", "50,0 100,100 0,100"};
        for (int i = 0; i < f.length; i += 2) POLIGONOS.put(f[i], f[i + 1]);
    }

    /** Relleno ya traducido: CSS de fondo, color plano (para SVG) o clase de imagen. */
    private static final class Fondo {
        String css, color, imagen;
        boolean mosaico;
    }

    /** Posición en px del documento (sin escalar) y giro en radianes. */
    private static final class Geo {
        double x, y, w, h, giro;
    }

    private final Paquete pq;
    private final Odf.Estilos est = new Odf.Estilos();
    private final Odt texto;
    private double k = -1, alto;
    private final Map<String, String> decoraciones = new HashMap<>();
    private final Map<String, Fondo> fondos = new HashMap<>();
    private final Map<String, String> clasesImagen = new HashMap<>();
    private final StringBuilder cssImagenes = new StringBuilder();
    private final Map<String, String> destinos = new HashMap<>();
    private int total;
    private boolean dibujo;

    private Odp(Paquete pq) {
        this.pq = pq;
        this.texto = new Odt(pq, est, Odt.MODO_DIAPO);
        texto.destinos = destinos;
    }

    static Html.Documento convertir(Paquete pq) throws Fallo {
        Odf.comprobar(pq);
        Odp o = new Odp(pq);
        StringBuilder sb = new StringBuilder();
        o.procesar(sb);
        return new Html.Documento(sb.toString(), CSS + o.cssImagenes, VIEWPORT, "odp");
    }

    private void procesar(StringBuilder sb) throws Fallo {
        nombresDePaginas();
        try {
            est.leerArchivo(pq, this::maestra);
        } catch (Exception ignorado) {
            // Estilos o páginas maestras dañados: se sigue con lo leído.
        }
        est.enMaestras = false;
        if (k < 0) fijarTamano(est.primeraMaestra != null ? est.maestras.get(est.primeraMaestra)[0] : null);

        XmlPullParser p;
        try {
            p = pq.parser("content.xml");
        } catch (XmlPullParserException e) {
            p = null;
        }
        if (p == null) throw new Fallo("No se puede leer la presentación. Puede que el archivo esté dañado.");
        int n = 0;
        boolean cuerpo = false;
        try {
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Odf.nombre(p)) {
                    case "office:automatic-styles": est.leerBloque(p, Odf.AUTO_CONTENIDO); break;
                    case "office:font-face-decls": case "office:scripts": Lectura.saltar(p); break;
                    case "office:presentation": cuerpo = true; break;
                    case "office:drawing": cuerpo = true; dibujo = true; break;
                    case "draw:page": {
                        if (!cuerpo) {
                            Lectura.saltar(p);
                            break;
                        }
                        n++;
                        int antes = sb.length();
                        try {
                            pagina(p, n, Math.max(n, total), sb);
                        } catch (RuntimeException | StackOverflowError e) {
                            sb.setLength(antes);
                            sb.append("<div class=num id=d").append(n).append('>').append(etiqueta()).append(' ')
                                    .append(n).append("</div><p class=nota>No se pudo mostrar esta página.</p>");
                        }
                        break;
                    }
                    case "presentation:settings": case "office:forms": case "presentation:header-decl":
                    case "presentation:footer-decl": case "presentation:date-time-decl":
                        Lectura.saltar(p);
                        break;
                    default: break;
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException e) {
            if (n == 0) throw new Fallo("La presentación está dañada y no se puede leer.");
            sb.append(Html.NOTA_DANADA);
        }
        if (n == 0) sb.append(dibujo ? "<p class=nota>El dibujo no tiene páginas.</p>"
                : "<p class=nota>La presentación no tiene diapositivas.</p>");
    }

    private String etiqueta() { return dibujo ? "Página" : "Diapositiva"; }

    /** Primera pasada: nombres de las páginas (para los enlaces entre diapositivas) y cuántas hay. */
    private void nombresDePaginas() {
        try {
            XmlPullParser p = pq.parser("content.xml");
            int t;
            while (p != null && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Odf.nombre(p)) {
                    case "office:automatic-styles": case "office:font-face-decls": case "office:scripts":
                        Lectura.saltar(p);
                        break;
                    case "draw:page": {
                        total++;
                        String n = Odf.atr(p, "draw:name");
                        if (n != null && !destinos.containsKey(n)) destinos.put(n, "#d" + total);
                        destinos.put("Slide " + total, "#d" + total);
                        destinos.put("page" + total, "#d" + total);
                        Lectura.saltar(p);
                        break;
                    }
                    default: break;
                }
            }
        } catch (Exception ignorado) {
            // Si falla, los enlaces internos se quedan sin resolver y la cuenta se corrige al pintar.
        }
    }

    private void fijarTamano(String disposicion) {
        Odf.Estilo pl = est.estilo("page-layout", disposicion);
        double w = Double.NaN, h = Double.NaN;
        if (pl != null) {
            w = Odf.px(pl.otras.get("fo:page-width"));
            h = Odf.px(pl.otras.get("fo:page-height"));
        }
        if (Double.isNaN(w) || Double.isNaN(h) || w < 10 || h < 10 || h / w > 20 || w / h > 20) {
            w = Odf.px("28cm");
            h = Odf.px("15.75cm");
        }
        k = ANCHO / w;
        alto = h * k;
        texto.escala = k;
    }

    /** Página maestra de styles.xml: se pintan su fondo y las formas que no son marcadores. */
    private void maestra(XmlPullParser p, String nombre, String disposicion, String estiloPagina)
            throws XmlPullParserException, IOException {
        if (k < 0) fijarTamano(disposicion);
        est.enMaestras = true;
        try {
            Fondo f = relleno(est.cadena(est.estilo("drawing-page", estiloPagina), "drawing-page", false), true);
            if (f != null) fondos.put(nombre, f);
            StringBuilder deco = new StringBuilder();
            arbol(p, deco, true);
            decoraciones.put(nombre, deco.toString());
        } finally {
            est.enMaestras = false;
        }
    }

    private void pagina(XmlPullParser p, int n, int total, StringBuilder sb) throws XmlPullParserException, IOException {
        String maestra = Odf.atr(p, "draw:master-page-name");
        List<Odf.Estilo> cad = est.cadena(est.estilo("drawing-page", Odf.atr(p, "draw:style-name")), "drawing-page", false);
        boolean oculta = "hidden".equals(Odf.prop(cad, Odf.OTRAS, "presentation:visibility"));
        boolean conFondo = !"false".equals(Odf.prop(cad, Odf.OTRAS, "presentation:background-visible"));
        boolean conObjetos = !"false".equals(Odf.prop(cad, Odf.OTRAS, "presentation:background-objects-visible"));
        Fondo f = relleno(cad, true);
        if (f == null && conFondo && maestra != null) f = fondos.get(maestra);

        StringBuilder formas = new StringBuilder();
        arbol(p, formas, false);

        sb.append("<div class=num id=d").append(n).append('>').append(etiqueta()).append(' ').append(n)
                .append(" de ").append(total);
        if (oculta) sb.append(" · oculta");
        sb.append("</div><div class=diapo style=\"height:").append(Html.px(alto)).append("px;");
        if (f != null && f.css != null) sb.append(f.css);
        sb.append("\">");
        if (f != null && f.imagen != null) {
            sb.append("<div class=\"im ").append(f.imagen).append(f.mosaico ? " mosaico" : "").append("\"></div>");
        }
        if (conObjetos && maestra != null) {
            String deco = decoraciones.get(maestra);
            if (deco != null) sb.append(deco);
        }
        sb.append(formas).append("</div>");
    }

    // --- Formas ---

    private void arbol(XmlPullParser p, StringBuilder out, boolean maestra) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            try {
                forma(p, out, maestra);
            } catch (RuntimeException e) {
                // Se ignora la forma que no se ha podido interpretar.
            }
        }
    }

    private void forma(XmlPullParser p, StringBuilder out, boolean maestra) throws XmlPullParserException, IOException {
        String n = Odf.nombre(p);
        switch (n) {
            case "draw:frame": marco(p, out, maestra); break;
            case "draw:rect": case "draw:ellipse": case "draw:circle": case "draw:custom-shape":
            case "draw:polygon": case "draw:polyline": case "draw:path": case "draw:regular-polygon":
            case "draw:caption":
                figura(p, out, n);
                break;
            case "draw:line": case "draw:connector": case "draw:measure": linea(p, out); break;
            case "presentation:notes": case "office:forms": case "draw:control": case "office:event-listeners":
            case "svg:title": case "svg:desc": case "draw:layer-set": case "dr3d:scene":
                Lectura.saltar(p);
                break;
            default:
                if (n.startsWith("anim:") || n.startsWith("smil:")) Lectura.saltar(p);
                break; // draw:g, draw:a: se recorren sus hijos
        }
    }

    private Geo geometria(XmlPullParser p) {
        Geo g = new Geo();
        g.x = Odf.px(Odf.atr(p, "svg:x"));
        g.y = Odf.px(Odf.atr(p, "svg:y"));
        g.w = Odf.px(Odf.atr(p, "svg:width"));
        g.h = Odf.px(Odf.atr(p, "svg:height"));
        String tr = Odf.atr(p, "draw:transform");
        if (tr != null) {
            Matcher m = TRANSFORMACION.matcher(tr);
            while (m.find()) {
                String[] a = m.group(2).trim().split("[\\s,]+");
                switch (m.group(1).toLowerCase(Locale.ROOT)) {
                    case "rotate": g.giro = anguloRad(a[0]); break;
                    case "translate":
                        if (Double.isNaN(g.x)) g.x = Odf.px(a[0]);
                        if (a.length > 1 && Double.isNaN(g.y)) g.y = Odf.px(a[1]);
                        break;
                    default: break;
                }
            }
        }
        if (Double.isNaN(g.x)) g.x = 0;
        if (Double.isNaN(g.y)) g.y = 0;
        if (Double.isNaN(g.w) || g.w < 0) g.w = 0;
        if (Double.isNaN(g.h) || g.h < 0) g.h = 0;
        g.x = Math.max(-1e5, Math.min(1e5, g.x));
        g.y = Math.max(-1e5, Math.min(1e5, g.y));
        g.w = Math.min(1e5, g.w);
        g.h = Math.min(1e5, g.h);
        return g;
    }

    private static double anguloRad(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("deg")) return Math.toRadians(Lectura.real(s.substring(0, s.length() - 3), 0));
        if (s.endsWith("rad")) return Lectura.real(s.substring(0, s.length() - 3), 0);
        return Lectura.real(s, 0);
    }

    /** Abre el div de una forma con su posición, tamaño y giro. */
    private void abrir(StringBuilder out, Geo g, String extra) {
        out.append("<div class=f style=\"left:").append(Html.px(g.x * k)).append("px;top:").append(Html.px(g.y * k))
                .append("px;width:").append(Html.px(g.w * k)).append("px;height:").append(Html.px(g.h * k)).append("px;");
        if (g.giro != 0) out.append("transform-origin:0 0;transform:rotate(").append(Html.px(Math.toDegrees(-g.giro)))
                .append("deg);");
        out.append(extra).append("\">");
    }

    private void marco(XmlPullParser p, StringBuilder out, boolean maestra) throws XmlPullParserException, IOException {
        String clase = Odf.atr(p, "presentation:class");
        if ((maestra && clase != null) || "true".equals(Odf.atr(p, "presentation:placeholder"))) {
            Lectura.saltar(p);
            return;
        }
        Geo g = geometria(p);
        Odf.Estilo ps = est.estilo("presentation", Odf.atr(p, "presentation:style-name"));
        Odf.Estilo gs = est.estilo("graphic", Odf.atr(p, "draw:style-name"));
        Odf.Estilo ts = est.estilo("paragraph", Odf.atr(p, "draw:text-style-name"));
        Odf.Estilo base = ps != null ? ps : gs;
        String familia = ps != null ? "presentation" : "graphic";
        List<Odf.Estilo> propia = est.cadena(base, familia, false);
        List<Odf.Estilo> conDefecto = est.cadena(base, familia, true);

        String imagen = null, alt = null, tx = null;
        StringBuilder tabla = null;
        boolean objeto = false, grafico = false;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Odf.nombre(p)) {
                case "draw:text-box":
                    if (tx == null) tx = textoCaja(p, conDefecto, false);
                    else Lectura.saltar(p);
                    break;
                case "draw:image":
                    if (imagen == null) imagen = imagen(p);
                    else Lectura.saltar(p);
                    break;
                case "table:table":
                    if (tabla == null) {
                        tabla = new StringBuilder();
                        texto.tabla(p, tabla);
                    } else {
                        Lectura.saltar(p);
                    }
                    break;
                case "draw:object": case "draw:object-ole": {
                    objeto = true;
                    if ("chart".equals(clase)) grafico = true;
                    Lectura.saltar(p);
                    break;
                }
                case "draw:plugin": case "draw:floating-frame": case "draw:applet":
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

        StringBuilder st = new StringBuilder();
        Fondo f = relleno(propia, false);
        if (f != null && f.css != null) st.append(f.css);
        String borde = trazoCss(propia, false);
        if (borde != null && tabla == null) st.append("border:").append(borde).append(';');
        cssContenedor(st, conDefecto, ts);
        abrir(out, g, st.toString());
        if (f != null && f.imagen != null) out.append("<div class=\"im ").append(f.imagen).append(f.mosaico ? " mosaico" : "").append("\"></div>");
        if (imagen != null) {
            out.append("<div class=\"im ").append(imagen).append('"');
            if (alt != null) out.append(" title=\"").append(Html.esc(alt)).append('"');
            out.append("></div>");
        }
        if (tabla != null) out.append(tabla);
        if (objeto && imagen == null) {
            out.append("<div class=fijo>[").append(grafico ? "Gráfico" : "Objeto incrustado").append("]</div>");
        }
        if (tx != null) out.append(tx);
        out.append("</div>");
    }

    /** CSS del texto heredado por todo el contenido de la forma (tamaño, color…). */
    private void cssContenedor(StringBuilder st, List<Odf.Estilo> conDefecto, Odf.Estilo ts) {
        StringBuilder c = new StringBuilder();
        texto.cssTexto(c, conDefecto, true);
        if (ts != null) {
            List<Odf.Estilo> ct = est.cadena(ts, "paragraph", false);
            texto.cssTexto(c, ct, true);
            texto.cssParrafo(c, ct, false);
        }
        if (c.indexOf("font-size:") < 0) c.append("font-size:").append(Html.px(18 / 0.75 * k)).append("px;");
        st.append(c);
    }

    /** Texto de un draw:text-box (o de una forma) con su relleno interior y anclaje vertical. */
    private String textoCaja(XmlPullParser p, List<Odf.Estilo> cad, boolean figura)
            throws XmlPullParserException, IOException {
        prepararListas(cad);
        StringBuilder b = new StringBuilder();
        int antes = texto.caracteres;
        if (figura) textoFigura(p, b, null);
        else texto.bloques(p, b, 0, null);
        boolean imagenes = b.indexOf("<img") >= 0;
        if (texto.caracteres == antes && !imagenes) return null;
        return envolverTexto(b, cad, figura);
    }

    private String envolverTexto(StringBuilder b, List<Odf.Estilo> cad, boolean figura) {
        StringBuilder r = new StringBuilder("<div class=tx style=\"padding:");
        String[] lados = {"top", "right", "bottom", "left"};
        double[] defecto = {0.125, 0.25, 0.125, 0.25};
        for (int i = 0; i < 4; i++) {
            double v = Odf.px(Odf.prop(cad, Odf.OTRAS, "fo:padding-" + lados[i]));
            if (Double.isNaN(v)) v = Odf.px(Odf.prop(cad, Odf.OTRAS, "fo:padding"));
            if (Double.isNaN(v) || v < 0) v = Odf.px(defecto[i] + "cm");
            r.append(Html.px(Math.min(v, 500) * k)).append(i < 3 ? "px " : "px;");
        }
        String va = Odf.prop(cad, Odf.OTRAS, "draw:textarea-vertical-align");
        if (va == null && figura) va = "middle";
        if ("middle".equals(va)) r.append("justify-content:center;");
        else if ("bottom".equals(va)) r.append("justify-content:flex-end;");
        if (figura) r.append("text-align:center;");
        if ("no-wrap".equals(Odf.prop(cad, Odf.OTRAS, "fo:wrap-option"))) r.append("white-space:nowrap;");
        r.append("\">").append(b).append("</div>");
        return r.toString();
    }

    /** Lista por defecto y estilos de esquema del marco, para las listas sin estilo propio. */
    private void prepararListas(List<Odf.Estilo> cad) {
        texto.listaDefecto = null;
        texto.baseEsquema = null;
        for (Odf.Estilo e : cad) {
            if (texto.listaDefecto == null && e.listaPropia != null) texto.listaDefecto = e.listaPropia;
            if (texto.baseEsquema == null && e.nombre != null && e.nombre.endsWith("outline1")) {
                texto.baseEsquema = e.nombre.substring(0, e.nombre.length() - 1);
            }
        }
    }

    /** Párrafos y listas que cuelgan directamente de una forma; anota el tipo de geometría. */
    private void textoFigura(XmlPullParser p, StringBuilder b, String[] tipo) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            try {
                switch (Odf.nombre(p)) {
                    case "text:p": texto.parrafo(p, b, false, false); break;
                    case "text:h": texto.parrafo(p, b, true, false); break;
                    case "text:list": texto.lista(p, b, 0, null); break;
                    case "draw:enhanced-geometry":
                        if (tipo != null) tipo[0] = Odf.atr(p, "draw:type");
                        Lectura.saltar(p);
                        break;
                    default: Lectura.saltar(p); break;
                }
            } catch (RuntimeException e) {
                // Párrafo raro: se ignora.
            }
        }
    }

    private void figura(XmlPullParser p, StringBuilder out, String n) throws XmlPullParserException, IOException {
        Geo g = geometria(p);
        if (n.equals("draw:circle") || (n.equals("draw:ellipse") && g.w == 0)) {
            double r = Odf.px(Odf.atr(p, "svg:r"));
            if (!Double.isNaN(r) && g.w == 0) {
                g.x = Odf.px(Odf.atr(p, "svg:cx")) - r;
                g.y = Odf.px(Odf.atr(p, "svg:cy")) - r;
                if (Double.isNaN(g.x)) g.x = 0;
                if (Double.isNaN(g.y)) g.y = 0;
                g.w = g.h = Math.min(2 * r, 1e5);
            }
        }
        String caja = Odf.atr(p, "svg:viewBox");
        String puntos = Odf.atr(p, "draw:points"), camino = Odf.atr(p, "svg:d");
        double radio = Odf.px(Odf.atr(p, "draw:corner-radius"));
        Odf.Estilo ps = est.estilo("presentation", Odf.atr(p, "presentation:style-name"));
        Odf.Estilo gs = est.estilo("graphic", Odf.atr(p, "draw:style-name"));
        Odf.Estilo ts = est.estilo("paragraph", Odf.atr(p, "draw:text-style-name"));
        Odf.Estilo base = ps != null ? ps : gs;
        List<Odf.Estilo> cad = est.cadena(base, ps != null ? "presentation" : "graphic", true);

        prepararListas(cad);
        String[] tipo = {null};
        StringBuilder b = new StringBuilder();
        int antes = texto.caracteres;
        textoFigura(p, b, tipo);
        String tx = texto.caracteres > antes ? envolverTexto(b, cad, true) : null;

        Fondo f = relleno(cad, true);
        String[] trazo = trazo(cad, true);
        String forma = "rect";
        String poligono = null;
        switch (n) {
            case "draw:ellipse": case "draw:circle": forma = "elipse"; break;
            case "draw:polygon": forma = "poligono"; break;
            case "draw:polyline": forma = "polilinea"; break;
            case "draw:path": forma = "camino"; break;
            case "draw:custom-shape": {
                String t = tipo[0] == null ? "" : tipo[0];
                if (t.equals("ellipse") || t.equals("circle") || t.equals("ring") || t.equals("flowchart-connector")) {
                    forma = "elipse";
                } else if (t.equals("round-rectangle") || t.equals("round-square")
                        || t.equals("flowchart-alternate-process")) {
                    forma = "redondeado";
                } else if (POLIGONOS.containsKey(t)) {
                    forma = "predefinida";
                    poligono = POLIGONOS.get(t);
                }
                break;
            }
            default: break;
        }

        StringBuilder st = new StringBuilder();
        boolean svg = !forma.equals("rect") && !forma.equals("elipse") && !forma.equals("redondeado");
        if (!svg) {
            if (f != null && f.css != null) st.append(f.css);
            if (trazo != null) {
                st.append("border:").append(trazo[1]).append("px ").append(trazo[2] != null ? "dashed" : "solid")
                        .append(" #").append(trazo[0]).append(';');
            }
            if (forma.equals("elipse")) st.append("border-radius:50%;");
            else if (forma.equals("redondeado")) {
                st.append("border-radius:").append(Html.px(Math.min(g.w, g.h) * k * 0.16)).append("px;");
            } else if (!Double.isNaN(radio) && radio > 0) {
                st.append("border-radius:").append(Html.px(Math.min(radio, Math.min(g.w, g.h) / 2) * k)).append("px;");
            }
        }
        cssContenedor(st, cad, ts);
        abrir(out, g, st.toString());
        if (f != null && f.imagen != null && !svg) {
            out.append("<div class=\"im ").append(f.imagen).append(f.mosaico ? " mosaico" : "").append('"')
                    .append(forma.equals("elipse") ? " style=\"border-radius:50%\"" : "").append("></div>");
        }
        if (svg) dibujoSvg(out, forma, poligono, caja, puntos, camino, f, trazo);
        if (tx != null) out.append(tx);
        out.append("</div>");
    }

    /** Polígonos, caminos y formas personalizadas como SVG que ocupa la caja de la forma. */
    private void dibujoSvg(StringBuilder out, String forma, String poligono, String caja, String puntos,
                           String camino, Fondo f, String[] trazo) {
        String vb = "0 0 100 100";
        String figura;
        if (poligono != null) {
            figura = "<polygon points=\"" + poligono + "\"";
        } else {
            if (caja == null || !NUMEROS.matcher(caja).matches() || caja.trim().split("[\\s,]+").length != 4) return;
            vb = caja.trim();
            if (forma.equals("camino")) {
                if (camino == null || camino.length() > 200_000 || !CAMINO.matcher(camino).matches()) return;
                figura = "<path d=\"" + camino.trim() + "\"";
            } else {
                if (puntos == null || puntos.length() > 200_000 || !NUMEROS.matcher(puntos).matches()) return;
                figura = (forma.equals("polilinea") ? "<polyline points=\"" : "<polygon points=\"") + puntos.trim() + "\"";
            }
        }
        String relleno = forma.equals("polilinea") || f == null ? null : f.color;
        out.append("<svg class=fg viewBox=\"").append(vb).append("\" preserveAspectRatio=\"none\">").append(figura)
                .append(" fill=\"").append(relleno != null ? "#" + relleno : "none").append('"');
        if (trazo != null) {
            out.append(" stroke=\"#").append(trazo[0]).append("\" stroke-width=\"").append(trazo[1])
                    .append("\" vector-effect=\"non-scaling-stroke\"");
            if (trazo[2] != null) out.append(" stroke-dasharray=\"6 4\"");
        }
        out.append("/></svg>");
    }

    private void linea(XmlPullParser p, StringBuilder out) throws XmlPullParserException, IOException {
        double x1 = num(Odf.atr(p, "svg:x1")) * k, y1 = num(Odf.atr(p, "svg:y1")) * k;
        double x2 = num(Odf.atr(p, "svg:x2")) * k, y2 = num(Odf.atr(p, "svg:y2")) * k;
        Odf.Estilo gs = est.estilo("graphic", Odf.atr(p, "draw:style-name"));
        List<Odf.Estilo> cad = est.cadena(gs, "graphic", true);
        Lectura.saltar(p);
        String[] trazo = trazo(cad, true);
        if (trazo == null) {
            if ("none".equals(Odf.prop(cad, Odf.OTRAS, "draw:stroke"))) return;
            trazo = new String[] {"000000", "1", null};
        }
        double x = Math.min(x1, x2), y = Math.min(y1, y2);
        out.append("<svg class=f style=\"left:").append(Html.px(x)).append("px;top:").append(Html.px(y))
                .append("px\" width=\"").append(Html.px(Math.max(Math.abs(x2 - x1), 1))).append("\" height=\"")
                .append(Html.px(Math.max(Math.abs(y2 - y1), 1))).append("\"><line x1=\"").append(Html.px(x1 - x))
                .append("\" y1=\"").append(Html.px(y1 - y)).append("\" x2=\"").append(Html.px(x2 - x))
                .append("\" y2=\"").append(Html.px(y2 - y)).append("\" stroke=\"#").append(trazo[0])
                .append("\" stroke-width=\"").append(trazo[1]).append('"');
        if (trazo[2] != null) out.append(" stroke-dasharray=\"6 4\"");
        out.append("/></svg>");
    }

    private static double num(String v) {
        double d = Odf.px(v);
        return Double.isNaN(d) ? 0 : Math.max(-1e5, Math.min(1e5, d));
    }

    // --- Rellenos, líneas e imágenes ---

    /** Relleno de una forma o página; null si no tiene. "figura": el color sin draw:fill cuenta como sólido. */
    private Fondo relleno(List<Odf.Estilo> cad, boolean figura) {
        if (cad.isEmpty()) return null;
        String fill = Odf.prop(cad, Odf.OTRAS, "draw:fill");
        String color = Odf.color(Odf.prop(cad, Odf.OTRAS, "draw:fill-color"));
        if (fill == null) fill = figura && color != null ? "solid" : "none";
        Fondo f = new Fondo();
        switch (fill) {
            case "solid":
                if (color == null) color = "729FCF";
                f.color = color;
                f.css = "background:" + conOpacidad(color, cad) + ";";
                break;
            case "gradient": {
                Odf.Degradado g = est.degradados.get(Odf.prop(cad, Odf.OTRAS, "draw:fill-gradient-name"));
                if (g != null) {
                    f.css = "background:" + degradado(g) + ";";
                    f.color = g.inicio != null ? g.inicio : color;
                } else if (color != null) {
                    f.css = "background:#" + color + ";";
                    f.color = color;
                }
                break;
            }
            case "bitmap": {
                String href = est.imagenesRelleno.get(Odf.prop(cad, Odf.OTRAS, "draw:fill-image-name"));
                f.imagen = href != null ? claseImagen(href, null) : null;
                f.mosaico = "repeat".equals(Odf.prop(cad, Odf.OTRAS, "style:repeat"));
                if (f.imagen == null && color != null) {
                    f.css = "background:#" + color + ";";
                    f.color = color;
                }
                break;
            }
            case "hatch": {
                String c = "true".equals(Odf.prop(cad, Odf.OTRAS, "draw:fill-hatch-solid")) ? color
                        : est.tramas.get(Odf.prop(cad, Odf.OTRAS, "draw:fill-hatch-name"));
                if (c != null) {
                    f.css = "background:#" + c + ";";
                    f.color = c;
                }
                break;
            }
            default: return null;
        }
        return f.css == null && f.imagen == null ? null : f;
    }

    private static String conOpacidad(String color, List<Odf.Estilo> cad) {
        double op = Odf.porcentaje(Odf.prop(cad, Odf.OTRAS, "draw:opacity"));
        if (Double.isNaN(op)) {
            double tr = Odf.porcentaje(Odf.prop(cad, Odf.OTRAS, "draw:transparency"));
            if (!Double.isNaN(tr)) op = 1 - tr;
        }
        if (Double.isNaN(op) || op >= 1) return "#" + color;
        int c = Integer.parseInt(color, 16);
        return "rgba(" + ((c >> 16) & 255) + "," + ((c >> 8) & 255) + "," + (c & 255) + ","
                + Html.px(Math.max(0, op)) + ")";
    }

    private static String degradado(Odf.Degradado g) {
        String a = "#" + (g.inicio != null ? g.inicio : "000000"), b = "#" + (g.fin != null ? g.fin : "FFFFFF");
        String estilo = g.estilo == null ? "linear" : g.estilo;
        double ang = ((180 - g.angulo) % 360 + 360) % 360;
        switch (estilo) {
            case "axial": return "linear-gradient(" + Html.px(ang) + "deg," + a + "," + b + "," + a + ")";
            case "radial": case "ellipsoid": case "square": case "rectangular":
                return "radial-gradient(" + b + "," + a + ")";
            default: return "linear-gradient(" + Html.px(ang) + "deg," + a + "," + b + ")";
        }
    }

    /** Línea de una forma: {color, grosor en px, "dash" o null}; null si no tiene. */
    private String[] trazo(List<Odf.Estilo> cad, boolean figura) {
        if (cad.isEmpty()) return null;
        String s = Odf.prop(cad, Odf.OTRAS, "draw:stroke");
        String color = Odf.color(Odf.prop(cad, Odf.OTRAS, "svg:stroke-color"));
        if (s == null) s = figura && color != null ? "solid" : "none";
        if (s.equals("none")) return null;
        double w = Odf.px(Odf.prop(cad, Odf.OTRAS, "svg:stroke-width"));
        w = Double.isNaN(w) || w <= 0 ? 1 : Math.max(1, Math.min(50, w * k));
        return new String[] {color != null ? color : "000000", Html.px(w), s.equals("dash") ? "dash" : null};
    }

    private String trazoCss(List<Odf.Estilo> cad, boolean figura) {
        String[] t = trazo(cad, figura);
        if (t == null) return null;
        return t[1] + "px " + (t[2] != null ? "dashed" : "solid") + " #" + t[0];
    }

    /** Clase CSS para la imagen de un draw:image (cada imagen se incrusta una sola vez). */
    private String imagen(XmlPullParser p) throws XmlPullParserException, IOException {
        String href = Odf.atr(p, "xlink:href");
        if (href != null && !href.trim().isEmpty()) {
            Lectura.saltar(p);
            return claseImagen(href.trim(), null);
        }
        String uri = texto.uriImagen(p);
        return uri != null ? claseImagen("\u0000" + uri.hashCode() + ":" + uri.length(), uri) : null;
    }

    private String claseImagen(String clave, String uri) {
        String clase = clasesImagen.get(clave);
        if (clase != null) return clase.isEmpty() ? null : clase;
        if (uri == null) uri = texto.imagenPaquete(clave);
        if (uri == null) {
            clasesImagen.put(clave, "");
            return null;
        }
        clase = "i" + clasesImagen.size();
        cssImagenes.append('.').append(clase).append("{background-image:url(\"").append(uri).append("\")}");
        clasesImagen.put(clave, clase);
        return clase;
    }
}
