package com.docreader.firma;

import android.content.Context;

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
import java.util.List;

/** Lista de firmas manuscritas guardadas en la app (firmas.json). */
public final class Firmas {
    private Firmas() { }

    private static File archivo(Context c) { return new File(c.getFilesDir(), "firmas.json"); }

    public static List<Trazo> lista(Context c) {
        migrar(c);
        List<Trazo> r = new ArrayList<>();
        File f = archivo(c);
        if (!f.isFile()) return r;
        try {
            JSONArray a = new JSONArray(leer(f));
            for (int i = 0; i < a.length(); i++) {
                try {
                    r.add(Trazo.deJson(a.getJSONObject(i)));
                } catch (JSONException ignorado) {
                    // Una firma dañada no impide usar las demás.
                }
            }
        } catch (IOException | JSONException ignorado) { }
        return r;
    }

    public static Trazo buscar(Context c, String id) {
        for (Trazo t : lista(c)) if (t.id.equals(id)) return t;
        return null;
    }

    /** Añade la firma al final con el nombre "Firma N" y la devuelve ya nombrada. */
    public static Trazo agregar(Context c, Trazo t) {
        List<Trazo> l = lista(c);
        int n = l.size() + 1;
        while (existeNombre(l, "Firma " + n)) n++;
        Trazo nueva = t.conNombre("Firma " + n);
        l.add(nueva);
        escribir(c, l);
        return nueva;
    }

    public static void renombrar(Context c, String id, String nombre) {
        List<Trazo> l = lista(c);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(id)) l.set(i, l.get(i).conNombre(nombre));
        }
        escribir(c, l);
    }

    public static void borrar(Context c, String id) {
        List<Trazo> l = lista(c);
        l.removeIf(t -> t.id.equals(id));
        escribir(c, l);
    }

    private static boolean existeNombre(List<Trazo> l, String n) {
        for (Trazo t : l) if (t.nombre.equals(n)) return true;
        return false;
    }

    private static void escribir(Context c, List<Trazo> l) {
        try {
            JSONArray a = new JSONArray();
            for (Trazo t : l) a.put(t.aJson());
            File f = archivo(c), tmp = new File(f.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(f);
        } catch (IOException | JSONException ignorado) { }
    }

    /** La versión anterior guardaba una sola firma en firma.json: pasa a ser la primera de la lista. */
    private static void migrar(Context c) {
        File viejo = new File(c.getFilesDir(), "firma.json");
        if (!viejo.isFile()) return;
        try {
            Trazo t = Trazo.deJson(new JSONObject(leer(viejo)));
            List<Trazo> l = new ArrayList<>();
            l.add(t.conNombre("Mi firma"));
            if (archivo(c).isFile()) {
                JSONArray a = new JSONArray(leer(archivo(c)));
                for (int i = 0; i < a.length(); i++) l.add(Trazo.deJson(a.getJSONObject(i)));
            }
            escribir(c, l);
        } catch (IOException | JSONException ignorado) { }
        viejo.delete();
    }

    private static String leer(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int n = 0;
            for (int r; n < d.length && (r = in.read(d, n, d.length - n)) > 0; ) n += r;
            return new String(d, 0, n, StandardCharsets.UTF_8);
        }
    }
}
