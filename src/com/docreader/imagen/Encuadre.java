package com.docreader.imagen;

/**
 * Matemáticas del encuadre de una imagen dentro de la vista, sin dependencias de Android
 * (se puede probar en la JVM).
 *
 * La imagen (iw × ih píxeles) se gira primero un múltiplo de 90° en sentido horario, se escala
 * por {@code s} y se desplaza para que su esquina superior izquierda (ya girada) quede en
 * (tx, ty) de la vista. La transformación resultante es afín:
 * <pre>
 *   vx = a·x + b·y + c
 *   vy = d·x + e·y + f
 * </pre>
 * y se entrega en el formato de {@code android.graphics.Matrix#setValues}.
 */
final class Encuadre {
    static final float ZOOM_MAX = 8f;       // respecto a "ajustar a la pantalla"
    static final float PIXEL_MAX = 4f;      // o 4 píxeles de pantalla por píxel de imagen
    static final float ZOOM_DOBLE = 2.5f;   // doble toque
    private static final float TOLERANCIA = 1.02f;

    /** Escala y desplazamiento: suficiente para reproducir (o animar) un encuadre. */
    static final class Estado {
        final float s, tx, ty;

        Estado(float s, float tx, float ty) {
            this.s = s;
            this.tx = tx;
            this.ty = ty;
        }
    }

    private int vw, vh, iw, ih, giro;
    private float s = 1f, tx, ty;
    private boolean ajustado = true;

    /**
     * Tamaño al que decodificar una imagen de w × h para no pasar de {@code ladoMax} píxeles en el
     * lado mayor ni de {@code pixelesMax} píxeles en total. Mantiene la proporción y nunca amplía.
     */
    static int[] tamanoDecodificado(int w, int h, int ladoMax, long pixelesMax) {
        if (w <= 0 || h <= 0) return new int[] {w, h};
        double f = Math.min(1.0, (double) ladoMax / Math.max(w, h));
        f = Math.min(f, Math.sqrt((double) pixelesMax / ((double) w * h)));
        if (f >= 1.0) return new int[] {w, h};
        return new int[] {Math.max(1, (int) Math.floor(w * f)), Math.max(1, (int) Math.floor(h * f))};
    }

    // --- Datos ---

    void setImagen(int ancho, int alto) {
        iw = Math.max(0, ancho);
        ih = Math.max(0, alto);
        giro = 0;
        ajustar();
    }

    /** Cambio de tamaño de la vista (p. ej. al girar el dispositivo). */
    void setVista(int ancho, int alto) {
        ancho = Math.max(0, ancho);
        alto = Math.max(0, alto);
        if (ancho == vw && alto == vh) return;
        boolean valido = valido();
        // Punto de la imagen (girada, sin escalar) que estaba en el centro de la vista.
        float cx = valido ? (vw / 2f - tx) / s : 0, cy = valido ? (vh / 2f - ty) / s : 0;
        vw = ancho;
        vh = alto;
        if (!valido || ajustado) {
            ajustar();
            return;
        }
        s = limitarEscala(s);
        tx = vw / 2f - cx * s;
        ty = vh / 2f - cy * s;
        if (s <= escalaAjuste() * TOLERANCIA) ajustar();
        else limitar();
    }

    boolean valido() { return vw > 0 && vh > 0 && iw > 0 && ih > 0; }

    int giro() { return giro; }

    float escala() { return s; }

    float tx() { return tx; }

    float ty() { return ty; }

    boolean ajustado() { return ajustado; }

    /** Ancho y alto de la imagen tras el giro, sin escalar. */
    float anchoGirado() { return giro % 2 == 0 ? iw : ih; }

    float altoGirado() { return giro % 2 == 0 ? ih : iw; }

    float escalaAjuste() {
        if (!valido()) return 1f;
        return Math.min(vw / anchoGirado(), vh / altoGirado());
    }

    float escalaMin() { return escalaAjuste(); }

    float escalaMax() { return Math.max(escalaAjuste() * ZOOM_MAX, PIXEL_MAX); }

    private float limitarEscala(float e) {
        if (Float.isNaN(e) || e <= 0) e = escalaMin();
        return Math.max(escalaMin(), Math.min(e, escalaMax()));
    }

    // --- Operaciones ---

    /** Ajusta la imagen entera a la vista, centrada. */
    void ajustar() {
        s = escalaAjuste();
        ajustado = true;
        limitar();
    }

    /** Gira 90° en sentido horario y vuelve a ajustar. */
    void girar() {
        giro = (giro + 1) % 4;
        ajustar();
    }

    /** Escala absoluta {@code nueva} manteniendo fijo el punto de la vista (fx, fy). */
    void zoomA(float nueva, float fx, float fy) {
        if (!valido()) return;
        nueva = limitarEscala(nueva);
        float f = nueva / s;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        s = nueva;
        ajustado = s <= escalaAjuste() * 1.0001f;
        limitar();
    }

