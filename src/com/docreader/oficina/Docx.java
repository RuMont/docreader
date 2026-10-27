package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convierte un documento de Word (.docx) en HTML. */
final class Docx {
    private static final String CSS =
            "body.docx{max-width:48em;margin:0 auto;padding:16px 18px 40px}"
            + "p,li,h1,h2,h3,h4,h5,h6{white-space:pre-wrap;tab-size:4}"
            + "p{margin:0 0 .55em}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1em 0 .45em}"
            + "h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.22em}h4{font-size:1.1em}h5,h6{font-size:1em}"
            + "ul,ol{margin:0 0 .55em;padding-left:1.7em}"
            + "li{margin:.15em 0}"
            + ".tabla{overflow-x:auto;margin:.6em 0}"
            + ".tabla table{border-collapse:collapse}"
            + ".tabla td{vertical-align:top;padding:4px 7px;border:1px solid transparent}"
            + ".tabla table.b td{border-color:#9a9a9a}"
            + ".tabla td p{margin:0 0 .2em}"
            + ".salto{display:block;border-top:1px dashed #b5b5b5;margin:1.2em 0}"
            + ".caja{border:1px solid #c8c8c8;border-radius:4px;padding:6px 10px;margin:.5em 0}";

    private static final Pattern ANCHO_VML = Pattern.compile(
            "(?i)(?:^|;)\\s*width\\s*:\\s*([0-9.]+)\\s*(pt|px|in|cm|mm)?");

    private static final Map<String, String> RESALTADOS = new HashMap<>();

    static {
        String[] r = {"yellow", "FFFF00", "green", "00FF00", "cyan", "00FFFF", "magenta", "FF00FF",
                "blue", "0000FF", "red", "FF0000", "darkBlue", "000080", "darkCyan", "008080",
                "darkGreen", "008000", "darkMagenta", "800080", "darkRed", "800000",
                "darkYellow", "808000", "darkGray", "808080", "lightGray", "C0C0C0",
                "black", "000000", "white", "FFFFFF"};
        for (int i = 0; i < r.length; i += 2) RESALTADOS.put(r[i], r[i + 1]);
    }

    private static final class Estilo {
        String nombre, basadoEn, numId;
        int ilvl = -1, esquema = -1;
    }

    /** Formato de una corrida de texto. */
    private static final class Formato {
        boolean b, i, u, s, oculto;
        int vertical;
        final StringBuilder css = new StringBuilder();
    }

    /** Estado de un párrafo mientras se lee. */
    private static final class Ctx {
        String estilo, numId, alinear;
        int ilvl = -1, esquema = -1;
        boolean titulo, saltoAntes, hayContenido;
        double sangria, primeraLinea;
        /** Cuadros de texto: se escriben detrás del párrafo. */
        final StringBuilder pendientes = new StringBuilder();
        /** Pila de campos abiertos: true mientras se leen sus instrucciones. */
        final List<Boolean> campos = new ArrayList<>();
        final StringBuilder instr = new StringBuilder();
        boolean enlaceCampo;

        boolean ocultoPorCampo() {
            for (Boolean b : campos) if (b) return true;
            return false;
        }
    }

    /** Celda de tabla. */
    private static final class Celda {
        final StringBuilder html = new StringBuilder();
        int span = 1, filas = 1, col, fusion; // fusion: 0 nada, 1 empieza, 2 continúa
        boolean continuaHorizontal, oculta;
        String fondo;
    }

    private final Paquete pq;
    private String parte;
    private Map<String, Paquete.Relacion> rels = Collections.emptyMap();
    private final Map<String, Estilo> estilos = new HashMap<>();
    private final Map<String, String> numAbstracto = new HashMap<>();
    private final Map<String, String[]> formatos = new HashMap<>();
    private final Map<String, int[]> inicios = new HashMap<>();
    private final Map<String, int[]> contadores = new HashMap<>();
    private double tamBase = 11;
    private String estiloDefecto;

    private Docx(Paquete pq) { this.pq = pq; }

    static Html.Documento convertir(Paquete pq) throws Fallo {
        Docx d = new Docx(pq);
        StringBuilder sb = new StringBuilder();
        d.procesar(sb);
        return new Html.Documento(sb.toString(), CSS, Html.VIEWPORT_MOVIL, "docx");
    }

