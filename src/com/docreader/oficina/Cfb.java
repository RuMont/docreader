package com.docreader.oficina;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lector del contenedor «Compound File Binary» (MS-CFB) de los Office 97–2003 (.doc, .xls, .ppt):
 * un pequeño sistema de archivos con sectores, una tabla FAT y un directorio en árbol.
 * Todo en memoria y sin dependencias de Android, para poder probarlo en la JVM.
 */
final class Cfb {
    private static final int FIN_CADENA = 0xFFFFFFFE, LIBRE = 0xFFFFFFFF, NINGUNO = 0xFFFFFFFF;
    private static final int TIPO_ALMACEN = 1, TIPO_FLUJO = 2, TIPO_RAIZ = 5;

    private final byte[] d;
    private final int tamSector, tamMini, corteMini;
    private final int[] fat;
    private int[] miniFat = new int[0];
    private byte[] miniFlujo = new byte[0];
    /** Ruta ("WordDocument", "ObjectPool/_123/Ole") en minúsculas → entrada del directorio. */
    private final Map<String, Entrada> entradas = new LinkedHashMap<>();

    private static final class Entrada {
        String nombre;
        int tipo, izquierda, derecha, hijo, inicio;
        long tam;
    }

    static boolean es(byte[] d) {
        return d.length >= 512 && (d[0] & 0xFF) == 0xD0 && (d[1] & 0xFF) == 0xCF && (d[2] & 0xFF) == 0x11
                && (d[3] & 0xFF) == 0xE0 && (d[4] & 0xFF) == 0xA1 && (d[5] & 0xFF) == 0xB1
                && (d[6] & 0xFF) == 0x1A && (d[7] & 0xFF) == 0xE1;
    }

    static Cfb leer(byte[] datos) throws Fallo {
        if (!es(datos)) throw new Fallo("El archivo no es un documento de Office 97–2003 válido.");
        try {
            return new Cfb(datos);
        } catch (Fallo f) {
            throw f;
        } catch (RuntimeException e) {
            throw new Fallo("El archivo está dañado y no se puede leer.");
        }
    }

    private Cfb(byte[] datos) throws Fallo {
        d = datos;
        int desplSector = u16(30), desplMini = u16(32);
        if (desplSector != 9 && desplSector != 12) throw new Fallo("El archivo está dañado y no se puede leer.");
        tamSector = 1 << desplSector;
        tamMini = 1 << Math.min(Math.max(desplMini, 6), 12);
        corteMini = i32(56) > 0 ? i32(56) : 4096;
        int sectoresTotales = Math.max(0, (d.length - tamSector) / tamSector) + 1;

        // DIFAT: 109 entradas en la cabecera y el resto encadenado en sectores DIFAT.
        List<Integer> sectoresFat = new ArrayList<>();
        for (int i = 0; i < 109; i++) {
            int s = i32(76 + 4 * i);
            if (s != LIBRE && s >= 0) sectoresFat.add(s);
        }
        int difat = i32(68), vueltas = 0;
        int porSector = tamSector / 4 - 1;
        while (difat != FIN_CADENA && difat != LIBRE && difat >= 0 && vueltas++ < sectoresTotales) {
            int base = desplazamiento(difat);
            for (int i = 0; i < porSector; i++) {
                int s = i32(base + 4 * i);
                if (s != LIBRE && s >= 0) sectoresFat.add(s);
            }
            difat = i32(base + 4 * porSector);
        }
        int entradasFat = tamSector / 4;
        fat = new int[sectoresFat.size() * entradasFat];
        for (int k = 0; k < sectoresFat.size(); k++) {
            int base = desplazamiento(sectoresFat.get(k));
            for (int i = 0; i < entradasFat; i++) fat[k * entradasFat + i] = i32Seguro(base + 4 * i);
        }

        // Directorio.
        byte[] dir = cadena(i32(48), -1);
        List<Entrada> lista = new ArrayList<>();
        for (int off = 0; off + 128 <= dir.length; off += 128) {
            Entrada e = new Entrada();
            int largo = Math.min(64, Math.max(0, u16(dir, off + 64) - 2));
            e.nombre = new String(dir, off, largo & ~1, StandardCharsets.UTF_16LE);
            e.tipo = dir[off + 66] & 0xFF;
            e.izquierda = i32(dir, off + 68);
            e.derecha = i32(dir, off + 72);
            e.hijo = i32(dir, off + 76);
            e.inicio = i32(dir, off + 116);
            e.tam = (i32(dir, off + 120) & 0xFFFFFFFFL);
            if (tamSector == 512) e.tam &= 0xFFFFFFFFL; // v3: la parte alta puede traer basura
            else e.tam |= ((long) i32(dir, off + 124)) << 32;
            lista.add(e);
        }
        if (lista.isEmpty() || lista.get(0).tipo != TIPO_RAIZ) throw new Fallo("El archivo está dañado y no se puede leer.");

        Entrada raiz = lista.get(0);
        miniFlujo = cadena(raiz.inicio, raiz.tam);
        byte[] mf = i32(60) == FIN_CADENA ? new byte[0] : cadena(i32(60), -1);
        miniFat = new int[mf.length / 4];
        for (int i = 0; i < miniFat.length; i++) miniFat[i] = i32(mf, 4 * i);

        recorrer(lista, raiz.hijo, "", new boolean[lista.size()], 0);
    }

