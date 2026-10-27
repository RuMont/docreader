package com.docreader.oficina;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Imágenes de los .doc: las que van en línea (PICF en el flujo Data) y las flotantes (dibujos
 * OfficeArt del flujo de tabla). Se sacan los BLIP JPEG, PNG y DIB como URI data:.
 */
final class DocImagen {
    private static final int MAX_IMAGEN = 16 << 20;
    private static final int PIB = 0x0104;

    private final byte[] wd, datos;
    /** Posición en el texto del ancla (carácter 0x08) → {spid, ancho en twips}. */
    private final Map<Integer, int[]> anclas = new HashMap<>();
    /** spid de la forma → índice (desde 1) de su imagen en el almacén de BLIP. */
    private final Map<Integer, Integer> pibs = new HashMap<>();
    /** Registros FBSE del almacén: {inicio de los datos, fin}. */
    private final List<int[]> fbse = new ArrayList<>();
    private byte[] arte;

    DocImagen(byte[] wd, byte[] datos) {
        this.wd = wd;
        this.datos = datos;
    }

    /** PlcfSpa del documento principal y OfficeArtContent (fcDggInfo). */
    void leerDibujos(byte[] plcSpa, byte[] arte) {
        if (plcSpa != null && plcSpa.length >= 30) {
            int n = (plcSpa.length - 4) / 30;
            for (int k = 0; k < n; k++) {
                int f = 4 * (n + 1) + 26 * k;
                int ancho = Cfb.i32(plcSpa, f + 12) - Cfb.i32(plcSpa, f + 4);
                anclas.put(Cfb.i32(plcSpa, 4 * k), new int[] {Cfb.i32(plcSpa, f), ancho});
            }
        }
        if (arte == null || anclas.isEmpty()) return;
        this.arte = arte;
        // OfficeArtDggContainer y, detrás, cada OfficeArtDgContainer precedido de un byte dgglbl.
        int off = 0;
        for (int vueltas = 0; off + 8 <= arte.length && vueltas < 64; vueltas++) {
            int tipo = Cfb.u16(arte, off + 2);
            if (tipo < 0xF000 || tipo > 0xF1FF) {
                off++;
                continue;
            }
            long fin = off + 8 + (Cfb.i32(arte, off + 4) & 0xFFFFFFFFL);
            recorrer(arte, off, (int) Math.min(fin, arte.length), 0, null);
            off = (int) Math.min(fin, Integer.MAX_VALUE);
        }
    }

    /** Recorre registros OfficeArt: guarda los FBSE y, de cada forma, su spid y su imagen (pib). */
    private void recorrer(byte[] b, int off, int fin, int prof, int[] forma) {
        for (int vueltas = 0; off + 8 <= fin && vueltas < 100_000; vueltas++) {
            int vi = Cfb.u16(b, off), tipo = Cfb.u16(b, off + 2), ini = off + 8;
            long largo = Cfb.i32(b, off + 4) & 0xFFFFFFFFL;
            int finR = (int) Math.min(ini + largo, fin);
            if (tipo == 0xF007) {
                fbse.add(new int[] {ini, finR});
            } else if (tipo == 0xF004 && prof < 16) {
                int[] f = {-1, -1};
                recorrer(b, ini, finR, prof + 1, f);
                if (f[0] != -1 && f[1] > 0) pibs.put(f[0], f[1]);
            } else if (tipo == 0xF00A && forma != null && ini + 4 <= finR) {
                forma[0] = Cfb.i32(b, ini);
            } else if ((tipo == 0xF00B || tipo == 0xF121 || tipo == 0xF122) && forma != null) {
                int n = vi >>> 4;
                for (int k = 0; k < n && ini + 6 * k + 6 <= finR; k++) {
                    int q = ini + 6 * k;
                    if ((Cfb.u16(b, q) & 0x3FFF) == PIB) forma[1] = Cfb.i32(b, q + 2);
                }
            } else if ((vi & 0xF) == 0xF && prof < 16) {
                recorrer(b, ini, finR, prof + 1, forma);
            }
            if (ini + largo >= fin) break;
            off = (int) (ini + largo);
        }
    }

    /** Imagen flotante anclada en "cp": {URI data: o null si no se puede mostrar, ancho px}; null si no es imagen. */
    String[] flotante(int cp) {
        int[] a = anclas.get(cp);
        if (a == null || arte == null) return null;
        Integer pib = pibs.get(a[0]);
        if (pib == null || pib < 1 || pib > fbse.size()) return null;
        int[] r = fbse.get(pib - 1);
        int ini = r[0], fin = r[1];
        if (ini + 36 > fin) return null;
        int cbNombre = arte[ini + 33] & 0xFF;
        String uri = null;
        if (ini + 36 + cbNombre + 8 <= fin) {
            uri = blip(arte, ini + 36 + cbNombre, fin);
        } else {
            long foDelay = Cfb.i32(arte, ini + 28) & 0xFFFFFFFFL, tam = Cfb.i32(arte, ini + 20) & 0xFFFFFFFFL;
            if (foDelay + 8 <= wd.length) uri = blip(wd, (int) foDelay, (int) Math.min(wd.length, foDelay + tam));
        }
        return new String[] {uri, ancho(a[1])};
    }

