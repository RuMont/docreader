package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Convierte un libro de Excel (.xlsx) en tablas HTML, una por hoja. */
final class Xlsx {
    static final int MAX_FILAS = 5000, MAX_COLS = 200;
    /** Tope de tamaño del HTML de todas las hojas juntas (en caracteres). */
    private static final int MAX_HTML = 30_000_000;

    private static final String CSS =
            "body.xlsx{padding:8px 10px 30px;font-size:14px}"
            + Html.CSS_HOJA;

    private static final String[] ORDEN_TEMA = {"lt1", "dk1", "lt2", "dk2", "accent1", "accent2",
            "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"};

    /** Paleta clásica de colores indexados de Excel. */
    private static final String[] PALETA = {
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080",
            "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
            "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF",
            "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
            "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696",
            "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333"};


    private static final class Xf {
        int numFmt, fuente, relleno;
        String alinear;
        boolean ajustar;
    }

    private static final class Fuente {
        boolean b, i, u, s;
        String color;
    }

    static final class Celda {
        String html, clase, estilo;
        boolean vacia;
    }

    private static final class Hoja {
        String nombre, ruta;
        boolean grafico;
    }

    private Paquete pq;
    /** Progreso de la lectura de hojas enormes (0–100), o -1; lo consulta el visor para mostrarlo. */
    static volatile int progreso = -1;

    /** Hoja con más filas de las que caben de una vez: se pasa a {@link HojaGrande}. */
    private static final class DemasiadasFilas extends RuntimeException {
        DemasiadasFilas() { super(null, null, false, false); }
    }
    private final List<String> compartidas = new ArrayList<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final Map<Integer, String> formatos = new HashMap<>();
    private final List<Fuente> fuentes = new ArrayList<>();
    private final List<String> rellenos = new ArrayList<>();
    private Map<String, String> tema = new HashMap<>();
    private String[] cssXf;
    private final FormatoNumero formato = new FormatoNumero();

    private Xlsx(Paquete pq) { this.pq = pq; }

    static Html.Documento convertir(Paquete pq) throws Fallo {
        Xlsx x = new Xlsx(pq);
        StringBuilder sb = new StringBuilder();
        List<String> nombres = new ArrayList<>(), cuerpos = new ArrayList<>();
        List<HojaGrande> grandes = new ArrayList<>();
        try {
            x.procesar(sb, nombres, cuerpos, grandes);
        } finally {
            progreso = -1;
        }
        x.pq = null; // ya no hace falta (y puede ocupar mucho)
        if (cuerpos.isEmpty()) return new Html.Documento(sb.toString(), CSS, Html.VIEWPORT_MOVIL, "xlsx");
        Html.Documento d = new Html.Documento("", CSS, Html.VIEWPORT_MOVIL, "xlsx",
                nombres.toArray(new String[0]), cuerpos.toArray(new String[0]));
        boolean alguna = false;
        for (HojaGrande g : grandes) alguna |= g != null;
        if (alguna) d.grandes = grandes.toArray(new HojaGrande[0]);
        return d;
    }

