package com.docreader.imagen;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Size;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.docreader.Impresion;
import com.docreader.Estilo;
import com.docreader.Icono;
import com.docreader.MenuPanel;
import com.docreader.Recientes;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Visor de imágenes sin conexión: decodifica con {@link ImageDecoder} (aplica la orientación
 * EXIF), reduce las imágenes enormes y anima GIF/WebP/AVIF animados.
 */
public final class ImagenActivity extends Activity implements VistaImagen.Oyente {
    private static final int M_OPCIONES = 99;
    private static final int M_GIRAR = 1, M_AJUSTAR = 2, M_INFO = 3, M_COMPARTIR = 4, M_ABRIR_CON = 5, M_IMPRIMIR = 6;

    // Límites del mapa de bits decodificado (4 bytes por píxel → ~64 MB).
    private static final int LADO_MAX = 4096;
    private static final long PIXELES_MAX = 64L * 1024 * 1024 / 4;

    public static final String[] TIPOS_MIME = {
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/bmp",
            "image/x-ms-bmp",
            "image/heic",
            "image/heif",
            "image/heic-sequence",
            "image/heif-sequence",
            "image/avif",
            "image/x-icon",
            "image/vnd.microsoft.icon",
            "image/vnd.wap.wbmp",
    };

    private static final Set<String> EXTENSIONES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "dib", "heic", "heif",
            "avif", "ico", "wbmp"));

    /** Imágenes que este visor puede mostrar. El SVG se abre aparte, como texto. */
    public static boolean soporta(String nombre, String mime) {
        if (EXTENSIONES.contains(extension(nombre))) return true;
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        return m.startsWith("image/") && !m.startsWith("image/svg");
    }

    private static String extension(String nombre) {
        if (nombre == null) return "";
        int p = nombre.lastIndexOf('.');
        return p < 0 ? "" : nombre.substring(p + 1).toLowerCase(Locale.ROOT).trim();
    }

    /** Resultado de decodificar (en segundo plano). */
    private static final class Decodificada {
        Drawable imagen;
        int anchoOriginal, altoOriginal, ancho, alto;
        String mime;
        String error;
        boolean reintentar;   // falló la memoria gráfica: probar con memoria normal
    }

    private Uri uri;
    private String nombre, mime;
    private VistaImagen vista;
    private ProgressBar cargando;
    private Decodificada actual;
    private boolean barrasVisibles = true;

    // --- Ciclo de vida ---

    @Override protected void onCreate(Bundle b) {
        getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        nombre = nombre(uri);
        mime = getIntent().getType();
        if (mime == null) {
            try {
                mime = getContentResolver().getType(uri);
            } catch (Exception ignorado) { }
        }
        setTitle(nombre);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);
        vista = new VistaImagen(this);
        vista.setOyente(this);
        raiz.addView(vista);
        cargando = new ProgressBar(this);
        cargando.setIndeterminate(true);
        raiz.addView(cargando, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(raiz);
        cargar();
    }

    @Override protected void onStart() {
        super.onStart();
        if (actual != null && actual.imagen instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) actual.imagen).start();
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (actual != null && actual.imagen instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) actual.imagen).stop();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (vista != null) vista.setImagen(null);
        if (actual != null) {
            liberar(actual.imagen);
            actual = null;
        }
    }

    // --- Decodificación ---

    private void cargar() {
        ContentResolver cr = getContentResolver();
        Uri u = uri;
        new Thread(() -> {
            Decodificada d = decodificar(cr, u, true);
            if (d.imagen == null && d.reintentar) d = decodificar(cr, u, false);
            Decodificada fin = d;
            runOnUiThread(() -> mostrar(fin));
        }, "DecodificarImagen").start();
    }

    private static Decodificada decodificar(ContentResolver cr, Uri u, boolean grafica) {
        Decodificada r = new Decodificada();
        try {
            ImageDecoder.Source origen = ImageDecoder.createSource(cr, u);
            r.imagen = ImageDecoder.decodeDrawable(origen, (dec, info, s) -> {
                Size t = info.getSize();
                r.anchoOriginal = t.getWidth();
                r.altoOriginal = t.getHeight();
                r.mime = info.getMimeType();
                int[] destino = Encuadre.tamanoDecodificado(t.getWidth(), t.getHeight(), LADO_MAX, PIXELES_MAX);
                if (destino[0] != t.getWidth() || destino[1] != t.getHeight()) {
                    dec.setTargetSize(destino[0], destino[1]);
                }
                // Memoria gráfica: no ocupa el montón de Java y se dibuja sin copias.
                dec.setAllocator(grafica ? ImageDecoder.ALLOCATOR_HARDWARE : ImageDecoder.ALLOCATOR_SOFTWARE);
                // Archivo truncado: mostrar lo que se pudo leer.
                dec.setOnPartialImageListener(e -> e.getError() == ImageDecoder.DecodeException.SOURCE_INCOMPLETE);
            });
            r.ancho = VistaImagen.ancho(r.imagen);
            r.alto = VistaImagen.alto(r.imagen);
            if (r.ancho <= 0 || r.alto <= 0) {
                liberar(r.imagen);
                r.imagen = null;
                r.error = "La imagen está vacía.";
            }
        } catch (ImageDecoder.DecodeException e) {
            r.error = e.getError() == ImageDecoder.DecodeException.SOURCE_EXCEPTION
                    ? "No se pudo leer el archivo."
                    : "La imagen está dañada o tiene un formato no compatible.";
        } catch (FileNotFoundException | SecurityException e) {
            r.error = "No se pudo acceder al archivo. Puede que ya no exista o que la app "
                    + "que lo compartió haya retirado el permiso.";
        } catch (OutOfMemoryError e) {
            r.error = "La imagen es demasiado grande para mostrarla.";
            r.reintentar = grafica;
        } catch (IllegalStateException | IllegalArgumentException e) {
            r.error = "No se pudo abrir la imagen.";
            r.reintentar = grafica;
        } catch (Exception e) {
            String m = e.getMessage();
            r.error = "No se pudo abrir la imagen." + (m != null && !m.isEmpty() ? "\n" + m : "");
        }
        return r;
    }

    private void mostrar(Decodificada d) {
        if (isFinishing() || isDestroyed()) {
            liberar(d.imagen);
            return;
        }
        cargando.setVisibility(View.GONE);
        if (d.imagen == null) {
            error(d.error != null ? d.error : "No se pudo abrir la imagen.");
            return;
        }
        actual = d;
        if (d.mime != null && (mime == null || !mime.startsWith("image/"))) mime = d.mime;
        vista.setImagen(d.imagen);
        if (d.imagen instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d.imagen).start();
        Recientes.registrar(this, uri, nombre, 0);
        invalidateOptionsMenu();
    }

    private static void liberar(Drawable d) {
        if (d instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) d).stop();
            d.setCallback(null);
        } else if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
    }

    private void error(String mensaje) {
        new AlertDialog.Builder(this)
                .setMessage(mensaje)
                .setPositiveButton("Aceptar", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // --- VistaImagen.Oyente ---

    @Override public void toque() {
        barrasVisibles = !barrasVisibles;
        ActionBar ab = getActionBar();
        if (ab != null) {
            if (barrasVisibles) ab.show(); else ab.hide();
        }
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            if (barrasVisibles) {
                getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
            } else {
                getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
            }
        }
    }

    // --- Menú ---

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_GIRAR, 0, "Girar")
                .setIcon(new Icono(this, Icono.Tipo.GIRAR, Estilo.texto(this)))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, M_OPCIONES, 0, "Opciones")
                .setIcon(new Icono(this, Icono.Tipo.MAS, Estilo.texto(this)))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == M_OPCIONES) {
            panelOpciones();
            return true;
        }
        return accion(item.getItemId()) || super.onOptionsItemSelected(item);
    }

    /** Ejecuta una opción del menú (desde la barra o desde el panel de opciones). */
    private boolean accion(int id) {
        switch (id) {
            case M_GIRAR:
                vista.girar();
                return true;
            case M_AJUSTAR:
                vista.ajustar();
                return true;
            case M_INFO:
                informacion();
                return true;
            case M_IMPRIMIR:
                imprimir();
                return true;
            case M_COMPARTIR:
                Intent i = new Intent(Intent.ACTION_SEND)
                        .setType(mimeImagen())
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                lanzar(Intent.createChooser(i, "Compartir imagen"));
                return true;
            case M_ABRIR_CON:
                Intent ver = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeImagen())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent selector = Intent.createChooser(ver, "Abrir con");
                selector.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                        new ComponentName[] {new ComponentName(this, ImagenActivity.class)});
                lanzar(selector);
                return true;
        }
        return false;
    }

    /** Panel de opciones (ver MenuPanel). */
    private void panelOpciones() {
        boolean lista = actual != null;
        MenuPanel.Nivel n = new MenuPanel.Nivel(nombre != null ? nombre : "Imagen")
                .accion(Icono.Tipo.AJUSTAR, "Ajustar a la pantalla", () -> accion(M_AJUSTAR)).activo(lista)
                .accion(Icono.Tipo.INFO, "Información", () -> accion(M_INFO)).activo(lista)
                .accion(Icono.Tipo.IMPRESORA, "Imprimir…", () -> accion(M_IMPRIMIR)).activo(lista)
                .accion(Icono.Tipo.COMPARTIR, "Compartir", () -> accion(M_COMPARTIR))
                .accion(Icono.Tipo.ABRIR_CON, "Abrir con…", () -> accion(M_ABRIR_CON));
        MenuPanel.mostrar(this, n);
    }

    /**
     * Imprime la imagen con el giro aplicado. Se decodifica de nuevo en memoria normal (la que se
     * muestra está en memoria gráfica, que no se puede dibujar en el PDF de impresión).
     */
    private void imprimir() {
        ContentResolver cr = getContentResolver();
        Uri u = uri;
        int giro = vista.giro();
        Toast.makeText(this, "Preparando la impresión…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Bitmap b = null;
            try {
                b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, u), (dec, info, s) -> {
                    Size t = info.getSize();
                    int[] destino = Encuadre.tamanoDecodificado(t.getWidth(), t.getHeight(), 4000, 16_000_000);
                    if (destino[0] != t.getWidth() || destino[1] != t.getHeight()) dec.setTargetSize(destino[0], destino[1]);
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    dec.setOnPartialImageListener(e -> true);
                });
                if (giro != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(90 * giro);
                    Bitmap g = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (g != b) b.recycle();
                    b = g;
                }
            } catch (Exception | OutOfMemoryError e) {
                b = null;
            }
            Bitmap fin = b;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fin == null) Toast.makeText(this, "No se pudo preparar la imagen para imprimir", Toast.LENGTH_SHORT).show();
                else Impresion.imagen(this, nombre, fin);
            });
        }, "ImprimirImagen").start();
    }

    private String mimeImagen() {
        return mime != null && mime.startsWith("image/") ? mime : "image/*";
    }

    private void lanzar(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Ninguna app puede abrir esta imagen", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Información ---

    private void informacion() {
        Decodificada d = actual;
        if (d == null) return;
        ContentResolver cr = getContentResolver();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            linea(sb, "Nombre", nombre);
            linea(sb, "Tipo", d.mime != null ? d.mime : mime);
            linea(sb, "Dimensiones", d.anchoOriginal + " × " + d.altoOriginal + " px ("
                    + String.format(Locale.getDefault(), "%.1f",
                            d.anchoOriginal * (double) d.altoOriginal / 1e6) + " MP)");
            if (d.ancho != d.anchoOriginal || d.alto != d.altoOriginal) {
                linea(sb, "Mostrada a", d.ancho + " × " + d.alto + " px");
            }
            if (d.imagen instanceof AnimatedImageDrawable) linea(sb, "Animada", "sí");
            long bytes = tamano(cr);
            if (bytes >= 0) linea(sb, "Tamaño", Formatter.formatFileSize(this, bytes));
            exif(cr, sb);
            String texto = sb.toString().trim();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                        .setTitle("Información")
                        .setMessage(texto)
                        .setPositiveButton("Aceptar", null)
                        .show();
            });
        }, "InfoImagen").start();
    }

    private static void linea(StringBuilder sb, String etiqueta, String valor) {
        if (valor == null || valor.trim().isEmpty()) return;
        sb.append(etiqueta).append(": ").append(valor.trim()).append('\n');
    }

    private long tamano(ContentResolver cr) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignorado) { }
        return -1;
    }

    /** Datos EXIF básicos (fecha, cámara, exposición). La ubicación GPS no se muestra a propósito. */
    private void exif(ContentResolver cr, StringBuilder sb) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return;
            ExifInterface ex = new ExifInterface(in);
            String fecha = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (fecha == null) fecha = ex.getAttribute(ExifInterface.TAG_DATETIME);
            linea(sb, "Fecha", fechaExif(fecha));

            String marca = ex.getAttribute(ExifInterface.TAG_MAKE);
            String modelo = ex.getAttribute(ExifInterface.TAG_MODEL);
            String camara;
            if (marca == null || marca.trim().isEmpty()) camara = modelo;
            else if (modelo == null || modelo.trim().isEmpty()) camara = marca;
            else if (modelo.trim().toLowerCase(Locale.ROOT).startsWith(marca.trim().toLowerCase(Locale.ROOT))) camara = modelo;
            else camara = marca.trim() + " " + modelo.trim();
            linea(sb, "Cámara", camara);

            StringBuilder exp = new StringBuilder();
            double f = ex.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0);
            if (f > 0) exp.append(String.format(Locale.ROOT, "f/%.1f", f));
            double t = ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0);
            if (t > 0) {
                separar(exp);
                if (t >= 1) exp.append(String.format(Locale.ROOT, "%.1f s", t));
                else exp.append("1/").append(Math.round(1 / t)).append(" s");
            }
            int iso = ex.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0);
            if (iso > 0) {
                separar(exp);
                exp.append("ISO ").append(iso);
            }
            double focal = ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0);
            if (focal > 0) {
                separar(exp);
                exp.append(String.format(Locale.ROOT, "%.1f mm", focal));
            }
            linea(sb, "Exposición", exp.toString());
        } catch (Exception | OutOfMemoryError ignorado) {
            // Sin EXIF o formato no compatible con ExifInterface.
        }
    }

    private static void separar(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" · ");
    }

    private static String fechaExif(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            Date d = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(s.trim());
            if (d != null) return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(d);
        } catch (Exception ignorado) { }
        return s.trim();
    }

    // --- Utilidades ---

    private String nombre(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignorado) { }
        String s = u.getLastPathSegment();
        return s != null ? s : "Imagen";
    }
}
