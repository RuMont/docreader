package com.docreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista de documentos abiertos y la última página leída de cada uno. Cada entrada guarda la URI
 * original ("uri") y, si se abrió con un permiso temporal, la de su copia privada ("copia"; ver
 * {@link Copias}). Los visores pueden referirse a la entrada por cualquiera de las dos.
 */
public final class Recientes {
    private static final int MAXIMO = 50;

    private Recientes() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("recientes", Context.MODE_PRIVATE);
    }

    private static JSONArray leer(Context c) {
        try {
            return new JSONArray(prefs(c).getString("lista", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void escribir(Context c, JSONArray a) {
        prefs(c).edit().putString("lista", a.toString()).apply();
    }

    private static int indice(JSONArray a, Uri uri) {
        String u = uri.toString();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && (u.equals(o.optString("uri")) || u.equals(o.optString("copia", null)))) return i;
        }
        return -1;
    }

    /** URI con la que se abre la entrada: la copia privada si la tiene. */
    public static Uri abrible(JSONObject o) {
        String copia = o.optString("copia", null);
        return Uri.parse(copia != null && !copia.isEmpty() ? copia : o.optString("uri"));
    }

    /** Copias privadas en uso, de la entrada más reciente a la más antigua. */
    static synchronized List<Uri> copias(Context c) {
        List<Uri> r = new ArrayList<>();
        for (JSONObject o : lista(c)) {
            String copia = o.optString("copia", null);
            if (copia != null && !copia.isEmpty()) r.add(Uri.parse(copia));
        }
        return r;
    }

    /** Asocia la copia privada al documento (creando la entrada si el visor aún no la creó). */
    static synchronized void asociarCopia(Context c, Uri original, Uri copia, String nombre) {
        JSONArray a = leer(c);
        int i = indice(a, original);
        try {
            if (i < 0) {
                JSONObject o = new JSONObject();
                o.put("uri", original.toString());
                o.put("nombre", nombre);
                o.put("fecha", System.currentTimeMillis());
                o.put("copia", copia.toString());
                JSONArray nueva = new JSONArray();
                nueva.put(o);
                for (int k = 0; k < a.length() && nueva.length() < MAXIMO; k++) nueva.put(a.opt(k));
                a = nueva;
            } else {
                a.getJSONObject(i).put("copia", copia.toString());
            }
        } catch (JSONException ignorado) { }
        escribir(c, a);
    }

    /** La copia se borró (límite de espacio): la entrada vuelve a depender del original. */
    static synchronized void olvidarCopia(Context c, Uri copia) {
        JSONArray a = leer(c);
        int i = indice(a, copia);
        if (i < 0) return;
        a.optJSONObject(i).remove("copia");
        escribir(c, a);
    }

    /** El usuario volvió a buscar el archivo: la entrada pasa a la URI nueva, con su página. */
    static synchronized void reemplazar(Context c, Uri vieja, Uri nueva) {
        JSONArray a = leer(c);
        int i = indice(a, vieja);
        if (i < 0) return;
        JSONObject o = a.optJSONObject(i);
        o.remove("copia");
        try {
            o.put("uri", nueva.toString());
        } catch (JSONException ignorado) { }
        int otra = indice(a, nueva);
        if (otra >= 0 && otra != i) a.remove(otra); // ya estaba con la URI nueva: se deja una sola
        escribir(c, a);
    }

    static synchronized void borrarTodo(Context c) {
        escribir(c, new JSONArray());
    }

    public static synchronized List<JSONObject> lista(Context c) {
        JSONArray a = leer(c);
        List<JSONObject> r = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) r.add(o);
        }
        return r;
    }

    /** Lo pone el primero de la lista, conservando la página guardada (y la copia, si la hay). */
    public static synchronized void registrar(Context c, Uri uri, String nombre, int total) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        JSONObject o = i >= 0 ? a.optJSONObject(i) : new JSONObject();
        JSONArray nueva = new JSONArray();
        try {
            if (i < 0) o.put("uri", uri.toString());
            o.put("nombre", nombre);
            o.put("total", total);
            o.put("fecha", System.currentTimeMillis());
        } catch (JSONException ignorado) { }
        nueva.put(o);
        for (int k = 0; k < a.length() && nueva.length() < MAXIMO; k++) {
            if (k != i) nueva.put(a.opt(k));
        }
        escribir(c, nueva);
    }

    public static synchronized void guardarPagina(Context c, Uri uri, int pagina) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        if (i < 0) return;
        try {
            a.getJSONObject(i).put("pagina", pagina);
        } catch (JSONException ignorado) { }
        escribir(c, a);
    }

    public static synchronized int pagina(Context c, Uri uri) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        return i < 0 ? 0 : a.optJSONObject(i).optInt("pagina", 0);
    }

    /** Quita la entrada y borra su copia privada, si la tenía. */
    public static synchronized void quitar(Context c, Uri uri) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        if (i < 0) return;
        String copia = a.optJSONObject(i).optString("copia", null);
        if (copia != null && !copia.isEmpty()) Copias.borrar(c, Uri.parse(copia));
        a.remove(i);
        escribir(c, a);
    }

    public static boolean noche(Context c) { return prefs(c).getBoolean("noche", false); }

    public static void setNoche(Context c, boolean b) { prefs(c).edit().putBoolean("noche", b).apply(); }
}
