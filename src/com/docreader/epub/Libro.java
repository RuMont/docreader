package com.docreader.epub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Estructura de un libro EPUB (2 o 3): metadatos, manifiesto, orden de lectura (spine) e índice.
 * No guarda el contenido de los capítulos: eso lo prepara {@link Capitulos} cuando hace falta.
 */
final class Libro {
    static final String MENSAJE_DRM = "Este libro está protegido con DRM y no se puede abrir.";
    private static final int MAX_XML = 8 * 1024 * 1024;

    /** Un documento del orden de lectura. Los no lineales solo se alcanzan por enlaces o índice. */
    static final class Seccion {
        final String ruta, tipo;
        final boolean lineal;

        Seccion(String ruta, String tipo, boolean lineal) {
            this.ruta = ruta;
            this.tipo = tipo == null ? "" : tipo;
            this.lineal = lineal;
        }
    }

    /** Entrada del índice: nivel 0 = primer nivel. La ruta puede ser null (título sin enlace). */
    static final class Entrada {
        final String texto, ruta, fragmento;
        final int nivel;

        Entrada(String texto, String ruta, String fragmento, int nivel) {
            this.texto = texto;
            this.ruta = ruta;
            this.fragmento = fragmento;
            this.nivel = nivel;
        }
    }

    String titulo = "", autor = "", idioma = "", identificador = "";
    final List<Seccion> secciones = new ArrayList<>();
    final List<Entrada> indice = new ArrayList<>();
    /** Ruta en minúsculas → media-type del manifiesto. */
    final Map<String, String> tipos = new HashMap<>();
    /** Ruta en minúsculas → clave XOR de una fuente ofuscada (algoritmo IDPF o Adobe). */
    final Map<String, byte[]> ofuscadas = new HashMap<>();
    private final Map<String, Integer> posicion = new HashMap<>();
    private int lineales;

    private Libro() { }

    // --- Apertura ---

    static Libro abrir(Archivo zip) throws Fallo {
        Libro l = new Libro();
        String opf = l.rutaOpf(zip);
        if (opf == null) {
            throw new Fallo("El archivo no es un libro EPUB válido: no se encuentra la descripción del libro (OPF).");
        }
        List<String[]> cifrados = l.comprobarCifrado(zip);
        Map<String, String[]> manifiesto = new LinkedHashMap<>();
        String[] tocNcx = new String[1];
        List<String[]> spine = new ArrayList<>();
        l.leerOpf(zip, opf, manifiesto, tocNcx, spine);
        l.prepararOfuscadas(cifrados);

        for (String[] it : manifiesto.values()) l.tipos.put(it[0].toLowerCase(Locale.ROOT), it[1]);
        Set<String> vistos = new HashSet<>();
        for (String[] ir : spine) {
            String[] it = manifiesto.get(ir[0]);
            if (it == null || !zip.existe(it[0]) || !mostrable(it[1], it[0])) continue;
            String ruta = zip.real(it[0]);
            if (!vistos.add(ruta.toLowerCase(Locale.ROOT))) continue;
            l.secciones.add(new Seccion(ruta, it[1], !"no".equalsIgnoreCase(ir[1])));
        }
        // Si el spine está vacío o roto, se usan los documentos del manifiesto en su orden.
        boolean sinSpine = l.secciones.isEmpty();
        for (String[] it : manifiesto.values()) {
            if (!esDocumento(it[1], it[0]) || !zip.existe(it[0])) continue;
            String ruta = zip.real(it[0]);
            if (!vistos.add(ruta.toLowerCase(Locale.ROOT))) continue;
            // Documentos fuera del spine: no lineales, pero accesibles desde enlaces.
            l.secciones.add(new Seccion(ruta, it[1], sinSpine));
        }
        if (l.secciones.isEmpty()) {
            for (String r : zip.rutas()) {
                String e = Rutas.extension(r);
                if ((e.equals("xhtml") || e.equals("html") || e.equals("htm")) && vistos.add(r.toLowerCase(Locale.ROOT))) {
                    l.secciones.add(new Seccion(r, "application/xhtml+xml", true));
                }
            }
        }
        if (l.secciones.isEmpty()) throw new Fallo("El libro no tiene ningún contenido que se pueda mostrar.");
        for (Seccion s : l.secciones) if (s.lineal) l.lineales++;
        if (l.lineales == 0) {
            List<Seccion> todas = new ArrayList<>(l.secciones);
            l.secciones.clear();
            for (Seccion s : todas) l.secciones.add(new Seccion(s.ruta, s.tipo, true));
            l.lineales = l.secciones.size();
        }
        for (int i = 0; i < l.secciones.size(); i++) {
            l.posicion.putIfAbsent(l.secciones.get(i).ruta.toLowerCase(Locale.ROOT), i);
        }

        // Índice: primero el de EPUB 3 (nav) y, si no hay o está vacío, el NCX de EPUB 2.
        for (String[] it : manifiesto.values()) {
            if (it.length > 2 && it[2].contains("nav")) {
                try {
                    l.leerNav(zip, it[0]);
                } catch (RuntimeException ignorado) {
                    l.indice.clear();
                }
                if (!l.indice.isEmpty()) break;
            }
        }
        if (l.indice.isEmpty()) {
            String ncx = null;
            if (tocNcx[0] != null && manifiesto.containsKey(tocNcx[0])) ncx = manifiesto.get(tocNcx[0])[0];
            if (ncx == null) {
                for (String[] it : manifiesto.values()) {
                    if ("application/x-dtbncx+xml".equalsIgnoreCase(it[1]) || it[0].toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                        ncx = it[0];
                        break;
                    }
                }
            }
            if (ncx != null) {
                try {
                    l.leerNcx(zip, ncx);
                } catch (RuntimeException ignorado) {
                    l.indice.clear();
                }
            }
        }
        // Las entradas que apuntan fuera del libro no sirven de nada.
        List<Entrada> buenas = new ArrayList<>();
        for (Entrada e : l.indice) {
            if (e.ruta == null || l.buscar(e.ruta) >= 0) buenas.add(e);
        }
        l.indice.clear();
        l.indice.addAll(buenas);
        return l;
    }

