package com.docreader.oficina;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Escribe un contenedor «Compound File Binary» (MS-CFB, versión 3, sectores de 512 bytes),
 * con miniflujo para los flujos pequeños y sectores DIFAT si el archivo pasa de ~7 MB.
 * Uso: añadir flujos con su ruta ("Carpeta/Flujo") y llamar a {@link #bytes()}.
 */
final class CfbEscritor {
    private static final int SECTOR = 512, MINI = 64, CORTE_MINI = 4096;
    private static final int FIN = 0xFFFFFFFE, LIBRE = 0xFFFFFFFF, SECT_FAT = 0xFFFFFFFD, SECT_DIFAT = 0xFFFFFFFC;
    private static final int NINGUNO = 0xFFFFFFFF;

    private static final class Nodo {
        final String nombre;
        final boolean almacen;
        byte[] datos;
        final List<Nodo> hijos = new ArrayList<>();
        int id, izquierda = NINGUNO, derecha = NINGUNO, hijo = NINGUNO, inicio = FIN;
        boolean negro = true;
        Nodo(String nombre, boolean almacen) {
            this.nombre = nombre;
            this.almacen = almacen;
        }
    }

    private final Nodo raiz = new Nodo("Root Entry", true);
    private int tamMiniflujo;

    void flujo(String ruta, byte[] datos) {
        String[] partes = ruta.split("/");
        Nodo n = raiz;
        for (int i = 0; i < partes.length - 1; i++) n = hijo(n, partes[i], true);
        hijo(n, partes[partes.length - 1], false).datos = datos;
    }

    private static Nodo hijo(Nodo padre, String nombre, boolean almacen) {
        for (Nodo h : padre.hijos) if (h.nombre.equals(nombre)) return h;
        Nodo h = new Nodo(nombre, almacen);
        padre.hijos.add(h);
        return h;
    }

    byte[] bytes() {
        // 1) Numerar entradas y construir los árboles rojo-negro de cada almacén.
        List<Nodo> todos = new ArrayList<>();
        numerar(raiz, todos);

        // 2) Repartir los flujos entre el miniflujo (< 4096 bytes) y sectores normales.
        ByteArrayOutputStream mini = new ByteArrayOutputStream();
        List<Integer> miniFat = new ArrayList<>();
        List<Nodo> grandes = new ArrayList<>();
        for (Nodo n : todos) {
            if (n.almacen || n == raiz) continue;
            byte[] d = n.datos == null ? new byte[0] : n.datos;
            if (d.length == 0) {
                n.inicio = FIN;
            } else if (d.length < CORTE_MINI) {
                int primero = miniFat.size(), sectores = (d.length + MINI - 1) / MINI;
                for (int i = 0; i < sectores; i++) miniFat.add(i == sectores - 1 ? FIN : primero + i + 1);
                n.inicio = primero;
                mini.write(d, 0, d.length);
                relleno(mini, MINI);
            } else {
                grandes.add(n);
            }
        }
        byte[] miniFlujo = mini.toByteArray();
        byte[] miniFatBytes = enteros(miniFat, SECTOR / 4);

        // 3) Sectores normales: grandes, miniflujo, miniFAT y, ya con todos los inicios
        //    conocidos, el directorio; después la FAT y la DIFAT.
        List<byte[]> bloques = new ArrayList<>();
        List<Integer> fat = new ArrayList<>();
        for (Nodo n : grandes) n.inicio = cadena(bloques, fat, n.datos);
        raiz.inicio = miniFlujo.length > 0 ? cadena(bloques, fat, miniFlujo) : FIN;
        tamMiniflujo = miniFlujo.length;
        int inicioMiniFat = miniFat.isEmpty() ? FIN : cadena(bloques, fat, miniFatBytes);
        int sectoresMiniFat = miniFat.isEmpty() ? 0 : miniFatBytes.length / SECTOR;
        int inicioDir = cadena(bloques, fat, directorio(todos));

        int datos = fat.size(), nFat = 0, nDifat = 0;
        while (true) { // los sectores FAT/DIFAT también ocupan entradas de la FAT
            int total = datos + nFat + nDifat;
            int necesFat = (total + SECTOR / 4 - 1) / (SECTOR / 4);
            int necesDifat = necesFat <= 109 ? 0 : (necesFat - 109 + 126) / 127;
            if (necesFat == nFat && necesDifat == nDifat) break;
            nFat = necesFat;
            nDifat = necesDifat;
        }
        int[] sectFat = new int[nFat], sectDifat = new int[nDifat];
        for (int i = 0; i < nFat; i++) {
            sectFat[i] = datos + i;
            fat.add(SECT_FAT);
        }
        for (int i = 0; i < nDifat; i++) {
            sectDifat[i] = datos + nFat + i;
            fat.add(SECT_DIFAT);
        }
        byte[] fatBytes = enteros(fat, SECTOR / 4 * nFat);

        // 4) Cabecera.
        byte[] cab = new byte[SECTOR];
        byte[] firma = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(firma, 0, cab, 0, 8);
        u16(cab, 24, 0x003E);
        u16(cab, 26, 3);
        u16(cab, 28, 0xFFFE);
        u16(cab, 30, 9);
        u16(cab, 32, 6);
        i32(cab, 44, nFat);
        i32(cab, 48, inicioDir);
        i32(cab, 56, CORTE_MINI);
        i32(cab, 60, inicioMiniFat);
        i32(cab, 64, sectoresMiniFat);
        i32(cab, 68, nDifat > 0 ? sectDifat[0] : FIN);
        i32(cab, 72, nDifat);
        for (int i = 0; i < 109; i++) i32(cab, 76 + 4 * i, i < nFat ? sectFat[i] : LIBRE);

        ByteArrayOutputStream o = new ByteArrayOutputStream(SECTOR * (fat.size() + 1));
        o.write(cab, 0, SECTOR);
        for (byte[] b : bloques) o.write(b, 0, b.length);
        o.write(fatBytes, 0, fatBytes.length);
        for (int k = 0; k < nDifat; k++) {
            byte[] s = new byte[SECTOR];
            for (int i = 0; i < 127; i++) {
                int idx = 109 + k * 127 + i;
                i32(s, 4 * i, idx < nFat ? sectFat[idx] : LIBRE);
            }
            i32(s, 508, k + 1 < nDifat ? sectDifat[k + 1] : FIN);
            o.write(s, 0, SECTOR);
        }
        return o.toByteArray();
    }

    /** Añade los datos como cadena de sectores y devuelve el primero. */
    private static int cadena(List<byte[]> bloques, List<Integer> fat, byte[] d) {
        int primero = fat.size(), n = Math.max(1, (d.length + SECTOR - 1) / SECTOR);
        for (int i = 0; i < n; i++) {
            byte[] s = new byte[SECTOR];
            System.arraycopy(d, i * SECTOR, s, 0, Math.min(SECTOR, d.length - i * SECTOR));
            bloques.add(s);
            fat.add(i == n - 1 ? FIN : primero + i + 1);
        }
        return primero;
    }

    private void numerar(Nodo n, List<Nodo> todos) {
        n.id = todos.size();
        todos.add(n);
        for (Nodo h : n.hijos) numerar(h, todos);
        if (!n.hijos.isEmpty()) {
            // Orden de MS-CFB: primero por longitud del nombre, luego sin distinguir mayúsculas.
            List<Nodo> orden = new ArrayList<>(n.hijos);
            orden.sort(Comparator.<Nodo>comparingInt(x -> x.nombre.length())
                    .thenComparing(x -> x.nombre.toUpperCase(Locale.ROOT)));
            int prof = 32 - Integer.numberOfLeadingZeros(orden.size()); // profundidad del árbol equilibrado
            n.hijo = arbol(orden, 0, orden.size() - 1, 1, prof);
        }
    }

    /**
     * Árbol binario equilibrado; si el último nivel no está completo, sus nodos van en rojo para
     * que todos los caminos tengan los mismos nodos negros (árbol rojo-negro válido).
     */
    private static int arbol(List<Nodo> l, int a, int b, int nivel, int prof) {
        if (a > b) return NINGUNO;
        int m = (a + b + 1) / 2;
        Nodo n = l.get(m);
        n.izquierda = arbol(l, a, m - 1, nivel + 1, prof);
        n.derecha = arbol(l, m + 1, b, nivel + 1, prof);
        boolean completo = l.size() == (1 << prof) - 1;
        n.negro = completo || nivel < prof;
        return n.id;
    }

    private byte[] directorio(List<Nodo> todos) {
        int n = (todos.size() + 3) / 4 * 4;
        byte[] d = new byte[n * 128];
        for (int i = 0; i < n; i++) {
            int o = i * 128;
            if (i >= todos.size()) { // entrada sin usar
                i32(d, o + 68, NINGUNO);
                i32(d, o + 72, NINGUNO);
                i32(d, o + 76, NINGUNO);
                continue;
            }
            Nodo e = todos.get(i);
            byte[] nombre = e.nombre.getBytes(StandardCharsets.UTF_16LE);
            int largo = Math.min(nombre.length, 62);
            System.arraycopy(nombre, 0, d, o, largo);
            u16(d, o + 64, largo + 2);
            d[o + 66] = (byte) (e == raiz ? 5 : e.almacen ? 1 : 2);
            d[o + 67] = (byte) (e.negro ? 1 : 0);
            i32(d, o + 68, e == raiz ? NINGUNO : e.izquierda);
            i32(d, o + 72, e == raiz ? NINGUNO : e.derecha);
            i32(d, o + 76, e.hijo);
            if (e == raiz || !e.almacen) {
                i32(d, o + 116, e.inicio);
                i32(d, o + 120, e == raiz ? tamMiniflujo : (e.datos == null ? 0 : e.datos.length));
            } else {
                i32(d, o + 116, 0);
            }
        }
        return d;
    }

    private static byte[] enteros(List<Integer> l, int multiplo) {
        int n = Math.max(l.size(), 1);
        int total = (n + multiplo - 1) / multiplo * multiplo;
        byte[] b = new byte[total * 4];
        for (int i = 0; i < total; i++) i32(b, 4 * i, i < l.size() ? l.get(i) : LIBRE);
        return b;
    }

    private static void relleno(ByteArrayOutputStream o, int m) {
        int r = o.size() % m;
        if (r != 0) o.write(new byte[m - r], 0, m - r);
    }

    private static void u16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void i32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }
}