    /** Recorre el árbol rojo-negro del directorio (hermanos izquierda/derecha, hijos de almacenes). */
    private void recorrer(List<Entrada> l, int i, String prefijo, boolean[] visto, int prof) {
        if (i == NINGUNO || i < 0 || i >= l.size() || visto[i] || prof > 64) return;
        visto[i] = true;
        Entrada e = l.get(i);
        recorrer(l, e.izquierda, prefijo, visto, prof + 1);
        String ruta = prefijo + e.nombre;
        if (e.tipo == TIPO_FLUJO || e.tipo == TIPO_ALMACEN) entradas.put(ruta.toLowerCase(Locale.ROOT), e);
        if (e.tipo == TIPO_ALMACEN) recorrer(l, e.hijo, ruta + "/", visto, prof + 1);
        recorrer(l, e.derecha, prefijo, visto, prof + 1);
    }

    // --- API ---

    /** ¿Existe el flujo o almacén? La ruta no distingue mayúsculas ("1Table", "ObjectPool/_1/Ole"). */
    boolean existe(String ruta) { return entradas.containsKey(ruta.toLowerCase(Locale.ROOT)); }

    /** Contenido de un flujo, o null si no existe. */
    byte[] flujo(String ruta) {
        Entrada e = entradas.get(ruta.toLowerCase(Locale.ROOT));
        if (e == null || e.tipo != TIPO_FLUJO) return null;
        if (e.tam == 0) return new byte[0];
        return e.tam < corteMini ? miniCadena(e.inicio, e.tam) : cadena(e.inicio, e.tam);
    }

    /** Rutas de todos los flujos y almacenes, tal como vienen escritas. */
    List<String> nombres() {
        List<String> r = new ArrayList<>();
        for (Entrada e : entradas.values()) r.add(e.nombre);
        return Collections.unmodifiableList(r);
    }

    /** Rutas completas (en minúsculas) de los flujos. */
    List<String> rutas() { return new ArrayList<>(entradas.keySet()); }

    // --- Cadenas de sectores ---

    /** Sigue la FAT desde "inicio"; con tam < 0 lee la cadena entera. Resistente a ciclos. */
    private byte[] cadena(int inicio, long tam) {
        long limite = tam < 0 ? (long) fat.length * tamSector : Math.min(tam, d.length);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream((int) Math.min(limite, 1 << 20));
        int s = inicio, pasos = 0;
        while (s >= 0 && s != FIN_CADENA && s < fat.length && o.size() < limite && pasos++ <= fat.length) {
            int off = desplazamiento(s);
            if (off < 0 || off >= d.length) break;
            int n = (int) Math.min(tamSector, Math.min(limite - o.size(), d.length - off));
            o.write(d, off, n);
            s = fat[s];
        }
        return o.toByteArray();
    }

    private byte[] miniCadena(int inicio, long tam) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream((int) Math.min(tam, 1 << 16));
        int s = inicio, pasos = 0;
        while (s >= 0 && s != FIN_CADENA && s < miniFat.length && o.size() < tam && pasos++ <= miniFat.length) {
            int off = s * tamMini;
            if (off >= miniFlujo.length) break;
            int n = (int) Math.min(tamMini, Math.min(tam - o.size(), miniFlujo.length - off));
            o.write(miniFlujo, off, n);
            s = miniFat[s];
        }
        return o.toByteArray();
    }

    private int desplazamiento(int sector) { return (sector + 1) * tamSector; }

    // --- Lectura little-endian ---

    private int u16(int off) { return u16(d, off); }

    private int i32(int off) { return i32(d, off); }

    private int i32Seguro(int off) { return off + 4 <= d.length ? i32(d, off) : LIBRE; }

    static int u16(byte[] b, int off) { return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8; }

    static int i32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }
}
