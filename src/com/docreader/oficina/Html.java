package com.docreader.oficina;

/** Piezas comunes del HTML generado: escape, hojas de estilo y la página completa. */
final class Html {
    private Html() { }

    static final String VIEWPORT_MOVIL = "width=device-width, initial-scale=1";
    static final String NOTA_DANADA =
            "<p class=nota>El archivo está dañado: se muestra solo la parte que se pudo leer.</p>";

    /**
     * Resultado de una conversión: cuerpo HTML, estilos propios y viewport.
     * Los libros de Excel traen además una parte por hoja ({@link #hojas}); se muestra una cada vez.
     */
    static final class Documento {
        final String cuerpo, css, viewport, clase;
        final String[] hojas, cuerposHojas;

        Documento(String cuerpo, String css, String viewport, String clase) {
            this(cuerpo, css, viewport, clase, null, null);
        }

        Documento(String cuerpo, String css, String viewport, String clase,
                  String[] hojas, String[] cuerposHojas) {
            this.cuerpo = cuerpo;
            this.css = css;
            this.viewport = viewport;
            this.clase = clase;
            this.hojas = hojas;
            this.cuerposHojas = cuerposHojas;
        }

        int numHojas() { return hojas == null ? 0 : hojas.length; }

        /** Índice de títulos (vacío si el documento no tiene). Cada título apunta a un id del HTML. */
        java.util.List<Titulo> indice = java.util.Collections.emptyList();

        Documento conIndice(java.util.List<Titulo> l) {
            indice = l == null ? java.util.Collections.emptyList() : l;
            return this;
        }

        /**
         * Estilos para el modo noche. Si es null se invierte la página entera; si no, se usan
         * estos estilos (p. ej. la paleta oscura del visor de código) y no se invierte nada.
         */
        String cssNoche;

        /** Estilos que se añaden con la opción "Ajustar líneas"; null si el documento no la ofrece. */
        String cssAjustar;

        /**
         * Página HTML original para la opción "Ver como página" (archivos .html y .svg); null si no
         * la hay. Se muestra igual de aislada: sin JavaScript, sin red y con la misma CSP.
         */
        String paginaWeb;
    }

    /** Entrada del índice: nivel 1–6, texto visible y el id del elemento en el HTML. */
    static final class Titulo {
        final int nivel;
        final String texto, ancla;

        Titulo(int nivel, String texto, String ancla) {
            this.nivel = nivel;
            this.texto = texto;
            this.ancla = ancla;
        }
    }

    private static final String BASE =
            "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}"
            + "body{margin:0;padding:12px 14px;background:#fff;color:#1b1b1b;font-family:sans-serif;"
            + "font-size:16px;line-height:1.5;overflow-wrap:break-word;min-height:100vh;box-sizing:border-box}"
            + "a{color:#1a5fb4}"
            + "img{max-width:100%;height:auto}"
            + ".nota{color:#6b6b6b;font-style:italic;font-size:14px;margin:12px 0}";

    /**
     * Modo noche: se invierte la página entera (así también se adaptan los colores puestos a
     * mano en el documento) y se vuelven a invertir las imágenes para que se vean normales.
     */
    private static final String NOCHE =
            "html{background:#000}"
            + "body{filter:invert(1) hue-rotate(180deg)}"
            + "img,.im{filter:invert(1) hue-rotate(180deg)}";

