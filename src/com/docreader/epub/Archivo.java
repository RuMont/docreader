package com.docreader.epub;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * El zip del libro, abierto con acceso aleatorio: cada archivo se lee solo cuando hace falta,
 * así que nunca está el libro entero en memoria.
 */
final class Archivo implements Closeable {
    /** Límite del contenido descomprimido del libro entero. */
    static final long LIMITE = 150L * 1024 * 1024;
    static final String MENSAJE_GRANDE =
            "El libro es demasiado grande para el lector (más de 150 MB una vez descomprimido).";
    static final String MENSAJE_DANADO = "El archivo está dañado o no es un libro EPUB válido.";
    private static final int MAX_ENTRADAS = 30000;

    private final ZipFile zip;
    private final File temporal;
    /** Ruta normalizada → nombre real de la entrada. */
    private final Map<String, String> nombres = new HashMap<>();
    /** Ruta normalizada en minúsculas → ruta normalizada. */
    private final Map<String, String> sinMayusculas = new HashMap<>();
    private final List<String> orden = new ArrayList<>();

    private Archivo(ZipFile zip, File temporal) {
        this.zip = zip;
        this.temporal = temporal;
    }

    /**
     * Abre el zip y comprueba el tamaño total. Si se indica un archivo temporal, se borra al
     * cerrar. Lanza Fallo con un mensaje para el usuario si no es un zip o es demasiado grande.
     */
    static Archivo abrir(File f, File temporal) throws IOException, Fallo {
        ZipFile z;
        try {
            z = new ZipFile(f);
        } catch (java.util.zip.ZipException | RuntimeException e) {
            if (temporal != null) temporal.delete();
            throw new Fallo(MENSAJE_DANADO);
        }
        Archivo a = new Archivo(z, temporal);
        try {
            long total = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (a.orden.size() >= MAX_ENTRADAS) throw new Fallo(MENSAJE_GRANDE);
                long t = e.getSize();
                if (t < 0) t = Math.max(0, e.getCompressedSize());
                total += t;
                if (total > LIMITE) throw new Fallo(MENSAJE_GRANDE);
                String r = Rutas.normalizar(e.getName());
                if (r.isEmpty() || a.nombres.containsKey(r)) continue;
                a.nombres.put(r, e.getName());
                a.sinMayusculas.putIfAbsent(r.toLowerCase(Locale.ROOT), r);
                a.orden.add(r);
            }
            if (a.orden.isEmpty()) throw new Fallo(MENSAJE_DANADO);
        } catch (Fallo | RuntimeException e) {
            a.close();
            if (e instanceof Fallo) throw (Fallo) e;
            throw new Fallo(MENSAJE_DANADO);
        }
        return a;
    }

    /** Todas las rutas, en el orden del zip. */
    List<String> rutas() {
        return Collections.unmodifiableList(orden);
    }

    /** Ruta real de un archivo (admite diferencias de mayúsculas y %XX), o null si no existe. */
    String real(String ruta) {
        if (ruta == null) return null;
        String r = Rutas.normalizar(ruta);
        if (nombres.containsKey(r)) return r;
        String m = sinMayusculas.get(r.toLowerCase(Locale.ROOT));
        if (m != null) return m;
        if (r.indexOf('%') >= 0) {
            String d = Rutas.normalizar(Rutas.decodificar(r));
            if (!d.equals(r)) return real(d);
        }
        return null;
    }

    boolean existe(String ruta) {
        return real(ruta) != null;
    }

    /** Tamaño descomprimido declarado, o -1 si no existe o no se sabe. */
    long tamano(String ruta) {
        String r = real(ruta);
        if (r == null) return -1;
        ZipEntry e = zip.getEntry(nombres.get(r));
        return e == null ? -1 : e.getSize();
    }

    /**
     * Contenido de un archivo, o null si no existe, está dañado o pasa de {@code max} bytes
     * (se comprueba al leer, no solo con el tamaño declarado, que puede mentir).
     */
    byte[] leer(String ruta, int max) {
        String r = real(ruta);
        if (r == null) return null;
        try {
            ZipEntry e = zip.getEntry(nombres.get(r));
            if (e == null) return null;
            if (e.getSize() > max) return null;
            try (InputStream in = zip.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(
                        e.getSize() > 0 ? (int) e.getSize() : 8192);
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > max) return null;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override public void close() {
        try {
            zip.close();
        } catch (IOException ignorado) { }
        if (temporal != null) temporal.delete();
    }
}
