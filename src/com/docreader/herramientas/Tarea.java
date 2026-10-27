package com.docreader.herramientas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Trabajo en segundo plano con un diálogo de progreso cancelable. */
abstract class Tarea {
    private final Activity a;
    private final AlertDialog dialogo;
    private final ProgressBar barra;
    private final TextView texto;
    volatile boolean cancelada;

    Tarea(Activity a, String titulo) {
        this.a = a;
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Math.round(24 * d), Math.round(12 * d), Math.round(24 * d), 0);
        texto = new TextView(a);
        texto.setText("Preparando…");
        v.addView(texto);
        barra = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        barra.setIndeterminate(true);
        v.addView(barra, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dialogo = new AlertDialog.Builder(a)
                .setTitle(titulo)
                .setView(v)
                .setCancelable(false)
                .setNegativeButton("Cancelar", (x, w) -> cancelada = true)
                .create();
    }

    /** Lo hecho y el total, para la barra; devuelve false si el usuario canceló. */
    boolean progreso(int hecho, int total, String mensaje) {
        a.runOnUiThread(() -> {
            barra.setIndeterminate(false);
            barra.setMax(Math.max(1, total));
            barra.setProgress(hecho);
            texto.setText(mensaje);
        });
        return !cancelada;
    }

    void iniciar() {
        dialogo.show();
        new Thread(() -> {
            String error = null;
            try {
                trabajo();
            } catch (OutOfMemoryError e) {
                error = "No hay memoria suficiente para este documento.";
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String err = error;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                dialogo.dismiss();
                if (cancelada) alCancelar();
                else if (err != null) mensaje(a, "No se pudo completar la operación:\n" + err);
                else alTerminar();
            });
        }, "herramienta-pdf").start();
    }

    abstract void trabajo() throws Exception;

    abstract void alTerminar();

    void alCancelar() { }

    static void mensaje(Activity a, String texto) {
        new AlertDialog.Builder(a).setMessage(texto).setPositiveButton("Aceptar", null).show();
    }

    // --- Utilidades comunes ---

    static void iniciarPdfbox(Context c) {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(c.getApplicationContext());
    }

    /** PdfBox necesita un archivo: copia el documento (puede ser un content://) a la caché. */
    static File copiar(Context c, Uri uri, String nombre) throws IOException {
        File f = new File(c.getCacheDir(), nombre);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("No se pudo leer el documento.");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (SecurityException e) {
            throw new IOException("Sin permiso para leer el documento.");
        }
        return f;
    }

    /** Nombre sin extensión y sin caracteres que no valen en archivos. */
    static String base(String nombre) {
        String b = nombre == null ? "documento" : nombre.replaceAll("(?i)\\.pdf$", "");
        b = b.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return b.isEmpty() ? "documento" : b;
    }
}
