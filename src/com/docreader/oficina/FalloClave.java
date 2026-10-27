package com.docreader.oficina;

/**
 * El documento está cifrado: hace falta contraseña (o la dada no es correcta).
 * El visor la pide al usuario y vuelve a intentar la conversión con ella.
 */
final class FalloClave extends Fallo {
    private static final long serialVersionUID = 1L;

    final boolean incorrecta;

    FalloClave(boolean incorrecta) {
        super(incorrecta ? "Contraseña incorrecta." : "Este documento está protegido con contraseña.");
        this.incorrecta = incorrecta;
    }

    /** Sin contraseña → hay que pedirla; con contraseña → era incorrecta. */
    static FalloClave para(String clave) { return new FalloClave(clave != null); }
}
