package com.docreader.herramientas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Lista ordenable de PDF para unirlos en uno nuevo. Puede empezar con el PDF abierto. */
public final class FusionarActivity extends Activity {
    public static final String EXTRA_NOMBRE = "com.docreader.herramientas.NOMBRE";
    public static final String EXTRA_CLAVE = "com.docreader.herramientas.CLAVE";
    private static final int ELEGIR = 1, GUARDAR = 2;

    private static final class Entrada {
        final Uri uri;
        final String nombre;
        String clave; // si el PDF está protegido
        Entrada(Uri uri, String nombre) {
            this.uri = uri;
            this.nombre = nombre;
        }
    }

    private final List<Entrada> lista = new ArrayList<>();
    private BaseAdapter adaptador;
    private Button unir;
    private TextView vacio;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Fusionar PDF");
        Uri inicial = getIntent().getData();
        if (inicial != null) {
            String n = getIntent().getStringExtra(EXTRA_NOMBRE);
            Entrada e = new Entrada(inicial, n != null ? n : nombre(inicial));
            e.clave = getIntent().getStringExtra(EXTRA_CLAVE);
            lista.add(e);
        }

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        TextView ayuda = new TextView(this);
        ayuda.setText("Los PDF se unirán en este orden. Usa las flechas para cambiarlo.");
        ayuda.setPadding(dp(16), dp(12), dp(16), dp(4));
        ayuda.setAlpha(0.75f);
        raiz.addView(ayuda);

        vacio = new TextView(this);
        vacio.setText("Añade al menos dos PDF.");
        vacio.setGravity(Gravity.CENTER);
        vacio.setPadding(dp(16), dp(32), dp(16), dp(16));
        raiz.addView(vacio);

        ListView lv = new ListView(this);
        adaptador = new BaseAdapter() {
            @Override public int getCount() { return lista.size(); }
            @Override public Object getItem(int i) { return lista.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) { return fila(i); }
        };
        lv.setAdapter(adaptador);
        raiz.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout botones = new LinearLayout(this);
        botones.setGravity(Gravity.END);
        botones.setPadding(dp(8), dp(4), dp(8), dp(8));
        Button anadir = new Button(this, null, android.R.attr.borderlessButtonStyle);
        anadir.setText("Añadir PDF");
        anadir.setOnClickListener(v -> elegir());
        unir = new Button(this);
        unir.setText("Fusionar");
        unir.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, Tarea.base(lista.get(0).nombre) + "_fusionado.pdf"), GUARDAR));
        botones.addView(anadir);
        botones.addView(unir);
        raiz.addView(botones);

        setContentView(raiz);
        actualizar();
        if (lista.isEmpty()) elegir();
    }

    private View fila(int i) {
        Entrada e = lista.get(i);
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(dp(16), dp(4), dp(4), dp(4));
        TextView t = new TextView(this);
        t.setText((i + 1) + ".  " + e.nombre);
        t.setTextSize(16);
        t.setMaxLines(2);
        f.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        f.addView(boton("↑", "Subir", i > 0, () -> mover(i, i - 1)));
        f.addView(boton("↓", "Bajar", i < lista.size() - 1, () -> mover(i, i + 1)));
        f.addView(boton("✕", "Quitar", true, () -> {
            lista.remove(i);
            actualizar();
        }));
        return f;
    }

    private Button boton(String texto, String descripcion, boolean activo, Runnable r) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(texto);
        b.setContentDescription(descripcion);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        b.setEnabled(activo);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void mover(int de, int a) {
        lista.add(a, lista.remove(de));
        actualizar();
    }

    private void actualizar() {
        adaptador.notifyDataSetChanged();
        unir.setEnabled(lista.size() >= 2);
        vacio.setVisibility(lista.size() < 2 ? View.VISIBLE : View.GONE);
    }

    private void elegir() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), ELEGIR);
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if (resultado != RESULT_OK || datos == null) return;
        if (pedido == ELEGIR) {
            ClipData varios = datos.getClipData();
            if (varios != null) {
                for (int i = 0; i < varios.getItemCount(); i++) anadir(varios.getItemAt(i).getUri());
            } else if (datos.getData() != null) {
                anadir(datos.getData());
            }
            actualizar();
        } else if (pedido == GUARDAR && datos.getData() != null) {
            fusionarEn(datos.getData());
        }
    }

    private void anadir(Uri u) {
        if (u != null) lista.add(new Entrada(u, nombre(u)));
    }

    private void fusionarEn(Uri destino) {
        List<Entrada> orden = new ArrayList<>(lista);
        OperacionesPdf.ClaveNecesaria[] falta = new OperacionesPdf.ClaveNecesaria[1];
        new Tarea(this, "Fusionando PDF") {
            @Override void trabajo() throws Exception {
                Tarea.iniciarPdfbox(FusionarActivity.this);
                List<File> copias = new ArrayList<>();
                try {
                    for (int i = 0; i < orden.size(); i++) {
                        if (!progreso(i, orden.size() + 1, "Leyendo «" + orden.get(i).nombre + "»…")) return;
                        copias.add(Tarea.copiar(FusionarActivity.this, orden.get(i).uri, "fusionar" + i + ".pdf"));
                    }
                    progreso(orden.size(), orden.size() + 1, "Uniendo " + orden.size() + " documentos…");
                    List<String> claves = new ArrayList<>();
                    for (Entrada e : orden) claves.add(e.clave);
                    try (OutputStream out = getContentResolver().openOutputStream(destino, "wt")) {
                        if (out == null) throw new IOException("No se pudo crear el archivo.");
                        OperacionesPdf.fusionar(copias, claves, out);
                    }
                } catch (OperacionesPdf.ClaveNecesaria e) {
                    borrar(destino);
                    falta[0] = e;
                    cancelada = true; // se resuelve en alCancelar pidiendo la contraseña
                } catch (Exception e) {
                    borrar(destino);
                    throw e;
                } finally {
                    for (File f : copias) f.delete();
                }
            }
            @Override void alTerminar() {
                new AlertDialog.Builder(FusionarActivity.this)
                        .setMessage("Se han unido " + orden.size() + " documentos en un PDF nuevo.")
                        .setPositiveButton("Abrir", (d, w) -> {
                            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(destino, "application/pdf")
                                    .setPackage(getPackageName()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            finish();
                        })
                        .setNegativeButton("Cerrar", (d, w) -> finish())
                        .show();
            }
            @Override void alCancelar() {
                borrar(destino);
                if (falta[0] != null) pedirClave(orden.get(falta[0].indice), falta[0].getMessage());
            }
        }.iniciar();
    }

    /** Pide la contraseña de un PDF protegido de la lista y vuelve a elegir dónde guardar. */
    private void pedirClave(Entrada e, String motivo) {
        android.widget.EditText campo = new android.widget.EditText(this);
        campo.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.widget.FrameLayout marco = new android.widget.FrameLayout(this);
        marco.setPadding(dp(20), dp(8), dp(20), 0);
        marco.addView(campo);
        new AlertDialog.Builder(this)
                .setTitle(motivo)
                .setMessage("Escribe la contraseña de «" + e.nombre + "».")
                .setView(marco)
                .setPositiveButton("Continuar", (d, w) -> {
                    e.clave = campo.getText().toString();
                    unir.performClick();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void borrar(Uri u) {
        try {
            DocumentsContract.deleteDocument(getContentResolver(), u);
        } catch (Exception ignorado) { }
    }

    private String nombre(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignorado) { }
        String s = u.getLastPathSegment();
        return s != null ? s : "documento.pdf";
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
