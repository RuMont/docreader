package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte una presentación de PowerPoint (.pptx) en HTML: cada diapositiva es una caja de
 * 960 px de ancho con sus formas en posición absoluta (en px CSS, escalando desde EMU).
 */
final class Pptx {
    private static final double ANCHO = 960;
    private static final String VIEWPORT = "width=1000";

    private static final String CSS =
            "body.pptx{padding:6px 20px 30px;background:#e4e4e4}"
            + ".num{font-size:15px;color:#555;margin:16px 0 6px}"
            + ".diapo{position:relative;width:960px;overflow:hidden;background:#fff;"
            + "border:1px solid #c4c4c4;line-height:1.2}"
            + ".f{position:absolute;box-sizing:border-box}"
            + ".tx{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;"
            + "box-sizing:border-box;overflow-wrap:break-word;color:#000}"
            + ".pa{white-space:pre-wrap;margin:0}"
            + ".im{position:absolute;left:0;top:0;right:0;bottom:0;background-size:100% 100%;"
            + "background-repeat:no-repeat;background-position:center}"
            + ".fijo{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;"
            + "justify-content:center;border:1px dashed #999;color:#666;font-size:16px;"
            + "background:rgba(240,240,240,.6)}"
            + ".pt{width:100%;height:100%;border-collapse:collapse;table-layout:fixed}"
            + ".pt td{border:1px solid #8c8c8c;vertical-align:top;overflow:hidden}"
            + "svg.f{overflow:visible}";

    private static final Map<String, String> MAPA_DEFECTO = new HashMap<>();
    private static final Map<String, String> PREDEFINIDOS = new HashMap<>();

    static {
        String[] m = {"bg1", "lt1", "tx1", "dk1", "bg2", "lt2", "tx2", "dk2"};
        for (int i = 0; i < m.length; i += 2) MAPA_DEFECTO.put(m[i], m[i + 1]);
        String[] c = {"black", "000000", "white", "FFFFFF", "red", "FF0000", "green", "008000",
                "blue", "0000FF", "yellow", "FFFF00", "gray", "808080", "grey", "808080",
                "darkGray", "A9A9A9", "lightGray", "D3D3D3", "orange", "FFA500", "purple", "800080",
                "navy", "000080", "cyan", "00FFFF", "magenta", "FF00FF", "silver", "C0C0C0"};
        for (int i = 0; i < c.length; i += 2) PREDEFINIDOS.put(c[i], c[i + 1]);
    }

    /** Propiedades de párrafo y de texto de un nivel o de una corrida. Null = heredar. */
    private static final class Nivel {
        Integer sz, base, spc;
        Boolean b, i, u, s, mayus;
        String color, algn, vineta, enlace, fuente;
        Long marL, indent, lnPts, antes, despues;
        Double lnPct, antesPct;
    }

    private static final class Corrida {
        String texto;
        boolean salto;
        Nivel rpr;
    }

    private static final class Parrafo {
        int nivel;
        Nivel ppr = new Nivel();
        Nivel fin;
        final List<Corrida> corridas = new ArrayList<>();
    }

    private static final class Cuerpo {
        String anclaje;
        Long l, t, r, b;
        boolean sinAjuste;
        double escala = 1, reduccion;
        Nivel[] lista = new Nivel[9];
        final List<Parrafo> parrafos = new ArrayList<>();
    }

    private static final class Forma {
        String phTipo, phIdx, alt;
        boolean marcador, geometria, volteoH, volteoV, conector, sinRelleno, sinLinea;
        double x, y, w, h, rot;
        String relleno, imagenRelleno, linea, prst;
        double grosor = -1;
        String rellenoEstilo, lineaEstilo, textoEstilo;
        Cuerpo cuerpo;
        String imagen, fijo, tabla;
    }

    /** Diseño (slideLayout) o patrón (slideMaster) ya analizado. */
    private static final class Plantilla {
        Map<String, Paquete.Relacion> rels = Collections.emptyMap();
        Plantilla padre;
        final List<Forma> marcadores = new ArrayList<>();
        String fondo, fondoImagen;
        boolean mostrarPadre = true;
        Nivel[] titulo = new Nivel[9], cuerpo = new Nivel[9], otro = new Nivel[9];
        Map<String, String> tema = new HashMap<>();
        Map<String, String> mapa = new HashMap<>(MAPA_DEFECTO);
        String decoracion = "";
    }

    /** Contexto de la parte que se está leyendo. */
    private static final class Ctx {
        Map<String, Paquete.Relacion> rels = Collections.emptyMap();
        Plantilla diseno, patron;
        Map<String, String> tema = Collections.emptyMap();
        Map<String, String> mapa = MAPA_DEFECTO;
    }

    /** Transformación de coordenadas de un grupo a la diapositiva: X = tx + sx·x. */
    private static final class Transf {
        static final Transf IDENTIDAD = new Transf(1, 1, 0, 0);
        final double sx, sy, tx, ty;

        Transf(double sx, double sy, double tx, double ty) {
            this.sx = sx;
            this.sy = sy;
            this.tx = tx;
            this.ty = ty;
        }

        Transf componer(double[] g) {
            // g = {offX, offY, extX, extY, chOffX, chOffY, chExtX, chExtY}
            double fx = g[6] > 0 ? g[2] / g[6] : 1, fy = g[7] > 0 ? g[3] / g[7] : 1;
            return new Transf(sx * fx, sy * fy, tx + sx * (g[0] - g[4] * fx), ty + sy * (g[1] - g[5] * fy));
        }
    }

    private final Paquete pq;
    private long cx = 12192000, cy = 6858000;
    private double k = ANCHO / 12192000.0;
    private Nivel[] porDefecto = new Nivel[9];
    private final Map<String, Plantilla> plantillas = new HashMap<>();
    private final Map<String, String> clasesImagen = new HashMap<>();
    private final StringBuilder cssImagenes = new StringBuilder();
    /** @font-face de las fuentes incrustadas en la presentación (embeddedFontLst). */
    private final StringBuilder cssFuentes = new StringBuilder();
    private final Map<String, Integer> indiceDiapositiva = new HashMap<>();

    private Pptx(Paquete pq) { this.pq = pq; }

    static Html.Documento convertir(Paquete pq) throws Fallo {
        Pptx x = new Pptx(pq);
        StringBuilder sb = new StringBuilder();
        x.procesar(sb);
        return new Html.Documento(sb.toString(), CSS + x.cssFuentes + x.cssImagenes, VIEWPORT, "pptx");
    }

