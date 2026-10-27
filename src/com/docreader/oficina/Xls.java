package com.docreader.oficina;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Convierte un libro de Excel 97–2003 (.xls, BIFF8) en tablas HTML, una por hoja, con el mismo
 * aspecto que {@link Xlsx}. Los de Excel 5/95 (BIFF5, flujo "Book") se leen en lo posible.
 * El flujo del libro es una lista de registros (tipo, longitud, datos): primero los globales
 * (textos compartidos, fuentes, formatos, estilos y la lista de hojas) y después un bloque por
 * hoja que empieza en BOF y acaba en EOF. Nada se da por bueno: todas las lecturas se comprueban.
 * Sin dependencias de Android, para poder probarlo en la JVM.
 */
final class Xls {
    private static final int MAX_FILAS = Xlsx.MAX_FILAS, MAX_COLS = Xlsx.MAX_COLS;
    /** Tope de tamaño del HTML de todas las hojas juntas (en caracteres). */
    private static final int MAX_HTML = 30_000_000;
    /** Registros que se leen como mucho en cada bloque (archivos dañados o desmesurados). */
    private static final int MAX_REGISTROS = 4_000_000;
    /** Celdas tapadas por fusiones, como mucho, en cada hoja. */
    private static final int MAX_CUBIERTAS = MAX_FILAS * MAX_COLS;

    private static final String CSS =
            "body.xls{padding:8px 10px 30px;font-size:14px}"
            + Html.CSS_HOJA;

    private static final String PROTEGIDO = "Este libro de Excel está protegido con contraseña y no se puede abrir.";
    private static final String DANADO = "El libro está dañado y no se puede leer.";

    // Tipos de registro.
    private static final int EOF = 0x000A, CONTINUE = 0x003C, CODEPAGE = 0x0042,
            DATEMODE = 0x0022, BOUNDSHEET = 0x0085, SST = 0x00FC, FONT = 0x0031, FORMAT = 0x041E, XF = 0x00E0,
            PALETTE = 0x0092, COLINFO = 0x007D, LABELSST = 0x00FD, LABEL = 0x0204, RSTRING = 0x00D6,
            NUMBER = 0x0203, RK = 0x027E, MULRK = 0x00BD, FORMULA = 0x0006, STRING = 0x0207, BOOLERR = 0x0205,
            BLANK = 0x0201, MULBLANK = 0x00BE, MERGEDCELLS = 0x00E5, OBJ = 0x005D;

    /** Paleta por omisión de BIFF8: índices 0–7 fijos y 8–63 modificables con PALETTE. */
    private static final String PALETA =
            "000000FFFFFFFF000000FF000000FFFFFF00FF00FF00FFFF000000FFFFFFFF000000FF000000FFFFFF00FF00FF00FFFF"
            + "800000008000000080808000800080008080C0C0C08080809999FF993366FFFFCCCCFFFF660066FF80800066CCCCCCFF"
            + "000080FF00FFFFFF0000FFFF8000808000000080800000FF00CCFFCCFFFFCCFFCCFFFF9999CCFFFF99CCCC99FFFFCC99"
            + "3366FF33CCCC99CC00FFCC00FF9900FF6600666699969696003366339966003300333300993300993366333399333333";

    private static final class Fuente {
        boolean b, i, u, s;
        int color;
    }

    private static final class Xf {
        int fuente, formato, alinear, relleno = -1;
        boolean ajustar;
    }

    private static final class Celda {
        String html, clase, estilo;
        boolean vacia;
    }

    private static final class Hoja {
        String nombre;
        int pos = -1, tipo;
        boolean oculta;
    }

    private final byte[] d;
    private boolean biff8;
    /** Juego de caracteres de las cadenas de 8 bits de BIFF5 (registro CODEPAGE). */
    private Charset juego = juego(1252);
    private final List<Fuente> fuentes = new ArrayList<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final Map<Integer, String> formatos = new HashMap<>();
    private final String[] paleta = new String[64];
    private final FormatoNumero formato = new FormatoNumero();
    private final List<Hoja> hojas = new ArrayList<>();
    private List<String> sstTexto = new ArrayList<>();
    private List<int[]> sstCorridas = new ArrayList<>();
    private String[] sst = new String[0];
    private String[] cssXf = new String[0];
    /** Corridas de formato (pares carácter, fuente) de la última cadena leída, o null. */
    private int[] corridas;