    /** Imagen en línea (carácter 0x01) cuyo PICF está en "fc" del flujo Data; null si no hay. */
    String[] enLinea(int fc) {
        if (datos == null || fc < 0 || fc + 68 > datos.length) return null;
        int lcb = Cfb.i32(datos, fc), cbCab = Cfb.u16(datos, fc + 4);
        if (lcb < 68 || cbCab < 68 || cbCab > lcb) return null;
        int fin = (int) Math.min((long) fc + lcb, datos.length);
        int mm = (short) Cfb.u16(datos, fc + 6);
        // PICMID: dxaGoal, dyaGoal, mx, my y recortes, en twips y milésimas.
        int dxaGoal = (short) Cfb.u16(datos, fc + 28), mx = Cfb.u16(datos, fc + 32);
        int p = fc + cbCab;
        if (mm == 0x66 && p < fin) p += 1 + (datos[p] & 0xFF);
        String uri = buscarBlip(datos, p, fin, 0);
        if (uri == null) uri = buscarFirma(datos, p, fin);
        return new String[] {uri, ancho((int) ((long) dxaGoal * (mx == 0 ? 1000 : mx) / 1000))};
    }

    private static String ancho(int twips) {
        return twips > 15 && twips < 15 * 4000 ? Html.px(twips / 15.0) : null;
    }

    /** Busca el primer BLIP dentro de una secuencia de registros (contenedores y FBSE incluidos). */
    private static String buscarBlip(byte[] b, int off, int fin, int prof) {
        for (int vueltas = 0; off + 8 <= fin && vueltas < 1000; vueltas++) {
            int vi = Cfb.u16(b, off), tipo = Cfb.u16(b, off + 2), ini = off + 8;
            long largo = Cfb.i32(b, off + 4) & 0xFFFFFFFFL;
            int finR = (int) Math.min(ini + largo, fin);
            String u = null;
            if (tipo >= 0xF018 && tipo <= 0xF117) {
                u = blip(b, off, finR);
            } else if (tipo == 0xF007 && ini + 36 <= finR && prof < 8) {
                u = buscarBlip(b, ini + 36 + (b[ini + 33] & 0xFF), finR, prof + 1);
            } else if ((vi & 0xF) == 0xF && prof < 8) {
                u = buscarBlip(b, ini, finR, prof + 1);
            }
            if (u != null) return u;
            if (ini + largo >= fin) break;
            off = (int) (ini + largo);
        }
        return null;
    }

    /** Registro BLIP en "off" → URI data: (JPEG, PNG o DIB como BMP); null para metarchivos y TIFF. */
    static String blip(byte[] b, int off, int fin) {
        if (off < 0 || off + 8 > fin || fin > b.length) return null;
        int inst = Cfb.u16(b, off) >>> 4, tipo = Cfb.u16(b, off + 2);
        int finR = (int) Math.min(off + 8 + (Cfb.i32(b, off + 4) & 0xFFFFFFFFL), fin);
        int a = off + 8 + ((inst & 1) != 0 ? 32 : 16) + 1;
        if (a + 4 > finR || finR - a > MAX_IMAGEN) return null;
        switch (tipo) {
            case 0xF01D: case 0xF02A: return uri("image/jpeg", b, a, finR);
            case 0xF01E: return uri("image/png", b, a, finR);
            case 0xF01F: return bmp(b, a, finR);
            default: return null;
        }
    }

    /** Imagen JPEG o PNG suelta (formatos antiguos sin OfficeArt). */
    private static String buscarFirma(byte[] b, int off, int fin) {
        for (int i = Math.max(0, off); i + 8 <= fin && i < off + 4096; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8 && (b[i + 2] & 0xFF) == 0xFF) {
                return uri("image/jpeg", b, i, fin);
            }
            if ((b[i] & 0xFF) == 0x89 && b[i + 1] == 'P' && b[i + 2] == 'N' && b[i + 3] == 'G') {
                return uri("image/png", b, i, fin);
            }
        }
        return null;
    }

    private static String uri(String tipo, byte[] b, int a, int fin) {
        if (fin - a > MAX_IMAGEN || fin <= a) return null;
        return "data:" + tipo + ";base64," + Base64.getEncoder().encodeToString(Arrays.copyOfRange(b, a, fin));
    }

    /** DIB (BITMAPINFOHEADER + paleta + píxeles) → archivo BMP con su cabecera de 14 bytes. */
    private static String bmp(byte[] b, int a, int fin) {
        if (a + 40 > fin) return null;
        int biSize = Cfb.i32(b, a), bits = Cfb.u16(b, a + 14), compresion = Cfb.i32(b, a + 16);
        int colores = Cfb.i32(b, a + 32);
        if (biSize < 40 || biSize > 124 || colores < 0 || colores > 256) return null;
        if (colores == 0 && bits <= 8) colores = 1 << bits;
        int mascaras = compresion == 3 && biSize == 40 ? 12 : 0;
        int datos = fin - a, tam = 14 + datos;
        byte[] r = new byte[tam];
        r[0] = 'B';
        r[1] = 'M';
        poner32(r, 2, tam);
        poner32(r, 10, 14 + biSize + mascaras + 4 * colores);
        System.arraycopy(b, a, r, 14, datos);
        return "data:image/bmp;base64," + Base64.getEncoder().encodeToString(r);
    }

    private static void poner32(byte[] r, int o, int v) {
        for (int k = 0; k < 4; k++) r[o + k] = (byte) (v >>> (8 * k));
    }
}
