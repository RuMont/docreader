package com.docreader.oficina;

/** Error con un mensaje pensado para mostrárselo tal cual al usuario. */
class Fallo extends Exception {
    private static final long serialVersionUID = 1L;

    Fallo(String mensaje) {
        super(mensaje);
    }
}
