package com.docreader.herramientas;

import java.util.ArrayList;
import java.util.List;

/**
 * Rangos de páginas escritos por el usuario ("1-3, 5, 8-", "-4"), numerados desde 1.
 * Se devuelven como pares {desde, hasta} numerados desde 0 e inclusivos.
 */
public final class Rangos {
    private Rangos() { }

    /** Lanza IllegalArgumentException con un mensaje para el usuario si el texto no es válido. */
    public static List<int[]> leer(String texto, int total) {
        List<int[]> r = new ArrayList<>();
        String t = texto == null ? "" : texto.replace(';', ',').replace(" ", "");
        if (t.isEmpty()) throw new IllegalArgumentException("Escribe las páginas, por ejemplo: 1-3, 5, 8-10");
        for (String parte : t.split(",")) {
            if (parte.isEmpty()) continue;
            int desde, hasta;
            int guion = parte.indexOf('-');
            try {
                if (guion < 0) {
                    desde = hasta = Integer.parseInt(parte);
                } else {
                    String a = parte.substring(0, guion), b = parte.substring(guion + 1);
                    desde = a.isEmpty() ? 1 : Integer.parseInt(a);
                    hasta = b.isEmpty() ? total : Integer.parseInt(b);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("«" + parte + "» no es una página ni un rango válido.");
            }
            if (desde < 1 || hasta > total || desde > hasta) {
                throw new IllegalArgumentException("El rango «" + parte + "» no es válido: el documento tiene "
                        + total + " páginas.");
            }
            r.add(new int[] {desde - 1, hasta - 1});
        }
        if (r.isEmpty()) throw new IllegalArgumentException("No has indicado ninguna página.");
        return r;
    }

    /** Grupos consecutivos de "n" páginas: 1-n, n+1-2n… */
    public static List<int[]> cada(int n, int total) {
        if (n < 1) throw new IllegalArgumentException("El número de páginas por parte debe ser 1 o más.");
        List<int[]> r = new ArrayList<>();
        for (int i = 0; i < total; i += n) r.add(new int[] {i, Math.min(total, i + n) - 1});
        return r;
    }

    /** "1-3" o "5" para mostrar un rango (desde 0) en nombres de archivo. */
    public static String etiqueta(int[] rango) {
        return rango[0] == rango[1] ? Integer.toString(rango[0] + 1) : (rango[0] + 1) + "-" + (rango[1] + 1);
    }

    public static int paginas(List<int[]> rangos) {
        int n = 0;
        for (int[] r : rangos) n += r[1] - r[0] + 1;
        return n;
    }
}
