package com.docreader.oficina;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Convierte un documento de Word 97–2003 (.doc, [MS-DOC]) en HTML: texto por piezas, formato de
 * caracteres y párrafos (FKP), estilos, listas, tablas, campos, notas e imágenes.
 * Sin dependencias de Android, para poder probarlo en la JVM.
 */
final class Doc {
    private static final String CSS =
            "body.doc{max-width:48em;margin:0 auto;padding:16px 18px 40px}"
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
            + "sup.ref{line-height:0}sup.ref a{text-decoration:none}"
            + ".notas{border-top:1px solid #bdbdbd;margin-top:2em;padding-top:.6em;font-size:.92em}"
            + ".nt{display:flex;gap:.5em;margin:0 0 .4em}.nt>a{flex:none;text-decoration:none}"
            + ".nt>div{flex:1;min-width:0}.nt p{margin:0 0 .3em}";

    private static final String SALTO = "<span class=salto></span>";
    private static final String DANADO = "El documento está dañado y no se puede leer.";
    /** Límite de caracteres que se leen (texto principal, notas y encabezados). */
    private static final int MAX_CARACTERES = 8_000_000;
    private static final int MAX_PROFUNDIDAD_TABLAS = 8, MAX_CAMPOS = 64, MAX_INSTRUCCION = 4096;

