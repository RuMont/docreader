package com.docreader.oficina;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hoja de cálculo demasiado grande para mostrarla de una vez (cientos de miles de filas). Se lee
 * en streaming con un analizador propio, mucho más rápido que el XML genérico, y se guarda en
 * forma compacta (unos 11 bytes por celda); luego se muestra por bloques de filas, generando el
 * HTML solo del bloque visible con el mismo aspecto que las hojas normales.
 */
final class HojaGrande {
    final String nombre;
    private final Xlsx libro;

    // Celdas, fila a fila: columna, estilo, tipo y valor (índice en textos compartidos o en "otros"/"numeros").
    private int[] filaNum = new int[1024], filaIni = new int[1024];
    private int filas;
    private short[] col = new short[1 << 16];
    private int[] estilo = new int[1 << 16], valor = new int[1 << 16];
    private byte[] tipo = new byte[1 << 16];
    private int celdas;
    private double[] numeros = new double[1 << 12];
    private int nNumeros;
    private final List<String> otros = new ArrayList<>();
    private final Map<String, Integer> indiceOtros = new HashMap<>();

    final List<int[]> fusiones = new ArrayList<>();
    final Map<Integer, Double> anchos = new HashMap<>();
    int maxFila, maxCol;
    boolean dibujos, columnasRecortadas;
    final int porBloque;

    // Tipos de celda.
    static final byte T_COMPARTIDA = 0, T_NUMERO = 1, T_TEXTO = 2, T_BOOL = 3, T_ERROR = 4, T_FECHA = 5,
            T_NUMERO_TEXTO = 6, T_INLINE = 7;

    private HojaGrande(String nombre, Xlsx libro) {
        this.nombre = nombre;
        this.libro = libro;
        this.porBloque = 1000;
    }

    // --- Lectura ---

    /** Lee la hoja del flujo XML (sin cargarla entera en memoria). */
    static HojaGrande leer(String nombre, Xlsx libro, InputStream in) throws IOException {
        HojaGrande h = new HojaGrande(nombre, libro);
        new Escaner(in, h).hoja();
        h.recortar();
        return h;
    }

    private void nuevaFila(int r) {
        if (filas == filaNum.length) {
            filaNum = Arrays.copyOf(filaNum, filas * 2);
            filaIni = Arrays.copyOf(filaIni, filas * 2);
        }
        filaNum[filas] = r;
        filaIni[filas] = celdas;
        filas++;
    }

    private void celda(int fila, int c, int s, byte t, int v) {
        if (filas == 0 || filaNum[filas - 1] != fila) nuevaFila(fila);
        if (celdas == col.length) {
            int n = celdas + (celdas >> 1);
            col = Arrays.copyOf(col, n);
            estilo = Arrays.copyOf(estilo, n);
            valor = Arrays.copyOf(valor, n);
            tipo = Arrays.copyOf(tipo, n);
        }
        col[celdas] = (short) c;
        estilo[celdas] = s;
        tipo[celdas] = t;
        valor[celdas] = v;
        celdas++;
        maxFila = Math.max(maxFila, fila);
        maxCol = Math.max(maxCol, c);
    }

    private int otro(String s) {
        Integer i = indiceOtros.get(s);
        if (i != null) return i;
        otros.add(s);
        indiceOtros.put(s, otros.size() - 1);
        return otros.size() - 1;
    }

    private int numero(double d) {
        if (nNumeros == numeros.length) numeros = Arrays.copyOf(numeros, nNumeros * 2);
        numeros[nNumeros] = d;
        return nNumeros++;
    }

    /** Ajusta los arrays al tamaño final para liberar memoria. */
    private void recortar() {
        filaNum = Arrays.copyOf(filaNum, filas);
        filaIni = Arrays.copyOf(filaIni, filas);
        col = Arrays.copyOf(col, celdas);
        estilo = Arrays.copyOf(estilo, celdas);
        valor = Arrays.copyOf(valor, celdas);
        tipo = Arrays.copyOf(tipo, celdas);
        numeros = Arrays.copyOf(numeros, nNumeros);
        indiceOtros.clear();
    }