    private String rutaOpf(Archivo zip) {
        byte[] b = zip.leer("META-INF/container.xml", MAX_XML);
        if (b != null) {
            Marcado m = new Marcado(Marcado.texto(b));
            String primera = null;
            while (m.siguiente() != Marcado.FIN) {
                if (m.tipo != Marcado.ABRE || !m.local.equals("rootfile")) continue;
                String r = m.atr("full-path");
                if (r == null) continue;
                String t = m.atr("media-type");
                if ((t == null || t.equalsIgnoreCase("application/oebps-package+xml")) && zip.existe(r)) {
                    return zip.real(r);
                }
                if (primera == null && zip.existe(r)) primera = zip.real(r);
            }
            if (primera != null && primera.toLowerCase(Locale.ROOT).endsWith(".opf")) return primera;
        }
        for (String r : zip.rutas()) {
            if (r.toLowerCase(Locale.ROOT).endsWith(".opf")) return r;
        }
        return null;
    }

    /**
     * Mira META-INF/encryption.xml. La ofuscación de fuentes (IDPF y Adobe) se admite; cualquier
     * otro cifrado, o los archivos de licencia de los DRM conocidos, impiden abrir el libro.
     * Devuelve los pares {ruta, algoritmo} de las fuentes ofuscadas.
     */
    private List<String[]> comprobarCifrado(Archivo zip) throws Fallo {
        List<String[]> ofuscadas = new ArrayList<>();
        if (zip.existe("META-INF/sinf.xml") || zip.existe("META-INF/license.lcpl")) throw new Fallo(MENSAJE_DRM);
        byte[] b = zip.leer("META-INF/encryption.xml", MAX_XML);
        if (b == null) {
            if (zip.existe("META-INF/encryption.xml")) throw new Fallo(MENSAJE_DRM);
            return ofuscadas;
        }
        Marcado m = new Marcado(Marcado.texto(b));
        String algoritmo = null;
        boolean drm = false;
        while (m.siguiente() != Marcado.FIN) {
            if (m.tipo == Marcado.ABRE && m.local.equals("encrypteddata")) {
                algoritmo = null;
            } else if (m.tipo == Marcado.ABRE && m.local.equals("encryptionmethod")) {
                // El primer EncryptionMethod de cada EncryptedData es el del contenido.
                if (algoritmo == null) algoritmo = m.atr("Algorithm");
            } else if (m.tipo == Marcado.ABRE && m.local.equals("cipherreference")) {
                String uri = m.atr("URI");
                if (uri == null) continue;
                String a = algoritmo == null ? "" : algoritmo.trim();
                if (a.equals("http://www.idpf.org/2008/embedding") || a.equals("http://ns.adobe.com/pdf/enc#RC")) {
                    ofuscadas.add(new String[] {Rutas.normalizar(Rutas.decodificar(uri)), a});
                } else {
                    drm = true;
                }
            }
        }
        if (drm) throw new Fallo(MENSAJE_DRM);
        if (zip.existe("META-INF/rights.xml") && !ofuscadas.isEmpty()) {
            // Adobe ADEPT: rights.xml junto a contenido cifrado con su propio esquema.
            for (String[] o : ofuscadas) {
                String e = Rutas.extension(o[0]);
                if (!e.equals("ttf") && !e.equals("otf") && !e.equals("woff") && !e.equals("woff2")) {
                    throw new Fallo(MENSAJE_DRM);
                }
            }
        }
        return ofuscadas;
    }

