package com.docreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;


import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Biblioteca: botón para abrir un PDF y lista de recientes. */
public final class MainActivity extends Activity {
    private static final int ELEGIR_PDF = 1, BUSCAR_DE_NUEVO = 2;

    private final List<JSONObject> recientes = new ArrayList<>();
    private ArrayAdapter<JSONObject> adaptador;
    private TextView vacio;
    /** Entradas de recientes que ahora mismo no se pueden leer (sin permiso, movidas o borradas). */
    private final java.util.Set<String> sinAcceso = new java.util.HashSet<>();
    private int generacion;
    /** Entrada que se está volviendo a buscar con el selector. */
    private Uri buscando;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int m = dp(16);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);

        Button abrir = new Button(this);
        abrir.setText("Abrir documento");
        abrir.setOnClickListener(v -> elegir());
        LinearLayout.LayoutParams lpBoton = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpBoton.setMargins(m, m, m, m / 2);
        raiz.addView(abrir, lpBoton);

        TextView titulo = new TextView(this);
        titulo.setText("Recientes");
        titulo.setTextSize(14);
        titulo.setAlpha(0.7f);
        titulo.setPadding(m, m / 2, m, m / 2);
        raiz.addView(titulo);

        vacio = new TextView(this);
        vacio.setText("Aún no has abierto ningún documento.\nPulsa «Abrir documento» para elegir un PDF,\n"
                + "Word, Excel, PowerPoint o texto.");
        vacio.setGravity(Gravity.CENTER);
        vacio.setPadding(m, m * 3, m, m);
        raiz.addView(vacio);

        ListView lista = new ListView(this);
        adaptador = new ArrayAdapter<JSONObject>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, recientes) {
            @Override public View getView(int pos, View v, ViewGroup padre) {
                v = super.getView(pos, v, padre);
                JSONObject o = getItem(pos);
                ((TextView) v.findViewById(android.R.id.text1)).setText(o.optString("nombre"));
                int total = o.optInt("total"), pag = o.optInt("pagina") + 1;
                String fecha = DateUtils.getRelativeTimeSpanString(o.optLong("fecha")).toString();
                String tipo = tipo(o.optString("nombre"));
                String detalle = (tipo.isEmpty() ? "" : tipo + " · ")
                        + (total > 0 ? "Página " + pag + " de " + total + " · " : "") + fecha;
                boolean perdido = sinAcceso.contains(o.optString("uri"));
                ((TextView) v.findViewById(android.R.id.text2))
                        .setText(perdido ? "No disponible · tócalo para buscarlo" : detalle);
                v.setAlpha(perdido ? 0.5f : 1f);
                return v;
            }
        };
        lista.setAdapter(adaptador);
        lista.setOnItemClickListener((p, v, pos, id) -> abrirReciente(recientes.get(pos)));
        lista.setOnItemLongClickListener((p, v, pos, id) -> {
            JSONObject o = recientes.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(o.optString("nombre"))
                    .setItems(new String[] {"Quitar de recientes", "Buscar el archivo de nuevo…"}, (d, w) -> {
                        if (w == 0) quitar(o);
                        else buscarDeNuevo(o);
                    })
                    .show();
            return true;
        });
        raiz.addView(lista, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(raiz);
        App.mostrarFalloAnterior(this);
    }

    @Override protected void onResume() {
        super.onResume();
        recientes.clear();
        recientes.addAll(Recientes.lista(this));
        adaptador.notifyDataSetChanged();
        vacio.setVisibility(recientes.isEmpty() ? View.VISIBLE : View.GONE);
        comprobarAcceso();
    }

    /** En segundo plano: qué recientes no se pueden leer, y limpieza de copias que sobran. */
    private void comprobarAcceso() {
        final int gen = ++generacion;
        final List<JSONObject> copia = new ArrayList<>(recientes);
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Copias.limpiar(app, Recientes.copias(app));
            java.util.Set<String> perdidos = new java.util.HashSet<>();
            for (JSONObject o : copia) {
                if (!Copias.accesible(app, Recientes.abrible(o))) perdidos.add(o.optString("uri"));
            }
            runOnUiThread(() -> {
                if (gen != generacion || isDestroyed()) return;
                sinAcceso.clear();
                sinAcceso.addAll(perdidos);
                adaptador.notifyDataSetChanged();
            });
        }, "recientes").start();
    }

    /** Tipo de documento por la extensión, para la lista. */
    private static String tipo(String nombre) {
        int p = nombre == null ? -1 : nombre.lastIndexOf('.');
        if (p < 0) return "";
        String e = nombre.substring(p + 1).toLowerCase(java.util.Locale.ROOT);
        switch (e) {
            case "pdf": return "PDF";
            case "doc": case "docx": case "docm": case "dotx": case "dotm": case "dot": return "Word";
            case "xls": case "xlsx": case "xlsm": case "xltx": case "xltm": case "xlt": return "Excel";
            case "ppt": case "pptx": case "pptm": case "pps": case "ppsx": case "ppsm": case "pot": case "potx": return "PowerPoint";
            case "odt": case "ott": return "Documento ODF";
            case "ods": case "ots": return "Hoja ODF";
            case "odp": case "otp": case "odg": return "Presentación ODF";
            case "epub": return "EPUB";
            case "md": case "markdown": return "Markdown";
            case "csv": case "tsv": return "CSV";
            case "txt": case "log": return "Texto";
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic": case "heif": case "avif": return "Imagen";
            default: return e.length() <= 6 ? e.toUpperCase(java.util.Locale.ROOT) : "";
        }
    }

    private void elegir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // Todos los archivos: el código fuente y muchos textos llegan con tipos MIME imprevisibles.
        // Lo que no se pueda abrir mostrará su explicación.
        i.setType("*/*");
        startActivityForResult(i, ELEGIR_PDF);
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if ((pedido != ELEGIR_PDF && pedido != BUSCAR_DE_NUEVO) || resultado != RESULT_OK || datos == null
                || datos.getData() == null) {
            return;
        }
        Uri uri = datos.getData();
        try {
            // Permiso permanente para poder reabrirlo desde "Recientes".
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignorado) { }
        if (pedido == BUSCAR_DE_NUEVO && buscando != null) {
            Recientes.reemplazar(this, buscando, uri); // conserva la página en la que se iba
            buscando = null;
        }
        leer(uri, null);
    }

    private void abrirReciente(JSONObject o) {
        leer(Recientes.abrible(o), o);
    }

    /**
     * El visor se elige por el contenido real del archivo (ver Tipos). Antes se comprueba que se
     * pueda leer: si no, se explica y se ofrece buscarlo de nuevo (desde recientes, "o").
     */
    private void leer(Uri uri, JSONObject o) {
        new Thread(() -> {
            boolean ok = Copias.accesible(this, uri);
            Intent destino = ok ? Tipos.intentPara(this, uri) : null;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (destino == null) {
                    perdido(o);
                    return;
                }
                // Dentro de la propia app no hace falta ceder permisos (y cederlos sin tenerlos falla).
                destino.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(destino);
                } catch (RuntimeException e) {
                    perdido(o);
                }
            });
        }, "tipo").start();
    }

    private void perdido(JSONObject o) {
        if (o == null) {
            new AlertDialog.Builder(this).setMessage("No se pudo abrir el archivo.").setPositiveButton("Aceptar", null).show();
            return;
        }
        sinAcceso.add(o.optString("uri"));
        adaptador.notifyDataSetChanged();
        new AlertDialog.Builder(this)
                .setTitle("No se puede abrir «" + o.optString("nombre") + "»")
                .setMessage("Puede que se haya movido o borrado, o que DocReader ya no tenga permiso para "
                        + "leerlo (pasa con algunos archivos abiertos desde otras apps).\n\n"
                        + "Búscalo de nuevo y se conservará la página en la que ibas.")
                .setPositiveButton("Buscarlo…", (d, w) -> buscarDeNuevo(o))
                .setNeutralButton("Quitar de recientes", (d, w) -> quitar(o))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void buscarDeNuevo(JSONObject o) {
        buscando = Uri.parse(o.optString("uri"));
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, BUSCAR_DE_NUEVO);
        } catch (RuntimeException e) {
            buscando = null;
        }
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        if (buscando != null) b.putString("buscando", buscando.toString());
    }

    @Override protected void onRestoreInstanceState(Bundle b) {
        super.onRestoreInstanceState(b);
        String s = b.getString("buscando");
        if (s != null) buscando = Uri.parse(s);
    }

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, "Fusionar PDF…");
        menu.add(0, 2, 0, "Borrar recientes…");
        return true;
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, com.docreader.herramientas.FusionarActivity.class));
            return true;
        }
        if (item.getItemId() == 2) {
            new AlertDialog.Builder(this)
                    .setMessage("Se vaciará la lista de recientes y se borrarán las copias guardadas de los "
                            + "documentos abiertos desde otras apps. Los archivos originales no se tocan.")
                    .setPositiveButton("Borrar", (d, w) -> borrarRecientes())
                    .setNegativeButton("Cancelar", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void quitar(JSONObject o) {
        Uri uri = Uri.parse(o.optString("uri"));
        Recientes.quitar(this, uri);
        try {
            getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignorado) { }
        onResume();
    }

    private void borrarRecientes() {
        Recientes.borrarTodo(this);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            try {
                getContentResolver().releasePersistableUriPermission(p.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignorado) { }
        }
        onResume(); // limpia también las copias, que ya no están en uso
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
