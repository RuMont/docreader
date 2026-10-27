package com.docreader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Impresión con el sistema de Android: la app solo entrega el documento y Android muestra su
 * pantalla de siempre (impresora, copias, páginas, «Guardar como PDF»). No necesita permisos.
 */
public final class Impresion {
    private Impresion() { }

    /** WebView fuera de pantalla que se está preparando para imprimir (hay que mantenerlo vivo). */
    private static WebView preparando;

    /** Nombre del trabajo de impresión: el del documento, sin rutas. */
    private static String trabajo(String nombre) {
        return nombre == null || nombre.isEmpty() ? "DocReader" : nombre;
    }

    private static PrintManager gestor(Activity a) {
        PrintManager pm = a.getSystemService(PrintManager.class);
        if (pm == null) Toast.makeText(a, "Este dispositivo no permite imprimir", Toast.LENGTH_SHORT).show();
        return pm;
    }

    // --- PDF ---

    /** Imprime un PDF tal cual, leído de la URI. */
    public static void pdf(Activity a, String nombre, Uri uri, int paginas) {
        pdf(a, nombre, () -> a.getContentResolver().openInputStream(uri), paginas, null);
    }

    /** Imprime un PDF de un archivo (temporal); el archivo se borra al terminar si "borrar". */
    public static void pdf(Activity a, String nombre, File archivo, int paginas, boolean borrar) {
        pdf(a, nombre, () -> new FileInputStream(archivo), paginas, borrar ? archivo : null);
    }

    private interface Fuente { InputStream abrir() throws IOException; }

    private static void pdf(Activity a, String nombre, Fuente fuente, int paginas, File borrarAlFinal) {
        PrintManager pm = gestor(a);
        if (pm == null) {
            if (borrarAlFinal != null) borrarAlFinal.delete();
            return;
        }
        String t = trabajo(nombre);
        Handler principal = new Handler(Looper.getMainLooper());
        pm.print(t, new PrintDocumentAdapter() {
            @Override public void onLayout(PrintAttributes antes, PrintAttributes ahora, CancellationSignal cancelar,
                                           LayoutResultCallback cb, Bundle extras) {
                if (cancelar.isCanceled()) {
                    cb.onLayoutCancelled();
                    return;
                }
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(t)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(paginas > 0 ? paginas : PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build();
                cb.onLayoutFinished(info, antes == null || !antes.equals(ahora));
            }

            @Override public void onWrite(PageRange[] rangos, ParcelFileDescriptor destino, CancellationSignal cancelar,
                                          WriteResultCallback cb) {
                // El PDF se entrega entero: el sistema de impresión ya selecciona las páginas pedidas.
                new Thread(() -> {
                    String error = null;
                    try (InputStream in = fuente.abrir();
                         OutputStream out = new FileOutputStream(destino.getFileDescriptor())) {
                        if (in == null) throw new IOException("No se pudo leer el documento.");
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0; ) {
                            if (cancelar.isCanceled()) break;
                            out.write(buf, 0, n);
                        }
                    } catch (IOException | SecurityException e) {
                        error = e.getMessage() != null ? e.getMessage() : e.toString();
                    }
                    String err = error;
                    principal.post(() -> {
                        if (cancelar.isCanceled()) cb.onWriteCancelled();
                        else if (err != null) cb.onWriteFailed(err);
                        else cb.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
                    });
                }, "imprimir-pdf").start();
            }

            @Override public void onFinish() {
                if (borrarAlFinal != null) borrarAlFinal.delete();
            }
        }, new PrintAttributes.Builder().build());
    }

    // --- HTML (Office, OpenDocument, EPUB, Markdown, código) ---

