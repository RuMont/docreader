package com.docreader;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.docreader.epub.EpubActivity;
import com.docreader.imagen.ImagenActivity;
import com.docreader.oficina.OficinaActivity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Decide qué visor abre un archivo. Manda el contenido real (las primeras bytes), después el
 * nombre y por último el tipo MIME, que muchas apps informan mal.
 */
public final class Tipos {
    private Tipos() { }

    /** Lento (lee del archivo): llamar fuera del hilo principal. */
    public static Intent intentPara(Context c, Uri uri) {
        String nombre = nombre(c, uri), mime = null;
        try {
            mime = c.getContentResolver().getType(uri);
        } catch (Exception ignorado) { }
        return new Intent(c, visor(c, uri, nombre, mime)).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static Class<?> visor(Context c, Uri uri, String nombre, String mime) {
        byte[] cab = cabecera(c, uri);
        String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (cab != null && cab.length > 0) {
            if (contiene(cab, "%PDF")) return LectorActivity.class;
            if (esImagen(cab)) return ImagenActivity.class;
            if (cab.length > 4 && cab[0] == 'P' && cab[1] == 'K') {
                // EPUB: el primer archivo del zip es "mimetype" con "application/epub+zip".
                if (contiene(cab, "mimetypeapplication/epub+zip")) return EpubActivity.class;
                return OficinaActivity.class; // docx/xlsx/pptx/odt/ods/odp (o un aviso)
            }
            return OficinaActivity.class; // texto, código, CSV… o un mensaje de no compatible
        }
        // Sin poder leer la cabecera: por nombre y tipo.
        if (n.endsWith(".pdf") || m.equals("application/pdf")) return LectorActivity.class;
        if (EpubActivity.soporta(nombre, mime)) return EpubActivity.class;
        if (ImagenActivity.soporta(nombre, mime)) return ImagenActivity.class;
        return OficinaActivity.class;
    }

    private static boolean esImagen(byte[] b) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true; // JPEG
        if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;      // PNG
        if (n >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;                 // GIF
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;                  // WebP
        if (n >= 2 && b[0] == 'B' && b[1] == 'M') return n >= 26 && b[14] >= 12;                             // BMP
        if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {                           // HEIF/AVIF
            String marca = new String(b, 8, 4, StandardCharsets.US_ASCII);
            return marca.startsWith("hei") || marca.startsWith("hev") || marca.equals("mif1")
                    || marca.equals("msf1") || marca.startsWith("avi");
        }
        return n >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0;                                   // ICO
    }

    private static boolean contiene(byte[] b, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        fuera:
        for (int i = 0; i + x.length <= b.length; i++) {
            for (int k = 0; k < x.length; k++) if (b[i + k] != x[k]) continue fuera;
            return true;
        }
        return false;
    }

    private static byte[] cabecera(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            byte[] b = new byte[1024];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            byte[] r = new byte[n];
            System.arraycopy(b, 0, r, 0, n);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    static String nombre(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignorado) { }
        return u.getLastPathSegment();
    }
}