    private void procesar(StringBuilder sb) throws Fallo {
        String pres = pq.principal("ppt/presentation.xml");
        if (!pq.existe(pres)) throw new Fallo("No se encontró la presentación. Puede que el archivo esté dañado.");
        Map<String, Paquete.Relacion> rels = pq.relaciones(pres);
        List<String> diapositivas = new ArrayList<>();
        try {
            XmlPullParser p = pq.parser(pres);
            if (p == null) throw new Fallo("No se puede leer la presentación.");
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Lectura.nombre(p)) {
                    case "sldSz": {
                        long a = Lectura.largo(Lectura.atr(p, "cx"), 0), b = Lectura.largo(Lectura.atr(p, "cy"), 0);
                        if (a > 0 && b > 0) {
                            cx = a;
                            cy = b;
                        }
                        break;
                    }
                    case "sldId": {
                        Paquete.Relacion r = rels.get(Lectura.rid(p, "id"));
                        if (r != null && !r.externa) diapositivas.add(r.destino);
                        break;
                    }
                    case "embeddedFont": fuenteIncrustada(p, rels); break;
                    case "notesSz": case "extLst": Lectura.saltar(p); break;
                    default: break;
                }
            }
        } catch (XmlPullParserException | IOException e) {
            if (diapositivas.isEmpty()) throw new Fallo("La presentación está dañada y no se puede leer.");
        }
        k = ANCHO / cx;
        for (int i = 0; i < diapositivas.size(); i++) indiceDiapositiva.put(diapositivas.get(i), i + 1);
        estiloPorDefecto(pres, rels);
        if (diapositivas.isEmpty()) {
            sb.append("<p class=nota>La presentación no tiene diapositivas.</p>");
            return;
        }
        for (int i = 0; i < diapositivas.size(); i++) {
            int antes = sb.length();
            try {
                diapositiva(diapositivas.get(i), i + 1, diapositivas.size(), sb);
            } catch (Exception e) {
                sb.setLength(antes);
                sb.append("<div class=num id=d").append(i + 1).append(">Diapositiva ").append(i + 1)
                        .append("</div><p class=nota>No se pudo mostrar esta diapositiva.</p>");
            }
        }
    }

    /** defaultTextStyle de presentation.xml, con los colores del tema del primer patrón. */
    private void estiloPorDefecto(String pres, Map<String, Paquete.Relacion> rels) {
        Ctx c = new Ctx();
        Paquete.Relacion rm = Paquete.relacion(rels, "/slideMaster");
        if (rm != null) {
            Plantilla patron = plantilla(rm.destino, true);
            c.tema = patron.tema;
            c.mapa = patron.mapa;
        }
        try {
            XmlPullParser p = pq.parser(pres);
            int t;
            while (p != null && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t == XmlPullParser.START_TAG && "defaultTextStyle".equals(Lectura.nombre(p))) {
                    porDefecto = leerLista(p, c);
                    return;
                }
            }
        } catch (Exception ignorado) { }
    }

    // --- Diapositivas y plantillas ---

    private void diapositiva(String ruta, int n, int total, StringBuilder sb)
            throws XmlPullParserException, IOException {
        Ctx c = new Ctx();
        c.rels = pq.relaciones(ruta);
        Paquete.Relacion rl = Paquete.relacion(c.rels, "/slideLayout");
        c.diseno = rl != null ? plantilla(rl.destino, false) : null;
        c.patron = c.diseno != null ? c.diseno.padre : null;
        if (c.patron != null) {
            c.tema = c.patron.tema;
            c.mapa = c.patron.mapa;
        }
        Plantilla propia = new Plantilla();
        boolean oculta = false;
        StringBuilder formas = new StringBuilder();
        XmlPullParser p = pq.parser(ruta);
        if (p == null) throw new IOException("falta la diapositiva");
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (t != XmlPullParser.START_TAG) continue;
            switch (Lectura.nombre(p)) {
                case "sld":
                    oculta = Boolean.FALSE.equals(Lectura.booleano(Lectura.atr(p, "show")));
                    propia.mostrarPadre = !Boolean.FALSE.equals(Lectura.booleano(Lectura.atr(p, "showMasterSp")));
                    break;
                case "bg": fondo(p, c, propia); break;
                case "spTree": arbol(p, c, Transf.IDENTIDAD, formas, null); break;
                case "clrMapOvr": case "transition": case "timing": case "extLst": Lectura.saltar(p); break;
                default: break;
            }
        }

        String fondo = null, fondoImagen = null;
        for (Plantilla pl : new Plantilla[] {propia, c.diseno, c.patron}) {
            if (pl != null && (pl.fondo != null || pl.fondoImagen != null)) {
                fondo = pl.fondo;
                fondoImagen = pl.fondoImagen;
                break;
            }
        }
        sb.append("<div class=num id=d").append(n).append(">Diapositiva ").append(n).append(" de ").append(total);
        if (oculta) sb.append(" · oculta");
        sb.append("</div><div class=diapo style=\"height:").append(Html.px(cy * k)).append("px;background:#")
                .append(fondo != null ? fondo : "FFFFFF").append("\">");
        if (fondoImagen != null) sb.append("<div class=\"im ").append(fondoImagen).append("\"></div>");
        if (propia.mostrarPadre && c.diseno != null) {
            if (c.diseno.mostrarPadre && c.patron != null) sb.append(c.patron.decoracion);
            sb.append(c.diseno.decoracion);
        }
        sb.append(formas).append("</div>");
    }

    // --- Fuentes ---

    /** <p:embeddedFont>: la fuente y sus variantes (regular, bold, italic, boldItalic). */
    private void fuenteIncrustada(XmlPullParser p, Map<String, Paquete.Relacion> rels)
            throws XmlPullParserException, IOException {
        String familia = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Lectura.nombre(p);
            if (n.equals("font")) {
                familia = Lectura.atr(p, "typeface");
                continue;
            }
            boolean negrita = n.equals("bold") || n.equals("boldItalic");
            boolean cursiva = n.equals("italic") || n.equals("boldItalic");
            if (!negrita && !cursiva && !n.equals("regular")) continue;
            Paquete.Relacion r = rels.get(Lectura.rid(p, "id"));
            if (familia == null || familia.isEmpty() || r == null || r.externa) continue;
            byte[] ttf = ttfDeEot(pq.bytes(r.destino));
            if (ttf == null) continue;
            cssFuentes.append("@font-face{font-family:\"").append(cssCadena(familia)).append("\";src:url(data:font/ttf;base64,")
                    .append(java.util.Base64.getEncoder().encodeToString(ttf)).append(");font-weight:")
                    .append(negrita ? 700 : 400).append(";font-style:").append(cursiva ? "italic" : "normal").append('}');
        }
    }

    /**
     * Las fuentes incrustadas de PowerPoint vienen en formato EOT. Si no están comprimidas (MTX)
     * se extrae el TrueType/OpenType tal cual (deshaciendo el XOR 0x50 si lo llevan).
     */
    static byte[] ttfDeEot(byte[] d) {
        if (d == null || d.length < 16) return null;
        if (esFuente(d, 0)) return d; // ya es un TTF/OTF
        int tamEot = Cfb.i32(d, 0), tamDatos = Cfb.i32(d, 4), banderas = Cfb.i32(d, 12);
        if (tamEot != d.length || tamDatos <= 0 || tamDatos > tamEot - 16) return null;
        if ((banderas & 0x4) != 0) return null; // MicroType Express: no se puede descomprimir aquí
        byte[] f = java.util.Arrays.copyOfRange(d, tamEot - tamDatos, tamEot);
        if ((banderas & 0x10000000) != 0) for (int i = 0; i < f.length; i++) f[i] ^= 0x50;
        return esFuente(f, 0) ? f : null;
    }

    private static boolean esFuente(byte[] b, int o) {
        if (b.length < o + 4) return false;
        int v = Cfb.i32(b, o);
        return v == 0x00000100 || v == 0x4F54544F /* OTTO */ || v == 0x65757274 /* true */;
    }

    /** font-family CSS: la fuente pedida y, detrás, la genérica más parecida. */
    private static String familiaCss(String fuente) {
        String f = fuente.toLowerCase(java.util.Locale.ROOT);
        String generica = f.matches(".*(mono|courier|consolas|code|menlo|terminal|typewriter).*") ? "monospace"
                : f.matches(".*(times|georgia|garamond|cambria|baskerville|book antiqua|palatino|merriweather"
                        + "|playfair|lora|serif)(?!.*sans).*") && !f.contains("sans") ? "serif" : "sans-serif";
        return "\"" + cssCadena(fuente) + "\"," + generica;
    }

    private static String cssCadena(String s) {
        return s.replace("\\", "").replace("\"", "").replace("<", "").replace(">", "").replace(";", "");
    }

    private Plantilla plantilla(String ruta, boolean esPatron) {
        Plantilla pl = plantillas.get(ruta);
        if (pl != null) return pl;
        pl = new Plantilla();
        plantillas.put(ruta, pl);
        pl.rels = pq.relaciones(ruta);
        if (esPatron) {
            Paquete.Relacion r = Paquete.relacion(pl.rels, "/theme");
            if (r != null) {
                pl.tema = new HashMap<>(Colores.tema(pq, r.destino));
                fuentesTema(r.destino, pl.tema);
            }
            leerMapaColores(ruta, pl);
        } else {
            Paquete.Relacion r = Paquete.relacion(pl.rels, "/slideMaster");
            if (r != null) pl.padre = plantilla(r.destino, true);
            if (pl.padre != null) {
                pl.tema = pl.padre.tema;
                pl.mapa = pl.padre.mapa;
            }
        }
        Ctx c = new Ctx();
        c.rels = pl.rels;
        c.tema = pl.tema;
        c.mapa = pl.mapa;
        c.patron = esPatron ? null : pl.padre;
        StringBuilder deco = new StringBuilder();
        try {
            XmlPullParser p = pq.parser(ruta);
            int t;
            while (p != null && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Lectura.nombre(p)) {
                    case "sldLayout": case "sldMaster":
                        pl.mostrarPadre = !Boolean.FALSE.equals(Lectura.booleano(Lectura.atr(p, "showMasterSp")));
                        break;
                    case "bg": fondo(p, c, pl); break;
                    case "spTree": arbol(p, c, Transf.IDENTIDAD, deco, pl); break;
                    case "titleStyle": pl.titulo = leerLista(p, c); break;
                    case "bodyStyle": pl.cuerpo = leerLista(p, c); break;
                    case "otherStyle": pl.otro = leerLista(p, c); break;
                    case "clrMap": case "clrMapOvr": case "transition": case "timing": case "extLst":
                    case "sldLayoutIdLst": case "hf":
                        Lectura.saltar(p);
                        break;
                    default: break;
                }
            }
        } catch (Exception ignorado) {
            // Plantilla dañada: se usa lo que se haya leído.
        }
        pl.decoracion = deco.toString();
        return pl;
    }

    /** El clrMap del patrón va detrás de las formas, así que se lee antes en una pasada aparte. */
    private void leerMapaColores(String ruta, Plantilla pl) {
        try {
            XmlPullParser p = pq.parser(ruta);
            int t;
            while (p != null && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG || !"clrMap".equals(Lectura.nombre(p))) continue;
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    pl.mapa.put(p.getAttributeName(i), p.getAttributeValue(i));
                }
                return;
            }
        } catch (Exception ignorado) { }
    }

    private void fondo(XmlPullParser p, Ctx c, Plantilla pl) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "solidFill": case "gradFill": case "pattFill": pl.fondo = primerColor(p, c); break;
                case "blipFill": pl.fondoImagen = imagenDe(p, c); break;
                case "bgRef": pl.fondo = primerColor(p, c); break;
                case "noFill": pl.fondo = "FFFFFF"; break;
                default: break;
            }
        }
    }

    // --- Colores ---

    private static boolean esColor(String n) {
        switch (n) {
            case "srgbClr": case "schemeClr": case "sysClr": case "prstClr": case "scrgbClr": case "hslClr":
                return true;
            default:
                return false;
        }
    }

    /** Primer color que aparezca dentro del elemento actual (que queda consumido). */
    private String primerColor(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        String r = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (Lectura.inicio(p) && r == null && esColor(Lectura.nombre(p))) r = color(p, c);
        }
        return r;
    }

    private String color(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        String base = null;
        switch (Lectura.nombre(p)) {
            case "srgbClr": base = Colores.hex6(Lectura.atr(p, "val")); break;
            case "sysClr":
                base = Colores.hex6(Lectura.atr(p, "lastClr"));
                if (base == null) base = "windowText".equals(Lectura.atr(p, "val")) ? "000000" : "FFFFFF";
                break;
            case "schemeClr": base = esquema(Lectura.atr(p, "val"), c); break;
            case "prstClr": base = PREDEFINIDOS.get(Lectura.atr(p, "val")); break;
            case "scrgbClr":
                base = Colores.rgb(canalLineal(Lectura.atr(p, "r")), canalLineal(Lectura.atr(p, "g")),
                        canalLineal(Lectura.atr(p, "b")));
                break;
            default: break;
        }
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p) || base == null) continue;
            double v = Lectura.entero(Lectura.atr(p, "val"), 100000) / 100000.0;
            switch (Lectura.nombre(p)) {
                case "lumMod": base = Colores.luminancia(base, v, 0); break;
                case "lumOff": base = Colores.luminancia(base, 1, v); break;
                case "tint": base = Colores.tinte(base, v); break;
                case "shade": base = Colores.sombra(base, v); break;
                default: break;
            }
        }
        return base;
    }

    private static int canalLineal(String v) {
        double x = Math.max(0, Math.min(1, Lectura.entero(v, 0) / 100000.0));
        double s = x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    private static String esquema(String val, Ctx c) {
        if (val == null || val.equals("phClr")) return null;
        String m = c.mapa.get(val);
        if (m == null) m = val;
        String r = c.tema.get(m);
        if (r == null) {
            if (m.equals("dk1")) r = "000000";
            else if (m.equals("lt1")) r = "FFFFFF";
        }
        return r;
    }

    // --- Imágenes ---

    /** Clase CSS para la imagen de un blipFill (cada imagen se incrusta una sola vez). */
    private String imagenDe(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        String id = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (Lectura.inicio(p) && id == null && "blip".equals(Lectura.nombre(p))) id = Lectura.rid(p, "embed");
        }
        return claseImagen(c, id);
    }

    private String claseImagen(Ctx c, String id) {
        if (id == null) return null;
        Paquete.Relacion r = c.rels.get(id);
        if (r == null || r.externa) return null;
        String clase = clasesImagen.get(r.destino);
        if (clase != null) return clase.isEmpty() ? null : clase;
        String uri = pq.imagen(r.destino);
        if (uri == null) {
            clasesImagen.put(r.destino, "");
            return null;
        }
        clase = "i" + clasesImagen.size();
        cssImagenes.append('.').append(clase).append("{background-image:url(\"").append(uri).append("\")}");
        clasesImagen.put(r.destino, clase);
        return clase;
    }

    // --- Árbol de formas ---

    private void arbol(XmlPullParser p, Ctx c, Transf t, StringBuilder out, Plantilla destino)
            throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (Lectura.inicio(p)) despachar(p, c, t, out, destino);
        }
    }

    private void despachar(XmlPullParser p, Ctx c, Transf t, StringBuilder out, Plantilla destino)
            throws XmlPullParserException, IOException {
        int marca = out.length();
        try {
            switch (Lectura.nombre(p)) {
                case "sp": case "cxnSp": case "pic": forma(p, c, t, out, destino); break;
                case "graphicFrame": marco(p, c, t, out, destino); break;
                case "grpSp": grupo(p, c, t, out, destino); break;
                case "nvGrpSpPr": case "grpSpPr": case "Fallback": case "contentPart": case "extLst":
                    Lectura.saltar(p);
                    break;
                default: break; // AlternateContent, Choice…: se recorren sus hijos
            }
        } catch (RuntimeException e) {
            // Se ignora la forma que no se ha podido interpretar, sin dejar HTML a medias.
            out.setLength(marca);
        }
    }

    private void grupo(XmlPullParser p, Ctx c, Transf t, StringBuilder out, Plantilla destino)
            throws XmlPullParserException, IOException {
        Transf tg = t;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Lectura.nombre(p);
            if (n.equals("grpSpPr")) {
                double[] g = {0, 0, 0, 0, 0, 0, 0, 0};
                boolean hay = false;
                int dg = p.getDepth();
                while (Lectura.dentro(p, dg)) {
                    if (!Lectura.inicio(p)) continue;
                    switch (Lectura.nombre(p)) {
                        case "off": g[0] = largo(p, "x"); g[1] = largo(p, "y"); hay = true; break;
                        case "ext": g[2] = largo(p, "cx"); g[3] = largo(p, "cy"); break;
                        case "chOff": g[4] = largo(p, "x"); g[5] = largo(p, "y"); break;
                        case "chExt": g[6] = largo(p, "cx"); g[7] = largo(p, "cy"); break;
                        case "extLst": case "effectLst": case "scene3d": Lectura.saltar(p); break;
                        default: break;
                    }
                }
                if (hay) tg = t.componer(g);
            } else if (n.equals("nvGrpSpPr")) {
                Lectura.saltar(p);
            } else {
                despachar(p, c, tg, out, destino);
            }
        }
    }

    private static double largo(XmlPullParser p, String atributo) {
        return Lectura.largo(Lectura.atr(p, atributo), 0);
    }

    private void forma(XmlPullParser p, Ctx c, Transf t, StringBuilder out, Plantilla destino)
            throws XmlPullParserException, IOException {
        Forma f = new Forma();
        f.conector = "cxnSp".equals(Lectura.nombre(p));
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "ph": marcador(p, f); break;
                case "cNvPr": f.alt = Lectura.atr(p, "descr"); Lectura.saltar(p); break;
                case "spPr": propiedadesForma(p, c, f); break;
                case "style": estiloForma(p, c, f); break;
                case "txBody": f.cuerpo = leerCuerpo(p, c); break;
                case "blipFill": f.imagen = imagenDe(p, c); break;
                case "extLst": Lectura.saltar(p); break;
                default: break;
            }
        }
        terminar(f, c, t, out, destino);
    }

    private static void marcador(XmlPullParser p, Forma f) {
        f.marcador = true;
        f.phTipo = Lectura.atr(p, "type");
        f.phIdx = Lectura.atr(p, "idx");
    }

    /** graphicFrame: tablas; gráficos y SmartArt se indican con un recuadro. */
    private void marco(XmlPullParser p, Ctx c, Transf t, StringBuilder out, Plantilla destino)
            throws XmlPullParserException, IOException {
        Forma f = new Forma();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "ph": marcador(p, f); break;
                case "off": f.x = largo(p, "x"); f.y = largo(p, "y"); break;
                case "ext":
                    f.w = largo(p, "cx");
                    f.h = largo(p, "cy");
                    f.geometria = true;
                    break;
                case "tbl": f.tabla = tabla(p, c); break;
                case "chart": f.fijo = "Gráfico"; Lectura.saltar(p); break;
                case "relIds": f.fijo = "Diagrama SmartArt"; Lectura.saltar(p); break;
                case "blip":
                    if (f.imagen == null) f.imagen = claseImagen(c, Lectura.rid(p, "embed"));
                    break;
                case "extLst": Lectura.saltar(p); break;
                default: break;
            }
        }
        if (f.tabla == null && f.imagen == null && f.fijo == null) f.fijo = "Objeto";
        terminar(f, c, t, out, destino);
    }

    private void propiedadesForma(XmlPullParser p, Ctx c, Forma f) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "xfrm":
                    f.rot = Lectura.entero(Lectura.atr(p, "rot"), 0) / 60000.0;
                    f.volteoH = Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "flipH")));
                    f.volteoV = Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "flipV")));
                    break;
                case "off": f.x = largo(p, "x"); f.y = largo(p, "y"); break;
                case "ext":
                    f.w = largo(p, "cx");
                    f.h = largo(p, "cy");
                    f.geometria = true;
                    break;
                case "prstGeom": f.prst = Lectura.atr(p, "prst"); Lectura.saltar(p); break;
                case "custGeom": f.prst = "cust"; Lectura.saltar(p); break;
                case "noFill": f.sinRelleno = true; break;
                case "solidFill": case "gradFill": case "pattFill": f.relleno = primerColor(p, c); break;
                case "blipFill": f.imagenRelleno = imagenDe(p, c); break;
                case "ln": linea(p, c, f); break;
                case "effectLst": case "scene3d": case "sp3d": case "extLst": Lectura.saltar(p); break;
                default: break;
            }
        }
    }

    private void linea(XmlPullParser p, Ctx c, Forma f) throws XmlPullParserException, IOException {
        String w = Lectura.atr(p, "w");
        if (w != null) f.grosor = Lectura.largo(w, -1);
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "noFill": f.sinLinea = true; break;
                case "solidFill": case "gradFill": case "pattFill": f.linea = primerColor(p, c); break;
                default: Lectura.saltar(p); break;
            }
        }
    }

    private void estiloForma(XmlPullParser p, Ctx c, Forma f) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Lectura.nombre(p);
            int idx = Lectura.entero(Lectura.atr(p, "idx"), 0);
            switch (n) {
                case "lnRef": { String col = primerColor(p, c); if (idx > 0) f.lineaEstilo = col; break; }
                case "fillRef": { String col = primerColor(p, c); if (idx > 0) f.rellenoEstilo = col; break; }
                case "fontRef": f.textoEstilo = primerColor(p, c); break;
                default: Lectura.saltar(p); break;
            }
        }
    }

    // --- Texto ---

    private Nivel[] leerLista(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        Nivel[] r = new Nivel[9];
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Lectura.nombre(p);
            if (n.length() == 7 && n.startsWith("lvl") && n.endsWith("pPr")) {
                int i = n.charAt(3) - '1';
                if (i >= 0 && i < 9) r[i] = leerPPr(p, c);
                else Lectura.saltar(p);
            } else {
                Lectura.saltar(p);
            }
        }
        return r;
    }

    private Nivel leerPPr(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        Nivel nv = new Nivel();
        nv.algn = Lectura.atr(p, "algn");
        String v = Lectura.atr(p, "marL");
        if (v != null) nv.marL = Lectura.largo(v, 0);
        v = Lectura.atr(p, "indent");
        if (v != null) nv.indent = Lectura.largo(v, 0);
        String fuenteVineta = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "lnSpc": {
                    double[] e = espacio(p);
                    if (e[0] >= 0) nv.lnPct = e[0];
                    if (e[1] >= 0) nv.lnPts = (long) e[1];
                    break;
                }
                case "spcBef": {
                    double[] e = espacio(p);
                    if (e[0] >= 0) nv.antesPct = e[0];
                    if (e[1] >= 0) nv.antes = (long) e[1];
                    break;
                }
                case "spcAft": {
                    double[] e = espacio(p);
                    if (e[1] >= 0) nv.despues = (long) e[1];
                    break;
                }
                case "buNone": nv.vineta = "-"; break; // sin viñeta (código propio: no empieza por "c" ni "n")
                case "buChar": {
                    String ch = Lectura.atr(p, "char");
                    nv.vineta = "c" + (ch == null || ch.isEmpty() ? "•" : ch);
                    break;
                }
                case "buAutoNum":
                    nv.vineta = "n" + Lectura.atr(p, "type") + ":" + Lectura.entero(Lectura.atr(p, "startAt"), 1);
                    break;
                case "buBlip": nv.vineta = "c•"; Lectura.saltar(p); break;
                case "buFont": fuenteVineta = Lectura.atr(p, "typeface"); break;
                case "defRPr": leerRPr(p, c, nv); break;
                default: Lectura.saltar(p); break;
            }
        }
        if (nv.vineta != null && nv.vineta.startsWith("c") && fuenteVineta != null) {
            nv.vineta = "c" + vinetaSimbolo(nv.vineta.substring(1), fuenteVineta);
        }
        return nv;
    }

    /** Traduce los caracteres de Wingdings/Symbol más comunes a Unicode. */
    private static String vinetaSimbolo(String ch, String fuente) {
        String f = fuente.toLowerCase(java.util.Locale.ROOT);
        if (f.startsWith("wingdings")) {
            switch (ch) {
                case "§": return "▪";
                case "Ø": return "➢";
                case "ü": return "✓";
                case "q": return "❑";
                case "v": return "❖";
                case "n": return "■";
                case "l": return "●";
                case "o": return "○";
                case "à": return "→";
                case "è": return "➔";
                default: return "•";
            }
        }
        if (f.startsWith("symbol")) return "•";
        return ch;
    }

    /** {porcentaje (1 = 100 %), puntos (centésimas)}; -1 si no viene. */
    private static double[] espacio(XmlPullParser p) throws XmlPullParserException, IOException {
        double[] r = {-1, -1};
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Lectura.nombre(p);
            if (n.equals("spcPct")) r[0] = Lectura.entero(Lectura.atr(p, "val"), 100000) / 100000.0;
            else if (n.equals("spcPts")) r[1] = Lectura.entero(Lectura.atr(p, "val"), 0);
        }
        return r;
    }

    /** Fuentes del tema (títulos "+mj-lt" y cuerpo "+mn-lt"), guardadas junto a sus colores. */
    private void fuentesTema(String ruta, Map<String, String> tema) {
        try {
            XmlPullParser p = pq.parser(ruta);
            if (p == null) return;
            String grupo = null;
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Lectura.nombre(p);
                if (n.equals("majorFont")) grupo = "+mj-lt";
                else if (n.equals("minorFont")) grupo = "+mn-lt";
                else if (n.equals("latin") && grupo != null) {
                    String f = Lectura.atr(p, "typeface");
                    if (f != null && !f.isEmpty()) tema.put(grupo, f);
                    grupo = null;
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException ignorado) { }
    }

    private void leerRPr(XmlPullParser p, Ctx c, Nivel nv) throws XmlPullParserException, IOException {
        String v = Lectura.atr(p, "sz");
        if (v != null) nv.sz = Lectura.entero(v, 1800);
        if (Lectura.atr(p, "b") != null) nv.b = Lectura.booleano(Lectura.atr(p, "b"));
        if (Lectura.atr(p, "i") != null) nv.i = Lectura.booleano(Lectura.atr(p, "i"));
        v = Lectura.atr(p, "u");
        if (v != null) nv.u = !v.equals("none");
        v = Lectura.atr(p, "strike");
        if (v != null) nv.s = !v.equals("noStrike");
        v = Lectura.atr(p, "baseline");
        if (v != null) nv.base = Lectura.entero(v, 0);
        v = Lectura.atr(p, "cap");
        if (v != null) nv.mayus = v.equals("all");
        v = Lectura.atr(p, "spc");
        if (v != null) nv.spc = Lectura.entero(v, 0);
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "solidFill": case "gradFill": nv.color = primerColor(p, c); break;
                case "hlinkClick": nv.enlace = enlace(Lectura.rid(p, "id"), c); Lectura.saltar(p); break;
                case "latin": {
                    String f = Lectura.atr(p, "typeface");
                    if (f != null && f.startsWith("+")) f = c.tema.get(f.startsWith("+mj") ? "+mj-lt" : "+mn-lt");
                    if (f != null && !f.isEmpty()) nv.fuente = f;
                    Lectura.saltar(p);
                    break;
                }
                default: Lectura.saltar(p); break;
            }
        }
    }

    private String enlace(String id, Ctx c) {
        if (id == null) return null;
        Paquete.Relacion r = c.rels.get(id);
        if (r == null) return null;
        if (r.externa) return r.destino;
        Integer n = indiceDiapositiva.get(r.destino);
        return n != null ? "#d" + n : null;
    }

    private Cuerpo leerCuerpo(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        Cuerpo cu = new Cuerpo();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "bodyPr": {
                    cu.anclaje = Lectura.atr(p, "anchor");
                    String v;
                    if ((v = Lectura.atr(p, "lIns")) != null) cu.l = Lectura.largo(v, 91440);
                    if ((v = Lectura.atr(p, "tIns")) != null) cu.t = Lectura.largo(v, 45720);
                    if ((v = Lectura.atr(p, "rIns")) != null) cu.r = Lectura.largo(v, 91440);
                    if ((v = Lectura.atr(p, "bIns")) != null) cu.b = Lectura.largo(v, 45720);
                    cu.sinAjuste = "none".equals(Lectura.atr(p, "wrap"));
                    int db = p.getDepth();
                    while (Lectura.dentro(p, db)) {
                        if (Lectura.inicio(p) && "normAutofit".equals(Lectura.nombre(p))) {
                            cu.escala = Lectura.entero(Lectura.atr(p, "fontScale"), 100000) / 100000.0;
                            cu.reduccion = Lectura.entero(Lectura.atr(p, "lnSpcReduction"), 0) / 100000.0;
                        }
                    }
                    break;
                }
                case "lstStyle": cu.lista = leerLista(p, c); break;
                case "p": cu.parrafos.add(leerParrafo(p, c)); break;
                default: Lectura.saltar(p); break;
            }
        }
        return cu;
    }

    private Parrafo leerParrafo(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        Parrafo pa = new Parrafo();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "pPr":
                    pa.nivel = Math.max(0, Math.min(8, Lectura.entero(Lectura.atr(p, "lvl"), 0)));
                    pa.ppr = leerPPr(p, c);
                    break;
                case "r": case "fld": {
                    Corrida co = new Corrida();
                    StringBuilder t = new StringBuilder();
                    int dr = p.getDepth();
                    while (Lectura.dentro(p, dr)) {
                        if (!Lectura.inicio(p)) continue;
                        String n = Lectura.nombre(p);
                        if (n.equals("rPr")) {
                            co.rpr = new Nivel();
                            leerRPr(p, c, co.rpr);
                        } else if (n.equals("t")) {
                            t.append(Lectura.texto(p));
                        } else {
                            Lectura.saltar(p);
                        }
                    }
                    co.texto = t.toString();
                    pa.corridas.add(co);
                    break;
                }
                case "br": {
                    Corrida co = new Corrida();
                    co.salto = true;
                    pa.corridas.add(co);
                    Lectura.saltar(p);
                    break;
                }
                case "endParaRPr":
                    pa.fin = new Nivel();
                    leerRPr(p, c, pa.fin);
                    break;
                case "Fallback": case "extLst": Lectura.saltar(p); break;
                default: break;
            }
        }
        return pa;
    }

    // --- Tablas ---

    private String tabla(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder("<table class=pt>");
        List<Double> columnas = new ArrayList<>();
        boolean grupoCols = false;
        Nivel[][] cadena = {porDefecto};
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "gridCol": columnas.add(largo(p, "w") * k); break;
                case "tr": {
                    if (!grupoCols) {
                        grupoCols = true;
                        if (!columnas.isEmpty()) {
                            sb.append("<colgroup>");
                            for (double w : columnas) sb.append("<col style=\"width:").append(Html.px(w)).append("px\">");
                            sb.append("</colgroup>");
                        }
                    }
                    sb.append("<tr style=\"height:").append(Html.px(largo(p, "h") * k)).append("px\">");
                    int dr = p.getDepth();
                    while (Lectura.dentro(p, dr)) {
                        if (!Lectura.inicio(p)) continue;
                        if (!"tc".equals(Lectura.nombre(p))) {
                            Lectura.saltar(p);
                            continue;
                        }
                        celda(p, c, cadena, sb);
                    }
                    sb.append("</tr>");
                    break;
                }
                case "tblPr": case "extLst": Lectura.saltar(p); break;
                default: break;
            }
        }
        return sb.append("</table>").toString();
    }

    private void celda(XmlPullParser p, Ctx c, Nivel[][] cadena, StringBuilder sb)
            throws XmlPullParserException, IOException {
        if (Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "hMerge")))
                || Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "vMerge")))) {
            Lectura.saltar(p);
            return;
        }
        int span = Lectura.entero(Lectura.atr(p, "gridSpan"), 1);
        int filas = Lectura.entero(Lectura.atr(p, "rowSpan"), 1);
        Cuerpo cu = null;
        String fondo = null;
        long[] margen = {91440, 45720, 91440, 45720};
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Lectura.nombre(p);
            if (n.equals("txBody")) {
                cu = leerCuerpo(p, c);
            } else if (n.equals("tcPr")) {
                margen[0] = Lectura.largo(Lectura.atr(p, "marL"), margen[0]);
                margen[1] = Lectura.largo(Lectura.atr(p, "marT"), margen[1]);
                margen[2] = Lectura.largo(Lectura.atr(p, "marR"), margen[2]);
                margen[3] = Lectura.largo(Lectura.atr(p, "marB"), margen[3]);
                int dp = p.getDepth();
                while (Lectura.dentro(p, dp)) {
                    if (!Lectura.inicio(p)) continue;
                    String m = Lectura.nombre(p);
                    if (m.equals("solidFill") || m.equals("gradFill")) fondo = primerColor(p, c);
                    else Lectura.saltar(p); // bordes (lnL, lnR…) y demás
                }
            } else {
                Lectura.saltar(p);
            }
        }
        sb.append("<td");
        if (span > 1) sb.append(" colspan=").append(span);
        if (filas > 1) sb.append(" rowspan=").append(filas);
        sb.append(" style=\"padding:").append(Html.px(margen[1] * k)).append("px ").append(Html.px(margen[2] * k))
                .append("px ").append(Html.px(margen[3] * k)).append("px ").append(Html.px(margen[0] * k)).append("px");
        if (fondo != null) sb.append(";background:#").append(fondo);
        sb.append("\">");
        if (cu != null) parrafos(cu, cadena, sb);
        sb.append("</td>");
    }

    // --- Pintado ---

    private static String normalTipo(String t) {
        if (t == null) return "body";
        switch (t) {
            case "title": case "ctrTitle": return "title";
            case "dt": case "ftr": case "sldNum": case "hdr": return t;
            default: return "body";
        }
    }

    private static Forma buscar(List<Forma> lista, String tipo, String idx) {
        if (idx != null) {
            for (Forma m : lista) if (idx.equals(m.phIdx)) return m;
        }
        String tn = normalTipo(tipo);
        for (Forma m : lista) if (normalTipo(m.phTipo).equals(tn)) return m;
        return null;
    }

    /** Coloca la forma (con herencia de su marcador de posición) y la escribe en HTML. */
    private void terminar(Forma f, Ctx c, Transf t, StringBuilder out, Plantilla destino) {
        if (f.geometria) {
            f.x = t.tx + t.sx * f.x;
            f.y = t.ty + t.sy * f.y;
            f.w = t.sx * f.w;
            f.h = t.sy * f.h;
        }
        if (destino != null && f.marcador) {
            destino.marcadores.add(f);
            return;
        }
        Forma fd = null, fp = null;
        Nivel[] estilos = null;
        if (f.marcador) {
            if (c.diseno != null) fd = buscar(c.diseno.marcadores, f.phTipo, f.phIdx);
            String tipo = f.phTipo != null ? f.phTipo : fd != null ? fd.phTipo : null;
            if (c.patron != null) {
                fp = buscar(c.patron.marcadores, tipo, null);
                String tn = normalTipo(tipo);
                estilos = tn.equals("title") ? c.patron.titulo : tn.equals("body") ? c.patron.cuerpo : c.patron.otro;
            }
            if (!f.geometria) {
                Forma o = fd != null && fd.geometria ? fd : fp != null && fp.geometria ? fp : null;
                if (o == null) return;
                f.x = o.x;
                f.y = o.y;
                f.w = o.w;
                f.h = o.h;
                f.rot = o.rot;
                f.geometria = true;
            }
        }
        if (!f.geometria) return;
        pintar(f, fd, fp, estilos, out);
    }

    private void pintar(Forma f, Forma fd, Forma fp, Nivel[] estilos, StringBuilder out) {
        double x = f.x * k, y = f.y * k, w = Math.max(0, f.w * k), h = Math.max(0, f.h * k);
        String colorLinea = f.sinLinea ? null : f.linea != null ? f.linea : f.lineaEstilo;
        double grosor = f.grosor > 0 ? Math.max(1, f.grosor * k) : 1;
        String prst = f.prst == null ? "" : f.prst;

        if (f.conector || prst.equals("line") || prst.contains("Connector")) {
            if (colorLinea == null) return;
            double x1 = f.volteoH ? w : 0, x2 = f.volteoH ? 0 : w;
            double y1 = f.volteoV ? h : 0, y2 = f.volteoV ? 0 : h;
            out.append("<svg class=f style=\"left:").append(Html.px(x)).append("px;top:").append(Html.px(y))
                    .append("px\" width=\"").append(Html.px(Math.max(w, 1))).append("\" height=\"")
                    .append(Html.px(Math.max(h, 1))).append("\"><line x1=\"").append(Html.px(x1))
                    .append("\" y1=\"").append(Html.px(y1)).append("\" x2=\"").append(Html.px(x2))
                    .append("\" y2=\"").append(Html.px(y2)).append("\" stroke=\"#").append(colorLinea)
                    .append("\" stroke-width=\"").append(Html.px(grosor)).append("\"/></svg>");
            return;
        }

        String relleno = f.sinRelleno ? null : f.relleno != null ? f.relleno : f.rellenoEstilo;
        out.append("<div class=f style=\"left:").append(Html.px(x)).append("px;top:").append(Html.px(y))
                .append("px;width:").append(Html.px(w)).append("px;height:").append(Html.px(h)).append("px;");
        if (relleno != null && f.imagen == null) out.append("background:#").append(relleno).append(';');
        if (colorLinea != null && f.tabla == null) {
            out.append("border:").append(Html.px(grosor)).append("px solid #").append(colorLinea).append(';');
        }
        if (prst.equals("ellipse") || prst.equals("flowChartConnector")) {
            out.append("border-radius:50%;overflow:hidden;");
        } else if (prst.startsWith("round")) {
            out.append("border-radius:").append(Html.px(Math.min(w, h) * 0.16)).append("px;overflow:hidden;");
        }
        if (f.rot != 0) out.append("transform:rotate(").append(Html.px(f.rot)).append("deg);");
        out.append("\">");
        if (f.imagenRelleno != null) out.append("<div class=\"im ").append(f.imagenRelleno).append("\"></div>");
        if (f.imagen != null) {
            out.append("<div class=\"im ").append(f.imagen).append('"');
            if (f.volteoH || f.volteoV) {
                out.append(" style=\"transform:scale(").append(f.volteoH ? -1 : 1).append(',')
                        .append(f.volteoV ? -1 : 1).append(")\"");
            }
            if (f.alt != null && !f.alt.isEmpty()) out.append(" title=\"").append(Html.esc(f.alt)).append('"');
            out.append("></div>");
        }
        if (f.tabla != null) out.append(f.tabla);
        if (f.fijo != null && f.imagen == null) out.append("<div class=fijo>[").append(f.fijo).append("]</div>");
        if (f.cuerpo != null && tieneTexto(f.cuerpo)) texto(f, fd, fp, estilos, out);
        out.append("</div>");
    }

    private static boolean tieneTexto(Cuerpo cu) {
        for (Parrafo pa : cu.parrafos) {
            for (Corrida co : pa.corridas) if (co.texto != null && !co.texto.isEmpty()) return true;
        }
        return false;
    }

    private static Cuerpo cuerpoDe(Forma f) { return f != null ? f.cuerpo : null; }

    private void texto(Forma f, Forma fd, Forma fp, Nivel[] estilos, StringBuilder out) {
        Cuerpo cu = f.cuerpo, cd = cuerpoDe(fd), cp = cuerpoDe(fp);
        String anclaje = cu.anclaje != null ? cu.anclaje : cd != null && cd.anclaje != null ? cd.anclaje
                : cp != null ? cp.anclaje : null;
        long l = primero(cu.l, cd != null ? cd.l : null, cp != null ? cp.l : null, 91440L);
        long t = primero(cu.t, cd != null ? cd.t : null, cp != null ? cp.t : null, 45720L);
        long r = primero(cu.r, cd != null ? cd.r : null, cp != null ? cp.r : null, 91440L);
        long b = primero(cu.b, cd != null ? cd.b : null, cp != null ? cp.b : null, 45720L);
        boolean sinAjuste = cu.sinAjuste;

        out.append("<div class=tx style=\"padding:").append(Html.px(t * k)).append("px ").append(Html.px(r * k))
                .append("px ").append(Html.px(b * k)).append("px ").append(Html.px(l * k)).append("px;");
        if ("ctr".equals(anclaje)) out.append("justify-content:center;");
        else if ("b".equals(anclaje)) out.append("justify-content:flex-end;");
        if (sinAjuste) out.append("white-space:nowrap;");
        out.append("\">");

        Nivel[] estiloTexto = new Nivel[9];
        if (f.textoEstilo != null) {
            Nivel n = new Nivel();
            n.color = f.textoEstilo;
            java.util.Arrays.fill(estiloTexto, n);
        }
        Nivel[][] cadena = {
                cd != null ? cd.lista : null, cp != null ? cp.lista : null, estiloTexto, estilos, porDefecto};
        parrafos(cu, cadena, out);
        out.append("</div>");
    }

    private static long primero(Long a, Long b, Long c, long def) {
        return a != null ? a : b != null ? b : c != null ? c : def;
    }

    /** Escribe los párrafos de un cuerpo de texto resolviendo la herencia por niveles. */
    private void parrafos(Cuerpo cu, Nivel[][] heredados, StringBuilder out) {
        int[] contadores = new int[9];
        boolean[] activos = new boolean[9];
        for (Parrafo pa : cu.parrafos) {
            int lv = pa.nivel;
            List<Nivel> cad = new ArrayList<>();
            cad.add(pa.ppr);
            if (cu.lista[lv] != null) cad.add(cu.lista[lv]);
            for (Nivel[] arr : heredados) if (arr != null && arr[lv] != null) cad.add(arr[lv]);

            boolean hayTexto = false;
            Nivel primeraCorrida = null;
            for (Corrida co : pa.corridas) {
                if (co.texto != null && !co.texto.isEmpty()) {
                    hayTexto = true;
                    if (primeraCorrida == null) primeraCorrida = co.rpr != null ? co.rpr : new Nivel();
                }
            }
            Nivel ref = primeraCorrida != null ? primeraCorrida : pa.fin != null ? pa.fin : new Nivel();
            double tam = tamano(ref, cad, cu);

            String algn = null, vineta = null;
            Long marL = null, indent = null, lnPts = null, antes = null, despues = null;
            Double lnPct = null, antesPct = null;
            for (Nivel n : cad) {
                if (algn == null) algn = n.algn;
                if (vineta == null) vineta = n.vineta;
                if (marL == null) marL = n.marL;
                if (indent == null) indent = n.indent;
                if (lnPts == null && lnPct == null) {
                    lnPts = n.lnPts;
                    lnPct = n.lnPct;
                }
                if (antes == null && antesPct == null) {
                    antes = n.antes;
                    antesPct = n.antesPct;
                }
                if (despues == null) despues = n.despues;
            }

            out.append("<div class=pa style=\"font-size:").append(Html.px(tam)).append("px;");
            if (algn != null) {
                switch (algn) {
                    case "ctr": out.append("text-align:center;"); break;
                    case "r": out.append("text-align:right;"); break;
                    case "just": case "dist": out.append("text-align:justify;"); break;
                    default: break;
                }
            }
            double ml = marL != null ? marL * k : 0, ind = indent != null ? indent * k : 0;
            if (ml > 0) out.append("padding-left:").append(Html.px(ml)).append("px;");
            if (ind != 0) out.append("text-indent:").append(Html.px(ind)).append("px;");
            if (lnPts != null) {
                out.append("line-height:").append(Html.px(lnPts / 100.0 * 12700 * k)).append("px;");
            } else {
                double pct = lnPct != null ? lnPct : 1;
                out.append("line-height:").append(Html.px(Math.max(0.6, 1.2 * (pct - cu.reduccion)))).append(';');
            }
            if (antes != null && antes > 0) {
                out.append("margin-top:").append(Html.px(antes / 100.0 * 12700 * k)).append("px;");
            } else if (antesPct != null && antesPct > 0) {
                out.append("margin-top:").append(Html.px(antesPct * 1.2)).append("em;");
            }
            if (despues != null && despues > 0) {
                out.append("margin-bottom:").append(Html.px(despues / 100.0 * 12700 * k)).append("px;");
            }
            out.append("\">");

            if (!hayTexto) {
                out.append("<br></div>");
                continue;
            }

            // Viñeta o numeración automática.
            String marca = null;
            if (vineta != null && vineta.startsWith("n")) {
                int sep = vineta.lastIndexOf(':');
                String tipo = vineta.substring(1, sep);
                int inicio = Lectura.entero(vineta.substring(sep + 1), 1);
                contadores[lv] = activos[lv] ? contadores[lv] + 1 : inicio;
                activos[lv] = true;
                marca = numeroVineta(tipo, contadores[lv]);
            } else {
                activos[lv] = false;
                if (vineta != null && vineta.startsWith("c")) marca = vineta.substring(1);
            }
            for (int i = lv + 1; i < 9; i++) activos[i] = false;
            if (marca != null) {
                String color = color(primeraCorrida, cad);
                out.append("<span style=\"display:inline-block;text-indent:0;");
                if (ind < 0) out.append("min-width:").append(Html.px(-ind)).append("px;");
                else out.append("margin-right:.4em;");
                if (color != null) out.append("color:#").append(color).append(';');
                out.append("\">");
                Html.esc(out, marca);
                out.append("</span>");
            }

            for (Corrida co : pa.corridas) {
                if (co.salto) {
                    out.append("<br>");
                    continue;
                }
                if (co.texto == null || co.texto.isEmpty()) continue;
                corrida(co, cad, cu, out);
            }
            out.append("</div>");
        }
    }

    private double tamano(Nivel rpr, List<Nivel> cad, Cuerpo cu) {
        Integer sz = rpr.sz;
        for (int i = 0; sz == null && i < cad.size(); i++) sz = cad.get(i).sz;
        double pt = (sz != null && sz > 0 ? sz : 1800) / 100.0 * cu.escala;
        return Math.max(1, pt * 12700 * k);
    }

    /** Color del texto: primero la corrida, luego la cadena de niveles heredados. */
    private static String color(Nivel rpr, List<Nivel> cad) {
        if (rpr != null && rpr.color != null) return rpr.color;
        for (Nivel n : cad) if (n.color != null) return n.color;
        return null;
    }

    private static Boolean bandera(Nivel rpr, List<Nivel> cad, int cual) {
        Boolean v = rpr != null ? valor(rpr, cual) : null;
        for (int i = 0; v == null && i < cad.size(); i++) v = valor(cad.get(i), cual);
        return v;
    }

    private static Boolean valor(Nivel n, int cual) {
        switch (cual) {
            case 0: return n.b;
            case 1: return n.i;
            case 2: return n.u;
            case 3: return n.s;
            default: return n.mayus;
        }
    }

    private void corrida(Corrida co, List<Nivel> cad, Cuerpo cu, StringBuilder out) {
        Nivel r = co.rpr != null ? co.rpr : new Nivel();
        double tam = tamano(r, cad, cu);
        Integer base = r.base;
        for (int i = 0; base == null && i < cad.size(); i++) base = cad.get(i).base;
        String enlace = r.enlace;
        String color = color(r, cad);

        StringBuilder st = new StringBuilder();
        st.append("font-size:").append(Html.px(base != null && base != 0 ? tam * 0.7 : tam)).append("px;");
        if (Boolean.TRUE.equals(bandera(r, cad, 0))) st.append("font-weight:bold;");
        if (Boolean.TRUE.equals(bandera(r, cad, 1))) st.append("font-style:italic;");
        boolean u = Boolean.TRUE.equals(bandera(r, cad, 2)), s = Boolean.TRUE.equals(bandera(r, cad, 3));
        if (u || s) st.append("text-decoration:").append(u ? "underline " : "").append(s ? "line-through" : "").append(';');
        if (Boolean.TRUE.equals(bandera(r, cad, 4))) st.append("text-transform:uppercase;");
        if (color != null) st.append("color:#").append(color).append(';');
        String fuente = r.fuente;
        for (int i = 0; fuente == null && i < cad.size(); i++) fuente = cad.get(i).fuente;
        if (fuente != null) st.append("font-family:").append(familiaCss(fuente).replace("\"", "&quot;")).append(';');
        Integer spc = r.spc;
        for (int i = 0; spc == null && i < cad.size(); i++) spc = cad.get(i).spc;
        if (spc != null && spc != 0) st.append("letter-spacing:").append(Html.px(spc / 100.0 * 12700 * k * cu.escala)).append("px;");
        if (base != null && base > 0) st.append("vertical-align:super;");
        else if (base != null && base < 0) st.append("vertical-align:sub;");

        if (enlace != null) out.append("<a href=\"").append(Html.esc(enlace)).append("\">");
        out.append("<span style=\"").append(st).append("\">");
        Html.esc(out, co.texto);
        out.append("</span>");
        if (enlace != null) out.append("</a>");
    }

    /** Texto de una numeración automática (arabicPeriod, alphaLcParenR, romanUcPeriod…). */
    private static String numeroVineta(String tipo, int n) {
        if (tipo == null) tipo = "arabicPeriod";
        String num;
        if (tipo.startsWith("alphaLc")) num = letrasMinusculas(n);
        else if (tipo.startsWith("alphaUc")) num = letrasMinusculas(n).toUpperCase(java.util.Locale.ROOT);
        else if (tipo.startsWith("romanLc")) num = romano(n).toLowerCase(java.util.Locale.ROOT);
        else if (tipo.startsWith("romanUc")) num = romano(n);
        else num = Integer.toString(n);
        if (tipo.endsWith("ParenBoth")) return "(" + num + ")";
        if (tipo.endsWith("ParenR")) return num + ")";
        if (tipo.endsWith("Plain")) return num;
        if (tipo.endsWith("Minus")) return "-" + num + "-";
        return num + ".";
    }

    private static String letrasMinusculas(int n) {
        return n < 1 ? Integer.toString(n) : Html.letras(n).toLowerCase(java.util.Locale.ROOT);
    }

    private static String romano(int n) {
        if (n < 1 || n > 3999) return Integer.toString(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                sb.append(s[i]);
                n -= v[i];
            }
        }
        return sb.toString();
    }
}
