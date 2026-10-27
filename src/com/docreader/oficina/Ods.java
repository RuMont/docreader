package com.docreader.oficina;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte una hoja de cálculo de OpenDocument (.ods) en tablas HTML, una por hoja, con el
 * mismo aspecto que Xlsx. Las filas y columnas repetidas se guardan como tramos, así que los
 * miles de filas vacías del final no ocupan memoria.
 */
final class Ods {
    private static final int MAX_FILAS = Xlsx.MAX_FILAS, MAX_COLS = Xlsx.MAX_COLS;
    /** Tope de tamaño del HTML de todas las hojas juntas (en caracteres). */
    private static final int MAX_HTML = 30_000_000;
    /** Tope para los contadores de filas y columnas, para que no se desborden. */
    private static final int TOPE = 1 << 30;

    private static final String CSS =
            "body.ods{padding:8px 10px 30px;font-size:14px}"
            + Html.CSS_HOJA;

    private static final Pattern FECHA = Pattern.compile(
            "(-?\\d{1,9})-(\\d{1,2})-(\\d{1,2})(?:T(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?)?.*");
    private static final Pattern DURACION = Pattern.compile(
            "-?P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:[.,]\\d*)?S)?)?");

    private static final class Celda {
        String html, clase, estilo;
        boolean vacia;
    }

    /** Celdas iguales seguidas en una fila (number-columns-repeated). */
    private static final class Tramo {
        final int col, n;
        final Celda ce;

        Tramo(int col, int n, Celda ce) {
            this.col = col;
            this.n = n;
            this.ce = ce;
        }
    }

    /** Una fila leída, que puede valer por varias iguales (number-rows-repeated). */
    private static final class Fila {
        final int inicio, cuenta;
        final List<Tramo> tramos = new ArrayList<>();

        Fila(int inicio, int cuenta) {
            this.inicio = inicio;
            this.cuenta = cuenta;
        }
    }

    /** Formato de un estilo de celda, ya traducido. */
    private static final class Formato {
        String css, alinear;
        boolean ajustar;
    }

    private final Paquete pq;
    private final Odf.Estilos est;
    private final Odt texto;
    private final Map<String, Formato> formatos = new HashMap<>();
    private final DecimalFormatSymbols simbolos = DecimalFormatSymbols.getInstance();

    // Estado de la hoja que se está leyendo.
    private List<Fila> filas;
    private List<int[]> fusiones;
    private double[] anchos;
    private String[] estiloCol;
    private int maxF, maxC;
    private boolean recortada, dibujos, roto;

    private Ods(Paquete pq) {
        this.pq = pq;
        this.est = Odf.Estilos.leer(pq, null);
        this.texto = new Odt(pq, est, Odt.MODO_HOJA);
    }

    static Html.Documento convertir(Paquete pq) throws Fallo {
        Odf.comprobar(pq);
        Ods o = new Ods(pq);
        List<String> nombres = new ArrayList<>(), cuerpos = new ArrayList<>();
        o.procesar(nombres, cuerpos);
        if (cuerpos.isEmpty()) {
            return new Html.Documento("<p class=nota>El libro no tiene hojas visibles.</p>", CSS,
                    Html.VIEWPORT_MOVIL, "ods");
        }
        return new Html.Documento("", CSS, Html.VIEWPORT_MOVIL, "ods",
                nombres.toArray(new String[0]), cuerpos.toArray(new String[0]));
    }