    /** Caracteres 0x80–0x9F de Windows-1252 (los textos «comprimidos» de 8 bits). */
    private static final char[] CP1252 = {
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ'};

    /** Colores «ico» de Word 97 (0 = automático). */
    private static final int[] ICO = {-1, 0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000,
        0xFFFF00, 0xFFFFFF, 0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0};

    // --- Propiedades ---

    /** Propiedades de carácter (CHP) que se usan. */
    private static final class Chp implements Cloneable {
        boolean b, i, s, mayus, versalitas, oculto, borrado, espec, ole, datos;
        int subrayado, hps = 20, color = -1, resaltado = -1, iss, pic = -1, simbolo = -1;

        Chp copia() {
            try {
                return (Chp) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Propiedades de párrafo (PAP) que se usan. */
    private static final class Pap implements Cloneable {
        int istd, jc, itap, ilfo, ilvl, esquema = 9, sangria, primeraLinea, fondo = -1;
        boolean enTabla, finFila, celdaInterior, finFilaInterior, saltoAntes;
        /** Marcas de cada celda (TC80.tcgrf) y sus bordes en twips, solo en las marcas de fin de fila. */
        int[] celdas, bordes, fondos;

        Pap copia() {
            try {
                Pap p = (Pap) clone();
                if (celdas != null) p.celdas = celdas.clone();
                if (bordes != null) p.bordes = bordes.clone();
                if (fondos != null) p.fondos = fondos.clone();
                return p;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Estilo de la hoja de estilos (STSH). */
    private static final class Estilo {
        String nombre = "";
        int sti, stk, base = 0xFFF;
        byte[] papx, chpx;
        Pap pap;
        Chp chp;
    }

    /** Definición de lista (LSTF + LVL). */
    private static final class Lista {
        final int[] inicio = new int[9], nfc = new int[9];
        final String[] texto = new String[9];
        int niveles;
    }

    /** Tramo con el mismo formato: posiciones de archivo (FKP) o de texto (CP). */
    private static final class Tramo {
        final int ini, fin, istd, prm;
        final byte[] grp;

        Tramo(int ini, int fin, int istd, byte[] grp, int prm) {
            this.ini = ini;
            this.fin = fin;
            this.istd = istd;
            this.grp = grp;
            this.prm = prm;
        }
    }

    /** Párrafo: contenido [ini, fin) y marca final en "fin" (o -1 si el texto acaba sin marca). */
    private static final class Parrafo {
        int ini, fin, marca;
        Pap pap;
    }

    /** Campo abierto (0x13 … 0x14 … 0x15). */
    private static final class Campo {
        boolean resultado;
        final StringBuilder instr = new StringBuilder();
        String href;
    }

    /** Celda de tabla ya convertida. */
    private static final class Celda {
        String html;
        int col, columnas = 1, filas = 1, fondo = -1;
        boolean oculta;
    }

    private final byte[] wd, tabla;
    private final DocImagen imagenes;
    private boolean danado;
    private int[] fibFcLcb;
    private int ccpText;

    private char[] texto = new char[0];
    private int[] pCp = {0}, pFc = new int[0], pPrm = new int[0];
    private boolean[] pComp = new boolean[0];
    private final List<byte[]> prc = new ArrayList<>();
    private final List<Tramo> chpx = new ArrayList<>(), papx = new ArrayList<>(), tramos = new ArrayList<>();
    private int[] papxIni = new int[0], tramosIni = new int[0];

    private Estilo[] estilos = new Estilo[0];
    private int hpsBase = 20;
    /** Profundidad de estilos de carácter aplicándose (contra ciclos entre estilos). */
    private int anidados;
    private final Map<Integer, Lista> listas = new HashMap<>();
    private int[] lfoLsid = new int[0];
    private final Map<Integer, int[]> contadores = new HashMap<>();

    private final Map<Integer, int[]> refsNotas = new HashMap<>();
    private int[] txtPie = new int[0], txtFinal = new int[0];
    private int basePie, baseFinal, numNotas, numPie, numFinal;
    private final StringBuilder notas = new StringBuilder(), notasFinales = new StringBuilder();

    private final List<Html.Titulo> indice = new ArrayList<>();
    private int numTitulos;
    private List<Campo> campos = new ArrayList<>();
    /** Campo HYPERLINK cuyo &lt;a&gt; está abierto en el párrafo actual. */
    private Campo enlaceAbierto;

    private Doc(byte[] wd, byte[] tabla, byte[] datos) {
        this.wd = wd;
        this.tabla = tabla;
        this.imagenes = new DocImagen(wd, datos);
    }

    static Html.Documento convertir(Cfb cfb) throws Fallo {
        return convertir(cfb, null);
    }

    /** Igual, con la contraseña para los documentos cifrados (null: aún no se ha pedido). */
    static Html.Documento convertir(Cfb cfb, String clave) throws Fallo {
        byte[] wd = cfb.flujo("WordDocument");
        if (wd == null) throw new Fallo("No se encontró el texto del documento. Puede que el archivo esté dañado.");
        if (wd.length < 0x22) throw new Fallo(DANADO);
        int ident = Cfb.u16(wd, 0), nFib = Cfb.u16(wd, 2), banderas = Cfb.u16(wd, 0x0A);
        boolean identValido = ident == 0xA5EC || ident == 0xA5DC;
        if (nFib < 0xC0 && (identValido || nFib >= 0x65)) {
            throw new Fallo("Este documento es de Word 6.0/95, un formato antiguo que el visor no puede abrir. "
                    + "Ábrelo en Word o LibreOffice y guárdalo como .docx.");
        }
        if (!identValido) throw new Fallo("El archivo no es un documento de Word válido.");
        byte[] tabla = cfb.flujo((banderas & 0x0200) != 0 ? "1Table" : "0Table");
        if (tabla == null) throw new Fallo("No se puede leer el texto del documento. Puede que el archivo esté dañado.");
        byte[] datos = cfb.flujo("Data");
        if ((banderas & 0x0100) != 0) {
            if ((banderas & 0x8000) != 0) {
                throw new Fallo("Este documento de Word usa una protección antigua (ofuscación XOR) que el visor "
                        + "no puede abrir. Ábrelo en Word o LibreOffice y guárdalo como .docx sin contraseña.");
            }
            Cripto.ClaveRc4 k = claveCifrado(tabla, clave);
            // [MS-DOC] 2.2.6: la FibBase (68 bytes) y la cabecera de cifrado del flujo de tabla van en claro;
            // el resto se cifra en bloques de 512 bytes contados desde el principio de cada flujo.
            int cab = Cfb.i32(wd, 0x0E);
            if (cab <= 0 || cab > tabla.length) cab = Math.min(tabla.length, largoCabecera(tabla));
            wd = wd.clone();
            tabla = tabla.clone();
            if (wd.length > 68) k.descifrar(wd, 68, wd.length - 68, 68, 512);
            k.descifrar(tabla, cab, tabla.length - cab, cab, 512);
            if (datos != null) {
                datos = datos.clone();
                k.descifrar(datos, 0, datos.length, 0, 512);
            }
        }
        Doc d = new Doc(wd, tabla, datos);
        StringBuilder sb = new StringBuilder();
        try {
            d.leer();
            d.convertirTexto(sb);
        } catch (Fallo f) {
            throw f;
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
            if (sb.length() == 0) throw new Fallo(DANADO);
            d.danado = true;
        }
        if (d.notas.length() > 0) sb.append("<div class=notas>").append(d.notas).append("</div>");
        if (d.notasFinales.length() > 0) sb.append("<div class=notas>").append(d.notasFinales).append("</div>");
        if (d.danado) sb.append(Html.NOTA_DANADA);
        if (sb.length() == 0) sb.append("<p class=nota>El documento está vacío.</p>");
        return new Html.Documento(sb.toString(), CSS, Html.VIEWPORT_MOVIL, "doc").conIndice(d.indice);
    }

    // --- Cifrado RC4 ---

    /** Valida la contraseña con la EncryptionHeader del principio del flujo de tabla. */
    private static Cripto.ClaveRc4 claveCifrado(byte[] tabla, String clave) throws Fallo {
        if (tabla.length < 52) throw new Fallo(DANADO);
        int mayor = Cfb.u16(tabla, 0), menor = Cfb.u16(tabla, 2);
        boolean clasico = mayor == 1 && menor == 1, cryptoApi = mayor >= 2 && mayor <= 4 && menor == 2;
        if (!clasico && !cryptoApi) {
            throw new Fallo("Este documento de Word está cifrado con un método que el visor no admite.");
        }
        if (clave == null) throw FalloClave.para(null);
        Cripto.ClaveRc4 k = clasico ? Cripto.rc4Clasico(tabla, 4, clave) : Cripto.rc4CryptoApi(tabla, 4, clave);
        if (k == null) throw FalloClave.para(clave);
        return k;
    }

    /** Tamaño de la cabecera de cifrado cuando lKey no es válido: versión, cabecera y verificador. */
    private static int largoCabecera(byte[] t) {
        if (Cfb.u16(t, 0) == 1) return 52;
        long cab = Cfb.i32(t, 8) & 0xFFFFFFFFL, v = 12 + cab;
        if (v + 4 > t.length) return t.length;
        return (int) Math.min(t.length, v + 4 + (Cfb.i32(t, (int) v) & 0xFFFFFFFFL) + 16 + 4 + 20);
    }

    // --- Lectura de la estructura (FIB, piezas, FKP, estilos, listas, notas) ---

    private void leer() throws Fallo {
        // FIB: FibBase (32 bytes), fibRgW (csw palabras), fibRgLw (cslw enteros) y fibRgFcLcb.
        int csw = Cfb.u16(wd, 0x20);
        int pos = 0x22 + 2 * csw;
        if (csw != 14 || pos + 2 > wd.length) throw new Fallo(DANADO);
        int cslw = Cfb.u16(wd, pos), lw = pos + 2;
        pos = lw + 4 * cslw;
        if (cslw < 11 || pos + 2 > wd.length) throw new Fallo(DANADO);
        int pares = Math.min(Cfb.u16(wd, pos), (wd.length - pos - 2) / 8);
        fibFcLcb = new int[2 * Math.max(0, pares)];
        for (int k = 0; k < fibFcLcb.length; k++) fibFcLcb[k] = Cfb.i32(wd, pos + 2 + 4 * k);
        int[] ccp = new int[8];
        for (int k = 0; k < 8; k++) ccp[k] = Math.max(0, Cfb.i32(wd, lw + 12 + 4 * k));
        ccpText = ccp[0];
        basePie = ccpText;
        baseFinal = ccp[0] + ccp[1] + ccp[2] + ccp[3] + ccp[4];

        leerPiezas(trozo(33));
        if (pFc.length == 0) throw new Fallo("No se puede leer el texto del documento. Puede que el archivo esté dañado.");
        leerTexto();
        try {
            leerFkp(trozo(12), true);
            leerFkp(trozo(13), false);
        } catch (RuntimeException e) {
            danado = true;
        }
        papxIni = inicios(papx);
        prepararTramos();
        try {
            leerEstilos(trozo(1));
        } catch (RuntimeException e) {
            estilos = new Estilo[0];
        }
        hpsBase = Math.max(2, chpEstilo(0, 0).hps);
        try {
            leerListas();
        } catch (RuntimeException ignorado) { }
        try {
            leerNotas(trozo(2), trozo(3), 0);
            leerNotas(trozo(46), trozo(47), 1);
        } catch (RuntimeException ignorado) { }
        try {
            imagenes.leerDibujos(trozo(40), trozo(50));
        } catch (RuntimeException ignorado) { }
    }

    /** Estructura n.º "k" del FibRgFcLcb, copiada del flujo de tabla (null si no hay). */
    private byte[] trozo(int k) {
        if (2 * k + 1 >= fibFcLcb.length) return null;
        int fc = fibFcLcb[2 * k], lcb = fibFcLcb[2 * k + 1];
        if (lcb <= 0 || fc < 0 || fc >= tabla.length) return null;
        int fin = (int) Math.min((long) fc + lcb, tabla.length);
        return Arrays.copyOfRange(tabla, fc, fin);
    }

    /** CLX: Prc con grpprl de piezas y Pcdt con la tabla de piezas (PlcPcd). */
    private void leerPiezas(byte[] clx) {
        if (clx == null) return;
        int i = 0;
        while (i < clx.length) {
            int tipo = clx[i] & 0xFF;
            if (tipo == 1 && i + 3 <= clx.length) {
                int cb = (short) Cfb.u16(clx, i + 1);
                if (cb < 0) return;
                int a = i + 3, b = Math.min(clx.length, a + cb);
                prc.add(Arrays.copyOfRange(clx, a, b));
                i = a + cb;
            } else if (tipo == 2 && i + 5 <= clx.length) {
                int lcb = Cfb.i32(clx, i + 1), a = i + 5;
                int fin = lcb < 0 || (long) a + lcb > clx.length ? clx.length : a + lcb;
                int total = (fin - a - 4) / 12, pcd = a + 4 * (total + 1), n = total;
                if (n <= 0) return;
                int[] cps = new int[n + 1];
                for (int k = 0; k <= total; k++) {
                    cps[k] = Cfb.i32(clx, a + 4 * k);
                    if (cps[k] < 0 || (k > 0 && cps[k] < cps[k - 1])) {
                        // Piezas desordenadas o negativas: se recorta ahí.
                        danado = true;
                        n = Math.max(0, k - 1);
                        break;
                    }
                }
                pCp = Arrays.copyOf(cps, n + 1);
                pFc = new int[n];
                pPrm = new int[n];
                pComp = new boolean[n];
                for (int k = 0; k < n; k++) {
                    int fc = Cfb.i32(clx, pcd + 8 * k + 2);
                    pComp[k] = (fc & 0x40000000) != 0;
                    fc &= 0x3FFFFFFF;
                    pFc[k] = pComp[k] ? fc / 2 : fc;
                    pPrm[k] = Cfb.u16(clx, pcd + 8 * k + 6);
                }
                return;
            } else {
                return;
            }
        }
    }

    private void leerTexto() {
        int total = Math.min(pCp[pCp.length - 1], MAX_CARACTERES);
        if (total < ccpText) {
            danado = true;
            ccpText = total;
        }
        texto = new char[total];
        for (int k = 0; k < pFc.length; k++) {
            int cp0 = pCp[k], cp1 = Math.min(pCp[k + 1], total), fc = pFc[k];
            for (int cp = cp0; cp < cp1; cp++) {
                long off = fc + (long) (cp - cp0) * (pComp[k] ? 1 : 2);
                if (off < 0 || off + (pComp[k] ? 1 : 2) > wd.length) {
                    if (cp < ccpText) danado = true;
                    break;
                }
                int o = (int) off;
                if (pComp[k]) {
                    int c = wd[o] & 0xFF;
                    texto[cp] = c >= 0x80 && c <= 0x9F ? CP1252[c - 0x80] : (char) c;
                } else {
                    texto[cp] = (char) Cfb.u16(wd, o);
                }
            }
        }
    }

    /** PlcBteChpx / PlcBtePapx: páginas FKP de 512 bytes con los tramos de formato por posición de archivo. */
    private void leerFkp(byte[] plc, boolean caracteres) {
        if (plc == null || plc.length < 8) return;
        List<Tramo> lista = caracteres ? chpx : papx;
        int n = (plc.length - 4) / 8;
        for (int k = 0; k < n; k++) {
            long base = (Cfb.i32(plc, 4 * (n + 1) + 4 * k) & 0x3FFFFFL) * 512;
            if (base + 512 > wd.length) {
                danado = true;
                continue;
            }
            int b = (int) base, cuenta = wd[b + 511] & 0xFF;
            int tamEntrada = caracteres ? 1 : 13;
            if (4 * (cuenta + 1) + tamEntrada * cuenta > 511) continue;
            for (int r = 0; r < cuenta; r++) {
                int ini = Cfb.i32(wd, b + 4 * r), fin = Cfb.i32(wd, b + 4 * r + 4);
                int desp = (wd[b + 4 * (cuenta + 1) + tamEntrada * r] & 0xFF) * 2;
                byte[] grp = null;
                int istd = 0;
                if (desp != 0) {
                    int o = b + desp, lim = b + 511;
                    if (caracteres) {
                        int cb = wd[o] & 0xFF;
                        if (o + 1 + cb <= lim) grp = Arrays.copyOfRange(wd, o + 1, o + 1 + cb);
                    } else {
                        int cb = wd[o] & 0xFF, a = o + 1, largo = 2 * cb - 1;
                        if (cb == 0 && o + 1 < lim) {
                            a = o + 2;
                            largo = 2 * (wd[o + 1] & 0xFF);
                        }
                        if (largo >= 2 && a + largo <= lim) {
                            istd = Cfb.u16(wd, a);
                            grp = Arrays.copyOfRange(wd, a + 2, a + largo);
                        }
                    }
                }
                if (fin > ini) lista.add(new Tramo(ini, fin, istd, grp, 0));
            }
        }
        lista.sort(Comparator.comparingInt(t -> t.ini));
    }

    private static int[] inicios(List<Tramo> l) {
        int[] r = new int[l.size()];
        for (int k = 0; k < r.length; k++) r[k] = l.get(k).ini;
        return r;
    }

    /** Índice del último tramo con ini &lt;= x (o -1). */
    private static int buscar(int[] inicios, int x) {
        int lo = 0, hi = inicios.length - 1, r = -1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (inicios[m] <= x) {
                r = m;
                lo = m + 1;
            } else {
                hi = m - 1;
            }
        }
        return r;
    }

    /** Pasa los tramos de caracteres de posiciones de archivo a posiciones de texto, pieza a pieza. */
    private void prepararTramos() {
        int[] ini = inicios(chpx);
        for (int k = 0; k < pFc.length; k++) {
            int cp0 = pCp[k], cp1 = Math.min(pCp[k + 1], texto.length), bpc = pComp[k] ? 1 : 2;
            long fc0 = pFc[k], fcFin = fc0 + (long) (cp1 - cp0) * bpc;
            int j = Math.max(0, buscar(ini, (int) fc0));
            int cp = cp0;
            while (cp < cp1) {
                long fc = fc0 + (long) (cp - cp0) * bpc;
                while (j < chpx.size() && chpx.get(j).fin <= fc) j++;
                long hasta;
                byte[] grp = null;
                if (j < chpx.size() && chpx.get(j).ini <= fc) {
                    hasta = Math.min(chpx.get(j).fin, fcFin);
                    grp = chpx.get(j).grp;
                } else {
                    hasta = j < chpx.size() ? Math.min(chpx.get(j).ini, fcFin) : fcFin;
                }
                int cpHasta = (int) Math.min(cp1, Math.max(cp + 1, cp0 + (hasta - fc0 + bpc - 1) / bpc));
                tramos.add(new Tramo(cp, cpHasta, 0, grp, pPrm[k]));
                cp = cpHasta;
            }
        }
        chpx.clear();
        tramosIni = inicios(tramos);
    }

    private int pieza(int cp) {
        int lo = 0, hi = pFc.length - 1, r = 0;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (pCp[m] <= cp) {
                r = m;
                lo = m + 1;
            } else {
                hi = m - 1;
            }
        }
        return r;
    }

    /** Grpprl de la pieza (Prm complejo), o null. */
    private byte[] grpPieza(int prm) {
        if ((prm & 1) == 0) return null;
        int i = prm >>> 1;
        return i < prc.size() ? prc.get(i) : null;
    }

    /** Propiedades del párrafo cuya marca final está en "cp". */
    private Pap papDe(int cp) {
        int k = pieza(cp);
        long fc = pFc[k] + (long) (cp - pCp[k]) * (pComp[k] ? 1 : 2);
        int i = fc > Integer.MAX_VALUE ? -1 : buscar(papxIni, (int) fc);
        Tramo t = i >= 0 && papx.get(i).fin > fc ? papx.get(i) : null;
        Pap p = papEstilo(t != null ? t.istd : 0, 0).copia();
        p.istd = t != null ? t.istd : 0;
        if (t != null) aplicar(t.grp, null, p);
        aplicar(grpPieza(pPrm[k]), null, p);
        return p;
    }

    // --- Estilos ---

    private void leerEstilos(byte[] s) {
        if (s == null || s.length < 6) return;
        int cbStshi = Cfb.u16(s, 0), cstd = Math.min(Cfb.u16(s, 2), 4095), cbBase = Cfb.u16(s, 4);
        if (cbBase < 10) cbBase = 10;
        estilos = new Estilo[cstd];
        int pos = 2 + cbStshi;
        for (int i = 0; i < cstd && pos + 2 <= s.length; i++) {
            int cb = Cfb.u16(s, pos);
            pos += 2;
            int fin = Math.min(pos + cb, s.length);
            if (cb >= 10 && pos + 10 <= fin) estilos[i] = leerEstilo(s, pos, fin, cbBase);
            pos += cb;
        }
    }

    private static Estilo leerEstilo(byte[] s, int pos, int fin, int cbBase) {
        Estilo e = new Estilo();
        e.sti = Cfb.u16(s, pos) & 0x0FFF;
        e.stk = Cfb.u16(s, pos + 2) & 0xF;
        e.base = Cfb.u16(s, pos + 2) >>> 4;
        int cupx = Cfb.u16(s, pos + 4) & 0xF;
        int n = pos + cbBase;
        if (n + 2 > fin) return e;
        int cch = Cfb.u16(s, n);
        if (n + 2 + 2 * cch > fin) return e;
        char[] nombre = new char[cch];
        for (int k = 0; k < cch; k++) nombre[k] = (char) Cfb.u16(s, n + 2 + 2 * k);
        e.nombre = new String(nombre);
        int u = n + 2 + 2 * cch + 2;
        for (int k = 0; k < cupx && u + 2 <= fin; k++) {
            int cbU = Cfb.u16(s, u), a = u + 2, b = a + cbU;
            if (b > fin) break;
            // Párrafo: PAPX, CHPX. Carácter: CHPX. Tabla: TAPX, PAPX, CHPX. Numeración: PAPX.
            boolean esPapx = (e.stk == 1 && k == 0) || (e.stk == 3 && k == 1) || (e.stk == 4 && k == 0);
            boolean esChpx = (e.stk == 1 && k == 1) || (e.stk == 2 && k == 0) || (e.stk == 3 && k == 2);
            if (esPapx && cbU >= 2) e.papx = Arrays.copyOfRange(s, a + 2, b);
            if (esChpx) e.chpx = Arrays.copyOfRange(s, a, b);
            u = b + (cbU & 1);
        }
        return e;
    }

    private Estilo estilo(int istd) {
        return istd >= 0 && istd < estilos.length ? estilos[istd] : null;
    }

    private Pap papEstilo(int istd, int prof) {
        Estilo e = estilo(istd);
        if (e == null || prof > 16) return new Pap();
        if (e.pap == null) {
            Pap p = e.base != 0xFFF && e.base != istd ? papEstilo(e.base, prof + 1).copia() : new Pap();
            aplicar(e.papx, null, p);
            e.pap = p;
        }
        return e.pap;
    }

    private Chp chpEstilo(int istd, int prof) {
        Estilo e = estilo(istd);
        if (e == null || prof > 16) return new Chp();
        if (e.chp == null) {
            Chp c = e.base != 0xFFF && e.base != istd ? chpEstilo(e.base, prof + 1).copia() : new Chp();
            aplicar(e.chpx, c, null);
            e.chp = c;
        }
        return e.chp;
    }

    /** Aplica un estilo de carácter (y los estilos de carácter en que se basa) sobre "c". */
    private void aplicarEstiloCaracter(int istd, Chp c) {
        Estilo e = estilo(istd);
        if (e == null || e.stk != 2 || anidados > 16) return;
        anidados++;
        try {
            if (e.base != istd) aplicarEstiloCaracter(e.base, c);
            aplicar(e.chpx, c, null);
        } finally {
            anidados--;
        }
    }

    /** Nivel de título 0–5 del estilo (por su identificador o su nombre), o -1. */
    private int nivelEstilo(int istd) {
        for (int vueltas = 0; vueltas < 16; vueltas++) {
            Estilo e = estilo(istd);
            if (e == null) return -1;
            if (e.sti >= 1 && e.sti <= 9) return Math.min(e.sti - 1, 5);
            if (e.sti == 62) return 0;
            int n = nivelPorNombre(e.nombre);
            if (n >= 0) return n;
            if (e.base == 0xFFF || e.base == istd) return -1;
            istd = e.base;
        }
        return -1;
    }

    private static int nivelPorNombre(String nombre) {
        String s = nombre.toLowerCase(Locale.ROOT).replace(" ", "");
        int coma = s.indexOf(',');
        if (coma > 0) s = s.substring(0, coma);
        if (s.equals("title") || s.equals("título") || s.equals("titulo")) return 0;
        for (String pre : new String[] {"heading", "título", "titulo", "encabezado", "überschrift", "titre"}) {
            if (s.length() == pre.length() + 1 && s.startsWith(pre)) {
                char c = s.charAt(pre.length());
                if (c >= '1' && c <= '9') return Math.min(c - '1', 5);
            }
        }
        return -1;
    }

    // --- Sprm ---

    /** Recorre un grpprl y aplica los sprm conocidos al CHP y/o al PAP. */
    private void aplicar(byte[] g, Chp c, Pap p) {
        if (g == null) return;
        int i = 0;
        while (i + 2 <= g.length) {
            int sprm = Cfb.u16(g, i), o = i + 2, largo;
            switch (sprm >>> 13) {
                case 0: case 1: largo = 1; break;
                case 2: case 4: case 5: largo = 2; break;
                case 3: largo = 4; break;
                case 7: largo = 3; break;
                default:
                    if (sprm == 0xD608 || sprm == 0xD606) {
                        if (o + 2 > g.length) return;
                        largo = Cfb.u16(g, o) + 1;
                    } else if (sprm == 0xC615 && o < g.length && (g[o] & 0xFF) == 255) {
                        largo = largoTabs(g, o);
                    } else {
                        if (o >= g.length) return;
                        largo = (g[o] & 0xFF) + 1;
                    }
            }
            if (o + largo > g.length) return;
            if (c != null) sprmCaracter(sprm, g, o, largo, c);
            if (p != null) sprmParrafo(sprm, g, o, largo, p);
            i = o + largo;
        }
    }

    /** Tamaño del operando de sprmPChgTabs cuando cb = 255 (PChgTabsDelClose + PChgTabsAdd). */
    private static int largoTabs(byte[] g, int o) {
        int i = o + 1;
        if (i >= g.length) return g.length - o;
        int del = g[i] & 0xFF;
        i += 1 + 4 * del;
        if (i >= g.length) return g.length - o;
        int add = g[i] & 0xFF;
        return i + 1 + 3 * add - o;
    }

    private static boolean conmutar(int v, boolean actual) {
        if (v == 0x80) return actual;
        if (v == 0x81) return !actual;
        return v != 0;
    }

    private void sprmCaracter(int sprm, byte[] g, int o, int largo, Chp c) {
        int v = g[o] & 0xFF;
        switch (sprm) {
            case 0x0835: c.b = conmutar(v, c.b); break;
            case 0x0836: c.i = conmutar(v, c.i); break;
            case 0x0837: case 0x2A53: c.s = conmutar(v, c.s); break;
            case 0x083A: c.versalitas = conmutar(v, c.versalitas); break;
            case 0x083B: c.mayus = conmutar(v, c.mayus); break;
            case 0x083C: c.oculto = conmutar(v, c.oculto); break;
            case 0x0800: c.borrado = v != 0; break;
            case 0x0855: c.espec = v != 0; break;
            case 0x080A: c.ole = v != 0; break;
            case 0x0806: c.datos = v != 0; break;
            case 0x2A3E: c.subrayado = v; break;
            case 0x2A48: c.iss = v; break;
            case 0x2A42: c.color = v < ICO.length ? ICO[v] : -1; break;
            case 0x2A0C: c.resaltado = v > 0 && v < ICO.length ? ICO[v] : -1; break;
            case 0x4A43: c.hps = Math.max(2, Math.min(3276, Cfb.u16(g, o))); break;
            case 0x6870:
                c.color = colorRef(g, o);
                break;
            case 0x6A03: c.pic = Cfb.i32(g, o); break;
            case 0x6A09: c.simbolo = Cfb.u16(g, o + 2); break;
            case 0x4866: c.resaltado = sombreado80(Cfb.u16(g, o)); break;
            case 0xCA71: if (largo >= 11) c.resaltado = sombreado(g, o + 1); break;
            case 0x4A30: aplicarEstiloCaracter(Cfb.u16(g, o), c); break;
            default: break;
        }
    }

    private static void sprmParrafo(int sprm, byte[] g, int o, int largo, Pap p) {
        int v = g[o] & 0xFF;
        switch (sprm) {
            case 0x2403: case 0x2461: p.jc = v; break;
            case 0x2407: p.saltoAntes = v != 0; break;
            case 0x2416: p.enTabla = v != 0; break;
            case 0x2417: p.finFila = v != 0; break;
            case 0x244B: p.celdaInterior = v != 0; break;
            case 0x244C: p.finFilaInterior = v != 0; break;
            case 0x6649: p.itap = Cfb.i32(g, o); break;
            case 0x664A: p.itap += Cfb.i32(g, o); break;
            case 0x460B: p.ilfo = (short) Cfb.u16(g, o); break;
            case 0x260A: p.ilvl = v; break;
            case 0x2640: p.esquema = v; break;
            case 0x840F: case 0x845E: p.sangria = (short) Cfb.u16(g, o); break;
            case 0x4610: p.sangria += (short) Cfb.u16(g, o); break;
            case 0x8411: case 0x8460: p.primeraLinea = (short) Cfb.u16(g, o); break;
            case 0x442D: p.fondo = sombreado80(Cfb.u16(g, o)); break;
            case 0xC64D: if (largo >= 11) p.fondo = sombreado(g, o + 1); break;
            case 0xD609: case 0xD612: {
                // Sombreado de las celdas: SHD80 (2 bytes) o SHD (10 bytes) por celda.
                int tam = sprm == 0xD609 ? 2 : 10, n = (largo - 1) / tam;
                if (sprm == 0xD609 && p.fondos != null) break; // ya hay SHD, más preciso
                p.fondos = new int[n];
                for (int k = 0; k < n; k++) {
                    int q = o + 1 + tam * k;
                    p.fondos[k] = tam == 2 ? sombreado80(Cfb.u16(g, q)) : sombreado(g, q);
                }
                break;
            }
            case 0xD608: case 0xD606: {
                // TDefTableOperand: cb, itcMac, rgdxaCenter[itcMac+1], rgTc80[itcMac] (20 bytes cada uno).
                if (largo < 3) break;
                int n = Math.min(g[o + 2] & 0xFF, 64), tc = o + 3 + 2 * (n + 1);
                if (tc > o + largo) break;
                p.celdas = new int[n];
                p.bordes = new int[n + 1];
                for (int k = 0; k <= n; k++) p.bordes[k] = (short) Cfb.u16(g, o + 3 + 2 * k);
                for (int k = 0; k < n; k++) {
                    int q = tc + 20 * k;
                    if (q + 2 <= o + largo) p.celdas[k] = Cfb.u16(g, q);
                }
                break;
            }
            case 0xD62B: {
                // sprmTVertMerge: itc, vertMerge.
                if (largo >= 3 && p.celdas != null) {
                    int itc = g[o + 1] & 0xFF;
                    if (itc < p.celdas.length) p.celdas[itc] = (p.celdas[itc] & ~0x60) | ((g[o + 2] & 3) << 5);
                }
                break;
            }
            default: break;
        }
    }

    /** Porcentaje de trama de los ipat 2–13 (5 %, 10 %, 20 %…). */
    private static final int[] TRAMA = {5, 10, 20, 25, 30, 40, 50, 60, 70, 75, 80, 90};

    /** Color de un sombreado (-1 si no hay): trama sólida, transparente o porcentaje de mezcla. */
    private static int mezcla(int frente, int fondo, int ipat) {
        if (ipat == 0) return fondo;
        if (frente < 0) frente = 0;
        if (ipat == 1) return frente;
        if (ipat < 2 || ipat > 13) return fondo;
        int f = fondo < 0 ? 0xFFFFFF : fondo, pct = TRAMA[ipat - 2], r = 0;
        for (int d = 0; d <= 16; d += 8) {
            int a = (frente >> d) & 0xFF, b = (f >> d) & 0xFF;
            r |= ((a * pct + b * (100 - pct)) / 100) << d;
        }
        return r;
    }

    private static int sombreado80(int v) {
        int fr = v & 0x1F, fo = (v >> 5) & 0x1F;
        return mezcla(fr < ICO.length ? ICO[fr] : -1, fo < ICO.length ? ICO[fo] : -1, v >>> 10);
    }

    private static int sombreado(byte[] g, int o) {
        return mezcla(colorRef(g, o), colorRef(g, o + 4), Cfb.u16(g, o + 8));
    }

    /** COLORREF (R, G, B, fAuto) → RGB, o -1 si es automático. */
    private static int colorRef(byte[] g, int o) {
        return (g[o + 3] & 0xFF) == 0xFF ? -1 : (g[o] & 0xFF) << 16 | (g[o + 1] & 0xFF) << 8 | (g[o + 2] & 0xFF);
    }

    // --- Listas ---

    private void leerListas() {
        if (fibFcLcb.length <= 2 * 74 + 1) return;
        int pos = fibFcLcb[2 * 73], n = tabla.length;
        if (fibFcLcb[2 * 73 + 1] > 0 && pos >= 0 && pos + 2 <= n) {
            int cLst = (short) Cfb.u16(tabla, pos);
            pos += 2;
            List<Integer> ids = new ArrayList<>();
            List<Lista> defs = new ArrayList<>();
            for (int k = 0; k < cLst && k < 4096 && pos + 28 <= n; k++, pos += 28) {
                Lista l = new Lista();
                l.niveles = (tabla[pos + 26] & 1) != 0 ? 1 : 9;
                ids.add(Cfb.i32(tabla, pos));
                defs.add(l);
            }
            // Los LVL de cada lista van seguidos detrás del PlfLst.
            fuera:
            for (int k = 0; k < defs.size(); k++) {
                Lista l = defs.get(k);
                for (int v = 0; v < l.niveles; v++) {
                    if (pos + 28 > n) break fuera;
                    l.inicio[v] = Cfb.i32(tabla, pos);
                    l.nfc[v] = tabla[pos + 4] & 0xFF;
                    pos += 28 + (tabla[pos + 25] & 0xFF) + (tabla[pos + 24] & 0xFF);
                    if (pos + 2 > n) break fuera;
                    int cch = Math.min(Cfb.u16(tabla, pos), 256);
                    pos += 2;
                    if (pos + 2 * cch > n) break fuera;
                    char[] t = new char[cch];
                    for (int x = 0; x < cch; x++) t[x] = (char) Cfb.u16(tabla, pos + 2 * x);
                    l.texto[v] = new String(t);
                    pos += 2 * cch;
                }
                listas.put(ids.get(k), l);
            }
        }
        int fcLfo = fibFcLcb[2 * 74];
        if (fibFcLcb[2 * 74 + 1] > 0 && fcLfo >= 0 && fcLfo + 4 <= n) {
            int lfoMac = Math.max(0, Math.min(Cfb.i32(tabla, fcLfo), (n - fcLfo - 4) / 16));
            lfoLsid = new int[Math.min(lfoMac, 32767)];
            for (int k = 0; k < lfoLsid.length; k++) lfoLsid[k] = Cfb.i32(tabla, fcLfo + 4 + 16 * k);
        }
    }

    private Lista listaDe(Pap p) {
        if (p.ilfo <= 0 || p.ilfo > lfoLsid.length) return null;
        return listas.get(lfoLsid[p.ilfo - 1]);
    }

    /** Avanza y devuelve el contador de la lista en el nivel indicado. */
    private int siguienteNumero(int lsid, Lista l, int nivel) {
        int[] c = contadores.computeIfAbsent(lsid, x -> {
            int[] a = new int[9];
            Arrays.fill(a, Integer.MIN_VALUE);
            return a;
        });
        c[nivel] = c[nivel] == Integer.MIN_VALUE ? l.inicio[nivel] : c[nivel] + 1;
        for (int k = nivel + 1; k < 9; k++) c[k] = Integer.MIN_VALUE;
        return c[nivel];
    }

    /** Texto del número ("1.2.a)") con los contadores actuales, para los títulos numerados. */
    private String etiqueta(int lsid, Lista l, int nivel) {
        String x = l.texto[nivel];
        if (x == null || l.nfc[nivel] == 23 || l.nfc[nivel] == 255) return "";
        int[] c = contadores.get(lsid);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < x.length(); i++) {
            char ch = x.charAt(i);
            if (ch < 9) {
                int v = c != null && c[ch] != Integer.MIN_VALUE ? c[ch] : l.inicio[ch];
                sb.append(numeroComo(v, l.nfc[ch]));
            } else {
                sb.append(ch);
            }
        }
        return sb.append(' ').toString();
    }

    private static String numeroComo(int v, int nfc) {
        if ((nfc == 1 || nfc == 2) && v > 0 && v < 4000) {
            String[] m = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
            int[] n = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < n.length; k++) {
                while (v >= n[k]) {
                    sb.append(m[k]);
                    v -= n[k];
                }
            }
            return nfc == 1 ? sb.toString().toUpperCase(Locale.ROOT) : sb.toString();
        }
        if ((nfc == 3 || nfc == 4) && v > 0) {
            String s = String.valueOf((char) ((nfc == 3 ? 'A' : 'a') + (v - 1) % 26));
            return s.repeat(Math.min(10, (v - 1) / 26 + 1));
        }
        return Integer.toString(v);
    }

    private static String tipoOl(int nfc) {
        switch (nfc) {
            case 1: return "I";
            case 2: return "i";
            case 3: return "A";
            case 4: return "a";
            default: return "1";
        }
    }

    /** Listas abiertas en un contenedor (cuerpo, celda o nota). */
    private static final class Listas {
        final List<String> etiquetas = new ArrayList<>();
        final List<Integer> claves = new ArrayList<>();

        void cerrar(StringBuilder sb, int hasta) {
            while (etiquetas.size() > hasta) {
                sb.append("</").append(etiquetas.remove(etiquetas.size() - 1)).append('>');
                claves.remove(claves.size() - 1);
            }
        }

        void abrirHasta(StringBuilder sb, int lsid, int nivel, int nfc, int numero) {
            String et = nfc == 23 || nfc == 255 ? "ul" : "ol";
            cerrar(sb, nivel + 1);
            if (etiquetas.size() == nivel + 1) {
                int k = etiquetas.size() - 1;
                if (claves.get(k) != lsid || !etiquetas.get(k).equals(et)) cerrar(sb, nivel);
            }
            while (etiquetas.size() < nivel + 1) {
                boolean propio = etiquetas.size() == nivel;
                sb.append('<').append(et);
                if (propio && et.equals("ol")) {
                    sb.append(" type=").append(tipoOl(nfc));
                    if (numero != 1) sb.append(" start=").append(numero);
                }
                if (propio && nfc == 255) sb.append(" style=\"list-style:none\"");
                sb.append('>');
                etiquetas.add(et);
                claves.add(lsid);
            }
        }
    }

    // --- Notas al pie y al final ---

    private void leerNotas(byte[] refs, byte[] txt, int tipo) {
        if (refs == null || txt == null || refs.length < 4) return;
        int n = (refs.length - 4) / 6;
        for (int k = 0; k < n; k++) refsNotas.put(Cfb.i32(refs, 4 * k), new int[] {tipo, k});
        int[] t = new int[txt.length / 4];
        for (int k = 0; k < t.length; k++) t[k] = Cfb.i32(txt, 4 * k);
        if (tipo == 0) txtPie = t;
        else txtFinal = t;
    }

    /** Escribe la llamada a la nota y guarda su texto para el final del documento. */
    private void nota(int[] ref, char c, StringBuilder out) {
        int k = ++numNotas;
        String marca = c != 2 ? Html.esc(String.valueOf(c)) : ref[0] == 0 ? Integer.toString(++numPie) : numeroComo(++numFinal, 2);
        out.append("<sup class=ref><a id=\"ref").append(k).append("\" href=\"#nota").append(k).append("\">")
                .append(marca).append("</a></sup>");
        int[] t = ref[0] == 0 ? txtPie : txtFinal;
        int base = ref[0] == 0 ? basePie : baseFinal, i = ref[1];
        StringBuilder cuerpo = new StringBuilder();
        if (i + 1 < t.length) {
            long a = (long) base + t[i], b = (long) base + t[i + 1];
            if (a >= 0 && a < b && b <= texto.length && b - a < 1_000_000) {
                List<Campo> guardados = campos;
                Campo enlace = enlaceAbierto;
                campos = new ArrayList<>();
                enlaceAbierto = null;
                rango((int) a, (int) b, cuerpo, true);
                campos = guardados;
                enlaceAbierto = enlace;
            }
        }
        (ref[0] == 0 ? notas : notasFinales).append("<div class=nt id=\"nota").append(k).append("\"><a href=\"#ref")
                .append(k).append("\">").append(marca).append("</a><div>").append(cuerpo).append("</div></div>");
    }

    // --- Conversión ---

    private void convertirTexto(StringBuilder sb) {
        rango(0, ccpText, sb, false);
    }

    /** Convierte el texto [a, b): párrafos, listas y tablas. */
    private void rango(int a, int b, StringBuilder sb, boolean enNota) {
        List<Parrafo> ps = new ArrayList<>();
        int ini = a;
        for (int cp = a; cp < b; cp++) {
            char c = texto[cp];
            if (c == 0x0D || c == 0x07 || c == 0x0C) {
                Parrafo p = new Parrafo();
                p.ini = ini;
                p.fin = cp;
                p.marca = cp;
                p.pap = papDe(cp);
                ps.add(p);
                ini = cp + 1;
            }
        }
        if (ini < b) {
            Parrafo p = new Parrafo();
            p.ini = ini;
            p.fin = b;
            p.marca = -1;
            p.pap = papDe(b - 1);
            ps.add(p);
        }
        int i = 0;
        Listas l = new Listas();
        while (i < ps.size()) {
            Parrafo p = ps.get(i);
            if (profundidad(p) > 0) {
                l.cerrar(sb, 0);
                int antes = i;
                i = tabla(ps, i, 1, sb, enNota);
                if (i == antes) i++;
            } else {
                parrafo(p, sb, l, enNota);
                i++;
            }
        }
        l.cerrar(sb, 0);
    }

    private static int profundidad(Parrafo p) {
        if (!p.pap.enTabla) return 0;
        return Math.max(1, Math.min(p.pap.itap, MAX_PROFUNDIDAD_TABLAS));
    }

    private boolean finDeFila(Parrafo p, int nivel) {
        if (nivel == 1) return p.pap.finFila || (p.marca >= 0 && texto[p.marca] == 7 && p.pap.celdas != null);
        return p.pap.finFilaInterior;
    }

    private boolean finDeCelda(Parrafo p) {
        return (p.marca >= 0 && texto[p.marca] == 7) || p.pap.celdaInterior;
    }

    /** Tabla del nivel indicado desde el párrafo i; devuelve el índice siguiente a la tabla. */
    private int tabla(List<Parrafo> ps, int i, int nivel, StringBuilder sb, boolean enNota) {
        List<List<Celda>> filas = new ArrayList<>();
        List<Pap> marcas = new ArrayList<>();
        List<Celda> fila = new ArrayList<>();
        while (i < ps.size()) {
            Parrafo p = ps.get(i);
            int prof = profundidad(p);
            if (prof < nivel) break;
            if (prof == nivel && finDeFila(p, nivel)) {
                filas.add(fila);
                marcas.add(p.pap);
                fila = new ArrayList<>();
                i++;
                continue;
            }
            StringBuilder html = new StringBuilder();
            int antes = i;
            i = celda(ps, i, nivel, html, enNota);
            if (i == antes) i++;
            Celda c = new Celda();
            c.html = html.length() == 0 ? "" : html.toString();
            fila.add(c);
        }
        if (!fila.isEmpty()) {
            filas.add(fila);
            marcas.add(null);
        }
        combinar(filas, marcas);
        sb.append("<div class=tabla><table class=b>");
        for (List<Celda> cs : filas) {
            sb.append("<tr>");
            for (Celda c : cs) {
                if (c.oculta) continue;
                sb.append("<td");
                if (c.columnas > 1) sb.append(" colspan=").append(c.columnas);
                if (c.filas > 1) sb.append(" rowspan=").append(c.filas);
                if (c.fondo >= 0) sb.append(" style=\"").append(fondo(c.fondo)).append('"');
                sb.append('>').append(c.html).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></div>");
        return i;
    }

    /**
     * Celdas combinadas: las columnas salen de los bordes de cada fila (rgdxaCenter) y las
     * combinaciones de TC80.tcgrf (horzMerge en los bits 0–1, vertMerge en los bits 5–6).
     */
    private static void combinar(List<List<Celda>> filas, List<Pap> marcas) {
        java.util.TreeSet<Integer> todos = new java.util.TreeSet<>();
        for (Pap m : marcas) if (m != null && m.bordes != null) for (int x : m.bordes) todos.add(x);
        int[] rejilla = new int[todos.size()];
        int n = 0;
        for (int x : todos) rejilla[n++] = x;
        for (int f = 0; f < filas.size(); f++) {
            List<Celda> cs = filas.get(f);
            Pap m = marcas.get(f);
            int col = 0;
            Celda anterior = null;
            for (int k = 0; k < cs.size(); k++) {
                Celda c = cs.get(k);
                c.col = col;
                if (m != null && m.bordes != null && k + 1 < m.bordes.length) {
                    int a = Arrays.binarySearch(rejilla, m.bordes[k]), b = Arrays.binarySearch(rejilla, m.bordes[k + 1]);
                    if (a >= 0 && b > a) {
                        c.col = a;
                        c.columnas = b - a;
                    }
                }
                col = c.col + c.columnas;
                if (m != null && m.fondos != null && k < m.fondos.length) c.fondo = m.fondos[k];
                int tc = m != null && m.celdas != null && k < m.celdas.length ? m.celdas[k] : 0;
                if ((tc & 3) >= 2 && anterior != null) {
                    anterior.columnas += c.columnas;
                    c.oculta = true;
                    continue;
                }
                if (((tc >> 5) & 3) == 1) {
                    for (int arriba = f - 1; arriba >= 0 && !c.oculta; arriba--) {
                        Celda o = null;
                        for (Celda x : filas.get(arriba)) if (x.col == c.col) o = x;
                        if (o == null) break;
                        if (!o.oculta) {
                            o.filas++;
                            c.oculta = true;
                        }
                    }
                }
                if (!c.oculta) anterior = c;
            }
        }
    }

    /** Contenido de una celda: párrafos (y tablas anidadas) hasta su marca de fin de celda. */
    private int celda(List<Parrafo> ps, int i, int nivel, StringBuilder sb, boolean enNota) {
        Listas l = new Listas();
        while (i < ps.size()) {
            Parrafo p = ps.get(i);
            int prof = profundidad(p);
            if (prof < nivel) break;
            if (prof > nivel) {
                l.cerrar(sb, 0);
                int antes = i;
                i = tabla(ps, i, nivel + 1, sb, enNota);
                if (i == antes) i++;
                continue;
            }
            if (finDeFila(p, nivel)) break;
            parrafo(p, sb, l, enNota);
            i++;
            if (finDeCelda(p)) break;
        }
        l.cerrar(sb, 0);
        return i;
    }

    private int nivelTitulo(Pap p) {
        if (p.esquema >= 0 && p.esquema < 9) return Math.min(p.esquema, 5);
        return nivelEstilo(p.istd);
    }

    private void parrafo(Parrafo p, StringBuilder sb, Listas l, boolean enNota) {
        Pap pap = p.pap;
        int nivel = nivelTitulo(pap);
        StringBuilder cont = new StringBuilder(), plano = new StringBuilder();
        boolean hay = enLinea(p.ini, p.fin, pap, nivel >= 0, cont, plano, enNota);
        boolean saltoDetras = p.marca >= 0 && texto[p.marca] == 0x0C;
        if (!hay) {
            // Párrafos vacíos que no se ven: la parte antes de un salto, marcas ocultas o borradas.
            Chp marca = p.marca >= 0 ? chpDe(p.marca, chpEstilo(pap.istd, 0)) : null;
            if (saltoDetras || (marca != null && (marca.oculto || marca.borrado)) || ocultoPorCampo()) {
                if (saltoDetras) {
                    l.cerrar(sb, 0);
                    sb.append(SALTO);
                }
                return;
            }
            cont.append("<br>");
        }

        Lista lista = hay ? listaDe(pap) : null;
        int lsid = lista != null ? lfoLsid[pap.ilfo - 1] : 0;
        int ilvl = lista != null ? Math.max(0, Math.min(pap.ilvl, lista.niveles - 1)) : 0;
        int numero = lista != null ? siguienteNumero(lsid, lista, ilvl) : 0;
        String prefijo = "";
        if (lista != null && nivel >= 0) {
            prefijo = etiqueta(lsid, lista, ilvl);
            lista = null;
        }

        StringBuilder estilo = new StringBuilder();
        if (pap.fondo >= 0) estilo.append(fondo(pap.fondo));
        if (pap.jc == 1) estilo.append("text-align:center;");
        else if (pap.jc == 2) estilo.append("text-align:right;");
        else if (pap.jc >= 3 && pap.jc <= 5) estilo.append("text-align:justify;");
        if (lista == null && pap.sangria > 0) {
            estilo.append("margin-left:").append(Html.px(Math.min(pap.sangria, 4000) / 20.0)).append("pt;");
        }
        if (lista == null && pap.primeraLinea != 0) {
            estilo.append("text-indent:").append(Html.px(Math.max(-2000, Math.min(2000, pap.primeraLinea)) / 20.0))
                    .append("pt;");
        }
        String atrEstilo = estilo.length() > 0 ? " style=\"" + estilo + "\"" : "";

        if (pap.saltoAntes && sb.length() > 0) {
            l.cerrar(sb, 0);
            sb.append(SALTO);
        }
        if (lista != null) {
            l.abrirHasta(sb, lsid, ilvl, lista.nfc[ilvl], numero);
            sb.append("<li").append(atrEstilo).append('>').append(cont).append("</li>");
        } else {
            l.cerrar(sb, 0);
            String et = nivel >= 0 ? "h" + (nivel + 1) : "p";
            sb.append('<').append(et);
            if (nivel >= 0 && !enNota) {
                String id = "t" + (++numTitulos);
                sb.append(" id=").append(id);
                String t = (prefijo + plano).replaceAll("\\s+", " ").trim();
                if (!t.isEmpty()) indice.add(new Html.Titulo(nivel + 1, t, id));
            }
            sb.append(atrEstilo).append('>');
            Html.esc(sb, prefijo);
            sb.append(cont).append("</").append(et).append('>');
        }
        if (saltoDetras) {
            l.cerrar(sb, 0);
            sb.append(SALTO);
        }
    }

    private boolean ocultoPorCampo() {
        for (Campo c : campos) if (!c.resultado) return true;
        return false;
    }

    /** CHP del carácter en "cp" partiendo del formato del estilo de párrafo. */
    private Chp chpDe(int cp, Chp base) {
        int j = buscar(tramosIni, cp);
        if (j < 0 || tramos.get(j).fin <= cp) return base;
        Tramo t = tramos.get(j);
        Chp c = base.copia();
        aplicar(t.grp, c, null);
        aplicar(grpPieza(t.prm), c, null);
        return c;
    }

    /**
     * Escribe el texto [ini, fin) con su formato. Devuelve true si hay algo visible.
     * "plano" recibe el texto sin etiquetas (para el índice).
     */
    private boolean enLinea(int ini, int fin, Pap pap, boolean titulo, StringBuilder out, StringBuilder plano,
                            boolean enNota) {
        Chp base = chpEstilo(pap.istd, 0);
        boolean hay = false;
        enlaceAbierto = null;
        Campo activo = enlaceActivo();
        if (activo != null) {
            abrirEnlace(activo, out);
            enlaceAbierto = activo;
        }
        StringBuilder t = new StringBuilder();
        Chp actual = null, chp = base;
        String claveActual = null;
        int hasta = -1, j = Math.max(0, buscar(tramosIni, ini));
        for (int cp = ini; cp < fin; cp++) {
            if (cp >= hasta) {
                while (j < tramos.size() && tramos.get(j).fin <= cp) j++;
                if (j < tramos.size() && tramos.get(j).ini <= cp) {
                    Tramo tr = tramos.get(j);
                    chp = base.copia();
                    aplicar(tr.grp, chp, null);
                    aplicar(grpPieza(tr.prm), chp, null);
                    hasta = tr.fin;
                } else {
                    chp = base;
                    hasta = j < tramos.size() ? tramos.get(j).ini : Integer.MAX_VALUE;
                }
            }
            char c = texto[cp];
            if (c == 0x13) {
                volcar(t, actual, claveActual, out);
                if (campos.size() < MAX_CAMPOS) campos.add(new Campo());
                continue;
            }
            if (c == 0x14) {
                if (campos.isEmpty()) continue;
                Campo f = campos.get(campos.size() - 1);
                if (f.resultado) continue;
                f.resultado = true;
                f.href = destinoEnlace(f.instr.toString());
                if (f.href != null && enlaceAbierto == null && !ocultoPorCampo()) {
                    volcar(t, actual, claveActual, out);
                    abrirEnlace(f, out);
                    enlaceAbierto = f;
                }
                continue;
            }
            if (c == 0x15) {
                if (campos.isEmpty()) continue;
                volcar(t, actual, claveActual, out);
                Campo f = campos.remove(campos.size() - 1);
                if (f == enlaceAbierto) {
                    out.append("</a>");
                    enlaceAbierto = null;
                }
                continue;
            }
            if (ocultoPorCampo()) {
                Campo f = campos.get(campos.size() - 1);
                if (f.instr.length() < MAX_INSTRUCCION) f.instr.append(c);
                else if (!f.resultado) campos.remove(campos.size() - 1); // campo roto: se muestra el resto
                continue;
            }
            if (chp.oculto || chp.borrado) continue;
            if (!enNota) {
                int[] ref = refsNotas.get(cp);
                if (ref != null && (c == 2 || c > 0x20)) {
                    volcar(t, actual, claveActual, out);
                    nota(ref, c, out);
                    hay = true;
                    continue;
                }
            }
            switch (c) {
                case 0x01:
                    if (chp.espec) {
                        volcar(t, actual, claveActual, out);
                        imagenEnLinea(chp, out);
                        hay = true;
                    }
                    continue;
                case 0x08:
                    if (chp.espec) {
                        volcar(t, actual, claveActual, out);
                        if (imagenFlotante(cp, out)) hay = true;
                    }
                    continue;
                case 0x0B:
                    volcar(t, actual, claveActual, out);
                    out.append("<br>");
                    hay = true;
                    continue;
                case 0x1E: c = '‑'; break;
                case 0x09: break;
                case 0x28:
                    if (chp.espec && chp.simbolo >= 0) c = simbolo(chp.simbolo);
                    break;
                default:
                    if (c < 0x20) continue;
            }
            if (chp != actual) {
                String clave = clave(chp, titulo);
                if (!clave.equals(claveActual)) volcar(t, actual, claveActual, out);
                actual = chp;
                claveActual = clave;
            }
            t.append(c);
            plano.append(c);
            hay = true;
        }
        volcar(t, actual, claveActual, out);
        if (enlaceAbierto != null) {
            out.append("</a>");
            enlaceAbierto = null;
        }
        return hay;
    }

    private Campo enlaceActivo() {
        if (ocultoPorCampo()) return null;
        for (Campo c : campos) if (c.href != null) return c;
        return null;
    }

    private static void abrirEnlace(Campo f, StringBuilder out) {
        out.append("<a href=\"").append(Html.esc(f.href)).append("\">");
    }

    /** Destino de un campo HYPERLINK externo ("HYPERLINK \"http://…\""), o null. */
    static String destinoEnlace(String instr) {
        String s = instr.trim();
        if (!s.regionMatches(true, 0, "HYPERLINK", 0, 9)) return null;
        s = s.substring(9);
        String url = null;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                // Modificador: \l (marcador interno) descarta el enlace; los demás llevan argumento.
                if (i + 1 < s.length() && s.charAt(i + 1) == 'l') return null;
                i += 2;
                continue;
            }
            if (c == '"') {
                int f = s.indexOf('"', i + 1);
                if (f < 0) f = s.length();
                if (url == null) url = s.substring(i + 1, f);
                i = f + 1;
                continue;
            }
            if (!Character.isWhitespace(c)) {
                int f = i;
                while (f < s.length() && !Character.isWhitespace(s.charAt(f))) f++;
                if (url == null) url = s.substring(i, f);
                i = f;
                continue;
            }
            i++;
        }
        if (url == null) return null;
        url = url.trim();
        String min = url.toLowerCase(Locale.ROOT);
        if (min.startsWith("http://") || min.startsWith("https://") || min.startsWith("mailto:")) return url;
        if (min.startsWith("www.")) return "http://" + url;
        return null;
    }

    /** Caracteres de las fuentes de símbolos (Symbol, Wingdings) que tienen equivalente común. */
    private static char simbolo(int x) {
        int c = x >= 0xF000 && x <= 0xF0FF ? x - 0xF000 : x;
        switch (c) {
            case 0xB7: case 0x9F: case 0x6C: return '•';
            case 0xA7: case 0x6E: return '▪';
            case 0xFC: return '✓';
            case 0xFB: return '✗';
            case 0xD8: return '➢';
            case 0xE0: return '→';
            default: return c >= 0x20 && c < 0x7F || c >= 0xA0 && x < 0xF000 ? (char) c : '•';
        }
    }

    /** Clave del formato visible de un CHP (dos tramos con la misma clave se escriben juntos). */
    private String clave(Chp f, boolean titulo) {
        StringBuilder s = new StringBuilder();
        s.append(f.b ? 'b' : '-').append(f.i ? 'i' : '-').append(f.subrayado != 0 ? 'u' : '-')
                .append(f.s ? 's' : '-').append(f.iss == 1 ? '^' : f.iss == 2 ? '_' : '-').append('|');
        if (f.color >= 0) s.append("color:#").append(hex(f.color)).append(';');
        if (f.resaltado >= 0) s.append("background-color:#").append(hex(f.resaltado)).append(';');
        if (!titulo && f.hps != hpsBase) {
            double em = Math.max(0.5, Math.min(4, f.hps / (double) hpsBase));
            if (Math.abs(em - 1) > 0.01) s.append("font-size:").append(Html.px(em)).append("em;");
        }
        if (f.mayus) s.append("text-transform:uppercase;");
        else if (f.versalitas) s.append("font-variant:small-caps;");
        return s.toString();
    }

    /** Fondo de celda o párrafo; sobre fondos oscuros el color automático del texto es blanco. */
    private static String fondo(int rgb) {
        int luz = 299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF);
        return "background:#" + hex(rgb) + (luz < 128_000 ? ";color:#fff;" : ";");
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    private static void volcar(StringBuilder t, Chp f, String clave, StringBuilder sb) {
        if (t.length() == 0) return;
        if (f == null || clave == null) {
            Html.esc(sb, t.toString());
            t.setLength(0);
            return;
        }
        int barra = clave.indexOf('|');
        String css = clave.substring(barra + 1);
        boolean b = clave.charAt(0) == 'b', i = clave.charAt(1) == 'i', u = clave.charAt(2) == 'u',
                s = clave.charAt(3) == 's';
        char v = clave.charAt(4);
        if (css.length() > 0) sb.append("<span style=\"").append(css).append("\">");
        if (b) sb.append("<b>");
        if (i) sb.append("<i>");
        if (u) sb.append("<u>");
        if (s) sb.append("<s>");
        if (v == '^') sb.append("<sup>");
        if (v == '_') sb.append("<sub>");
        Html.esc(sb, t.toString());
        if (v == '_') sb.append("</sub>");
        if (v == '^') sb.append("</sup>");
        if (s) sb.append("</s>");
        if (u) sb.append("</u>");
        if (i) sb.append("</i>");
        if (b) sb.append("</b>");
        if (css.length() > 0) sb.append("</span>");
        t.setLength(0);
    }

    // --- Imágenes ---

    private void imagenEnLinea(Chp chp, StringBuilder out) {
        if (chp.ole) {
            out.append("<span class=nota>[Objeto incrustado]</span>");
            return;
        }
        if (chp.datos || chp.pic < 0) return;
        img(imagenes.enLinea(chp.pic), out);
    }

    private boolean imagenFlotante(int cp, StringBuilder out) {
        String[] im = imagenes.flotante(cp);
        if (im == null) return false;
        img(im, out);
        return true;
    }

    private static void img(String[] im, StringBuilder out) {
        if (im == null || im[0] == null) {
            out.append("<span class=nota>[imagen]</span>");
            return;
        }
        out.append("<img src=\"").append(im[0]).append('"');
        if (im[1] != null) out.append(" style=\"width:").append(im[1]).append("px\"");
        out.append('>');
    }
}
