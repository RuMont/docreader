package com.docreader.herramientas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Herramientas del menú del lector de PDF: extraer/dividir, convertir a imágenes y a Word, y
 * abrir la pantalla de fusionar. La actividad le reenvía sus onActivityResult.
 */
public final class HerramientasPdf {
    /** Lo que la herramienta necesita saber del documento abierto. */
    public interface Documento {
        Uri uri();
        String nombre();
        int paginas();
        int paginaActual();
        /** Texto del OCR de la página (si se reconoció), o null. */
        String textoOcr(int pag);
        /** Contraseña con la que se abrió el PDF, o null si no tiene. */
        String clave();
    }

    private static final int GUARDAR_EXTRAIDO = 40, CARPETA_PARTES = 41, GUARDAR_DOCX = 42,
            GUARDAR_PROTEGIDO = 43, GUARDAR_SIN_CLAVE = 44;

    private final Activity a;
    private final Documento doc;
    private List<int[]> rangos; // pendiente entre el diálogo y el selector de destino
    private String claveNueva;  // ídem para proteger
    private com.docreader.DialogoProteger.Permisos permisos;

    public HerramientasPdf(Activity a, Documento doc) {
        this.a = a;
        this.doc = doc;
    }

    // --- Dividir / extraer ---

    public void dividir() {
        int total = doc.paginas();
        LinearLayout v = vertical();
        RadioGroup modos = new RadioGroup(a);
        RadioButton extraer = radio("Extraer páginas a un PDF nuevo");
        RadioButton porRangos = radio("Separar por rangos (un PDF por rango)");
        RadioButton cadaN = radio("Dividir cada N páginas");
        RadioButton una = radio("Una página por archivo");
        for (RadioButton r : new RadioButton[] {extraer, porRangos, cadaN, una}) modos.addView(r);
        v.addView(modos);
        EditText campo = new EditText(a);
        campo.setSingleLine(true);
        v.addView(campo);
        TextView ayuda = new TextView(a);
        ayuda.setAlpha(0.7f);
        v.addView(ayuda);

        Runnable actualizar = () -> {
            int id = modos.getCheckedRadioButtonId();
            campo.setVisibility(id == una.getId() ? View.GONE : View.VISIBLE);
            campo.setError(null);
            if (id == cadaN.getId()) {
                campo.setInputType(InputType.TYPE_CLASS_NUMBER);
                campo.setHint("Páginas por parte, p. ej. 10");
                ayuda.setText("El documento tiene " + total + " páginas.");
            } else {
                campo.setInputType(InputType.TYPE_CLASS_TEXT);
                campo.setHint(id == extraer.getId() ? "p. ej. 1-3, 5, 8-" : "p. ej. 1-3, 4-10, 11-");
                ayuda.setText("Páginas de 1 a " + total + ". «8-» significa de la 8 al final.");
            }
        };
        modos.setOnCheckedChangeListener((g, id) -> actualizar.run());
        modos.check(extraer.getId());

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Dividir PDF")
                .setView(v)
                .setPositiveButton("Continuar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            int id = modos.getCheckedRadioButtonId();
            String t = campo.getText().toString().trim();
            try {
                if (id == una.getId()) {
                    rangos = Rangos.cada(1, total);
                } else if (id == cadaN.getId()) {
                    int n;
                    try {
                        n = Integer.parseInt(t);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Escribe cuántas páginas quieres en cada parte.");
                    }
                    rangos = Rangos.cada(n, total);
                } else {
                    rangos = Rangos.leer(t, total);
                }
            } catch (IllegalArgumentException e) {
                campo.setError(e.getMessage());
                return;
            }
            d.dismiss();
            if (id == extraer.getId()) {
                String nombre = Tarea.base(doc.nombre()) + "_paginas_" + t.replace(" ", "").replace(",", "_") + ".pdf";
                a.startActivityForResult(crear("application/pdf", nombre), GUARDAR_EXTRAIDO);
            } else if (rangos.size() == 1) {
                Tarea.mensaje(a, "Con esa división sale un único PDF igual al original.");
            } else {
                a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), CARPETA_PARTES);
            }
        }));
        d.show();
    }

    private void extraerEn(Uri destino) {
        List<int[]> r = rangos;
        new Tarea(a, "Extrayendo páginas") {
            @Override void trabajo() throws Exception {
                Tarea.iniciarPdfbox(a);
                progreso(0, 1, "Copiando el documento…");
                File f = Tarea.copiar(a, doc.uri(), "herramienta.pdf");
                try (OutputStream out = a.getContentResolver().openOutputStream(destino, "wt")) {
                    if (out == null) throw new IOException("No se pudo crear el archivo.");
                    progreso(0, 1, "Creando el PDF con " + Rangos.paginas(r) + " páginas…");
                    OperacionesPdf.extraer(f, doc.clave(), r, out);
                } finally {
                    f.delete();
                }
            }
            @Override void alTerminar() {
                listo(destino, "PDF creado con " + Rangos.paginas(r) + " páginas." + sinClave());
            }
        }.iniciar();
    }

    private void partesEn(Uri arbol) {
        List<int[]> r = rangos;
        String base = Tarea.base(doc.nombre());
        Uri carpeta = DocumentsContract.buildDocumentUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol));
        new Tarea(a, "Dividiendo el PDF") {
            int hechas;
            @Override void trabajo() throws Exception {
                Tarea.iniciarPdfbox(a);
                File f = Tarea.copiar(a, doc.uri(), "herramienta.pdf");
                try {
                    OperacionesPdf.dividir(f, doc.clave(), r, (k, rango) -> {
                        Uri u = DocumentsContract.createDocument(a.getContentResolver(), carpeta, "application/pdf",
                                base + "_parte" + (k + 1) + "_p" + Rangos.etiqueta(rango) + ".pdf");
                        OutputStream out = u == null ? null : a.getContentResolver().openOutputStream(u, "wt");
                        if (out == null) throw new IOException("No se pudo crear un archivo en esa carpeta.");
                        hechas = k + 1;
                        return out;
                    }, (h, t) -> progreso(h, t, "Parte " + Math.min(h + 1, t) + " de " + t));
                } finally {
                    f.delete();
                }
            }
            @Override void alTerminar() {
                Tarea.mensaje(a, "Se han creado " + r.size() + " archivos PDF en la carpeta elegida." + sinClave());
            }
            @Override void alCancelar() {
                Tarea.mensaje(a, "División cancelada. Se crearon " + hechas + " de " + r.size() + " partes.");
            }
        }.iniciar();
    }

    // --- Convertir a imágenes ---

    public void aImagenes() {
        int total = doc.paginas(), actual = doc.paginaActual();
        LinearLayout v = vertical();
        v.addView(titulo("Páginas"));
        RadioGroup cuales = new RadioGroup(a);
        RadioButton todas = radio("Todas (" + total + ")");
        RadioButton esta = radio("Solo la página actual (" + (actual + 1) + ")");
        RadioButton rango = radio("Elegir páginas");
        cuales.addView(todas);
        cuales.addView(esta);
        cuales.addView(rango);
        v.addView(cuales);
        EditText campo = new EditText(a);
        campo.setHint("p. ej. 1-3, 5");
        campo.setSingleLine(true);
        campo.setVisibility(View.GONE);
        v.addView(campo);
        cuales.setOnCheckedChangeListener((g, id) -> campo.setVisibility(id == rango.getId() ? View.VISIBLE : View.GONE));
        cuales.check(total == 1 ? esta.getId() : todas.getId());

        v.addView(titulo("Formato"));
        RadioGroup formato = new RadioGroup(a);
        formato.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton jpg = radio("JPG"), png = radio("PNG");
        formato.addView(jpg);
        formato.addView(png);
        formato.check(jpg.getId());
        v.addView(formato);

        v.addView(titulo("Calidad"));
        RadioGroup calidad = new RadioGroup(a);
        calidad.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton normal = radio("Normal (150 ppp)"), alta = radio("Alta (300 ppp)");
        calidad.addView(normal);
        calidad.addView(alta);
        calidad.check(normal.getId());
        v.addView(calidad);

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Convertir a imágenes")
                .setView(v)
                .setPositiveButton("Convertir", null)
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            List<Integer> pags = new ArrayList<>();
            int id = cuales.getCheckedRadioButtonId();
            if (id == esta.getId()) {
                pags.add(actual);
            } else {
                List<int[]> r;
                try {
                    r = id == todas.getId() ? Rangos.cada(total, total) : Rangos.leer(campo.getText().toString(), total);
                } catch (IllegalArgumentException e) {
                    campo.setError(e.getMessage());
                    return;
                }
                for (int[] x2 : r) for (int i = x2[0]; i <= x2[1]; i++) pags.add(i);
            }
            d.dismiss();
            convertirImagenes(pags, png.isChecked(), alta.isChecked() ? 300 : 150);
        }));
        d.show();
    }

    private void convertirImagenes(List<Integer> pags, boolean png, int ppp) {
        String base = Tarea.base(doc.nombre());
        List<Uri> hechas = new ArrayList<>();
        new Tarea(a, "Convirtiendo a imágenes") {
            @Override void trabajo() throws Exception {
                File f = Tarea.copiar(a, doc.uri(), "herramienta.pdf");
                try (Paginas p = new Paginas(f, doc.clave())) {
                    hechas.addAll(p.aGaleria(a, pags, base, base, png, ppp,
                            (h, t) -> progreso(h, t, "Página " + Math.min(h + 1, t) + " de " + t)));
                } finally {
                    f.delete();
                }
            }
            @Override void alTerminar() { imagenesListas(hechas, base); }
            @Override void alCancelar() { if (!hechas.isEmpty()) imagenesListas(hechas, base); }
        }.iniciar();
    }

    private void imagenesListas(List<Uri> hechas, String carpeta) {
        new AlertDialog.Builder(a)
                .setTitle("Imágenes guardadas")
                .setMessage("Se han guardado " + hechas.size() + (hechas.size() == 1 ? " imagen" : " imágenes")
                        + " en la galería, en Imágenes › DocReader › " + carpeta + ".")
                .setPositiveButton("Aceptar", null)
                .setNeutralButton("Compartir", (x, w) -> {
                    Intent i = hechas.size() == 1
                            ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, hechas.get(0))
                            : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                                    Intent.EXTRA_STREAM, new ArrayList<>(hechas));
                    i.setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(i, "Compartir imágenes"));
                })
                .show();
    }

    // --- Convertir a Word ---

    public void aDocx() {
        a.startActivityForResult(crear("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Tarea.base(doc.nombre()) + ".docx"), GUARDAR_DOCX);
    }

    private void docxEn(Uri destino) {
        new Tarea(a, "Convirtiendo a Word") {
            @Override void trabajo() throws Exception {
                Tarea.iniciarPdfbox(a);
                File f = Tarea.copiar(a, doc.uri(), "herramienta.pdf");
                try (Paginas p = new Paginas(f, doc.clave());
                     OutputStream out = a.getContentResolver().openOutputStream(destino, "wt")) {
                    if (out == null) throw new IOException("No se pudo crear el archivo.");
                    PdfADocx.convertir(f, doc.clave(), out, Tarea.base(doc.nombre()), new PdfADocx.Paginas() {
                        @Override public String textoOcr(int pag) { return doc.textoOcr(pag); }
                        @Override public byte[] imagen(int pag, int[] tam) { return p.jpeg(pag, 150, tam); }
                    }, (h, t) -> progreso(h, t, "Página " + Math.min(h + 1, t) + " de " + t));
                } catch (Exception e) {
                    borrar(destino);
                    throw e;
                } finally {
                    f.delete();
                }
            }
            @Override void alTerminar() {
                listo(destino, "Documento de Word creado. Los títulos y párrafos se han reconstruido a partir del "
                        + "PDF; las tablas y columnas pueden necesitar retoques. Las páginas escaneadas sin OCR "
                        + "se incluyen como imagen." + sinClave());
            }
            @Override void alCancelar() { borrar(destino); }
        }.iniciar();
    }

    // --- Contraseña ---

    /** Copia del PDF protegida con contraseña (AES-256) y, opcionalmente, permisos limitados. */
    public void proteger() {
        com.docreader.DialogoProteger.mostrar(a, doc.nombre(), true, (clave, p) -> {
            claveNueva = clave;
            permisos = p;
            a.startActivityForResult(crear("application/pdf", Tarea.base(doc.nombre()) + "_protegido.pdf"),
                    GUARDAR_PROTEGIDO);
        });
    }

    public void quitarClave() {
        new AlertDialog.Builder(a)
                .setTitle("Quitar contraseña")
                .setMessage("Se guardará una copia del PDF sin contraseña ni restricciones. "
                        + "Cualquiera que tenga la copia podrá abrirla.")
                .setPositiveButton("Continuar", (x, w) -> a.startActivityForResult(
                        crear("application/pdf", Tarea.base(doc.nombre()) + "_sin_contraseña.pdf"), GUARDAR_SIN_CLAVE))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void claveEn(Uri destino, boolean proteger) {
        String nueva = claveNueva;
        com.docreader.DialogoProteger.Permisos p = permisos;
        claveNueva = null;
        boolean[] firmas = new boolean[1];
        new Tarea(a, proteger ? "Protegiendo el PDF" : "Quitando la contraseña") {
            @Override void trabajo() throws Exception {
                Tarea.iniciarPdfbox(a);
                File f = Tarea.copiar(a, doc.uri(), "herramienta.pdf");
                try (OutputStream out = a.getContentResolver().openOutputStream(destino, "wt")) {
                    if (out == null) throw new IOException("No se pudo crear el archivo.");
                    firmas[0] = proteger
                            ? OperacionesPdf.proteger(f, doc.clave(), nueva, p.imprimir, p.copiar, p.modificar, out)
                            : OperacionesPdf.quitarClave(f, doc.clave(), out);
                } catch (Exception e) {
                    borrar(destino);
                    throw e;
                } finally {
                    f.delete();
                }
            }
            @Override void alTerminar() {
                String m = proteger ? "Copia protegida con contraseña (cifrado AES-256)." : "Copia sin contraseña guardada.";
                if (firmas[0]) m += "\n\nAtención: las firmas digitales que tenía el PDF dejan de ser válidas en la copia.";
                listo(destino, m);
            }
            @Override void alCancelar() { borrar(destino); }
        }.iniciar();
    }

    // --- Imprimir ---

    /**
     * Manda el PDF al sistema de impresión. Si tiene contraseña, el servicio de impresión no
     * podría abrirlo: se imprime una copia temporal descifrada, salvo que el autor lo prohíba.
     */
    public void imprimir() {
        if (doc.clave() == null) {
            com.docreader.Impresion.pdf(a, doc.nombre(), doc.uri(), doc.paginas());
            return;
        }
        File[] copia = new File[1];
        boolean[] prohibido = new boolean[1];
        new Tarea(a, "Preparando la impresión") {
            @Override void trabajo() throws Exception {
                Tarea.iniciarPdfbox(a);
                File f = Tarea.copiar(a, doc.uri(), "herramienta.pdf");
                File destino = new File(a.getCacheDir(), "impresion.pdf");
                try (com.tom_roush.pdfbox.pdmodel.PDDocument d = OperacionesPdf.cargar(f, doc.clave())) {
                    if (!d.getCurrentAccessPermission().canPrint()) {
                        prohibido[0] = true;
                        return;
                    }
                    d.setAllSecurityToBeRemoved(true);
                    d.save(destino);
                    copia[0] = destino;
                } finally {
                    f.delete();
                }
            }
            @Override void alTerminar() {
                if (prohibido[0]) Tarea.mensaje(a, "El autor de este PDF no permite imprimirlo.");
                else if (copia[0] != null) com.docreader.Impresion.pdf(a, doc.nombre(), copia[0], doc.paginas(), true);
            }
        }.iniciar();
    }

    // --- Fusionar ---

    public void fusionar() {
        a.startActivity(new Intent(a, FusionarActivity.class).setData(doc.uri())
                .putExtra(FusionarActivity.EXTRA_NOMBRE, doc.nombre())
                .putExtra(FusionarActivity.EXTRA_CLAVE, doc.clave())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    // --- Resultados de los selectores ---

    /** true si el resultado era de una herramienta. */
    public boolean alResultado(int pedido, int resultado, Intent datos) {
        if (pedido != GUARDAR_EXTRAIDO && pedido != CARPETA_PARTES && pedido != GUARDAR_DOCX
                && pedido != GUARDAR_PROTEGIDO && pedido != GUARDAR_SIN_CLAVE) return false;
        if (resultado != Activity.RESULT_OK || datos == null || datos.getData() == null) return true;
        if (pedido == GUARDAR_EXTRAIDO) extraerEn(datos.getData());
        else if (pedido == CARPETA_PARTES) partesEn(datos.getData());
        else if (pedido == GUARDAR_PROTEGIDO) claveEn(datos.getData(), true);
        else if (pedido == GUARDAR_SIN_CLAVE) claveEn(datos.getData(), false);
        else docxEn(datos.getData());
        return true;
    }

    // --- Utilidades ---

    private void listo(Uri destino, String mensaje) {
        String tipo = a.getContentResolver().getType(destino);
        new AlertDialog.Builder(a)
                .setMessage(mensaje)
                .setPositiveButton("Abrir", (x, w) -> {
                    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(destino, tipo)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setPackage(a.getPackageName()); // con DocReader
                    try {
                        a.startActivity(i);
                    } catch (Exception e) {
                        Tarea.mensaje(a, "No se pudo abrir el archivo.");
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    /** Aviso para las copias de un PDF protegido: salen sin contraseña. */
    private String sinClave() {
        return doc.clave() == null ? "" : "\n\nEl PDF original tiene contraseña; el archivo nuevo no la tiene.";
    }

    private void borrar(Uri u) {
        try {
            DocumentsContract.deleteDocument(a.getContentResolver(), u);
        } catch (Exception ignorado) { }
    }

    private static Intent crear(String tipo, String nombre) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(tipo).putExtra(Intent.EXTRA_TITLE, nombre);
    }

    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        int m = dp(20);
        v.setPadding(m, dp(8), m, 0);
        return v;
    }

    private RadioButton radio(String texto) {
        RadioButton r = new RadioButton(a);
        r.setText(texto);
        r.setId(View.generateViewId());
        return r;
    }

    private TextView titulo(String texto) {
        TextView t = new TextView(a);
        t.setText(texto);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