    private void leerOpf(Archivo zip, String opf, Map<String, String[]> manifiesto, String[] toc,
                         List<String[]> spine) throws Fallo {
        byte[] b = zip.leer(opf, MAX_XML);
        if (b == null) throw new Fallo("El libro está dañado: no se puede leer su descripción (OPF).");
        Marcado m = new Marcado(Marcado.texto(b));
        String idUnico = null, primerIdentificador = null;
        Map<String, String> identificadores = new HashMap<>();
        boolean enMetadatos = false;
        while (m.siguiente() != Marcado.FIN) {
            if (m.tipo == Marcado.CIERRA && m.local.equals("metadata")) enMetadatos = false;
            if (m.tipo != Marcado.ABRE) continue;
            switch (m.local) {
                case "package":
                    idUnico = m.atr("unique-identifier");
                    break;
                case "metadata":
                    enMetadatos = !m.autoCierre;
                    break;
                case "title":
                    if (enMetadatos && titulo.isEmpty()) titulo = Marcado.limpio(m.textoDentro());
                    break;
                case "creator":
                    if (enMetadatos && autor.isEmpty()) autor = Marcado.limpio(m.textoDentro());
                    break;
                case "language":
                    if (enMetadatos && idioma.isEmpty()) idioma = Marcado.limpio(m.textoDentro());
                    break;
                case "identifier": {
                    if (!enMetadatos) break;
                    String id = m.atr("id");
                    String v = m.textoDentro().trim();
                    if (primerIdentificador == null) primerIdentificador = v;
                    if (id != null) identificadores.put(id, v);
                    break;
                }
                case "item": {
                    String id = m.atr("id"), href = m.atr("href");
                    if (id == null || href == null) break;
                    String ruta = Rutas.ruta(opf, href);
                    if (ruta == null) break;
                    String tipo = m.atr("media-type");
                    String prop = m.atr("properties");
                    manifiesto.putIfAbsent(id, new String[] {ruta, tipo == null ? "" : tipo.trim(),
                            prop == null ? "" : prop.toLowerCase(Locale.ROOT)});
                    break;
                }
                case "spine":
                    toc[0] = m.atr("toc");
                    break;
                case "itemref": {
                    String idref = m.atr("idref");
                    if (idref != null) spine.add(new String[] {idref, m.atr("linear")});
                    break;
                }
                default:
                    break;
            }
        }
        identificador = idUnico != null && identificadores.containsKey(idUnico)
                ? identificadores.get(idUnico) : primerIdentificador == null ? "" : primerIdentificador;
    }

