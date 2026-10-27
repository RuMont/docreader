package com.docreader.epub;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Convierte cada capítulo del libro en una página HTML autónoma y segura: sin scripts, sin
 * formularios, sin marcos y sin ninguna referencia que no sea una URI data:. Las imágenes,
 * hojas de estilo y fuentes se toman del propio libro, con un presupuesto de memoria.
 */
final class Capitulos {
    /** Tamaño máximo de un documento XHTML. */
    private static final int MAX_DOCUMENTO = 16 * 1024 * 1024;
    private static final int MAX_HOJA = 4 * 1024 * 1024;
    /** Presupuesto de imágenes por capítulo (bytes sin codificar) y máximo por imagen. */
    static final long MAX_IMAGENES = 32L * 1024 * 1024;
    private static final int MAX_IMAGEN = 20 * 1024 * 1024;
    /** Fuentes: máximo por archivo y en total por capítulo. */
    private static final int MAX_FUENTE = 3 * 1024 * 1024;
    private static final long MAX_FUENTES = 8L * 1024 * 1024;

    static final String CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; "
            + "font-src data:; form-action 'none'; base-uri 'none'";

    /** Estilo del lector, antes que el del libro: márgenes cómodos y texto legible en el móvil. */
    static final String LECTOR =
            "html{-webkit-text-size-adjust:100%;text-size-adjust:100%;margin:0;padding:12px 18px 56px;"
            + "background:#fff;color:#1b1b1b}"
            + "body{margin:0;line-height:1.6;overflow-wrap:break-word;word-wrap:break-word;"
            + "-webkit-hyphens:auto;hyphens:auto}"
            + "a{color:#1a5fb4}"
            + "table{border-collapse:collapse}"
            + ".epub-sin-imagen{color:#777;font-style:italic}"
            + ".epub-imagen{text-align:center;margin:0}"
            + ".epub-imagen img{max-height:calc(100vh - 80px);object-fit:contain}"
            + ".epub-aviso{color:#777;font-style:italic;text-align:center;margin:3em 1em;font-family:sans-serif}";

    /** Ajustes que se imponen al libro para que nada se salga de la pantalla. */
    static final String FORZADO =
            "img,video,canvas{max-width:100%!important;box-sizing:border-box}"
            + ":where(img[height]){height:auto}"
            + "svg{max-width:100%!important}"
            + "body>svg:only-child,body>div:only-child>svg:only-child"
            + "{display:block;margin:0 auto;max-height:calc(100vh - 80px)}"
            + "pre{white-space:pre-wrap!important;overflow-wrap:break-word}"
            + "table{max-width:100%}"
            + "body{max-width:100%!important;box-sizing:border-box}";

    /** Modo noche: fondo oscuro y texto claro; las imágenes no se tocan. */
    static final String NOCHE =
            ":root{color-scheme:dark}"
            + "html:root,:root body{background:#121212!important;color:#e3e3e3!important}"
            + "*:not(img):not(svg):not(svg *){background-color:transparent!important;color:inherit!important;"
            + "border-color:#4a4a4a!important;text-shadow:none!important}"
            + ":root a:link,:root a:visited,:root a *{color:#8ab4f8!important}"
            + ".epub-sin-imagen,.epub-aviso{color:#9a9a9a!important}";

    /** Un capítulo listo para cargar en el WebView. */
    static final class Pagina {
        final int seccion;
        final String ruta;
        private final String antes, estilos, cuerpo;

        Pagina(int seccion, String ruta, String antes, String estilos, String cuerpo) {
            this.seccion = seccion;
            this.ruta = ruta;
            this.antes = antes;
            this.estilos = estilos;
            this.cuerpo = cuerpo;
        }

        /** Tamaño aproximado en caracteres, para la caché. */
        long peso() {
            return (long) antes.length() + estilos.length() + cuerpo.length();
        }

