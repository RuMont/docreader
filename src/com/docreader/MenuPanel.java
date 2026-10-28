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

    private static final int DURACION_HOJA = 240;

    private final Activity a;
    private final Dialog dialogo;
    private final FrameLayout marco;
    private final LinearLayout hoja;
    private final View velo;
    private final Deque<View> pila = new ArrayDeque<>();
    private boolean animando, cerrando;

    /**
     * La ventana ocupa toda la pantalla (transparente) y el velo oscuro y la hoja se animan dentro:
     * así, al cambiar de nivel solo se recoloca la hoja y no se redimensiona la ventana en cada
     * fotograma, que es lo que hacía que la animación fuera a saltos.
     */
    private MenuPanel(Activity a) {
        this.a = a;
        dialogo = new Dialog(a);
        dialogo.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout raiz = new FrameLayout(a);
        velo = new View(a);
        velo.setBackgroundColor(0x5C000000);
        velo.setAlpha(0f);
        velo.setOnClickListener(v -> cerrar(null));
        raiz.addView(velo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        hoja = new LinearLayout(a);
        hoja.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Estilo.superficie(a));
        float r = dp(22);
        fondo.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        hoja.setBackground(fondo);
        hoja.setPadding(0, dp(8), 0, dp(10));
        hoja.setClipChildren(true);
        hoja.setClickable(true); // los toques en la hoja no llegan al velo

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
        raiz.addView(hoja, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        dialogo.setContentView(raiz);
        Window w = dialogo.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setWindowAnimations(0);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // De borde a borde: el velo cubre también las barras del sistema y la hoja deja
                // hueco abajo para la barra de navegación.
                w.setDecorFitsSystemWindows(false);
                w.setStatusBarColor(0);
                w.setNavigationBarColor(0);
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null && !Estilo.oscuro(a)) {
                    c.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
                raiz.setOnApplyWindowInsetsListener((v, ins) -> {
                    android.graphics.Insets b = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                    hoja.setPadding(b.left, dp(8), b.right, dp(10) + b.bottom);
                    return ins;
                });
            }
        }
        dialogo.setCanceledOnTouchOutside(false);
        // «Atrás» sube un nivel; en el principal, cierra.
        dialogo.setOnKeyListener((d, codigo, ev) -> {
            if (codigo != KeyEvent.KEYCODE_BACK) return false;
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                if (pila.size() > 1) volver();
                else cerrar(null);
            }
            return true;
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
        p.hoja.setVisibility(View.INVISIBLE);
        p.dialogo.show();
        p.hoja.post(p::entrarHoja);
    }

    /** La hoja sube desde abajo mientras aparece el velo. */
    private void entrarHoja() {
        hoja.setTranslationY(hoja.getHeight());
        hoja.setVisibility(View.VISIBLE);
        hoja.animate().translationY(0).setDuration(DURACION_HOJA)
                .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
        velo.animate().alpha(1f).setDuration(DURACION_HOJA).start();
    }

    /** Baja la hoja, cierra y, ya cerrado, ejecuta "despues" (si lo hay). */
    private void cerrar(Runnable despues) {
        if (cerrando) return;
        cerrando = true;
        hoja.animate().translationY(hoja.getHeight()).setDuration(DURACION_HOJA - 40)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f)).withLayer()
                .withEndAction(() -> {
                    dialogo.dismiss();
                    if (despues != null) a.getWindow().getDecorView().post(despues);
                }).start();
        velo.animate().alpha(0f).setDuration(DURACION_HOJA - 40).start();
    }

    // --- Niveles ---

    private View pagina(Nivel n, boolean conVolver) {
        LinearLayout pagina = new LinearLayout(a);
        pagina.setOrientation(LinearLayout.VERTICAL);

        LinearLayout cabecera = new LinearLayout(a);
        cabecera.setGravity(Gravity.CENTER_VERTICAL);
        cabecera.setPadding(conVolver ? dp(16) : dp(22), dp(4), dp(16), dp(6));
        if (conVolver) {
            // Toda la cabecera (flecha y título) vuelve al nivel anterior.
            ImageView flecha = new ImageView(a);
            flecha.setImageDrawable(new Icono(a, Icono.Tipo.IZQUIERDA, Estilo.texto(a)));
            cabecera.addView(flecha, new LinearLayout.LayoutParams(dp(22), dp(22)));
            cabecera.setBackground(Estilo.tocable(a, 0, dp(14)));
            cabecera.setContentDescription("Volver a " + (pila.isEmpty() ? "el menú" : "el nivel anterior"));
            cabecera.setOnClickListener(v -> volver());
            LinearLayout.LayoutParams lpCab = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lpCab.setMargins(dp(8), 0, dp(8), dp(2));
            cabecera.setLayoutParams(lpCab);
        }
        TextView titulo = new TextView(a);
        titulo.setText(n.titulo);
        titulo.setTextSize(conVolver ? 16 : 14);
        titulo.setTextColor(conVolver ? Estilo.texto(a) : Estilo.texto2(a));
        titulo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titulo.setSingleLine(true);
        titulo.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titulo.setMinHeight(dp(44));
        titulo.setGravity(Gravity.CENTER_VERTICAL);
        titulo.setPadding(conVolver ? dp(14) : 0, 0, 0, 0);
        cabecera.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (cabecera.getLayoutParams() != null) pagina.addView(cabecera, cabecera.getLayoutParams());
        else pagina.addView(cabecera);

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
            // Después de cerrar: la acción puede abrir otro diálogo o cambiar de pantalla.
            f.setOnClickListener(v -> cerrar(e.accion));
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
     * entra por la izquierda. Al volver, al revés. Para que vaya fluido no se recoloca nada en cada
     * fotograma: el marco toma una sola vez la altura mayor de los dos niveles y la hoja se desplaza
     * en vertical (lo que sobra queda bajo el borde de la pantalla); solo se mueven capas ya pintadas.
     */
    private void cambiar(View sale, View entra, boolean bajando) {
        animando = true;
        int ancho = marco.getWidth();
        int alto0 = marco.getHeight();
        int alto1 = medir(entra, ancho);
        int maximo = Math.max(alto0, alto1);
        if (entra.getParent() == null) marco.addView(entra);
        entra.setTranslationX(bajando ? -ancho : ancho);
        marco.getLayoutParams().height = maximo;
        marco.requestLayout();
        hoja.setTranslationY(maximo - alto0);

        sale.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        entra.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(DURACION);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            sale.setTranslationX((bajando ? ancho : -ancho) * f);
            entra.setTranslationX((bajando ? -ancho : ancho) * (1 - f));
            hoja.setTranslationY(maximo - (alto0 + (alto1 - alto0) * f));
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) {
                marco.removeView(sale);
                sale.setTranslationX(0);
                sale.setLayerType(View.LAYER_TYPE_NONE, null);
                entra.setLayerType(View.LAYER_TYPE_NONE, null);
                entra.getLayoutParams().height = alto1;
                marco.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                marco.requestLayout();
                hoja.setTranslationY(0);
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
