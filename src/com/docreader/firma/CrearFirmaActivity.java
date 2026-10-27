package com.docreader.firma;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pantalla para dibujar una firma nueva. Va siempre en horizontal y a pantalla completa para
 * tener más espacio. Al guardar la añade a la lista de firmas y devuelve su id en EXTRA_ID.
 */
public final class CrearFirmaActivity extends Activity {
    public static final String EXTRA_ID = "com.docreader.firma.ID";

    private PanelFirma panel;
    private Button deshacer, borrar;

    @Override protected void onCreate(Bundle b) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(b);
        int m = dp(8);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(colorTema(android.R.attr.colorBackground));

        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        barra.setPadding(dp(16), 0, m, 0);
        TextView titulo = new TextView(this);
        titulo.setText("Nueva firma");
        titulo.setTextSize(18);
        barra.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        barra.addView(muestra("Negro", Trazo.NEGRO));
        barra.addView(muestra("Azul", Trazo.AZUL));
        deshacer = plano("Deshacer", v -> panel.deshacer());
        borrar = plano("Borrar", v -> panel.borrar());
        barra.addView(deshacer);
        barra.addView(borrar);
        barra.addView(plano("Cancelar", v -> finish()));
        barra.addView(plano("Guardar", v -> guardar()));
        raiz.addView(barra);

        panel = new PanelFirma(this);
        panel.setAlCambiar(this::actualizarBotones);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.setMargins(m, 0, m, m);
        raiz.addView(panel, lp);

        setContentView(raiz);
        pantallaCompleta();
        actualizarBotones();
    }

    private void guardar() {
        Trazo t = panel.trazo();
        if (t == null) {
            panel.avisar();
            return;
        }
        Trazo guardada = Firmas.agregar(this, t);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ID, guardada.id));
        finish();
    }

    private void actualizarBotones() {
        boolean hay = !panel.vacio();
        deshacer.setEnabled(hay);
        borrar.setEnabled(hay);
    }

    /** Botón redondo de color para elegir la tinta. */
    private View muestra(String nombre, int color) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), 0x55888888);
        v.setBackground(g);
        v.setContentDescription("Tinta " + nombre.toLowerCase());
        v.setOnClickListener(x -> panel.setColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMargins(dp(6), 0, dp(6), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private Button plano(String texto, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(texto);
        b.setOnClickListener(l);
        return b;
    }

    private void pantallaCompleta() {
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            WindowInsetsController c = getWindow().getInsetsController();
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private int colorTema(int atributo) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(atributo, tv, true);
        return tv.data;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
