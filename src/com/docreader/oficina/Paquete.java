package com.docreader.oficina;

import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Contenido de un archivo OOXML (un zip) cargado en memoria, con ayudas para relaciones e imágenes. */
final class Paquete {
    /** Límite del contenido descomprimido, para no agotar la memoria. */
    static final long LIMITE = 60L * 1024 * 1024;
    static final String MENSAJE_GRANDE =
            "El documento es demasiado grande para el visor (más de 60 MB una vez descomprimido).";

    /** Una relación de un archivo .rels, con el destino ya resuelto a ruta dentro del zip. */
    static final class Relacion {
        final String id, tipo, destino;
        final boolean externa;

        Relacion(String id, String tipo, String destino, boolean externa) {
            this.id = id;
            this.tipo = tipo == null ? "" : tipo;
            this.destino = destino;
            this.externa = externa;
        }

        /** true si el tipo termina en el sufijo dado, p. ej. "/image". */
        boolean es(String sufijo) { return tipo.endsWith(sufijo); }
    }

    /**
     * Partes enormes (una hoja de cálculo de cientos de MB, por ejemplo) que no se descomprimen al
     * leer el paquete: se quedan comprimidas y se leen en streaming con {@link #flujo}.
     */
    static final long UMBRAL_DIFERIR = 16L << 20;

    private static final class Diferida {
        final byte[] zip;
        final int inicio, comprimido, metodo;

        Diferida(byte[] zip, int inicio, int comprimido, int metodo) {
            this.zip = zip;
            this.inicio = inicio;
            this.comprimido = comprimido;
            this.metodo = metodo;
        }
    }

    private final Map<String, Diferida> diferidas = new HashMap<>();

    /** Archivos en el orden del zip (LinkedHashMap: hace falta para volver a escribirlo). */
    private final Map<String, byte[]> archivos = new java.util.LinkedHashMap<>();
    private final Map<String, String> sinMayusculas = new HashMap<>();
    private final Map<String, Map<String, Relacion>> cacheRels = new HashMap<>();

    private Paquete() { }

