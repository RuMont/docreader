package com.docreader.firma;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Aspecto visible de una firma con certificado: firma manuscrita opcional a la izquierda y
 * el texto «Firmado digitalmente por…» a la derecha. Se dibuja igual en pantalla y en el PDF.
 */
public final class Apariencia {
    public static final float ASPECTO = 3.2f;

    final String[] lineas;
    final Trazo trazo;

    public Apariencia(String nombre, Date fecha, Trazo trazo) {
        this.trazo = trazo;
        String f = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(fecha);
        lineas = new String[] {"Firmado digitalmente por", nombre, "Fecha: " + f};
    }

    /** Parte izquierda (firma manuscrita) y derecha (texto), en fracciones del ancho. */
    private float corte() { return trazo == null ? 0f : 0.38f; }

    // --- Pantalla ---

    public void dibujar(Canvas c, RectF r) {
        Paint borde = new Paint(Paint.ANTI_ALIAS_FLAG);
        borde.setStyle(Paint.Style.STROKE);
        borde.setStrokeWidth(Math.max(1f, r.height() * 0.012f));
        borde.setColor(0xFF5A6B8C);
        Paint fondo = new Paint();
        fondo.setColor(0xF2FFFFFF);
        c.drawRect(r, fondo);
        c.drawRect(r, borde);
        float m = r.height() * 0.08f;
        if (trazo != null) {
            RectF zona = new RectF(r.left + m, r.top + m, r.left + r.width() * corte() - m / 2, r.bottom - m);
            trazo.dibujar(c, ajustar(zona, trazo.aspecto));
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1A1A1A);
        RectF t = new RectF(r.left + r.width() * corte() + m, r.top + m, r.right - m, r.bottom - m);
        float tam = tamanoPantalla(p, t);
        float y = t.top + (t.height() - tam * 1.25f * lineas.length) / 2 + tam;
        for (int i = 0; i < lineas.length; i++) {
            p.setTypeface(i == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextSize(i == 1 ? tam : tam * 0.85f);
            c.drawText(lineas[i], t.left, y, p);
            y += tam * 1.25f;
        }
    }

    private float tamanoPantalla(Paint p, RectF t) {
        float tam = t.height() / (lineas.length * 1.25f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        for (String l : lineas) {
            p.setTextSize(100);
            float ancho = p.measureText(l) / 100f;
            if (ancho > 0) tam = Math.min(tam, t.width() / ancho);
        }
        return tam;
    }

    // --- PDF (caja de w×h puntos con origen abajo a la izquierda) ---

    void aPdf(PDPageContentStream cs, PDFont fuente, float w, float h) throws IOException {
        cs.setStrokingColor(0x5A / 255f, 0x6B / 255f, 0x8C / 255f);
        float lw = Math.max(0.5f, h * 0.012f);
        cs.setLineWidth(lw);
        cs.addRect(lw / 2, lw / 2, w - lw, h - lw); // dentro de la caja: si no, el visor recorta el borde
        cs.stroke();
        float m = h * 0.08f;
        if (trazo != null) {
            RectF zona = ajustar(new RectF(m, m, w * corte() - m / 2, h - m), trazo.aspecto);
            trazo.aPdf(cs, zona.left, zona.top, zona.width(), zona.height());
        }
        float tx = w * corte() + m, ancho = w - tx - m, alto = h - 2 * m;
        float tam = alto / (lineas.length * 1.25f);
        for (String l : lineas) {
            float a = fuente.getStringWidth(l) / 1000f;
            if (a > 0) tam = Math.min(tam, ancho / a);
        }
        float y = m + alto - (alto - tam * 1.25f * lineas.length) / 2 - tam;
        cs.setNonStrokingColor(0.1f, 0.1f, 0.1f);
        for (int i = 0; i < lineas.length; i++) {
            cs.beginText();
            cs.setFont(fuente, i == 1 ? tam : tam * 0.85f);
            cs.newLineAtOffset(tx, y);
            cs.showText(lineas[i]);
            cs.endText();
            y -= tam * 1.25f;
        }
    }

    /** Rectángulo centrado dentro de "zona" con el aspecto dado. */
    private static RectF ajustar(RectF zona, float aspecto) {
        float w = zona.width(), h = zona.height();
        if (w / h > aspecto) w = h * aspecto; else h = w / aspecto;
        float x = zona.left + (zona.width() - w) / 2, y = zona.top + (zona.height() - h) / 2;
        return new RectF(x, y, x + w, y + h);
    }
}
