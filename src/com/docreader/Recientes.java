package com.docreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lista de documentos abiertos y la última página leída de cada uno. */
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
            if (o != null && u.equals(o.optString("uri"))) return i;
        }
        return -1;
    }

    public static List<JSONObject> lista(Context c) {
        JSONArray a = leer(c);
        List<JSONObject> r = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) r.add(o);
        }
        return r;
    }

    /** Lo pone el primero de la lista, conservando la página guardada. */
    public static void registrar(Context c, Uri uri, String nombre, int total) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        JSONObject o = i >= 0 ? a.optJSONObject(i) : new JSONObject();
        JSONArray nueva = new JSONArray();
        try {
            o.put("uri", uri.toString());
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

    public static void guardarPagina(Context c, Uri uri, int pagina) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        if (i < 0) return;
        try {
            a.getJSONObject(i).put("pagina", pagina);
        } catch (JSONException ignorado) { }
        escribir(c, a);
    }

    public static int pagina(Context c, Uri uri) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        return i < 0 ? 0 : a.optJSONObject(i).optInt("pagina", 0);
    }

    public static void quitar(Context c, Uri uri) {
        JSONArray a = leer(c);
        int i = indice(a, uri);
        if (i < 0) return;
        a.remove(i);
        escribir(c, a);
    }

    public static boolean noche(Context c) { return prefs(c).getBoolean("noche", false); }

    public static void setNoche(Context c, boolean b) { prefs(c).edit().putBoolean("noche", b).apply(); }
}