        String html(boolean noche) {
            StringBuilder sb = new StringBuilder((int) Math.min(Integer.MAX_VALUE - 16, peso() + 4096));
            sb.append(antes)
                    .append("<style>").append(LECTOR).append("</style>")
                    .append(estilos)
                    .append("<style>").append(FORZADO).append("</style>");
            if (noche) sb.append("<style>").append(NOCHE).append("</style>");
            sb.append("</head>").append(cuerpo);
            return sb.toString();
        }
    }

    private static final Set<String> VACIOS = conjunto("area", "base", "br", "col", "embed", "hr", "img",
            "input", "keygen", "link", "meta", "param", "source", "track", "wbr", "basefont", "frame", "isindex");
    /** Se eliminan con todo su contenido. */
    private static final Set<String> FUERA = conjunto("script", "iframe", "object", "applet", "frameset",
            "noembed", "noframes", "select", "textarea", "button", "template", "title", "datalist", "dialog",
            "portal", "fencedframe", "xml");
    /** Se elimina solo la etiqueta; su contenido se conserva. */
    private static final Set<String> SIN_ETIQUETA = conjunto("form", "html", "body", "noscript", "audio",
            "video", "label", "optgroup", "option", "fieldset", "legend", "font", "blink", "marquee");
    /** Elementos que pueden aparecer en <head>; cualquier otro marca el inicio del cuerpo. */
    private static final Set<String> DE_CABECERA = conjunto("html", "head", "meta", "title", "link", "style",
            "script", "base", "noscript");
    private static final Set<String> ATR_FUERA = conjunto("srcset", "sizes", "formaction", "action", "ping",
            "background", "poster", "data", "codebase", "manifest", "target", "autofocus", "contenteditable",
            "http-equiv", "integrity", "crossorigin", "referrerpolicy", "loading", "download", "lowsrc",
            "dynsrc", "archive", "classid", "xml:base", "is", "slot", "popover", "popovertarget");

    private final Archivo zip;
    private final Libro libro;

    Capitulos(Archivo zip, Libro libro) {
        this.zip = zip;
        this.libro = libro;
    }

    /** Prepara la sección i. Nunca lanza excepciones: si algo falla, la página lo explica. */
    Pagina preparar(int i) {
        Libro.Seccion s = libro.secciones.get(i);
        try {
            return convertir(i, s);
        } catch (OutOfMemoryError e) {
            return aviso(i, s.ruta, "Este capítulo es demasiado grande para mostrarlo en este dispositivo.");
        } catch (RuntimeException e) {
            return aviso(i, s.ruta, "Este capítulo está dañado y no se puede mostrar.");
        }
    }

    private Pagina aviso(int i, String ruta, String texto) {
        return new Pagina(i, ruta, cabecera(null), "",
                "<body><p class=epub-aviso>" + Marcado.esc(texto) + "</p></body></html>");
    }

    private static String cabecera(String atributosHtml) {
        return "<!DOCTYPE html><html" + (atributosHtml == null ? "" : atributosHtml) + "><head><meta charset=utf-8>"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">"
                + "<meta name=viewport content=\"width=device-width, initial-scale=1\">";
    }

    private Pagina convertir(int i, Libro.Seccion s) {
        Presupuesto p = new Presupuesto();
        String tipo = s.tipo.toLowerCase(Locale.ROOT);
        String ext = Rutas.extension(s.ruta);
        boolean svg = tipo.equals("image/svg+xml") || ext.equals("svg");
        if (!svg && (tipo.startsWith("image/") || (!Libro.esDocumento(s.tipo, s.ruta) && !ext.isEmpty()
                && Arrays.asList("jpg", "jpeg", "png", "gif", "webp").contains(ext)))) {
            // Una imagen suelta en el orden de lectura (cómics, portadas).
            String d = p.datos(s.ruta);
            String cuerpo = d == null
                    ? "<body><p class=epub-aviso>No se puede mostrar esta imagen.</p></body></html>"
                    : "<body><div class=epub-imagen><img alt=\"\" src=\"" + d + "\"></div></body></html>";
            return new Pagina(i, s.ruta, cabecera(atrIdioma(null, null)), "", cuerpo);
        }
        long tam = zip.tamano(s.ruta);
        if (tam > MAX_DOCUMENTO) return aviso(i, s.ruta, "Este capítulo es demasiado grande para mostrarlo.");
        byte[] b = zip.leer(s.ruta, MAX_DOCUMENTO);
        if (b == null) return aviso(i, s.ruta, "Este capítulo está dañado o falta en el libro.");
        return convertirDocumento(i, s.ruta, Marcado.texto(b), p);
    }