    /** Arrastre: el contenido se mueve al revés que dx/dy (como un scroll). */
    void desplazar(float dx, float dy) {
        tx -= dx;
        ty -= dy;
        limitar();
    }

    /**
     * Mantiene la imagen dentro de la vista: centrada en el eje en que es más pequeña que la
     * vista y sin dejar huecos en el eje en que es más grande.
     */
    void limitar() {
        tx = limitarEje(tx, anchoGirado() * s, vw);
        ty = limitarEje(ty, altoGirado() * s, vh);
    }

    private static float limitarEje(float t, float contenido, float vista) {
        if (contenido <= vista) return (vista - contenido) / 2f;
        return Math.max(vista - contenido, Math.min(t, 0f));
    }

    // --- Desplazamiento como offsets (para OverScroller): offset = -t ---

    int offX() { return Math.round(-tx); }

    int offY() { return Math.round(-ty); }

    int minOffX() { return Math.round(-limitarEje(Float.MAX_VALUE, anchoGirado() * s, vw)); }

    int maxOffX() { return Math.round(-limitarEje(-Float.MAX_VALUE, anchoGirado() * s, vw)); }

    int minOffY() { return Math.round(-limitarEje(Float.MAX_VALUE, altoGirado() * s, vh)); }

    int maxOffY() { return Math.round(-limitarEje(-Float.MAX_VALUE, altoGirado() * s, vh)); }

    void colocarOffset(float ox, float oy) {
        tx = -ox;
        ty = -oy;
        limitar();
    }

    // --- Estados (animaciones) ---

    Estado estado() { return new Estado(s, tx, ty); }

    void aplicar(Estado e) {
        s = limitarEscala(e.s);
        tx = e.tx;
        ty = e.ty;
        ajustado = s <= escalaAjuste() * 1.0001f;
        limitar();
    }

    /** Destino del doble toque en (fx, fy): si está ampliada vuelve a ajustar; si no, 2,5×. */
    Estado destinoDobleToque(float fx, float fy) {
        Estado antes = estado();
        boolean ajustadoAntes = ajustado;
        if (s > escalaAjuste() * TOLERANCIA) ajustar();
        else zoomA(escalaAjuste() * ZOOM_DOBLE, fx, fy);
        Estado destino = estado();
        s = antes.s;
        tx = antes.tx;
        ty = antes.ty;
        ajustado = ajustadoAntes;
        return destino;
    }

    /** Interpolación lineal (los bordes de la imagen se mueven en línea recta). */
    static Estado interpolar(Estado a, Estado b, float t) {
        return new Estado(a.s + (b.s - a.s) * t, a.tx + (b.tx - a.tx) * t, a.ty + (b.ty - a.ty) * t);
    }

    /** Sin limitar: para los fotogramas intermedios de una animación entre dos estados válidos. */
    void aplicarSinLimitar(Estado e) {
        s = e.s;
        tx = e.tx;
        ty = e.ty;
    }

    // --- Matriz ---

    /** Rellena los 9 valores de una {@code android.graphics.Matrix}. */
    void valores(float[] m) {
        float a, b, c, d, e, f;
        switch (giro) {
            case 1:  // (x, y) → (ih − y, x)
                a = 0; b = -s; c = s * ih + tx;
                d = s; e = 0; f = ty;
                break;
            case 2:  // (x, y) → (iw − x, ih − y)
                a = -s; b = 0; c = s * iw + tx;
                d = 0; e = -s; f = s * ih + ty;
                break;
            case 3:  // (x, y) → (y, iw − x)
                a = 0; b = s; c = tx;
                d = -s; e = 0; f = s * iw + ty;
                break;
            default:
                a = s; b = 0; c = tx;
                d = 0; e = s; f = ty;
        }
        m[0] = a; m[1] = b; m[2] = c;
        m[3] = d; m[4] = e; m[5] = f;
        m[6] = 0; m[7] = 0; m[8] = 1;
    }

    /** Punto de la imagen original → punto de la vista. */
    float[] aVista(float x, float y) {
        float[] m = new float[9];
        valores(m);
        return new float[] {m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]};
    }

    /** Punto de la vista → punto de la imagen original. */
    float[] aImagen(float vx, float vy) {
        float gx = (vx - tx) / s, gy = (vy - ty) / s;  // coordenadas en la imagen girada
        switch (giro) {
            case 1: return new float[] {gy, ih - gx};
            case 2: return new float[] {iw - gx, ih - gy};
            case 3: return new float[] {iw - gy, gx};
            default: return new float[] {gx, gy};
        }
    }
}
