package com.docreader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Punto de entrada único de «Abrir con»: mira qué es el archivo de verdad y lo pasa al visor
 * adecuado. No tiene interfaz (tema translúcido) y se cierra enseguida.
 */
public final class EnrutadorActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        new Thread(() -> {
            Intent destino = Tipos.intentPara(this, uri);
            runOnUiThread(() -> {
                try {
                    startActivity(destino);
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_LONG).show();
                }
                finish();
                overridePendingTransition(0, 0);
            });
        }, "enrutador").start();
    }
}