    private void procesar(List<String> nombres, List<String> cuerpos) throws Fallo {
        XmlPullParser p;
        try {
            p = pq.parser("content.xml");
        } catch (XmlPullParserException e) {
            p = null;
        }
        if (p == null) throw new Fallo("No se puede leer el libro de cálculo. Puede que el archivo esté dañado.");
        long total = 0;
        boolean libro = false;
        try {
            int t;
            while (!roto && (t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Odf.nombre(p)) {
                    case "office:automatic-styles": est.leerBloque(p, Odf.AUTO_CONTENIDO); break;
                    case "office:font-face-decls": case "office:scripts": case "office:forms":
                    case "table:named-expressions": case "table:database-ranges": case "table:calculation-settings":
                    case "table:content-validations": case "table:label-ranges": case "table:data-pilot-tables":
                    case "table:consolidation": case "table:dde-links": case "table:tracked-changes":
                        Lectura.saltar(p);
                        break;
                    case "office:spreadsheet": libro = true; break;
                    case "table:table": {
                        if (!libro) {
                            Lectura.saltar(p);
                            break;
                        }
                        String nombre = Odf.atr(p, "table:name");
                        Odf.Estilo ts = est.estilo("table", Odf.atr(p, "table:style-name"));
                        if ("false".equals(Odf.prop(est.cadena(ts, "table", false), Odf.OTRAS, "table:display"))) {
                            Lectura.saltar(p);
                            break;
                        }
                        StringBuilder h = new StringBuilder();
                        if (total > MAX_HTML) {
                            Lectura.saltar(p);
                            h.append("<p class=nota>El libro es demasiado grande: esta hoja no se muestra.</p>");
                        } else {
                            try {
                                hoja(p, h);
                            } catch (RuntimeException | StackOverflowError e) {
                                h.setLength(0);
                                h.append("<p class=nota>No se pudo leer esta hoja.</p>");
                            }
                        }
                        total += h.length();
                        nombres.add(nombre != null && !nombre.trim().isEmpty() ? nombre : "Hoja " + (nombres.size() + 1));
                        cuerpos.add(h.toString());
                        break;
                    }
                    default: break;
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException e) {
            if (cuerpos.isEmpty()) throw new Fallo("El libro está dañado y no se puede leer.");
        }
        if (cuerpos.isEmpty() && roto) throw new Fallo("El libro está dañado y no se puede leer.");
    }

    // --- Lectura de una hoja ---

    private void hoja(XmlPullParser p, StringBuilder sb) {
        filas = new ArrayList<>();
        fusiones = new ArrayList<>();
        anchos = new double[MAX_COLS + 2];
        Arrays.fill(anchos, Double.NaN);
        estiloCol = new String[MAX_COLS + 2];
        maxF = maxC = 0;
        recortada = dibujos = false;
        texto.hayDibujos = false;
        int fila = 1, col = 1;
        try {
            int d = p.getDepth();
            while (Lectura.dentro(p, d)) {
                if (!Lectura.inicio(p)) continue;
                switch (Odf.nombre(p)) {
                    case "table:table-column": col = columna(p, col); break;
                    case "table:table-row": fila = fila(p, fila); break;
                    case "table:shapes": dibujos = true; Lectura.saltar(p); break;
                    case "table:table-header-rows": case "table:table-rows": case "table:table-row-group":
                    case "table:table-header-columns": case "table:table-columns": case "table:table-column-group":
                        break;
                    default: Lectura.saltar(p); break;
                }
            }
        } catch (XmlPullParserException | IOException e) {
            roto = true;
        }
        if (texto.hayDibujos) dibujos = true;
        pintar(sb);
        if (roto) sb.append(Html.NOTA_DANADA);
        filas = null;
        fusiones = null;
    }

    private static int repeticiones(XmlPullParser p, String atributo) {
        return Math.max(1, Math.min(TOPE, Lectura.entero(Odf.atr(p, atributo), 1)));
    }

    private static int sumar(int a, int b) {
        return (int) Math.min((long) a + b, TOPE);
    }

    private int columna(XmlPullParser p, int col) {
        int rep = repeticiones(p, "table:number-columns-repeated");
        Odf.Estilo ce = est.estilo("table-column", Odf.atr(p, "table:style-name"));
        double w = Odf.px(Odf.prop(est.cadena(ce, "table-column", false), Odf.OTRAS, "style:column-width"));
        String vis = Odf.atr(p, "table:visibility");
        boolean oculta = "collapse".equals(vis) || "filter".equals(vis);
        String def = Odf.atr(p, "table:default-cell-style-name");
        for (int c = col; c <= MAX_COLS && c < (long) col + rep; c++) {
            if (oculta) anchos[c] = 8.5;
            else if (!Double.isNaN(w) && w > 0) anchos[c] = Math.max(4, Math.min(w, 900));
            estiloCol[c] = def;
        }
        return sumar(col, rep);
    }

    private int fila(XmlPullParser p, int fila) throws XmlPullParserException, IOException {
        int rep = repeticiones(p, "table:number-rows-repeated");
        String defFila = Odf.atr(p, "table:default-cell-style-name");
        Fila f = new Fila(fila, rep);
        int c = 1;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            String n = Odf.nombre(p);
            if (n.equals("table:table-cell")) {
                int crep = repeticiones(p, "table:number-columns-repeated");
                try {
                    celda(p, f, c, crep, defFila);
                } catch (RuntimeException e) {
                    // Celda rara: se ignora.
                }
                c = sumar(c, crep);
            } else if (n.equals("table:covered-table-cell")) {
                c = sumar(c, repeticiones(p, "table:number-columns-repeated"));
                Lectura.saltar(p);
            } else {
                Lectura.saltar(p);
            }
        }
        if (!f.tramos.isEmpty()) filas.add(f);
        return sumar(fila, rep);
    }

    private void celda(XmlPullParser p, Fila f, int c, int crep, String defFila)
            throws XmlPullParserException, IOException {
        String tipo = Odf.atr(p, "office:value-type");
        String valor = Odf.atr(p, "office:value");
        String fecha = Odf.atr(p, "office:date-value"), hora = Odf.atr(p, "office:time-value");
        String booleano = Odf.atr(p, "office:boolean-value"), cadena = Odf.atr(p, "office:string-value");
        String moneda = Odf.atr(p, "office:currency");
        String estilo = Odf.atr(p, "table:style-name");
        if (estilo == null) estilo = defFila;
        if (estilo == null && c <= MAX_COLS) estilo = estiloCol[c];
        int cs = Math.max(1, Math.min(TOPE, Lectura.entero(Odf.atr(p, "table:number-columns-spanned"), 1)));
        int rs = Math.max(1, Math.min(TOPE, Lectura.entero(Odf.atr(p, "table:number-rows-spanned"), 1)));

        StringBuilder html = new StringBuilder();
        int parrafos = 0, largo = 0;
        boolean hay = false;
        int d = p.getDepth();
        while (Lectura.dentro(p, d)) {
            if (!Lectura.inicio(p)) continue;
            switch (Odf.nombre(p)) {
                case "text:p": case "text:h": {
                    Odt.Linea l = new Odt.Linea();
                    texto.enLinea(p, l);
                    if (parrafos++ > 0) html.append("<br>");
                    html.append(l.sb);
                    hay |= l.hay;
                    largo += l.plano.length();
                    break;
                }
                case "office:annotation": Lectura.saltar(p); break;
                case "draw:frame": case "draw:g": case "draw:a": case "draw:rect": case "draw:ellipse":
                case "draw:custom-shape": case "draw:line": case "draw:connector": case "draw:polygon":
                case "draw:path": case "draw:polyline": case "draw:circle": case "draw:measure":
                    dibujos = true;
                    Lectura.saltar(p);
                    break;
                default: break; // listas y demás: se recorren hasta sus párrafos
            }
        }

        Celda ce = new Celda();
        boolean numerico = false, centrado = false;
        if (tipo == null) tipo = "";
        switch (tipo) {
            case "float": case "percentage": case "currency": case "date": case "time": numerico = true; break;
            case "boolean": centrado = true; break;
            default: break;
        }
        if (hay) {
            ce.html = html.toString();
        } else {
            String v = valorTexto(tipo, valor, fecha, hora, booleano, cadena, moneda);
            ce.html = Html.esc(v);
            largo = v.length();
        }
        ce.vacia = ce.html.isEmpty();

        Formato fm = formato(estilo);
        String clase = numerico ? "n" : centrado ? "c" : null;
        if (fm.alinear != null) {
            switch (fm.alinear) {
                case "center": clase = "c"; break;
                case "end": case "right": clase = "n"; break;
                case "start": case "left": case "justify": clase = "l"; break;
                default: break;
            }
        }
        if (!numerico && !centrado && (fm.ajustar || largo > 60 || parrafos > 1)) {
            clase = clase == null ? "w" : clase + " w";
        }
        ce.clase = clase;
        ce.estilo = fm.css.isEmpty() ? null : fm.css;

        int fila = f.inicio, ultimaF = (int) Math.min((long) fila + f.cuenta - 1, TOPE);
        int ultimaC = (int) Math.min((long) c + crep - 1, TOPE);
        if (!ce.vacia) {
            if (fila > MAX_FILAS || c > MAX_COLS) {
                recortada = true;
            } else {
                maxF = Math.max(maxF, Math.min(ultimaF, MAX_FILAS));
                maxC = Math.max(maxC, Math.min(ultimaC, MAX_COLS));
                if (ultimaF > MAX_FILAS || ultimaC > MAX_COLS) recortada = true;
            }
        }
        if (fila <= MAX_FILAS && c <= MAX_COLS) {
            if (!ce.vacia || ce.estilo != null) f.tramos.add(new Tramo(c, crep, ce));
            if (cs > 1 || rs > 1) {
                fusiones.add(new int[] {fila, c, (int) Math.min((long) fila + rs - 1, TOPE),
                        (int) Math.min((long) c + cs - 1, TOPE)});
            }
        }
    }

    /** Texto de la celda a partir de su valor, para las que no traen el texto ya formateado. */
    private String valorTexto(String tipo, String valor, String fecha, String hora, String booleano,
                              String cadena, String moneda) {
        switch (tipo) {
            case "float": return general(valor);
            case "percentage": {
                double v = Lectura.real(valor, Double.NaN);
                return Double.isNaN(v) ? "" : general(Double.toString(v * 100)) + " %";
            }
            case "currency": {
                String n = general(valor);
                return n.isEmpty() || moneda == null ? n : n + " " + moneda;
            }
            case "date": return fecha(fecha);
            case "time": return hora(hora);
            case "boolean":
                if (booleano == null) return "";
                return booleano.trim().equals("true") || booleano.trim().equals("1") ? "VERDADERO" : "FALSO";
            case "string": return cadena != null ? cadena : "";
            default: return "";
        }
    }

    /** Número sin ruido de coma flotante (15 cifras significativas como mucho). */
    private String general(String valor) {
        double v = Lectura.real(valor, Double.NaN);
        if (Double.isNaN(v)) return valor == null ? "" : valor.trim();
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        BigDecimal b = new BigDecimal(v).round(new MathContext(15)).stripTrailingZeros();
        double a = Math.abs(v);
        String r = a >= 1e15 || a < 1e-9 ? b.round(new MathContext(6)).toString() : b.toPlainString();
        return r.replace('.', simbolos.getDecimalSeparator());
    }

    private static String fecha(String v) {
        if (v == null) return "";
        Matcher m = FECHA.matcher(v.trim());
        if (!m.matches()) return v.trim();
        StringBuilder r = new StringBuilder();
        r.append(String.format(Locale.ROOT, "%02d/%02d/%04d", Lectura.entero(m.group(3), 0),
                Lectura.entero(m.group(2), 0), Lectura.entero(m.group(1), 0)));
        if (m.group(4) != null) {
            int h = Lectura.entero(m.group(4), 0), mi = Lectura.entero(m.group(5), 0);
            int s = m.group(6) != null ? Lectura.entero(m.group(6), 0) : 0;
            if (h != 0 || mi != 0 || s != 0) {
                r.append(String.format(Locale.ROOT, " %02d:%02d", h, mi));
                if (s != 0) r.append(String.format(Locale.ROOT, ":%02d", s));
            }
        }
        return r.toString();
    }

    private static String hora(String v) {
        if (v == null) return "";
        Matcher m = DURACION.matcher(v.trim());
        if (!m.matches()) return v.trim();
        long h = Lectura.largo(m.group(1), 0) * 24 + Lectura.largo(m.group(2), 0);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, Lectura.largo(m.group(3), 0), Lectura.largo(m.group(4), 0));
    }