    // Estado de la hoja que se está leyendo.
    private Map<Long, Celda> celdas;
    private int maxF, maxC;
    private boolean recortada;

    private Xls(byte[] d, boolean biff8) {
        this.d = d;
        this.biff8 = biff8;
        for (int i = 0; i < 64; i++) paleta[i] = PALETA.substring(6 * i, 6 * i + 6);
    }

    static Html.Documento convertir(Cfb cfb) throws Fallo {
        return convertir(cfb, null);
    }

    /**
     * Igual, con la contraseña que haya escrito el usuario (o null). Si el libro está cifrado y
     * no hay contraseña válida, lanza {@link FalloClave}.
     */
    static Html.Documento convertir(Cfb cfb, String clave) throws Fallo {
        // Un .xlsx cifrado con contraseña también viaja dentro de un contenedor CFB.
        if (cfb.existe("EncryptedPackage")) throw new Fallo(PROTEGIDO);
        byte[] d = cfb.flujo("Workbook");
        boolean b8 = true;
        if (d == null) {
            d = cfb.flujo("Book");
            b8 = false;
        }
        if (d == null) throw new Fallo("No se encontró el libro de cálculo. Puede que el archivo esté dañado.");
        if (d.length >= 6 && esBof(Cfb.u16(d, 0)) && Cfb.u16(d, 2) >= 2) b8 = Cfb.u16(d, 4) >= 0x0600;
        d = XlsCifrado.descifrar(d, b8, clave);
        Xls x = new Xls(d, b8);
        StringBuilder sb = new StringBuilder();
        List<String> nombres = new ArrayList<>(), cuerpos = new ArrayList<>();
        x.procesar(sb, nombres, cuerpos);
        if (cuerpos.isEmpty()) return new Html.Documento(sb.toString(), CSS, Html.VIEWPORT_MOVIL, "xls");
        return new Html.Documento("", CSS, Html.VIEWPORT_MOVIL, "xls",
                nombres.toArray(new String[0]), cuerpos.toArray(new String[0]));
    }

    private static boolean esBof(int t) { return t == 0x0809 || t == 0x0409 || t == 0x0209 || t == 0x0009; }

    /** Lee los globales y convierte cada hoja visible: nombre en "nombres", HTML en "cuerpos". */
    private void procesar(StringBuilder sb, List<String> nombres, List<String> cuerpos) throws Fallo {
        Registros r = new Registros(d, 0);
        if (!r.siguiente() || !esBof(r.tipo)) throw new Fallo(DANADO);
        if (r.tipo != 0x0809) throw new Fallo("Este libro es de una versión muy antigua de Excel y no se puede abrir.");
        if (r.largo >= 2) biff8 = r.u16(0) >= 0x0600;
        if (r.largo >= 4 && r.u16(2) == 0x0010) { // flujo con una sola hoja, sin globales
            terminarEstilos();
            Hoja h = new Hoja();
            h.pos = 0;
            hojas.add(h);
            convertirHojas(sb, nombres, cuerpos, true);
            return;
        }

        boolean completo = false;
        while (r.siguiente()) {
            if (r.tipo == EOF) {
                completo = true;
                break;
            }
            try {
                global(r);
            } catch (RuntimeException e) {
                // Registro dañado: se ignora y se sigue con el siguiente.
            }
        }
        terminarEstilos();

        // Bloques BOF…EOF de primer nivel que siguen a los globales, por si algún
        // desplazamiento de BOUNDSHEET no apunta a un BOF.
        List<Integer> bloques = new ArrayList<>();
        if (completo) {
            Registros s = new Registros(d, r.pos);
            int prof = 0;
            while (s.siguiente()) {
                if (esBof(s.tipo)) {
                    if (prof == 0) bloques.add(s.ini - 4);
                    prof++;
                } else if (s.tipo == EOF) {
                    prof = Math.max(0, prof - 1);
                }
            }
        }
        if (hojas.isEmpty()) {
            for (int i = 0; i < bloques.size(); i++) hojas.add(new Hoja());
        }
        for (int i = 0; i < hojas.size(); i++) {
            Hoja h = hojas.get(i);
            int p = h.pos;
            if (p < 0 || p > d.length - 4 || !esBof(Cfb.u16(d, p))) p = i < bloques.size() ? bloques.get(i) : -1;
            h.pos = p;
        }
        convertirHojas(sb, nombres, cuerpos, completo);
    }

