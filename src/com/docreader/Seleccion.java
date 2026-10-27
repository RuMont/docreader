package com.docreader;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.List;

/** Texto seleccionado en una página. Puntos y rectángulos en puntos de la página. */
final class Seleccion {
    int pag;
    PointF a, b;
    String texto;
    List<RectF> rects;

    RectF primero() { return rects.get(0); }

    RectF ultimo() { return rects.get(rects.size() - 1); }
}