    private Pagina convertirDocumento(int i, String ruta, String texto, Presupuesto p) {
        Marcado m = new Marcado(texto);
        StringBuilder estilos = new StringBuilder();
        StringBuilder cuerpo = new StringBuilder(texto.length() + 1024);
        String idiomaHtml = null, dirHtml = null, claseHtml = null;
        boolean enCuerpo = false;
        int extranjero = 0; // profundidad dentro de <svg> o <math>
        while (m.siguiente() != Marcado.FIN) {
            switch (m.tipo) {
                case Marcado.TEXTO:
                    if (!enCuerpo) {
                        if (m.texto.trim().isEmpty()) break;
                        enCuerpo = true;
                        abrirCuerpo(cuerpo, null, ruta, p);
                    }
                    if (m.crudo) Marcado.esc(cuerpo, m.texto);
                    else textoSeguro(cuerpo, m.texto);
                    break;
                case Marcado.ABRE: {
                    String l = m.local;
                    String prefijo = prefijo(m.nombre);
                    if (l.equals("html")) {
                        if (idiomaHtml == null) idiomaHtml = idioma(m);
                        if (dirHtml == null) dirHtml = m.atr("dir");
                        if (claseHtml == null) claseHtml = m.atr("class");
                        break;
                    }
                    if (l.equals("style")) {
                        if (m.autoCierre) break;
                        String css = m.textoDentro();
                        String media = m.atr("media");
                        anadirEstilo(estilos, Css.hoja(css, ruta, p), media);
                        break;
                    }
                    if (l.equals("link")) {
                        String rel = m.atr("rel");
                        String href = m.atr("href");
                        if (rel != null && href != null && (" " + rel.toLowerCase(Locale.ROOT) + " ").contains(" stylesheet ")
                                && !rel.toLowerCase(Locale.ROOT).contains("alternate")) {
                            String r = Rutas.ruta(ruta, href);
                            String css = r == null ? null : p.hoja(r);
                            if (css != null) anadirEstilo(estilos, Css.hoja(css, r, p), m.atr("media"));
                        }
                        break;
                    }
                    if (!enCuerpo) {
                        if (l.equals("body")) {
                            enCuerpo = true;
                            abrirCuerpo(cuerpo, m, ruta, p);
                            break;
                        }
                        if (DE_CABECERA.contains(l)) {
                            if (FUERA.contains(l)) m.saltar();
                            break;
                        }
                        enCuerpo = true;
                        abrirCuerpo(cuerpo, null, ruta, p);
                    }
                    if (l.equals("body") || l.equals("head")) break;
                    if ("epub".equalsIgnoreCase(prefijo)) {
                        // epub:switch → se muestra epub:default y se descartan los epub:case.
                        if (l.equals("case")) m.saltar();
                        break;
                    }
                    if (FUERA.contains(l) || l.equals("meta") || l.equals("base") || l.equals("input")
                            || l.equals("embed") || l.equals("source") || l.equals("track") || l.equals("param")
                            || l.equals("keygen") || l.equals("frame")) {
                        if (!VACIOS.contains(l)) m.saltar();
                        break;
                    }
                    if (extranjero == 0 && SIN_ETIQUETA.contains(l)) break;
                    if (!nombreValido(m.nombre)) break;
                    if (l.equals("img") && extranjero == 0) {
                        imagen(cuerpo, m, ruta, p);
                        break;
                    }
                    boolean foraneo = l.equals("svg") || l.equals("math");
                    if (foraneo && !m.autoCierre) extranjero++;
                    String nombre = sinPrefijo(m.nombre);
                    cuerpo.append('<').append(nombre);
                    atributos(cuerpo, m, ruta, p, extranjero > 0 || foraneo);
                    if (VACIOS.contains(l) && extranjero == 0) {
                        cuerpo.append('>');
                    } else if (m.autoCierre) {
                        if (extranjero > 0 || foraneo) cuerpo.append("/>");
                        else cuerpo.append("></").append(nombre).append('>');
                    } else {
                        cuerpo.append('>');
                    }
                    break;
                }
                case Marcado.CIERRA: {
                    String l = m.local;
                    if (!enCuerpo) break;
                    if (l.equals("html") || l.equals("body") || l.equals("head")) break;
                    if ("epub".equalsIgnoreCase(prefijo(m.nombre))) break;
                    if (VACIOS.contains(l) && extranjero == 0) break;
                    if (FUERA.contains(l) || l.equals("style") || l.equals("link")) break;
                    if (extranjero == 0 && SIN_ETIQUETA.contains(l)) break;
                    if (!nombreValido(m.nombre)) break;
                    if ((l.equals("svg") || l.equals("math")) && extranjero > 0) extranjero--;
                    cuerpo.append("</").append(sinPrefijo(m.nombre)).append('>');
                    break;
                }
                default:
                    break;
            }
        }
        if (!enCuerpo) abrirCuerpo(cuerpo, null, ruta, p);
        cuerpo.append("</body></html>");
        StringBuilder atrHtml = new StringBuilder(atrIdioma(idiomaHtml, dirHtml));
        if (claseHtml != null && !claseHtml.trim().isEmpty()) {
            atrHtml.append(" class=\"");
            Marcado.esc(atrHtml, claseHtml);
            atrHtml.append('"');
        }
        return new Pagina(i, ruta, cabecera(atrHtml.toString()), estilos.toString(), cuerpo.toString());
    }

