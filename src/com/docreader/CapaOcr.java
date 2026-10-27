package com.docreader;

import android.graphics.PointF;
import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Texto reconocido por OCR en una página: palabras en orden de lectura con su caja (en puntos). */
final class CapaOcr {
    static final class Palabra {
        final String texto;
        final RectF r;
        final boolean nuevaLinea;
        Palabra(String texto, RectF r, boolean nuevaLinea) {
            this.texto = texto;
            this.r = r;
            this.nuevaLinea = nuevaLinea;
        }
    }

    final List<Palabra> palabras;
    private String plegado;   // texto sin tildes y en minúsculas, mismo largo que el original
    private int[] palabraDe;  // índice de palabra para cada carácter de "plegado" (-1 en separadores)

    CapaOcr(List<Palabra> palabras) {
        this.palabras = palabras;
    }

    // --- Persistencia: una línea por palabra "x0 y0 x1 y1 L texto" ---

    void guardar(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (Palabra p : palabras) {
                w.write(String.format(Locale.ROOT, "%.1f %.1f %.1f %.1f %d %s\n",
                        p.r.left, p.r.top, p.r.right, p.r.bottom, p.nuevaLinea ? 1 : 0, p.texto));
            }
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar el OCR");
    }

    static CapaOcr cargar(File f) {
        if (!f.isFile()) return null;
        List<Palabra> l = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String s; (s = r.readLine()) != null; ) {
                String[] c = s.split(" ", 6);
                if (c.length < 6) continue;
                l.add(new Palabra(c[5], new RectF(Float.parseFloat(c[0]), Float.parseFloat(c[1]),
                        Float.parseFloat(c[2]), Float.parseFloat(c[3])), "1".equals(c[4])));
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return new CapaOcr(l);
    }

    // --- Selección ---

    /** Selección entre las palabras más cercanas a a y b (si a == b, la palabra en a). */
    Seleccion seleccionar(PointF a, PointF b) {
        int ia = cercana(a), ib = cercana(b);
        if (ia < 0 || ib < 0) return null;
        if (ia > ib) { int t = ia; ia = ib; ib = t; }
        Seleccion s = new Seleccion();
        s.a = a;
        s.b = b;
        StringBuilder sb = new StringBuilder();
        s.rects = new ArrayList<>();
        RectF linea = null;
        for (int i = ia; i <= ib; i++) {
            Palabra p = palabras.get(i);
            if (i > ia) sb.append(p.nuevaLinea ? '\n' : ' ');
            sb.append(p.texto);
            if (linea == null || p.nuevaLinea) {
                linea = new RectF(p.r);
                s.rects.add(linea);
            } else {
                linea.union(p.r);
            }
        }
        s.texto = sb.toString();
        return s;
    }

    private int cercana(PointF p) {
        int mejor = -1;
        float dist = 30 * 30; // como mucho 30 puntos de distancia
        for (int i = 0; i < palabras.size(); i++) {
            RectF r = palabras.get(i).r;
            float dx = p.x < r.left ? r.left - p.x : p.x > r.right ? p.x - r.right : 0;
            float dy = p.y < r.top ? r.top - p.y : p.y > r.bottom ? p.y - r.bottom : 0;
            float d = dx * dx + dy * dy * 4; // penaliza más la distancia vertical (otra línea)
            if (d < dist) {
                dist = d;
                mejor = i;
                if (d == 0) break;
            }
        }
        return mejor;
    }

    // --- Búsqueda (sin distinguir mayúsculas ni tildes) ---

    List<List<RectF>> buscar(String consulta) {
        List<List<RectF>> res = new ArrayList<>();
        String q = plegar(consulta.trim());
        if (q.isEmpty()) return res;
        indexar();
        for (int i = plegado.indexOf(q); i >= 0; i = plegado.indexOf(q, i + 1)) {
            int fin = i + q.length() - 1;
            List<RectF> rects = new ArrayList<>();
            int ultima = -1;
            RectF linea = null;
            for (int k = i; k <= fin; k++) {
                int w = palabraDe[k];
                if (w < 0 || w == ultima) continue;
                Palabra p = palabras.get(w);
                if (linea == null || p.nuevaLinea) {
                    linea = new RectF(p.r);
                    rects.add(linea);
                } else {
                    linea.union(p.r);
                }
                ultima = w;
            }
            if (!rects.isEmpty()) res.add(rects);
        }
        return res;
    }

    private void indexar() {
        if (plegado != null) return;
        StringBuilder sb = new StringBuilder();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < palabras.size(); i++) {
            if (i > 0) {
                sb.append(' ');
                idx.add(-1);
            }
            String t = plegar(palabras.get(i).texto);
            sb.append(t);
            for (int k = 0; k < t.length(); k++) idx.add(i);
        }
        plegado = sb.toString();
        palabraDe = new int[idx.size()];
        for (int i = 0; i < palabraDe.length; i++) palabraDe[i] = idx.get(i);
    }

    /** Minúsculas y sin tildes, conservando la longitud (un carácter por carácter). */
    static String plegar(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            String d = Normalizer.normalize(String.valueOf(s.charAt(i)), Normalizer.Form.NFD);
            sb.append(Character.toLowerCase(d.charAt(0)));
        }
        return sb.toString();
    }
}
