package com.docreader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Copias privadas de los documentos abiertos desde otras apps con un permiso temporal (WhatsApp,
 * Gmail, la mayoría de gestores de archivos): ese permiso caduca y, sin copia, el documento ya no
 * se podría abrir desde «Recientes». Las copias viven en el almacenamiento privado de la app, se
 * sirven con este proveedor (no exportado; solo se comparten con el permiso puntual de «Compartir»)
 * y se borran al quitar el documento de recientes o al pasar del límite de espacio.
 */
public final class Copias extends ContentProvider {
    static final String AUTORIDAD = "com.docreader.copias";
    private static final long MAX_ARCHIVO = 50L << 20, MAX_TOTAL = 300L << 20;

    // --- Gestión ---

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "copias");
    }

    static boolean esCopia(Uri u) {
        return u != null && "content".equals(u.getScheme()) && AUTORIDAD.equals(u.getAuthority());
    }

    /**
     * Intenta quedarse con el documento para siempre: primero con un permiso permanente (si la app
     * que lo envió lo permite) y si no, con una copia. Devuelve la URI de la copia, o null si no
     * hace falta o no se puede (demasiado grande, sin espacio…). Lento: fuera del hilo principal.
     */
    static Uri conservar(Context c, Uri original, int flags, String nombre) {
        if (original == null || esCopia(original) || "file".equals(original.getScheme())) return null;
        if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            int modo = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            for (int m : new int[] {modo, Intent.FLAG_GRANT_READ_URI_PERMISSION}) {
                try {
                    c.getContentResolver().takePersistableUriPermission(original, m);
                    return null;
                } catch (SecurityException ignorado) { }
            }
        }
        if (permanente(c, original)) return null;
        String seguro = nombre == null || nombre.trim().isEmpty() ? "documento"
                : nombre.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        File carpeta = new File(dir(c), UUID.randomUUID().toString());
        File destino = new File(carpeta, seguro);
        if (!carpeta.mkdirs()) return null;
        try (InputStream in = c.getContentResolver().openInputStream(original);
             OutputStream out = new FileOutputStream(destino)) {
            if (in == null) throw new IOException();
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_ARCHIVO) throw new IOException("demasiado grande");
                out.write(buf, 0, n);
            }
        } catch (IOException | SecurityException e) {
            borrarCarpeta(carpeta);
            return null;
        }
        return new Uri.Builder().scheme("content").authority(AUTORIDAD)
                .appendPath(carpeta.getName()).appendPath(seguro).build();
    }

    /** ¿Tiene la app un permiso permanente sobre la URI (o no necesita ninguno)? */
    static boolean permanente(Context c, Uri u) {
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.isReadPermission() && p.getUri().equals(u)) return true;
        }
        return false;
    }

    /** ¿Se puede leer ahora mismo? Lento (abre el archivo): fuera del hilo principal. */
    static boolean accesible(Context c, Uri u) {
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(u, "r")) {
            return pfd != null;
        } catch (Exception e) {
            return false;
        }
    }

    static void borrar(Context c, Uri copia) {
        if (!esCopia(copia) || copia.getPathSegments().size() < 1) return;
        String id = copia.getPathSegments().get(0);
        if (id.contains("..") || id.contains("/")) return;
        borrarCarpeta(new File(dir(c), id));
    }

    /**
     * Borra las copias que ya no están en recientes y, si entre todas pasan del límite, las de los
     * documentos abiertos hace más tiempo. "enUso" son las copias de recientes, de más a menos reciente.
     */
    static void limpiar(Context c, List<Uri> enUso) {
        File[] carpetas = dir(c).listFiles();
        if (carpetas == null) return;
        Set<String> vivas = new HashSet<>();
        for (Uri u : enUso) if (esCopia(u) && !u.getPathSegments().isEmpty()) vivas.add(u.getPathSegments().get(0));
        for (File f : carpetas) if (!vivas.contains(f.getName())) borrarCarpeta(f);
        long total = 0;
        for (Uri u : enUso) {
            File f = archivo(c, u);
            if (f == null) continue;
            total += f.length();
            if (total > MAX_TOTAL) {
                borrar(c, u);
                Recientes.olvidarCopia(c, u);
            }
        }
    }

    private static void borrarCarpeta(File f) {
        File[] hijos = f.listFiles();
        if (hijos != null) for (File h : hijos) h.delete();
        f.delete();
    }

    /** Archivo de una URI de copia, comprobando que no se salga de la carpeta de copias. */
    private static File archivo(Context c, Uri u) {
        if (!esCopia(u)) return null;
        List<String> partes = u.getPathSegments();
        if (partes.size() != 2) return null;
        for (String p : partes) if (p.isEmpty() || p.equals(".") || p.equals("..") || p.contains("/")) return null;
        File f = new File(new File(dir(c), partes.get(0)), partes.get(1));
        try {
            if (!f.getCanonicalPath().startsWith(dir(c).getCanonicalPath() + File.separator)) return null;
        } catch (IOException e) {
            return null;
        }
        return f.isFile() ? f : null;
    }

    // --- Proveedor ---

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] columnas, String sel, String[] args, String orden) {
        File f = archivo(getContext(), uri);
        if (f == null) return null;
        if (columnas == null) columnas = new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cur = new MatrixCursor(columnas, 1);
        Object[] fila = new Object[columnas.length];
        for (int i = 0; i < columnas.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columnas[i])) fila[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(columnas[i])) fila[i] = f.length();
        }
        cur.addRow(fila);
        return cur;
    }

    @Override public String getType(Uri uri) {
        File f = archivo(getContext(), uri);
        if (f == null) return null;
        String n = f.getName();
        int p = n.lastIndexOf('.');
        String t = p < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(p + 1).toLowerCase(Locale.ROOT));
        return t != null ? t : "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String modo) throws FileNotFoundException {
        if (!"r".equals(modo)) throw new FileNotFoundException("Solo lectura");
        File f = archivo(getContext(), uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }

    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }

    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