    private String atrIdioma(String idioma, String dir) {
        StringBuilder sb = new StringBuilder();
        String l = idioma != null && !idioma.trim().isEmpty() ? idioma : libro.idioma;
        if (l != null && !l.trim().isEmpty()) {
            sb.append(" lang=\"");
            Marcado.esc(sb, l.trim());
            sb.append('"');
        }
        if (dir != null && (dir.equalsIgnoreCase("rtl") || dir.equalsIgnoreCase("ltr") || dir.equalsIgnoreCase("auto"))) {
            sb.append(" dir=").append(dir.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String idioma(Marcado m) {
        String l = m.atr("lang");
        return l != null ? l : m.atr("xml:lang");
    }

    private static void anadirEstilo(StringBuilder estilos, String css, String media) {
        if (css == null || css.trim().isEmpty()) return;
        estilos.append("<style");
        if (media != null && !media.trim().isEmpty()) {
            estilos.append(" media=\"");
            Marcado.esc(estilos, media.trim());
            estilos.append('"');
        }
        estilos.append('>').append(css).append("</style>");
    }

    /** <body> con los atributos útiles del original (clases e id, de los que depende el CSS del libro). */
    private void abrirCuerpo(StringBuilder sb, Marcado m, String ruta, Presupuesto p) {
        sb.append("<body");
        if (m != null) {
            for (String n : new String[] {"id", "class", "dir", "epub:type"}) {
                String v = m.atr(n);
                if (v == null) continue;
                sb.append(' ').append(n).append("=\"");
                Marcado.esc(sb, v);
                sb.append('"');
            }
            String l = idioma(m);
            if (l != null) {
                sb.append(" lang=\"");
                Marcado.esc(sb, l);
                sb.append('"');
            }
            String st = m.atr("style");
            if (st != null) {
                sb.append(" style=\"");
                Marcado.esc(sb, Css.estilo(st, ruta, p));
                sb.append('"');
            }
        }
        sb.append('>');
    }

    /** <img>: la imagen se incrusta como data:; si no se puede, queda su texto alternativo. */
    private void imagen(StringBuilder sb, Marcado m, String ruta, Presupuesto p) {
        String src = m.atr("src");
        String d = null;
        if (src != null) {
            String s = src.trim();
            if (s.regionMatches(true, 0, "data:image/", 0, 11) && s.indexOf('"') < 0) d = s;
            else {
                String r = Rutas.ruta(ruta, s);
                if (r != null) d = p.datos(r);
            }
        }
        if (d == null) {
            String alt = Marcado.limpio(m.atr("alt"));
            if (!alt.isEmpty()) {
                sb.append("<span class=epub-sin-imagen>[");
                Marcado.esc(sb, alt);
                sb.append("]</span>");
            }
            return;
        }
        sb.append("<img");
        atributos(sb, m, ruta, p, false);
        sb.append(" src=\"").append(d).append("\">");
    }

    /** Copia los atributos seguros, adaptando enlaces, imágenes SVG y estilos. */
    private void atributos(StringBuilder sb, Marcado m, String ruta, Presupuesto p, boolean svg) {
        boolean hayLang = m.atr("lang") != null;
        String l = m.local;
        for (int k = 0; k < m.atributos.size(); k += 2) {
            String n = m.atributos.get(k), v = m.atributos.get(k + 1);
            String nl = n.toLowerCase(Locale.ROOT);
            if (!nombreValido(n) || nl.startsWith("on") || ATR_FUERA.contains(nl)) continue;
            if (nl.equals("src") && l.equals("img")) continue; // la pone imagen()
            if (nl.equals("src")) continue; // ningún otro elemento conservado debe cargar nada
            if (nl.equals("xml:lang")) {
                if (hayLang) continue;
                n = "lang";
                nl = "lang";
            }
            if (nl.equals("style")) {
                v = Css.estilo(v, ruta, p);
            } else if (nl.equals("href") || nl.equals("xlink:href")) {
                String t = v.trim();
                if (l.equals("image") || l.equals("feimage")) {
                    String r = Rutas.ruta(ruta, t);
                    String d = t.regionMatches(true, 0, "data:image/", 0, 11) ? t : r == null ? null : p.datos(r);
                    if (d == null) continue;
                    v = d;
                } else if (l.equals("a") || l.equals("area")) {
                    String esquema = t.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u0000-\\u001f]", "");
                    if (esquema.startsWith("javascript:") || esquema.startsWith("vbscript:")
                            || esquema.startsWith("data:") || esquema.startsWith("file:")
                            || esquema.startsWith("content:") || esquema.startsWith("intent:")) continue;
                } else if (!t.startsWith("#")) {
                    continue; // <use>, <link> y similares: solo referencias internas al documento
                }
            } else if (svg && (v.toLowerCase(Locale.ROOT).contains("url(") && !v.contains("url(#"))) {
                continue; // atributos de presentación SVG que apuntan fuera
            }
            sb.append(' ').append(n).append("=\"");
            Marcado.esc(sb, v);
            sb.append('"');
        }
    }

    /** Texto normal: las entidades se dejan (HTML las entiende), pero un "<" suelto se escapa. */
    private static void textoSeguro(StringBuilder sb, String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c != 0) sb.append(c);
        }
    }