    /** Tabla de hoja de cálculo, compartida por XLSX y CSV. */
    static final String CSS_HOJA =
            ".hoja{border-collapse:collapse;font-size:14px;line-height:1.3;background:#fff}"
            + ".hoja td,.hoja th{border:1px solid #d4d4d4;padding:2px 6px;white-space:nowrap;vertical-align:bottom}"
            + ".hoja th{background:#f0f0f0;color:#5a5a5a;font-weight:normal;text-align:center;"
            + "position:sticky;z-index:1;font-size:12px}"
            + ".hoja thead th{top:0;z-index:2}"
            + ".hoja tbody th{left:0;text-align:right}"
            + ".hoja thead th.esq{left:0;z-index:3}"
            + ".hoja td.n{text-align:right}"
            + ".hoja td.c{text-align:center}"
            + ".hoja td.l{text-align:left}"
            + ".hoja td.w{white-space:pre-wrap;min-width:10em;max-width:36em}"
            + ".hoja tr.cab td{font-weight:bold}";

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s == null ? 0 : s.length() + 16);
        esc(sb, s);
        return sb.toString();
    }

    static void esc(StringBuilder sb, String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                case 0: break;
                default: sb.append(c);
            }
        }
    }

    /** Letras de columna de hoja de cálculo: 1 → A, 27 → AA. */
    static String letras(int col) {
        StringBuilder sb = new StringBuilder();
        while (col > 0) {
            int r = (col - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            col = (col - 1) / 26;
        }
        return sb.toString();
    }

    /** Número para CSS con dos decimales como mucho. */
    static String px(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        long r = Math.round(v * 100);
        if (r % 100 == 0) return Long.toString(r / 100);
        return Double.toString(r / 100.0);
    }

    /** Opciones de presentación para {@link #pagina(Documento, boolean, int, int)}. */
    static final int OP_AJUSTAR = 1, OP_COMO_PAGINA = 2;

    /**
     * Política de seguridad de todas las páginas: nada externo, solo estilos en línea e imágenes
     * y fuentes data:. Además, ni &lt;base&gt; ni formularios.
     */
    private static final String CSP = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
            + "img-src data:; style-src 'unsafe-inline'; font-src data:; base-uri 'none'; form-action 'none'\">";

    /** Página HTML completa, con la hoja de estilo clara u oscura. */
    static String pagina(Documento d, boolean noche) {
        return pagina(d, noche, 0, 0);
    }

    /** Igual, pero para libros con varias hojas muestra solo la hoja indicada. */
    static String pagina(Documento d, boolean noche, int hoja) {
        return pagina(d, noche, hoja, 0);
    }

    /** Igual, con opciones de presentación ({@link #OP_AJUSTAR}, {@link #OP_COMO_PAGINA}). */
    static String pagina(Documento d, boolean noche, int hoja, int opciones) {
        if ((opciones & OP_COMO_PAGINA) != 0 && d.paginaWeb != null) return paginaWeb(d.paginaWeb, noche);
        String cuerpo = d.numHojas() > 0 ? d.cuerposHojas[Math.max(0, Math.min(hoja, d.numHojas() - 1))] : d.cuerpo;
        StringBuilder sb = new StringBuilder(cuerpo.length() + d.css.length() + 4096);
        sb.append("<!DOCTYPE html><html lang=es><head><meta charset=utf-8>")
                .append(CSP)
                .append("<meta name=viewport content=\"").append(d.viewport).append("\">")
                .append("<style>").append(BASE).append(d.css);
        if ((opciones & OP_AJUSTAR) != 0 && d.cssAjustar != null) sb.append(d.cssAjustar);
        if (noche) sb.append(d.cssNoche != null ? d.cssNoche : NOCHE);
        sb.append("</style></head><body class=").append(d.clase).append('>')
                .append(cuerpo)
                .append("</body></html>");
        return sb.toString();
    }

    /**
     * Muestra una página HTML del usuario tal cual, pero precedida de la misma CSP (que no se
     * puede relajar desde el propio documento) y con las redirecciones &lt;meta refresh&gt; y
     * las etiquetas &lt;base&gt; desactivadas.
     */
    static String paginaWeb(String html, boolean noche) {
        StringBuilder sb = new StringBuilder(html.length() + 1024);
        sb.append("<!DOCTYPE html><meta charset=utf-8>").append(CSP)
                .append("<meta name=viewport content=\"").append(VIEWPORT_MOVIL).append("\">")
                .append("<style>html{background:#fff}");
        if (noche) sb.append(NOCHE);
        sb.append("</style>");
        neutralizar(sb, html);
        return sb.toString();
    }

    /** Copia el HTML cambiando "&lt;meta http-equiv=refresh" y "&lt;base" por etiquetas inertes. */
    private static void neutralizar(StringBuilder sb, String html) {
        int n = html.length(), desde = 0, i = 0;
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) break;
            if (empiezaEtiqueta(html, lt, "base")) {
                sb.append(html, desde, lt).append("<x-base");
                desde = i = lt + 5;
                continue;
            }
            if (empiezaEtiqueta(html, lt, "meta")) {
                int gt = html.indexOf('>', lt);
                if (gt < 0) break;
                String etiqueta = html.substring(lt, gt).toLowerCase(java.util.Locale.ROOT);
                if (etiqueta.contains("http-equiv") && (etiqueta.contains("refresh") || etiqueta.contains("set-cookie"))) {
                    sb.append(html, desde, lt).append("<x-meta");
                    desde = lt + 5;
                }
                i = gt + 1;
                continue;
            }
            i = lt + 1;
        }
        sb.append(html, desde, n);
    }

    private static boolean empiezaEtiqueta(String s, int lt, String nombre) {
        int fin = lt + 1 + nombre.length();
        if (fin > s.length() || !s.regionMatches(true, lt + 1, nombre, 0, nombre.length())) return false;
        if (fin == s.length()) return true;
        char c = s.charAt(fin);
        return Character.isWhitespace(c) || c == '>' || c == '/';
    }
}