    /** Calcula la clave de cada fuente ofuscada a partir del identificador único del libro. */
    private void prepararOfuscadas(List<String[]> lista) {
        for (String[] o : lista) {
            byte[] clave = null;
            if (o[1].contains("idpf")) {
                // SHA-1 del identificador sin espacios en blanco; se aplica a los primeros 1040 bytes.
                String id = identificador.replaceAll("[\\u0020\\u0009\\u000D\\u000A]", "");
                try {
                    clave = MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignorado) { }
            } else {
                // Adobe: los 16 bytes del UUID del identificador; se aplica a los primeros 1024 bytes.
                String hex = identificador.toLowerCase(Locale.ROOT).replace("urn:uuid:", "").replaceAll("[^0-9a-f]", "");
                if (hex.length() == 32) {
                    clave = new byte[16];
                    for (int i = 0; i < 16; i++) clave[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                }
            }
            // Sin clave válida la fuente queda marcada (clave vacía) y no se usa.
            ofuscadas.put(o[0].toLowerCase(Locale.ROOT), clave == null ? new byte[0] : clave);
        }
    }

    /** Deshace la ofuscación de una fuente. Devuelve null si no se puede. */
    byte[] desofuscar(String ruta, byte[] datos) {
        byte[] clave = ofuscadas.get(ruta.toLowerCase(Locale.ROOT));
        if (clave == null) return datos;
        if (clave.length == 0) return null;
        byte[] r = datos.clone();
        int n = Math.min(r.length, clave.length == 20 ? 1040 : 1024);
        for (int i = 0; i < n; i++) r[i] ^= clave[i % clave.length];
        return r;
    }

    // --- Índice ---

    /** Documento de navegación de EPUB 3: <nav epub:type="toc"> con listas <ol> anidadas. */
    private void leerNav(Archivo zip, String ruta) {
        byte[] b = zip.leer(ruta, MAX_XML);
        if (b == null) return;
        String texto = Marcado.texto(b);
        // Se busca primero el nav de tipo toc; si no hay, se usa el primero.
        for (int intento = 0; intento < 2 && indice.isEmpty(); intento++) {
            Marcado m = new Marcado(texto);
            int navs = 0;
            boolean dentro = false;
            int nivelNav = 0, ol = 0;
            boolean conTitulo = false;
            while (m.siguiente() != Marcado.FIN) {
                if (m.tipo == Marcado.ABRE && m.local.equals("nav") && !m.autoCierre) {
                    if (dentro) {
                        nivelNav++;
                        continue;
                    }
                    String tipo = m.atrLocal("type");
                    String rol = m.atr("role");
                    boolean esToc = (tipo != null && (" " + tipo + " ").contains(" toc "))
                            || "doc-toc".equals(rol);
                    if (intento == 0 ? esToc : navs == 0) {
                        dentro = true;
                        nivelNav = 1;
                    }
                    navs++;
                    continue;
                }
                if (!dentro) continue;
                if (m.tipo == Marcado.CIERRA && m.local.equals("nav")) {
                    if (--nivelNav == 0) break;
                    continue;
                }
                if (m.tipo == Marcado.ABRE && m.local.equals("ol") && !m.autoCierre) ol++;
                else if (m.tipo == Marcado.CIERRA && m.local.equals("ol")) ol = Math.max(0, ol - 1);
                else if (m.tipo == Marcado.ABRE && m.local.equals("li")) conTitulo = false;
                else if (m.tipo == Marcado.ABRE && !conTitulo && ol > 0
                        && (m.local.equals("a") || m.local.equals("span"))) {
                    String href = m.local.equals("a") ? m.atr("href") : null;
                    String titulo = m.atr("title");
                    String t = Marcado.limpio(m.textoDentro());
                    if (t.isEmpty() && titulo != null) t = Marcado.limpio(titulo);
                    if (t.isEmpty()) continue;
                    conTitulo = true;
                    String[] r = href == null ? null : Rutas.resolver(ruta, href);
                    if (href != null && r == null) continue; // enlace externo: no es parte del libro
                    indice.add(new Entrada(t, r == null ? null : r[0], r == null ? null : r[1], Math.max(0, ol - 1)));
                }
            }
        }
    }

    /** Índice de EPUB 2 (toc.ncx): navPoint anidados con navLabel/text y content src. */
    private void leerNcx(Archivo zip, String ruta) {
        byte[] b = zip.leer(ruta, MAX_XML);
        if (b == null) return;
        Marcado m = new Marcado(Marcado.texto(b));
        int nivel = 0;
        boolean enMapa = false;
        List<String> etiquetas = new ArrayList<>();
        List<Boolean> hechos = new ArrayList<>();
        while (m.siguiente() != Marcado.FIN) {
            if (m.tipo == Marcado.ABRE && m.local.equals("navmap")) enMapa = true;
            else if (m.tipo == Marcado.CIERRA && m.local.equals("navmap")) enMapa = false;
            if (!enMapa) continue;
            if (m.tipo == Marcado.ABRE && m.local.equals("navpoint") && !m.autoCierre) {
                nivel++;
                etiquetas.add(null);
                hechos.add(false);
            } else if (m.tipo == Marcado.CIERRA && m.local.equals("navpoint") && nivel > 0) {
                nivel--;
                etiquetas.remove(etiquetas.size() - 1);
                hechos.remove(hechos.size() - 1);
            } else if (m.tipo == Marcado.ABRE && m.local.equals("text") && nivel > 0
                    && etiquetas.get(nivel - 1) == null) {
                etiquetas.set(nivel - 1, Marcado.limpio(m.textoDentro()));
            } else if (m.tipo == Marcado.ABRE && m.local.equals("content") && nivel > 0 && !hechos.get(nivel - 1)) {
                hechos.set(nivel - 1, true);
                String src = m.atr("src");
                String t = etiquetas.get(nivel - 1);
                String[] r = src == null ? null : Rutas.resolver(ruta, src);
                if (t == null || t.isEmpty()) t = "Sección " + (indice.size() + 1);
                indice.add(new Entrada(t, r == null ? null : r[0], r == null ? null : r[1], nivel - 1));
            }
        }
    }

    // --- Consultas ---

    /** Posición de la sección con esa ruta (sin distinguir mayúsculas), o -1. */
    int buscar(String ruta) {
        if (ruta == null) return -1;
        Integer i = posicion.get(Rutas.normalizar(ruta).toLowerCase(Locale.ROOT));
        if (i == null && ruta.indexOf('%') >= 0) i = posicion.get(Rutas.normalizar(Rutas.decodificar(ruta)).toLowerCase(Locale.ROOT));
        return i == null ? -1 : i;
    }

    int total() {
        return secciones.size();
    }

    /** Número de secciones lineales (las que se recorren con Anterior/Siguiente). */
    int lineales() {
        return lineales;
    }

    /** Siguiente sección lineal después de i, o -1. */
    int siguiente(int i) {
        for (int k = i + 1; k < secciones.size(); k++) if (secciones.get(k).lineal) return k;
        return -1;
    }

    /** Sección lineal anterior a i, o -1. */
    int anterior(int i) {
        for (int k = Math.min(i, secciones.size()) - 1; k >= 0; k--) if (secciones.get(k).lineal) return k;
        return -1;
    }

    /** Posición (1..lineales) de una sección lineal, o 0 si no es lineal. */
    int ordinal(int i) {
        if (i < 0 || i >= secciones.size() || !secciones.get(i).lineal) return 0;
        int n = 0;
        for (int k = 0; k <= i; k++) if (secciones.get(k).lineal) n++;
        return n;
    }

    /** Entrada del índice que corresponde a una sección (preferiblemente sin fragmento), o null. */
    Entrada entradaDe(int i) {
        if (i < 0 || i >= secciones.size()) return null;
        Entrada conFragmento = null;
        for (Entrada e : indice) {
            if (e.ruta == null || buscar(e.ruta) != i) continue;
            if (e.fragmento == null) return e;
            if (conFragmento == null) conFragmento = e;
        }
        return conFragmento;
    }

    /** Posición en el índice de la entrada más cercana a la sección i (para resaltarla), o -1. */
    int entradaActual(int i) {
        int mejor = -1, mejorSeccion = -1;
        for (int k = 0; k < indice.size(); k++) {
            Entrada e = indice.get(k);
            int s = e.ruta == null ? -1 : buscar(e.ruta);
            if (s == i) return k;
            if (s >= 0 && s < i && s > mejorSeccion) {
                mejor = k;
                mejorSeccion = s;
            }
        }
        return mejor;
    }

    static boolean esDocumento(String tipo, String ruta) {
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        if (t.equals("application/xhtml+xml") || t.equals("text/html") || t.equals("application/x-dtbook+xml")) return true;
        if (!t.isEmpty()) return false;
        String e = Rutas.extension(ruta);
        return e.equals("xhtml") || e.equals("html") || e.equals("htm");
    }

    /** Lo que puede ir en el orden de lectura: documentos, SVG e imágenes sueltas. */
    static boolean mostrable(String tipo, String ruta) {
        if (esDocumento(tipo, ruta)) return true;
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        if (t.startsWith("image/")) return true;
        String e = Rutas.extension(ruta);
        return e.equals("svg") || e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("gif")
                || e.equals("webp") || (t.isEmpty() && e.isEmpty());
    }

    List<Entrada> indice() {
        return Collections.unmodifiableList(indice);
    }
}
