package com.docreader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Iconos de línea dibujados en código (rejilla de 24 unidades, trazo de 1,8 con extremos
 * redondeados), sin recursos ni bibliotecas. Se escalan a cualquier tamaño y toman el color dado.
 */
public final class Icono extends Drawable {
    public enum Tipo {
        BUSCAR, ARRIBA, ABAJO, IZQUIERDA, DERECHA, CERRAR, LISTA, MAS, CARPETA, UNIR, AVISO, DOCUMENTO,
        CODIGO, QUITAR, IR_PAGINA, LAPIZ, FIRMA, HERRAMIENTAS, OJO, IMPRESORA, COMPARTIR, OCR, TIJERAS,
        CONVERTIR, CANDADO, IMAGEN, LUNA, INDICE, LETRA, GIRAR, AJUSTAR, INFO, ABRIR_CON, PAPELERA
    }

    private final Tipo tipo;
    private final Paint trazo = new Paint(Paint.ANTI_ALIAS_FLAG), relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tam;

    public Icono(Context c, Tipo tipo, int color) {
        this.tipo = tipo;
        tam = Estilo.dp(c, 24);
        trazo.setStyle(Paint.Style.STROKE);
        trazo.setStrokeCap(Paint.Cap.ROUND);
        trazo.setStrokeJoin(Paint.Join.ROUND);
        trazo.setStrokeWidth(1.8f);
        trazo.setColor(color);
        relleno.setStyle(Paint.Style.FILL);
        relleno.setColor(color);
    }

    @Override public int getIntrinsicWidth() { return tam; }

    @Override public int getIntrinsicHeight() { return tam; }

