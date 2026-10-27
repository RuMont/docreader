package com.docreader;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.BlendMode;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Páginas en scroll vertical continuo con zoom.
 * Coordenadas de "contenido": píxeles de pantalla con zoom 1 (cada página ajustada al ancho).
 * Con zoom > 1 se renderiza aparte, a resolución completa, solo la zona visible (teselas).
 */
public final class VistaPdf extends View {
    interface Oyente {
        void pagina(int actual, int total);
        void toque();
        void enlace(Uri uri);
        /** Selección nueva o cambiada (null = sin selección). */
        void seleccion(Seleccion s, boolean arrastrando);
        void seleccionMovida();
        /** Pulsación larga en una página sin texto que aún no tiene OCR. */
        void necesitaOcr(int pag, PointF punto);
        void anotacionTocada(Anotaciones.Anotacion a);
    }

    static final class Enlace { RectF r; int destino = -1; Uri uri; }

    /** Lo que se está colocando sobre la página (una firma). */
    public interface Contenido {
        void dibujar(Canvas c, RectF destino);
        /** Ancho / alto que debe conservar al cambiar de tamaño. */
        float aspecto();
    }
    static final class Tesela { int pag; RectF r; Matrix m; int w, h; Bitmap b; }
    static final class Coincidencia {
        final int pag; final List<RectF> rects;
        Coincidencia(int pag, List<RectF> rects) { this.pag = pag; this.rects = rects; }
    }

    private static final float ZOOM_MAX = 6f;
    /**
     * Máximo de píxeles de la imagen base de una página (~16 MB). En horizontal una página a lo
     * ancho de la pantalla pasaba de 30 MB: dos visibles más las precargadas no cabían en la caché,
     * se expulsaban entre sí y se redibujaban sin parar (parpadeo). Si la base queda por debajo de
     * la resolución de pantalla, la zona visible se completa con teselas de detalle.
     */
    private static final float MAX_PIXELES_BASE = 4_000_000f;

    private Motor motor;
    private Oyente oyente;
    private int n;
    private float[] pw, ph, top, left;
    private float base, totalH;
    private final float gap;
    private float zoom = 1f, offX, offY;
    private float minX, maxX, minY, maxY;
    private volatile int visMin, visMax;
    private int paginaActual = -1, paginaInicial;
    private boolean escalando, noche;

