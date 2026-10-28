package com.docreader.firma;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/** Lista de firmas guardadas con vista previa: usar, renombrar, borrar o crear una nueva. */
public final class SelectorFirmas {
    public interface AlUsar { void usar(Trazo t); }

    private SelectorFirmas() { }

    /** Si no hay ninguna firma guardada se va directamente a crear una ("nueva"). */
    public static void mostrar(Activity a, AlUsar usar, Runnable nueva) {
        List<Trazo> firmas = Firmas.lista(a);
        if (firmas.isEmpty()) {
            nueva.run();
            return;
        }
        ListView lista = new ListView(a);
        AlertDialog[] dialogo = new AlertDialog[1];
        BaseAdapter adaptador = new BaseAdapter() {
            @Override public int getCount() { return firmas.size(); }
            @Override public Object getItem(int i) { return firmas.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup padre) {
                return fila(a, firmas.get(i), () -> opciones(a, firmas, i, this, dialogo[0], nueva));
            }
        };
        lista.setAdapter(adaptador);
        lista.setOnItemClickListener((p, v, i, id) -> {
            dialogo[0].dismiss();
            usar.usar(firmas.get(i));
        });
        dialogo[0] = new AlertDialog.Builder(a)
                .setTitle("Elige una firma")
                .setView(lista)
                .setPositiveButton("Nueva firma", (d, w) -> nueva.run())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static View fila(Context c, Trazo t, Runnable menu) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout fila = new LinearLayout(c);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(Math.round(20 * d), Math.round(8 * d), Math.round(4 * d), Math.round(8 * d));
        fila.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); // que la fila reciba el toque

        LinearLayout textos = new LinearLayout(c);
        textos.setOrientation(LinearLayout.VERTICAL);
        FrameLayout papel = new FrameLayout(c);
        papel.setBackground(com.docreader.Estilo.redondo(0xFFFFFFFF, 10 * d));
        papel.setClipToOutline(true);
        papel.addView(new Miniatura(c, t));
        textos.addView(papel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * d)));
        TextView nombre = new TextView(c);
        nombre.setText(t.nombre);
        nombre.setPadding(0, Math.round(4 * d), 0, 0);
        textos.addView(nombre);
        fila.addView(textos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        android.widget.ImageButton mas = com.docreader.Estilo.icono(c, com.docreader.Icono.Tipo.MAS,
                "Opciones de " + t.nombre, v -> menu.run());
        mas.setFocusable(false);
        fila.addView(mas);
        return fila;
    }

    private static void opciones(Activity a, List<Trazo> firmas, int i, BaseAdapter ad,
                                 AlertDialog padre, Runnable nueva) {
        Trazo t = firmas.get(i);
        new AlertDialog.Builder(a)
                .setTitle(t.nombre)
                .setItems(new String[] {"Renombrar", "Borrar"}, (d, w) -> {
                    if (w == 0) {
                        renombrar(a, t, nombre -> {
                            Firmas.renombrar(a, t.id, nombre);
                            firmas.set(i, Firmas.buscar(a, t.id));
                            ad.notifyDataSetChanged();
                        });
                    } else {
                        new AlertDialog.Builder(a)
                                .setMessage("¿Borrar «" + t.nombre + "»?")
                                .setPositiveButton("Borrar", (d2, w2) -> {
                                    Firmas.borrar(a, t.id);
                                    firmas.remove(i);
                                    ad.notifyDataSetChanged();
                                    if (firmas.isEmpty()) padre.dismiss();
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                })
                .show();
    }

    private interface AlNombrar { void listo(String nombre); }

    private static void renombrar(Activity a, Trazo t, AlNombrar cb) {
        EditText e = new EditText(a);
        e.setText(t.nombre);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout marco = new FrameLayout(a);
        int m = Math.round(20 * a.getResources().getDisplayMetrics().density);
        marco.setPadding(m, m / 2, m, 0);
        marco.addView(e);
        new AlertDialog.Builder(a)
                .setTitle("Nombre de la firma")
                .setView(marco)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) cb.listo(n);
                })
                .setNegativeButton("Cancelar", null)
                .show();
        e.requestFocus();
    }

    /** Vista previa de una firma, centrada y ajustada a su espacio. */
    static final class Miniatura extends View {
        private final Trazo t;

        Miniatura(Context c, Trazo t) {
            super(c);
            this.t = t;
        }

        @Override protected void onDraw(Canvas c) {
            float m = getHeight() * 0.1f;
            float w = getWidth() - 2 * m, h = getHeight() - 2 * m;
            if (w <= 0 || h <= 0) return;
            if (w / h > t.aspecto) w = h * t.aspecto; else h = w / t.aspecto;
            float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
            t.dibujar(c, new RectF(x, y, x + w, y + h));
        }
    }
}
