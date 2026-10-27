package com.docreader.oficina;

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
 * Markdown (CommonMark con las extensiones de GitHub: tablas, tachado, listas de tareas y
 * enlaces sin marcar) convertido a HTML sin bibliotecas. El HTML que traiga el documento no se
 * interpreta nunca: se muestra como texto. Cada título recibe un id único para el índice.
 */
final class Markdown {
    static final Set<String> EXTENSIONES = new HashSet<>(Arrays.asList(
            "md", "markdown", "mdown", "mkd", "mkdn", "mdwn", "mdtxt", "mdtext", "rmd", "ronn"));

    /** Profundidad máxima de citas y listas anidadas; por debajo se trata como texto. */
    private static final int MAX_PROF = 24;

    private static final String CSS =
            "body.md{max-width:48em;margin:0 auto;padding:16px 18px 40px}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .5em;scroll-margin-top:10px}"
            + "h1{font-size:1.75em}h2{font-size:1.4em}h3{font-size:1.2em}h4{font-size:1.05em}h5{font-size:.95em}"
            + "h6{font-size:.9em;color:#57606a}"
            + "h1,h2{padding-bottom:.25em;border-bottom:1px solid #d8dee4}"
            + "p{margin:0 0 .8em}"
            + "ul,ol{margin:0 0 .8em;padding-left:1.8em}"
            + "li{margin:.2em 0}li>ul,li>ol{margin-bottom:0}"
            + "li.tarea{list-style:none}li.tarea>input{margin:0 .45em 0 -1.4em;vertical-align:-1px}"
            + "code{font-family:monospace;font-size:.88em;background:#eff1f3;padding:.12em .35em;border-radius:5px}"
            + "pre.cb{background:#f6f8fa;padding:10px 12px;border-radius:6px;overflow-x:auto;line-height:1.45;"
            + "font-size:13.5px;margin:0 0 .9em;tab-size:4}"
            + "pre.cb code{background:none;padding:0;font-size:inherit;border-radius:0}"
            + "pre.htmlb{border-left:3px solid #d0d7de}"
            + "blockquote{margin:0 0 .9em;padding:0 1em;color:#57606a;border-left:4px solid #d0d7de}"
            + "hr{border:0;border-top:2px solid #d8dee4;margin:1.5em 0}"
            + ".tabla{overflow-x:auto;margin:0 0 .9em}"
            + ".tabla table{border-collapse:collapse}"
            + ".tabla th,.tabla td{border:1px solid #d0d7de;padding:5px 12px;vertical-align:top}"
            + ".tabla th{font-weight:600;background:#f6f8fa}"
            + ".tabla tr:nth-child(2n) td{background:#f6f8fa}"
            + ".al{text-align:left}.ac{text-align:center}.ar{text-align:right}"
            + ".img-alt{display:inline-block;border:1px dashed #a8b1bb;border-radius:4px;padding:0 6px;"
            + "color:#57606a;font-size:.9em}"
            + ".enlace{color:#1a5fb4}"
            + "details.indice{border:1px solid #d0d7de;border-radius:8px;padding:8px 14px;margin:0 0 1.2em;"
            + "background:#f6f8fa}"
            + "details.indice summary{font-weight:600}"
            + "details.indice ul{list-style:none;padding-left:0;margin:.5em 0 0}"
            + "details.indice li{margin:.25em 0}"
            + "details.indice a{text-decoration:none}"
            + ".i2{padding-left:1.1em}.i3{padding-left:2.2em}.i4{padding-left:3.3em}.i5{padding-left:4.4em}.i6{padding-left:5.5em}"
            + Codigo.CSS_TOKENS;

    private static final Pattern REFERENCIA = Pattern.compile(
            "^ {0,3}\\[((?:[^\\]\\[\\\\]|\\\\.){1,999})\\]:[ \\t]*(<[^>\\n]*>|\\S+)"
            + "(?:[ \\t]+(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|\\((?:[^)\\\\]|\\\\.)*\\)))?[ \\t]*$");
    private static final Pattern TAREA = Pattern.compile("^\\[([ xX])\\](?:[ \\t]|$)");
    private static final Pattern ETIQUETA_SOLA = Pattern.compile(
            "^</?[A-Za-z][A-Za-z0-9-]*(?:\\s+[A-Za-z_:][\\w.:-]*(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?)*\\s*/?>\\s*$");
    private static final Set<String> BLOQUES_HTML = new HashSet<>(Arrays.asList((
            "address article aside base basefont blockquote body caption center col colgroup dd details dialog "
            + "dir div dl dt fieldset figcaption figure footer form frame frameset h1 h2 h3 h4 h5 h6 head header "
            + "hr html iframe legend li link main menu menuitem nav noframes ol optgroup option p param search "
            + "section summary table tbody td tfoot th thead title tr track ul").split(" ")));

    private final Map<String, String[]> refs = new HashMap<>();
    final List<Html.Titulo> titulos = new ArrayList<>();
    private final Map<String, Integer> usados = new HashMap<>();
    /** Casilla de una tarea pendiente de colocar al principio del primer párrafo del elemento. */
    private String prefijo;

    private Markdown() { }

    static boolean esMime(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        return m.startsWith("text/markdown") || m.startsWith("text/x-markdown") || m.startsWith("text/x-gfm")
                || m.startsWith("text/x-web-markdown");
    }

    static Html.Documento convertir(byte[] datos) {
        return convertir(Texto.decodificar(datos));
    }

    static Html.Documento convertir(String s) {
        boolean cortado = s.length() > Texto.MAX_CARACTERES;
        if (cortado) s = s.substring(0, Texto.MAX_CARACTERES);
        Markdown md = new Markdown();
        StringBuilder cuerpo = new StringBuilder(s.length() * 2 + 1024);
        md.documento(s, cuerpo);
        StringBuilder sb = new StringBuilder(cuerpo.length() + md.titulos.size() * 80 + 512);
        md.indice(sb);
        sb.append(cuerpo);
        if (s.trim().isEmpty()) sb.append("<p class=nota>El archivo está vacío.</p>");
        if (cortado) sb.append("<p class=nota>El archivo es muy largo: se muestra solo el principio.</p>");
        return new Html.Documento(sb.toString(), CSS, Html.VIEWPORT_MOVIL, "md").conIndice(md.titulos);
    }

