package com.docreader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/** Tipo de documento por la extensión: nombre para la lista e insignia de color apagado. */
final class TipoDoc {
    private TipoDoc() { }

    private static String ext(String nombre) {
        int p = nombre == null ? -1 : nombre.lastIndexOf('.');
        return p < 0 ? "" : nombre.substring(p + 1).toLowerCase(Locale.ROOT);
    }

    /** Nombre del tipo («PDF», «Word»…), o "" si no se reconoce. */
    static String nombre(String archivo) {
        String e = ext(archivo);
        switch (familia(e)) {
            case 'p': return "PDF";
            case 'w': return e.startsWith("od") || e.startsWith("ot") ? "Documento ODF" : "Word";
            case 'x': return e.startsWith("od") || e.startsWith("ot") ? "Hoja ODF" : e.equals("csv") || e.equals("tsv") ? "CSV" : "Excel";
            case 'd': return e.startsWith("od") || e.startsWith("ot") ? "Presentación ODF" : "PowerPoint";
            case 'e': return "EPUB";
            case 'm': return "Markdown";
            case 'i': return "Imagen";
            case 't': return "Texto";
            case 'c': return "Código";
            default: return e.length() > 0 && e.length() <= 6 ? e.toUpperCase(Locale.ROOT) : "";
        }
    }

    private static char familia(String e) {
        switch (e) {
            case "pdf": return 'p';
            case "doc": case "docx": case "docm": case "dot": case "dotx": case "dotm": case "odt": case "ott": case "rtf": return 'w';
            case "xls": case "xlsx": case "xlsm": case "xlt": case "xltx": case "xltm": case "ods": case "ots": case "csv": case "tsv": return 'x';
            case "ppt": case "pptx": case "pptm": case "pps": case "ppsx": case "ppsm": case "pot": case "potx": case "potm":
            case "odp": case "otp": case "odg": return 'd';
            case "epub": return 'e';
            case "md": case "markdown": case "mdown": case "mkd": return 'm';
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic": case "heif": case "avif":
            case "ico": case "svg": return 'i';
            case "txt": case "log": case "text": return 't';
            case "": return '?';
            default: return 'c';
        }
    }

    private static String etiqueta(String e) {
        switch (familia(e)) {
            case 'p': return "PDF";
            case 'w': return e.startsWith("od") || e.startsWith("ot") ? "ODT" : "DOC";
            case 'x': return e.startsWith("od") || e.startsWith("ot") ? "ODS" : e.equals("csv") || e.equals("tsv") ? "CSV" : "XLS";
            case 'd': return e.startsWith("od") || e.startsWith("ot") ? "ODP" : "PPT";
            case 'e': return "EPUB";
            case 'm': return "MD";
            case 'i': return "IMG";
            case 't': return "TXT";
            case 'c': return "</>";
            default: return "?";
        }
    }

    /** Colores apagados por familia, acordes con la paleta neutra. */
    private static int color(String e) {
        switch (familia(e)) {
            case 'p': return 0xFFB0493F;
            case 'w': return 0xFF3F67A0;
            case 'x': return 0xFF3E7A55;
            case 'd': return 0xFFB0653A;
            case 'e': return 0xFF76599A;
            case 'i': return 0xFF8A6D3B;
            default: return 0xFF5E6873;
        }
    }

    static Drawable insignia(Context c, String archivo) {
        String e = ext(archivo);
        int base = color(e);
        boolean oscuro = Estilo.oscuro(c);
        // En oscuro el texto se aclara para que contraste con el fondo.
        int textoColor = oscuro ? mezcla(base, 0xFFFFFFFF, 0.45f) : base;
        int fondoColor = (base & 0x00FFFFFF) | (oscuro ? 0x3A000000 : 0x1E000000);
        return new Insignia(etiqueta(e), textoColor, fondoColor, Estilo.dp(c, 44), Estilo.dp(c, 12));
    }

    private static int mezcla(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static final class Insignia extends Drawable {
        private final String texto;
        private final Paint fondo = new Paint(Paint.ANTI_ALIAS_FLAG), letra = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int tam;
        private final float radio;

        Insignia(String texto, int colorTexto, int colorFondo, int tam, float radio) {
            this.texto = texto;
            this.tam = tam;
            this.radio = radio;
            fondo.setColor(colorFondo);
            letra.setColor(colorTexto);
            letra.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            letra.setTextAlign(Paint.Align.CENTER);
            letra.setLetterSpacing(0.04f);
        }

        @Override public int getIntrinsicWidth() { return tam; }

        @Override public int getIntrinsicHeight() { return tam; }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawRoundRect(new RectF(b), radio, radio, fondo);
            letra.setTextSize(b.height() * (texto.length() >= 4 ? 0.24f : 0.29f));
            float y = b.exactCenterY() - (letra.descent() + letra.ascent()) / 2;
            c.drawText(texto, b.exactCenterX(), y, letra);
        }

        @Override public void setAlpha(int a) { fondo.setAlpha(a); letra.setAlpha(a); }

        @Override public void setColorFilter(ColorFilter f) { fondo.setColorFilter(f); letra.setColorFilter(f); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
