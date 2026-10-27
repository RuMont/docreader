package com.docreader.oficina;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
import android.content.SharedPreferences;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.docreader.Impresion;
import com.docreader.Recientes;

/**
 * Visor sin conexión de documentos de Office (.docx, .xlsx, .pptx), de texto (.txt, .csv),
 * Markdown y código fuente.
 * El documento se convierte a HTML en segundo plano y se muestra en un WebView sin red,
 * sin JavaScript y sin acceso a archivos.
 */
public final class OficinaActivity extends Activity {
    private static final int M_BUSCAR = 1, M_NOCHE = 2, M_COMPARTIR = 3, M_INDICE = 4, M_AJUSTAR = 5, M_PAGINA = 6,
            M_PROTEGER = 7, M_QUITAR_CLAVE = 8, M_IMPRIMIR = 9;
    private static final int GUARDAR_PROTEGIDO = 30, GUARDAR_SIN_CLAVE = 31;

    // Protección con contraseña (solo Office 2007+ y OpenDocument).
    private static final int SIN_PROTECCION = 0, OOXML = 1, OOXML_CIFRADO = 2, ODF = 3, ODF_CIFRADO = 4;
    private int proteccion = SIN_PROTECCION;
    private String claveDoc, claveNueva; // con la que se abrió / la elegida para proteger

    /**
     * Dirección ficticia de la página (el dominio .invalid no existe nunca). Con ella las anclas
     * internas son navegaciones dentro del mismo documento: base + "#id" solo desplaza la vista.
     */
    private static final String BASE = "https://docreader.invalid/doc";
    private static final String HOST_BASE = "docreader.invalid";

    /** Tipos MIME para el selector de documentos (ACTION_OPEN_DOCUMENT). */
    public static final String[] TIPOS_MIME = {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "text/plain",
            "text/csv",
            "text/comma-separated-values",
            "text/tab-separated-values",
            // Markdown y código fuente.
            "text/markdown",
            "text/x-markdown",
            "text/html",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",
            "image/svg+xml",
            "application/json",
            "text/css",
            "application/javascript",
            "text/javascript",
            "application/typescript",
            "text/x-java",
            "text/x-java-source",
            "text/x-kotlin",
            "text/x-python",
            "text/x-c",
            "text/x-csrc",
            "text/x-chdr",
            "text/x-c++src",
            "text/x-c++hdr",
            "text/x-csharp",
            "text/x-go",
            "text/x-rust",
            "text/x-ruby",
            "application/x-ruby",
            "text/x-php",
            "application/x-php",
            "text/x-perl",
            "text/x-lua",
            "application/sql",
            "text/x-sql",
            "application/x-sh",
            "application/x-shellscript",
            "text/x-sh",
            "text/x-shellscript",
            "application/x-yaml",
            "application/yaml",
            "text/yaml",
            "text/x-yaml",
            "application/toml",
            "text/x-toml",
            "text/x-properties",
            "text/x-diff",
            "text/x-patch",
            // Formatos antiguos: no se pueden abrir, pero así el usuario recibe una explicación.
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
    };

    private static final Set<String> EXT_WORD = conjunto("docx", "docm", "dotx", "dotm");
    private static final Set<String> EXT_EXCEL = conjunto("xlsx", "xlsm", "xltx", "xltm");
    private static final Set<String> EXT_POWERPOINT = conjunto("pptx", "pptm", "ppsx", "ppsm", "potx", "potm");
    private static final Set<String> EXT_TEXTO = conjunto("txt", "text", "log", "out", "nfo");
    private static final Set<String> EXT_CSV = conjunto("csv", "tsv", "tab");
    private static final Set<String> EXT_ANTIGUAS = conjunto("doc", "dot", "xls", "xlt", "ppt", "pps", "pot");

    private Uri uri;
    private String nombre, mime;
    private WebView web;
    private LinearLayout barraBusqueda;
    private TextView cargando, contador;
    private EditText campo;
    private boolean noche;
    private Html.Documento documento;
    private int volverX, volverY;
    /** Posición vertical a recuperar como fracción del documento (tras girar la pantalla); -1 = ninguna. */
    private double volverFraccion = -1;
    private int orientacion; // la última vista, para recargar solo al girar

    /*
     * Presentaciones (pptx/ppt/odp): en vertical, todas las diapositivas seguidas; en horizontal,
     * «modo presentación»: una diapositiva cada vez ajustada a la pantalla. Una página larga a ese
     * tamaño agotaba la memoria de dibujo del WebView (zonas en blanco al desplazarse).
     */
    private Html.Documento original;   // lo que devolvió el conversor
    private boolean enPresentacion;    // "documento" es la vista de una diapositiva cada vez
    private Runnable trasMedir;        // acción pendiente hasta que el WebView tenga su nuevo tamaño
    private LinearLayout raizVista;
    private FrameLayout marcoVista;
    private boolean barrasVisibles;    // en modo presentación: menú y pestañas mostrados con un toque
    private int anchoAjustado, altoAjustado; // tamaño del WebView para el que se ajustó la diapositiva

    /*
     * Búsqueda en modo presentación: abarca todas las diapositivas. Panel a la derecha con el
     * término (toque = cambiarlo), ‹ › para recorrer los resultados y ✕ para quitar la búsqueda.
     */
    private String terminoPres;          // null = sin búsqueda (diapositiva a pantalla completa)
    private int[] cuentasPres;           // resultados estimados por diapositiva
    private int activaPres, totalDiapoPres; // resultado activo y total en la diapositiva mostrada
    private boolean irAlUltimoPres;      // al llegar a la diapositiva, saltar a su último resultado
    private int direccionPres = 1, saltosVacios;
    /**
     * true cuando ya se ha lanzado findAllAsync en la página cargada. Al cargar otra página el
     * WebView avisa de «0 resultados» para reiniciar la búsqueda anterior: ese aviso no es el
     * resultado de la diapositiva nueva y hay que ignorarlo (si no, se marcaban todas como vacías).
     */
    private boolean busquedaLanzada;
    private boolean ceroComprobado; // un «0 resultados» se confirma buscando otra vez antes de creérselo
    private LinearLayout panelBusqueda;
    private TextView terminoVista, contadorPres;
    private Button anteriorPres, siguientePres;
    private final Runnable reajustar = () -> {
        if (this.enPresentacion) montarVistaPresentacion(this.hojaActual);
    };
    /** HTML cargado ahora; se sirve también si una navegación interna llegara a recargar la página. */
    private volatile String paginaActual;
    private long ultimoRescate;

    // Código fuente: ajuste de líneas (se recuerda) y "Ver como página" para .html/.svg.
    private boolean ajustar, comoPagina;

    // Libros de Excel: una hoja visible cada vez, con pestañas abajo.
    private LinearLayout barraHojas, pestanas;
    private HorizontalScrollView desplazador;
    private Button botonHojas;
    private int hojaActual;

    // --- API pública para la pantalla principal ---

    /**
     * true si el visor sabe tratar el archivo por su extensión o tipo MIME. También devuelve
     * true para .doc/.xls/.ppt, que se abren aquí para explicar que hay que guardarlos en el
     * formato moderno.
     */
    public static boolean soporta(String nombre, String mime) {
        String e = extension(nombre);
        if (EXT_WORD.contains(e) || EXT_EXCEL.contains(e) || EXT_POWERPOINT.contains(e)
                || EXT_TEXTO.contains(e) || EXT_CSV.contains(e) || EXT_ANTIGUAS.contains(e)
                || Markdown.EXTENSIONES.contains(e) || Codigo.porArchivo(nombre) != null) {
            return true;
        }
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        for (String t : TIPOS_MIME) if (t.equals(m)) return true;
        return m.startsWith("application/vnd.openxmlformats-officedocument.")
                || m.startsWith("text/") || Markdown.esMime(m) || Codigo.porMime(m) != null;
    }

