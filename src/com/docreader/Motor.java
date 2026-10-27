package com.docreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Todo acceso a PdfRenderer ocurre en un único hilo, porque no es thread-safe.
 * El OCR va en otro hilo para no bloquear el dibujado de páginas.
 */
final class Motor {
    interface AlAbrir { void listo(String error, boolean pideClave); }
    interface AlRenderBase { void listo(int pag, Bitmap b, List<VistaPdf.Enlace> enlaces, boolean fallo); }
    interface AlRenderDetalle { void listo(int gen, List<VistaPdf.Tesela> teselas); }
    interface Filtro { boolean quiere(int pag); }
    interface AlBuscar {
        void encontrado(int gen, int pag, List<List<RectF>> coincidencias);
        void terminado(int gen);
    }
    interface AlSeleccionar { void listo(int gen, Seleccion s, boolean necesitaOcr); }
    interface AlOcr {
        void progreso(int hechas, int total);
        void terminado(String error);
    }
    interface AlComprobar { void listo(boolean escaneado); }

    private final Context ctx;
    private final HandlerThread hilo = new HandlerThread("render-pdf");
    private final HandlerThread hiloOcr = new HandlerThread("ocr");
    private final Handler h, hOcr;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Ocr ocr;
    private ParcelFileDescriptor pfd;
    private PdfRenderer pdf;
    private File temporal;
    private volatile File dirOcr;

    // Solo se tocan en el hilo de render.
    private final SparseArray<CapaOcr> capas = new SparseArray<>();
    private Boolean[] textoNativo;

    int paginas;
    float[] anchos, altos;
    /** Identifica el contenido del archivo (no su ruta): clave de OCR y anotaciones. */
    String huella;
    volatile int genDetalle, genBusqueda, genSeleccion, genOcr;

    Motor(Context c) {
        ctx = c.getApplicationContext();
        ocr = new Ocr(ctx);
        hilo.start();
        hiloOcr.start();
        h = new Handler(hilo.getLooper());
        hOcr = new Handler(hiloOcr.getLooper());
    }