    private void convertirHojas(StringBuilder sb, List<String> nombres, List<String> cuerpos, boolean completo)
            throws Fallo {
        for (int i = 0; i < hojas.size(); i++) {
            Hoja h = hojas.get(i);
            if (h.nombre == null || h.nombre.isEmpty()) h.nombre = "Hoja " + (i + 1);
        }
        long total = 0;
        for (Hoja h : hojas) {
            if (h.oculta || h.tipo == 6) continue; // ocultas y módulos de Visual Basic
            sb.setLength(0);
            if (total > MAX_HTML) {
                sb.append("<p class=nota>El libro es demasiado grande: esta hoja no se muestra.</p>");
            } else if (h.tipo == 2) {
                sb.append("<p class=nota>Esta hoja es un gráfico y no se puede mostrar.</p>");
            } else if (h.pos < 0) {
                sb.append("<p class=nota>No se encontró esta hoja en el archivo.</p>");
            } else {
                try {
                    hoja(h.pos, sb);
                } catch (RuntimeException e) {
                    sb.setLength(0);
                    sb.append("<p class=nota>No se pudo leer esta hoja.</p>");
                } finally {
                    celdas = null;
                }
            }
            total += sb.length();
            nombres.add(h.nombre);
            cuerpos.add(sb.toString());
        }
        if (cuerpos.isEmpty()) {
            sb.setLength(0);
            if (!completo && hojas.isEmpty()) throw new Fallo(DANADO);
            sb.append("<p class=nota>El libro no tiene hojas visibles.</p>");
        }
    }

    // --- Globales ---

    private void global(Registros r) {
        switch (r.tipo) {
            case CODEPAGE:
                juego = juego(r.u16(0));
                break;
            case DATEMODE:
                formato.fecha1904 = r.u16(0) == 1;
                break;
            case BOUNDSHEET: {
                Hoja h = new Hoja();
                h.pos = r.i32(0);
                h.oculta = (r.u8(4) & 3) != 0;
                h.tipo = r.u8(5);
                hojas.add(h);
                h.nombre = cadena(r.trozo(6), false);
                break;
            }
            case SST: {
                if (!biff8) break;
                Trozo t = r.conContinuaciones();
                t.saltar(8);
                // El total declarado no siempre es fiable: se lee hasta acabar los datos.
                while (t.p < t.fin) {
                    try {
                        sstTexto.add(cadena(t, true));
                        sstCorridas.add(corridas);
                    } catch (RuntimeException e) {
                        break;
                    }
                }
                break;
            }
            case FONT: {
                Fuente f = new Fuente();
                int g = r.u16(2);
                f.i = (g & 0x02) != 0;
                f.s = (g & 0x08) != 0;
                f.color = r.u16(4);
                f.b = r.u16(6) >= 600;
                f.u = r.u8(10) != 0;
                fuentes.add(f);
                break;
            }
            case FORMAT: {
                int id = r.u16(0);
                formatos.put(id, cadena(r.trozo(2), biff8));
                break;
            }
            case XF: xfs.add(xf(r)); break;
            case PALETTE: {
                int n = r.u16(0);
                for (int i = 0; i < n && 8 + i < 64; i++) {
                    int o = 2 + 4 * i;
                    paleta[8 + i] = String.format(Locale.ROOT, "%02X%02X%02X", r.u8(o), r.u8(o + 1), r.u8(o + 2));
                }
                break;
            }
            default: break;
        }
    }