    private static boolean nombreValido(String n) {
        if (n == null || n.isEmpty() || n.length() > 64) return false;
        char c = n.charAt(0);
        if (!(Character.isLetter(c) || c == '_')) return false;
        for (int i = 1; i < n.length(); i++) {
            char d = n.charAt(i);
            if (!(Character.isLetterOrDigit(d) || d == '-' || d == '_' || d == ':' || d == '.')) return false;
        }
        return true;
    }

    private static String prefijo(String nombre) {
        int i = nombre == null ? -1 : nombre.indexOf(':');
        return i > 0 ? nombre.substring(0, i) : null;
    }

    private static String sinPrefijo(String nombre) {
        int i = nombre.indexOf(':');
        return i >= 0 ? nombre.substring(i + 1) : nombre;
    }

    private static Set<String> conjunto(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    // --- Recursos incrustados ---

    /** Recursos de un capítulo con su presupuesto de memoria. */
    private final class Presupuesto implements Css.Recursos {
        long imagenes = MAX_IMAGENES, fuentes = MAX_FUENTES;
        final Map<String, String> hechas = new HashMap<>();

        @Override public String datos(String ruta) {
            String real = zip.real(ruta);
            if (real == null) return null;
            String clave = real.toLowerCase(Locale.ROOT);
            if (hechas.containsKey(clave)) return hechas.get(clave);
            String d = crear(real);
            hechas.put(clave, d);
            return d;
        }

        private String crear(String ruta) {
            long tam = zip.tamano(ruta);
            String tipoManifiesto = libro.tipos.get(ruta.toLowerCase(Locale.ROOT));
            boolean fuente = esFuente(ruta, tipoManifiesto);
            if (fuente) {
                if (tam > MAX_FUENTE || tam > fuentes) return null;
                byte[] b = zip.leer(ruta, (int) Math.min(MAX_FUENTE, fuentes));
                if (b == null || b.length == 0) return null;
                b = libro.desofuscar(ruta, b);
                if (b == null) return null;
                String mime = tipoFuente(b);
                if (mime == null) return null;
                fuentes -= b.length;
                return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(b);
            }
            if (tam > MAX_IMAGEN || tam > imagenes) return null;
            byte[] b = zip.leer(ruta, (int) Math.min(MAX_IMAGEN, imagenes));
            if (b == null || b.length == 0) return null;
            String mime = tipoImagen(b, ruta, tipoManifiesto);
            if (mime == null) return null;
            imagenes -= b.length;
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(b);
        }

        @Override public String hoja(String ruta) {
            byte[] b = zip.leer(ruta, MAX_HOJA);
            return b == null ? null : textoCss(b);
        }
    }

    static boolean esFuente(String ruta, String tipo) {
        String e = Rutas.extension(ruta);
        if (e.equals("ttf") || e.equals("otf") || e.equals("woff") || e.equals("woff2") || e.equals("eot")) return true;
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        return t.startsWith("font/") || t.contains("font-") || t.contains("x-font") || t.contains("opentype")
                || t.contains("truetype") || t.contains("woff");
    }

    static String tipoFuente(byte[] b) {
        if (b.length < 4) return null;
        if (b[0] == 'w' && b[1] == 'O' && b[2] == 'F' && b[3] == 'F') return "font/woff";
        if (b[0] == 'w' && b[1] == 'O' && b[2] == 'F' && b[3] == '2') return "font/woff2";
        if (b[0] == 'O' && b[1] == 'T' && b[2] == 'T' && b[3] == 'O') return "font/otf";
        if ((b[0] == 0 && b[1] == 1 && b[2] == 0 && b[3] == 0) || (b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e')) {
            return "font/ttf";
        }
        if (b[0] == 't' && b[1] == 't' && b[2] == 'c' && b[3] == 'f') return "font/collection";
        return null; // EOT u ofuscación sin deshacer: no sirve
    }

    static String tipoImagen(byte[] b, String ruta, String tipo) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length > 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
                && b[8] == 'a' && b[9] == 'v' && b[10] == 'i') return "image/avif";
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        if (t.equals("image/svg+xml") || Rutas.extension(ruta).equals("svg")) return "image/svg+xml";
        String inicio = new String(b, 0, Math.min(b.length, 512), StandardCharsets.ISO_8859_1);
        if (inicio.contains("<svg")) return "image/svg+xml";
        return null; // TIFF y otros formatos que el WebView no dibuja
    }

    /** Texto de una hoja de estilo: BOM, @charset o UTF-8. */
    static String textoCss(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFE || (b[0] & 0xFF) == 0xFF)) return Marcado.texto(b);
        String inicio = new String(b, 0, Math.min(b.length, 128), StandardCharsets.ISO_8859_1);
        if (inicio.startsWith("@charset \"")) {
            int fin = inicio.indexOf('"', 10);
            if (fin > 10) {
                try {
                    Charset c = Charset.forName(inicio.substring(10, fin).trim());
                    if (!c.name().startsWith("UTF-16") && !c.name().startsWith("UTF-32")) return new String(b, c);
                } catch (RuntimeException ignorado) { }
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
