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
    private static final int ELEGIR_PDF = 1;

    private final List<JSONObject> recientes = new ArrayList<>();
    private ArrayAdapter<JSONObject> adaptador;
    private TextView vacio;

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
                ((TextView) v.findViewById(android.R.id.text2))
                        .setText(total > 0 ? "Página " + pag + " de " + total + " · " + fecha : fecha);
                return v;
            }
        };
        lista.setAdapter(adaptador);
        lista.setOnItemClickListener((p, v, pos, id) ->
                leer(Uri.parse(recientes.get(pos).optString("uri"))));
        lista.setOnItemLongClickListener((p, v, pos, id) -> {
            JSONObject o = recientes.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(o.optString("nombre"))
                    .setItems(new String[] {"Quitar de recientes"}, (d, w) -> quitar(o))
                    .show();
            return true;
        });
        raiz.addView(lista, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(raiz);
    }

    @Override protected void onResume() {
        super.onResume();
        recientes.clear();
        recientes.addAll(Recientes.lista(this));
        adaptador.notifyDataSetChanged();
        vacio.setVisibility(recientes.isEmpty() ? View.VISIBLE : View.GONE);
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
        if (pedido != ELEGIR_PDF || resultado != RESULT_OK || datos == null || datos.getData() == null) return;
        Uri uri = datos.getData();
        try {
            // Permiso permanente para poder reabrirlo desde "Recientes".
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignorado) { }
        leer(uri);
    }

    /** El visor se elige por el contenido real del archivo (ver Tipos). */
    private void leer(Uri uri) {
        new Thread(() -> {
            Intent destino = Tipos.intentPara(this, uri);
            runOnUiThread(() -> startActivity(destino));
        }, "tipo").start();
    }

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, "Fusionar PDF…");
        return true;
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, com.docreader.herramientas.FusionarActivity.class));
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

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
