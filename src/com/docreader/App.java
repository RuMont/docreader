package com.docreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Si la app se cierra por un error, guarda el informe técnico en el almacenamiento privado (no se
 * envía a ningún sitio) y, al volver a abrirla, ofrece copiarlo para poder averiguar la causa.
 */
public final class App extends Application {
    private static final String ARCHIVO = "ultimo-fallo.txt";

    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previo = Thread.getDefaultUncaughtExceptionHandler();
        File destino = new File(getFilesDir(), ARCHIVO);
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode() + "";
        } catch (Exception ignorado) { }
        String v = version;
        Thread.setDefaultUncaughtExceptionHandler((hilo, e) -> {
            try (FileOutputStream out = new FileOutputStream(destino)) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("DocReader 1.0.0 (" + v + "), Android " + Build.VERSION.RELEASE + " (API "
                        + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
                pw.println("Hilo: " + hilo.getName());
                e.printStackTrace(pw);
                pw.flush();
                out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignorado) { }
            if (previo != null) previo.uncaughtException(hilo, e);
        });
    }

    /** Si hubo un cierre inesperado, lo cuenta y ofrece copiar el informe (una sola vez). */
    static void mostrarFalloAnterior(Activity a) {
        File f = new File(a.getFilesDir(), ARCHIVO);
        if (!f.isFile()) return;
        String texto;
        try {
            texto = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            texto = null;
        }
        f.delete();
        if (texto == null || texto.isEmpty()) return;
        String informe = texto;
        new AlertDialog.Builder(a)
                .setTitle("DocReader se cerró por un error")
                .setMessage("La última vez la app se cerró inesperadamente. Puedes copiar el informe técnico "
                        + "para enviárselo a quien la mantiene; no contiene el documento.")
                .setPositiveButton("Copiar informe", (d, w) -> {
                    ClipboardManager cm = a.getSystemService(ClipboardManager.class);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Informe de DocReader", informe));
                        Toast.makeText(a, "Informe copiado", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }
}
