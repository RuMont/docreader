package com.docreader;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageGotoLinkContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Funciones de PdfRenderer que solo existen desde Android 15 (API 35). */
final class Api35 {
    private Api35() { }

    static PdfRenderer abrir(ParcelFileDescriptor d, String clave) throws IOException {
        return new PdfRenderer(d, new LoadParams.Builder().setPassword(clave).build());
    }

    static List<VistaPdf.Enlace> enlaces(PdfRenderer.Page p) {
        List<VistaPdf.Enlace> r = new ArrayList<>();
        for (PdfPageGotoLinkContent g : p.getGotoLinks()) {
            for (RectF b : g.getBounds()) {
                VistaPdf.Enlace e = new VistaPdf.Enlace();
                e.r = b;
                e.destino = g.getDestination().getPageNumber();
                r.add(e);
            }
        }
        for (PdfPageLinkContent l : p.getLinkContents()) {
            for (RectF b : l.getBounds()) {
                VistaPdf.Enlace e = new VistaPdf.Enlace();
                e.r = b;
                e.uri = l.getUri();
                r.add(e);
            }
        }
        return r;
    }

    static List<List<RectF>> buscar(PdfRenderer.Page p, String texto) {
        List<List<RectF>> r = new ArrayList<>();
        for (PageMatchBounds m : p.searchText(texto)) r.add(m.getBounds());
        return r;
    }

    /** ¿La página tiene una capa de texto real (no es solo una imagen escaneada)? */
    static boolean tieneTexto(PdfRenderer.Page p) {
        int letras = 0;
        for (PdfPageTextContent t : p.getTextContents()) {
            String s = t.getText();
            if (s != null) letras += s.trim().length();
            if (letras >= 3) return true;
        }
        return false;
    }

    /** Selección nativa entre dos puntos; si son el mismo punto, la palabra en ese punto. */
    static Seleccion seleccionar(PdfRenderer.Page p, PointF a, PointF b) {
        PageSelection ps = p.selectContent(
                new SelectionBoundary(new Point(Math.round(a.x), Math.round(a.y))),
                new SelectionBoundary(new Point(Math.round(b.x), Math.round(b.y))));
        if (ps == null) return null;
        StringBuilder sb = new StringBuilder();
        List<RectF> rects = new ArrayList<>();
        for (PdfPageTextContent t : ps.getSelectedTextContents()) {
            if (t.getText() != null) sb.append(t.getText());
            rects.addAll(t.getBounds());
        }
        if (sb.toString().trim().isEmpty() || rects.isEmpty()) return null;
        Seleccion s = new Seleccion();
        s.a = a;
        s.b = b;
        s.texto = sb.toString();
        s.rects = rects;
        return s;
    }
}