    @Override public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2);
        c.scale(s, s);
        Path p = new Path();
        switch (tipo) {
            case BUSCAR:
                c.drawCircle(10.5f, 10.5f, 6f, trazo);
                linea(c, 15f, 15f, 20f, 20f);
                break;
            case ARRIBA: quebrada(c, 6, 15, 12, 9, 18, 15); break;
            case ABAJO: quebrada(c, 6, 9, 12, 15, 18, 9); break;
            case IZQUIERDA: quebrada(c, 15, 6, 9, 12, 15, 18); break;
            case DERECHA: quebrada(c, 9, 6, 15, 12, 9, 18); break;
            case CERRAR:
                linea(c, 6.5f, 6.5f, 17.5f, 17.5f);
                linea(c, 17.5f, 6.5f, 6.5f, 17.5f);
                break;
            case QUITAR:
                linea(c, 6f, 12f, 18f, 12f);
                break;
            case LISTA:
                linea(c, 5f, 7f, 19f, 7f);
                linea(c, 5f, 12f, 19f, 12f);
                linea(c, 5f, 17f, 19f, 17f);
                break;
            case MAS:
                c.drawCircle(12f, 5.5f, 1.7f, relleno);
                c.drawCircle(12f, 12f, 1.7f, relleno);
                c.drawCircle(12f, 18.5f, 1.7f, relleno);
                break;
            case CARPETA:
                p.moveTo(3.5f, 7.5f);
                p.quadTo(3.5f, 5.5f, 5.5f, 5.5f);
                p.lineTo(9.2f, 5.5f);
                p.lineTo(11.2f, 7.5f);
                p.lineTo(18.5f, 7.5f);
                p.quadTo(20.5f, 7.5f, 20.5f, 9.5f);
                p.lineTo(20.5f, 16.5f);
                p.quadTo(20.5f, 18.5f, 18.5f, 18.5f);
                p.lineTo(5.5f, 18.5f);
                p.quadTo(3.5f, 18.5f, 3.5f, 16.5f);
                p.close();
                c.drawPath(p, trazo);
                break;
            case UNIR:
                c.drawRoundRect(new RectF(4f, 3.5f, 13.5f, 15f), 1.5f, 1.5f, trazo);
                c.drawRoundRect(new RectF(10.5f, 9f, 20f, 20.5f), 1.5f, 1.5f, trazo);
                break;
            case AVISO:
                p.moveTo(12f, 4f);
                p.lineTo(21f, 19.5f);
                p.lineTo(3f, 19.5f);
                p.close();
                c.drawPath(p, trazo);
                linea(c, 12f, 10f, 12f, 14f);
                c.drawCircle(12f, 16.8f, 1.1f, relleno);
                break;
            case DOCUMENTO:
                p.moveTo(14f, 3.5f);
                p.lineTo(7f, 3.5f);
                p.quadTo(5.5f, 3.5f, 5.5f, 5f);
                p.lineTo(5.5f, 19f);
                p.quadTo(5.5f, 20.5f, 7f, 20.5f);
                p.lineTo(17f, 20.5f);
                p.quadTo(18.5f, 20.5f, 18.5f, 19f);
                p.lineTo(18.5f, 8f);
                p.close();
                c.drawPath(p, trazo);
                quebrada(c, 14, 3.5f, 14, 8, 18.5f, 8);
                linea(c, 9f, 12.5f, 15f, 12.5f);
                linea(c, 9f, 16f, 13f, 16f);
                break;
            case IR_PAGINA:
                c.drawRoundRect(new RectF(5.5f, 3.5f, 18.5f, 20.5f), 2f, 2f, trazo);
                linea(c, 12f, 7.5f, 12f, 15.5f);
                quebrada(c, 9f, 12.5f, 12f, 15.5f, 15f, 12.5f);
                break;
            case LAPIZ:
                p.moveTo(4.5f, 19.5f);
                p.lineTo(5.5f, 15f);
                p.lineTo(15.5f, 5f);
                p.lineTo(19f, 8.5f);
                p.lineTo(9f, 18.5f);
                p.close();
                c.drawPath(p, trazo);
                linea(c, 13.5f, 7f, 17f, 10.5f);
                break;
            case FIRMA:
                p.moveTo(3.5f, 15f);
                p.cubicTo(6f, 8f, 8.5f, 6f, 9f, 9f);
                p.cubicTo(9.5f, 12f, 7f, 17f, 9.5f, 16.5f);
                p.cubicTo(12f, 16f, 13f, 11f, 14.5f, 12.5f);
                p.cubicTo(15.5f, 13.5f, 15f, 15.5f, 17f, 15f);
                p.lineTo(20.5f, 14f);
                c.drawPath(p, trazo);
                linea(c, 3.5f, 19.5f, 20.5f, 19.5f);
                break;
            case HERRAMIENTAS:
                p.moveTo(14.5f, 4f);
                p.cubicTo(11.5f, 4f, 10f, 6.5f, 10.8f, 9.2f);
                p.lineTo(4.5f, 15.5f);
                p.quadTo(4.5f, 19.5f, 8.5f, 19.5f);
                p.lineTo(14.8f, 13.2f);
                p.cubicTo(17.5f, 14f, 20f, 12.5f, 20f, 9.5f);
                p.lineTo(17f, 11f);
                p.lineTo(13f, 7f);
                p.close();
                c.drawPath(p, trazo);
                break;
            case OJO:
                p.moveTo(2.5f, 12f);
                p.quadTo(12f, 2f, 21.5f, 12f);
                p.quadTo(12f, 22f, 2.5f, 12f);
                p.close();
                c.drawPath(p, trazo);
                c.drawCircle(12f, 12f, 3f, trazo);
                break;
            case IMPRESORA:
                c.drawRoundRect(new RectF(3.5f, 8.5f, 20.5f, 16.5f), 2f, 2f, trazo);
                quebrada(c, 7f, 8.5f, 7f, 3.5f, 17f, 3.5f);
                linea(c, 17f, 3.5f, 17f, 8.5f);
                c.drawRect(new RectF(7f, 13.5f, 17f, 20.5f), trazo);
                break;
            case COMPARTIR:
                c.drawCircle(17.5f, 5.5f, 2.5f, trazo);
                c.drawCircle(6.5f, 12f, 2.5f, trazo);
                c.drawCircle(17.5f, 18.5f, 2.5f, trazo);
                linea(c, 8.7f, 10.7f, 15.3f, 6.8f);
                linea(c, 8.7f, 13.3f, 15.3f, 17.2f);
                break;
            case OCR:
                quebrada(c, 3.5f, 8f, 3.5f, 3.5f, 8f, 3.5f);
                quebrada(c, 16f, 3.5f, 20.5f, 3.5f, 20.5f, 8f);
                quebrada(c, 20.5f, 16f, 20.5f, 20.5f, 16f, 20.5f);
                quebrada(c, 8f, 20.5f, 3.5f, 20.5f, 3.5f, 16f);
                linea(c, 8f, 8f, 16f, 8f);
                linea(c, 12f, 8f, 12f, 16.5f);
                break;
            case TIJERAS:
                c.drawCircle(6.5f, 6.5f, 2.8f, trazo);
                c.drawCircle(6.5f, 17.5f, 2.8f, trazo);
                linea(c, 8.8f, 8.2f, 20f, 17.5f);
                linea(c, 8.8f, 15.8f, 20f, 6.5f);
                break;
            case CONVERTIR:
                linea(c, 4f, 8.5f, 19f, 8.5f);
                quebrada(c, 15.5f, 5f, 19f, 8.5f, 15.5f, 12f);
                linea(c, 20f, 15.5f, 5f, 15.5f);
                quebrada(c, 8.5f, 12f, 5f, 15.5f, 8.5f, 19f);
                break;
            case CANDADO:
                c.drawRoundRect(new RectF(5f, 10.5f, 19f, 20.5f), 2.5f, 2.5f, trazo);
                p.moveTo(8f, 10.5f);
                p.lineTo(8f, 7.5f);
                p.cubicTo(8f, 2.5f, 16f, 2.5f, 16f, 7.5f);
                p.lineTo(16f, 10.5f);
                c.drawPath(p, trazo);
                linea(c, 12f, 14.5f, 12f, 16.5f);
                break;
            case IMAGEN:
                c.drawRoundRect(new RectF(3.5f, 4.5f, 20.5f, 19.5f), 2.5f, 2.5f, trazo);
                c.drawCircle(9f, 9.5f, 1.8f, trazo);
                p.moveTo(3.5f, 17f);
                p.lineTo(9f, 12.5f);
                p.lineTo(13f, 16f);
                p.lineTo(16f, 13.5f);
                p.lineTo(20.5f, 17.5f);
                c.drawPath(p, trazo);
                break;
            case LUNA:
                p.moveTo(19.5f, 14.5f);
                p.cubicTo(14f, 17f, 7f, 12f, 9.5f, 4.5f);
                p.cubicTo(1.5f, 7f, 3.5f, 20.5f, 12.5f, 20f);
                p.cubicTo(15.5f, 19.8f, 18f, 17.5f, 19.5f, 14.5f);
                p.close();
                c.drawPath(p, trazo);
                break;
            case INDICE:
                c.drawCircle(5f, 7f, 1.2f, relleno);
                c.drawCircle(5f, 12f, 1.2f, relleno);
                c.drawCircle(5f, 17f, 1.2f, relleno);
                linea(c, 9f, 7f, 19.5f, 7f);
                linea(c, 11f, 12f, 19.5f, 12f);
                linea(c, 11f, 17f, 19.5f, 17f);
                break;
            case LETRA:
                quebrada(c, 3f, 19f, 8.5f, 5f, 14f, 19f);
                linea(c, 5f, 14f, 12f, 14f);
                c.drawCircle(18f, 15.5f, 3f, trazo);
                linea(c, 21f, 12.5f, 21f, 19f);
                break;
            case GIRAR:
                c.drawArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 290f, false, trazo);
                quebrada(c, 14.5f, 3f, 16.2f, 6f, 13f, 7.5f);
                break;
            case AJUSTAR:
                quebrada(c, 4f, 9f, 4f, 4f, 9f, 4f);
                quebrada(c, 15f, 4f, 20f, 4f, 20f, 9f);
                quebrada(c, 20f, 15f, 20f, 20f, 15f, 20f);
                quebrada(c, 9f, 20f, 4f, 20f, 4f, 15f);
                break;
            case INFO:
                c.drawCircle(12f, 12f, 8.5f, trazo);
                linea(c, 12f, 11f, 12f, 16.5f);
                c.drawCircle(12f, 7.8f, 1.1f, relleno);
                break;
            case ABRIR_CON:
                quebrada(c, 10f, 4.5f, 4.5f, 4.5f, 4.5f, 19.5f);
                quebrada(c, 4.5f, 19.5f, 19.5f, 19.5f, 19.5f, 14f);
                linea(c, 11f, 13f, 19.5f, 4.5f);
                quebrada(c, 14f, 4.5f, 19.5f, 4.5f, 19.5f, 10f);
                break;
            case PAPELERA:
                linea(c, 4f, 6.5f, 20f, 6.5f);
                quebrada(c, 9f, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f);
                linea(c, 14.5f, 3.5f, 15f, 6.5f);
                p.moveTo(6f, 6.5f);
                p.lineTo(7f, 20.5f);
                p.lineTo(17f, 20.5f);
                p.lineTo(18f, 6.5f);
                c.drawPath(p, trazo);
                break;
            case CODIGO:
                quebrada(c, 8.5f, 7, 3.5f, 12, 8.5f, 17);
                quebrada(c, 15.5f, 7, 20.5f, 12, 15.5f, 17);
                break;
        }
        c.restore();
    }

    private void linea(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, trazo);
    }

    private void quebrada(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        c.drawPath(p, trazo);
    }

    @Override public void setAlpha(int a) {
        trazo.setAlpha(a);
        relleno.setAlpha(a);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter f) {
        trazo.setColorFilter(f);
        relleno.setColorFilter(f);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
