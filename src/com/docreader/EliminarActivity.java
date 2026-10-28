package com.docreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

/**
 * Elimina un documento del almacenamiento, sin permisos especiales: solo se puede si el archivo
 * se eligió con el selector de Android (que concede escritura) y su proveedor admite borrar. Si
 * no, se ofrece buscarlo con el selector para poder borrarlo. Pantalla transparente: solo diálogos.
 * Devuelve RESULT_OK si se borró (el visor que la abrió se cierra).
 */
public final class EliminarActivity extends Activity {
    public static final String EXTRA_NOMBRE = "com.docreader.NOMBRE";
    /** Código para startActivityForResult desde los visores. */
    public static final int PEDIDO = 7700;
    private static final int BUSCAR = 1;

    private Uri original;
    private String nombre;
    /** Para no cerrar la pantalla al pasar de un diálogo a otro. */
    private boolean siguiendo;

    /** ¿Se puede borrar ya? (Documento del selector, con permiso de escritura y que admite borrar.) */
    public static boolean borrable(Context c, Uri u) {
        if (u == null || Copias.esCopia(u)) return false;
        try {
            if (!DocumentsContract.isDocumentUri(c, u)) return false;
            if (c.checkCallingOrSelfUriPermission(u, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            try (Cursor cur = c.getContentResolver().query(u,
                    new String[] {DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
                return cur != null && cur.moveToFirst()
                        && (cur.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Texto de la opción: borrar directamente o, si no se puede, buscarlo antes con el selector. */
    public static String etiqueta(Context c, Uri u) {
        return borrable(c, Recientes.original(c, u)) ? "Eliminar archivo…" : "Buscarlo para eliminar…";
    }

    /** Abre el proceso de borrado para el documento "uri" (puede ser la copia de recientes). */
    public static Intent intent(Context c, Uri uri, String nombre) {
        return new Intent(c, EliminarActivity.class).setData(uri).putExtra(EXTRA_NOMBRE, nombre);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri u = getIntent().getData();
        if (u == null) {
            finish();
            return;
        }
        original = Recientes.original(this, u); // si es una copia, el archivo de verdad
        nombre = getIntent().getStringExtra(EXTRA_NOMBRE);
        if (nombre == null) nombre = Tipos.nombre(this, original);
        if (b != null) return; // se recrea tras el selector: sigue en onActivityResult
        if (borrable(this, original)) confirmar(original, nombre);
        else sinPermiso();
    }

    private void confirmar(Uri u, String n) {
        siguiendo = false;
        new AlertDialog.Builder(this)
                .setTitle("¿Eliminar «" + n + "»?")
                .setMessage("Se borrará del almacenamiento del dispositivo. No se puede deshacer.")
                .setPositiveButton("Eliminar", (d, w) -> {
                    siguiendo = true;
                    borrar(u);
                })
                .setNegativeButton("Cancelar", null)
                .setOnDismissListener(d -> { if (!siguiendo) finish(); })
                .show();
    }

    private void sinPermiso() {
        siguiendo = false;
        new AlertDialog.Builder(this)
                .setTitle("No se puede eliminar desde aquí")
                .setMessage("DocReader solo puede borrar archivos elegidos con el selector de archivos (este "
                        + "se abrió desde otra app o ya no está). Búscalo con el selector y se eliminará.")
                .setPositiveButton("Buscarlo…", (d, w) -> {
                    siguiendo = true;
                    buscar();
                })
                .setNegativeButton("Cancelar", null)
                .setOnDismissListener(d -> { if (!siguiendo) finish(); })
                .show();
    }

    private void buscar() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            if (DocumentsContract.isDocumentUri(this, original)) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, original);
        } catch (Exception ignorado) { }
        try {
            startActivityForResult(i, BUSCAR);
        } catch (RuntimeException e) {
            finish();
        }
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if (pedido != BUSCAR) return;
        if (resultado != RESULT_OK || datos == null || datos.getData() == null) {
            finish();
            return;
        }
        Uri elegido = datos.getData();
        String n = Tipos.nombre(this, elegido);
        if (!borrable(this, elegido)) {
            new AlertDialog.Builder(this)
                    .setMessage("Ese archivo no se puede eliminar desde DocReader (la app que lo guarda no lo permite).")
                    .setPositiveButton("Aceptar", null)
                    .setOnDismissListener(d -> finish())
                    .show();
            return;
        }
        // Se avisa si se eligió otro archivo distinto del que se quería borrar.
        confirmar(elegido, n != null ? n : nombre);
    }

    private void borrar(Uri u) {
        Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok;
            try {
                ok = DocumentsContract.deleteDocument(app.getContentResolver(), u);
            } catch (Exception e) {
                ok = false;
            }
            boolean hecho = ok;
            if (hecho) {
                Recientes.quitar(app, u);
                Recientes.quitar(app, original);
                for (Uri x : new Uri[] {u, original}) {
                    for (int f : new int[] {Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION}) {
                        try {
                            app.getContentResolver().releasePersistableUriPermission(x, f);
                        } catch (Exception ignorado) { }
                    }
                }
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (hecho) {
                    Toast.makeText(app, "Archivo eliminado", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    new AlertDialog.Builder(this)
                            .setMessage("No se pudo eliminar el archivo.")
                            .setPositiveButton("Aceptar", null)
                            .setOnDismissListener(d -> finish())
                            .show();
                }
            });
        }, "eliminar").start();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