    private Xf xf(Registros r) {
        Xf x = new Xf();
        x.fuente = r.u16(0);
        x.formato = r.u16(2);
        int al = r.u8(6);
        x.alinear = al & 7;
        x.ajustar = (al & 8) != 0;
        int patron, frente;
        if (biff8) {
            patron = (r.i32(14) >>> 26) & 0x3F;
            frente = r.u16(18) & 0x7F;
        } else {
            int v = r.i32(8);
            patron = (v >>> 16) & 0x3F;
            frente = v & 0x7F;
        }
        if (patron == 1) x.relleno = frente; // solo los rellenos sólidos, como en Xlsx
        return x;
    }

    /**
     * Se usan siempre los valores del propio XF de la celda, no los de su estilo padre: Excel los
     * copia en la celda, y muchos programas escriben mal los bits de "atributo heredado"
     * (negritas y rellenos que se perderían si se hiciera caso a esos bits).
     */
    private void terminarEstilos() {
        cssXf = new String[xfs.size()];
        sst = new String[sstTexto.size()];
        for (int i = 0; i < sst.length; i++) sst[i] = rica(sstTexto.get(i), sstCorridas.get(i));
        sstTexto = null;
        sstCorridas = null;
    }

    /** Juego de caracteres de un código de página de Windows o Mac (CODEPAGE). */
    private static Charset juego(int cp) {
        String[] nombres;
        switch (cp) {
            case 367: return StandardCharsets.US_ASCII;
            case 1200: return StandardCharsets.UTF_16LE;
            case 10000: case 32768: nombres = new String[] {"x-MacRoman", "MacRoman", "macintosh"}; break;
            case 32769: nombres = new String[] {"windows-1252"}; break;
            case 932: nombres = new String[] {"windows-31j", "Shift_JIS"}; break;
            case 936: nombres = new String[] {"GBK", "GB2312"}; break;
            case 949: nombres = new String[] {"x-windows-949", "EUC-KR"}; break;
            case 950: nombres = new String[] {"x-windows-950", "Big5"}; break;
            default: nombres = new String[] {"windows-" + cp, "Cp" + cp, "IBM" + cp}; break;
        }
        for (String n : nombres) {
            try {
                return Charset.forName(n);
            } catch (RuntimeException ignorado) { }
        }
        try {
            return Charset.forName("windows-1252");
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * Cadena de Excel: longitud de 1 o 2 bytes y, en BIFF8, un byte de opciones (UTF-16 o
     * comprimida, corridas de formato y datos fonéticos que se saltan). Deja las corridas en
     * {@link #corridas}. En BIFF5 son bytes en el código de página del libro.
     */
    private String cadena(Trozo t, boolean largo2) {
        int cch = largo2 ? t.u16() : t.u8();
        corridas = null;
        if (!biff8) {
            int n = Math.min(cch, t.fin - t.p);
            String s = new String(t.b, t.p, n, juego);
            t.p += n;
            return s;
        }
        int op = t.u8();
        int nCorr = (op & 0x08) != 0 ? t.u16() : 0;
        long ext = (op & 0x04) != 0 ? t.i32() & 0xFFFFFFFFL : 0;
        String s = t.caracteres(cch, (op & 0x01) != 0);
        if (nCorr > 0) {
            int n = Math.min(nCorr, (t.fin - t.p) / 4);
            corridas = new int[2 * n];
            for (int i = 0; i < 2 * n; i++) corridas[i] = t.u16();
            if (n < nCorr) t.p = t.fin;
        }
        t.p = (int) Math.min(t.fin, t.p + ext);
        return s;
    }

    /** Texto con corridas de formato como HTML (negrita, cursiva, color… de cada tramo). */
    private String rica(String s, int[] c) {
        if (c == null || c.length == 0) return Html.esc(s);
        StringBuilder sb = new StringBuilder(s.length() + 32);
        int antes = 0;
        String css = "";
        for (int i = 0; i + 1 < c.length; i += 2) {
            int hasta = Math.max(antes, Math.min(c[i], s.length()));
            tramo(sb, s, antes, hasta, css);
            antes = hasta;
            Fuente f = fuente(c[i + 1]);
            css = f != null ? cssFuente(f) : "";
        }
        tramo(sb, s, antes, s.length(), css);
        return sb.toString();
    }

    private static void tramo(StringBuilder sb, String s, int a, int b, String css) {
        if (a >= b) return;
        if (css.isEmpty()) {
            Html.esc(sb, s.substring(a, b));
        } else {
            sb.append("<span style=\"").append(css).append("\">");
            Html.esc(sb, s.substring(a, b));
            sb.append("</span>");
        }
    }

    /** Fuente por su índice; el índice 4 no existe (Excel se lo salta). */
    private Fuente fuente(int i) {
        if (i == 4) return null;
        if (i > 4) i--;
        return i >= 0 && i < fuentes.size() ? fuentes.get(i) : null;
    }

    private String color(int i) { return i >= 0 && i < 64 ? paleta[i] : null; }

    private String cssFuente(Fuente f) {
        StringBuilder css = new StringBuilder();
        if (f.b) css.append("font-weight:bold;");
        if (f.i) css.append("font-style:italic;");
        if (f.u || f.s) {
            css.append("text-decoration:").append(f.u ? "underline " : "").append(f.s ? "line-through" : "")
                    .append(';');
        }
        String c = color(f.color);
        if (c != null && !c.equals("000000")) css.append("color:#").append(c).append(';');
        return css.toString();
    }

    private Xf xf(int s) { return s >= 0 && s < xfs.size() ? xfs.get(s) : null; }

    private String cssDe(int s) {
        Xf x = xf(s);
        if (x == null) return "";
        if (cssXf[s] != null) return cssXf[s];
        StringBuilder css = new StringBuilder();
        Fuente f = fuente(x.fuente);
        if (f != null) css.append(cssFuente(f));
        String fondo = color(x.relleno);
        if (fondo != null) css.append("background:#").append(fondo).append(';');
        cssXf[s] = css.toString();
        return cssXf[s];
    }

    // --- Hojas ---

    private static long clave(int f, int c) { return ((long) f << 16) | c; }

    private void hoja(int pos, StringBuilder sb) {
        Registros r = new Registros(d, pos);
        if (!r.siguiente() || !esBof(r.tipo)) {
            sb.append("<p class=nota>No se encontró esta hoja en el archivo.</p>");
            return;
        }
        if (r.largo >= 4 && r.u16(2) == 0x0020) {
            sb.append("<p class=nota>Esta hoja es un gráfico y no se puede mostrar.</p>");
            return;
        }
        celdas = new HashMap<>();
        maxF = 0;
        maxC = 0;
        recortada = false;
        List<int[]> fusiones = new ArrayList<>();
        Map<Integer, Double> anchos = new HashMap<>();
        boolean dibujos = false, danada = false, terminada = false;
        int prof = 1, pendF = -1, pendC = 0, pendXf = 0;
        while (r.siguiente()) {
            int t = r.tipo;
            if (esBof(t)) { // gráfico incrustado: un bloque BOF…EOF dentro de la hoja
                prof++;
                dibujos = true;
                continue;
            }
            if (t == EOF) {
                if (--prof <= 0) {
                    terminada = true;
                    break;
                }
                continue;
            }
            if (prof > 1) continue;
            try {
                switch (t) {
                    case COLINFO: {
                        int a = r.u16(0) + 1, b = Math.min(MAX_COLS, r.u16(2) + 1);
                        double w = r.u16(4) / 256.0;
                        if ((r.u16(8) & 1) != 0) w = 0.5;
                        if (w > 0) for (int c = a; c <= b; c++) anchos.put(c, Math.min(w, 120) * 7 + 5);
                        break;
                    }
                    case LABELSST: {
                        int i = r.i32(6);
                        String h = i >= 0 && i < sst.length ? sst[i] : "";
                        poner(r.u16(0), r.u16(2), celda(h, null, true, r.u16(4)));
                        pendF = -1;
                        break;
                    }
                    case LABEL: case RSTRING:
                        poner(r.u16(0), r.u16(2), celda(Html.esc(cadena(r.trozo(6), true)), null, true, r.u16(4)));
                        pendF = -1;
                        break;
                    case NUMBER:
                        poner(r.u16(0), r.u16(2), numero(r.f64(6), r.u16(4)));
                        pendF = -1;
                        break;
                    case RK:
                        poner(r.u16(0), r.u16(2), numero(rk(r.i32(6)), r.u16(4)));
                        pendF = -1;
                        break;
                    case MULRK: {
                        int f = r.u16(0), c = r.u16(2), n = (r.largo - 6) / 6;
                        for (int i = 0; i < n; i++) {
                            poner(f, c + i, numero(rk(r.i32(6 + 6 * i)), r.u16(4 + 6 * i)));
                        }
                        pendF = -1;
                        break;
                    }
                    case BLANK:
                        poner(r.u16(0), r.u16(2), celda("", null, false, r.u16(4)));
                        pendF = -1;
                        break;
                    case MULBLANK: {
                        int f = r.u16(0), c = r.u16(2), n = (r.largo - 6) / 2;
                        for (int i = 0; i < n && c + i < MAX_COLS; i++) {
                            int s = r.u16(4 + 2 * i);
                            if (!cssDe(s).isEmpty()) poner(f, c + i, celda("", null, false, s));
                        }
                        pendF = -1;
                        break;
                    }
                    case BOOLERR: {
                        int v = r.u8(6);
                        String h = r.u8(7) != 0 ? error(v) : v != 0 ? "VERDADERO" : "FALSO";
                        poner(r.u16(0), r.u16(2), celda(h, "c", false, r.u16(4)));
                        pendF = -1;
                        break;
                    }
                    case FORMULA: case 0x0206: case 0x0406: { // los dos últimos, de BIFF3/4, aparecen a veces
                        int f = r.u16(0), c = r.u16(2), s = r.u16(4);
                        pendF = -1;
                        if (r.u16(12) != 0xFFFF) {
                            poner(f, c, numero(r.f64(6), s));
                            break;
                        }
                        int v = r.u8(8);
                        switch (r.u8(6)) {
                            case 0: // el texto viene en el registro STRING siguiente
                                pendF = f;
                                pendC = c;
                                pendXf = s;
                                break;
                            case 1: poner(f, c, celda(v != 0 ? "VERDADERO" : "FALSO", "c", false, s)); break;
                            case 2: poner(f, c, celda(error(v), "c", false, s)); break;
                            default: poner(f, c, celda("", null, true, s)); break;
                        }
                        break;
                    }
                    case STRING: {
                        if (pendF < 0) break;
                        int f = pendF;
                        pendF = -1;
                        Trozo tz = biff8 ? r.conContinuaciones() : r.trozo(0);
                        poner(f, pendC, celda(Html.esc(cadena(tz, true)), null, true, pendXf));
                        break;
                    }
                    case MERGEDCELLS: {
                        int n = Math.min(r.u16(0), (r.largo - 2) / 8);
                        for (int i = 0; i < n; i++) {
                            int o = 2 + 8 * i;
                            int f1 = r.u16(o) + 1, f2 = r.u16(o + 2) + 1, c1 = r.u16(o + 4) + 1, c2 = r.u16(o + 6) + 1;
                            fusiones.add(new int[] {Math.min(f1, f2), Math.min(c1, c2), Math.max(f1, f2), Math.max(c1, c2)});
                        }
                        break;
                    }
                    case OBJ: {
                        // ftCmo: tipo de objeto 5 = gráfico, 8 = imagen.
                        int ot = r.u16(0) == 0x15 ? r.u16(4) : -1;
                        if (ot == 5 || ot == 8) dibujos = true;
                        break;
                    }
                    default: break;
                }
            } catch (RuntimeException e) {
                danada = true;
            }
        }
        if (r.excedido) recortada = true;
        else if (!terminada) danada = true;
        if (danada) sb.append(Html.NOTA_DANADA);
        pintar(sb, fusiones, anchos, dibujos);
    }

    /** Guarda una celda (fila y columna desde 0), con los mismos topes que Xlsx. */
    private void poner(int fila, int col, Celda ce) {
        int f = fila + 1, c = col + 1;
        if (f > MAX_FILAS || c > MAX_COLS) {
            if (!ce.vacia) recortada = true;
            return;
        }
        if (ce.vacia && ce.estilo == null) return;
        celdas.put(clave(f, c), ce);
        if (!ce.vacia) {
            maxF = Math.max(maxF, f);
            maxC = Math.max(maxC, c);
        }
    }

    /** Tabla HTML de la hoja: idéntica a la de Xlsx (letras de columna, números de fila, fusiones). */
    private void pintar(StringBuilder sb, List<int[]> fusiones, Map<Integer, Double> anchos, boolean dibujos) {
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

        Map<Long, int[]> inicioFusion = new HashMap<>();
        Set<Long> cubiertas = new HashSet<>();
        for (int[] r : fusiones) {
            if (r[0] > maxF || r[1] > maxC) continue;
            int f2 = Math.min(r[2], maxF), c2 = Math.min(r[3], maxC);
            if (f2 == r[0] && c2 == r[1]) continue;
            long k = clave(r[0], r[1]);
            if (cubiertas.contains(k) || inicioFusion.containsKey(k)) continue;
            if (cubiertas.size() + (long) (f2 - r[0] + 1) * (c2 - r[1] + 1) > MAX_CUBIERTAS) continue;
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
        for (int f = 1; f <= maxF; f++) {
            if (sb.length() > MAX_HTML) {
                ultimaFila = f - 1;
                recortada = true;
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
        if (recortada) {
            sb.append("<p class=nota>La hoja es muy grande: se muestran solo las primeras ")
                    .append(ultimaFila).append(" filas y ").append(maxC).append(" columnas.</p>");
        }
        if (dibujos) sb.append("<p class=nota>Esta hoja tiene gráficos o imágenes que no se muestran.</p>");
    }

    private Celda numero(double v, int s) {
        Xf x = xf(s);
        int id = x != null ? x.formato : 0;
        return celda(Html.esc(formato.numero(v, id, formatos.get(id))), "n", false, s);
    }

    /** Celda con su alineación, ajuste de línea y estilo (fuente y relleno) del XF "s". */
    private Celda celda(String html, String clase, boolean texto, int s) {
        Celda ce = new Celda();
        Xf x = xf(s);
        ce.html = html;
        ce.vacia = html.isEmpty();
        if (x != null) {
            switch (x.alinear) {
                case 1: clase = "l"; break;
                case 2: case 6: clase = "c"; break; // centrado y centrado en la selección
                case 3: clase = "n"; break;
                default: break;
            }
        }
        if (texto && ((x != null && x.ajustar) || largoVisible(html) > 60 || html.indexOf('\n') >= 0)) {
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

    /** Número RK: entero de 30 bits o los 30 bits altos de un double, quizá multiplicado por 100. */
    private static double rk(int v) {
        double r = (v & 2) != 0 ? (double) (v >> 2) : Double.longBitsToDouble((long) (v & 0xFFFFFFFC) << 32);
        return (v & 1) != 0 ? r / 100 : r;
    }

    private static String error(int c) {
        switch (c) {
            case 0x00: return "#NULL!";
            case 0x07: return "#DIV/0!";
            case 0x0F: return "#VALUE!";
            case 0x17: return "#REF!";
            case 0x1D: return "#NAME?";
            case 0x24: return "#NUM!";
            case 0x2A: return "#N/A";
            default: return "#ERROR!";
        }
    }

    // --- Lectura de registros ---

    /** Recorre los registros de un flujo. Un registro que no cabe entero se da por truncado. */
    private static final class Registros {
        final byte[] d;
        int pos, tipo, ini, largo, cuenta;
        boolean excedido;

        Registros(byte[] d, int pos) {
            this.d = d;
            this.pos = pos;
        }

        boolean siguiente() {
            if (pos < 0 || pos > d.length - 4) return false;
            if (++cuenta > MAX_REGISTROS) {
                excedido = true;
                return false;
            }
            tipo = Cfb.u16(d, pos);
            largo = Cfb.u16(d, pos + 2);
            ini = pos + 4;
            if (largo > d.length - ini) return false;
            pos = ini + largo;
            return true;
        }

        private int at(int off, int n) {
            if (off < 0 || off + n > largo) throw new IndexOutOfBoundsException();
            return ini + off;
        }

        int u8(int off) { return d[at(off, 1)] & 0xFF; }

        int u16(int off) { return Cfb.u16(d, at(off, 2)); }

        int i32(int off) { return Cfb.i32(d, at(off, 4)); }

        double f64(int off) {
            int o = at(off, 8);
            return Double.longBitsToDouble((Cfb.i32(d, o) & 0xFFFFFFFFL) | ((long) Cfb.i32(d, o + 4) << 32));
        }

        Trozo trozo(int desde) { return new Trozo(d, at(desde, 0), ini + largo, new int[0]); }

        /** Datos del registro actual seguidos de los de sus CONTINUE, que se consumen. */
        Trozo conContinuaciones() {
            int total = largo, q = pos, n = 0;
            while (q <= d.length - 4 && Cfb.u16(d, q) == CONTINUE) {
                int l = Cfb.u16(d, q + 2);
                if (l > d.length - q - 4) break;
                total += l;
                q += 4 + l;
                n++;
            }
            byte[] b = new byte[total];
            int[] fr = new int[n];
            System.arraycopy(d, ini, b, 0, largo);
            int o = largo;
            q = pos;
            for (int i = 0; i < n; i++) {
                int l = Cfb.u16(d, q + 2);
                fr[i] = o;
                System.arraycopy(d, q + 4, b, o, l);
                o += l;
                q += 4 + l;
            }
            pos = q;
            cuenta += n;
            return new Trozo(b, 0, total, fr);
        }
    }

    /** Datos de uno o varios registros seguidos, con las fronteras entre ellos (inicio de cada CONTINUE). */
    private static final class Trozo {
        final byte[] b;
        final int fin;
        final int[] fr;
        int p, k;

        Trozo(byte[] b, int ini, int fin, int[] fr) {
            this.b = b;
            this.p = ini;
            this.fin = fin;
            this.fr = fr;
        }

        private void hay(int n) {
            if (n < 0 || n > fin - p) throw new IndexOutOfBoundsException();
        }

        int u8() {
            hay(1);
            return b[p++] & 0xFF;
        }

        int u16() {
            hay(2);
            int v = Cfb.u16(b, p);
            p += 2;
            return v;
        }

        int i32() {
            hay(4);
            int v = Cfb.i32(b, p);
            p += 4;
            return v;
        }

        void saltar(int n) {
            hay(n);
            p += n;
        }

        /**
         * "cch" caracteres de una cadena BIFF8 (UTF-16LE, o comprimida en 8 bits = ISO-8859-1).
         * Si la cadena sigue en un CONTINUE, este empieza con un byte que vuelve a indicar la
         * compresión del resto. Si los datos se acaban antes, devuelve lo leído.
         */
        String caracteres(int cch, boolean alto) {
            StringBuilder sb = new StringBuilder(Math.min(cch, Math.max(0, fin - p)));
            while (cch > 0 && p < fin) {
                while (k < fr.length && fr[k] < p) k++;
                if (k < fr.length && fr[k] == p) {
                    alto = (b[p++] & 0x01) != 0;
                    continue;
                }
                int lim = k < fr.length ? fr[k] : fin;
                int n = Math.min(cch, (lim - p) / (alto ? 2 : 1));
                if (n == 0) { // un byte suelto antes de la frontera: datos dañados
                    p = lim;
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    if (alto) {
                        sb.append((char) ((b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8));
                        p += 2;
                    } else {
                        sb.append((char) (b[p++] & 0xFF));
                    }
                }
                cch -= n;
            }
            return sb.toString();
        }
    }
}
