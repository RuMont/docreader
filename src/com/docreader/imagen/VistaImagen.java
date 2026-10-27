package com.docreader.imagen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/**
 * Muestra una imagen (mapa de bits o animación) con zoom con dos dedos, arrastre con inercia,
 * doble toque para ampliar/ajustar y giro en pasos de 90°. Las cuentas están en {@link Encuadre}.
 */
final class VistaImagen extends View {
    interface Oyente {
        void toque();
    }

    private final Encuadre encuadre = new Encuadre();
    private final Matrix matriz = new Matrix();
    private final float[] valores = new float[9];
    private final OverScroller scroller;
    private final ScaleGestureDetector escala;
    private final GestureDetector gestos;
    private ValueAnimator animacion;
    private Drawable imagen;
    private Oyente oyente;
    private boolean escalando, escaloEnDobleToque;

    VistaImagen(Context ctx) {
        super(ctx);
        scroller = new OverScroller(ctx);
        escala = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                if (imagen == null) return false;
                escalando = true;
                escaloEnDobleToque = true;
                detener();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                encuadre.zoomA(encuadre.escala() * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                invalidate();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                escalando = false;
            }
        });
        gestos = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                detener();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (escalando) return false;
                encuadre.desplazar(dx, dy);
                invalidate();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (escalando || imagen == null) return false;
                scroller.fling(encuadre.offX(), encuadre.offY(), (int) -vx, (int) -vy,
                        encuadre.minOffX(), encuadre.maxOffX(), encuadre.minOffY(), encuadre.maxOffY());
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (oyente != null) oyente.toque();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                escaloEnDobleToque = false;
                return true;
            }
            @Override public boolean onDoubleTapEvent(MotionEvent e) {
                // Se actúa al levantar el dedo: si lo arrastró fue un "zoom rápido", no un doble toque.
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !escaloEnDobleToque && imagen != null) {
                    animarHacia(encuadre.destinoDobleToque(e.getX(), e.getY()));
                }
                return true;
            }
        });
    }

    void setOyente(Oyente o) { oyente = o; }

    /** La vista no se queda con la propiedad del drawable: quien lo crea lo libera. */
    void setImagen(Drawable d) {
        detener();
        if (imagen != null) imagen.setCallback(null);
        imagen = d;
        if (d != null) {
            d.setCallback(this);
            d.setBounds(0, 0, ancho(d), alto(d));
            encuadre.setImagen(ancho(d), alto(d));
        } else {
            encuadre.setImagen(0, 0);
        }
        invalidate();
    }

    /**
     * Tamaño en píxeles de la imagen. Para mapas de bits se usa el del Bitmap: el tamaño
     * "intrínseco" de un BitmapDrawable depende de la densidad de pantalla.
     */
    static int ancho(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap().getWidth();
        }
        return d.getIntrinsicWidth();
    }

    static int alto(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap().getHeight();
        }
        return d.getIntrinsicHeight();
    }

    void girar() {
        detener();
        encuadre.girar();
        invalidate();
    }

    void ajustar() {
        detener();
        if (imagen != null) animarHacia(ajustada());
    }

    boolean ajustado() { return encuadre.ajustado(); }

    private Encuadre.Estado ajustada() {
        Encuadre.Estado antes = encuadre.estado();
        encuadre.ajustar();
        Encuadre.Estado fin = encuadre.estado();
        encuadre.aplicarSinLimitar(antes);
        return fin;
    }

    private void animarHacia(Encuadre.Estado fin) {
        detener();
        Encuadre.Estado inicio = encuadre.estado();
        animacion = ValueAnimator.ofFloat(0f, 1f);
        animacion.setDuration(220);
        animacion.setInterpolator(new DecelerateInterpolator());
        animacion.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            if (t >= 1f) encuadre.aplicar(fin);
            else encuadre.aplicarSinLimitar(Encuadre.interpolar(inicio, fin, t));
            invalidate();
        });
        animacion.start();
    }

    private void detener() {
        scroller.forceFinished(true);
        if (animacion != null) {
            animacion.cancel();
            animacion = null;
            encuadre.aplicar(encuadre.estado()); // deja un encuadre válido donde se quedó
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        detener();
        encuadre.setVista(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        if (imagen == null || !encuadre.valido()) return;
        encuadre.valores(valores);
        matriz.setValues(valores);
        int guardado = c.save();
        c.concat(matriz);
        imagen.draw(c);
        c.restoreToCount(guardado);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        escala.onTouchEvent(ev);
        gestos.onTouchEvent(ev);
        return true;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            encuadre.colocarOffset(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    // Los drawables animados piden redibujarse a través del callback.
    @Override protected boolean verifyDrawable(Drawable who) {
        return who == imagen || super.verifyDrawable(who);
    }

    @Override public void invalidateDrawable(Drawable who) {
        if (who == imagen) invalidate();
        else super.invalidateDrawable(who);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (imagen instanceof Animatable) ((Animatable) imagen).start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        detener();
        if (imagen instanceof Animatable) ((Animatable) imagen).stop();
    }
}