    /** Índice plegable al principio del documento, si hay al menos tres títulos. */
    private void indice(StringBuilder sb) {
        if (titulos.size() < 3) return;
        int min = 6;
        for (Html.Titulo t : titulos) min = Math.min(min, t.nivel);
        sb.append("<details class=indice><summary>Índice</summary><ul>");
        for (Html.Titulo t : titulos) {
            sb.append("<li class=i").append(t.nivel - min + 1).append("><a href=\"#");
            Html.esc(sb, t.ancla);
            sb.append("\">");
            Html.esc(sb, t.texto.isEmpty() ? "(sin título)" : t.texto);
            sb.append("</a></li>");
        }
        sb.append("</ul></details>");
    }

    private void documento(String s, StringBuilder out) {
        List<String> l = lineas(s);
        recogerReferencias(l);
        int desde = frontMatter(l, out);
        bloques(desde == 0 ? l : l.subList(desde, l.size()), out, false, 0);
    }

    // --- Líneas ---

    private static List<String> lineas(String s) {
        List<String> l = new ArrayList<>();
        int i = 0, n = s.length();
        while (i <= n) {
            int j = i;
            while (j < n && s.charAt(j) != '\n' && s.charAt(j) != '\r') j++;
            l.add(expandir(s, i, j));
            if (j >= n) break;
            if (s.charAt(j) == '\r' && j + 1 < n && s.charAt(j + 1) == '\n') j++;
            i = j + 1;
        }
        return l;
    }