    /** Cada hoja visible se convierte por separado: nombre en "nombres", HTML en "cuerpos". */
    private void procesar(StringBuilder sb, List<String> nombres, List<String> cuerpos, List<HojaGrande> grandes)
            throws Fallo {
        String libro = pq.principal("xl/workbook.xml");
        if (!pq.existe(libro)) throw new Fallo("No se encontró el libro de cálculo. Puede que el archivo esté dañado.");
        Map<String, Paquete.Relacion> rels = pq.relaciones(libro);

        Paquete.Relacion r = Paquete.relacion(rels, "/theme");
        if (r != null) tema = Colores.tema(pq, r.destino);
        r = Paquete.relacion(rels, "/sharedStrings");
        try {
            leerCompartidas(r != null ? r.destino : "xl/sharedStrings.xml");
        } catch (Exception ignorado) { }
        r = Paquete.relacion(rels, "/styles");
        try {
            leerEstilos(r != null ? r.destino : "xl/styles.xml");
        } catch (Exception ignorado) { }
        cssXf = new String[xfs.size()];

        List<Hoja> hojas = new ArrayList<>();
        try {
            XmlPullParser p = pq.parser(libro);
            if (p == null) throw new Fallo("No se puede leer el libro de cálculo.");
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Lectura.nombre(p);
                if (n.equals("workbookPr")) {
                    formato.fecha1904 = Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "date1904")));
                } else if (n.equals("sheet")) {
                    String estado = Lectura.atr(p, "state");
                    if ("hidden".equals(estado) || "veryHidden".equals(estado)) continue;
                    Paquete.Relacion rh = rels.get(Lectura.rid(p, "id"));
                    if (rh == null || rh.externa) continue;
                    Hoja h = new Hoja();
                    h.nombre = Lectura.atr(p, "name");
                    if (h.nombre == null) h.nombre = "Hoja " + (hojas.size() + 1);
                    h.ruta = rh.destino;
                    h.grafico = !rh.es("/worksheet");
                    hojas.add(h);
                }
            }
        } catch (XmlPullParserException | IOException e) {
            if (hojas.isEmpty()) throw new Fallo("El libro está dañado y no se puede leer.");
        }
        if (hojas.isEmpty()) {
            sb.append("<p class=nota>El libro no tiene hojas visibles.</p>");
            return;
        }

        long total = 0;
        for (Hoja h : hojas) {
            sb.setLength(0);
            HojaGrande grande = null;
            if (!h.grafico && pq.esDiferida(h.ruta)) {
                grande = grande(h);
                if (grande == null) sb.append("<p class=nota>No se pudo leer esta hoja.</p>");
                else sb.append(grande.bloque(0));
            } else if (total > MAX_HTML) {
                sb.append("<p class=nota>El libro es demasiado grande: esta hoja no se muestra.</p>");
            } else if (h.grafico) {
                sb.append("<p class=nota>Esta hoja es un gráfico y no se puede mostrar.</p>");
            } else {
                int antes = sb.length();
                try {
                    hoja(h.ruta, sb);
                } catch (DemasiadasFilas e) {
                    // Más filas de las que caben de una vez: se lee como hoja grande, por bloques.
                    sb.setLength(antes);
                    grande = grande(h);
                    if (grande == null) sb.append("<p class=nota>No se pudo leer esta hoja.</p>");
                    else sb.append(grande.bloque(0));
                } catch (XmlPullParserException | IOException | RuntimeException e) {
                    sb.setLength(antes);
                    sb.append("<p class=nota>No se pudo leer esta hoja.</p>");
                }
            }
            if (grande == null) total += sb.length();
            nombres.add(h.nombre);
            cuerpos.add(sb.toString());
            grandes.add(grande);
        }
    }

    /** Lee una hoja grande en streaming, actualizando {@link #progreso}; null si falla. */
    private HojaGrande grande(Hoja h) {
        java.util.concurrent.atomic.AtomicLong leido = new java.util.concurrent.atomic.AtomicLong();
        long total = pq.comprimido(h.ruta);
        if (total <= 0) {
            byte[] b = pq.bytes(h.ruta);
            total = b == null ? 1 : b.length;
        }
        final long tot = total;
        java.io.InputStream in = pq.flujo(h.ruta, leido);
        if (in == null) return null;
        java.io.InputStream conProgreso = new java.io.FilterInputStream(in) {
            @Override public int read(byte[] x, int o, int n) throws IOException {
                int r = super.read(x, o, n);
                // Las partes en memoria no cuentan en "leido": se aproxima con lo leído del flujo.
                if (pq != null && pq.comprimido(h.ruta) < 0 && r > 0) leido.addAndGet(r);
                progreso = (int) Math.min(99, leido.get() * 100 / tot);
                return r;
            }
        };
        try (java.io.InputStream cerrar = conProgreso) {
            return HojaGrande.leer(h.nombre, this, cerrar);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Textos compartidos sin etiquetas y "plegados" (para buscar), calculados una vez. */
    private String[] planas;

    synchronized String[] compartidasPlanas() {
        if (planas == null) {
            planas = new String[compartidas.size()];
            for (int i = 0; i < planas.length; i++) planas[i] = HojaGrande.plegar(textoPlano(compartidas.get(i)));
        }
        return planas;
    }

    /** Texto visible de un fragmento HTML (sin etiquetas y con las entidades resueltas). */
    static String textoPlano(String html) {
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) return html;
        StringBuilder sb = new StringBuilder(html.length());
        boolean etiqueta = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') etiqueta = true;
            else if (c == '>') etiqueta = false;
            else if (!etiqueta) sb.append(c);
        }
        return sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    // --- Textos compartidos y estilos ---

    private void leerCompartidas(String ruta) throws XmlPullParserException, IOException {
        XmlPullParser p = pq.parser(ruta);
        if (p == null) return;
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (t == XmlPullParser.START_TAG && "si".equals(Lectura.nombre(p))) compartidas.add(textoRico(p));
        }
    }

    /** Contenido de un &lt;si&gt; o &lt;is&gt; como HTML (con negrita, cursiva y color de las corridas). */
    private String textoRico(XmlPullParser p) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "t": Html.esc(sb, Lectura.texto(p)); break;
                case "r": {
                    Fuente f = null;
                    StringBuilder t = new StringBuilder();
                    int dr = p.getDepth();
                    while (Lectura.dentro(p, dr)) {
                        if (!Lectura.inicio(p)) continue;
                        String n = Lectura.nombre(p);
                        if (n.equals("rPr")) f = fuente(p);
                        else if (n.equals("t")) Html.esc(t, Lectura.texto(p));
                    }
                    String css = f != null ? cssFuente(f) : "";
                    if (css.isEmpty()) sb.append(t);
                    else sb.append("<span style=\"").append(css).append("\">").append(t).append("</span>");
                    break;
                }
                case "rPh": case "phoneticPr": Lectura.saltar(p); break;
                default: break;
            }
        }
        return sb.toString();
    }

    private void leerEstilos(String ruta) throws XmlPullParserException, IOException {
        XmlPullParser p = pq.parser(ruta);
        if (p == null) return;
        String seccion = "";
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            String n = Lectura.nombre(p);
            if (t == XmlPullParser.END_TAG) {
                if (n.equals(seccion)) seccion = "";
                continue;
            }
            if (t != XmlPullParser.START_TAG) continue;
            switch (n) {
                case "numFmts": case "fonts": case "fills": case "cellXfs": case "cellStyleXfs":
                case "dxfs": case "borders": case "cellStyles": case "colors": case "tableStyles":
                    seccion = n;
                    break;
                case "numFmt":
                    if (seccion.equals("numFmts")) {
                        formatos.put(Lectura.entero(Lectura.atr(p, "numFmtId"), -1), Lectura.atr(p, "formatCode"));
                    }
                    break;
                case "font": if (seccion.equals("fonts")) fuentes.add(fuente(p)); break;
                case "fill": if (seccion.equals("fills")) rellenos.add(relleno(p)); break;
                case "xf": if (seccion.equals("cellXfs")) xfs.add(xf(p)); break;
                default: break;
            }
        }
    }

    private Fuente fuente(XmlPullParser p) throws XmlPullParserException, IOException {
        Fuente f = new Fuente();
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "b": f.b = Lectura.activo(p); break;
                case "i": f.i = Lectura.activo(p); break;
                case "u": f.u = !"none".equals(Lectura.atr(p, "val")); break;
                case "strike": f.s = Lectura.activo(p); break;
                case "color": f.color = color(p); break;
                default: break;
            }
        }
        return f;
    }

    private String relleno(XmlPullParser p) throws XmlPullParserException, IOException {
        String tipo = null, frente = null, fondo = null, degradado = null;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Lectura.nombre(p)) {
                case "patternFill": tipo = Lectura.atr(p, "patternType"); break;
                case "fgColor": frente = color(p); break;
                case "bgColor": fondo = color(p); break;
                case "color": if (degradado == null) degradado = color(p); break;
                default: break;
            }
        }
        if (degradado != null) return degradado;
        if ("solid".equals(tipo)) return frente != null ? frente : fondo;
        return null;
    }

    private Xf xf(XmlPullParser p) throws XmlPullParserException, IOException {
        Xf x = new Xf();
        x.numFmt = Lectura.entero(Lectura.atr(p, "numFmtId"), 0);
        x.fuente = Lectura.entero(Lectura.atr(p, "fontId"), 0);
        x.relleno = Lectura.entero(Lectura.atr(p, "fillId"), 0);
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (Lectura.inicio(p) && "alignment".equals(Lectura.nombre(p))) {
                x.alinear = Lectura.atr(p, "horizontal");
                x.ajustar = Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "wrapText")));
            }
        }
        return x;
    }

    /** Color de Excel: rgb (ARGB), theme + tint o indexed. */
    private String color(XmlPullParser p) {
        String base = null;
        String rgb = Lectura.atr(p, "rgb");
        String t = Lectura.atr(p, "theme");
        String ind = Lectura.atr(p, "indexed");
        if (rgb != null) {
            base = Colores.hex6(rgb);
        } else if (t != null) {
            int i = Lectura.entero(t, -1);
            if (i >= 0 && i < ORDEN_TEMA.length) base = tema.get(ORDEN_TEMA[i]);
            if (base == null && i == 0) base = "FFFFFF";
            if (base == null && i == 1) base = "000000";
        } else if (ind != null) {
            int i = Lectura.entero(ind, -1);
            if (i >= 0 && i < PALETA.length) base = PALETA[i];
        }
        double tinte = Lectura.real(Lectura.atr(p, "tint"), 0);
        if (base != null && tinte != 0) base = Colores.tinteExcel(base, Math.max(-1, Math.min(1, tinte)));
        return base;
    }

    private static String cssFuente(Fuente f) {
        StringBuilder css = new StringBuilder();
        if (f.b) css.append("font-weight:bold;");
        if (f.i) css.append("font-style:italic;");
        if (f.u || f.s) {
            css.append("text-decoration:").append(f.u ? "underline " : "").append(f.s ? "line-through" : "")
                    .append(';');
        }
        if (f.color != null && !f.color.equals("000000")) css.append("color:#").append(f.color).append(';');
        return css.toString();
    }

    private String cssDe(int s) {
        if (s < 0 || s >= xfs.size()) return "";
        if (cssXf[s] != null) return cssXf[s];
        Xf x = xfs.get(s);
        StringBuilder css = new StringBuilder();
        if (x.fuente >= 0 && x.fuente < fuentes.size()) css.append(cssFuente(fuentes.get(x.fuente)));
        if (x.relleno >= 0 && x.relleno < rellenos.size() && rellenos.get(x.relleno) != null) {
            css.append("background:#").append(rellenos.get(x.relleno)).append(';');
        }
        cssXf[s] = css.toString();
        return cssXf[s];
    }

    // --- Hojas ---

    private static long clave(int f, int c) { return ((long) f << 16) | c; }

    /** "AB12" → {12, 28}; null si no es una referencia válida. */
    private static int[] referencia(String ref) {
        int i = 0, col = 0, fila = 0, n = ref.length();
        while (i < n && ref.charAt(i) == '$') i++;
        int ini = i;
        while (i < n && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            if (col > 20000) return null;
            i++;
        }
        if (i == ini) return null;
        if (i < n && ref.charAt(i) == '$') i++;
        int iniF = i;
        while (i < n && Character.isDigit(ref.charAt(i))) {
            fila = fila * 10 + (ref.charAt(i) - '0');
            if (fila > 2_000_000) return null;
            i++;
        }
        if (i == iniF || i != n) return null;
        return new int[] {fila, col};
    }

    private static int[] rango(String ref) {
        if (ref == null) return null;
        String[] partes = ref.split(":");
        int[] a = referencia(partes[0].trim());
        int[] b = partes.length > 1 ? referencia(partes[1].trim()) : a;
        if (a == null || b == null) return null;
        return new int[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])};
    }

    private void hoja(String ruta, StringBuilder sb) throws XmlPullParserException, IOException {
        XmlPullParser p = pq.parser(ruta);
        if (p == null) {
            sb.append("<p class=nota>No se encontró esta hoja en el archivo.</p>");
            return;
        }
        Map<Long, Celda> celdas = new HashMap<>();
        List<int[]> fusiones = new ArrayList<>();
        Map<Integer, Double> anchos = new HashMap<>();
        int maxF = 0, maxC = 0, fila = 0, ultimaCol = 0;
        boolean recortada = false, dibujos = false;
        int t;
        while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (t != XmlPullParser.START_TAG) continue;
            switch (Lectura.nombre(p)) {
                case "col": {
                    int a = Math.max(1, Lectura.entero(Lectura.atr(p, "min"), 0));
                    int b = Math.min(MAX_COLS, Lectura.entero(Lectura.atr(p, "max"), 0));
                    double w = Lectura.real(Lectura.atr(p, "width"), -1);
                    if (Boolean.TRUE.equals(Lectura.booleano(Lectura.atr(p, "hidden")))) w = 0.5;
                    if (w > 0) for (int c = a; c <= b; c++) anchos.put(c, Math.min(w, 120) * 7 + 5);
                    break;
                }
                case "row":
                    fila = Lectura.entero(Lectura.atr(p, "r"), fila + 1);
                    ultimaCol = 0;
                    break;
                case "c": {
                    int[] rc = null;
                    String ref = Lectura.atr(p, "r");
                    if (ref != null) rc = referencia(ref);
                    int f = rc != null ? rc[0] : Math.max(fila, 1);
                    int c = rc != null ? rc[1] : ultimaCol + 1;
                    ultimaCol = c;
                    String tipo = Lectura.atr(p, "t");
                    int s = Lectura.entero(Lectura.atr(p, "s"), 0);
                    String v = null, rica = null;
                    int d = p.getDepth();
                    while (Lectura.dentro(p, d)) {
                        if (!Lectura.inicio(p)) continue;
                        String n = Lectura.nombre(p);
                        if (n.equals("v")) v = Lectura.texto(p);
                        else if (n.equals("is")) rica = textoRico(p);
                        else Lectura.saltar(p);
                    }
                    Celda ce = celda(tipo, v, rica, s);
                    if (f > MAX_FILAS && !ce.vacia && c <= MAX_COLS) throw new DemasiadasFilas();
                    if (f > MAX_FILAS || c > MAX_COLS) {
                        if (!ce.vacia) recortada = true;
                        break;
                    }
                    if (ce.vacia && ce.estilo == null) break;
                    celdas.put(clave(f, c), ce);
                    if (!ce.vacia) {
                        maxF = Math.max(maxF, f);
                        maxC = Math.max(maxC, c);
                    }
                    break;
                }
                case "mergeCell": {
                    int[] r = rango(Lectura.atr(p, "ref"));
                    if (r != null) fusiones.add(r);
                    break;
                }
                case "drawing": case "legacyDrawing": dibujos = true; break;
                case "sheetViews": case "pageMargins": case "pageSetup": case "headerFooter":
                case "conditionalFormatting": case "dataValidations": case "extLst": case "sheetPr":
                    Lectura.saltar(p);
                    break;
                default: break;
            }
        }

        // Las fusiones cuyo origen tiene contenido amplían el área visible.
        for (int[] r : fusiones) {
            Celda o = celdas.get(clave(r[0], r[1]));
            if (o != null && !o.vacia && r[0] <= MAX_FILAS && r[1] <= MAX_COLS) {
                maxF = Math.max(maxF, Math.min(r[2], MAX_FILAS));
                maxC = Math.max(maxC, Math.min(r[3], MAX_COLS));
            }
        }
        if (maxF == 0) {
            sb.append("<p class=nota>Hoja vacía.</p>");
            if (dibujos) sb.append("<p class=nota>Esta hoja tiene gráficos o imágenes que no se muestran.</p>");
            return;
        }

        int ultimaFila = tabla(sb, celdas, fusiones, anchos, 1, maxF, maxC);
        if (ultimaFila < maxF) recortada = true;
        if (recortada) {
            sb.append("<p class=nota>La hoja es muy grande: se muestran solo las primeras ")
                    .append(ultimaFila).append(" filas y ").append(maxC).append(" columnas.</p>");
        }
        if (dibujos) sb.append("<p class=nota>Esta hoja tiene gráficos o imágenes que no se muestran.</p>");
    }

    /**
     * Tabla HTML de las filas f0..maxF (números de fila reales) y columnas 1..maxC. La usan las
     * hojas normales y los bloques de {@link HojaGrande}. Devuelve la última fila escrita.
     */
    int tabla(StringBuilder sb, Map<Long, Celda> celdas, List<int[]> fusiones, Map<Integer, Double> anchos,
              int f0, int maxF, int maxC) {
        Map<Long, int[]> inicioFusion = new HashMap<>();
        Set<Long> cubiertas = new HashSet<>();
        for (int[] r : fusiones) {
            if (r[0] > maxF || r[1] > maxC) continue;
            int f2 = Math.min(r[2], maxF), c2 = Math.min(r[3], maxC);
            if (f2 == r[0] && c2 == r[1]) continue;
            long k = clave(r[0], r[1]);
            if (cubiertas.contains(k) || inicioFusion.containsKey(k)) continue;
            inicioFusion.put(k, new int[] {f2 - r[0] + 1, c2 - r[1] + 1});
            for (int f = r[0]; f <= f2; f++) {
                for (int c = r[1]; c <= c2; c++) {
                    if (f != r[0] || c != r[1]) cubiertas.add(clave(f, c));
                }
            }
        }

        sb.append("<table class=hoja><colgroup><col>");
        for (int c = 1; c <= maxC; c++) {
            Double w = anchos.get(c);
            if (w != null) sb.append("<col style=\"width:").append(Html.px(w)).append("px\">");
            else sb.append("<col>");
        }
        sb.append("</colgroup><thead><tr><th class=esq></th>");
        for (int c = 1; c <= maxC; c++) sb.append("<th>").append(Html.letras(c)).append("</th>");
        sb.append("</tr></thead><tbody>");
        int ultimaFila = maxF;
        for (int f = f0; f <= maxF; f++) {
            if (sb.length() > MAX_HTML) {
                ultimaFila = f - 1;
                break;
            }
            sb.append("<tr><th>").append(f).append("</th>");
            for (int c = 1; c <= maxC; c++) {
                long k = clave(f, c);
                if (cubiertas.contains(k)) continue;
                Celda ce = celdas.get(k);
                int[] fu = inicioFusion.get(k);
                sb.append("<td");
                if (fu != null) {
                    if (fu[0] > 1) sb.append(" rowspan=").append(fu[0]);
                    if (fu[1] > 1) sb.append(" colspan=").append(fu[1]);
                }
                if (ce != null) {
                    if (ce.clase != null) sb.append(" class=\"").append(ce.clase).append('"');
                    if (ce.estilo != null) sb.append(" style=\"").append(ce.estilo).append('"');
                    sb.append('>').append(ce.html);
                } else {
                    sb.append('>');
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return ultimaFila;
    }

    Celda celda(String tipo, String v, String rica, int s) {
        Celda ce = new Celda();
        Xf x = s >= 0 && s < xfs.size() ? xfs.get(s) : null;
        String clase = null;
        boolean texto = false;
        if (tipo == null) tipo = "n";
        switch (tipo) {
            case "s": {
                int i = Lectura.entero(v, -1);
                ce.html = i >= 0 && i < compartidas.size() ? compartidas.get(i) : "";
                texto = true;
                break;
            }
            case "inlineStr":
                ce.html = rica != null ? rica : Html.esc(v == null ? "" : v);
                texto = true;
                break;
            case "b":
                ce.html = v == null ? "" : "1".equals(v.trim()) ? "VERDADERO" : "FALSO";
                clase = "c";
                break;
            case "e":
                ce.html = Html.esc(v == null ? "" : v);
                clase = "c";
                break;
            case "str":
                ce.html = Html.esc(v == null ? "" : v);
                texto = true;
                break;
            case "d":
                ce.html = Html.esc(v == null ? "" : v.replace('T', ' '));
                clase = "n";
                break;
            default:
                if (v == null || v.trim().isEmpty()) {
                    ce.html = "";
                } else {
                    try {
                        ce.html = Html.esc(numero(Double.parseDouble(v.trim()), x));
                        clase = "n";
                    } catch (NumberFormatException e) {
                        ce.html = Html.esc(v);
                        texto = true;
                    }
                }
        }
        ce.vacia = ce.html.isEmpty();
        if (x != null && x.alinear != null) {
            switch (x.alinear) {
                case "center": case "centerContinuous": clase = "c"; break;
                case "right": clase = "n"; break;
                case "left": clase = "l"; break;
                default: break;
            }
        }
        if (texto && ((x != null && x.ajustar) || largoVisible(ce.html) > 60 || ce.html.indexOf('\n') >= 0)) {
            clase = clase == null ? "w" : clase + " w";
        }
        ce.clase = clase;
        String css = cssDe(s);
        ce.estilo = css.isEmpty() ? null : css;
        return ce;
    }

    /** Caracteres de texto de un fragmento HTML, sin contar las etiquetas. */
    private static int largoVisible(String html) {
        int n = 0;
        boolean etiqueta = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') etiqueta = true;
            else if (c == '>') etiqueta = false;
            else if (!etiqueta) n++;
        }
        return n;
    }

    // --- Números y fechas ---

    private String numero(double v, Xf x) {
        int id = x != null ? x.numFmt : 0;
        return formato.numero(v, id, formatos.get(id));
    }
}