    // --- Bloques ---

    int bloques() { return Math.max(1, (maxFila + porBloque - 1) / porBloque); }

    int bloqueDeFila(int fila) { return Math.max(0, Math.min(bloques() - 1, (fila - 1) / porBloque)); }

    int primeraFila(int b) { return b * porBloque + 1; }

    int ultimaFila(int b) { return Math.min(maxFila, (b + 1) * porBloque); }

    String rotulo(int b) {
        return "Filas " + miles(primeraFila(b)) + "–" + miles(ultimaFila(b)) + " de " + miles(maxFila);
    }

    static String miles(int n) {
        return String.format(Locale.forLanguageTag("es-ES"), "%,d", n).replace(',', ' ').replace('.', ' ');
    }

    /** Primera celda (índice) de la primera fila con número ≥ f. */
    private int filaDesde(int f) {
        int lo = 0, hi = filas;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (filaNum[m] < f) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    /** HTML del bloque b: la misma tabla que una hoja normal, con los números de fila reales. */
    String bloque(int b) {
        int f0 = primeraFila(b), f1 = ultimaFila(b);
        Map<Long, Xlsx.Celda> mapa = new HashMap<>();
        for (int i = filaDesde(f0); i < filas && filaNum[i] <= f1; i++) {
            int fin = i + 1 < filas ? filaIni[i + 1] : celdas;
            for (int k = filaIni[i]; k < fin; k++) {
                Xlsx.Celda ce = libro.celda(tipoTexto(tipo[k]), texto(k), tipo[k] == T_INLINE ? otros.get(valor[k]) : null,
                        estilo[k]);
                mapa.put(((long) filaNum[i] << 16) | col[k], ce);
            }
        }
        StringBuilder sb = new StringBuilder(mapa.size() * 40 + 4096);
        List<int[]> fus = new ArrayList<>();
        for (int[] r : fusiones) {
            if (r[0] >= f0 && r[0] <= f1) fus.add(new int[] {r[0], r[1], Math.min(r[2], f1), r[3]});
        }
        libro.tabla(sb, mapa, fus, anchos, f0, f1, maxCol);
        if (columnasRecortadas) {
            sb.append("<p class=nota>Se muestran solo las primeras ").append(Xlsx.MAX_COLS).append(" columnas.</p>");
        }
        if (dibujos && b == 0) sb.append("<p class=nota>Esta hoja tiene gráficos o imágenes que no se muestran.</p>");
        return sb.toString();
    }

    private static String tipoTexto(byte t) {
        switch (t) {
            case T_COMPARTIDA: return "s";
            case T_TEXTO: return "str";
            case T_BOOL: return "b";
            case T_ERROR: return "e";
            case T_FECHA: return "d";
            case T_INLINE: return "inlineStr";
            default: return "n";
        }
    }

    /** Valor tal como venía en &lt;v&gt; (lo que espera Xlsx.celda). */
    private String texto(int k) {
        switch (tipo[k]) {
            case T_COMPARTIDA: return Integer.toString(valor[k]);
            case T_NUMERO: return Double.toString(numeros[valor[k]]);
            case T_INLINE: return null;
            default: return otros.get(valor[k]);
        }
    }

    // --- Búsqueda ---

    /**
     * Qué bloques contienen el texto (sin distinguir mayúsculas ni acentos). Recorre todas las
     * celdas con su valor ya formateado, igual que se ve. Comprueba "seguir" de vez en cuando
     * para poder cancelarse.
     */
    boolean[] buscar(String termino, java.util.function.BooleanSupplier seguir) {
        String t = plegar(termino);
        boolean[] r = new boolean[bloques()];
        if (t.isEmpty()) return r;
        String[] compartidas = libro.compartidasPlanas();
        for (int i = 0; i < filas; i++) {
            if ((i & 1023) == 0 && !seguir.getAsBoolean()) return null;
            int b = bloqueDeFila(filaNum[i]);
            if (r[b]) continue;
            String fs = Integer.toString(filaNum[i]);
            if (fs.contains(t)) { // el número de fila también se ve (y la búsqueda del visor lo encuentra)
                r[b] = true;
                continue;
            }
            int fin = i + 1 < filas ? filaIni[i + 1] : celdas;
            for (int k = filaIni[i]; k < fin && !r[b]; k++) {
                String v;
                if (tipo[k] == T_COMPARTIDA) {
                    v = valor[k] >= 0 && valor[k] < compartidas.length ? compartidas[valor[k]] : "";
                } else {
                    Xlsx.Celda ce = libro.celda(tipoTexto(tipo[k]), texto(k),
                            tipo[k] == T_INLINE ? otros.get(valor[k]) : null, estilo[k]);
                    v = plegar(Xlsx.textoPlano(ce.html));
                }
                if (v.contains(t)) r[b] = true;
            }
        }
        return r;
    }

    static String plegar(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    // --- Analizador ---

    /**
     * Analizador de &lt;sheetData&gt; a nivel de bytes: reconoce &lt;row&gt;, &lt;c&gt; (con &lt;v&gt;,
     * &lt;f&gt; e &lt;is&gt;&lt;t&gt;), &lt;col&gt;, &lt;mergeCell&gt; y &lt;drawing&gt;, ignora el resto y
     * admite prefijos de espacio de nombres. No expande entidades externas (no hay DTD).
     */
    private static final class Escaner {
        private final InputStream in;
        private final HojaGrande h;
        private final byte[] buf = new byte[1 << 16];
        private int pos, lim;
        private final byte[] etiqueta = new byte[1 << 14];
        private int largoEtiqueta;
        private byte[] texto = new byte[1 << 12];
        private int largoTexto;

        Escaner(InputStream in, HojaGrande h) {
            this.in = in;
            this.h = h;
        }

        private int leer() throws IOException {
            if (pos == lim) {
                lim = in.read(buf, 0, buf.length);
                pos = 0;
                if (lim <= 0) {
                    lim = 0;
                    return -1;
                }
            }
            return buf[pos++] & 0xFF;
        }

        private boolean rellenar() throws IOException {
            lim = in.read(buf, 0, buf.length);
            pos = 0;
            if (lim <= 0) {
                lim = 0;
                return false;
            }
            return true;
        }

        /** Lee hasta el siguiente '<' acumulando el texto; devuelve false al acabar el flujo. */
        private boolean hastaEtiqueta(boolean guardar) throws IOException {
            largoTexto = 0;
            while (true) {
                if (pos >= lim && !rellenar()) return false;
                int p = pos, l = lim;
                byte[] b = buf;
                while (p < l && b[p] != '<') p++;
                if (guardar && p > pos) {
                    int n = p - pos;
                    if (largoTexto + n > texto.length) texto = Arrays.copyOf(texto, Math.max(texto.length * 2, largoTexto + n));
                    System.arraycopy(b, pos, texto, largoTexto, n);
                    largoTexto += n;
                }
                if (p < l) {
                    pos = p + 1;
                    return true;
                }
                pos = l;
            }
        }

        /** Lee la etiqueta (sin '<') hasta '>', respetando comillas. */
        private void leerEtiqueta() throws IOException {
            largoEtiqueta = 0;
            int comilla = 0;
            while (true) {
                if (pos >= lim && !rellenar()) return;
                byte[] b = buf;
                int p = pos, l = lim;
                while (p < l) {
                    byte c = b[p++];
                    if (comilla != 0) {
                        if (c == comilla) comilla = 0;
                    } else if (c == '"' || c == '\'') {
                        comilla = c;
                    } else if (c == '>') {
                        pos = p;
                        return;
                    }
                    if (largoEtiqueta < etiqueta.length) etiqueta[largoEtiqueta++] = c;
                }
                pos = l;
            }
        }

        /** Inicio y fin del nombre local de la etiqueta leída (sin prefijo ni '/'). */
        private int iniNombre, finNombre;
        private boolean cierre;

        private void analizarNombre() {
            int i = 0;
            cierre = largoEtiqueta > 0 && etiqueta[0] == '/';
            if (cierre) i = 1;
            int ini = i;
            while (i < largoEtiqueta) {
                byte b = etiqueta[i];
                if (b == ' ' || b == '/' || b == '\t' || b == '\n' || b == '\r') break;
                if (b == ':') ini = i + 1;
                i++;
            }
            iniNombre = ini;
            finNombre = i;
        }

        /** ¿La etiqueta leída se llama así (nombre local)? Sin crear objetos. */
        private boolean es(byte[] n) {
            if (finNombre - iniNombre != n.length) return false;
            for (int k = 0; k < n.length; k++) if (etiqueta[iniNombre + k] != n[k]) return false;
            return true;
        }

        private boolean autocerrada() {
            return largoEtiqueta > 0 && etiqueta[largoEtiqueta - 1] == '/';
        }

        /** Posición del valor de un atributo (en atrIni/atrFin); false si no está. */
        private int atrIni, atrFin;

        private boolean buscarAtr(byte[] nb) {
            int i = finNombre;
            while (i < largoEtiqueta) {
                while (i < largoEtiqueta && (etiqueta[i] == ' ' || etiqueta[i] == '\t' || etiqueta[i] == '\n'
                        || etiqueta[i] == '\r')) i++;
                int ini = i;
                while (i < largoEtiqueta && etiqueta[i] != '=' && etiqueta[i] != ' ') i++;
                int fin = i, local = ini;
                for (int k = ini; k < fin; k++) if (etiqueta[k] == ':') local = k + 1;
                while (i < largoEtiqueta && etiqueta[i] != '"' && etiqueta[i] != '\'') i++;
                if (i >= largoEtiqueta) return false;
                byte q = etiqueta[i++];
                int iniV = i;
                while (i < largoEtiqueta && etiqueta[i] != q) i++;
                int finV = i++;
                if (fin - local == nb.length) {
                    boolean igual = true;
                    for (int k = 0; k < nb.length; k++) if (etiqueta[local + k] != nb[k]) { igual = false; break; }
                    if (igual) {
                        atrIni = iniV;
                        atrFin = finV;
                        return true;
                    }
                }
            }
            return false;
        }

        private String atr(byte[] nb) {
            if (!buscarAtr(nb)) return null;
            return entidades(new String(etiqueta, atrIni, atrFin - atrIni, StandardCharsets.UTF_8));
        }

        /** Atributo entero sin crear textos; "def" si falta o no es un número. */
        private int atrEntero(byte[] nb, int def) {
            if (!buscarAtr(nb)) return def;
            return entero(etiqueta, atrIni, atrFin, def);
        }

        /** Columna de la referencia del atributo r ("AB12" → 28), sin crear textos; 0 si no hay. */
        private int atrColumna() {
            if (!buscarAtr(R)) return 0;
            int c = 0;
            for (int k = atrIni; k < atrFin; k++) {
                byte b = etiqueta[k];
                if (b == '$') continue;
                if (b >= 'A' && b <= 'Z') c = c * 26 + (b - 'A' + 1);
                else if (b >= 'a' && b <= 'z') c = c * 26 + (b - 'a' + 1);
                else break;
                if (c > 20000) return 0;
            }
            return c;
        }

        private static int entero(byte[] b, int ini, int fin, int def) {
            while (ini < fin && (b[ini] == ' ' || b[ini] == '\n' || b[ini] == '\t' || b[ini] == '\r')) ini++;
            while (fin > ini && (b[fin - 1] == ' ' || b[fin - 1] == '\n' || b[fin - 1] == '\t' || b[fin - 1] == '\r')) fin--;
            if (ini >= fin) return def;
            boolean neg = b[ini] == '-';
            if (neg) ini++;
            long v = 0;
            for (int k = ini; k < fin; k++) {
                int d = b[k] - '0';
                if (d < 0 || d > 9) return def;
                v = v * 10 + d;
                if (v > Integer.MAX_VALUE) return def;
            }
            return (int) (neg ? -v : v);
        }

        private static final byte[] ROW = b("row"), C = b("c"), COL = b("col"), MERGE = b("mergeCell"),
                DRAWING = b("drawing"), LEGACY = b("legacyDrawing"), V = b("v"), IS = b("is"), T = b("t"),
                F = b("f"), R = b("r"), S = b("s"), MIN = b("min"), MAX = b("max"), WIDTH = b("width"),
                HIDDEN = b("hidden"), REF = b("ref");

        private static byte[] b(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

        private String textoLeido() {
            return entidades(new String(texto, 0, largoTexto, StandardCharsets.UTF_8));
        }

        void hoja() throws IOException {
            int fila = 0, ultimaCol = 0;
            while (hastaEtiqueta(false)) {
                leerEtiqueta();
                if (largoEtiqueta == 0 || etiqueta[0] == '?' || etiqueta[0] == '!') continue;
                analizarNombre();
                if (cierre) continue;
                if (es(C)) {
                    int c = atrColumna();
                    if (c <= 0) c = ultimaCol + 1;
                    ultimaCol = c;
                    int s = atrEntero(S, 0);
                    byte t = tipoCelda();
                    if (autocerrada()) continue;
                    celdaContenido(fila <= 0 ? 1 : fila, c, s, t);
                } else if (es(ROW)) {
                    fila = atrEntero(R, fila + 1);
                    ultimaCol = 0;
                } else if (es(COL)) {
                    int a = Math.max(1, atrEntero(MIN, 0));
                    int bmax = Math.min(Xlsx.MAX_COLS, atrEntero(MAX, 0));
                    double w = real(atr(WIDTH));
                    String oculta = atr(HIDDEN);
                    if ("1".equals(oculta) || "true".equals(oculta)) w = 0.5;
                    if (w > 0) for (int k = a; k <= bmax; k++) h.anchos.put(k, Math.min(w, 120) * 7 + 5);
                } else if (es(MERGE)) {
                    int[] r = rango(atr(REF));
                    if (r != null) h.fusiones.add(r);
                } else if (es(DRAWING) || es(LEGACY)) {
                    h.dibujos = true;
                }
            }
        }

        /** Tipo de la celda (atributo t) sin crear textos; -1 = número (sin t). */
        private static final byte SIN_TIPO = -1;

        private byte tipoCelda() {
            if (!buscarAtr(T)) return SIN_TIPO;
            int n = atrFin - atrIni;
            byte a = n > 0 ? etiqueta[atrIni] : 0;
            if (n == 1) {
                switch (a) {
                    case 's': return T_COMPARTIDA;
                    case 'n': return SIN_TIPO;
                    case 'b': return T_BOOL;
                    case 'e': return T_ERROR;
                    case 'd': return T_FECHA;
                    default: return SIN_TIPO;
                }
            }
            if (n == 3 && a == 's') return T_TEXTO;        // str
            if (n == 9 && a == 'i') return T_INLINE;       // inlineStr
            return SIN_TIPO;
        }

        /** Contenido de una &lt;c&gt; hasta su cierre: valor &lt;v&gt; o texto &lt;is&gt;. */
        private void celdaContenido(int fila, int c, int s, byte t) throws IOException {
            String v = null;
            int compartida = -1;
            StringBuilder inline = null;
            boolean enIs = false;
            while (hastaEtiqueta(false)) {
                leerEtiqueta();
                if (largoEtiqueta == 0) continue;
                analizarNombre();
                if (cierre) {
                    if (es(C)) break;
                    if (es(IS)) enIs = false;
                    continue;
                }
                if (es(V) && !autocerrada()) {
                    hastaEtiqueta(true);
                    if (t == T_COMPARTIDA) compartida = entero(texto, 0, largoTexto, -1);
                    else v = textoLeido();
                    leerEtiqueta(); // </v>
                } else if (es(IS)) {
                    enIs = true;
                    inline = new StringBuilder();
                } else if (es(T) && enIs && !autocerrada()) {
                    hastaEtiqueta(true);
                    Html.esc(inline, textoLeido());
                    leerEtiqueta(); // </t>
                } else if (es(F) && !autocerrada()) {
                    // La fórmula se ignora (se muestra el valor calculado): hasta </f>.
                    while (hastaEtiqueta(false)) {
                        leerEtiqueta();
                        analizarNombre();
                        if (cierre && es(F)) break;
                    }
                }
            }
            if (c > Xlsx.MAX_COLS) {
                if (v != null || inline != null || compartida >= 0) h.columnasRecortadas = true;
                return;
            }
            switch (t) {
                case T_COMPARTIDA:
                    if (compartida >= 0) h.celda(fila, c, s, T_COMPARTIDA, compartida);
                    break;
                case T_INLINE:
                    if (inline != null && inline.length() > 0) h.celda(fila, c, s, T_INLINE, h.otro(inline.toString()));
                    break;
                case T_BOOL: case T_ERROR: case T_FECHA:
                    if (v != null) h.celda(fila, c, s, t, h.otro(v));
                    break;
                case T_TEXTO:
                    if (v != null && !v.isEmpty()) h.celda(fila, c, s, T_TEXTO, h.otro(v));
                    break;
                default:
                    if (v == null || v.trim().isEmpty()) break;
                    try {
                        h.celda(fila, c, s, T_NUMERO, h.numero(Double.parseDouble(v.trim())));
                    } catch (NumberFormatException e) {
                        h.celda(fila, c, s, T_NUMERO_TEXTO, h.otro(v));
                    }
            }
        }

        private static int entero(String s, int def) {
            if (s == null) return def;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static double real(String s) {
            if (s == null) return -1;
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** Columna de una referencia "AB12" (28), o 0. */
        private static int columna(String ref) {
            int c = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch == '$') continue;
                if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
                else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
                else break;
                if (c > 20000) return 0;
            }
            return c;
        }

        private static int[] rango(String ref) {
            if (ref == null) return null;
            String[] p = ref.split(":");
            int[] a = celdaRef(p[0]), b = p.length > 1 ? celdaRef(p[1]) : a;
            if (a == null || b == null) return null;
            return new int[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])};
        }

        private static int[] celdaRef(String r) {
            int c = columna(r.trim());
            int i = 0;
            String s = r.trim();
            while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
            int f = entero(s.substring(i), -1);
            return c > 0 && f > 0 ? new int[] {f, c} : null;
        }

        /** Entidades XML predefinidas y numéricas. */
        private static String entidades(String s) {
            if (s.indexOf('&') < 0) return s;
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int pc = c == '&' ? s.indexOf(';', i) : -1;
                if (pc < 0 || pc - i > 10) {
                    sb.append(c);
                    continue;
                }
                String e = s.substring(i + 1, pc);
                String r = null;
                switch (e) {
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "amp": r = "&"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    default:
                        try {
                            if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                        } catch (IllegalArgumentException ignorado) { }
                }
                if (r == null) sb.append(c);
                else {
                    sb.append(r);
                    i = pc;
                }
            }
            return sb.toString();
        }
    }
}