    /** Tabuladores a espacios (paradas cada 4 columnas) y NUL a U+FFFD. */
    private static String expandir(String s, int a, int b) {
        boolean cambiar = false;
        for (int i = a; i < b; i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == 0) {
                cambiar = true;
                break;
            }
        }
        if (!cambiar) return s.substring(a, b);
        StringBuilder sb = new StringBuilder(b - a + 16);
        for (int i = a; i < b; i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                int k = 4 - (sb.length() % 4);
                for (int q = 0; q < k; q++) sb.append(' ');
            } else {
                sb.append(c == 0 ? '\uFFFD' : c);
            }
        }
        return sb.toString();
    }

    private static boolean blanco(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != ' ') return false;
        return true;
    }

    private static int sangria(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static int repetidos(String t, int desde, char c) {
        int i = desde;
        while (i < t.length() && t.charAt(i) == c) i++;
        return i - desde;
    }

    /** Cabecera YAML (--- … ---) de Jekyll, Hugo, etc.: se muestra como bloque de código. */
    private int frontMatter(List<String> l, StringBuilder out) {
        if (l.isEmpty() || !l.get(0).equals("---")) return 0;
        for (int k = 1; k < l.size() && k < 500; k++) {
            String x = l.get(k);
            if (x.equals("---") || x.equals("...")) {
                StringBuilder c = new StringBuilder();
                for (int q = 1; q < k; q++) c.append(l.get(q)).append('\n');
                if (c.length() > 0) c.setLength(c.length() - 1);
                out.append("<pre class=cb><code>");
                Codigo.resaltar(out, c.toString(), Codigo.porEtiqueta("yaml"));
                out.append("</code></pre>");
                return k + 1;
            }
        }
        return 0;
    }

    // --- Referencias [etiqueta]: url "título" ---

    private void recogerReferencias(List<String> l) {
        char valla = 0;
        int largo = 0;
        for (String ln : l) {
            int i = 0;
            while (i < ln.length() && (ln.charAt(i) == ' ' || ln.charAt(i) == '>')) i++;
            String t = ln.substring(i);
            char c = t.isEmpty() ? 0 : t.charAt(0);
            if (c == '`' || c == '~') {
                int k = repetidos(t, 0, c);
                if (valla == 0 && k >= 3) {
                    valla = c;
                    largo = k;
                } else if (valla == c && k >= largo && blanco(t.substring(k))) {
                    valla = 0;
                }
                continue;
            }
            if (valla != 0 || c != '[' || t.indexOf("]:") < 0 || t.length() > 3000) continue;
            Matcher m = REFERENCIA.matcher(t);
            if (!m.matches()) continue;
            String clave = normalizar(m.group(1));
            if (clave.isEmpty() || refs.containsKey(clave)) continue;
            String dest = m.group(2);
            if (dest.startsWith("<") && dest.endsWith(">")) dest = dest.substring(1, dest.length() - 1);
            String titulo = m.group(3);
            if (titulo != null) titulo = desescapar(titulo.substring(1, titulo.length() - 1));
            refs.put(clave, new String[] {desescapar(dest), titulo});
        }
    }

    private static String normalizar(String etiqueta) {
        return etiqueta.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String desescapar(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && esPuntuacionAscii(s.charAt(i + 1))) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // --- Bloques ---

    private void bloques(List<String> l, StringBuilder out, boolean apretada, int prof) {
        int i = 0, n = l.size();
        while (i < n) {
            String ln = l.get(i);
            if (blanco(ln)) {
                i++;
                continue;
            }
            int ind = sangria(ln);
            if (ind >= 4) {
                i = codigoSangrado(l, i, out);
                continue;
            }
            String t = ln.substring(ind);
            char c = t.charAt(0);
            if ((c == '`' || c == '~') && esValla(t)) {
                i = cercado(l, i, ind, out);
                continue;
            }
            if (c == '#') {
                int nivel = nivelAtx(t);
                if (nivel > 0) {
                    titulo(nivel, contenidoAtx(t, nivel), out);
                    i++;
                    continue;
                }
            }
            if (esSeparador(t)) {
                out.append("<hr>");
                i++;
                continue;
            }
            if (c == '>' && prof < MAX_PROF) {
                i = cita(l, i, out, prof);
                continue;
            }
            if (c == '<' && tipoHtml(t) > 0) {
                i = bloqueHtml(l, i, tipoHtml(t), out);
                continue;
            }
            if (prof < MAX_PROF && marca(ln) != null) {
                i = lista(l, i, out, prof);
                continue;
            }
            if (i + 1 < n && t.indexOf('|') >= 0 && esTabla(ln, l.get(i + 1))) {
                i = tabla(l, i, out);
                continue;
            }
            i = parrafo(l, i, out, apretada, prof);
        }
    }

    /** true si la línea empieza un bloque. Con "interrumpe", solo los que pueden cortar un párrafo. */
    private static boolean iniciaBloque(String ln, boolean interrumpe) {
        int ind = sangria(ln);
        if (ind >= 4 || ind >= ln.length()) return false;
        String t = ln.substring(ind);
        char c = t.charAt(0);
        if (c == '>') return true;
        if (c == '#' && nivelAtx(t) > 0) return true;
        if ((c == '`' || c == '~') && esValla(t)) return true;
        if (esSeparador(t)) return true;
        Marca m = marca(ln);
        if (m != null) return !interrumpe || (!blanco(m.resto) && (!m.ordenada || m.inicio == 1));
        if (c == '<') {
            int tipo = tipoHtml(t);
            return tipo > 0 && tipo < 7;
        }
        return false;
    }

    private static boolean esValla(String t) {
        char c = t.charAt(0);
        int k = repetidos(t, 0, c);
        return k >= 3 && (c == '~' || t.indexOf('`', k) < 0);
    }

    private static int nivelAtx(String t) {
        int k = repetidos(t, 0, '#');
        if (k < 1 || k > 6) return 0;
        return t.length() == k || t.charAt(k) == ' ' ? k : 0;
    }

    private static String contenidoAtx(String t, int nivel) {
        String c = t.substring(nivel).trim();
        int e = c.length();
        while (e > 0 && c.charAt(e - 1) == '#') e--;
        if (e == 0) return "";
        if (e < c.length() && c.charAt(e - 1) == ' ') return c.substring(0, e).trim();
        return c;
    }

    private static boolean esSeparador(String t) {
        char tipo = 0;
        int cuenta = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ') continue;
            if (c != '*' && c != '-' && c != '_') return false;
            if (tipo == 0) tipo = c;
            else if (c != tipo) return false;
            cuenta++;
        }
        return cuenta >= 3;
    }

    private void titulo(int nivel, String texto, StringBuilder out) {
        String html = inline(texto);
        String plano = quitarEtiquetas(html).trim();
        String id = ancla(plano);
        titulos.add(new Html.Titulo(nivel, plano, id));
        out.append("<h").append(nivel).append(" id=\"");
        Html.esc(out, id);
        out.append("\">").append(html).append("</h").append(nivel).append('>');
    }

    /** Id al estilo de GitHub: minúsculas, espacios a guiones, sin puntuación; único en el documento. */
    private String ancla(String plano) {
        String l = plano.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.length() && sb.length() < 80; i++) {
            char c = l.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
            else if (c == ' ') sb.append('-');
        }
        String base = sb.length() == 0 ? "seccion" : sb.toString();
        Integer previo = usados.get(base);
        String id = base;
        if (previo != null) {
            int k = previo;
            do {
                id = base + "-" + k++;
            } while (usados.containsKey(id));
            usados.put(base, k);
        } else {
            usados.put(base, 1);
        }
        usados.putIfAbsent(id, 1);
        return id;
    }

    private static int codigoSangrado(List<String> l, int i, StringBuilder out) {
        List<String> buf = new ArrayList<>();
        int j = i;
        while (j < l.size()) {
            String x = l.get(j);
            if (blanco(x)) {
                buf.add(x.length() > 4 ? x.substring(4) : "");
            } else if (sangria(x) >= 4) {
                buf.add(x.substring(4));
            } else {
                break;
            }
            j++;
        }
        while (!buf.isEmpty() && blanco(buf.get(buf.size() - 1))) buf.remove(buf.size() - 1);
        out.append("<pre class=cb><code>");
        for (int k = 0; k < buf.size(); k++) {
            if (k > 0) out.append('\n');
            Html.esc(out, buf.get(k));
        }
        out.append("</code></pre>");
        return j;
    }

    private static int cercado(List<String> l, int i, int ind, StringBuilder out) {
        String t = l.get(i).substring(ind);
        char c = t.charAt(0);
        int k = repetidos(t, 0, c);
        String info = t.substring(k).trim();
        StringBuilder codigo = new StringBuilder();
        int j = i + 1;
        while (j < l.size()) {
            String x = l.get(j);
            int s = sangria(x);
            if (s < 4 && s < x.length()) {
                int r = repetidos(x, s, c);
                if (r >= k && blanco(x.substring(s + r))) {
                    j++;
                    break;
                }
            }
            codigo.append(x, Math.min(ind, s), x.length()).append('\n');
            j++;
        }
        if (codigo.length() > 0) codigo.setLength(codigo.length() - 1);
        String lang = info.isEmpty() ? "" : info.split("[\\s,{}]+", 3)[0];
        if (lang.isEmpty() && info.startsWith("{")) {
            String[] p = info.split("[\\s,{}.]+");
            for (String q : p) if (!q.isEmpty()) { lang = q; break; }
        }
        Codigo.Lenguaje len = Codigo.porEtiqueta(desescapar(lang));
        out.append("<pre class=cb><code>");
        if (len != null) Codigo.resaltar(out, codigo.toString(), len);
        else Html.esc(out, codigo.toString());
        out.append("</code></pre>");
        return j;
    }

    private int cita(List<String> l, int i, StringBuilder out, int prof) {
        List<String> dentro = new ArrayList<>();
        int j = i;
        boolean ultimaTexto = false;
        while (j < l.size()) {
            String x = l.get(j);
            int s = sangria(x);
            if (s < 4 && s < x.length() && x.charAt(s) == '>') {
                String r = x.substring(s + 1);
                if (r.startsWith(" ")) r = r.substring(1);
                dentro.add(r);
                // Se mira el contenido más interno: "> > texto" también admite continuación perezosa.
                String interno = r;
                int q = 0;
                while (q < interno.length() && (interno.charAt(q) == ' ' || interno.charAt(q) == '>') && q < 64) q++;
                if (q > 0 && interno.trim().startsWith(">")) interno = interno.substring(q);
                ultimaTexto = !blanco(interno) && !iniciaBloque(interno, false) && sangria(r) < 4;
                j++;
            } else if (ultimaTexto && !blanco(x) && !iniciaBloque(x, false)) {
                dentro.add(x); // continuación perezosa del párrafo
                j++;
            } else {
                break;
            }
        }
        out.append("<blockquote>");
        bloques(dentro, out, false, prof + 1);
        out.append("</blockquote>");
        return j;
    }

    // --- HTML del documento: se muestra como código, nunca se interpreta ---

    private static int tipoHtml(String t) {
        if (t.length() < 2 || t.charAt(0) != '<') return 0;
        String m = t.length() > 40 ? t.substring(0, 40).toLowerCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT);
        for (String e : new String[] {"script", "pre", "style", "textarea"}) {
            if (m.startsWith("<" + e)) {
                int k = e.length() + 1;
                if (m.length() == k || m.charAt(k) == ' ' || m.charAt(k) == '>') return 1;
            }
        }
        if (m.startsWith("<!--")) return 2;
        if (m.startsWith("<?")) return 3;
        if (m.startsWith("<![cdata[")) return 5;
        if (m.length() > 2 && m.charAt(1) == '!' && Character.isLetter(m.charAt(2))) return 4;
        int i = m.charAt(1) == '/' ? 2 : 1;
        int j = i;
        while (j < m.length() && (Character.isLetterOrDigit(m.charAt(j)) || m.charAt(j) == '-')) j++;
        if (j > i && BLOQUES_HTML.contains(m.substring(i, j))
                && (j == m.length() || m.charAt(j) == ' ' || m.charAt(j) == '>' || m.startsWith("/>", j))) {
            return 6;
        }
        if (t.length() < 2000 && ETIQUETA_SOLA.matcher(t).matches()) return 7;
        return 0;
    }

    private static boolean finHtml(int tipo, String ln) {
        String m = ln.toLowerCase(Locale.ROOT);
        switch (tipo) {
            case 1: return m.contains("</script>") || m.contains("</pre>") || m.contains("</style>") || m.contains("</textarea>");
            case 2: return m.contains("-->");
            case 3: return m.contains("?>");
            case 4: return m.contains(">");
            case 5: return m.contains("]]>");
            default: return false;
        }
    }

    private static int bloqueHtml(List<String> l, int i, int tipo, StringBuilder out) {
        StringBuilder sb = new StringBuilder();
        int j = i;
        while (j < l.size()) {
            String x = l.get(j);
            if (tipo >= 6 && blanco(x)) break;
            sb.append(x).append('\n');
            j++;
            if (tipo < 6 && finHtml(tipo, x)) break;
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        out.append("<pre class=\"cb htmlb\"><code>");
        Codigo.resaltar(out, sb.toString(), Codigo.porEtiqueta("html"));
        out.append("</code></pre>");
        return j;
    }

    // --- Listas ---

    private static final class Marca {
        boolean ordenada;
        char car;
        int inicio = 1, ancho;
        String resto;
    }

    private static Marca marca(String ln) {
        int ind = sangria(ln);
        int n = ln.length();
        if (ind >= 4 || ind >= n) return null;
        int i = ind;
        char c = ln.charAt(i);
        Marca m = new Marca();
        if (c == '-' || c == '+' || c == '*') {
            m.car = c;
            i++;
        } else if (c >= '0' && c <= '9') {
            int j = i;
            while (j < n && j - i < 10 && ln.charAt(j) >= '0' && ln.charAt(j) <= '9') j++;
            if (j - i > 9 || j >= n || (ln.charAt(j) != '.' && ln.charAt(j) != ')')) return null;
            m.ordenada = true;
            m.inicio = Integer.parseInt(ln.substring(i, j));
            m.car = ln.charAt(j);
            i = j + 1;
        } else {
            return null;
        }
        if (i < n && ln.charAt(i) != ' ') return null;
        int k = i;
        while (k < n && ln.charAt(k) == ' ') k++;
        if (k >= n) {
            m.ancho = i + 1;
            m.resto = "";
        } else if (k - i >= 5) {
            m.ancho = i + 1;
            m.resto = ln.substring(i + 1);
        } else {
            m.ancho = k;
            m.resto = ln.substring(k);
        }
        return m;
    }

    private static boolean mismaLista(Marca a, Marca b, String ln) {
        return b != null && a.ordenada == b.ordenada && a.car == b.car && !esSeparador(ln.trim());
    }

    private int lista(List<String> l, int i, StringBuilder out, int prof) {
        Marca primera = marca(l.get(i));
        List<List<String>> items = new ArrayList<>();
        boolean suelta = false;
        int n = l.size(), j = i;
        while (j < n) {
            String ln = l.get(j);
            Marca m = marca(ln);
            if (!mismaLista(primera, m, ln)) break;
            List<String> it = new ArrayList<>();
            it.add(m.resto);
            j++;
            boolean vacio = blanco(m.resto), blancoPrevio = false;
            while (j < n) {
                String x = l.get(j);
                if (blanco(x)) {
                    if (vacio && it.size() == 1) break; // un elemento vacío no sigue tras una línea en blanco
                    it.add("");
                    blancoPrevio = true;
                    j++;
                    continue;
                }
                if (sangria(x) >= m.ancho) {
                    it.add(x.substring(m.ancho));
                    blancoPrevio = false;
                    j++;
                    continue;
                }
                if (!blancoPrevio && ultimaEsTexto(it) && !iniciaBloque(x, false)) {
                    it.add(x.trim()); // continuación perezosa
                    j++;
                    continue;
                }
                break;
            }
            int finales = 0;
            while (it.size() > 1 && blanco(it.get(it.size() - 1))) {
                it.remove(it.size() - 1);
                finales++;
            }
            items.add(it);
            if (finales > 0 && j < n && mismaLista(primera, marca(l.get(j)), l.get(j))) suelta = true;
            if (!suelta && sueltaPorDentro(it)) suelta = true;
        }

        String pendiente = prefijo;
        prefijo = null;
        String etiqueta = primera.ordenada ? "ol" : "ul";
        out.append('<').append(etiqueta);
        if (primera.ordenada && primera.inicio != 1) out.append(" start=").append(primera.inicio);
        out.append('>');
        for (List<String> it : items) {
            Matcher t = TAREA.matcher(it.get(0));
            StringBuilder sub = new StringBuilder();
            if (t.find()) {
                it.set(0, it.get(0).substring(t.end()));
                prefijo = "<input type=checkbox disabled" + (t.group(1).equals(" ") ? "" : " checked") + ">";
                out.append("<li class=tarea>");
            } else {
                prefijo = null;
                out.append("<li>");
            }
            bloques(it, sub, !suelta, prof + 1);
            if (prefijo != null) out.append(prefijo);
            prefijo = null;
            out.append(sub).append("</li>");
        }
        out.append("</").append(etiqueta).append('>');
        prefijo = pendiente;
        return j;
    }

    private static boolean ultimaEsTexto(List<String> it) {
        String u = it.get(it.size() - 1);
        if (blanco(u) || sangria(u) >= 4) return false;
        String t = u.trim();
        return !(t.startsWith("```") || t.startsWith("~~~") || nivelAtx(t) > 0);
    }

    /** true si el elemento tiene dos bloques propios separados por una línea en blanco. */
    private static boolean sueltaPorDentro(List<String> it) {
        boolean visto = false, hueco = false, enLista = false;
        char valla = 0;
        int largo = 0;
        for (String x : it) {
            if (blanco(x)) {
                if (visto && valla == 0) hueco = true;
                continue;
            }
            int s = sangria(x);
            String t = x.substring(s);
            if (s < 4 && (t.charAt(0) == '`' || t.charAt(0) == '~')) {
                int k = repetidos(t, 0, t.charAt(0));
                if (valla == 0 && k >= 3) {
                    if (hueco) return true;
                    valla = t.charAt(0);
                    largo = k;
                    visto = true;
                    continue;
                } else if (valla == t.charAt(0) && k >= largo) {
                    valla = 0;
                    continue;
                }
            }
            if (valla != 0) continue;
            boolean esMarca = s < 4 && marca(x) != null;
            if (hueco) {
                // Una línea en blanco entre elementos de una lista anidada no afloja esta lista.
                if (!(enLista && (esMarca || s >= 2))) return true;
            }
            if (s == 0 || esMarca) enLista = esMarca && (s < 2 || enLista);
            hueco = false;
            visto = true;
        }
        return false;
    }

    // --- Tablas (GFM) ---

    private static boolean esTabla(String cabecera, String delim) {
        if (sangria(delim) >= 4 || delim.indexOf('|') < 0 || delim.indexOf('-') < 0) return false;
        List<String> d = celdas(delim);
        for (String c : d) if (!c.matches(":?-+:?")) return false;
        return celdas(cabecera).size() == d.size();
    }

    private static List<String> celdas(String ln) {
        String t = ln.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        List<String> r = new ArrayList<>();
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                c.append('|'); // \| se convierte antes de analizar la celda (también dentro de `código`)
                i++;
            } else if (ch == '\\' && i + 1 < t.length()) {
                c.append(ch).append(t.charAt(++i));
            } else if (ch == '|') {
                r.add(c.toString().trim());
                c.setLength(0);
            } else {
                c.append(ch);
            }
        }
        r.add(c.toString().trim());
        return r;
    }

    private int tabla(List<String> l, int i, StringBuilder out) {
        List<String> cab = celdas(l.get(i));
        List<String> del = celdas(l.get(i + 1));
        int cols = cab.size();
        String[] alin = new String[cols];
        for (int k = 0; k < cols; k++) {
            String d = del.get(k);
            boolean izq = d.startsWith(":"), der = d.endsWith(":");
            alin[k] = izq && der ? " class=ac" : der ? " class=ar" : izq ? " class=al" : "";
        }
        out.append("<div class=tabla><table><thead><tr>");
        for (int k = 0; k < cols; k++) {
            out.append("<th").append(alin[k]).append('>').append(inline(cab.get(k))).append("</th>");
        }
        out.append("</tr></thead>");
        int j = i + 2;
        boolean cuerpo = false;
        while (j < l.size()) {
            String x = l.get(j);
            if (blanco(x) || iniciaBloque(x, false)) break;
            if (!cuerpo) {
                out.append("<tbody>");
                cuerpo = true;
            }
            List<String> fila = celdas(x);
            out.append("<tr>");
            for (int k = 0; k < cols; k++) {
                out.append("<td").append(alin[k]).append('>');
                if (k < fila.size()) out.append(inline(fila.get(k)));
                out.append("</td>");
            }
            out.append("</tr>");
            j++;
        }
        if (cuerpo) out.append("</tbody>");
        out.append("</table></div>");
        return j;
    }

    // --- Párrafos y títulos subrayados ---

    private int parrafo(List<String> l, int i, StringBuilder out, boolean apretada, int prof) {
        int n = l.size(), j = i;
        // Definiciones de referencias al principio del párrafo: ya se recogieron, no se muestran.
        while (j < n && !blanco(l.get(j)) && REFERENCIA.matcher(l.get(j)).matches()) j++;
        if (j > i && (j >= n || blanco(l.get(j)) || iniciaBloque(l.get(j), true))) return j;
        StringBuilder texto = new StringBuilder(l.get(j).trim());
        j++;
        while (j < n) {
            String x = l.get(j);
            if (blanco(x)) break;
            int s = sangria(x);
            if (s < 4) {
                String t = x.substring(s);
                if (t.charAt(0) == '=' && repetidos(t, 0, '=') == t.trim().length()) {
                    titulo(1, texto.toString().trim(), out);
                    return j + 1;
                }
                if (t.charAt(0) == '-' && repetidos(t, 0, '-') == t.trim().length()) {
                    titulo(2, texto.toString().trim(), out);
                    return j + 1;
                }
                if (iniciaBloque(x, true)) break;
                if (t.indexOf('|') >= 0 && j + 1 < n && esTabla(x, l.get(j + 1))) break;
            }
            texto.append('\n').append(x.trim().isEmpty() ? "" : stripInicio(x));
            j++;
        }
        String contenido = texto.toString();
        int fin = contenido.length();
        while (fin > 0 && contenido.charAt(fin - 1) == ' ') fin--;
        contenido = contenido.substring(0, fin);
        if (!apretada) out.append("<p>");
        if (prefijo != null) {
            out.append(prefijo).append(' ');
            prefijo = null;
        }
        out.append(inline(contenido));
        if (!apretada) out.append("</p>");
        return j;
    }

    private static String stripInicio(String x) {
        return x.substring(sangria(x));
    }

    // --- En línea ---

    private String inline(String t) {
        return new EnLinea(t).html();
    }

    static String quitarEtiquetas(String html) {
        StringBuilder sb = new StringBuilder(html.length());
        boolean dentro = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') dentro = true;
            else if (c == '>' && dentro) dentro = false;
            else if (!dentro) sb.append(c);
        }
        return sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&").replace('\n', ' ');
    }

    static boolean esPuntuacionAscii(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }

    private static boolean esPuntuacion(char c) {
        if (c < 128) return esPuntuacionAscii(c);
        switch (Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION: case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION: case Character.MATH_SYMBOL: case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL: case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    private static boolean esEspacio(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Destino de enlace permitido: http(s), mailto, tel o relativo (#ancla). null si no. */
    static String hrefSeguro(String dest) {
        String d = dest.trim();
        int dosPuntos = d.indexOf(':');
        int corte = d.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int k = d.indexOf(c);
            if (k >= 0 && k < corte) corte = k;
        }
        if (dosPuntos > 0 && dosPuntos < corte) {
            String esquema = d.substring(0, dosPuntos).toLowerCase(Locale.ROOT);
            if (!esquema.equals("http") && !esquema.equals("https") && !esquema.equals("mailto") && !esquema.equals("tel")) {
                return null;
            }
        }
        return d.replace(" ", "%20");
    }

    private static final int TEXTO = 0, DELIM = 1, CORCHETE = 2, AUTO = 3;

    private static final class Nodo {
        int tipo;
        String html, plano;
        char car;
        int cuenta, original, fuente;
        boolean abre, cierra, procesado, imagen, activo = true;
        String antes = "", despues = "";

        Nodo(int tipo, String html) {
            this.tipo = tipo;
            this.html = html;
        }
    }

    /** Analizador de una secuencia en línea: código, énfasis, enlaces, imágenes, escapes… */
    private final class EnLinea {
        final String t;
        final int n;
        final List<Nodo> nodos = new ArrayList<>();
        final StringBuilder texto = new StringBuilder();
        final List<Integer> corchetes = new ArrayList<>();
        final Set<Integer> sinCierre = new HashSet<>();

        EnLinea(String t) {
            this.t = t;
            this.n = t.length();
        }

        String html() {
            int i = 0;
            while (i < n) {
                char c = t.charAt(i);
                switch (c) {
                    case '\\':
                        if (i + 1 < n && t.charAt(i + 1) == '\n') {
                            quitarEspaciosFinales();
                            texto.append("<br>\n");
                            i += 2;
                        } else if (i + 1 < n && esPuntuacionAscii(t.charAt(i + 1))) {
                            escapar(t.charAt(i + 1));
                            i += 2;
                        } else {
                            texto.append('\\');
                            i++;
                        }
                        break;
                    case '`':
                        i = codigo(i);
                        break;
                    case '*': case '_': case '~':
                        i = delimitador(i);
                        break;
                    case '!':
                        if (i + 1 < n && t.charAt(i + 1) == '[') {
                            abrirCorchete(i, true);
                            i += 2;
                        } else {
                            texto.append('!');
                            i++;
                        }
                        break;
                    case '[':
                        abrirCorchete(i, false);
                        i++;
                        break;
                    case ']':
                        i = cerrarCorchete(i);
                        break;
                    case '<': {
                        int e = autoenlace(i);
                        if (e > i) {
                            i = e;
                        } else {
                            texto.append("&lt;");
                            i++;
                        }
                        break;
                    }
                    case '&': {
                        int e = entidad(i);
                        if (e > i) {
                            texto.append(t, i, e);
                            i = e;
                        } else {
                            texto.append("&amp;");
                            i++;
                        }
                        break;
                    }
                    case '\n': {
                        int espacios = quitarEspaciosFinales();
                        texto.append(espacios >= 2 ? "<br>\n" : "\n");
                        i++;
                        while (i < n && t.charAt(i) == ' ') i++;
                        break;
                    }
                    default:
                        if ((c == 'h' || c == 'H' || c == 'w' || c == 'W') && puedeEnlaceDesnudo(i)) {
                            int e = enlaceDesnudo(i);
                            if (e > i) {
                                i = e;
                                break;
                            }
                        }
                        escapar(c);
                        i++;
                }
            }
            soltar();
            procesarEnfasis(-1);
            return renderizar(0, nodos.size());
        }

        private void escapar(char c) {
            switch (c) {
                case '&': texto.append("&amp;"); break;
                case '<': texto.append("&lt;"); break;
                case '>': texto.append("&gt;"); break;
                case '"': texto.append("&quot;"); break;
                case '\'': texto.append("&#39;"); break;
                case 0: texto.append('\uFFFD'); break;
                default: texto.append(c);
            }
        }

        private int quitarEspaciosFinales() {
            int k = 0;
            while (texto.length() > 0 && texto.charAt(texto.length() - 1) == ' ') {
                texto.setLength(texto.length() - 1);
                k++;
            }
            return k;
        }

        private void soltar() {
            if (texto.length() > 0) {
                nodos.add(new Nodo(TEXTO, texto.toString()));
                texto.setLength(0);
            }
        }

        private int codigo(int i) {
            int k = repetidos(t, i, '`');
            int cierre = buscarCierreCodigo(i + k, k);
            if (cierre < 0) {
                for (int q = 0; q < k; q++) texto.append('`');
                return i + k;
            }
            String c = t.substring(i + k, cierre).replace('\n', ' ');
            if (c.length() >= 2 && c.charAt(0) == ' ' && c.charAt(c.length() - 1) == ' ' && !c.trim().isEmpty()) {
                c = c.substring(1, c.length() - 1);
            }
            texto.append("<code>");
            Html.esc(texto, c);
            texto.append("</code>");
            return cierre + k;
        }

        private int buscarCierreCodigo(int desde, int k) {
            if (sinCierre.contains(k)) return -1;
            int j = desde;
            while (j < n) {
                int b = t.indexOf('`', j);
                if (b < 0) break;
                int e = b + repetidos(t, b, '`');
                if (e - b == k) return b;
                j = e;
            }
            sinCierre.add(k);
            return -1;
        }

        private int delimitador(int i) {
            char c = t.charAt(i);
            int j = i + repetidos(t, i, c);
            int k = j - i;
            if (c == '~' && k > 2) {
                for (int q = 0; q < k; q++) texto.append('~');
                return j;
            }
            char antes = i == 0 ? '\n' : t.charAt(i - 1);
            char despues = j >= n ? '\n' : t.charAt(j);
            boolean eAntes = esEspacio(antes), eDesp = esEspacio(despues);
            boolean pAntes = esPuntuacion(antes), pDesp = esPuntuacion(despues);
            boolean izq = !eDesp && (!pDesp || eAntes || pAntes);
            boolean der = !eAntes && (!pAntes || eDesp || pDesp);
            boolean abre, cierra;
            if (c == '_') {
                abre = izq && (!der || pAntes);
                cierra = der && (!izq || pDesp);
            } else {
                abre = izq;
                cierra = der;
            }
            if (!abre && !cierra) {
                for (int q = 0; q < k; q++) texto.append(c);
                return j;
            }
            soltar();
            Nodo d = new Nodo(DELIM, null);
            d.car = c;
            d.cuenta = d.original = k;
            d.abre = abre;
            d.cierra = cierra;
            nodos.add(d);
            return j;
        }

        private void abrirCorchete(int i, boolean imagen) {
            soltar();
            Nodo b = new Nodo(CORCHETE, imagen ? "![" : "[");
            b.imagen = imagen;
            b.fuente = i + (imagen ? 2 : 1);
            nodos.add(b);
            corchetes.add(nodos.size() - 1);
        }

        private String dest, titulo;

        private int cerrarCorchete(int i) {
            if (corchetes.isEmpty()) {
                texto.append(']');
                return i + 1;
            }
            int ultimo = corchetes.size() - 1;
            int idx = corchetes.get(ultimo);
            Nodo b = nodos.get(idx);
            if (!b.activo) {
                corchetes.remove(ultimo);
                texto.append(']');
                return i + 1;
            }
            String etiqueta = t.substring(b.fuente, i);
            dest = titulo = null;
            int fin = -1;
            if (i + 1 < n && t.charAt(i + 1) == '(') fin = enlaceEnLinea(i + 2);
            if (fin < 0 && i + 1 < n && t.charAt(i + 1) == '[') {
                int c = t.indexOf(']', i + 2);
                int a = t.indexOf('[', i + 2);
                if (c >= 0 && c - i < 1000 && (a < 0 || a > c)) {
                    String lab = t.substring(i + 2, c);
                    String[] r = refs.get(normalizar(lab.trim().isEmpty() ? etiqueta : lab));
                    if (r != null) {
                        dest = r[0];
                        titulo = r[1];
                        fin = c + 1;
                    }
                }
            }
            if (fin < 0 && etiqueta.length() < 1000) {
                String[] r = refs.get(normalizar(etiqueta));
                if (r != null) {
                    dest = r[0];
                    titulo = r[1];
                    fin = i + 1;
                }
            }
            if (fin < 0) {
                corchetes.remove(ultimo);
                texto.append(']');
                return i + 1;
            }
            soltar();
            procesarEnfasis(idx);
            corchetes.remove(ultimo);
            if (b.imagen) {
                String alt = quitarEtiquetas(renderizar(idx + 1, nodos.size())).trim();
                nodos.subList(idx + 1, nodos.size()).clear();
                b.html = imagen(dest, alt, titulo);
                b.tipo = TEXTO;
            } else {
                for (int k = idx + 1; k < nodos.size(); k++) {
                    Nodo x = nodos.get(k);
                    if (x.tipo == AUTO) x.html = x.plano; // no hay enlaces dentro de enlaces
                }
                String href = hrefSeguro(dest);
                StringBuilder a = new StringBuilder();
                if (href == null) {
                    a.append("<span class=enlace>");
                } else {
                    a.append("<a href=\"");
                    Html.esc(a, href);
                    a.append('"');
                    if (titulo != null) {
                        a.append(" title=\"");
                        Html.esc(a, titulo);
                        a.append('"');
                    }
                    a.append('>');
                }
                b.html = a.toString();
                b.tipo = TEXTO;
                nodos.add(new Nodo(TEXTO, href == null ? "</span>" : "</a>"));
                for (int q : corchetes) {
                    Nodo o = nodos.get(q);
                    if (!o.imagen) o.activo = false;
                }
            }
            return fin;
        }

        /** (destino "título") tras el corchete; devuelve el final o -1. */
        private int enlaceEnLinea(int j) {
            int limite = Math.min(n, j + 8192);
            j = saltarEspacios(j, limite);
            String d;
            if (j < limite && t.charAt(j) == '<') {
                int k = j + 1;
                while (k < limite && t.charAt(k) != '>' && t.charAt(k) != '\n' && t.charAt(k) != '<') {
                    k += t.charAt(k) == '\\' ? 2 : 1;
                }
                if (k >= limite || t.charAt(k) != '>') return -1;
                d = t.substring(j + 1, k);
                j = k + 1;
            } else {
                int k = j, prof = 0;
                while (k < limite) {
                    char c = t.charAt(k);
                    if (c == '\\' && k + 1 < limite && esPuntuacionAscii(t.charAt(k + 1))) {
                        k += 2;
                        continue;
                    }
                    if (c <= ' ') break;
                    if (c == '(') {
                        if (++prof > 32) return -1;
                    } else if (c == ')') {
                        if (prof == 0) break;
                        prof--;
                    }
                    k++;
                }
                if (prof != 0) return -1;
                d = t.substring(j, Math.min(k, n));
                j = k;
            }
            int antes = j;
            j = saltarEspacios(j, limite);
            String tit = null;
            if (j < limite && j > antes && (t.charAt(j) == '"' || t.charAt(j) == '\'' || t.charAt(j) == '(')) {
                char cierre = t.charAt(j) == '(' ? ')' : t.charAt(j);
                int k = j + 1;
                while (k < limite && t.charAt(k) != cierre) k += t.charAt(k) == '\\' ? 2 : 1;
                if (k >= limite) return -1;
                tit = desescapar(t.substring(j + 1, k));
                j = saltarEspacios(k + 1, limite);
            }
            if (j < limite && t.charAt(j) == ')') {
                dest = desescapar(d);
                titulo = tit;
                return j + 1;
            }
            return -1;
        }

        private int saltarEspacios(int j, int limite) {
            boolean salto = false;
            while (j < limite) {
                char c = t.charAt(j);
                if (c == ' ') j++;
                else if (c == '\n' && !salto) {
                    salto = true;
                    j++;
                } else break;
            }
            return j;
        }

        private String imagen(String d, String alt, String tit) {
            StringBuilder sb = new StringBuilder();
            String src = d.trim();
            if (src.regionMatches(true, 0, "data:image/", 0, 11)) {
                sb.append("<img src=\"");
                for (int k = 0; k < src.length(); k++) {
                    char c = src.charAt(k);
                    if (!Character.isWhitespace(c)) escapar(sb, c);
                }
                sb.append("\" alt=\"");
                Html.esc(sb, alt);
                sb.append('"');
                if (tit != null) {
                    sb.append(" title=\"");
                    Html.esc(sb, tit);
                    sb.append('"');
                }
                return sb.append('>').toString();
            }
            // Sin red: las imágenes externas o de otros archivos se sustituyen por su texto alternativo.
            sb.append("<span class=img-alt title=\"");
            Html.esc(sb, src);
            sb.append("\">");
            Html.esc(sb, alt.isEmpty() ? "Imagen" : alt);
            return sb.append("</span>").toString();
        }

        private void escapar(StringBuilder sb, char c) {
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }

        /** &lt;https://…&gt; o &lt;correo@dominio&gt;. */
        private int autoenlace(int i) {
            int k = i + 1;
            while (k < n && k - i < 2048) {
                char c = t.charAt(k);
                if (c == '>' || c == '<' || c <= ' ') break;
                k++;
            }
            if (k >= n || t.charAt(k) != '>' || k == i + 1) return i;
            String u = t.substring(i + 1, k);
            String href;
            if (u.matches("[A-Za-z][A-Za-z0-9+.-]{1,31}:.*")) {
                href = hrefSeguro(u);
                if (href == null) return i;
            } else if (u.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*")) {
                href = "mailto:" + u;
            } else {
                return i;
            }
            enlaceAuto(href, u);
            return k + 1;
        }

        private void enlaceAuto(String href, String visible) {
            soltar();
            StringBuilder a = new StringBuilder("<a href=\"");
            Html.esc(a, href);
            a.append("\">");
            StringBuilder p = new StringBuilder();
            Html.esc(p, visible);
            a.append(p).append("</a>");
            Nodo x = new Nodo(AUTO, a.toString());
            x.plano = p.toString();
            nodos.add(x);
        }

        private boolean puedeEnlaceDesnudo(int i) {
            char p = i == 0 ? ' ' : t.charAt(i - 1);
            return esEspacio(p) || "(*_~\"'".indexOf(p) >= 0;
        }

        /** Enlaces sin marcar (http://, https://, www.) al estilo de GitHub. */
        private int enlaceDesnudo(int i) {
            boolean www = t.regionMatches(true, i, "www.", 0, 4);
            int pref = www ? 4 : t.regionMatches(true, i, "https://", 0, 8) ? 8 : t.regionMatches(true, i, "http://", 0, 7) ? 7 : 0;
            if (pref == 0) return i;
            int k = i;
            while (k < n) {
                char c = t.charAt(k);
                if (esEspacio(c) || c == '<' || c == '[' || c == ']' || c == '`') break;
                k++;
            }
            int abiertos = 0, cerrados = 0;
            for (int q = i; q < k; q++) {
                if (t.charAt(q) == '(') abiertos++;
                else if (t.charAt(q) == ')') cerrados++;
            }
            while (k > i + pref) {
                char c = t.charAt(k - 1);
                if ("?!.,:*_~'\"".indexOf(c) >= 0) {
                    k--;
                } else if (c == ')' && cerrados > abiertos) {
                    k--;
                    cerrados--;
                } else if (c == ';') {
                    int a = k - 2;
                    while (a > i && Character.isLetterOrDigit(t.charAt(a))) a--;
                    if (t.charAt(a) == '&') k = a;
                    else break;
                } else {
                    break;
                }
            }
            if (k <= i + pref) return i;
            String u = t.substring(i, k);
            if (www && u.indexOf('.', 4) < 0 && u.length() == 4) return i;
            enlaceAuto(www ? "http://" + u : u, u);
            return k;
        }

        private int entidad(int i) {
            int j = i + 1;
            if (j < n && t.charAt(j) == '#') {
                j++;
                boolean hex = j < n && (t.charAt(j) == 'x' || t.charAt(j) == 'X');
                if (hex) j++;
                int d0 = j;
                while (j < n && j - d0 < (hex ? 6 : 7) && Character.digit(t.charAt(j), hex ? 16 : 10) >= 0) j++;
                return j > d0 && j < n && t.charAt(j) == ';' ? j + 1 : i;
            }
            int d0 = j;
            if (j >= n || !(t.charAt(j) < 128 && Character.isLetter(t.charAt(j)))) return i;
            while (j < n && j - d0 < 32 && t.charAt(j) < 128 && Character.isLetterOrDigit(t.charAt(j))) j++;
            return j - d0 >= 2 && j < n && t.charAt(j) == ';' ? j + 1 : i;
        }

        /** Algoritmo de énfasis de CommonMark sobre los delimitadores posteriores a "fondo". */
        private void procesarEnfasis(int fondo) {
            List<Nodo> ds = new ArrayList<>();
            for (int k = fondo + 1; k < nodos.size(); k++) {
                Nodo x = nodos.get(k);
                if (x.tipo == DELIM && !x.procesado) ds.add(x);
            }
            int m = ds.size();
            if (m == 0) return;
            int[] prev = new int[m], next = new int[m];
            for (int q = 0; q < m; q++) {
                prev[q] = q - 1;
                next[q] = q + 1;
            }
            int[] suelos = new int[18];
            Arrays.fill(suelos, -1);
            int c = 0;
            while (c < m) {
                Nodo cl = ds.get(c);
                if (!cl.cierra || cl.cuenta == 0) {
                    c = next[c];
                    continue;
                }
                int clave = (cl.car == '*' ? 0 : cl.car == '_' ? 1 : 2) * 6 + (cl.abre ? 3 : 0) + cl.original % 3;
                int o = prev[c];
                boolean hallado = false;
                while (o >= 0 && o > suelos[clave]) {
                    Nodo op = ds.get(o);
                    if (op.car == cl.car && op.abre && op.cuenta > 0) {
                        if (cl.car == '~') {
                            if (op.cuenta == cl.cuenta) {
                                hallado = true;
                                break;
                            }
                        } else if (!((op.cierra || cl.abre) && (op.original + cl.original) % 3 == 0
                                && !(op.original % 3 == 0 && cl.original % 3 == 0))) {
                            hallado = true;
                            break;
                        }
                    }
                    o = prev[o];
                }
                if (hallado) {
                    Nodo op = ds.get(o);
                    int usar;
                    String etiqueta;
                    if (cl.car == '~') {
                        usar = op.cuenta;
                        etiqueta = "del";
                    } else {
                        usar = op.cuenta >= 2 && cl.cuenta >= 2 ? 2 : 1;
                        etiqueta = usar == 2 ? "strong" : "em";
                    }
                    op.cuenta -= usar;
                    cl.cuenta -= usar;
                    op.despues = "<" + etiqueta + ">" + op.despues;
                    cl.antes = cl.antes + "</" + etiqueta + ">";
                    // Los delimitadores entre los dos quedan como texto.
                    next[o] = c;
                    prev[c] = o;
                    if (op.cuenta == 0) {
                        int p = prev[o];
                        if (p >= 0) next[p] = c;
                        prev[c] = p;
                    }
                    if (cl.cuenta == 0) {
                        int nx = next[c], p = prev[c];
                        if (p >= 0) next[p] = nx;
                        if (nx < m) prev[nx] = p;
                        c = nx;
                    }
                } else {
                    suelos[clave] = prev[c];
                    int nx = next[c];
                    if (!cl.abre) {
                        int p = prev[c];
                        if (p >= 0) next[p] = nx;
                        if (nx < m) prev[nx] = p;
                    }
                    c = nx;
                }
            }
            for (Nodo x : ds) x.procesado = true;
        }

        private String renderizar(int desde, int hasta) {
            StringBuilder sb = new StringBuilder();
            for (int k = desde; k < hasta; k++) {
                Nodo x = nodos.get(k);
                if (x.tipo == DELIM) {
                    sb.append(x.antes);
                    for (int q = 0; q < x.cuenta; q++) sb.append(x.car);
                    sb.append(x.despues);
                } else {
                    sb.append(x.html);
                }
            }
            return sb.toString();
        }
    }
}
