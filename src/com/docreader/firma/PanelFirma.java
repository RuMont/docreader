package com.docreader.firma;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Superficie blanca para dibujar la firma con el dedo, con una línea guía. */
final class PanelFirma extends View {
    private final List<List<PointF>> trazos = new ArrayList<>();
    private List<PointF> actual;
    private final Paint tinta = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guia = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pincel;
    private boolean aviso;
    private Runnable alCambiar;

    PanelFirma(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        pincel = 3.5f * d;
        tinta.setStyle(Paint.Style.STROKE);
        tinta.setStrokeCap(Paint.Cap.ROUND);
        tinta.setStrokeJoin(Paint.Join.ROUND);
        tinta.setStrokeWidth(pincel);
        tinta.setColor(Trazo.NEGRO);
        guia.setStyle(Paint.Style.STROKE);
        guia.setStrokeWidth(d);
        guia.setColor(0xFF9E9E9E);
        guia.setPathEffect(new DashPathEffect(new float[] {6 * d, 4 * d}, 0));
        texto.setColor(0xFF9E9E9E);
        texto.setTextSize(16 * d);
        setBackgroundColor(0xFFFFFFFF); // papel blanco también en modo oscuro
    }

    void setAlCambiar(Runnable r) { alCambiar = r; }

    void setColor(int c) {
        tinta.setColor(c);
        invalidate();
    }

    boolean vacio() { return trazos.isEmpty(); }

    void borrar() {
        trazos.clear();
        invalidate();
        if (alCambiar != null) alCambiar.run();
    }

    void deshacer() {
        if (trazos.isEmpty()) return;
        trazos.remove(trazos.size() - 1);
        invalidate();
        if (alCambiar != null) alCambiar.run();
    }

    void avisar() {
        aviso = true;
        invalidate();
    }

    Trazo trazo() {
        return trazos.isEmpty() ? null : Trazo.desdePuntos(trazos, pincel, tinta.getColor());
    }

    @Override protected void onDraw(Canvas c) {
        float y = getHeight() * 0.72f;
        c.drawLine(getWidth() * 0.06f, y, getWidth() * 0.94f, y, guia);
        if (trazos.isEmpty()) {
            texto.setColor(aviso ? 0xFFD32F2F : 0xFF9E9E9E);
            c.drawText(aviso ? "Dibuja la firma antes de guardarla" : "Firma aquí",
                    getWidth() * 0.06f, y + texto.getTextSize() * 1.6f, texto);
        }
        for (List<PointF> t : trazos) {
            Path p = new Path();
            p.moveTo(t.get(0).x, t.get(0).y);
            if (t.size() == 1) p.lineTo(t.get(0).x + 0.1f, t.get(0).y);
            for (int i = 1; i < t.size(); i++) p.lineTo(t.get(i).x, t.get(i).y);
            c.drawPath(p, tinta);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                actual = new ArrayList<>();
                trazos.add(actual);
                aviso = false;
                // fallthrough
            case MotionEvent.ACTION_MOVE:
                if (actual == null) return true;
                for (int h = 0; h < e.getHistorySize(); h++) {
                    actual.add(new PointF(e.getHistoricalX(h), e.getHistoricalY(h)));
                }
                actual.add(new PointF(e.getX(), e.getY()));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                actual = null;
                if (alCambiar != null) alCambiar.run();
                return true;
        }
        return true;
    }
}
