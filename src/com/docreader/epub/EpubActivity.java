package com.docreader.epub;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.docreader.EliminarActivity;
import com.docreader.Estilo;
import com.docreader.Icono;
import com.docreader.Impresion;
import com.docreader.MenuPanel;
import com.docreader.Recientes;

/**
 * Lector de libros EPUB (2 y 3) sin conexión. Muestra un capítulo cada vez en un WebView sin
 * red, sin JavaScript y sin acceso a archivos; las imágenes, estilos y fuentes del libro se
 * incrustan como URI data:. Recuerda el capítulo y la posición de lectura de cada libro.
 *
 * Recibe el libro como {@code getIntent().getData()} (normalmente una URI content://).
 */
public final class EpubActivity extends Activity {
    private static final int M_OPCIONES = 99;
    private static final int M_INDICE = 1, M_BUSCAR = 2, M_LETRA = 3, M_NOCHE = 4, M_COMPARTIR = 5, M_IMPRIMIR = 6;
    public static final String MIME = "application/epub+zip";

    /** Capítulos preparados que se guardan en memoria, y su tamaño máximo total en caracteres. */
    private static final int MAX_CACHE = 3;
    private static final long MAX_CACHE_PESO = 48L * 1024 * 1024;
    private static final int LETRA_MIN = 60, LETRA_MAX = 250, LETRA_PASO = 10;
    private static final String PREFS = "epub";

    private Uri uri;
    private String nombre;
    private Vista web;
    private FrameLayout marco;
    private LinearLayout barraBusqueda, barraNav;
    private TextView cargando, contador, etiquetaTitulo, etiquetaNumero;
    private EditText campo;
    private Button botonAnterior, botonSiguiente;
    private boolean noche;
    private int letra;

    /** Hilo único para el zip: abrir el libro y preparar capítulos. */
    private ExecutorService hilo;
    private Archivo archivo;
    private Libro libro;
    private Capitulos capitulos;
    /** Caché LRU de capítulos preparados; se usa desde los dos hilos. */
    private final LinkedHashMap<Integer, Capitulos.Pagina> cache = new LinkedHashMap<>(8, 0.75f, true);

    private int actual = -1;
    /** Cada carga lleva un número; los resultados de cargas anteriores se descartan. */
    private volatile int generacion;
    /** Fracción de desplazamiento que hay que recuperar cuando acabe de cargar (-1: ninguna). */
    private float restaurar = -1;
    private boolean esperandoCarga, cargada;
    /** Posiciones previas a seguir un enlace interno: el botón Atrás vuelve a ellas. */
    private final ArrayDeque<float[]> historial = new ArrayDeque<>();

    // --- API pública para la pantalla principal ---

    /** true para archivos .epub o de tipo application/epub+zip. */
    public static boolean soporta(String nombre, String mime) {
        if (nombre != null && nombre.trim().toLowerCase(Locale.ROOT).endsWith(".epub")) return true;
        return mime != null && mime.trim().toLowerCase(Locale.ROOT).startsWith(MIME);
    }