    void abrir(Uri uri, String clave, AlAbrir cb) {
        h.post(() -> {
            String error = null;
            boolean pide = false;
            try {
                abrirInterno(uri, clave);
            } catch (SecurityException e) {
                pide = true;
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String err = error;
            boolean p = pide;
            ui.post(() -> cb.listo(err, p));
        });
    }

    /** Error especial: el archivo no es un PDF (el lector lo pasa al visor adecuado). */
    static final String NO_ES_PDF = "no-es-pdf";

    private void abrirInterno(Uri uri, String clave) throws IOException {
        cerrarPdf();
        if (!esPdf(ctx, uri)) throw new IOException(NO_ES_PDF);
        try {
            pfd = descriptor(uri);
            pdf = crear(pfd, clave);
        } catch (IOException e) {
            // Algunos proveedores dan descriptores no buscables: se copia a la caché.
            cerrarPdf();
            temporal = new File(ctx.getCacheDir(), "abierto.pdf");
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temporal)) {
                if (in == null) throw new IOException("No se pudo leer el archivo");
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
            } catch (SecurityException se) {
                throw new IOException("Sin permiso para leer el archivo");
            }
            pfd = ParcelFileDescriptor.open(temporal, ParcelFileDescriptor.MODE_READ_ONLY);
            pdf = crear(pfd, clave);
        }
        paginas = pdf.getPageCount();
        if (paginas == 0) throw new IOException("El PDF no tiene páginas");
        anchos = new float[paginas];
        altos = new float[paginas];
        for (int i = 0; i < paginas; i++) {
            try (PdfRenderer.Page p = pdf.openPage(i)) {
                anchos[i] = Math.max(1, p.getWidth());
                altos[i] = Math.max(1, p.getHeight());
            }
        }
        textoNativo = new Boolean[paginas];
        capas.clear();
        huella = huella(uri);
        dirOcr = new File(ctx.getFilesDir(), "ocr/" + huella);
    }

    /** Un PDF lleva "%PDF" en su primer kilobyte (se toleran bytes basura delante). */
    static boolean esPdf(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return true; // que falle después con su propio mensaje
            byte[] b = new byte[1024];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            for (int i = 0; i + 4 <= n; i++) {
                if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F') return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private ParcelFileDescriptor descriptor(Uri uri) throws IOException {
        try {
            ParcelFileDescriptor d = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (d == null) throw new IOException("No se pudo abrir el archivo");
            return d;
        } catch (SecurityException e) {
            throw new IOException("Sin permiso para leer el archivo. Vuelve a abrirlo desde «Abrir PDF».");
        }
    }

    private static PdfRenderer crear(ParcelFileDescriptor d, String clave) throws IOException {
        if (clave != null && Build.VERSION.SDK_INT >= 35) return Api35.abrir(d, clave);
        return new PdfRenderer(d);
    }

    /**
     * Huella del contenido leída con un descriptor propio: el de PdfRenderer no se toca,
     * porque en Android 15+ el motor puede cerrarlo ("Bad file descriptor").
     * Si no se puede leer, se usa la URI para no impedir abrir el documento.
     */
    private String huella(Uri uri) {
        try {
            if (temporal != null) {
                return calcularHuella(ParcelFileDescriptor.open(temporal, ParcelFileDescriptor.MODE_READ_ONLY));
            }
            ParcelFileDescriptor d = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (d != null) return calcularHuella(d);
        } catch (Exception ignorado) {
            // Se usa la reserva.
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder("u");
            for (byte x : md.digest(uri.toString().getBytes())) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "u" + Integer.toHexString(uri.toString().hashCode());
        }
    }

    /** SHA-1 del tamaño + primeros y últimos 64 KB. Cierra el descriptor recibido. */
    private static String calcularHuella(ParcelFileDescriptor d) throws IOException {
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(d)) {
            FileChannel ch = in.getChannel();
            long tam = ch.size();
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(Long.toString(tam).getBytes());
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            for (long pos : new long[] {0, Math.max(0, tam - buf.capacity())}) {
                buf.clear();
                while (buf.hasRemaining()) {
                    int n = ch.read(buf, pos + buf.position());
                    if (n <= 0) break;
                }
                buf.flip();
                md.update(buf);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // --- Render ---

    void base(int pag, int ancho, Filtro filtro, AlRenderBase cb) {
        h.post(() -> {
            if (pdf == null || !filtro.quiere(pag)) {
                ui.post(() -> cb.listo(pag, null, null, false));
                return;
            }
            Bitmap b = null;
            List<VistaPdf.Enlace> enlaces = null;
            try (PdfRenderer.Page p = pdf.openPage(pag)) {
                float s = ancho / (float) p.getWidth();
                int alto = Math.max(1, Math.round(p.getHeight() * s));
                b = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(s, s);
                p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                if (Build.VERSION.SDK_INT >= 35) enlaces = Api35.enlaces(p);
            } catch (Throwable t) {
                b = null;
            }
            Bitmap res = b;
            List<VistaPdf.Enlace> enl = enlaces;
            ui.post(() -> cb.listo(pag, res, enl, res == null));
        });
    }

    void detalle(int gen, List<VistaPdf.Tesela> teselas, AlRenderDetalle cb) {
        h.post(() -> {
            if (pdf == null) return;
            List<VistaPdf.Tesela> hechas = new ArrayList<>();
            for (VistaPdf.Tesela t : teselas) {
                if (gen != genDetalle) return;
                try (PdfRenderer.Page p = pdf.openPage(t.pag)) {
                    t.b = Bitmap.createBitmap(t.w, t.h, Bitmap.Config.ARGB_8888);
                    t.b.eraseColor(Color.WHITE);
                    p.render(t.b, null, t.m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    hechas.add(t);
                } catch (Throwable ignorado) {
                    // Sin detalle para esta página; se sigue viendo la versión base.
                }
            }
            ui.post(() -> cb.listo(gen, hechas));
        });
    }

    // --- Texto (hilo de render) ---

    private boolean tieneTextoNativo(int pag) {
        if (Build.VERSION.SDK_INT < 35) return false;
        if (textoNativo[pag] == null) {
            try (PdfRenderer.Page p = pdf.openPage(pag)) {
                textoNativo[pag] = Api35.tieneTexto(p);
            } catch (Throwable t) {
                textoNativo[pag] = false;
            }
        }
        return textoNativo[pag];
    }

    private CapaOcr capa(int pag) {
        CapaOcr c = capas.get(pag);
        if (c == null) {
            c = CapaOcr.cargar(new File(dirOcr, pag + ".txt"));
            if (c != null) capas.put(pag, c);
        }
        return c;
    }

    void seleccionar(int gen, int pag, PointF a, PointF b, AlSeleccionar cb) {
        genSeleccion = gen;
        h.post(() -> {
            if (gen != genSeleccion || pdf == null) return;
            Seleccion s = null;
            boolean necesitaOcr = false;
            try {
                if (tieneTextoNativo(pag)) {
                    try (PdfRenderer.Page p = pdf.openPage(pag)) {
                        s = Api35.seleccionar(p, a, b);
                    }
                } else {
                    CapaOcr c = capa(pag);
                    if (c != null) s = c.seleccionar(a, b);
                    else necesitaOcr = true;
                }
            } catch (Throwable ignorado) {
                s = null;
            }
            if (s != null) s.pag = pag;
            Seleccion res = s;
            boolean n = necesitaOcr;
            ui.post(() -> cb.listo(gen, res, n));
        });
    }

    void buscar(String texto, int gen, AlBuscar cb) {
        genBusqueda = gen;
        h.post(() -> {
            if (pdf == null) {
                ui.post(() -> cb.terminado(gen));
                return;
            }
            for (int i = 0; i < paginas; i++) {
                if (gen != genBusqueda) return;
                List<List<RectF>> m = new ArrayList<>();
                try {
                    if (tieneTextoNativo(i)) {
                        try (PdfRenderer.Page p = pdf.openPage(i)) {
                            m = Api35.buscar(p, texto);
                        }
                    } else {
                        CapaOcr c = capa(i);
                        if (c != null) m = c.buscar(texto);
                    }
                } catch (Throwable t) {
                    continue;
                }
                if (!m.isEmpty()) {
                    int pag = i;
                    List<List<RectF>> res = m;
                    ui.post(() -> cb.encontrado(gen, pag, res));
                }
            }
            ui.post(() -> cb.terminado(gen));
        });
    }

    /** ¿Las primeras páginas carecen de texto y aún no se han reconocido? */
    void comprobarEscaneado(AlComprobar cb) {
        h.post(() -> {
            if (pdf == null) return;
            boolean escaneado = true;
            for (int i = 0; i < Math.min(paginas, 3); i++) {
                if ((Build.VERSION.SDK_INT >= 35 && tieneTextoNativo(i)) || capa(i) != null) {
                    escaneado = false;
                    break;
                }
            }
            boolean r = escaneado && Build.VERSION.SDK_INT >= 35;
            ui.post(() -> cb.listo(r));
        });
    }

    /** Texto del OCR guardado de una página (líneas separadas por saltos), o null. Hilo cualquiera. */
    String textoOcr(int pag) {
        File d = dirOcr;
        CapaOcr c = d == null ? null : CapaOcr.cargar(new File(d, pag + ".txt"));
        if (c == null || c.palabras.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (CapaOcr.Palabra p : c.palabras) {
            if (sb.length() > 0) sb.append(p.nuevaLinea ? '\n' : ' ');
            sb.append(p.texto);
        }
        return sb.toString();
    }

    // --- OCR ---

    /** Reconoce las páginas [desde, hasta] que no tengan texto ni OCR previo. */
    void reconocer(int desde, int hasta, AlOcr cb) {
        int gen = ++genOcr;
        h.post(() -> {
            if (pdf == null) return;
            List<Integer> pendientes = new ArrayList<>();
            for (int i = Math.max(0, desde); i <= Math.min(hasta, paginas - 1); i++) {
                if (!tieneTextoNativo(i) && capa(i) == null) pendientes.add(i);
            }
            ui.post(() -> cb.progreso(0, pendientes.size()));
            siguienteOcr(gen, pendientes, 0, cb);
        });
    }

    void cancelarOcr() {
        genOcr++;
        ocr.detener();
    }

    /** Se ejecuta en el hilo de render: dibuja la página y pasa la imagen al hilo de OCR. */
    private void siguienteOcr(int gen, List<Integer> pendientes, int k, AlOcr cb) {
        if (gen != genOcr || pdf == null) return;
        if (k >= pendientes.size()) {
            ui.post(() -> cb.terminado(null));
            return;
        }
        int pag = pendientes.get(k);
        // ~300 ppp, limitado para no agotar la memoria con páginas enormes.
        float escala = Math.min(300f / 72f, 3500f / Math.max(anchos[pag], altos[pag]));
        Bitmap b;
        try (PdfRenderer.Page p = pdf.openPage(pag)) {
            b = Bitmap.createBitmap(Math.round(anchos[pag] * escala), Math.round(altos[pag] * escala),
                    Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(escala, escala);
            p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
        } catch (Throwable t) {
            ui.post(() -> cb.terminado("No se pudo preparar la página " + (pag + 1)));
            return;
        }
        File destino = new File(dirOcr, pag + ".txt");
        hOcr.post(() -> {
            CapaOcr capa;
            try {
                capa = ocr.reconocer(b, escala);
                b.recycle();
                if (gen != genOcr) return;
                dirOcr.mkdirs();
                capa.guardar(destino);
            } catch (IOException e) {
                b.recycle();
                String msg = e.getMessage();
                ui.post(() -> cb.terminado(msg));
                return;
            }
            h.post(() -> {
                capas.put(pag, capa);
                ui.post(() -> cb.progreso(k + 1, pendientes.size()));
                siguienteOcr(gen, pendientes, k + 1, cb);
            });
        });
    }

    // --- Cierre ---

    void cerrar() {
        genDetalle = -1;
        genBusqueda = -1;
        genSeleccion = -1;
        cancelarOcr();
        hOcr.post(() -> {
            ocr.cerrar();
            hiloOcr.quitSafely();
        });
        h.post(() -> {
            cerrarPdf();
            hilo.quitSafely();
        });
    }

    private void cerrarPdf() {
        if (pdf != null) {
            try { pdf.close(); } catch (Exception ignorado) { }
            pdf = null;
        }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignorado) { }
            pfd = null;
        }
        if (temporal != null) {
            temporal.delete();
            temporal = null;
        }
    }
}
