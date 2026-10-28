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
        int flags = getIntent().getFlags();
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Intent destino = Tipos.intentPara(this, uri);
            runOnUiThread(() -> {
                boolean abierto = false;
                try {
                    startActivity(destino);
                    abierto = true;
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_LONG).show();
                }
                finish();
                overridePendingTransition(0, 0);
                if (abierto) {
                    // Mientras el visor tiene el permiso (temporal) se guarda el documento para
                    // poder reabrirlo desde «Recientes» cuando ese permiso caduque.
                    new Thread(() -> {
                        String nombre = Tipos.nombre(app, uri);
                        Uri copia = Copias.conservar(app, uri, flags, nombre);
                        if (copia != null) Recientes.asociarCopia(app, uri, copia, nombre);
                    }, "conservar").start();
                }
            });
        }, "enrutador").start();
    }
}