    // --- Ciclo de vida ---

    @Override protected void onCreate(Bundle b) {
        // La barra superior se superpone al contenido: en modo presentación la diapositiva ocupa toda
        // la pantalla y la barra aparece encima con un toque. Fuera de ese modo se reserva su hueco.
        getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        noche = Recientes.noche(this);
        orientacion = getResources().getConfiguration().orientation;
        ajustar = preferencias().getBoolean("ajustar_lineas", false);
        if (b != null) comoPagina = b.getBoolean("como_pagina", false);
        nombre = nombre(uri);
        try {
            mime = getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            mime = null;
        }
        setTitle(nombre);
        try {
            construirInterfaz();
        } catch (RuntimeException e) {
            // Sin WebView del sistema (desinstalado o actualizándose) no hay nada que hacer.
            error("No se puede mostrar el documento porque el componente WebView del sistema no está disponible.");
            return;
        }
        Object previo = getLastNonConfigurationInstance();
        if (previo instanceof Html.Documento) {
            documento = original = (Html.Documento) previo;
            if (esPresentacion(original) && horizontal()) {
                int diapo = Recientes.pagina(this, uri);
                trasMedir = () -> entrarPresentacion(diapo);
            } else if (documento.numHojas() > 0) {
                montarPestanas();
                hojaActual = Recientes.pagina(this, uri);
                marcarPestana();
            }
            mostrar();
        } else {
            abrir(null);
        }
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return original != null ? original : documento;
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putBoolean("como_pagina", comoPagina);
    }

    private SharedPreferences preferencias() {
        return getSharedPreferences("visor", MODE_PRIVATE);
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void construirInterfaz() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raizVista = raiz;
        raiz.setPadding(0, alturaActionBar(), 0, 0); // hueco de la barra superior superpuesta

        // Barra de búsqueda (oculta hasta pulsar "Buscar").
        barraBusqueda = new LinearLayout(this);
        barraBusqueda.setGravity(Gravity.CENTER_VERTICAL);
        barraBusqueda.setBackgroundColor(colorTema(android.R.attr.colorBackground));
        barraBusqueda.setPadding(dp(8), 0, 0, 0);
        barraBusqueda.setVisibility(View.GONE);
        campo = new EditText(this);
        campo.setHint("Buscar en el documento");
        campo.setSingleLine(true);
        campo.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        campo.setOnEditorActionListener((v, accion, ev) -> {
            buscar(campo.getText().toString());
            return true;
        });
        barraBusqueda.addView(campo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        contador = new TextView(this);
        contador.setPadding(dp(8), 0, dp(4), 0);
        barraBusqueda.addView(contador);
        barraBusqueda.addView(botonPlano("▲", v -> saltar(false)));
        barraBusqueda.addView(botonPlano("▼", v -> saltar(true)));
        barraBusqueda.addView(botonPlano("✕", v -> cerrarBusqueda()));
        raiz.addView(barraBusqueda, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout marco = new FrameLayout(this);
        marcoVista = marco;
        web = new WebView(this);
        configurarWeb(web);
        // Cuando el WebView ya tiene su tamaño definitivo (al abrir o tras girar), se monta la vista
        // de presentación, que necesita ese tamaño para ajustar la diapositiva a la pantalla.
        web.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (trasMedir != null && r - l > 0 && b - t > 0) {
                Runnable a = trasMedir;
                trasMedir = null;
                web.post(a);
            } else if (enPresentacion && (r - l != anchoAjustado || b - t != altoAjustado)) {
                // Cambió el tamaño (barras del sistema, recorte de pantalla…): se reajusta la diapositiva.
                web.removeCallbacks(reajustar);
                web.postDelayed(reajustar, 150);
            }
        });
        // En modo presentación, deslizar a los lados cambia de diapositiva.
        android.view.GestureDetector deslizar = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                        if (enPresentacion && !toqueEnEnlace()) alternarBarras();
                        return false;
                    }

