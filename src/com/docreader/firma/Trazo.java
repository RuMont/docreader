package com.docreader.firma;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Firma manuscrita como trazos vectoriales. Las coordenadas están normalizadas a 0..1 dentro
 * del rectángulo que ocupa la firma (y hacia abajo); el grosor es relativo al ancho.
 */
public final class Trazo {
    public static final int NEGRO = 0xFF1A1A1A, AZUL = 0xFF1A3A8F;

    /** Identificador y nombre en la lista de firmas guardadas. */
    public final String id, nombre;
    final List<float[]> lineas;
    /** Ancho / alto. */
    public final float aspecto;
    final float grosor;
    public final int color;

    private Trazo(String id, String nombre, List<float[]> lineas, float aspecto, float grosor, int color) {
        this.id = id;
        this.nombre = nombre;
        this.lineas = lineas;
        this.aspecto = aspecto;
        this.grosor = grosor;
        this.color = color;
    }

    /** Desde los trazos dibujados en pantalla (en píxeles) con un pincel de "pincel" píxeles. */
    static Trazo desdePuntos(List<List<PointF>> trazos, float pincel, int color) {
        RectF caja = null;
        for (List<PointF> t : trazos) {
            for (PointF p : t) {
                if (caja == null) caja = new RectF(p.x, p.y, p.x, p.y);
                else caja.union(p.x, p.y);
            }
        }
        if (caja == null) return null;
        caja.inset(-pincel, -pincel); // margen para que el grosor no se corte
        float w = Math.max(caja.width(), 1), h = Math.max(caja.height(), 1);
        List<float[]> lineas = new ArrayList<>();
        for (List<PointF> t : trazos) {
            float[] l = new float[t.size() * 2];
            for (int i = 0; i < t.size(); i++) {
                l[2 * i] = (t.get(i).x - caja.left) / w;
                l[2 * i + 1] = (t.get(i).y - caja.top) / h;
            }
            lineas.add(l);
        }
        return new Trazo(Long.toString(System.currentTimeMillis(), 36), "", lineas, w / h, pincel / w, color);
    }

    Trazo conNombre(String n) { return new Trazo(id, n, lineas, aspecto, grosor, color); }

    /** Dibuja la firma ajustada al rectángulo (que debería tener su mismo aspecto). */
    public void dibujar(Canvas c, RectF r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setStrokeWidth(Math.max(1f, grosor * r.width()));
        for (float[] l : lineas) {
            Path path = new Path();
            path.moveTo(r.left + l[0] * r.width(), r.top + l[1] * r.height());
            if (l.length == 2) path.lineTo(r.left + l[0] * r.width() + 0.1f, r.top + l[1] * r.height());
            for (int i = 2; i < l.length; i += 2) {
                path.lineTo(r.left + l[i] * r.width(), r.top + l[i + 1] * r.height());
            }
            c.drawPath(path, p);
        }
    }

    /** Escribe los trazos en un flujo de contenido PDF, en una caja de w×h puntos (y hacia arriba). */
    void aPdf(PDPageContentStream cs, float x, float y, float w, float h) throws IOException {
        cs.saveGraphicsState();
        cs.setStrokingColor(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f);
        cs.setLineWidth(Math.max(0.5f, grosor * w));
        cs.setLineCapStyle(1);  // redondo
        cs.setLineJoinStyle(1);
        for (float[] l : lineas) {
            cs.moveTo(x + l[0] * w, y + (1 - l[1]) * h);
            if (l.length == 2) cs.lineTo(x + l[0] * w + 0.01f, y + (1 - l[1]) * h);
            for (int i = 2; i < l.length; i += 2) cs.lineTo(x + l[i] * w, y + (1 - l[i + 1]) * h);
            cs.stroke();
        }
        cs.restoreGraphicsState();
    }

    // --- Persistencia (ver Firmas) ---

    JSONObject aJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("nombre", nombre);
        o.put("aspecto", aspecto);
        o.put("grosor", grosor);
        o.put("color", color);
        JSONArray ls = new JSONArray();
        for (float[] l : lineas) {
            JSONArray a = new JSONArray();
            for (float v : l) a.put(Math.round(v * 10000) / 10000.0);
            ls.put(a);
        }
        o.put("lineas", ls);
        return o;
    }

    static Trazo deJson(JSONObject o) throws JSONException {
        JSONArray ls = o.getJSONArray("lineas");
        List<float[]> lineas = new ArrayList<>();
        for (int i = 0; i < ls.length(); i++) {
            JSONArray a = ls.getJSONArray(i);
            float[] l = new float[a.length()];
            for (int k = 0; k < l.length; k++) l[k] = (float) a.getDouble(k);
            if (l.length >= 2) lineas.add(l);
        }
        if (lineas.isEmpty()) throw new JSONException("Firma vacía");
        return new Trazo(o.optString("id", Long.toString(System.currentTimeMillis(), 36)),
                o.optString("nombre", "Firma"), lineas, (float) o.getDouble("aspecto"),
                (float) o.getDouble("grosor"), o.optInt("color", NEGRO));
    }
}