    private Formato formato(String nombre) {
        String k = nombre == null ? "" : nombre;
        Formato fm = formatos.get(k);
        if (fm != null) return fm;
        fm = new Formato();
        Odf.Estilo e = est.estilo("table-cell", nombre);
        if (e == null) e = est.estilo("table-cell", "Default");
        List<Odf.Estilo> cad = est.cadena(e, "table-cell", true);
        StringBuilder css = new StringBuilder();
        texto.cssTexto(css, cad, false);
        String fondo = Odf.color(Odf.prop(cad, Odf.OTRAS, "fo:background-color"));
        if (fondo != null) css.append("background:#").append(fondo).append(';');
        fm.css = css.toString();
        String fuente = Odf.prop(cad, Odf.OTRAS, "style:text-align-source");
        String al = Odf.prop(cad, Odf.PARRAFO, "fo:text-align");
        if (al != null && !"value-type".equals(fuente)) fm.alinear = al;
        fm.ajustar = "wrap".equals(Odf.prop(cad, Odf.OTRAS, "fo:wrap-option"));
        formatos.put(k, fm);
        return fm;
    }

    // --- Pintado ---

    private static long clave(int f, int c) { return ((long) f << 16) | c; }

    /** Celda guardada en (f, c), o null. */
    private Celda celdaEn(int f, int c) {
        int lo = 0, hi = filas.size() - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            Fila x = filas.get(m);
            if ((long) x.inicio + x.cuenta - 1 < f) lo = m + 1;
            else if (x.inicio > f) hi = m - 1;
            else {
                for (Tramo t : x.tramos) if (c >= t.col && c < (long) t.col + t.n) return t.ce;
                return null;
            }
        }
        return null;
    }

    private void pintar(StringBuilder sb) {
        // Las fusiones cuyo origen tiene contenido amplían el área visible.
        for (int[] r : fusiones) {
            Celda o = celdaEn(r[0], r[1]);
            if (o != null && !o.vacia) {
                maxF = Math.max(maxF, Math.min(r[2], MAX_FILAS));
                maxC = Math.max(maxC, Math.min(r[3], MAX_COLS));
            }
        }
        if (maxF == 0) {
            sb.append("<p class=nota>Hoja vacía.</p>");
            if (dibujos) sb.append("<p class=nota>Esta hoja tiene gráficos o imágenes que no se muestran.</p>");
            return;
        }

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
            double w = anchos[c];
            if (!Double.isNaN(w)) sb.append("<col style=\"width:").append(Html.px(w)).append("px\">");
            else sb.append("<col>");
        }
        sb.append("</colgroup><thead><tr><th class=esq></th>");
        for (int c = 1; c <= maxC; c++) sb.append("<th>").append(Html.letras(c)).append("</th>");
        sb.append("</tr></thead><tbody>");

        Celda[] fila = new Celda[maxC + 1];
        Fila actual = null;
        int idx = 0;
        int ultimaFila = maxF;
        for (int f = 1; f <= maxF; f++) {
            if (sb.length() > MAX_HTML) {
                ultimaFila = f - 1;
                recortada = true;
                break;
            }
            while (idx < filas.size() && (long) filas.get(idx).inicio + filas.get(idx).cuenta - 1 < f) idx++;
            Fila x = idx < filas.size() && filas.get(idx).inicio <= f ? filas.get(idx) : null;
            if (x != actual) {
                actual = x;
                Arrays.fill(fila, null);
                if (x != null) {
                    for (Tramo t : x.tramos) {
                        for (long c = t.col; c <= maxC && c < (long) t.col + t.n; c++) fila[(int) c] = t.ce;
                    }
                }
            }
            sb.append("<tr><th>").append(f).append("</th>");
            for (int c = 1; c <= maxC; c++) {
                long k = clave(f, c);
                if (!cubiertas.isEmpty() && cubiertas.contains(k)) continue;
                Celda ce = fila[c];
                int[] fu = inicioFusion.isEmpty() ? null : inicioFusion.get(k);
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
        if (recortada) {
            sb.append("<p class=nota>La hoja es muy grande: se muestran solo las primeras ")
                    .append(ultimaFila).append(" filas y ").append(maxC).append(" columnas.</p>");
        }
        if (dibujos) sb.append("<p class=nota>Esta hoja tiene gráficos o imágenes que no se muestran.</p>");
    }
}
