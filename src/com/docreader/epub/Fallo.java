package com.docreader.epub;

/** Error al abrir un libro, con un mensaje pensado para mostrárselo al usuario tal cual. */
final class Fallo extends Exception {
    Fallo(String mensaje) {
        super(mensaje);
    }
}