    private final LruCache<Integer, Bitmap> bases = new LruCache<Integer, Bitmap>(128 << 20) {
        @Override protected int sizeOf(Integer k, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private final Set<Integer> pedidas = new HashSet<>(), fallidas = new HashSet<>();
    private final SparseArray<List<Enlace>> enlaces = new SparseArray<>();
    private List<Tesela> teselas = new ArrayList<>();
    private int genDetalle;
    private List<Coincidencia> coincidencias;
    private int coincidenciaActual = -1;

    private Seleccion sel;
    private int genSel, arrastre; // arrastre: 0 nada, 1 tirador inicial, 2 tirador final
    private float agarreX, agarreY;
    private final float radio;
    private List<Anotaciones.Anotacion> anotaciones = new ArrayList<>();

    // Colocación de una firma: rectángulo en puntos de la página "colPag".
    private Contenido colocando;
    private int colPag, colModo; // colModo: 0 nada, 1 mover, 2 cambiar tamaño
    private RectF colRect;
    private float colX, colY;
    private final Paint pColBorde = new Paint(Paint.ANTI_ALIAS_FLAG), pColFondo = new Paint();

    private final Paint pPagina = new Paint(), pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pResalte = new Paint(), pActual = new Paint();
    private final Paint pSel = new Paint(), pTirador = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAnot = new Paint(), pNota = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final OverScroller scroller;
    private final ScaleGestureDetector escala;
    private final GestureDetector gestos;
    private ValueAnimator animacion;
    private final Runnable rDetalle = this::pedirDetalle;

    public VistaPdf(Context ctx) {
        super(ctx);
        gap = 8 * getResources().getDisplayMetrics().density;
        pPagina.setColor(0xFFFFFFFF);
        pResalte.setColor(0x66FFD600);
        pActual.setColor(0x88FF6D00);
        pSel.setColor(0x553D7EFF);
        pTirador.setColor(0xFF3D7EFF);
        radio = 11 * getResources().getDisplayMetrics().density;
        pColBorde.setStyle(Paint.Style.STROKE);
        pColBorde.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        pColBorde.setColor(0xFF3D7EFF);
        pColBorde.setPathEffect(new DashPathEffect(new float[] {radio, radio / 2}, 0));
        pColFondo.setColor(0x1A3D7EFF);
        scroller = new OverScroller(ctx);
        escala = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                escalando = true;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomA(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                escalando = false;
                cambiado();
            }
        });
        gestos = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                if (animacion != null) animacion.cancel();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                offX += dx;
                offY += dy;
                cambiado();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (escalando) return false;
                scroller.fling((int) offX, (int) offY, (int) -vx, (int) -vy,
                        (int) minX, (int) maxX, (int) minY, (int) maxY);
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (sel != null) {
                    borrarSeleccion();
                    return true;
                }
                if (tocarAnotacion(e.getX(), e.getY()) || tocarEnlace(e.getX(), e.getY())) return true;
                if (oyente != null) oyente.toque();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (escalando || n == 0 || base == 0 || colocando != null) return;
                int pag = paginaEn(e.getX(), e.getY());
                if (pag < 0) return;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                seleccionarEn(pag, aPagina(pag, e.getX(), e.getY()));
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                animarZoom(zoom > 1.5f ? 1f : 2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    void setOyente(Oyente o) { oyente = o; }

    void setDocumento(Motor m, int inicial) {
        motor = m;
        n = m.paginas;
        pw = m.anchos;
        ph = m.altos;
        top = new float[n];
        left = new float[n];
        paginaInicial = Math.max(0, Math.min(inicial, n - 1));
        if (getWidth() > 0) {
            maquetar();
            irA(paginaInicial);
        }
    }

    int paginaActual() { return Math.max(paginaActual, 0); }

    void setNoche(boolean b) {
        noche = b;
        ColorMatrixColorFilter f = b ? new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0})) : null;
        pPagina.setColorFilter(f);
        pBmp.setColorFilter(f);
        invalidate();
    }

    void setCoincidencias(List<Coincidencia> lista, int actual) {
        coincidencias = lista;
        coincidenciaActual = actual;
        if (lista != null && actual >= 0 && actual < lista.size()) {
            Coincidencia c = lista.get(actual);
            RectF u = new RectF(c.rects.get(0));
            for (RectF r : c.rects) u.union(r);
            centrarEn(c.pag, u);
        }
        invalidate();
    }

    // --- Colocación de firmas ---

    /** Empieza a colocar un contenido en el centro de la parte visible de la página actual. */
    void empezarColocacion(Contenido c) {
        borrarSeleccion();
        colocando = c;
        colocarEnPaginaActual();
    }

    /** Lleva el contenido a la página que se está viendo (centrado en lo visible). */
    void colocarEnPaginaActual() {
        if (colocando == null || n == 0 || base == 0) return;
        int pag = paginaActual();
        float w = pw[pag] * 0.4f, h = w / colocando.aspecto();
        if (h > ph[pag] * 0.3f) {
            h = ph[pag] * 0.3f;
            w = h * colocando.aspecto();
        }
        PointF centro = aPagina(pag, getWidth() / 2f, getHeight() / 2f);
        float cx = Math.max(w / 2, Math.min(centro.x, pw[pag] - w / 2));
        float cy = Math.max(h / 2, Math.min(centro.y, ph[pag] - h / 2));
        colPag = pag;
        colRect = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        invalidate();
    }

    void terminarColocacion() {
        colocando = null;
        colModo = 0;
        invalidate();
    }

    boolean colocando() { return colocando != null; }

    int paginaColocacion() { return colPag; }

    /** Rectángulo colocado en fracciones (0..1) de la página. */
    RectF fraccionColocacion() {
        return new RectF(colRect.left / pw[colPag], colRect.top / ph[colPag],
                colRect.right / pw[colPag], colRect.bottom / ph[colPag]);
    }

    private RectF colocacionEnVista() {
        return new RectF(vistaX(colPag, colRect.left), vistaY(colPag, colRect.top),
                vistaX(colPag, colRect.right), vistaY(colPag, colRect.bottom));
    }

    private boolean empezarColocar(MotionEvent ev) {
        if (colocando == null) return false;
        RectF v = colocacionEnVista();
        if (Math.hypot(ev.getX() - v.right, ev.getY() - v.bottom) < radio * 2.5f) colModo = 2;
        else if (v.contains(ev.getX(), ev.getY())) colModo = 1;
        else return false;
        colX = ev.getX();
        colY = ev.getY();
        return true;
    }

    private void moverColocacion(MotionEvent ev) {
        float escala = base * zoom;
        float dx = (ev.getX() - colX) / escala, dy = (ev.getY() - colY) / escala;
        colX = ev.getX();
        colY = ev.getY();
        RectF r = colRect;
        if (colModo == 1) {
            dx = Math.max(-r.left, Math.min(dx, pw[colPag] - r.right));
            dy = Math.max(-r.top, Math.min(dy, ph[colPag] - r.bottom));
            r.offset(dx, dy);
        } else {
            float asp = colocando.aspecto();
            float delta = Math.abs(dx) > Math.abs(dy * asp) ? dx : dy * asp;
            float w = Math.max(pw[colPag] * 0.08f, r.width() + delta);
            w = Math.min(w, Math.min(pw[colPag] - r.left, (ph[colPag] - r.top) * asp));
            r.right = r.left + w;
            r.bottom = r.top + w / asp;
        }
        invalidate();
    }

    private void dibujarColocacion(Canvas c) {
        RectF v = colocacionEnVista();
        c.drawRect(v, pColFondo);
        colocando.dibujar(c, v);
        c.drawRect(v, pColBorde);
        c.drawCircle(v.right, v.bottom, radio, pTirador);
    }

    // --- Selección ---

    Seleccion getSeleccion() { return sel; }

    /** Selecciona la palabra en un punto de la página (en puntos de la página). */
    void seleccionarEn(int pag, PointF p) {
        motor.seleccionar(++genSel, pag, p, p, (gen, s, necesitaOcr) -> {
            if (gen != genSel) return;
            if (s == null) {
                if (necesitaOcr && oyente != null) oyente.necesitaOcr(pag, p);
                return;
            }
            RectF r0 = s.primero(), r1 = s.ultimo();
            s.a = new PointF(r0.left + 0.5f, r0.centerY());
            s.b = new PointF(r1.right - 0.5f, r1.centerY());
            sel = s;
            invalidate();
            if (oyente != null) oyente.seleccion(sel, false);
        });
    }

    void borrarSeleccion() {
        genSel++;
        arrastre = 0;
        if (sel == null) return;
        sel = null;
        invalidate();
        if (oyente != null) oyente.seleccion(null, false);
    }

    /** Rectángulo de la selección en coordenadas de la vista (para la barra flotante). */
    void rectSeleccion(Rect out) {
        if (sel == null) {
            out.setEmpty();
            return;
        }
        RectF u = new RectF();
        for (RectF r : sel.rects) {
            u.union(left[sel.pag] + r.left * base, top[sel.pag] + r.top * base,
                    left[sel.pag] + r.right * base, top[sel.pag] + r.bottom * base);
        }
        out.set(Math.round(u.left * zoom - offX), Math.round(u.top * zoom - offY),
                Math.round(u.right * zoom - offX), Math.round(u.bottom * zoom - offY));
        out.intersect(0, 0, getWidth(), getHeight());
    }

    void setAnotaciones(List<Anotaciones.Anotacion> lista) {
        anotaciones = lista;
        invalidate();
    }

    void irAAnotacion(Anotaciones.Anotacion a) {
        if (a.rects.isEmpty()) irA(a.pag);
        else centrarEn(a.pag, a.rects.get(0));
    }

    private int paginaEn(float x, float y) {
        float cx = (x + offX) / zoom, cy = (y + offY) / zoom;
        int i = primera(cy);
        rectPagina(i, tmp);
        return tmp.contains(cx, cy) ? i : -1;
    }

    /** Punto de la vista → puntos de la página indicada. */
    private PointF aPagina(int pag, float x, float y) {
        return new PointF(((x + offX) / zoom - left[pag]) / base, ((y + offY) / zoom - top[pag]) / base);
    }

    private float vistaX(int pag, float px) { return (left[pag] + px * base) * zoom - offX; }

    private float vistaY(int pag, float py) { return (top[pag] + py * base) * zoom - offY; }

    /** Centro del tirador 1 (inicio) o 2 (fin) en coordenadas de la vista. */
    private PointF centroTirador(int cual) {
        RectF r = cual == 1 ? sel.primero() : sel.ultimo();
        float ax = vistaX(sel.pag, cual == 1 ? r.left : r.right), ay = vistaY(sel.pag, r.bottom);
        return new PointF(cual == 1 ? ax - radio : ax + radio, ay + radio);
    }

    private boolean empezarArrastre(MotionEvent ev) {
        if (sel == null) return false;
        for (int cual = 1; cual <= 2; cual++) {
            PointF c = centroTirador(cual);
            if (Math.hypot(ev.getX() - c.x, ev.getY() - c.y) < radio * 2.2f) {
                arrastre = cual;
                RectF r = cual == 1 ? sel.primero() : sel.ultimo();
                agarreX = ev.getX() - vistaX(sel.pag, cual == 1 ? r.left : r.right);
                agarreY = ev.getY() - vistaY(sel.pag, r.bottom);
                if (oyente != null) oyente.seleccion(sel, true);
                return true;
            }
        }
        return false;
    }

    private void arrastrar(MotionEvent ev) {
        Seleccion s = sel;
        RectF r = arrastre == 1 ? s.primero() : s.ultimo();
        // El tirador cuelga bajo la línea: se apunta al centro vertical de la línea.
        PointF p = aPagina(s.pag, ev.getX() - agarreX, ev.getY() - agarreY - r.height() * base * zoom / 2);
        p.x = Math.max(0, Math.min(p.x, pw[s.pag]));
        p.y = Math.max(0, Math.min(p.y, ph[s.pag]));
        PointF a = arrastre == 1 ? p : s.a, b = arrastre == 2 ? p : s.b;
        motor.seleccionar(++genSel, s.pag, a, b, (gen, nueva, ocr) -> {
            if (gen != genSel || nueva == null || sel == null) return;
            sel = nueva;
            invalidate();
            if (arrastre == 0 && oyente != null) oyente.seleccionMovida();
        });
    }

    private boolean tocarAnotacion(float x, float y) {
        int i = paginaEn(x, y);
        if (i < 0 || oyente == null) return false;
        PointF p = aPagina(i, x, y);
        for (Anotaciones.Anotacion a : anotaciones) {
            if (a.pag != i) continue;
            for (RectF r : a.rects) {
                if (p.x >= r.left - 2 && p.x <= r.right + 2 && p.y >= r.top - 2 && p.y <= r.bottom + 2) {
                    oyente.anotacionTocada(a);
                    return true;
                }
            }
        }
        return false;
    }

    void irA(int pag) {
        if (n == 0 || base == 0) {
            paginaInicial = pag;
            return;
        }
        pag = Math.max(0, Math.min(pag, n - 1));
        scroller.forceFinished(true);
        offY = (top[pag] - gap) * zoom;
        cambiado();
    }

    /** Centra la pantalla en un rectángulo dado en puntos de la página. */
    private void centrarEn(int pag, RectF r) {
        if (n == 0 || base == 0) return;
        scroller.forceFinished(true);
        float cx = left[pag] + r.centerX() * base, cy = top[pag] + r.centerY() * base;
        offX = cx * zoom - getWidth() / 2f;
        offY = cy * zoom - getHeight() / 2f;
        cambiado();
    }

    // --- Maquetación ---

    private void maquetar() {
        float maxW = 0;
        for (float w : pw) maxW = Math.max(maxW, w);
        base = getWidth() / maxW;
        float y = gap;
        for (int i = 0; i < n; i++) {
            top[i] = y;
            left[i] = (getWidth() - pw[i] * base) / 2f;
            y += ph[i] * base + gap;
        }
        totalH = y;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (n == 0 || w == 0) return;
        if (base == 0) {
            maquetar();
            irA(paginaInicial);
            return;
        }
        // Conservar el punto que estaba en el centro de la pantalla.
        float cx = (offX + ow / 2f) / zoom, cy = (offY + oh / 2f) / zoom;
        int pag = primera(cy);
        float fy = (cy - top[pag]) / (ph[pag] * base);
        float fx = ow > 0 ? cx / ow : 0.5f;
        float baseAnterior = base;
        maquetar();
        if (base != baseAnterior) {
            bases.evictAll();
            pedidas.clear();
            fallidas.clear();
            teselas = new ArrayList<>();
        }
        offX = fx * w * zoom - w / 2f;
        offY = (top[pag] + fy * ph[pag] * base) * zoom - h / 2f;
        cambiado();
    }

    private int primera(float y) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (top[mid] <= y) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private void rectPagina(int i, RectF r) {
        r.set(left[i], top[i], left[i] + pw[i] * base, top[i] + ph[i] * base);
    }

    private void limitar() {
        float w = getWidth(), h = getHeight();
        float cw = w * zoom, ch = totalH * zoom;
        minX = 0;
        maxX = Math.max(0, cw - w);
        if (ch <= h) {
            minY = maxY = -(h - ch) / 2f;
        } else {
            minY = 0;
            maxY = ch - h;
        }
        offX = Math.max(minX, Math.min(offX, maxX));
        offY = Math.max(minY, Math.min(offY, maxY));
    }

    private void cambiado() {
        if (n == 0 || base == 0) return;
        limitar();
        visMin = primera(offY / zoom);
        visMax = primera((offY + getHeight()) / zoom);
        invalidate();
        removeCallbacks(rDetalle);
        if (zoom > 1.05f || baseReducida()) {
            postDelayed(rDetalle, 150);
        } else if (!teselas.isEmpty()) {
            teselas = new ArrayList<>();
            motor.genDetalle = ++genDetalle;
        }
        if (sel != null && oyente != null) oyente.seleccionMovida();
        int p = offY >= maxY - 1 && maxY > 0 ? n - 1 : primera((offY + getHeight() / 2f) / zoom);
        if (p != paginaActual) {
            paginaActual = p;
            if (oyente != null) oyente.pagina(p, n);
        }
    }

    // --- Dibujo ---

    @Override protected void onDraw(Canvas c) {
        c.drawColor(noche ? 0xFF2B2B2B : 0xFFDADADA);
        if (n == 0 || base == 0) return;
        int a = visMin, b = visMax;
        c.save();
        c.translate(-offX, -offY);
        c.scale(zoom, zoom);
        for (int i = a; i <= b; i++) {
            rectPagina(i, tmp);
            c.drawRect(tmp, pPagina);
            Bitmap bm = bases.get(i);
            if (bm != null) c.drawBitmap(bm, null, tmp, pBmp);
            else pedir(i);
        }
        pedir(a - 1);
        pedir(b + 1);
        for (Tesela t : teselas) {
            if (t.pag >= a && t.pag <= b) c.drawBitmap(t.b, null, t.r, pBmp);
        }
        dibujarAnotaciones(c, a, b);
        if (sel != null) {
            for (RectF r : sel.rects) {
                tmp.set(left[sel.pag] + r.left * base, top[sel.pag] + r.top * base,
                        left[sel.pag] + r.right * base, top[sel.pag] + r.bottom * base);
                c.drawRect(tmp, pSel);
            }
        }
        if (coincidencias != null) {
            for (int k = 0; k < coincidencias.size(); k++) {
                Coincidencia co = coincidencias.get(k);
                if (co.pag < a || co.pag > b) continue;
                Paint p = k == coincidenciaActual ? pActual : pResalte;
                for (RectF r : co.rects) {
                    tmp.set(left[co.pag] + r.left * base, top[co.pag] + r.top * base,
                            left[co.pag] + r.right * base, top[co.pag] + r.bottom * base);
                    c.drawRect(tmp, p);
                }
            }
        }
        c.restore();
        if (sel != null) dibujarTiradores(c);
        if (colocando != null && colPag >= a - 1 && colPag <= b + 1) dibujarColocacion(c);
    }

    private void dibujarAnotaciones(Canvas c, int a, int b) {
        pAnot.setBlendMode(noche ? null : BlendMode.MULTIPLY);
        for (Anotaciones.Anotacion an : anotaciones) {
            if (an.pag < a || an.pag > b) continue;
            float l = left[an.pag], t = top[an.pag];
            for (RectF r : an.rects) {
                if (an.tipo == Anotaciones.SUBRAYADO) {
                    pAnot.setColor(an.color);
                    float grosor = Math.max(1.2f, r.height() * 0.09f) * base;
                    float y = t + r.bottom * base;
                    c.drawRect(l + r.left * base, y - grosor, l + r.right * base, y + grosor * 0.3f, pAnot);
                } else {
                    pAnot.setColor(noche ? (an.color & 0x00FFFFFF) | 0x66000000 : (an.color & 0x00FFFFFF) | 0xB0000000);
                    c.drawRect(l + r.left * base, t + r.top * base, l + r.right * base, t + r.bottom * base, pAnot);
                }
            }
            if (!an.nota.isEmpty() && !an.rects.isEmpty()) {
                // Marca de nota: un cuadradito con esquina doblada junto al final.
                RectF r = an.rects.get(an.rects.size() - 1);
                float lado = Math.max(7f, r.height() * 0.8f) * base;
                float x = l + r.right * base + 2 * base, y = t + r.top * base;
                pNota.setColor(an.color | 0xFF000000);
                c.drawRect(x, y, x + lado, y + lado, pNota);
                pNota.setColor(0x55000000);
                c.drawRect(x + lado * 0.6f, y, x + lado, y + lado * 0.4f, pNota);
            }
        }
    }

    private void dibujarTiradores(Canvas c) {
        for (int cual = 1; cual <= 2; cual++) {
            PointF ctr = centroTirador(cual);
            c.drawCircle(ctr.x, ctr.y, radio, pTirador);
            // Esquina en punta hacia el texto (forma de gota).
            if (cual == 1) c.drawRect(ctr.x, ctr.y - radio, ctr.x + radio, ctr.y, pTirador);
            else c.drawRect(ctr.x - radio, ctr.y - radio, ctr.x, ctr.y, pTirador);
        }
    }

    /** Fracción (≤ 1) de la resolución de pantalla a la que se guarda la página base. */
    private float escalaBase(int i) {
        float w = pw[i] * base, h = ph[i] * base;
        return Math.min(1f, (float) Math.sqrt(MAX_PIXELES_BASE / Math.max(1f, w * h)));
    }

    private int anchoBase(int i) { return Math.max(1, Math.round(pw[i] * base * escalaBase(i))); }

    /** ¿Alguna página visible tiene la base reducida? Entonces hace falta detalle también sin zoom. */
    private boolean baseReducida() {
        for (int i = visMin; i <= visMax && i < n; i++) if (escalaBase(i) < 0.99f) return true;
        return false;
    }

    private void pedir(int i) {
        if (i < 0 || i >= n || pedidas.contains(i) || fallidas.contains(i) || bases.get(i) != null) return;
        pedidas.add(i);
        int ancho = anchoBase(i);
        motor.base(i, ancho, pag -> pag >= visMin - 2 && pag <= visMax + 2, (pag, bm, enl, fallo) -> {
            pedidas.remove(pag);
            if (fallo) fallidas.add(pag);
            if (bm == null) {
                invalidate();
                return;
            }
            if (bm.getWidth() != anchoBase(pag)) return; // renderizada antes de un cambio de tamaño
            bases.put(pag, bm);
            if (enl != null) enlaces.put(pag, enl);
            invalidate();
        });
    }

    private void pedirDetalle() {
        if (!scroller.isFinished() || escalando) {
            postDelayed(rDetalle, 100);
            return;
        }
        int gen = ++genDetalle;
        motor.genDetalle = gen;
        RectF vista = new RectF(offX / zoom, offY / zoom,
                (offX + getWidth()) / zoom, (offY + getHeight()) / zoom);
        List<Tesela> specs = new ArrayList<>();
        for (int i = visMin; i <= visMax; i++) {
            RectF r = new RectF();
            rectPagina(i, r);
            RectF in = new RectF();
            if (!in.setIntersect(r, vista)) continue;
            Tesela t = new Tesela();
            t.pag = i;
            t.w = (int) Math.ceil(in.width() * zoom);
            t.h = (int) Math.ceil(in.height() * zoom);
            if (t.w <= 0 || t.h <= 0) continue;
            in.right = in.left + t.w / zoom;
            in.bottom = in.top + t.h / zoom;
            t.r = in;
            t.m = new Matrix();
            t.m.setScale(base * zoom, base * zoom);
            t.m.postTranslate(-(in.left - r.left) * zoom, -(in.top - r.top) * zoom);
            specs.add(t);
        }
        motor.detalle(gen, specs, (g, lista) -> {
            if (g != genDetalle) return;
            teselas = lista;
            invalidate();
        });
    }

    // --- Gestos ---

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int accion = ev.getActionMasked();
        if (accion == MotionEvent.ACTION_DOWN && empezarColocar(ev)) return true;
        if (colModo != 0) {
            if (accion == MotionEvent.ACTION_MOVE) moverColocacion(ev);
            else if (accion == MotionEvent.ACTION_UP || accion == MotionEvent.ACTION_CANCEL) colModo = 0;
            return true;
        }
        if (accion == MotionEvent.ACTION_DOWN && empezarArrastre(ev)) return true;
        if (arrastre != 0) {
            if (accion == MotionEvent.ACTION_MOVE) {
                arrastrar(ev);
            } else if (accion == MotionEvent.ACTION_UP || accion == MotionEvent.ACTION_CANCEL) {
                arrastre = 0;
                if (oyente != null && sel != null) oyente.seleccion(sel, false);
            }
            return true;
        }
        escala.onTouchEvent(ev);
        gestos.onTouchEvent(ev);
        return true;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.getCurrX();
            offY = scroller.getCurrY();
            cambiado();
            postInvalidateOnAnimation();
        }
    }

    private void zoomA(float z, float fx, float fy) {
        z = Math.max(1f, Math.min(z, ZOOM_MAX));
        float f = z / zoom;
        offX = (offX + fx) * f - fx;
        offY = (offY + fy) * f - fy;
        zoom = z;
        cambiado();
    }

    private void animarZoom(float destino, float fx, float fy) {
        if (animacion != null) animacion.cancel();
        animacion = ValueAnimator.ofFloat(zoom, destino);
        animacion.setDuration(220);
        animacion.addUpdateListener(a -> zoomA((Float) a.getAnimatedValue(), fx, fy));
        animacion.start();
    }

    private boolean tocarEnlace(float x, float y) {
        if (n == 0 || base == 0) return false;
        float cx = (x + offX) / zoom, cy = (y + offY) / zoom;
        int i = primera(cy);
        rectPagina(i, tmp);
        if (!tmp.contains(cx, cy)) return false;
        List<Enlace> lista = enlaces.get(i);
        if (lista == null) return false;
        float px = (cx - left[i]) / base, py = (cy - top[i]) / base;
        for (Enlace e : lista) {
            if (!e.r.contains(px, py)) continue;
            if (e.destino >= 0) {
                irA(e.destino);
                return true;
            }
            if (e.uri != null && oyente != null) {
                oyente.enlace(e.uri);
                return true;
            }
        }
        return false;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(rDetalle);
        if (animacion != null) animacion.cancel();
    }
}
