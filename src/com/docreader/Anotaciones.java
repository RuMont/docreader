package com.docreader;

import android.content.Context;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resaltados, subrayados y notas de un documento. Se guardan en la app (el PDF no se modifica),
 * asociados a la huella del contenido del archivo, así sobreviven a que se abra desde otra ruta.
 */
final class Anotaciones {
    static final int RESALTE = 0, SUBRAYADO = 1;
    static final int[] COLORES = {0xFFFFD600, 0xFF66BB6A, 0xFFF06292, 0xFF42A5F5};
    static final String[] NOMBRES_COLOR = {"Amarillo", "Verde", "Rosa", "Azul"};

    static final class Anotacion {
        long id;
        int pag, tipo, color;
        List<RectF> rects;
        String texto = "", nota = "";
    }

    private final File archivo;
    final List<Anotacion> lista = new ArrayList<>();

    private Anotaciones(File archivo) {
        this.archivo = archivo;
    }

    static Anotaciones cargar(Context c, String huella) {
        File dir = new File(c.getFilesDir(), "anotaciones");
        dir.mkdirs();
        Anotaciones a = new Anotaciones(new File(dir, huella + ".json"));
        if (!a.archivo.isFile()) return a;
        try (InputStream in = new FileInputStream(a.archivo)) {
            byte[] datos = new byte[(int) a.archivo.length()];
            int leidos = 0;
            for (int n; leidos < datos.length && (n = in.read(datos, leidos, datos.length - leidos)) > 0; ) leidos += n;
            JSONArray arr = new JSONArray(new String(datos, 0, leidos, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Anotacion n = new Anotacion();
                n.id = o.getLong("id");
                n.pag = o.getInt("pag");
                n.tipo = o.optInt("tipo");
                n.color = o.optInt("color", COLORES[0]);
                n.texto = o.optString("texto");
                n.nota = o.optString("nota");
                n.rects = new ArrayList<>();
                JSONArray r = o.getJSONArray("rects");
                for (int k = 0; k + 3 < r.length(); k += 4) {
                    n.rects.add(new RectF((float) r.getDouble(k), (float) r.getDouble(k + 1),
                            (float) r.getDouble(k + 2), (float) r.getDouble(k + 3)));
                }
                a.lista.add(n);
            }
        } catch (IOException | JSONException ignorado) {
            // Archivo dañado: se empieza vacío (no se sobrescribe hasta el próximo cambio).
        }
        a.ordenar();
        return a;
    }

    Anotacion agregar(Seleccion s, int tipo, int color, String nota) {
        Anotacion n = new Anotacion();
        n.id = System.currentTimeMillis();
        n.pag = s.pag;
        n.tipo = tipo;
        n.color = color;
        n.rects = new ArrayList<>(s.rects);
        n.texto = s.texto;
        n.nota = nota == null ? "" : nota;
        lista.add(n);
        ordenar();
        guardar();
        return n;
    }

    void borrar(Anotacion n) {
        lista.remove(n);
        guardar();
    }

    void guardar() {
        JSONArray arr = new JSONArray();
        try {
            for (Anotacion n : lista) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("pag", n.pag);
                o.put("tipo", n.tipo);
                o.put("color", n.color);
                o.put("texto", n.texto);
                o.put("nota", n.nota);
                JSONArray r = new JSONArray();
                for (RectF f : n.rects) {
                    r.put(f.left);
                    r.put(f.top);
                    r.put(f.right);
                    r.put(f.bottom);
                }
                o.put("rects", r);
                arr.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        File tmp = new File(archivo.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(archivo);
    }

    /** Texto plano con todas las anotaciones, para compartir. */
    String exportar(String titulo) {
        StringBuilder sb = new StringBuilder("Anotaciones de «").append(titulo).append("»\n");
        for (Anotacion n : lista) {
            sb.append("\nPágina ").append(n.pag + 1).append(n.tipo == SUBRAYADO ? " (subrayado)" : "")
                    .append(":\n«").append(n.texto.replace('\n', ' ')).append("»\n");
            if (!n.nota.isEmpty()) sb.append("Nota: ").append(n.nota).append('\n');
        }
        return sb.toString();
    }

    private void ordenar() {
        Collections.sort(lista, (x, y) -> x.pag != y.pag ? Integer.compare(x.pag, y.pag)
                : Float.compare(x.rects.isEmpty() ? 0 : x.rects.get(0).top,
                        y.rects.isEmpty() ? 0 : y.rects.get(0).top));
    }
}
