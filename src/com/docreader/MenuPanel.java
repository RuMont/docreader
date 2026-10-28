package com.docreader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Menú de opciones de los visores: hoja que sube desde abajo, con submenús anidados. Al entrar
 * en un submenú, el nivel actual se desplaza hacia la derecha y el nuevo entra por la izquierda;
 * al volver (botón ‹ de la cabecera o «Atrás») ocurre lo contrario. La altura se anima entre
 * niveles. Las acciones cierran el panel; los interruptores lo dejan abierto.
 */
public final class MenuPanel {
    /** Un nivel del menú: título y elementos. Se construye encadenando llamadas. */
    public static final class Nivel {
        final String titulo;
        final List<Elemento> elementos = new ArrayList<>();

        public Nivel(String titulo) { this.titulo = titulo; }

        public Nivel accion(Icono.Tipo icono, String texto, Runnable r) {
            Elemento e = new Elemento(icono, texto);
            e.accion = r;
            elementos.add(e);
            return this;
        }

        /** Submenú; se construye al abrirlo, así refleja el estado de ese momento. */
        public Nivel submenu(Icono.Tipo icono, String texto, Supplier<Nivel> nivel) {
            Elemento e = new Elemento(icono, texto);
            e.submenu = nivel;
            elementos.add(e);
            return this;
        }

        public Nivel interruptor(Icono.Tipo icono, String texto, boolean activo, Consumer<Boolean> alCambiar) {
            Elemento e = new Elemento(icono, texto);
            e.marcado = activo;
            e.alCambiar = alCambiar;
            elementos.add(e);
            return this;
        }

        /** Texto secundario bajo el último elemento añadido. */
        public Nivel detalle(String texto) {
            if (!elementos.isEmpty()) elementos.get(elementos.size() - 1).detalle = texto;
            return this;
        }

        /** Desactiva (en gris) el último elemento añadido si "activo" es false. */
        public Nivel activo(boolean activo) {
            if (!elementos.isEmpty()) elementos.get(elementos.size() - 1).activo = activo;
            return this;
        }
    }

    static final class Elemento {
        final Icono.Tipo icono;
        final String texto;
        String detalle;
        Runnable accion;
        Supplier<Nivel> submenu;
        Consumer<Boolean> alCambiar;
        boolean marcado, activo = true;

        Elemento(Icono.Tipo icono, String texto) {
            this.icono = icono;
            this.texto = texto;
        }
    }

    private static final int DURACION = 260;

    private final Activity a;
    private final Dialog dialogo;
    private final FrameLayout marco;
    private final Deque<View> pila = new ArrayDeque<>();
    private boolean animando;

    private MenuPanel(Activity a) {
        this.a = a;
        dialogo = new Dialog(a);
        dialogo.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout hoja = new LinearLayout(a);
        hoja.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Estilo.superficie(a));
        float r = dp(22);
        fondo.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        hoja.setBackground(fondo);
        hoja.setPadding(0, dp(8), 0, dp(10));
        hoja.setClipChildren(true);

        View asa = new View(a);
        asa.setBackground(Estilo.redondo(Estilo.linea(a), dp(2)));
        LinearLayout.LayoutParams lpAsa = new LinearLayout.LayoutParams(dp(36), dp(4));
        lpAsa.gravity = Gravity.CENTER_HORIZONTAL;
        lpAsa.bottomMargin = dp(4);
        hoja.addView(asa, lpAsa);