    /** Lee el zip entero. Lanza Fallo si está dañado o si pasa del límite de tamaño. */
    static Paquete leer(InputStream in) throws IOException, Fallo {
        // Se lee entero para poder volver a intentarlo por el directorio central si hace falta.
        ByteArrayOutputStream todo = new ByteArrayOutputStream(65536);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) {
            if (todo.size() + (long) n > LIMITE) throw new Fallo(MENSAJE_GRANDE);
            todo.write(b, 0, n);
        }
        byte[] datos = todo.toByteArray();
        if (tieneEnormes(datos)) {
            // Con partes enormes se lee por el directorio central, dejándolas comprimidas.
            Paquete pq = leerDirectorio(datos, null, true);
            if (pq != null) return pq;
        }
        try {
            return leerFlujo(new ByteArrayInputStream(datos));
        } catch (Fallo f) {
            if (MENSAJE_GRANDE.equals(f.getMessage())) throw f;
            // ZipInputStream no admite entradas STORED con descriptor de datos (las cifradas de
            // LibreOffice, por ejemplo): se leen desde el directorio central.
            Paquete pq = leerDirectorio(datos, null);
            if (pq == null) throw f;
            return pq;
        }
    }

    private static Paquete leerFlujo(InputStream in) throws IOException, Fallo {
        Paquete pq = new Paquete();
        long total = 0;
        byte[] buf = new byte[16384];
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                long previsto = e.getSize();
                if (previsto > LIMITE) throw new Fallo(MENSAJE_GRANDE);
                ByteArrayOutputStream out = new ByteArrayOutputStream(
                        previsto > 0 ? (int) previsto : 8192);
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > LIMITE) throw new Fallo(MENSAJE_GRANDE);
                    out.write(buf, 0, n);
                }
                String nombre = normalizar(e.getName());
                if (nombre.isEmpty()) continue;
                pq.archivos.put(nombre, out.toByteArray());
                pq.sinMayusculas.put(nombre.toLowerCase(Locale.ROOT), nombre);
            }
        } catch (ZipException | EOFException | IllegalArgumentException e) {
            throw new Fallo("El archivo está dañado o incompleto y no se puede abrir.");
        }
        if (pq.archivos.isEmpty()) throw new Fallo("El archivo está vacío o dañado.");
        return pq;
    }

    /** Rutas de los archivos del paquete, en el orden del zip. */
    List<String> rutas() {
        return new ArrayList<>(archivos.keySet());
    }

    /**
     * Contenido de un solo archivo de un zip en bruto, leyendo solo esa entrada (sirve para mirar
     * el manifiesto sin descomprimir todo). null si no está o el zip no se puede leer.
     */
    static byte[] leerEntrada(byte[] zip, String ruta) {
        try {
            Paquete pq = leerDirectorio(zip, normalizar(ruta));
            return pq == null ? null : pq.bytes(ruta);
        } catch (Fallo e) {
            return null;
        }
    }

    /** ¿Tiene el zip alguna parte que, descomprimida, pase de {@link #UMBRAL_DIFERIR}? */
    private static boolean tieneEnormes(byte[] z) {
        try {
            int fin = finDirectorio(z);
            if (fin < 0) return false;
            int cuantas = u16(z, fin + 10);
            long pos = u32(z, fin + 16);
            for (int k = 0; k < cuantas; k++) {
                int p = (int) pos;
                if (p < 0 || p + 46 > z.length || u32(z, p) != 0x02014b50L) return false;
                if (u32(z, p + 24) > UMBRAL_DIFERIR) return true;
                pos = (long) p + 46 + u16(z, p + 28) + u16(z, p + 30) + u16(z, p + 32);
            }
        } catch (RuntimeException ignorado) { }
        return false;
    }

    private static int finDirectorio(byte[] z) {
        for (int i = z.length - 22; i >= Math.max(0, z.length - 22 - 65535); i--) {
            if (u32(z, i) == 0x06054b50L) return i;
        }
        return -1;
    }

    private static Paquete leerDirectorio(byte[] z, String solo) throws Fallo {
        return leerDirectorio(z, solo, false);
    }

    /**
     * Lee el zip a partir de su directorio central (solo la ruta "solo" si no es null); null si no
     * se puede. Con "diferir", las partes enormes no se descomprimen (ver {@link #flujo}).
     */
    private static Paquete leerDirectorio(byte[] z, String solo, boolean diferir) throws Fallo {
        try {
            int fin = finDirectorio(z);
            if (fin < 0) return null;
            int cuantas = u16(z, fin + 10);
            long pos = u32(z, fin + 16);
            Paquete pq = new Paquete();
            long total = 0;
            for (int k = 0; k < cuantas; k++) {
                int p = (int) pos;
                if (p < 0 || p + 46 > z.length || u32(z, p) != 0x02014b50L) return null;
                int metodo = u16(z, p + 10);
                long comprimido = u32(z, p + 20), tam = u32(z, p + 24);
                int ln = u16(z, p + 28), le = u16(z, p + 30), lc = u16(z, p + 32);
                long local = u32(z, p + 42);
                String nombre = normalizar(new String(z, p + 46, Math.min(ln, z.length - p - 46), StandardCharsets.UTF_8));
                pos = (long) p + 46 + ln + le + lc;
                if (nombre.isEmpty() || nombre.endsWith("/") || local + 30 > z.length || u32(z, (int) local) != 0x04034b50L) continue;
                if (solo != null && !solo.equalsIgnoreCase(nombre)) continue;
                long inicio = local + 30 + u16(z, (int) local + 26) + u16(z, (int) local + 28);
                if (comprimido > z.length || inicio + comprimido > z.length) continue;
                if (diferir && tam > UMBRAL_DIFERIR && (metodo == 0 || metodo == 8)) {
                    pq.diferidas.put(nombre, new Diferida(z, (int) inicio, (int) comprimido, metodo));
                    pq.sinMayusculas.put(nombre.toLowerCase(Locale.ROOT), nombre);
                    continue;
                }
                if (tam > LIMITE || total + tam > LIMITE) throw new Fallo(MENSAJE_GRANDE);
                byte[] datos;
                if (metodo == 0) {
                    datos = java.util.Arrays.copyOfRange(z, (int) inicio, (int) (inicio + comprimido));
                } else if (metodo == 8) {
                    java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
                    try {
                        inf.setInput(z, (int) inicio, (int) comprimido);
                        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(64, Math.min(tam, 1 << 24)));
                        byte[] buf = new byte[16384];
                        while (!inf.finished()) {
                            int n = inf.inflate(buf);
                            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                            out.write(buf, 0, n);
                            if (total + out.size() > LIMITE) throw new Fallo(MENSAJE_GRANDE);
                        }
                        datos = out.toByteArray();
                    } catch (java.util.zip.DataFormatException e) {
                        continue;
                    } finally {
                        inf.end();
                    }
                } else {
                    continue;
                }
                total += datos.length;
                pq.archivos.put(nombre, datos);
                pq.sinMayusculas.put(nombre.toLowerCase(Locale.ROOT), nombre);
            }
            return pq.archivos.isEmpty() && pq.diferidas.isEmpty() ? null : pq;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int i) {
        return ((b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (long) (b[i + 3] & 0xFF) << 24);
    }

    boolean existe(String ruta) { return bytes(ruta) != null || diferida(ruta) != null; }

    /** ¿Es una parte enorme que solo se puede leer en streaming? */
    boolean esDiferida(String ruta) { return diferida(ruta) != null; }

    private Diferida diferida(String ruta) {
        if (ruta == null || diferidas.isEmpty()) return null;
        String r = normalizar(ruta);
        Diferida d = diferidas.get(r);
        if (d == null) {
            String real = sinMayusculas.get(r.toLowerCase(Locale.ROOT));
            if (real != null) d = diferidas.get(real);
        }
        return d;
    }

    /** Tamaño comprimido de una parte diferida (para calcular el progreso), o -1. */
    long comprimido(String ruta) {
        Diferida d = diferida(ruta);
        return d == null ? -1 : d.comprimido;
    }

    /**
     * Contenido de una parte como flujo: de memoria o, si es diferida, descomprimiéndola sobre la
     * marcha. "leido" (opcional) acumula los bytes comprimidos consumidos, para el progreso.
     */
    InputStream flujo(String ruta, java.util.concurrent.atomic.AtomicLong leido) {
        byte[] b = bytes(ruta);
        if (b != null) return new ByteArrayInputStream(b);
        Diferida d = diferida(ruta);
        if (d == null) return null;
        InputStream crudo = new ByteArrayInputStream(d.zip, d.inicio, d.comprimido) {
            @Override public synchronized int read(byte[] x, int o, int n) {
                int r = super.read(x, o, n);
                if (r > 0 && leido != null) leido.addAndGet(r);
                return r;
            }
        };
        if (d.metodo == 0) return crudo;
        return new java.util.zip.InflaterInputStream(crudo, new java.util.zip.Inflater(true), 1 << 16);
    }

    /**
     * Sustituye el contenido de un archivo del paquete (con null lo quita). Lo usa el descifrado
     * de OpenDocument para dejar en su sitio las partes ya descifradas.
     */
    void poner(String ruta, byte[] datos) {
        String r = normalizar(ruta);
        String real = sinMayusculas.get(r.toLowerCase(Locale.ROOT));
        if (real != null) {
            archivos.remove(real);
            sinMayusculas.remove(r.toLowerCase(Locale.ROOT));
        }
        if (datos == null || r.isEmpty()) return;
        archivos.put(r, datos);
        sinMayusculas.put(r.toLowerCase(Locale.ROOT), r);
    }

    byte[] bytes(String ruta) {
        if (ruta == null) return null;
        String r = normalizar(ruta);
        byte[] b = archivos.get(r);
        if (b == null) {
            String real = sinMayusculas.get(r.toLowerCase(Locale.ROOT));
            if (real != null) b = archivos.get(real);
        }
        if (b == null && r.indexOf('%') >= 0) {
            String dec = decodificarPorcentajes(r);
            if (!dec.equals(r)) return bytes(dec);
        }
        return b;
    }

    /** Parser con espacios de nombres para un archivo del paquete, o null si no existe. */
    XmlPullParser parser(String ruta) throws XmlPullParserException {
        byte[] b = bytes(ruta);
        InputStream entrada;
        if (b == null) {
            // Parte enorme: en streaming (comprobando antes que no traiga DTD).
            InputStream f = diferida(ruta) == null ? null : flujo(ruta, null);
            if (f == null) return null;
            java.io.BufferedInputStream bf = new java.io.BufferedInputStream(f, 1 << 16);
            try {
                bf.mark(4096);
                byte[] cab = new byte[2048];
                int n = 0;
                for (int r; n < cab.length && (r = bf.read(cab, n, cab.length - n)) > 0; ) n += r;
                bf.reset();
                if (tieneDoctype(java.util.Arrays.copyOf(cab, n))) return null;
            } catch (IOException e) {
                return null;
            }
            entrada = bf;
        } else {
            if (tieneDoctype(b)) return null;
            entrada = new ByteArrayInputStream(b);
        }
        XmlPullParser p = Xml.newPullParser();
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        } catch (XmlPullParserException ignorado) { }
        try {
            // OOXML nunca usa DTD; sin ellas no hay entidades que expandir (ni "bombas" XML).
            p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        } catch (XmlPullParserException ignorado) { }
        p.setInput(entrada, null);
        return p;
    }

    private static boolean tieneDoctype(byte[] b) {
        String inicio = new String(b, 0, Math.min(b.length, 2048), StandardCharsets.ISO_8859_1);
        return inicio.contains("<!DOCTYPE") || inicio.contains("<!ENTITY");
    }

    /** Relaciones de una parte (por su archivo _rels/xxx.rels). "" para las del paquete. */
    Map<String, Relacion> relaciones(String parte) {
        String clave = parte == null ? "" : normalizar(parte);
        Map<String, Relacion> m = cacheRels.get(clave);
        if (m != null) return m;
        m = new HashMap<>();
        int i = clave.lastIndexOf('/');
        String rutaRels = (i >= 0 ? clave.substring(0, i + 1) : "") + "_rels/" + clave.substring(i + 1) + ".rels";
        try {
            XmlPullParser p = parser(rutaRels);
            if (p != null) {
                int t;
                while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                    if (t != XmlPullParser.START_TAG || !"Relationship".equals(Lectura.nombre(p))) continue;
                    String id = Lectura.atr(p, "Id");
                    String destino = Lectura.atr(p, "Target");
                    if (id == null || destino == null) continue;
                    boolean externa = "External".equalsIgnoreCase(Lectura.atr(p, "TargetMode"));
                    m.put(id, new Relacion(id, Lectura.atr(p, "Type"),
                            externa ? destino.trim() : resolver(clave, destino), externa));
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException ignorado) {
            // Relaciones ilegibles: se sigue con las que se hayan leído.
        }
        cacheRels.put(clave, m);
        return m;
    }

    /** Primera relación interna cuyo tipo acaba en el sufijo dado. */
    static Relacion relacion(Map<String, Relacion> rels, String sufijo) {
        for (Relacion r : rels.values()) {
            if (!r.externa && r.es(sufijo)) return r;
        }
        return null;
    }

    /** Parte principal del documento según _rels/.rels, o la ruta por defecto si no aparece. */
    String principal(String porDefecto) {
        Relacion r = relacion(relaciones(""), "/officeDocument");
        if (r != null && existe(r.destino)) return r.destino;
        return porDefecto;
    }

    /** Imagen del paquete como URI data:, o null si falta o el navegador no sabe mostrarla. */
    String imagen(String ruta) {
        byte[] b = bytes(ruta);
        if (b == null || b.length == 0) return null;
        String mime = tipoImagen(b, ruta);
        if (mime == null) return null;
        return "data:" + mime + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String tipoImagen(byte[] b, String ruta) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length > 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        String r = ruta.toLowerCase(Locale.ROOT);
        if (r.endsWith(".svg")) return "image/svg+xml";
        // EMF, WMF, TIFF y demás: el WebView no los dibuja.
        return null;
    }

    /** Quita "." y "..", barras iniciales y barras invertidas. */
    static String normalizar(String ruta) {
        String[] partes = ruta.replace('\\', '/').split("/");
        List<String> pila = new ArrayList<>();
        for (String s : partes) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!pila.isEmpty()) pila.remove(pila.size() - 1);
                continue;
            }
            pila.add(s);
        }
        return String.join("/", pila);
    }

    /** Resuelve el destino de una relación respecto a la carpeta de la parte de origen. */
    static String resolver(String parte, String destino) {
        String d = destino.trim().replace('\\', '/');
        int q = d.indexOf('#');
        if (q >= 0) d = d.substring(0, q);
        if (d.startsWith("/")) return normalizar(d);
        int i = parte == null ? -1 : parte.lastIndexOf('/');
        String dir = i >= 0 ? parte.substring(0, i + 1) : "";
        return normalizar(dir + d);
    }

    private static String decodificarPorcentajes(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int v = Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16);
                if (v >= 0 && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                    out.write(v);
                    i += 2;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
