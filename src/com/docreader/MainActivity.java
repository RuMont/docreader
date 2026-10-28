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
import android.widget.FrameLayout;
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
    private android.widget.BaseAdapter adaptador;
    private LinearLayout vacio, filaSeccion;
    /** Entradas de recientes que ahora mismo no se pueden leer (sin permiso, movidas o borradas). */
    private final java.util.Set<String> sinAcceso = new java.util.HashSet<>();
    private int generacion;
    /** Entrada que se está volviendo a buscar con el selector. */
    private Uri buscando;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int m = dp(16);

        ListView lista = new ListView(this);
        lista.setDivider(null);
        lista.setSelector(new android.graphics.drawable.ColorDrawable(0));
        lista.setBackgroundColor(Estilo.fondo(this));
        lista.setClipToPadding(false);
        lista.setPadding(0, 0, 0, dp(24));

        // Cabecera (se desplaza con la lista): acciones principales y título de la sección.
        LinearLayout cabecera = new LinearLayout(this);
        cabecera.setOrientation(LinearLayout.VERTICAL);
        cabecera.setPadding(m, dp(20), m, 0);
        cabecera.addView(Estilo.principal(this, "Abrir documento", Icono.Tipo.CARPETA, v -> elegir()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lpUnir = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpUnir.topMargin = dp(10);
        cabecera.addView(Estilo.tonal(this, "Unir varios PDF", Icono.Tipo.UNIR, v ->
                startActivity(new Intent(this, com.docreader.herramientas.FusionarActivity.class))), lpUnir);

        filaSeccion = new LinearLayout(this);
        filaSeccion.setGravity(Gravity.CENTER_VERTICAL);
        filaSeccion.setPadding(dp(4), dp(22), 0, dp(2));
        filaSeccion.addView(Estilo.seccion(this, "Recientes"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        filaSeccion.addView(Estilo.textoBoton(this, "Borrar", v -> confirmarBorrado()));
        cabecera.addView(filaSeccion);

        // Estado vacío.
        vacio = new LinearLayout(this);
        vacio.setOrientation(LinearLayout.VERTICAL);
        vacio.setGravity(Gravity.CENTER_HORIZONTAL);
        vacio.setPadding(m, dp(56), m, m);
        android.widget.ImageView ilustracion = new android.widget.ImageView(this);
        ilustracion.setImageDrawable(new Icono(this, Icono.Tipo.DOCUMENTO, Estilo.texto2(this)));
        ilustracion.setAlpha(0.6f);
        vacio.addView(ilustracion, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView t1 = new TextView(this);
        t1.setText("Sin documentos recientes");
        t1.setTextSize(17);
        t1.setTextColor(Estilo.texto(this));
        t1.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t1.setPadding(0, dp(14), 0, dp(6));
        vacio.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("Abre un PDF, un documento de Office, un libro EPUB, una imagen o cualquier texto. "
                + "También puedes usar «Abrir con» desde otra app.");
        t2.setTextColor(Estilo.texto2(this));
        t2.setGravity(Gravity.CENTER);
        t2.setLineSpacing(0, 1.15f);
        vacio.addView(t2);
        cabecera.addView(vacio);
        lista.addHeaderView(cabecera, null, false);

        adaptador = new android.widget.BaseAdapter() {
            @Override public int getCount() { return recientes.size(); }
            @Override public Object getItem(int i) { return recientes.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View v, ViewGroup padre) {
                Fila f = v != null ? (Fila) v.getTag() : new Fila();
                JSONObject o = recientes.get(pos);
                String nombre = o.optString("nombre");
                f.insignia.setImageDrawable(TipoDoc.insignia(MainActivity.this, nombre));
                f.nombre.setText(nombre);
                int total = o.optInt("total"), pag = o.optInt("pagina") + 1;
                String fecha = DateUtils.getRelativeTimeSpanString(o.optLong("fecha")).toString();
                String tipo = TipoDoc.nombre(nombre);
                boolean perdido = sinAcceso.contains(o.optString("uri"));
                f.detalle.setText(perdido ? "No disponible · tócalo para buscarlo"
                        : (tipo.isEmpty() ? "" : tipo + " · ") + (total > 0 ? "Pág. " + pag + " de " + total + " · " : "") + fecha);
                f.raiz.setAlpha(perdido ? 0.55f : 1f);
                f.opciones.setOnClickListener(x -> opciones(o));
                return f.tarjeta;
            }
        };
        lista.setAdapter(adaptador);
        lista.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof JSONObject) abrirReciente((JSONObject) o);
        });
        lista.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof JSONObject) opciones((JSONObject) o);
            return true;
        });

        setTitle("DocReader");
        setContentView(lista);
        App.mostrarFalloAnterior(this);
    }

    /** Vistas de una fila de recientes: tarjeta con insignia de tipo, nombre, detalle y menú. */
    private final class Fila {
        final FrameLayout tarjeta = new FrameLayout(MainActivity.this);
        final LinearLayout raiz = new LinearLayout(MainActivity.this);
        final android.widget.ImageView insignia = new android.widget.ImageView(MainActivity.this);
        final TextView nombre = new TextView(MainActivity.this), detalle = new TextView(MainActivity.this);
        final android.widget.ImageButton opciones;

        Fila() {
            tarjeta.setTag(this);
            tarjeta.setPadding(dp(16), dp(4), dp(16), dp(4));
            raiz.setGravity(Gravity.CENTER_VERTICAL);
            raiz.setBackground(Estilo.tocable(MainActivity.this, Estilo.superficie(MainActivity.this), dp(14)));
            raiz.setPadding(dp(12), dp(10), dp(4), dp(10));
            raiz.setDuplicateParentStateEnabled(true);
            raiz.addView(insignia, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout textos = new LinearLayout(MainActivity.this);
            textos.setOrientation(LinearLayout.VERTICAL);
            textos.setPadding(dp(14), 0, dp(4), 0);
            nombre.setTextSize(15.5f);
            nombre.setTextColor(Estilo.texto(MainActivity.this));
            nombre.setSingleLine(true);
            nombre.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            detalle.setTextSize(13);
            detalle.setTextColor(Estilo.texto2(MainActivity.this));
            detalle.setSingleLine(true);
            detalle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detalle.setPadding(0, dp(2), 0, 0);
            textos.addView(nombre);
            textos.addView(detalle);
            raiz.addView(textos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            opciones = Estilo.icono(MainActivity.this, Icono.Tipo.MAS, "Opciones", null);
            opciones.setFocusable(false); // si no, la fila deja de responder al toque
            raiz.addView(opciones);
            tarjeta.addView(raiz);
        }
    }

    private void opciones(JSONObject o) {
        new AlertDialog.Builder(this)
                .setTitle(o.optString("nombre"))
                .setItems(new String[] {"Abrir", "Buscar el archivo de nuevo…", "Quitar de recientes"}, (d, w) -> {
                    if (w == 0) abrirReciente(o);
                    else if (w == 1) buscarDeNuevo(o);
                    else quitar(o);
                })
                .show();
    }

    private void confirmarBorrado() {
        new AlertDialog.Builder(this)
                .setTitle("Borrar recientes")
                .setMessage("Se vaciará la lista y se borrarán las copias guardadas de los documentos abiertos "
                        + "desde otras apps. Los archivos originales no se tocan.")
                .setPositiveButton("Borrar", (d, w) -> borrarRecientes())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        recientes.clear();
        recientes.addAll(Recientes.lista(this));
        adaptador.notifyDataSetChanged();
        vacio.setVisibility(recientes.isEmpty() ? View.VISIBLE : View.GONE);
        filaSeccion.setVisibility(recientes.isEmpty() ? View.GONE : View.VISIBLE);
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
        menu.add(0, 1, 0, "Opciones")
                .setIcon(new Icono(this, Icono.Tipo.MAS, Estilo.texto(this)))
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() != 1) return super.onOptionsItemSelected(item);
        MenuPanel.Nivel n = new MenuPanel.Nivel("DocReader")
                .accion(Icono.Tipo.CARPETA, "Abrir documento…", this::elegir)
                .accion(Icono.Tipo.UNIR, "Unir varios PDF…", () ->
                        startActivity(new Intent(this, com.docreader.herramientas.FusionarActivity.class)));
        if (!recientes.isEmpty()) n.accion(Icono.Tipo.PAPELERA, "Borrar recientes…", this::confirmarBorrado);
        MenuPanel.mostrar(this, n);
        return true;
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
