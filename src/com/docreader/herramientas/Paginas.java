package com.docreader.herramientas;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Páginas del PDF como imágenes, con el motor de Android (independiente del visor). */
final class Paginas implements AutoCloseable {
    private final ParcelFileDescriptor pfd;
    private final PdfRenderer pdf;

    Paginas(File archivo, String clave) throws IOException {
        pfd = ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            pdf = clave != null && android.os.Build.VERSION.SDK_INT >= 35
                    ? new PdfRenderer(pfd, new android.graphics.pdf.LoadParams.Builder().setPassword(clave).build())
                    : new PdfRenderer(pfd);
        } catch (IOException | SecurityException e) {
            pfd.close();
            throw new IOException("No se pudo abrir el PDF: " + e.getMessage());
        }
    }

    int total() { return pdf.getPageCount(); }

    /** Página a los ppp indicados, sin pasar de "maxLado" píxeles en el lado mayor. */
    Bitmap dibujar(int pag, int ppp, int maxLado) {
        try (PdfRenderer.Page p = pdf.openPage(pag)) {
            float escala = Math.min(ppp / 72f, maxLado / (float) Math.max(p.getWidth(), p.getHeight()));
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * escala)),
                    Math.max(1, Math.round(p.getHeight() * escala)), Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(escala, escala);
            p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            return b;
        }
    }

    byte[] jpeg(int pag, int ppp, int[] tam) {
        Bitmap b = dibujar(pag, ppp, 3000);
        tam[0] = b.getWidth();
        tam[1] = b.getHeight();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, o);
        b.recycle();
        return o.toByteArray();
    }

    /**
     * Guarda las páginas en la galería (Imágenes/DocReader/carpeta). Desde Android 10 una app
     * puede crear sus propias imágenes ahí sin pedir permisos.
     */
    List<Uri> aGaleria(Context c, List<Integer> pags, String carpeta, String base, boolean png, int ppp,
                       OperacionesPdf.Progreso progreso) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Uri coleccion = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        List<Uri> hechas = new ArrayList<>();
        for (int k = 0; k < pags.size(); k++) {
            if (progreso != null && !progreso.seguir(k, pags.size())) break;
            int pag = pags.get(k);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, String.format("%s_p%03d.%s", base, pag + 1, png ? "png" : "jpg"));
            v.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocReader/" + carpeta);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri u = cr.insert(coleccion, v);
            if (u == null) throw new IOException("No se pudo crear la imagen en la galería.");
            Bitmap b = dibujar(pag, ppp, 6000);
            try (OutputStream out = cr.openOutputStream(u)) {
                if (out == null) throw new IOException("No se pudo escribir la imagen.");
                b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, out);
            } catch (IOException e) {
                cr.delete(u, null, null);
                throw e;
            } finally {
                b.recycle();
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(u, v, null, null);
            hechas.add(u);
        }
        if (progreso != null) progreso.seguir(pags.size(), pags.size());
        return hechas;
    }

    @Override public void close() {
        try { pdf.close(); } catch (Exception ignorado) { }
        try { pfd.close(); } catch (Exception ignorado) { }
    }
}