    /**
     * Imprime una página HTML con un WebView aparte (fuera de pantalla), para no imprimir lo que
     * se ve: sin modo noche, sin pantalla completa y, si se pide, con todas las hojas. Mismo
     * aislamiento que los visores: sin JavaScript ni red; solo se cargan recursos data:.
     */
    public static void html(Activity a, String nombre, String html, String urlBase) {
        PrintManager pm = gestor(a);
        if (pm == null) return;
        WebView w = new WebView(a);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(false);
        s.setBlockNetworkLoads(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setSafeBrowsingEnabled(false);
        s.setTextZoom(100);
        w.setWebViewClient(new WebViewClient() {
            private boolean lanzado;

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (r.isForMainFrame() || (u != null && "data".equalsIgnoreCase(u.getScheme()))) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (lanzado) return;
                lanzado = true;
                if (a.isFinishing() || a.isDestroyed()) {
                    preparando = null;
                    return;
                }
                String t = trabajo(nombre);
                pm.print(t, v.createPrintDocumentAdapter(t), new PrintAttributes.Builder().build());
                preparando = null; // el adaptador ya guarda su propia referencia
            }
        });
        preparando = w;
        Toast.makeText(a, "Preparando la impresión…", Toast.LENGTH_SHORT).show();
        w.loadDataWithBaseURL(urlBase, html, "text/html", "utf-8", null);
    }

    /**
     * Estilos que se añaden al imprimir documentos convertidos a HTML: fondo blanco, sin cabeceras
     * fijas, y cada diapositiva reducida para caber en el ancho del papel y sin partirse.
     */
    public static final String CSS =
            "@media print{"
            + "html,body{background:#fff!important}"
            + "body{min-height:0!important;padding:0!important}"
            + ".hoja th{position:static!important}"
            + ".num{display:none!important}"
            + ".diapo{zoom:0.72;break-inside:avoid;margin:0 auto 12px!important;box-shadow:none!important}"
            + ".salto-hoja{break-before:page}"
            + "h2.titulo-hoja{font:bold 16px sans-serif;margin:0 0 8px}"
            + "img{break-inside:avoid}"
            + "}";

    // --- Imágenes ---

    /** Imprime una imagen en una página, centrada y ajustada a los márgenes del papel elegido. */
    public static void imagen(Activity a, String nombre, Bitmap b) {
        PrintManager pm = gestor(a);
        if (pm == null || b == null) return;
        String t = trabajo(nombre);
        boolean apaisada = b.getWidth() > b.getHeight();
        PrintAttributes inicial = new PrintAttributes.Builder()
                .setMediaSize(apaisada ? PrintAttributes.MediaSize.ISO_A4.asLandscape() : PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        pm.print(t, new PrintDocumentAdapter() {
            private PrintAttributes atributos;

            @Override public void onLayout(PrintAttributes antes, PrintAttributes ahora, CancellationSignal cancelar,
                                           LayoutResultCallback cb, Bundle extras) {
                if (cancelar.isCanceled()) {
                    cb.onLayoutCancelled();
                    return;
                }
                atributos = ahora;
                cb.onLayoutFinished(new PrintDocumentInfo.Builder(t)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                        .setPageCount(1)
                        .build(), true);
            }

            @Override public void onWrite(PageRange[] rangos, ParcelFileDescriptor destino, CancellationSignal cancelar,
                                          WriteResultCallback cb) {
                PrintedPdfDocument doc = new PrintedPdfDocument(a, atributos);
                try {
                    PdfDocument.Page p = doc.startPage(0);
                    Canvas c = p.getCanvas();
                    RectF zona = new RectF(doc.getPageContentRect()); // la página sin los márgenes
                    float escala = Math.min(zona.width() / b.getWidth(), zona.height() / b.getHeight());
                    float an = b.getWidth() * escala, al = b.getHeight() * escala;
                    RectF dst = new RectF(zona.centerX() - an / 2, zona.centerY() - al / 2,
                            zona.centerX() + an / 2, zona.centerY() + al / 2);
                    c.drawColor(Color.WHITE);
                    c.drawBitmap(b, null, dst, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                    doc.finishPage(p);
                    try (OutputStream out = new FileOutputStream(destino.getFileDescriptor())) {
                        doc.writeTo(out);
                    }
                    cb.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
                } catch (IOException | RuntimeException e) {
                    cb.onWriteFailed(e.getMessage());
                } finally {
                    doc.close();
                }
            }

            @Override public void onFinish() {
                if (!b.isRecycled()) b.recycle();
            }
        }, inicial);
    }
}