        marco = new FrameLayout(a);
        marco.setClipChildren(true);
        hoja.addView(marco, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        dialogo.setContentView(hoja);
        Window w = dialogo.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod); // sube desde abajo
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setNavigationBarColor(Estilo.superficie(a));
        }
        dialogo.setCanceledOnTouchOutside(true);
        // «Atrás» sube un nivel; en el principal, cierra.
        dialogo.setOnKeyListener((d, codigo, ev) -> {
            if (codigo == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP && pila.size() > 1) {
                volver();
                return true;
            }
            return false;
        });
    }

    /** Abre el panel con el nivel principal. */
    public static void mostrar(Activity a, Nivel principal) {
        if (a.isFinishing() || a.isDestroyed()) return;
        MenuPanel p = new MenuPanel(a);
        View v = p.pagina(principal, false);
        p.medir(v, a.getResources().getDisplayMetrics().widthPixels);
        p.pila.push(v);
        p.marco.addView(v);
        p.dialogo.show();
    }

    // --- Niveles ---

    private View pagina(Nivel n, boolean conVolver) {
        LinearLayout pagina = new LinearLayout(a);
        pagina.setOrientation(LinearLayout.VERTICAL);

        LinearLayout cabecera = new LinearLayout(a);
        cabecera.setGravity(Gravity.CENTER_VERTICAL);
        cabecera.setPadding(conVolver ? dp(6) : dp(22), dp(4), dp(16), dp(6));
        if (conVolver) cabecera.addView(Estilo.icono(a, Icono.Tipo.IZQUIERDA, "Volver", v -> volver()));
        TextView titulo = new TextView(a);
        titulo.setText(n.titulo);
        titulo.setTextSize(conVolver ? 16 : 14);
        titulo.setTextColor(conVolver ? Estilo.texto(a) : Estilo.texto2(a));
        titulo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titulo.setSingleLine(true);
        titulo.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titulo.setMinHeight(dp(44));
        titulo.setGravity(Gravity.CENTER_VERTICAL);
        titulo.setPadding(conVolver ? dp(4) : 0, 0, 0, 0);
        cabecera.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pagina.addView(cabecera);

        LinearLayout filas = new LinearLayout(a);
        filas.setOrientation(LinearLayout.VERTICAL);
        filas.setPadding(dp(8), 0, dp(8), 0);
        for (Elemento e : n.elementos) filas.addView(fila(e));
        ScrollView desplazable = new ScrollView(a);
        desplazable.setVerticalScrollBarEnabled(false);
        desplazable.setOverScrollMode(View.OVER_SCROLL_NEVER);
        desplazable.addView(filas);
        pagina.addView(desplazable, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return pagina;
    }

    private View fila(Elemento e) {
        LinearLayout f = new LinearLayout(a);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setMinimumHeight(dp(52));
        f.setPadding(dp(14), dp(6), dp(10), dp(6));
        f.setBackground(Estilo.tocable(a, 0, dp(14)));

        ImageView icono = new ImageView(a);
        if (e.icono != null) icono.setImageDrawable(new Icono(a, e.icono, Estilo.texto2(a)));
        f.addView(icono, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout textos = new LinearLayout(a);
        textos.setOrientation(LinearLayout.VERTICAL);
        textos.setPadding(dp(16), 0, dp(8), 0);
        TextView t = new TextView(a);
        t.setText(e.texto);
        t.setTextSize(15.5f);
        t.setTextColor(Estilo.texto(a));
        textos.addView(t);
        if (e.detalle != null) {
            TextView d = new TextView(a);
            d.setText(e.detalle);
            d.setTextSize(12.5f);
            d.setTextColor(Estilo.texto2(a));
            textos.addView(d);
        }
        f.addView(textos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (e.submenu != null) {
            ImageView flecha = new ImageView(a);
            flecha.setImageDrawable(new Icono(a, Icono.Tipo.DERECHA, Estilo.texto2(a)));
            f.addView(flecha, new LinearLayout.LayoutParams(dp(20), dp(20)));
            f.setOnClickListener(v -> entrar(e.submenu.get()));
        } else if (e.alCambiar != null) {
            Switch s = new Switch(a);
            s.setChecked(e.marcado);
            s.setClickable(false); // lo cambia el toque en toda la fila
            s.setFocusable(false);
            f.addView(s);
            f.setOnClickListener(v -> {
                e.marcado = !e.marcado;
                s.setChecked(e.marcado);
                e.alCambiar.accept(e.marcado);
            });
        } else {
            f.setOnClickListener(v -> {
                dialogo.dismiss();
                // Después de cerrar: la acción puede abrir otro diálogo o cambiar de pantalla.
                if (e.accion != null) a.getWindow().getDecorView().post(e.accion);
            });
        }
        f.setEnabled(e.activo);
        f.setAlpha(e.activo ? 1f : 0.4f);
        return f;
    }

    // --- Transiciones ---

    private void entrar(Nivel n) {
        if (animando) return;
        View actual = pila.peek(), nueva = pagina(n, true);
        pila.push(nueva);
        cambiar(actual, nueva, true);
    }

    private void volver() {
        if (animando || pila.size() <= 1) return;
        View actual = pila.pop(), anterior = pila.peek();
        cambiar(actual, anterior, false);
    }

    /**
     * Al bajar de nivel, todo se desplaza hacia la derecha: el actual sale por la derecha y el nuevo
     * entra por la izquierda. Al volver, al revés. A la vez, la altura pasa a la del nivel nuevo.
     */
    private void cambiar(View sale, View entra, boolean bajando) {
        animando = true;
        int ancho = marco.getWidth();
        int alto0 = marco.getHeight();
        int alto1 = medir(entra, ancho);
        if (entra.getParent() == null) marco.addView(entra);
        entra.setTranslationX(bajando ? -ancho : ancho);
        marco.getLayoutParams().height = alto0;
        marco.requestLayout();

        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(DURACION);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            sale.setTranslationX((bajando ? ancho : -ancho) * f);
            entra.setTranslationX((bajando ? -ancho : ancho) * (1 - f));
            marco.getLayoutParams().height = Math.round(alto0 + (alto1 - alto0) * f);
            marco.requestLayout();
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) {
                marco.removeView(sale);
                sale.setTranslationX(0);
                marco.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                entra.getLayoutParams().height = alto1;
                marco.requestLayout();
                animando = false;
            }
        });
        va.start();
    }

    /** Alto que ocupará la página (sin pasar del 75 % de la pantalla; si no cabe, se desplaza). */
    private int medir(View v, int ancho) {
        int maximo = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.75f);
        v.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Con la lista dentro de un ScrollView, se mide el contenido real de la página.
        LinearLayout pagina = (LinearLayout) v;
        View cab = pagina.getChildAt(0);
        ScrollView sv = (ScrollView) pagina.getChildAt(1);
        int ws = View.MeasureSpec.makeMeasureSpec(ancho, View.MeasureSpec.EXACTLY);
        int hs = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        cab.measure(ws, hs);
        sv.getChildAt(0).measure(ws, hs);
        int alto = Math.min(maximo, cab.getMeasuredHeight() + sv.getChildAt(0).getMeasuredHeight());
        v.getLayoutParams().height = alto;
        return alto;
    }

    private int dp(float v) { return Estilo.dp(a, v); }
}