                    @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                                     float vx, float vy) {
                        if (!enPresentacion || Math.abs(vx) < 800 || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                        mostrarHoja(hojaActual + (vx < 0 ? 1 : -1));
                        return true;
                    }
                });
        web.setOnTouchListener((v, e) -> {
            deslizar.onTouchEvent(e);
            return false; // el WebView sigue recibiendo el gesto (zoom, desplazamiento…)
        });
        web.setVisibility(View.INVISIBLE);
        marco.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        construirPanelBusqueda(marco);
        cargando = new TextView(this);
        cargando.setText("Abriendo…");
        marco.addView(cargando, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        raiz.addView(marco, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Pestañas de hojas (solo en libros con varias hojas).
        barraHojas = new LinearLayout(this);
        barraHojas.setGravity(Gravity.CENTER_VERTICAL);
        barraHojas.setBackgroundColor(colorTema(android.R.attr.colorBackground));
        barraHojas.setElevation(dp(6));
        barraHojas.setVisibility(View.GONE);
        desplazador = new HorizontalScrollView(this);
        desplazador.setHorizontalScrollBarEnabled(false);
        desplazador.setFillViewport(true);
        pestanas = new LinearLayout(this);
        pestanas.setPadding(dp(4), dp(6), dp(4), dp(6));
        desplazador.addView(pestanas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barraHojas.addView(desplazador, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        botonHojas = botonPlano("☰", v -> selectorHojas());
        botonHojas.setContentDescription("Todas las hojas");
        botonHojas.setVisibility(View.GONE);
        barraHojas.addView(botonHojas);
        // El selector solo aparece si las pestañas no caben en el ancho de la pantalla.
        barraHojas.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> barraHojas.post(() -> {
            int disponible = barraHojas.getWidth() - barraHojas.getPaddingLeft() - barraHojas.getPaddingRight();
            int vis = pestanas.getWidth() > disponible ? View.VISIBLE : View.GONE;
            if (botonHojas.getVisibility() != vis) botonHojas.setVisibility(vis);
        }));
        raiz.addView(barraHojas, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(raiz);
    }

    private void configurarWeb(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(false);
        s.setBlockNetworkLoads(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSafeBrowsingEnabled(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        s.setTextZoom(100);
        w.setBackgroundColor(noche ? 0xFF000000 : 0xFFFFFFFF);
        w.setFindListener((activa, total, terminado) -> {
            if (enPresentacion && terminoPres != null) {
                if (terminado && busquedaLanzada) hallazgoPresentacion(activa, total);
                return;
            }
            if (barraBusqueda.getVisibility() != View.VISIBLE) return;
            if (total == 0) {
                contador.setText(!terminado ? "…"
                        : documento != null && documento.numHojas() > 1 ? "Sin resultados en esta hoja" : "Sin resultados");
            }
            else contador.setText((activa + 1) + "/" + total + (terminado ? "" : "…"));
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return interceptar(r.getUrl());
            }

            /**
             * Red de seguridad: si una navegación a BASE#ancla no se tratara como interna y
             * recargara la página, se responde con el mismo HTML (sin red) y el navegador salta
             * igualmente al ancla. Cualquier otra petición sigue bloqueada.
             */
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String h = paginaActual;
                if (h != null && r.isForMainFrame() && esPropia(u) && "/doc".equals(u.getPath()) && u.getQuery() == null) {
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream(h.getBytes(StandardCharsets.UTF_8)));
                }
                return null;
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                // Si aun así se perdiera la página (navegación bloqueada), se vuelve a montar.
                long ahora = System.currentTimeMillis();
                if (r.isForMainFrame() && paginaActual != null && ahora - ultimoRescate > 3000) {
                    ultimoRescate = ahora;
                    String h = paginaActual;
                    v.post(() -> {
                        if (web != null) cargar(h);
                    });
                }
            }

            @Override public void onPageFinished(WebView v, String url) {
                v.setVisibility(View.VISIBLE);
                if (enPresentacion && terminoPres != null) {
                    busquedaLanzada = true;
                    v.findAllAsync(terminoPres);
                }
                if (volverFraccion >= 0) {
                    double f = volverFraccion;
                    volverFraccion = -1;
                    // Se espera a que el WebView haya calculado la nueva altura del contenido.
                    v.postDelayed(() -> {
                        if (web == null) return;
                        int recorrido = alturaContenido() - web.getHeight();
                        if (recorrido > 0) web.scrollTo(0, (int) Math.round(f * recorrido));
                    }, 250);
                } else if (volverX > 0 || volverY > 0) {
                    int x = volverX, y = volverY;
                    volverX = volverY = 0;
                    v.postDelayed(() -> {
                        if (web != null) web.scrollTo(x, y);
                    }, 150);
                }
            }
        });
    }

    /** Las anclas internas se siguen; los enlaces externos se confirman antes de salir de la app. */
    private boolean interceptar(Uri destino) {
        if (destino == null) return true;
        String s = destino.toString();
        if (s.startsWith("#") || s.startsWith("about:blank#")) return false;
        if (esPropia(destino)) {
            // Ancla del propio documento: desplazamiento dentro de la página, sin recargar.
            if (s.startsWith(BASE + "#")) return false;
            Toast.makeText(this, "Este enlace apunta a otro archivo y no se puede abrir desde el visor",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        String esquema = destino.getScheme() == null ? "" : destino.getScheme().toLowerCase(Locale.ROOT);
        switch (esquema) {
            case "http": case "https": case "mailto": case "tel":
                enlace(destino);
                break;
            default:
                break; // data:, file:, content:, javascript:… no se abren nunca
        }
        return true;
    }

    private static boolean esPropia(Uri u) {
        return u != null && HOST_BASE.equalsIgnoreCase(u.getHost());
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

    // --- Apertura ---

    /** Convierte el documento; "clave" es la contraseña si el usuario ya la escribió. */
    private void abrir(String clave) {
        cargando.setVisibility(View.VISIBLE);
        final String n = nombre, m = mime;
        final boolean oscuro = noche;
        final int ops = opciones();
        new Thread(() -> {
            Html.Documento doc = null;
            String html = null, fallo;
            FalloClave sinClave = null;
            int prot = SIN_PROTECCION;
            try {
                byte[] datos = leer();
                doc = convertir(datos, n, m, clave);
                prot = proteccion(datos);
                html = Html.pagina(doc, oscuro, 0, ops);
                fallo = null;
            } catch (FalloClave e) {
                sinClave = e;
                fallo = e.getMessage();
            } catch (Fallo e) {
                fallo = e.getMessage();
            } catch (FileNotFoundException | SecurityException e) {
                fallo = "No se pudo leer el archivo. Puede que ya no exista o que se haya perdido el permiso para abrirlo.";
            } catch (IOException e) {
                fallo = "No se pudo leer el archivo:\n" + e.getMessage();
            } catch (OutOfMemoryError e) {
                fallo = "El documento es demasiado grande para mostrarlo en este dispositivo.";
            } catch (Exception e) {
                fallo = "No se pudo abrir el documento:\n" + e;
            }
            final Html.Documento d = doc;
            final String h = html, f = fallo;
            final FalloClave fc = sinClave;
            final int pr = prot;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || web == null) return;
                cargando.setVisibility(View.GONE);
                if (fc != null) {
                    pedirClave(fc.incorrecta);
                    return;
                }
                if (f != null) {
                    error(f);
                    return;
                }
                documento = original = d;
                proteccion = pr;
                claveDoc = clave;
                int recordada = Recientes.pagina(this, uri);
                Recientes.registrar(this, uri, nombre, 0);
                if (esPresentacion(d) && horizontal()) {
                    if (web.getWidth() > 0) entrarPresentacion(recordada);
                    else trasMedir = () -> entrarPresentacion(recordada);
                } else if (d.numHojas() > 0) {
                    montarPestanas();
                    mostrarHoja(recordada);
                } else if (oscuro == noche && ops == opciones()) {
                    cargar(h);
                } else {
                    // El modo noche (u otra opción) cambió mientras se convertía: se regenera la página.
                    mostrar();
                }
                invalidateOptionsMenu();
            });
        }, "oficina").start();
    }

    private byte[] leer() throws IOException, Fallo {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new FileNotFoundException();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > Paquete.LIMITE) throw new Fallo(Paquete.MENSAJE_GRANDE);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Elige el conversor mirando primero el contenido real y después la extensión y el MIME. */
    private static Html.Documento convertir(byte[] datos, String nombre, String mime, String clave)
            throws Fallo, IOException {
        String ext = extension(nombre);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (datos.length == 0) throw new Fallo("El archivo está vacío.");

        if (empiezaPor(datos, 0x50, 0x4B, 0x03, 0x04)) {
            Paquete pq = Paquete.leer(new ByteArrayInputStream(datos));
            String principal = pq.principal(null);
            if (principal == null) {
                if (pq.existe("word/document.xml")) principal = "word/document.xml";
                else if (pq.existe("xl/workbook.xml")) principal = "xl/workbook.xml";
                else if (pq.existe("ppt/presentation.xml")) principal = "ppt/presentation.xml";
            }
            if (principal != null) {
                String p = principal.toLowerCase(Locale.ROOT);
                if (p.startsWith("word/") || p.endsWith("document.xml")) return Docx.convertir(pq);
                if (p.startsWith("xl/") || p.endsWith("workbook.xml")) return Xlsx.convertir(pq);
                if (p.startsWith("ppt/") || p.endsWith("presentation.xml")) return Pptx.convertir(pq);
            }
            if (pq.existe("mimetype") || pq.existe("content.xml")) return Odf.convertir(pq, clave); // OpenDocument
            throw new Fallo("El archivo no es un documento de Office ni de OpenDocument compatible.");
        }

        boolean ole = empiezaPor(datos, 0xD0, 0xCF, 0x11, 0xE0);
        boolean moderna = EXT_WORD.contains(ext) || EXT_EXCEL.contains(ext) || EXT_POWERPOINT.contains(ext);
        if (ole) {
            // Office 97–2003 (contenedor Compound File): se elige el conversor por los flujos que trae.
            Cfb cfb = Cfb.leer(datos);
            if (Cripto.esOoxmlCifrado(cfb)) {
                // .docx/.xlsx/.pptx con contraseña: se descifra el zip y se convierte como siempre.
                return convertir(Cripto.descifrarOoxml(cfb, clave), nombre, mime, clave);
            }
            if (cfb.existe("WordDocument")) return Doc.convertir(cfb, clave);
            if (cfb.existe("Workbook") || cfb.existe("Book")) return Xls.convertir(cfb, clave);
            if (cfb.existe("PowerPoint Document") || cfb.existe("PP97_DUALSTORAGE")
                    || cfb.existe("PP40") || cfb.existe("Header")) {
                return Ppt.convertir(cfb, clave);
            }
            throw new Fallo("Este archivo de Office 97–2003 no es un documento de Word, Excel ni PowerPoint "
                    + "compatible (puede ser de otro programa, como Outlook o Visio).");
        }
        boolean binario = !Texto.pareceTexto(datos);
        if (binario && (EXT_ANTIGUAS.contains(ext) || m.equals("application/msword")
                || m.equals("application/vnd.ms-powerpoint")
                || (m.equals("application/vnd.ms-excel") && !EXT_CSV.contains(ext)))) {
            throw new Fallo("El archivo está dañado o no es un documento de Office válido.");
        }
        if (moderna) throw new Fallo("El archivo está dañado o no es un documento de Office válido.");

        boolean texto = Texto.pareceTexto(datos);
        // Primero el nombre y la extensión (muchos gestores dan application/octet-stream al código).
        if (Markdown.EXTENSIONES.contains(ext) && texto) return Markdown.convertir(datos);
        Codigo.Lenguaje len = Codigo.porArchivo(nombre);
        if (len != null && texto) return Codigo.convertir(datos, len);
        if (EXT_CSV.contains(ext) || m.contains("csv") || m.contains("comma-separated")
                || m.contains("tab-separated") || m.equals("application/vnd.ms-excel")) {
            return Texto.csv(datos, nombre);
        }
        if (texto) {
            if (EXT_TEXTO.contains(ext)) return Texto.texto(datos);
            if (Markdown.esMime(m)) return Markdown.convertir(datos);
            len = Codigo.porMime(m);
            if (len == null && datos.length > 2 && datos[0] == '#' && datos[1] == '!') {
                len = Codigo.porShebang(new String(datos, 0, Math.min(datos.length, 256), StandardCharsets.ISO_8859_1));
            }
            if (len != null) return Codigo.convertir(datos, len);
            return Texto.texto(datos);
        }
        throw new Fallo("Este tipo de archivo no es compatible.\n\n"
                + "El visor abre documentos .docx, .xlsx, .pptx, texto, CSV, Markdown y código fuente.");
    }

    private static boolean empiezaPor(byte[] d, int... firma) {
        if (d.length < firma.length) return false;
        for (int i = 0; i < firma.length; i++) if ((d[i] & 0xFF) != firma[i]) return false;
        return true;
    }

    /**
     * Al girar la pantalla la actividad no se recrea (configChanges), pero el WebView puede quedarse
     * con la escala y la altura de la orientación anterior: zonas en blanco o sin poder llegar al
     * final. Se vuelve a montar la página y se recupera la posición como fracción del documento.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration nueva) {
        super.onConfigurationChanged(nueva);
        if (nueva.orientation == orientacion) return;
        orientacion = nueva.orientation;
        if (documento == null || web == null || web.getVisibility() != View.VISIBLE) return;
        int recorrido = alturaContenido() - web.getHeight();
        double f = recorrido > 0 ? Math.min(1, web.getScrollY() / (double) recorrido) : 0;
        if (esPresentacion(original)) {
            int total = contarDiapositivas(original);
            if (horizontal()) {
                // De la lista vertical a una diapositiva: la que estaba a la vista.
                int diapo = (int) Math.min(total - 1, Math.floor(f * total));
                trasMedir = () -> entrarPresentacion(diapo);
            } else {
                // De una diapositiva a la lista: se vuelve a esa misma diapositiva.
                double fr = total > 1 ? hojaActual / (double) (total - 1) : 0;
                trasMedir = () -> salirPresentacion(fr);
            }
            return;
        }
        web.post(() -> {
            if (web == null || documento == null) return;
            volverFraccion = f;
            volverX = volverY = 0;
            cargar(Html.pagina(documento, noche, hojaActual, opciones()));
        });
    }

    // --- Modo presentación (una diapositiva cada vez, en horizontal) ---

    private boolean horizontal() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private static boolean esPresentacion(Html.Documento d) {
        return d != null && d.numHojas() == 0 && ("pptx".equals(d.clase) || "ppt".equals(d.clase) || "odp".equals(d.clase));
    }

    private static final java.util.regex.Pattern INICIO_DIAPO = java.util.regex.Pattern.compile("(?=<div class=num id=d\\d)");

    private static int contarDiapositivas(Html.Documento d) {
        return Math.max(1, INICIO_DIAPO.split(d.cuerpo, -1).length - (d.cuerpo.startsWith("<div class=num") ? 1 : 0));
    }

    /** Entra en modo presentación (pantalla completa) mostrando la diapositiva "diapo". */
    private void entrarPresentacion(int diapo) {
        if (original == null || web == null) return;
        pantallaCompleta(true);
        // El WebView cambia de tamaño al ocultar las barras: se ajusta cuando lo haga (y, por si ya
        // tenía ese tamaño, también tras una pausa breve).
        hojaActual = Math.max(0, diapo);
        enPresentacion = true;
        anchoAjustado = altoAjustado = -1;
        web.removeCallbacks(reajustar);
        web.postDelayed(reajustar, 200);
    }

    /** Monta la vista de una diapositiva cada vez, ajustada al tamaño actual del WebView. */
    private void montarVistaPresentacion(int diapo) {
        if (original == null || web == null || web.getWidth() <= 0) return;
        anchoAjustado = web.getWidth();
        altoAjustado = web.getHeight();
        String[] trozos = INICIO_DIAPO.split(original.cuerpo, -1);
        java.util.List<String> cuerpos = new java.util.ArrayList<>();
        String previo = "";
        for (String t : trozos) {
            if (t.startsWith("<div class=num")) {
                cuerpos.add(previo + t);
                previo = "";
            } else {
                previo += t; // lo que va antes de la primera diapositiva (avisos)
            }
        }
        if (cuerpos.isEmpty()) {
            salirPresentacion(0);
            return;
        }
        String[] nombres = new String[cuerpos.size()];
        for (int i = 0; i < nombres.length; i++) nombres[i] = Integer.toString(i + 1);

        // Escala para que la diapositiva entera quepa en la pantalla (ancho y alto).
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<div class=diapo style=\"height:([\\d.]+)px")
                .matcher(original.cuerpo);
        double alto = m.find() ? Double.parseDouble(m.group(1)) : 540;
        float densidad = getResources().getDisplayMetrics().density;
        double cssAncho = Math.max(1, web.getWidth() / densidad);
        double cssAlto = Math.max(1, web.getHeight() / densidad);
        double f = Math.min(cssAncho / (960 + 24), cssAlto / (alto + 24));
        int anchoVista = (int) Math.ceil(cssAncho / f);
        String css = original.css + ".num{display:none}"
                + "html,body{height:100%}"
                + "body." + original.clase + "{padding:0;margin:0;min-height:100%;display:flex;align-items:center;"
                + "justify-content:center;background:#202020}"
                + ".diapo{margin:0 auto;flex:none;content-visibility:visible}";
        Html.Documento vista = new Html.Documento("", css, "width=" + anchoVista, original.clase,
                nombres, cuerpos.toArray(new String[0]));
        vista.conIndice(original.indice);
        documento = vista;
        enPresentacion = true;
        hojaActual = -1; // fuerza la carga en mostrarHoja
        montarPestanas();
        barraHojas.setVisibility(barrasVisibles ? View.VISIBLE : View.GONE);
        mostrarHoja(Math.max(0, Math.min(diapo, nombres.length - 1)));
    }

    /**
     * Pantalla completa del modo presentación: sin barra superior ni barras del sistema, y la
     * barra de diapositivas pasa a superponerse sobre la diapositiva (abajo) en vez de quitarle sitio.
     */
    private void pantallaCompleta(boolean activar) {
        android.app.ActionBar ab = getActionBar();
        android.view.WindowInsetsController ctl =
                android.os.Build.VERSION.SDK_INT >= 30 ? getWindow().getInsetsController() : null;
        ViewGroup padreBarra = (ViewGroup) barraHojas.getParent();
        if (activar) {
            barrasVisibles = false;
            if (ab != null) ab.hide();
            if (ctl != null) {
                ctl.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ctl.hide(android.view.WindowInsets.Type.systemBars());
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            }
            raizVista.setPadding(0, 0, 0, 0);
            if (padreBarra != marcoVista) {
                padreBarra.removeView(barraHojas);
                marcoVista.addView(barraHojas, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            }
            barraHojas.setVisibility(View.GONE);
        } else {
            if (ab != null) ab.show();
            if (ctl != null) ctl.show(android.view.WindowInsets.Type.systemBars());
            else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            raizVista.setPadding(0, alturaActionBar(), 0, 0);
            if (padreBarra != raizVista) {
                padreBarra.removeView(barraHojas);
                raizVista.addView(barraHojas, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    /** ¿El último toque cayó sobre un enlace? Entonces no se muestra/oculta el menú. */
    private boolean toqueEnEnlace() {
        WebView.HitTestResult r = web.getHitTestResult();
        int t = r == null ? WebView.HitTestResult.UNKNOWN_TYPE : r.getType();
        return t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                || t == WebView.HitTestResult.EMAIL_TYPE || t == WebView.HitTestResult.PHONE_TYPE;
    }

    // --- Búsqueda en modo presentación ---

    private void construirPanelBusqueda(FrameLayout marco) {
        panelBusqueda = new LinearLayout(this);
        panelBusqueda.setOrientation(LinearLayout.VERTICAL);
        panelBusqueda.setGravity(Gravity.CENTER);
        panelBusqueda.setPadding(dp(10), dp(12), dp(10), dp(12));
        panelBusqueda.setBackgroundColor(0xFF2A2A2A);
        panelBusqueda.setVisibility(View.GONE);

        TextView etiqueta = new TextView(this);
        etiqueta.setText("Buscando");
        etiqueta.setTextColor(0xFF9E9E9E);
        etiqueta.setTextSize(12);
        etiqueta.setGravity(Gravity.CENTER);
        panelBusqueda.addView(etiqueta);

        terminoVista = new TextView(this);
        terminoVista.setTextColor(0xFFFFFFFF);
        terminoVista.setTextSize(16);
        terminoVista.setTypeface(null, Typeface.BOLD);
        terminoVista.setGravity(Gravity.CENTER);
        terminoVista.setMaxLines(4);
        terminoVista.setEllipsize(android.text.TextUtils.TruncateAt.END);
        terminoVista.setPadding(dp(4), dp(8), dp(4), dp(8));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0xFF3A3A3A);
        fondo.setCornerRadius(dp(8));
        terminoVista.setBackground(fondo);
        terminoVista.setContentDescription("Cambiar la búsqueda");
        terminoVista.setOnClickListener(v -> dialogoBuscarPresentacion());
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lpT.topMargin = dp(4);
        panelBusqueda.addView(terminoVista, lpT);

        contadorPres = new TextView(this);
        contadorPres.setTextColor(0xFFBDBDBD);
        contadorPres.setTextSize(13);
        contadorPres.setGravity(Gravity.CENTER);
        contadorPres.setPadding(0, dp(8), 0, dp(4));
        panelBusqueda.addView(contadorPres);

        LinearLayout flechas = new LinearLayout(this);
        flechas.setGravity(Gravity.CENTER);
        anteriorPres = botonPanel("‹", "Resultado anterior", v -> moverBusqueda(-1));
        siguientePres = botonPanel("›", "Resultado siguiente", v -> moverBusqueda(1));
        flechas.addView(anteriorPres);
        flechas.addView(siguientePres);
        panelBusqueda.addView(flechas);

        Button quitar = botonPanel("✕", "Quitar la búsqueda", v -> quitarBusquedaPresentacion(true));
        panelBusqueda.addView(quitar);

        marco.addView(panelBusqueda, new FrameLayout.LayoutParams(dp(PANEL_DP),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
    }

    private static final int PANEL_DP = 128;

    private Button botonPanel(String texto, String descripcion, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(texto);
        b.setTextSize(24);
        b.setTextColor(0xFFFFFFFF);
        b.setContentDescription(descripcion);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setOnClickListener(l);
        return b;
    }

    private void dialogoBuscarPresentacion() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("Buscar en la presentación");
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        if (terminoPres != null) {
            e.setText(terminoPres);
            e.selectAll();
        }
        FrameLayout marcoDialogo = new FrameLayout(this);
        marcoDialogo.setPadding(dp(20), dp(8), dp(20), 0);
        marcoDialogo.addView(e);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Buscar")
                .setView(marcoDialogo)
                .setPositiveButton("Buscar", (x, w) -> buscarPresentacion(e.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .create();
        e.setOnEditorActionListener((v, accion, ev) -> {
            d.dismiss();
            buscarPresentacion(e.getText().toString());
            return true;
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
    }

    /** Busca en todas las diapositivas; sin texto equivale a quitar la búsqueda. */
    private void buscarPresentacion(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty() || documento == null || documento.numHojas() == 0) {
            quitarBusquedaPresentacion(true);
            return;
        }
        terminoPres = t;
        ceroComprobado = false;
        cuentasPres = new int[documento.numHojas()];
        String q = plegar(t);
        for (int i = 0; i < cuentasPres.length; i++) cuentasPres[i] = contar(plegar(textoVisible(documento.cuerposHojas[i])), q);
        terminoVista.setText(t);
        mostrarPanel(true);
        int destino = siguienteConResultados(hojaActual - 1, 1);
        if (destino < 0) {
            activaPres = totalDiapoPres = 0;
            web.clearMatches();
            actualizarPanel();
            return;
        }
        direccionPres = 1;
        saltosVacios = 0;
        irADiapoBusqueda(destino, false);
    }

    private void irADiapoBusqueda(int diapo, boolean alUltimo) {
        irAlUltimoPres = alUltimo;
        if (diapo == hojaActual) {
            busquedaLanzada = true;
            web.findAllAsync(terminoPres);
        } else {
            mostrarHoja(diapo); // al terminar de cargar, onPageFinished lanza la búsqueda
        }
    }

    /** Resultado de findAllAsync en la diapositiva actual. */
    private void hallazgoPresentacion(int activa, int total) {
        if (total == 0 && !ceroComprobado) {
            ceroComprobado = true;
            web.findAllAsync(terminoPres);
            return;
        }
        if (total == 0) {
            // La estimación decía que había algo y el WebView no lo encuentra: seguir buscando.
            if (cuentasPres != null && hojaActual >= 0 && hojaActual < cuentasPres.length) cuentasPres[hojaActual] = 0;
            int otra = siguienteConResultados(hojaActual, direccionPres);
            if (otra >= 0 && otra != hojaActual && saltosVacios++ < documento.numHojas()) {
                irADiapoBusqueda(otra, direccionPres < 0);
                return;
            }
            activaPres = totalDiapoPres = 0;
            actualizarPanel();
            return;
        }
        saltosVacios = 0;
        if (cuentasPres != null && hojaActual >= 0 && hojaActual < cuentasPres.length) cuentasPres[hojaActual] = total;
        if (irAlUltimoPres) {
            irAlUltimoPres = false;
            if (total > 1) {
                web.findNext(false); // del primero, hacia atrás, al último
                return;
            }
        }
        activaPres = activa;
        totalDiapoPres = total;
        actualizarPanel();
    }

    private void moverBusqueda(int dir) {
        if (terminoPres == null || totalGlobal() == 0) return;
        direccionPres = dir;
        if (dir > 0 && activaPres < totalDiapoPres - 1) {
            web.findNext(true);
            return;
        }
        if (dir < 0 && activaPres > 0) {
            web.findNext(false);
            return;
        }
        int otra = siguienteConResultados(hojaActual, dir);
        if (otra < 0) return;
        if (otra == hojaActual) web.findNext(dir > 0); // solo hay resultados aquí: da la vuelta
        else irADiapoBusqueda(otra, dir < 0);
    }

    /** Siguiente diapositiva (en la dirección dada, dando la vuelta) con resultados, o -1. */
    private int siguienteConResultados(int desde, int dir) {
        if (cuentasPres == null) return -1;
        int n = cuentasPres.length;
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(desde + dir * k, n);
            if (cuentasPres[i] > 0) return i;
        }
        return -1;
    }

    private int totalGlobal() {
        int t = 0;
        if (cuentasPres != null) for (int c : cuentasPres) t += c;
        return t;
    }

    private void actualizarPanel() {
        int total = totalGlobal();
        if (total == 0) {
            contadorPres.setText("Sin resultados");
        } else {
            int antes = 0;
            for (int i = 0; i < hojaActual && i < cuentasPres.length; i++) antes += cuentasPres[i];
            contadorPres.setText((antes + activaPres + 1) + " / " + total + "\ndiapositiva " + (hojaActual + 1));
        }
        anteriorPres.setEnabled(total > 0);
        siguientePres.setEnabled(total > 0);
    }

    /** Muestra el panel a la derecha y estrecha la diapositiva para dejarle sitio (o al revés). */
    private void mostrarPanel(boolean mostrar) {
        panelBusqueda.setVisibility(mostrar ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) web.getLayoutParams();
        int margen = mostrar ? dp(PANEL_DP) : 0;
        if (lp.rightMargin != margen) {
            lp.rightMargin = margen;
            web.setLayoutParams(lp); // el cambio de tamaño reajusta la diapositiva (ver reajustar)
        }
    }

    private void quitarBusquedaPresentacion(boolean reajustarVista) {
        terminoPres = null;
        cuentasPres = null;
        activaPres = totalDiapoPres = 0;
        if (web != null) web.clearMatches();
        if (panelBusqueda != null) {
            if (reajustarVista) mostrarPanel(false);
            else {
                panelBusqueda.setVisibility(View.GONE);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) web.getLayoutParams();
                lp.rightMargin = 0;
                web.setLayoutParams(lp);
            }
        }
    }

    /** Texto visible de un trozo de HTML (sin etiquetas ni entidades). */
    private static String textoVisible(String html) {
        // Sin la etiqueta oculta «Diapositiva N de M» (en este modo no se ve ni se puede encontrar).
        String t = html.replaceAll("<div class=num[^>]*>[^<]*</div>", " ").replaceAll("<[^>]*>", " ");
        return t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    /** Minúsculas y sin tildes, como la búsqueda del WebView. */
    private static String plegar(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static int contar(String texto, String q) {
        int n = 0;
        for (int i = texto.indexOf(q); i >= 0; i = texto.indexOf(q, i + q.length())) n++;
        return n;
    }

    /** Un toque en modo presentación muestra u oculta el menú superior y las pestañas de diapositivas. */
    private void alternarBarras() {
        barrasVisibles = !barrasVisibles;
        android.app.ActionBar ab = getActionBar();
        if (ab != null) {
            if (barrasVisibles) ab.show(); else ab.hide();
        }
        barraHojas.setVisibility(barrasVisibles && documento != null && documento.numHojas() > 1 ? View.VISIBLE : View.GONE);
        if (barrasVisibles) marcarPestana(); // centra la pestaña de la diapositiva actual
    }

    private int alturaActionBar() {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            return android.util.TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        }
        return dp(56);
    }

    /** Vuelve a la lista vertical de diapositivas, en la fracción "fr" del documento. */
    private void salirPresentacion(double fr) {
        if (original == null || web == null) return;
        documento = original;
        quitarBusquedaPresentacion(false);
        enPresentacion = false;
        web.removeCallbacks(reajustar);
        pantallaCompleta(false);
        hojaActual = 0;
        montarPestanas(); // sin hojas: oculta la barra
        volverX = volverY = 0;
        volverFraccion = fr;
        cargar(Html.pagina(documento, noche, 0, opciones()));
    }

    /** Altura total del contenido en píxeles de pantalla (CSS × escala actual). */
    @SuppressWarnings("deprecation")
    private int alturaContenido() {
        return (int) Math.round(web.getContentHeight() * web.getScale());
    }

    /** Vuelve a montar la página (p. ej. al cambiar el modo noche) conservando la posición. */
    private void mostrar() {
        if (documento == null || web == null) return;
        volverX = web.getScrollX();
        volverY = web.getScrollY();
        cargar(Html.pagina(documento, noche, hojaActual, opciones()));
    }

    private int opciones() {
        return (ajustar ? Html.OP_AJUSTAR : 0) | (comoPagina ? Html.OP_COMO_PAGINA : 0);
    }

    private boolean viendoPagina() {
        return comoPagina && documento != null && documento.paginaWeb != null;
    }

    // --- Hojas de Excel ---

    private void montarPestanas() {
        pestanas.removeAllViews();
        int n = documento.numHojas();
        barraHojas.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        if (n <= 1) return;
        for (int i = 0; i < n; i++) {
            TextView t = new TextView(this);
            t.setText(documento.hojas[i]);
            t.setSingleLine(true);
            t.setMaxWidth(dp(200));
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            t.setMinWidth(dp(56));
            int hoja = i;
            t.setOnClickListener(v -> mostrarHoja(hoja));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), 0, dp(2), 0);
            pestanas.addView(t, lp);
        }
        marcarPestana();
    }

    /** Muestra solo la hoja indicada y la recuerda para la próxima vez. */
    private void mostrarHoja(int i) {
        if (documento == null || documento.numHojas() == 0) return;
        i = Math.max(0, Math.min(i, documento.numHojas() - 1));
        boolean otra = i != hojaActual;
        hojaActual = i;
        Recientes.guardarPagina(this, uri, i);
        marcarPestana();
        if (barraBusqueda.getVisibility() == View.VISIBLE) {
            web.clearMatches();
            contador.setText("");
        }
        if (otra || web.getVisibility() != View.VISIBLE) {
            volverX = volverY = 0;
            cargar(Html.pagina(documento, noche, hojaActual, opciones()));
        }
    }

    private void marcarPestana() {
        int acento = colorTema(android.R.attr.colorAccent);
        for (int k = 0; k < pestanas.getChildCount(); k++) {
            TextView t = (TextView) pestanas.getChildAt(k);
            boolean activa = k == hojaActual;
            t.setTypeface(null, activa ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(activa ? acento : colorTexto());
            t.setAlpha(activa ? 1f : 0.75f);
            if (activa) {
                GradientDrawable fondo = new GradientDrawable();
                fondo.setColor((acento & 0x00FFFFFF) | 0x26000000);
                fondo.setCornerRadius(dp(18));
                t.setBackground(fondo);
                // Centrar la pestaña activa en la barra.
                desplazador.post(() -> desplazador.smoothScrollTo(
                        t.getLeft() - (desplazador.getWidth() - t.getWidth()) / 2, 0));
            } else {
                t.setBackground(null);
            }
        }
    }

    /** Panel inferior con todas las hojas, para libros con más pestañas de las que caben. */
    private void selectorHojas() {
        if (documento == null || documento.numHojas() == 0) return;
        ListView lista = new ListView(this);
        lista.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice,
                documento.hojas));
        lista.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lista.setItemChecked(hojaActual, true);
        lista.setSelection(Math.max(0, hojaActual - 2));
        Dialog d = panelInferior((enPresentacion ? "Diapositivas (" : "Hojas (") + documento.numHojas() + ")",
                lista, documento.numHojas());
        lista.setOnItemClickListener((p, v, pos, id) -> {
            d.dismiss();
            mostrarHoja(pos);
        });
    }

    /** Panel inferior con los títulos del documento; al tocar uno se salta a él. */
    private void selectorIndice() {
        if (documento == null || documento.indice.isEmpty()) return;
        List<Html.Titulo> titulos = documento.indice;
        int min = 6;
        for (Html.Titulo t : titulos) min = Math.min(min, t.nivel);
        final int minimo = min;
        ListView lista = new ListView(this);
        lista.setAdapter(new ArrayAdapter<Html.Titulo>(this, android.R.layout.simple_list_item_1, titulos) {
            @Override public View getView(int pos, View reciclada, ViewGroup padre) {
                TextView v = (TextView) super.getView(pos, reciclada, padre);
                Html.Titulo t = getItem(pos);
                int nivel = Math.max(0, Math.min(t.nivel - minimo, 5));
                v.setText(t.texto.isEmpty() ? "(sin título)" : t.texto);
                v.setMaxLines(2);
                v.setEllipsize(android.text.TextUtils.TruncateAt.END);
                v.setTypeface(null, nivel == 0 ? Typeface.BOLD : Typeface.NORMAL);
                v.setTextSize(nivel == 0 ? 16 : 15);
                v.setPaddingRelative(dp(20 + 18 * nivel), dp(6), dp(20), dp(6));
                return v;
            }
        });
        Dialog d = panelInferior("Índice", lista, titulos.size());
        lista.setOnItemClickListener((p, v, pos, id) -> {
            d.dismiss();
            irA(titulos.get(pos).ancla);
        });
    }

    /**
     * Salta a un título. La página se cargó con la dirección BASE, así que BASE#ancla solo se
     * diferencia en el fragmento: el WebView lo trata como navegación dentro del mismo documento
     * (desplaza la vista, no vuelve a cargar ni pide nada a la red) y funciona sin JavaScript.
     */
    private void irA(String ancla) {
        if (web == null || ancla == null || ancla.isEmpty()) return;
        if (enPresentacion && ancla.matches("d\\d+")) { // diapositiva N: en este modo, otra "hoja"
            mostrarHoja(Integer.parseInt(ancla.substring(1)) - 1);
            return;
        }
        web.loadUrl(BASE + "#" + Uri.encode(ancla));
    }

    /** Hoja inferior con asa, título y una lista, con el estilo común del visor. */
    private Dialog panelInferior(String tituloPanel, ListView lista, int filas) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(colorTema(android.R.attr.colorBackground));
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
        titulo.setText(tituloPanel);
        titulo.setTextSize(18);
        titulo.setTypeface(null, Typeface.BOLD);
        titulo.setPadding(dp(20), dp(4), dp(20), dp(8));
        panel.addView(titulo);

        int alto = Math.min(filas * dp(52),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6f));
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
        return d;
    }

    private void cargar(String html) {
        busquedaLanzada = false; // los avisos de búsqueda hasta el próximo findAllAsync son de la página vieja
        ceroComprobado = false;
        cargando.setVisibility(View.GONE);
        web.setBackgroundColor(noche ? 0xFF000000 : 0xFFFFFFFF);
        paginaActual = html;
        web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
    }

    // --- Proteger / quitar contraseña ---

    /** ¿Admite contraseña este archivo y la tiene ya? (por el contenido, no por el nombre). */
    private static int proteccion(byte[] d) {
        try {
            if (Cfb.es(d)) return Cripto.esOoxmlCifrado(Cfb.leer(d)) ? OOXML_CIFRADO : SIN_PROTECCION;
            if (!empiezaPor(d, 0x50, 0x4B, 0x03, 0x04)) return SIN_PROTECCION;
            String inicio = new String(d, 0, Math.min(d.length, 120), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (inicio.contains("mimetypeapplication/vnd.oasis.opendocument")) {
                return OdfCifrado.estaCifrado(d) ? ODF_CIFRADO : ODF;
            }
            // OOXML: el zip contiene [Content_Types].xml (se busca el nombre en el directorio central).
            byte[] marca = "[Content_Types].xml".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            fuera:
            for (int i = Math.max(0, d.length - 1_000_000); i + marca.length <= d.length; i++) {
                for (int k = 0; k < marca.length; k++) if (d[i + k] != marca[k]) continue fuera;
                return OOXML;
            }
        } catch (Exception ignorado) { }
        return SIN_PROTECCION;
    }

    private Intent crearDocumento(String sufijo) {
        String ext = extension(nombre), base = nombre.replaceAll("\\.[^.]*$", "");
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime != null ? mime : "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, base + sufijo + (ext.isEmpty() ? "" : "." + ext));
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if (resultado != RESULT_OK || datos == null || datos.getData() == null) return;
        if (pedido == GUARDAR_PROTEGIDO || pedido == GUARDAR_SIN_CLAVE) {
            guardarCopia(datos.getData(), pedido == GUARDAR_PROTEGIDO);
        }
    }

    /** Escribe la copia protegida o sin contraseña en segundo plano. */
    private void guardarCopia(Uri destino, boolean proteger) {
        String nueva = claveNueva, actual = claveDoc;
        int tipo = proteccion;
        claveNueva = null;
        AlertDialog progreso = new AlertDialog.Builder(this)
                .setMessage(proteger ? "Protegiendo el documento…" : "Quitando la contraseña…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            try {
                byte[] d = leer(), r;
                if (proteger) r = tipo == OOXML ? Cripto.protegerOoxml(d, nueva) : OdfCifrado.proteger(d, nueva);
                else r = tipo == OOXML_CIFRADO ? Cripto.descifrarOoxml(Cfb.leer(d), actual) : OdfCifrado.quitarClave(d, actual);
                try (java.io.OutputStream out = getContentResolver().openOutputStream(destino, "wt")) {
                    if (out == null) throw new IOException("No se pudo crear el archivo.");
                    out.write(r);
                }
            } catch (Fallo e) {
                error = e.getMessage();
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            if (error != null) {
                try {
                    android.provider.DocumentsContract.deleteDocument(getContentResolver(), destino);
                } catch (Exception ignorado) { }
            }
            String err = error;
            runOnUiThread(() -> {
                progreso.dismiss();
                new AlertDialog.Builder(this)
                        .setMessage(err != null ? "No se pudo guardar la copia:\n" + err
                                : proteger ? "Copia protegida con contraseña (cifrado AES-256). El original no se ha modificado."
                                : "Copia sin contraseña guardada.")
                        .setPositiveButton("Aceptar", null)
                        .show();
            });
        }, "proteger").start();
    }

    /** Pide la contraseña del documento y vuelve a convertirlo. No se guarda en ningún sitio. */
    private void pedirClave(boolean incorrecta) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSingleLine(true);
        FrameLayout marco = new FrameLayout(this);
        marco.setPadding(dp(20), dp(8), dp(20), 0);
        marco.addView(e);
        android.app.AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(incorrecta ? "Contraseña incorrecta" : "Documento protegido")
                .setMessage("Escribe la contraseña de «" + nombre + "».")
                .setView(marco)
                .setPositiveButton("Abrir", (x, w) -> abrir(e.getText().toString()))
                .setNegativeButton("Cancelar", (x, w) -> finish())
                .setOnCancelListener(x -> finish())
                .create();
        e.setOnEditorActionListener((v, accion, ev) -> {
            d.dismiss();
            abrir(e.getText().toString());
            return true;
        });
        d.show();
        e.requestFocus();
    }

    private void error(String mensaje) {
        new AlertDialog.Builder(this)
                .setMessage(mensaje)
                .setPositiveButton("Aceptar", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // --- Menú ---

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_INDICE, 0, "Índice")
                .setIcon(android.R.drawable.ic_menu_sort_by_size)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_BUSCAR, 0, "Buscar")
                .setIcon(android.R.drawable.ic_menu_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_NOCHE, 0, "Modo noche").setCheckable(true).setChecked(noche);
        menu.add(0, M_AJUSTAR, 0, "Ajustar líneas").setCheckable(true).setChecked(ajustar);
        menu.add(0, M_PAGINA, 0, "Ver como página").setCheckable(true).setChecked(comoPagina);
        menu.add(0, M_PROTEGER, 0, "Proteger con contraseña…");
        menu.add(0, M_QUITAR_CLAVE, 0, "Quitar contraseña…");
        menu.add(0, M_IMPRIMIR, 0, "Imprimir…");
        menu.add(0, M_COMPARTIR, 0, "Compartir");
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        boolean pagina = viendoPagina();
        menu.findItem(M_INDICE).setVisible(documento != null && !documento.indice.isEmpty() && !pagina);
        menu.findItem(M_AJUSTAR).setVisible(documento != null && documento.cssAjustar != null && !pagina)
                .setChecked(ajustar);
        menu.findItem(M_PAGINA).setVisible(documento != null && documento.paginaWeb != null).setChecked(comoPagina);
        menu.findItem(M_NOCHE).setChecked(noche);
        menu.findItem(M_IMPRIMIR).setVisible(original != null || documento != null);
        menu.findItem(M_PROTEGER).setVisible(proteccion == OOXML || proteccion == ODF);
        menu.findItem(M_QUITAR_CLAVE).setVisible(proteccion == OOXML_CIFRADO || proteccion == ODF_CIFRADO);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_BUSCAR:
                if (documento == null) return true;
                if (enPresentacion) {
                    dialogoBuscarPresentacion();
                    return true;
                }
                barraBusqueda.setVisibility(View.VISIBLE);
                campo.requestFocus();
                teclado(true);
                return true;
            case M_NOCHE:
                noche = !noche;
                item.setChecked(noche);
                Recientes.setNoche(this, noche);
                mostrar();
                return true;
            case M_INDICE:
                selectorIndice();
                return true;
            case M_AJUSTAR:
                ajustar = !ajustar;
                item.setChecked(ajustar);
                preferencias().edit().putBoolean("ajustar_lineas", ajustar).apply();
                mostrar();
                return true;
            case M_PAGINA:
                comoPagina = !comoPagina;
                item.setChecked(comoPagina);
                if (barraBusqueda.getVisibility() == View.VISIBLE) cerrarBusqueda();
                if (documento != null && web != null) {
                    volverX = volverY = 0;
                    cargar(Html.pagina(documento, noche, hojaActual, opciones()));
                }
                invalidateOptionsMenu();
                return true;
            case M_PROTEGER:
                com.docreader.DialogoProteger.mostrar(this, nombre, false, (c, permisos) -> {
                    claveNueva = c;
                    startActivityForResult(crearDocumento("_protegido"), GUARDAR_PROTEGIDO);
                });
                return true;
            case M_QUITAR_CLAVE:
                new AlertDialog.Builder(this)
                        .setTitle("Quitar contraseña")
                        .setMessage("Se guardará una copia sin contraseña. Cualquiera que tenga la copia podrá abrirla.")
                        .setPositiveButton("Continuar", (x, w) ->
                                startActivityForResult(crearDocumento("_sin_contraseña"), GUARDAR_SIN_CLAVE))
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            case M_IMPRIMIR:
                imprimir();
                return true;
            case M_COMPARTIR:
                Intent i = new Intent(Intent.ACTION_SEND)
                        .setType(mime != null ? mime : "*/*")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(i, "Compartir documento"));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "Ninguna app puede compartir este archivo", Toast.LENGTH_SHORT).show();
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- Impresión ---

    /**
     * Imprime el documento completo tal como es, no como se ve: sin modo noche, con todas las
     * diapositivas aunque se esté en modo presentación y, en libros de varias hojas, preguntando
     * si se imprime solo la hoja actual o todo el libro.
     */
    private void imprimir() {
        Html.Documento d = original != null ? original : documento;
        if (d == null) return;
        int n = d.numHojas();
        if (n <= 1 || viendoPagina()) {
            Impresion.html(this, nombre, htmlImpresion(d, false), BASE);
            return;
        }
        int hoja = Math.max(0, Math.min(hojaActual, n - 1));
        new AlertDialog.Builder(this)
                .setTitle("Imprimir")
                .setItems(new String[] {"Hoja actual («" + d.hojas[hoja] + "»)", "Todo el libro (" + n + " hojas)"},
                        (x, w) -> Impresion.html(this, nombre, htmlImpresion(d, w == 1), BASE))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String htmlImpresion(Html.Documento d, boolean todasLasHojas) {
        // Los textos largos (código, por ejemplo) se ajustan al ancho del papel en vez de cortarse.
        int ops = Html.OP_AJUSTAR | (viendoPagina() ? Html.OP_COMO_PAGINA : 0);
        Html.Documento p;
        if (todasLasHojas) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.numHojas(); i++) {
                sb.append(i == 0 ? "<div>" : "<div class=salto-hoja>")
                        .append("<h2 class=titulo-hoja>").append(Html.esc(d.hojas[i])).append("</h2>")
                        .append(d.cuerposHojas[i]).append("</div>");
            }
            p = new Html.Documento(sb.toString(), d.css + Impresion.CSS, d.viewport, d.clase);
        } else {
            p = new Html.Documento(d.cuerpo, d.css + Impresion.CSS, d.viewport, d.clase, d.hojas, d.cuerposHojas);
        }
        p.cssAjustar = d.cssAjustar;
        p.paginaWeb = d.paginaWeb;
        return Html.pagina(p, false, hojaActual, ops);
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
        if (barraBusqueda != null && barraBusqueda.getVisibility() == View.VISIBLE) cerrarBusqueda();
        else super.onBackPressed();
    }

    // --- Utilidades ---

    private static Set<String> conjunto(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static String extension(String nombre) {
        if (nombre == null) return "";
        int i = nombre.lastIndexOf('.');
        return i >= 0 ? nombre.substring(i + 1).trim().toLowerCase(Locale.ROOT) : "";
    }

    private String nombre(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignorado) { }
        String s = u.getLastPathSegment();
        return s != null ? s : "Documento";
    }

    private Button botonPlano(String texto, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(texto);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        b.setOnClickListener(l);
        return b;
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
            return a.getColor(0, noche ? 0xFFFFFFFF : 0xFF1B1B1B);
        } finally {
            a.recycle();
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