    // --- Ciclo de vida ---

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        noche = Recientes.noche(this);
        letra = prefs().getInt("letra", 100);
        nombre = nombre(uri);
        setTitle(sinExtension(nombre));
        hilo = Executors.newSingleThreadExecutor();
        try {
            construirInterfaz();
        } catch (RuntimeException e) {
            // Sin WebView del sistema (desinstalado o actualizándose) no hay nada que hacer.
            error("No se puede mostrar el libro porque el componente WebView del sistema no está disponible.");
            return;
        }
        abrir();
    }

    @Override protected void onPause() {
        guardarPosicion();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        if (hilo != null) {
            final Archivo a = archivo;
            archivo = null;
            hilo.execute(() -> {
                if (a != null) a.close();
            });
            hilo.shutdown();
        }
        synchronized (cache) {
            cache.clear();
        }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        // El manifiesto declara configChanges: al girar no se recrea la actividad, pero el texto se
        // vuelve a distribuir y hay que recuperar el punto de lectura.
        float f = cargada && web != null ? web.fraccion() : -1;
        super.onConfigurationChanged(c);
        if (f > 0 && web != null) {
            web.postDelayed(() -> {
                if (web != null) web.irAFraccion(f);
            }, 350);
        }
    }

    private void construirInterfaz() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);

        // Barra de búsqueda (oculta hasta pulsar "Buscar").
        barraBusqueda = new LinearLayout(this);
        barraBusqueda.setGravity(Gravity.CENTER_VERTICAL);
        barraBusqueda.setBackground(Estilo.barra(this, false));
        barraBusqueda.setPadding(dp(12), dp(6), dp(4), dp(6));
        barraBusqueda.setVisibility(View.GONE);
        campo = new EditText(this);
        campo.setHint("Buscar en el capítulo");
        Estilo.campo(this, campo);
        campo.setSingleLine(true);
        campo.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        campo.setOnEditorActionListener((v, accion, ev) -> {
            buscar(campo.getText().toString());
            return true;
        });
        barraBusqueda.addView(campo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        contador = new TextView(this);
        contador.setPadding(dp(8), 0, dp(4), 0);
        contador.setMaxWidth(dp(150));
        barraBusqueda.addView(contador);
        barraBusqueda.addView(Estilo.icono(this, Icono.Tipo.ARRIBA, "Anterior", v -> saltar(false)));
        barraBusqueda.addView(Estilo.icono(this, Icono.Tipo.ABAJO, "Siguiente", v -> saltar(true)));
        barraBusqueda.addView(Estilo.icono(this, Icono.Tipo.CERRAR, "Cerrar", v -> cerrarBusqueda()));
        raiz.addView(barraBusqueda, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        marco = new FrameLayout(this);
        web = new Vista(this);
        configurarWeb(web);
        web.alDesplazar = () -> {
            web.removeCallbacks(mostrarProgreso);
            web.postDelayed(mostrarProgreso, 150);
        };
        // Un toque muestra u oculta las barras; deslizar a los lados cambia de capítulo.
        android.view.GestureDetector gestos = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                        if (!toqueEnEnlace() && barraBusqueda.getVisibility() != View.VISIBLE) alternarBarras();
                        return false;
                    }

                    @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                                     float vx, float vy) {
                        if (e1 == null || libro == null) return false;
                        float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                        if (Math.abs(vx) < 700 || Math.abs(dx) < dp(80) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                        // Con zoom o contenido ancho, primero se desplaza el propio capítulo hasta su borde.
                        if (web.canScrollHorizontally(dx < 0 ? 1 : -1)) return false;
                        if (dx < 0) siguiente();
                        else anterior();
                        return true;
                    }
                });
        web.setOnTouchListener((v, e) -> {
            gestos.onTouchEvent(e);
            return false; // el WebView sigue recibiendo el gesto (desplazamiento, zoom, selección…)
        });
        web.setVisibility(View.INVISIBLE);
        marco.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cargando = new TextView(this);
        cargando.setText("Abriendo…");
        marco.addView(cargando, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        raiz.addView(marco, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Barra inferior: anterior, capítulo actual (abre el índice) y siguiente.
        barraNav = new LinearLayout(this);
        barraNav.setGravity(Gravity.CENTER_VERTICAL);
        barraNav.setBackground(Estilo.barra(this, true));
        barraNav.setElevation(dp(6));
        barraNav.setPadding(dp(2), dp(2), dp(2), dp(2));
        barraNav.setVisibility(View.GONE);
        botonAnterior = botonPlano("‹ Anterior", v -> anterior());
        barraNav.addView(botonAnterior);
        LinearLayout centro = new LinearLayout(this);
        centro.setOrientation(LinearLayout.VERTICAL);
        centro.setGravity(Gravity.CENTER);
        centro.setPadding(dp(4), dp(4), dp(4), dp(4));
        centro.setOnClickListener(v -> mostrarIndice());
        centro.setContentDescription("Índice");
        TypedValue fondo = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, fondo, true)) {
            centro.setBackgroundResource(fondo.resourceId);
        }
        etiquetaTitulo = new TextView(this);
        etiquetaTitulo.setSingleLine(true);
        etiquetaTitulo.setEllipsize(TextUtils.TruncateAt.END);
        etiquetaTitulo.setGravity(Gravity.CENTER);
        etiquetaTitulo.setTextSize(14);
        centro.addView(etiquetaTitulo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        etiquetaNumero = new TextView(this);
        etiquetaNumero.setSingleLine(true);
        etiquetaNumero.setGravity(Gravity.CENTER);
        etiquetaNumero.setTextSize(12);
        etiquetaNumero.setAlpha(0.7f);
        centro.addView(etiquetaNumero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barraNav.addView(centro, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        botonSiguiente = botonPlano("Siguiente ›", v -> siguiente());
        barraNav.addView(botonSiguiente);
        raiz.addView(barraNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(raiz);
    }

    private void configurarWeb(Vista w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(false);
        s.setBlockNetworkLoads(true);
        s.setBlockNetworkImage(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSafeBrowsingEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        s.setTextZoom(letra);
        w.setBackgroundColor(colorFondo());
        w.setFindListener((activa, total, terminado) -> {
            if (barraBusqueda.getVisibility() != View.VISIBLE) return;
            if (total == 0) contador.setText(!terminado ? "…" : "Sin resultados en este capítulo");
            else contador.setText((activa + 1) + "/" + total + (terminado ? "" : "…"));
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return interceptar(r.getUrl());
            }

            /** Defensa adicional: ninguna petición sale del WebView (todo va en URI data:). */
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (r.isForMainFrame() || (u != null && "data".equalsIgnoreCase(u.getScheme()))) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (!esperandoCarga) return;
                esperandoCarga = false;
                final int gen = generacion;
                final float f = restaurar;
                restaurar = -1;
                v.postDelayed(() -> {
                    if (web == null || gen != generacion) return;
                    if (f > 0) web.irAFraccion(f);
                    final int y = web.getScrollY();
                    cargando.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    cargada = true;
                    // Las imágenes grandes pueden cambiar la altura un poco después: segundo intento
                    // si el usuario aún no se ha movido.
                    if (f > 0) {
                        web.postDelayed(() -> {
                            if (web != null && gen == generacion && web.getScrollY() == y) web.irAFraccion(f);
                        }, 450);
                    }
                }, 120);
                precargar();
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // El proceso del WebView murió (normalmente por falta de memoria con un capítulo enorme).
                if (v == web) {
                    marco.removeView(web);
                    web.destroy();
                    web = null;
                }
                if (!isFinishing()) {
                    error("El capítulo es demasiado grande o complejo para mostrarlo en este dispositivo.");
                }
                return true;
            }
        });
    }

    // --- Apertura ---

    private void abrir() {
        cargando.setVisibility(View.VISIBLE);
        hilo.execute(() -> {
            Archivo a = null;
            Libro l = null;
            String fallo;
            try {
                a = abrirArchivo();
                l = Libro.abrir(a);
                fallo = null;
            } catch (Fallo e) {
                fallo = e.getMessage();
            } catch (FileNotFoundException | SecurityException e) {
                fallo = "No se pudo leer el archivo. Puede que ya no exista o que se haya perdido el permiso para abrirlo.";
            } catch (IOException e) {
                fallo = "No se pudo leer el archivo:\n" + e.getMessage();
            } catch (OutOfMemoryError e) {
                fallo = "El libro es demasiado grande para abrirlo en este dispositivo.";
            } catch (Exception e) {
                fallo = "No se pudo abrir el libro:\n" + e;
            }
            if (fallo != null && a != null) {
                a.close();
                a = null;
            }
            final Archivo aa = a;
            final Libro ll = l;
            final String f = fallo;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || web == null) {
                    if (aa != null && !hilo.isShutdown()) hilo.execute(aa::close);
                    else if (aa != null) aa.close();
                    return;
                }
                if (f != null) {
                    cargando.setVisibility(View.GONE);
                    error(f);
                    return;
                }
                listo(aa, ll);
            });
        });
    }

    /**
     * Abre el zip. Si el proveedor da un archivo normal se lee directamente (sin copiarlo); si no
     * (flujos, archivos en la nube…), se copia a la caché de la app y se borra al cerrar.
     */
    private Archivo abrirArchivo() throws IOException, Fallo {
        ContentResolver cr = getContentResolver();
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                StructStat st = Os.fstat(pfd.getFileDescriptor());
                if (OsConstants.S_ISREG(st.st_mode)) {
                    if (st.st_size == 0) throw new Fallo("El archivo está vacío.");
                    if (st.st_size > Archivo.LIMITE) throw new Fallo(Archivo.MENSAJE_GRANDE);
                    try {
                        return Archivo.abrir(new File("/proc/self/fd/" + pfd.getFd()), null);
                    } catch (IOException e) {
                        // No se puede reabrir por esa vía: se copia.
                    }
                }
            }
        } catch (ErrnoException | IOException | IllegalArgumentException | UnsupportedOperationException e) {
            // Sin descriptor de archivo: se copia.
        }

        File dir = new File(getCacheDir(), "epub");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No se puede usar la caché de la app.");
        File[] viejos = dir.listFiles();
        if (viejos != null) {
            long limite = System.currentTimeMillis() - 24L * 3600 * 1000;
            for (File v : viejos) if (v.lastModified() < limite) v.delete();
        }
        File f = File.createTempFile("libro", ".epub", dir);
        boolean bien = false;
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new FileNotFoundException();
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > Archivo.LIMITE) throw new Fallo(Archivo.MENSAJE_GRANDE);
                out.write(buf, 0, n);
            }
            if (total == 0) throw new Fallo("El archivo está vacío.");
            bien = true;
        } finally {
            if (!bien) f.delete();
        }
        return Archivo.abrir(f, f);
    }

    private void listo(Archivo a, Libro l) {
        archivo = a;
        libro = l;
        capitulos = new Capitulos(a, l);
        String titulo = l.titulo.isEmpty() ? sinExtension(nombre) : l.titulo;
        setTitle(titulo);
        ActionBar ab = getActionBar();
        if (ab != null && !l.autor.isEmpty()) ab.setSubtitle(l.autor);
        Recientes.registrar(this, uri, titulo, 0);
        calcularPesos();
        SharedPreferences p = prefs();
        String h = l.huella.isEmpty() ? null : "h:" + l.huella;
        int cap = h != null ? p.getInt("c:" + h, -1) : -1;
        float frac = h != null ? p.getFloat("f:" + h, 0) : 0;
        if (cap < 0) {
            cap = p.getInt("c:" + uri, -1);
            frac = p.getFloat("f:" + uri, 0);
        }
        if (cap < 0) {
            cap = Recientes.pagina(this, uri);
            frac = 0;
        }
        if (cap < 0 || cap >= l.total()) {
            cap = l.siguiente(-1);
            frac = 0;
        }
        barraNav.setVisibility(barrasVisibles ? View.VISIBLE : View.GONE);
        irA(Math.max(0, cap), null, frac, false);
        invalidateOptionsMenu();
    }

    // --- Navegación ---

    /**
     * Muestra la sección i. Con fragmento, el WebView se desplaza a ese ancla; si no, a la
     * fracción indicada (0 = principio). Con {@code apilar} se recuerda la posición actual para
     * poder volver con Atrás.
     */
    private void irA(int i, String fragmento, float fraccion, boolean apilar) {
        if (libro == null || web == null) return;
        i = Math.max(0, Math.min(i, libro.total() - 1));
        if (apilar && actual >= 0) {
            historial.push(new float[] {actual, fraccionActual()});
            while (historial.size() > 50) historial.removeLast();
        }
        actual = i;
        restaurar = fragmento == null ? fraccion : -1;
        cargada = false;
        actualizarBarra();
        if (barraBusqueda.getVisibility() == View.VISIBLE) {
            web.clearMatches();
            contador.setText("");
        }
        guardarPosicion();
        final int gen = ++generacion;
        final int cap = i;
        final boolean oscuro = noche;
        boolean enCache;
        synchronized (cache) {
            enCache = cache.containsKey(cap);
        }
        if (!enCache) {
            cargando.setText("Cargando…");
            cargando.setVisibility(View.VISIBLE);
        }
        web.setVisibility(View.INVISIBLE);
        hilo.execute(() -> {
            if (gen != generacion) return;
            Capitulos.Pagina pg = obtener(cap);
            String html;
            try {
                html = pg.html(oscuro);
            } catch (OutOfMemoryError e) {
                synchronized (cache) {
                    cache.clear();
                }
                html = null;
            }
            final String h = html;
            runOnUiThread(() -> {
                if (web == null || gen != generacion) return;
                if (h == null) {
                    cargando.setVisibility(View.GONE);
                    error("El capítulo es demasiado grande para mostrarlo en este dispositivo.");
                    return;
                }
                esperandoCarga = true;
                web.loadDataWithBaseURL(Rutas.url(pg.ruta, fragmento), h, "text/html", "utf-8", null);
            });
        });
    }

    /** Imprime el capítulo actual con los colores claros, aunque se esté leyendo en modo noche. */
    private void imprimirCapitulo() {
        if (libro == null || hilo == null || hilo.isShutdown() || actual < 0) return;
        final int cap = actual;
        Toast.makeText(this, "Preparando la impresión…", Toast.LENGTH_SHORT).show();
        hilo.execute(() -> {
            String html;
            String base;
            try {
                Capitulos.Pagina pg = obtener(cap);
                html = pg.html(false);
                base = Rutas.url(pg.ruta, null);
            } catch (Exception | OutOfMemoryError e) {
                html = null;
                base = null;
            }
            final String h = html, b = base;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (h == null) Toast.makeText(this, "No se pudo preparar el capítulo", Toast.LENGTH_SHORT).show();
                else Impresion.html(this, nombre, h, b);
            });
        });
    }

    /** Capítulo preparado, de la caché o recién hecho (en el hilo del zip). */
    private Capitulos.Pagina obtener(int i) {
        synchronized (cache) {
            Capitulos.Pagina p = cache.get(i);
            if (p != null) return p;
        }
        Capitulos.Pagina p = capitulos.preparar(i);
        synchronized (cache) {
            cache.put(i, p);
            long peso = 0;
            for (Capitulos.Pagina q : cache.values()) peso += q.peso();
            Iterator<Map.Entry<Integer, Capitulos.Pagina>> it = cache.entrySet().iterator();
            while (it.hasNext() && (cache.size() > MAX_CACHE || peso > MAX_CACHE_PESO)) {
                Map.Entry<Integer, Capitulos.Pagina> e = it.next();
                if (e.getKey() == i) continue;
                peso -= e.getValue().peso();
                it.remove();
            }
        }
        return p;
    }

    /** Prepara el capítulo siguiente en segundo plano para que pasar de página sea inmediato. */
    private void precargar() {
        if (libro == null || hilo == null || hilo.isShutdown()) return;
        final int s = libro.siguiente(actual);
        if (s < 0) return;
        synchronized (cache) {
            if (cache.containsKey(s)) return;
        }
        final int gen = generacion;
        hilo.execute(() -> {
            if (gen == generacion) obtener(s);
        });
    }

    // --- Barras (toque) ---

    private boolean barrasVisibles = true;
    /** Posición en pantalla del inicio del capítulo mientras cambian las barras (para que el texto no salte). */
    private int anclaTexto;
    private long finCompensar;
    private final View.OnLayoutChangeListener compensar = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (web == null || System.currentTimeMillis() > finCompensar) return;
        int[] pos = new int[2];
        web.getLocationOnScreen(pos);
        int nuevo = Math.max(0, pos[1] - anclaTexto);
        if (nuevo != web.getScrollY()) web.scrollTo(web.getScrollX(), nuevo);
    };

    /**
     * Muestra u oculta la barra superior, la de capítulos y las del sistema. Al cambiar el alto
     * disponible, se corrige el desplazamiento para que el texto se quede quieto en la pantalla.
     */
    private void alternarBarras() {
        if (libro == null || web == null) return;
        barrasVisibles = !barrasVisibles;
        int[] pos = new int[2];
        web.getLocationOnScreen(pos);
        anclaTexto = pos[1] - web.getScrollY();
        finCompensar = System.currentTimeMillis() + 700; // las barras del sistema se animan un rato
        web.removeOnLayoutChangeListener(compensar);
        web.addOnLayoutChangeListener(compensar);

        android.app.ActionBar ab = getActionBar();
        if (ab != null) {
            if (barrasVisibles) ab.show();
            else ab.hide();
        }
        barraNav.setVisibility(barrasVisibles ? View.VISIBLE : View.GONE);
        android.view.WindowInsetsController ctl =
                android.os.Build.VERSION.SDK_INT >= 30 ? getWindow().getInsetsController() : null;
        if (ctl != null) {
            if (barrasVisibles) {
                ctl.show(android.view.WindowInsets.Type.systemBars());
            } else {
                ctl.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ctl.hide(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(barrasVisibles ? View.SYSTEM_UI_FLAG_VISIBLE
                    : View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    /** ¿El último toque cayó sobre un enlace? Entonces no se muestran ni ocultan las barras. */
    private boolean toqueEnEnlace() {
        WebView.HitTestResult r = web.getHitTestResult();
        int t = r == null ? WebView.HitTestResult.UNKNOWN_TYPE : r.getType();
        return t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                || t == WebView.HitTestResult.EMAIL_TYPE || t == WebView.HitTestResult.PHONE_TYPE;
    }

    private void anterior() {
        if (libro == null) return;
        int a = libro.anterior(actual);
        // Al retroceder se empieza por el final del capítulo anterior, como al pasar páginas.
        if (a >= 0) irA(a, null, 1f, false);
    }

    private void siguiente() {
        if (libro == null) return;
        int s = libro.siguiente(actual);
        if (s >= 0) irA(s, null, 0, false);
    }

    private void actualizarBarra() {
        int ord = libro.ordinal(actual);
        Libro.Entrada e = libro.entradaDe(actual);
        String numero = ord > 0 ? "Capítulo " + ord + " de " + libro.lineales() : "Sección complementaria";
        String titulo = e != null ? e.texto : "";
        if (titulo.isEmpty() && ord == 0) {
            String r = libro.secciones.get(actual).ruta;
            titulo = sinExtension(r.substring(r.lastIndexOf('/') + 1));
        }
        etiquetaTitulo.setText(titulo);
        etiquetaTitulo.setVisibility(titulo.isEmpty() ? View.GONE : View.VISIBLE);
        textoNumero = numero;
        etiquetaNumero.setText(numero);
        mostrarProgreso.run();
        etiquetaNumero.setTextSize(titulo.isEmpty() ? 14 : 12);
        activar(botonAnterior, libro.anterior(actual) >= 0);
        activar(botonSiguiente, libro.siguiente(actual) >= 0);
    }

    private static void activar(Button b, boolean si) {
        b.setEnabled(si);
        b.setAlpha(si ? 1f : 0.35f);
    }

    /** Los enlaces internos cambian de capítulo; los externos se confirman antes de salir de la app. */
    private boolean interceptar(Uri destino) {
        if (destino == null) return true;
        String esquema = destino.getScheme() == null ? "" : destino.getScheme().toLowerCase(Locale.ROOT);
        if (esquema.equals("https") && Rutas.HOST.equalsIgnoreCase(destino.getHost())) {
            if (libro == null) return true;
            String ruta = Rutas.normalizar(destino.getPath());
            int i = libro.buscar(ruta);
            if (i < 0) {
                Toast.makeText(this, "Este enlace apunta a algo que no está en el libro", Toast.LENGTH_SHORT).show();
                return true;
            }
            String f = destino.getFragment();
            irA(i, f == null || f.isEmpty() ? null : f, 0, true);
            return true;
        }
        switch (esquema) {
            case "http": case "https": case "mailto": case "tel":
                enlace(destino);
                break;
            default:
                break; // data:, file:, content:, javascript:… no se abren nunca
        }
        return true;
    }

    private void enlace(Uri destino) {
        new AlertDialog.Builder(this)
                .setTitle("Enlace externo")
                .setMessage(destino.toString())
                .setPositiveButton("Abrir", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, destino));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "Ninguna app puede abrir este enlace", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // --- Posición de lectura ---

    private float fraccionActual() {
        if (web == null) return 0;
        if (!cargada) return restaurar >= 0 ? restaurar : 0;
        return web.fraccion();
    }

    private void guardarPosicion() {
        if (libro == null || actual < 0 || uri == null) return;
        float f = fraccionActual();
        SharedPreferences.Editor e = prefs().edit()
                .putInt("c:" + uri, actual)
                .putFloat("f:" + uri, f);
        // Por la huella del libro: se recupera aunque se abra desde otro sitio (u otra copia).
        if (!libro.huella.isEmpty()) e.putInt("c:h:" + libro.huella, actual).putFloat("f:h:" + libro.huella, f);
        e.apply();
        Recientes.guardarPagina(this, uri, actual);
        Recientes.guardarProgreso(this, uri, porcentaje());
    }

    // --- Progreso ---

    /** Tamaño acumulado de las secciones del libro (las lineales), para calcular el porcentaje. */
    private long[] acumulado;
    private long totalLibro;

    private void calcularPesos() {
        int n = libro.secciones.size();
        acumulado = new long[n];
        long t = 0;
        for (int i = 0; i < n; i++) {
            acumulado[i] = t;
            Libro.Seccion s = libro.secciones.get(i);
            if (!s.lineal) continue;
            long tam = archivo.tamano(s.ruta);
            t += tam > 0 ? tam : 4096;
        }
        totalLibro = Math.max(1, t);
    }

    /** Porcentaje leído del libro entero (por el tamaño de cada capítulo y la posición en el actual). */
    private int porcentaje() {
        if (acumulado == null || actual < 0 || actual >= acumulado.length) return 0;
        long fin = actual + 1 < acumulado.length ? acumulado[actual + 1] : totalLibro;
        double p = (acumulado[actual] + fraccionActual() * (fin - acumulado[actual])) / totalLibro;
        return (int) Math.max(0, Math.min(100, Math.floor(p * 100)));
    }

    private String textoNumero;

    /** Actualiza «Capítulo 5 de 23 · 42 %» (se llama también al desplazarse, con un pequeño retraso). */
    private final Runnable mostrarProgreso = () -> {
        if (etiquetaNumero == null || libro == null || textoNumero == null) return;
        etiquetaNumero.setText(textoNumero + " · " + porcentaje() + " %");
    };

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Vuelve a cargar el capítulo actual en la misma posición (modo noche, por ejemplo). */
    private void recargar() {
        if (libro == null || actual < 0) return;
        irA(actual, null, fraccionActual(), false);
    }

    // --- Índice ---

    /** Panel inferior con el índice del libro, sangrado por niveles y con el capítulo actual resaltado. */
    private void mostrarIndice() {
        if (libro == null) return;
        final List<Libro.Entrada> entradas = new ArrayList<>(libro.indice());
        int marcada;
        if (entradas.isEmpty()) {
            // Libro sin índice: se listan los capítulos del orden de lectura.
            for (int k = 0; k < libro.total(); k++) {
                if (!libro.secciones.get(k).lineal) continue;
                entradas.add(new Libro.Entrada("Capítulo " + libro.ordinal(k), libro.secciones.get(k).ruta, null, 0));
            }
            marcada = Math.max(0, libro.ordinal(actual) - 1);
            if (libro.ordinal(actual) == 0) marcada = -1;
        } else {
            marcada = libro.entradaActual(actual);
        }
        final int resaltada = marcada;

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Estilo.superficie(this));
        float r = dp(20);
        fondo.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(fondo);

        View asa = new View(this);
        GradientDrawable forma = new GradientDrawable();
        forma.setColor(0x66888888);
        forma.setCornerRadius(dp(2));
        asa.setBackground(forma);
        LinearLayout.LayoutParams lpAsa = new LinearLayout.LayoutParams(dp(36), dp(4));
        lpAsa.gravity = Gravity.CENTER_HORIZONTAL;
        lpAsa.bottomMargin = dp(8);
        panel.addView(asa, lpAsa);

        TextView titulo = new TextView(this);
        titulo.setText("Índice");
        titulo.setTextSize(18);
        titulo.setTypeface(null, Typeface.BOLD);
        titulo.setPadding(dp(20), dp(4), dp(20), dp(8));
        panel.addView(titulo);

        final int acento = Estilo.acento(this);
        final int texto = colorTexto();
        ListView lista = new ListView(this);
        lista.setAdapter(new ArrayAdapter<Libro.Entrada>(this, android.R.layout.simple_list_item_1, entradas) {
            @Override public View getView(int pos, View v, ViewGroup padre) {
                TextView t = (TextView) super.getView(pos, v, padre);
                Libro.Entrada e = getItem(pos);
                t.setText(e.texto);
                t.setMaxLines(3);
                t.setEllipsize(TextUtils.TruncateAt.END);
                int sangria = dp(20) + dp(16) * Math.min(e.nivel, 6);
                t.setPadding(sangria, dp(10), dp(20), dp(10));
                t.setMinHeight(dp(44));
                t.setTextSize(e.nivel == 0 ? 16 : 15);
                boolean activa = pos == resaltada;
                t.setTypeface(null, activa ? Typeface.BOLD : Typeface.NORMAL);
                t.setTextColor(activa ? acento : texto);
                t.setAlpha(e.ruta == null ? 0.6f : 1f);
                if (activa) {
                    t.setBackgroundColor(Estilo.acentoSuave(EpubActivity.this));
                } else {
                    t.setBackground(null);
                }
                return t;
            }
        });
        lista.setOnItemClickListener((p, v, pos, id) -> {
            // Un título sin enlace lleva a la primera entrada con enlace que le sigue.
            for (int k = pos; k < entradas.size(); k++) {
                Libro.Entrada e = entradas.get(k);
                if (e.ruta == null) continue;
                int i = libro.buscar(e.ruta);
                if (i < 0) continue;
                d.dismiss();
                guardarPosicion();
                irA(i, e.fragmento, 0, false);
                return;
            }
        });
        if (resaltada >= 0) lista.setSelection(Math.max(0, resaltada - 2));
        int alto = Math.min(Math.max(1, entradas.size()) * dp(56),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.7f));
        panel.addView(lista, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alto));

        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        d.show();
    }

    // --- Tamaño de letra ---

    private void elegirLetra() {
        LinearLayout fila = new LinearLayout(this);
        fila.setGravity(Gravity.CENTER);
        fila.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView valor = new TextView(this);
        valor.setTextSize(18);
        valor.setGravity(Gravity.CENTER);
        valor.setMinWidth(dp(88));
        valor.setText(letra + " %");
        Button menos = new Button(this);
        menos.setText("A−");
        menos.setTextSize(15);
        Button mas = new Button(this);
        mas.setText("A+");
        mas.setTextSize(21);
        menos.setOnClickListener(v -> {
            cambiarLetra(letra - LETRA_PASO);
            valor.setText(letra + " %");
        });
        mas.setOnClickListener(v -> {
            cambiarLetra(letra + LETRA_PASO);
            valor.setText(letra + " %");
        });
        fila.addView(menos);
        fila.addView(valor);
        fila.addView(mas);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Tamaño de letra")
                .setView(fila)
                .setPositiveButton("Aceptar", null)
                .setNeutralButton("Restablecer", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            cambiarLetra(100);
            valor.setText(letra + " %");
        }));
        d.show();
    }

    private void cambiarLetra(int nueva) {
        nueva = Math.max(LETRA_MIN, Math.min(LETRA_MAX, nueva));
        if (nueva == letra || web == null) return;
        float f = cargada ? web.fraccion() : -1;
        letra = nueva;
        prefs().edit().putInt("letra", letra).apply();
        web.getSettings().setTextZoom(letra);
        if (f > 0) {
            final int gen = generacion;
            web.postDelayed(() -> {
                if (web != null && gen == generacion) web.irAFraccion(f);
            }, 250);
        }
    }

    // --- Menú ---

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_BUSCAR, 0, "Buscar")
                .setIcon(new Icono(this, Icono.Tipo.BUSCAR, Estilo.texto(this)))
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
            case M_INDICE:
                mostrarIndice();
                return true;
            case M_BUSCAR:
                if (libro == null) return true;
                barraBusqueda.setVisibility(View.VISIBLE);
                campo.requestFocus();
                teclado(true);
                return true;
            case M_LETRA:
                elegirLetra();
                return true;
            case M_NOCHE:
                noche = !noche;
                Recientes.setNoche(this, noche);
                if (web != null) web.setBackgroundColor(colorFondo());
                recargar();
                return true;
            case M_IMPRIMIR:
                imprimirCapitulo();
                return true;
            case M_COMPARTIR:
                Intent i = new Intent(Intent.ACTION_SEND)
                        .setType(MIME)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(i, "Compartir libro"));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "Ninguna app puede compartir este archivo", Toast.LENGTH_SHORT).show();
                }
                return true;
        }
        return false;
    }

    /** Panel de opciones con submenús (ver MenuPanel). */
    private void panelOpciones() {
        MenuPanel.Nivel n = new MenuPanel.Nivel(nombre != null ? nombre : "Libro")
                .accion(Icono.Tipo.INDICE, "Índice", () -> accion(M_INDICE))
                .accion(Icono.Tipo.BUSCAR, "Buscar en el capítulo", () -> accion(M_BUSCAR))
                .submenu(Icono.Tipo.OJO, "Vista", () -> new MenuPanel.Nivel("Vista")
                        .interruptor(Icono.Tipo.LUNA, "Modo noche", noche, x -> accion(M_NOCHE))
                        .accion(Icono.Tipo.LETRA, "Tamaño de letra…", () -> accion(M_LETRA)))
                .accion(Icono.Tipo.IMPRESORA, "Imprimir capítulo…", () -> accion(M_IMPRIMIR))
                .accion(Icono.Tipo.COMPARTIR, "Compartir", () -> accion(M_COMPARTIR))
                .accion(Icono.Tipo.PAPELERA, EliminarActivity.etiqueta(this, uri), () -> startActivityForResult(
                        EliminarActivity.intent(this, uri, nombre), EliminarActivity.PEDIDO));
        MenuPanel.mostrar(this, n);
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if (pedido == EliminarActivity.PEDIDO && resultado == RESULT_OK) finish(); // el archivo ya no existe
    }

    // --- Búsqueda ---

    private void buscar(String texto) {
        texto = texto.trim();
        if (web == null) return;
        if (texto.isEmpty()) {
            web.clearMatches();
            contador.setText("");
            return;
        }
        teclado(false);
        contador.setText("…");
        web.findAllAsync(texto);
    }

    private void saltar(boolean adelante) {
        if (web != null && campo.getText().toString().trim().length() > 0) web.findNext(adelante);
    }

    private void cerrarBusqueda() {
        if (web != null) web.clearMatches();
        contador.setText("");
        campo.setText("");
        teclado(false);
        barraBusqueda.setVisibility(View.GONE);
    }

    @Override public void onBackPressed() {
        if (barraBusqueda != null && barraBusqueda.getVisibility() == View.VISIBLE) {
            cerrarBusqueda();
        } else if (!historial.isEmpty() && libro != null && web != null) {
            // Vuelve al punto desde el que se siguió un enlace (p. ej. tras leer una nota).
            float[] h = historial.pop();
            irA((int) h[0], null, h[1], false);
        } else {
            super.onBackPressed();
        }
    }

    // --- Utilidades ---

    private void error(String mensaje) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setMessage(mensaje)
                .setPositiveButton("Aceptar", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private int colorFondo() {
        return noche ? 0xFF121212 : 0xFFFFFFFF;
    }

    private String nombre(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignorado) { }
        String s = u.getLastPathSegment();
        return s != null ? s : "Libro";
    }

    private static String sinExtension(String n) {
        if (n == null) return "Libro";
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private Button botonPlano(String texto, View.OnClickListener l) {
        return Estilo.textoBoton(this, texto, l);
    }

    private void teclado(boolean mostrar) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mostrar) imm.showSoftInput(campo, 0);
        else imm.hideSoftInputFromWindow(campo.getWindowToken(), 0);
    }

    private int colorTema(int atributo) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(atributo, tv, true);
        return tv.data;
    }

    /** textColorPrimary es una lista de colores por estado: se lee con obtainStyledAttributes. */
    private int colorTexto() {
        android.content.res.TypedArray a = obtainStyledAttributes(new int[] {android.R.attr.textColorPrimary});
        try {
            return a.getColor(0, 0xFF1B1B1B);
        } finally {
            a.recycle();
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** WebView que sabe en qué fracción del capítulo está el lector. */
    static final class Vista extends WebView {
        Runnable alDesplazar;

        Vista(Context c) {
            super(c);
        }

        @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            if (alDesplazar != null) alDesplazar.run();
        }

        private int recorrido() {
            return computeVerticalScrollRange() - computeVerticalScrollExtent();
        }

        /** 0 = principio del capítulo, 1 = final. */
        float fraccion() {
            int max = recorrido();
            if (max <= 0) return 0;
            return Math.max(0f, Math.min(1f, getScrollY() / (float) max));
        }

        void irAFraccion(float f) {
            int max = recorrido();
            if (max > 0) scrollTo(getScrollX(), Math.round(Math.max(0f, Math.min(1f, f)) * max));
        }
    }
}