    private void procesar(StringBuilder sb) throws Fallo {
        parte = pq.principal("word/document.xml");
        if (!pq.existe(parte)) {
            throw new Fallo("No se encontró el texto del documento. Puede que el archivo esté dañado.");
        }
        rels = pq.relaciones(parte);
        Paquete.Relacion r = Paquete.relacion(rels, "/styles");
        try {
            leerEstilos(r != null ? r.destino : "word/styles.xml");
        } catch (Exception ignorado) { }
        r = Paquete.relacion(rels, "/numbering");
        try {
            leerNumeracion(r != null ? r.destino : "word/numbering.xml");
        } catch (Exception ignorado) { }

        try {
            XmlPullParser p = pq.parser(parte);
            if (p == null) throw new Fallo("No se puede leer el texto del documento.");
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t == XmlPullParser.START_TAG && "body".equals(Lectura.nombre(p))) bloques(p, sb, null);
            }
        } catch (XmlPullParserException | IOException e) {
            if (sb.length() == 0) throw new Fallo("El documento está dañado y no se puede leer.");
            sb.append(Html.NOTA_DANADA);
        }
        if (sb.length() == 0) sb.append("<p class=nota>El documento está vacío.</p>");
    }

    // --- Estilos y numeración ---

    private void leerEstilos(String ruta) throws XmlPullParserException, IOException {
        XmlPullParser p = pq.parser(ruta);
        if (p == null) return;
        Estilo actual = null;
        boolean defectos = false;
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            String n = Lectura.nombre(p);
            if (t == XmlPullParser.END_TAG) {
                if (n.equals("style")) actual = null;
                else if (n.equals("rPrDefault")) defectos = false;
                continue;
            }
            if (t != XmlPullParser.START_TAG) continue;
            switch (n) {
                case "rPrDefault": defectos = true; break;
                case "sz":
                    if (defectos) {
                        int v = Lectura.entero(Lectura.atr(p, "val"), 0);
                        if (v > 0) tamBase = v / 2.0;
                    }
                    break;
                case "style": {
                    String id = Lectura.atr(p, "styleId");
                    if (id == null) break;
                    actual = new Estilo();
                    estilos.put(id, actual);
                    if ("paragraph".equals(Lectura.atr(p, "type"))
                            && Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "default")))) {
                        estiloDefecto = id;
                    }
                    break;
                }
                case "name": if (actual != null) actual.nombre = Lectura.atr(p, "val"); break;
                case "basedOn": if (actual != null) actual.basadoEn = Lectura.atr(p, "val"); break;
                case "outlineLvl":
                    if (actual != null) actual.esquema = Lectura.entero(Lectura.atr(p, "val"), -1);
                    break;
                case "numId": if (actual != null) actual.numId = Lectura.atr(p, "val"); break;
                case "ilvl": if (actual != null) actual.ilvl = Lectura.entero(Lectura.atr(p, "val"), -1); break;
                case "rPrChange": case "pPrChange": Lectura.saltar(p); break;
                default: break;
            }
        }
    }

    private void leerNumeracion(String ruta) throws XmlPullParserException, IOException {
        XmlPullParser p = pq.parser(ruta);
        if (p == null) return;
        String abs = null, num = null;
        int nivel = -1;
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            String n = Lectura.nombre(p);
            if (t == XmlPullParser.END_TAG) {
                if (n.equals("abstractNum")) abs = null;
                else if (n.equals("num")) num = null;
                else if (n.equals("lvl")) nivel = -1;
                continue;
            }
            if (t != XmlPullParser.START_TAG) continue;
            switch (n) {
                case "abstractNum":
                    abs = Lectura.atr(p, "abstractNumId");
                    if (abs != null) {
                        formatos.put(abs, new String[9]);
                        int[] ini = new int[9];
                        Arrays.fill(ini, 1);
                        inicios.put(abs, ini);
                    }
                    break;
                case "lvl":
                    if (abs != null) nivel = Lectura.entero(Lectura.atr(p, "ilvl"), -1);
                    break;
                case "numFmt":
                    // Si hay mc:AlternateContent, gana el último (el Fallback, con el formato clásico).
                    if (abs != null && nivel >= 0 && nivel < 9) formatos.get(abs)[nivel] = Lectura.atr(p, "val");
                    break;
                case "start":
                    if (abs != null && nivel >= 0 && nivel < 9) {
                        inicios.get(abs)[nivel] = Lectura.entero(Lectura.atr(p, "val"), 1);
                    }
                    break;
                case "num": num = Lectura.atr(p, "numId"); break;
                case "abstractNumId": if (num != null) numAbstracto.put(num, Lectura.atr(p, "val")); break;
                default: break;
            }
        }
    }

    /** Nivel de título (0 = h1) de un estilo, siguiendo la cadena basedOn; -1 si no es título. */
    private int nivelEstilo(String id) {
        for (int vueltas = 0; id != null && vueltas < 15; vueltas++) {
            Estilo e = estilos.get(id);
            if (e != null && e.esquema >= 0 && e.esquema < 9) return Math.min(e.esquema, 5);
            int n = nivelPorNombre(e != null ? e.nombre : null);
            if (n < 0) n = nivelPorNombre(id);
            if (n >= 0) return n;
            if (e == null) return -1;
            id = e.basadoEn;
        }
        return -1;
    }

    private static int nivelPorNombre(String nombre) {
        if (nombre == null) return -1;
        String s = nombre.toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.equals("title") || s.equals("título") || s.equals("titulo")) return 0;
        for (String pre : new String[] {"heading", "título", "titulo", "ttulo", "encabezado"}) {
            if (s.length() == pre.length() + 1 && s.startsWith(pre)) {
                char c = s.charAt(pre.length());
                if (c >= '1' && c <= '9') return Math.min(c - '1', 5);
            }
        }
        return -1;
    }

    private int nivelTitulo(Ctx c) {
        if (c.esquema >= 0 && c.esquema < 9) return Math.min(c.esquema, 5);
        return nivelEstilo(c.estilo != null ? c.estilo : estiloDefecto);
    }

    /** Numeración heredada del estilo del párrafo: {numId, ilvl}. */
    private Estilo numeracionEstilo(String id) {
        for (int vueltas = 0; id != null && vueltas < 15; vueltas++) {
            Estilo e = estilos.get(id);
            if (e == null) return null;
            if (e.numId != null) return e;
            id = e.basadoEn;
        }
        return null;
    }

    private String formatoLista(String numId, int nivel) {
        String abs = numAbstracto.get(numId);
        String[] f = abs != null ? formatos.get(abs) : null;
        return f != null && f[nivel] != null ? f[nivel] : "bullet";
    }

    private int siguienteNumero(String numId, int nivel) {
        int[] cont = contadores.get(numId);
        if (cont == null) {
            cont = new int[9];
            Arrays.fill(cont, Integer.MIN_VALUE);
            contadores.put(numId, cont);
        }
        String abs = numAbstracto.get(numId);
        int[] ini = abs != null ? inicios.get(abs) : null;
        int inicio = ini != null ? ini[nivel] : 1;
        cont[nivel] = cont[nivel] == Integer.MIN_VALUE ? inicio : cont[nivel] + 1;
        for (int k = nivel + 1; k < 9; k++) cont[k] = Integer.MIN_VALUE;
        return cont[nivel];
    }

    private static boolean esVineta(String fmt) {
        return fmt == null || fmt.equals("bullet") || fmt.equals("none");
    }

    private static String tipoOl(String fmt) {
        switch (fmt) {
            case "lowerLetter": return "a";
            case "upperLetter": return "A";
            case "lowerRoman": return "i";
            case "upperRoman": return "I";
            default: return "1";
        }
    }

    /** Listas abiertas en un contenedor (cuerpo, celda, cuadro de texto). */
    private static final class Listas {
        final List<String> etiquetas = new ArrayList<>(), claves = new ArrayList<>();

        void cerrar(StringBuilder sb, int hasta) {
            while (etiquetas.size() > hasta) {
                sb.append("</").append(etiquetas.remove(etiquetas.size() - 1)).append('>');
                claves.remove(claves.size() - 1);
            }
        }

        void abrirHasta(StringBuilder sb, String numId, int nivel, String fmt, int numero) {
            String et = esVineta(fmt) ? "ul" : "ol";
            cerrar(sb, nivel + 1);
            if (etiquetas.size() == nivel + 1) {
                int k = etiquetas.size() - 1;
                if (!claves.get(k).equals(numId) || !etiquetas.get(k).equals(et)) cerrar(sb, nivel);
            }
            while (etiquetas.size() < nivel + 1) {
                boolean propio = etiquetas.size() == nivel;
                sb.append('<').append(et);
                if (propio && et.equals("ol")) {
                    sb.append(" type=").append(tipoOl(fmt));
                    if (numero != 1) sb.append(" start=").append(numero);
                }
                if (propio && "none".equals(fmt)) sb.append(" style=\"list-style:none\"");
                sb.append('>');
                etiquetas.add(et);
                claves.add(numId);
            }
        }
    }

    // --- Bloques ---

    /** Lee párrafos y tablas hasta cerrar el elemento actual. Si celda != null, lee también tcPr. */
    private void bloques(XmlPullParser p, StringBuilder sb, Celda celda)
            throws XmlPullParserException, IOException {
        Listas l = new Listas();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            try {
                switch (Lectura.nombre(p)) {
                    case "p": parrafo(p, sb, l); break;
                    case "tbl": l.cerrar(sb, 0); tabla(p, sb); break;
                    case "tcPr":
                        if (celda != null) propiedadesCelda(p, celda);
                        else Lectura.saltar(p);
                        break;
                    case "bookmarkStart": ancla(p, sb); break;
                    case "sectPr": case "Fallback": case "del": case "moveFrom": case "sdtPr":
                    case "trPr": case "tblPr": case "customXmlPr":
                        Lectura.saltar(p);
                        break;
                    default: break; // contenedor desconocido (sdt, customXml…): se recorren sus hijos
                }
            } catch (RuntimeException e) {
                // Un elemento raro no debe tumbar todo el documento.
            }
        }
        l.cerrar(sb, 0);
    }

    private void ancla(XmlPullParser p, StringBuilder sb) {
        String n = Lectura.atr(p, "name");
        if (n != null && !n.equals("_GoBack")) sb.append("<a id=\"").append(Html.esc(n)).append("\"></a>");
    }

    private void parrafo(XmlPullParser p, StringBuilder sb, Listas l) throws XmlPullParserException, IOException {
        Ctx c = new Ctx();
        StringBuilder cont = new StringBuilder();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            if ("pPr".equals(Lectura.nombre(p))) {
                propiedadesParrafo(p, c);
                c.titulo = nivelTitulo(c) >= 0;
            } else {
                enLinea(p, cont, c);
            }
        }
        if (c.enlaceCampo) cont.append("</a>");
        if (!c.hayContenido) cont.append("<br>");

        int nivel = nivelTitulo(c);
        String numId = c.numId;
        int ilvl = c.ilvl;
        if (numId == null) {
            Estilo e = numeracionEstilo(c.estilo != null ? c.estilo : estiloDefecto);
            if (e != null) {
                numId = e.numId;
                if (ilvl < 0) ilvl = e.ilvl;
            }
        }
        boolean lista = nivel < 0 && numId != null && !numId.equals("0") && c.hayContenido;
        ilvl = Math.max(0, Math.min(8, ilvl));

        StringBuilder estilo = new StringBuilder();
        if (c.alinear != null) estilo.append("text-align:").append(c.alinear).append(';');
        if (!lista && c.sangria > 0) {
            estilo.append("margin-left:").append(Html.px(Math.min(c.sangria, 4000) / 20)).append("pt;");
        }
        if (!lista && c.primeraLinea != 0) {
            estilo.append("text-indent:").append(Html.px(Math.max(-2000, Math.min(2000, c.primeraLinea)) / 20))
                    .append("pt;");
        }
        String atrEstilo = estilo.length() > 0 ? " style=\"" + estilo + "\"" : "";

        if (c.saltoAntes) {
            l.cerrar(sb, 0);
            sb.append("<span class=salto></span>");
        }
        if (lista) {
            String fmt = formatoLista(numId, ilvl);
            int numero = siguienteNumero(numId, ilvl);
            l.abrirHasta(sb, numId, ilvl, fmt, numero);
            sb.append("<li").append(atrEstilo).append('>').append(cont).append("</li>");
        } else {
            l.cerrar(sb, 0);
            String et = nivel >= 0 ? "h" + (nivel + 1) : "p";
            sb.append('<').append(et).append(atrEstilo).append('>').append(cont).append("</").append(et).append('>');
        }
        sb.append(c.pendientes);
    }

    private void propiedadesParrafo(XmlPullParser p, Ctx c) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "pStyle": c.estilo = Lectura.atr(p, "val"); break;
                case "numId": c.numId = Lectura.atr(p, "val"); break;
                case "ilvl": c.ilvl = Lectura.entero(Lectura.atr(p, "val"), 0); break;
                case "outlineLvl": c.esquema = Lectura.entero(Lectura.atr(p, "val"), -1); break;
                case "pageBreakBefore": c.saltoAntes = Lectura.activo(p); break;
                case "jc": {
                    String v = Lectura.atr(p, "val");
                    if (v == null) break;
                    switch (v) {
                        case "center": c.alinear = "center"; break;
                        case "right": case "end": c.alinear = "right"; break;
                        case "both": case "distribute": c.alinear = "justify"; break;
                        default: c.alinear = null;
                    }
                    break;
                }
                case "ind": {
                    String izq = Lectura.atr(p, "left");
                    if (izq == null) izq = Lectura.atr(p, "start");
                    c.sangria = Lectura.real(izq, 0);
                    String colgante = Lectura.atr(p, "hanging");
                    if (colgante != null) c.primeraLinea = -Lectura.real(colgante, 0);
                    else c.primeraLinea = Lectura.real(Lectura.atr(p, "firstLine"), 0);
                    break;
                }
                case "rPr": case "pPrChange": case "sectPr": case "tabs": case "numberingChange":
                    Lectura.saltar(p);
                    break;
                default: break;
            }
        }
    }

    // --- Contenido de un párrafo ---

    private void enLinea(XmlPullParser p, StringBuilder sb, Ctx c) throws XmlPullParserException, IOException {
        switch (Lectura.nombre(p)) {
            case "r": corrida(p, sb, c); break;
            case "hyperlink": enlace(p, sb, c); break;
            case "bookmarkStart": ancla(p, sb); break;
            case "del": case "moveFrom": case "Fallback": case "rPr": case "sdtPr": case "pPr":
            case "instrText": case "delText": case "customXmlPr": case "smartTagPr": case "sdtEndPr":
                Lectura.saltar(p);
                break;
            default: break; // ins, smartTag, sdt, fldSimple, AlternateContent, oMath…: se recorren
        }
    }

    private void enlace(XmlPullParser p, StringBuilder sb, Ctx c) throws XmlPullParserException, IOException {
        String href = null;
        String id = Lectura.rid(p, "id");
        if (id != null) {
            Paquete.Relacion r = rels.get(id);
            if (r != null && r.externa) href = r.destino;
        }
        String ancla = Lectura.atr(p, "anchor");
        if (ancla != null) href = (href == null ? "" : href) + "#" + ancla;
        if (href != null) sb.append("<a href=\"").append(Html.esc(href)).append("\">");
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (Lectura.inicio(p)) enLinea(p, sb, c);
        }
        if (href != null) sb.append("</a>");
    }

    private void corrida(XmlPullParser p, StringBuilder sb, Ctx c) throws XmlPullParserException, IOException {
        Formato f = null;
        StringBuilder t = new StringBuilder();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "rPr": f = formato(p, c.titulo); break;
                case "t":
                    if (c.ocultoPorCampo()) Lectura.saltar(p);
                    else Html.esc(t, Lectura.texto(p));
                    break;
                case "tab": t.append('\t'); break;
                case "br":
                    if ("page".equals(Lectura.atr(p, "type"))) t.append("<span class=salto></span>");
                    else t.append("<br>");
                    break;
                case "cr": t.append("<br>"); break;
                case "noBreakHyphen": t.append('‑'); break;
                case "softHyphen": t.append("&shy;"); break;
                case "instrText":
                    if (c.campos.size() == 1 && c.campos.get(0)) c.instr.append(Lectura.texto(p));
                    else Lectura.saltar(p);
                    break;
                case "fldChar":
                    volcar(sb, t, f, c);
                    campo(Lectura.atr(p, "fldCharType"), sb, c);
                    break;
                case "drawing": dibujo(p, t, c); break;
                case "pict": case "object": vml(p, t, c); break;
                case "delText": case "Fallback": case "footnoteReference": case "endnoteReference":
                    Lectura.saltar(p);
                    break;
                default: break;
            }
        }
        volcar(sb, t, f, c);
    }

    /** Escribe el texto acumulado de la corrida con su formato. */
    private static void volcar(StringBuilder sb, StringBuilder t, Formato f, Ctx c) {
        if (t.length() == 0) return;
        if (f != null && f.oculto) {
            t.setLength(0);
            return;
        }
        c.hayContenido = true;
        if (f == null) {
            sb.append(t);
            t.setLength(0);
            return;
        }
        if (f.css.length() > 0) sb.append("<span style=\"").append(f.css).append("\">");
        if (f.b) sb.append("<b>");
        if (f.i) sb.append("<i>");
        if (f.u) sb.append("<u>");
        if (f.s) sb.append("<s>");
        if (f.vertical > 0) sb.append("<sup>");
        if (f.vertical < 0) sb.append("<sub>");
        sb.append(t);
        if (f.vertical < 0) sb.append("</sub>");
        if (f.vertical > 0) sb.append("</sup>");
        if (f.s) sb.append("</s>");
        if (f.u) sb.append("</u>");
        if (f.i) sb.append("</i>");
        if (f.b) sb.append("</b>");
        if (f.css.length() > 0) sb.append("</span>");
        t.setLength(0);
    }

    private Formato formato(XmlPullParser p, boolean titulo) throws XmlPullParserException, IOException {
        Formato f = new Formato();
        String fondo = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "b": f.b = Lectura.activo(p); break;
                case "i": f.i = Lectura.activo(p); break;
                case "u": {
                    String v = Lectura.atr(p, "val");
                    f.u = v == null || !v.equals("none");
                    break;
                }
                case "strike": case "dstrike": f.s = Lectura.activo(p); break;
                case "vanish": f.oculto = Lectura.activo(p); break;
                case "color": {
                    String v = Colores.hex6(Lectura.atr(p, "val"));
                    if (v != null) f.css.append("color:#").append(v).append(';');
                    break;
                }
                case "sz": {
                    double pt = Lectura.entero(Lectura.atr(p, "val"), 0) / 2.0;
                    if (!titulo && pt > 0 && tamBase > 0 && Math.abs(pt - tamBase) > 0.01) {
                        double em = Math.max(0.5, Math.min(4, pt / tamBase));
                        f.css.append("font-size:").append(Html.px(em)).append("em;");
                    }
                    break;
                }
                case "highlight": {
                    String v = RESALTADOS.get(Lectura.atr(p, "val"));
                    if (v != null) fondo = v;
                    break;
                }
                case "shd": {
                    String v = Colores.hex6(Lectura.atr(p, "fill"));
                    if (v != null && fondo == null) fondo = v;
                    break;
                }
                case "vertAlign": {
                    String v = Lectura.atr(p, "val");
                    f.vertical = "superscript".equals(v) ? 1 : "subscript".equals(v) ? -1 : 0;
                    break;
                }
                case "caps": if (Lectura.activo(p)) f.css.append("text-transform:uppercase;"); break;
                case "smallCaps": if (Lectura.activo(p)) f.css.append("font-variant:small-caps;"); break;
                case "rPrChange": Lectura.saltar(p); break;
                default: break;
            }
        }
        if (fondo != null) f.css.append("background:#").append(fondo).append(';');
        return f;
    }

    /** Campos complejos (fldChar): solo se interpretan los HYPERLINK; el resto muestra su resultado. */
    private void campo(String tipo, StringBuilder sb, Ctx c) {
        if (tipo == null) return;
        switch (tipo) {
            case "begin":
                c.campos.add(true);
                if (c.campos.size() == 1) c.instr.setLength(0);
                break;
            case "separate":
                if (c.campos.isEmpty()) break;
                if (c.campos.size() == 1) abrirEnlaceCampo(sb, c);
                c.campos.set(c.campos.size() - 1, false);
                break;
            case "end":
                if (c.campos.isEmpty()) break;
                if (c.campos.size() == 1 && c.enlaceCampo) {
                    sb.append("</a>");
                    c.enlaceCampo = false;
                }
                c.campos.remove(c.campos.size() - 1);
                break;
            default: break;
        }
    }

    private void abrirEnlaceCampo(StringBuilder sb, Ctx c) {
        String s = c.instr.toString().trim();
        if (!s.regionMatches(true, 0, "HYPERLINK", 0, 9)) return;
        String url = null, ancla = null;
        Matcher m = Pattern.compile("(\\\\l\\s+)?\"([^\"]*)\"").matcher(s);
        while (m.find()) {
            if (m.group(1) != null) ancla = m.group(2);
            else if (url == null) url = m.group(2);
        }
        String href = url != null ? url : "";
        if (ancla != null) href += "#" + ancla;
        if (href.isEmpty()) return;
        sb.append("<a href=\"").append(Html.esc(href)).append("\">");
        c.enlaceCampo = true;
    }

    // --- Imágenes y cuadros de texto ---

    private void dibujo(XmlPullParser p, StringBuilder t, Ctx c) throws XmlPullParserException, IOException {
        long ancho = 0;
        String alt = null;
        List<String> imagenes = new ArrayList<>();
        boolean grafico = false;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "extent":
                    if (ancho == 0) ancho = Lectura.largo(Lectura.atr(p, "cx"), 0);
                    break;
                case "docPr":
                    alt = Lectura.atr(p, "descr");
                    if (alt == null || alt.isEmpty()) alt = Lectura.atr(p, "title");
                    break;
                case "blip": {
                    String id = Lectura.rid(p, "embed");
                    if (id != null) imagenes.add(id);
                    break;
                }
                case "txbxContent":
                    c.pendientes.append("<div class=caja>");
                    bloques(p, c.pendientes, null);
                    c.pendientes.append("</div>");
                    break;
                case "chart": grafico = true; break;
                case "Fallback": Lectura.saltar(p); break;
                default: break;
            }
        }
        for (String id : imagenes) imagen(t, id, imagenes.size() == 1 ? ancho : 0, alt, c);
        if (imagenes.isEmpty() && grafico) {
            t.append("<span class=nota>[Gráfico]</span>");
            c.hayContenido = true;
        }
    }

    /** Imágenes VML antiguas (w:pict, w:object). */
    private void vml(XmlPullParser p, StringBuilder t, Ctx c) throws XmlPullParserException, IOException {
        long ancho = 0;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "shape": case "rect": case "roundrect": {
                    long a = anchoVml(Lectura.atr(p, "style"));
                    if (a > 0) ancho = a;
                    break;
                }
                case "imagedata": {
                    String id = Lectura.rid(p, "id");
                    if (id != null) imagen(t, id, ancho, Lectura.atr(p, "title"), c);
                    break;
                }
                case "txbxContent":
                    c.pendientes.append("<div class=caja>");
                    bloques(p, c.pendientes, null);
                    c.pendientes.append("</div>");
                    break;
                case "Fallback": Lectura.saltar(p); break;
                default: break;
            }
        }
    }

    /** Ancho en EMU a partir del atributo style de VML ("width:120pt;…"). */
    private static long anchoVml(String estilo) {
        if (estilo == null) return 0;
        Matcher m = ANCHO_VML.matcher(estilo);
        if (!m.find()) return 0;
        double v = Lectura.real(m.group(1), 0);
        String u = m.group(2) == null ? "px" : m.group(2).toLowerCase(Locale.ROOT);
        switch (u) {
            case "pt": return (long) (v * 12700);
            case "in": return (long) (v * 914400);
            case "cm": return (long) (v * 360000);
            case "mm": return (long) (v * 36000);
            default: return (long) (v * 9525);
        }
    }

    private void imagen(StringBuilder t, String id, long anchoEmu, String alt, Ctx c) {
        Paquete.Relacion r = rels.get(id);
        if (r == null || r.externa) return;
        String src = pq.imagen(r.destino);
        c.hayContenido = true;
        if (src == null) {
            t.append("<span class=nota>[imagen]</span>");
            return;
        }
        t.append("<img src=\"").append(src).append('"');
        if (alt != null && !alt.isEmpty()) t.append(" alt=\"").append(Html.esc(alt)).append('"');
        if (anchoEmu > 0) t.append(" style=\"width:").append(Html.px(anchoEmu / 9525.0)).append("px\"");
        t.append('>');
    }

    // --- Tablas ---

    private void tabla(XmlPullParser p, StringBuilder sb) throws XmlPullParserException, IOException {
        boolean bordes = false;
        List<List<Celda>> filas = new ArrayList<>();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "tblPr": bordes = bordesTabla(p); break;
                case "tblGrid": case "tblPrEx": case "Fallback": Lectura.saltar(p); break;
                case "tr": filas.add(fila(p)); break;
                default: break;
            }
        }

        // Fusiones verticales: la celda que continúa se oculta y la de arriba crece.
        for (int i = 0; i < filas.size(); i++) {
            for (Celda ce : filas.get(i)) {
                if (ce.fusion != 2) continue;
                Celda origen = null;
                for (int k = i - 1; k >= 0; k--) {
                    Celda o = enColumna(filas.get(k), ce.col);
                    if (o == null) break;
                    if (!o.oculta) {
                        origen = o;
                        break;
                    }
                }
                if (origen != null) {
                    origen.filas++;
                    ce.oculta = true;
                }
            }
        }

        sb.append("<div class=tabla><table").append(bordes ? " class=b" : "").append('>');
        for (List<Celda> fila : filas) {
            sb.append("<tr>");
            for (Celda ce : fila) {
                if (ce.oculta) continue;
                sb.append("<td");
                if (ce.span > 1) sb.append(" colspan=").append(ce.span);
                if (ce.filas > 1) sb.append(" rowspan=").append(ce.filas);
                if (ce.fondo != null) sb.append(" style=\"background:#").append(ce.fondo).append('"');
                sb.append('>').append(ce.html).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></div>");
    }

    private static Celda enColumna(List<Celda> fila, int col) {
        for (Celda c : fila) if (c.col == col) return c;
        return null;
    }

    private boolean bordesTabla(XmlPullParser p) throws XmlPullParserException, IOException {
        boolean hayEstilo = false, hayBordes = false;
        int visibles = 0;
        boolean enBordes = false;
        int d = p.getDepth();
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (t == XmlPullParser.END_TAG) {
                if (p.getDepth() <= d) break;
                if ("tblBorders".equals(Lectura.nombre(p))) enBordes = false;
                continue;
            }
            if (t != XmlPullParser.START_TAG) continue;
            String n = Lectura.nombre(p);
            if (n.equals("tblStyle")) {
                String v = Lectura.atr(p, "val");
                hayEstilo = v != null && !v.toLowerCase(Locale.ROOT).contains("normal");
            } else if (n.equals("tblBorders")) {
                hayBordes = true;
                enBordes = true;
            } else if (enBordes) {
                String v = Lectura.atr(p, "val");
                if (v != null && !v.equals("none") && !v.equals("nil")) visibles++;
            } else if (n.equals("tblPrChange")) {
                Lectura.saltar(p);
            }
        }
        return hayBordes ? visibles > 0 : hayEstilo;
    }

    private List<Celda> fila(XmlPullParser p) throws XmlPullParserException, IOException {
        List<Celda> fila = new ArrayList<>();
        int col = 0;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "gridBefore": col += Math.max(0, Lectura.entero(Lectura.atr(p, "val"), 0)); break;
                case "tc": {
                    Celda ce = new Celda();
                    bloques(p, ce.html, ce);
                    if (ce.continuaHorizontal && !fila.isEmpty()) {
                        fila.get(fila.size() - 1).span += ce.span;
                    } else {
                        ce.col = col;
                        fila.add(ce);
                    }
                    col += ce.span;
                    break;
                }
                case "tblPrEx": case "trPrChange": case "Fallback": Lectura.saltar(p); break;
                default: break;
            }
        }
        return fila;
    }

    private void propiedadesCelda(XmlPullParser p, Celda ce) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "gridSpan": ce.span = Math.max(1, Math.min(1000, Lectura.entero(Lectura.atr(p, "val"), 1))); break;
                case "vMerge": {
                    String v = Lectura.atr(p, "val");
                    ce.fusion = "restart".equals(v) ? 1 : 2;
                    break;
                }
                case "hMerge":
                    ce.continuaHorizontal = !"restart".equals(Lectura.atr(p, "val"));
                    break;
                case "shd": {
                    String v = Colores.hex6(Lectura.atr(p, "fill"));
                    if (v != null) ce.fondo = v;
                    break;
                }
                case "tcPrChange": Lectura.saltar(p); break;
                default: break;
            }
        }
    }
}
