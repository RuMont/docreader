package com.docreader;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.docreader.firma.Apariencia;
import com.docreader.firma.CrearFirmaActivity;
import com.docreader.firma.Firmador;
import com.docreader.firma.Firmas;
import com.docreader.firma.Identidad;
import com.docreader.firma.SelectorFirmas;
import com.docreader.firma.Trazo;
import com.docreader.herramientas.HerramientasPdf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class LectorActivity extends Activity implements VistaPdf.Oyente {
    private static final int M_BUSCAR = 1, M_IR = 2, M_NOCHE = 3, M_COMPARTIR = 4,
            M_ANOTACIONES = 5, M_OCR = 6, M_FIRMAR = 7,
            M_DIVIDIR = 8, M_FUSIONAR = 9, M_IMAGENES = 10, M_DOCX = 11, M_PROTEGER = 12, M_QUITAR_CLAVE = 13;
    private static final int PEDIR_P12 = 20, GUARDAR_FIRMADO = 21, CREAR_FIRMA = 22;
    private static final int S_COPIAR = 1, S_RESALTAR = 2, S_SUBRAYAR = 3, S_NOTA = 4,
            S_COMPARTIR = 5, S_BUSCAR = 6;

    private Uri uri;
    private Motor motor;
    private VistaPdf vista;
    private LinearLayout barraInferior, barraBusqueda, aviso;
    private TextView avisoTexto;
    private Button avisoBoton;
    private Anotaciones anotaciones;
    private ActionMode modo;
    private boolean ocrEnCurso;
    private int ocrTotal;

    // Firma en curso: manuscrita (solo trazo) o con certificado (identidad + trazo opcional).
    private LinearLayout barraFirma;
    private Trazo firmaTrazo;
    private Identidad firmaIdentidad;
    private Firmador.Posicion firmaPosicion;
    private HerramientasPdf herramientas;
    /** Contraseña con la que se abrió el PDF (null si no tiene); la usan firmar y las herramientas. */
    private String clavePdf;
    private SeekBar barra;
    private TextView etiqueta, indicador, cargando, contador;
    private EditText campo;
    private boolean barrasVisibles = true, noche;
    private int total;

    private final List<VistaPdf.Coincidencia> coincidencias = new ArrayList<>();
    private int actual = -1, genBusqueda;
    private final Runnable ocultarIndicador = () -> indicador.animate().alpha(0f).setDuration(300);

    @Override protected void onCreate(Bundle b) {
        getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        noche = Recientes.noche(this);
        construirInterfaz();
        setTitle(nombre(uri));
        motor = new Motor(this);
        abrir(null);
    }

    private void construirInterfaz() {
        int fondo = (colorTema(android.R.attr.colorBackground) & 0x00FFFFFF) | 0xF0000000;
        FrameLayout raiz = new FrameLayout(this);

        vista = new VistaPdf(this);
        vista.setOyente(this);
        vista.setNoche(noche);
        raiz.addView(vista);

        cargando = new TextView(this);
        cargando.setText("Abriendo…");
        raiz.addView(cargando, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Barra de búsqueda (oculta hasta pulsar "Buscar").
        barraBusqueda = new LinearLayout(this);
        barraBusqueda.setGravity(Gravity.CENTER_VERTICAL);
        barraBusqueda.setBackgroundColor(fondo);
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
        barraBusqueda.addView(botonPlano("▲", v -> saltar(-1)));
        barraBusqueda.addView(botonPlano("▼", v -> saltar(1)));
        barraBusqueda.addView(botonPlano("✕", v -> cerrarBusqueda()));
        // Aviso (PDF escaneado, progreso del OCR…).
        aviso = new LinearLayout(this);
        aviso.setGravity(Gravity.CENTER_VERTICAL);
        aviso.setBackgroundColor(fondo);
        aviso.setPadding(dp(16), dp(4), dp(4), dp(4));
        aviso.setVisibility(View.GONE);
        avisoTexto = new TextView(this);
        aviso.addView(avisoTexto, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        avisoBoton = botonPlano("", null);
        aviso.addView(avisoBoton);
        aviso.addView(botonPlano("✕", v -> {
            if (ocrEnCurso) cancelarOcr();
            aviso.setVisibility(View.GONE);
        }));

        LinearLayout arriba = new LinearLayout(this);
        arriba.setOrientation(LinearLayout.VERTICAL);
        arriba.addView(barraBusqueda);
        arriba.addView(aviso);
        FrameLayout.LayoutParams lpArriba = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lpArriba.topMargin = alturaActionBar();
        raiz.addView(arriba, lpArriba);

        // Barra inferior para saltar rápido por el documento.
        barraInferior = new LinearLayout(this);
        barraInferior.setGravity(Gravity.CENTER_VERTICAL);
        barraInferior.setBackgroundColor(fondo);
        barraInferior.setPadding(dp(4), dp(10), dp(16), dp(10));
        barra = new SeekBar(this);
        barra.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean usuario) {
                if (usuario) vista.irA(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        barraInferior.addView(barra, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        etiqueta = new TextView(this);
        etiqueta.setOnClickListener(v -> dialogoIrA());
        barraInferior.addView(etiqueta);
        raiz.addView(barraInferior, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Barra mientras se coloca una firma.
        barraFirma = new LinearLayout(this);
        barraFirma.setOrientation(LinearLayout.VERTICAL);
        barraFirma.setBackgroundColor(fondo);
        barraFirma.setPadding(dp(16), dp(8), dp(8), dp(4));
        barraFirma.setVisibility(View.GONE);
        TextView ayuda = new TextView(this);
        ayuda.setText("Arrastra la firma para moverla y usa el punto azul para cambiar su tamaño.");
        barraFirma.addView(ayuda);
        LinearLayout botones = new LinearLayout(this);
        botones.setGravity(Gravity.END);
        botones.addView(botonPlano("Cancelar", v -> cancelarFirma()));
        botones.addView(botonPlano("Traer aquí", v -> vista.colocarEnPaginaActual()));
        botones.addView(botonPlano("Firmar y guardar", v -> elegirDestinoFirmado()));
        barraFirma.addView(botones);
        raiz.addView(barraFirma, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Número de página flotante cuando las barras están ocultas.
        indicador = new TextView(this);
        indicador.setTextColor(0xFFFFFFFF);
        indicador.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable pastilla = new GradientDrawable();
        pastilla.setColor(0xB0000000);
        pastilla.setCornerRadius(dp(12));
        indicador.setBackground(pastilla);
        indicador.setAlpha(0f);
        FrameLayout.LayoutParams lpInd = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lpInd.setMargins(0, 0, dp(16), dp(24));
        raiz.addView(indicador, lpInd);

        setContentView(raiz);
    }

    private void abrir(String clave) {
        cargando.setVisibility(View.VISIBLE);
        motor.abrir(uri, clave, (error, pideClave) -> {
            if (isFinishing() || isDestroyed()) return;
            cargando.setVisibility(View.GONE);
            if (pideClave) {
                if (Build.VERSION.SDK_INT >= 35) pedirClave(clave != null);
                else error("Este PDF tiene contraseña y tu versión de Android no permite abrirlo.");
                return;
            }
            if (Motor.NO_ES_PDF.equals(error)) {
                // No es un PDF (p. ej. un .md con el tipo mal puesto): se abre en el visor adecuado.
                new Thread(() -> {
                    Intent destino = Tipos.intentPara(this, uri);
                    runOnUiThread(() -> {
                        if (!LectorActivity.class.getName().equals(destino.getComponent().getClassName())) {
                            startActivity(destino);
                        } else {
                            Toast.makeText(this, "El archivo no es un PDF válido", Toast.LENGTH_LONG).show();
                        }
                        finish();
                    });
                }).start();
                return;
            }
            if (error != null) {
                error("No se pudo abrir el PDF:\n" + error);
                return;
            }
            total = motor.paginas;
            clavePdf = clave;
            barra.setMax(total - 1);
            Recientes.registrar(this, uri, getTitle().toString(), total);
            vista.setDocumento(motor, Recientes.pagina(this, uri));
            anotaciones = Anotaciones.cargar(this, motor.huella);
            vista.setAnotaciones(anotaciones.lista);
            invalidateOptionsMenu();
            motor.comprobarEscaneado(escaneado -> {
                if (escaneado && !ocrEnCurso) {
                    mostrarAviso("Este PDF parece escaneado: no tiene texto.", "Reconocer", this::reconocerTodo);
                }
            });
        });
    }

    private void pedirClave(boolean incorrecta) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(incorrecta ? "Contraseña incorrecta" : "PDF protegido")
                .setMessage("Escribe la contraseña del documento")
                .setView(conMargen(e))
                .setPositiveButton("Abrir", (d, w) -> abrir(e.getText().toString()))
                .setNegativeButton("Cancelar", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void error(String mensaje) {
        new AlertDialog.Builder(this)
                .setMessage(mensaje)
                .setPositiveButton("Aceptar", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // --- VistaPdf.Oyente ---

    @Override public void pagina(int p, int t) {
        String texto = (p + 1) + " / " + t;
        etiqueta.setText(texto);
        barra.setProgress(p);
        if (!barrasVisibles) {
            indicador.setText(texto);
            indicador.animate().cancel();
            indicador.setAlpha(1f);
            indicador.removeCallbacks(ocultarIndicador);
            indicador.postDelayed(ocultarIndicador, 1200);
        }
    }

    @Override public void toque() {
        if (vista.colocando()) return; // durante la firma las barras se quedan visibles
        barrasVisibles = !barrasVisibles;
        ActionBar ab = getActionBar();
        if (ab != null) {
            if (barrasVisibles) ab.show(); else ab.hide();
        }
        barraInferior.setVisibility(barrasVisibles ? View.VISIBLE : View.GONE);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            if (barrasVisibles) {
                getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
            } else {
                getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
            }
        }
    }

    @Override public void enlace(Uri destino) {
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

    // --- Menú ---

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        // Hasta que el PDF se abre bien no se ofrece nada específico de PDF (firmar, herramientas…).
        if (total == 0) {
            menu.add(0, M_COMPARTIR, 0, "Compartir");
            return true;
        }
        menu.add(0, M_BUSCAR, 0, "Buscar")
                .setIcon(android.R.drawable.ic_menu_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_IR, 0, "Ir a página…");
        menu.add(0, M_ANOTACIONES, 0, "Anotaciones");
        menu.add(0, M_FIRMAR, 0, "Firmar…");
        android.view.SubMenu h = menu.addSubMenu("Herramientas PDF");
        h.add(0, M_DIVIDIR, 0, "Dividir o extraer páginas…");
        h.add(0, M_FUSIONAR, 0, "Fusionar con otros PDF…");
        h.add(0, M_IMAGENES, 0, "Convertir a imágenes…");
        h.add(0, M_DOCX, 0, "Convertir a Word (.docx)…");
        if (clavePdf == null) h.add(0, M_PROTEGER, 0, "Proteger con contraseña…");
        else h.add(0, M_QUITAR_CLAVE, 0, "Quitar contraseña…");
        menu.add(0, M_OCR, 0, "Reconocer texto (OCR)");
        menu.add(0, M_NOCHE, 0, "Modo noche").setCheckable(true).setChecked(noche);
        menu.add(0, M_COMPARTIR, 0, "Compartir");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_BUSCAR:
                barraBusqueda.setVisibility(View.VISIBLE);
                campo.requestFocus();
                teclado(true);
                return true;
            case M_IR:
                dialogoIrA();
                return true;
            case M_ANOTACIONES:
                listaAnotaciones();
                return true;
            case M_FIRMAR:
                menuFirmar();
                return true;
            case M_DIVIDIR:
                herramientas().dividir();
                return true;
            case M_FUSIONAR:
                herramientas().fusionar();
                return true;
            case M_IMAGENES:
                herramientas().aImagenes();
                return true;
            case M_DOCX:
                herramientas().aDocx();
                return true;
            case M_PROTEGER:
                herramientas().proteger();
                return true;
            case M_QUITAR_CLAVE:
                herramientas().quitarClave();
                return true;
            case M_OCR:
                reconocerTodo();
                return true;
            case M_NOCHE:
                noche = !noche;
                item.setChecked(noche);
                vista.setNoche(noche);
                Recientes.setNoche(this, noche);
                return true;
            case M_COMPARTIR:
                Intent i = new Intent(Intent.ACTION_SEND)
                        .setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Compartir PDF"));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void dialogoIrA() {
        if (total == 0) return;
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 – " + total);
        new AlertDialog.Builder(this)
                .setTitle("Ir a página")
                .setView(conMargen(e))
                .setPositiveButton("Ir", (d, w) -> {
                    try {
                        vista.irA(Integer.parseInt(e.getText().toString().trim()) - 1);
                    } catch (NumberFormatException ignorado) { }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // --- Búsqueda ---

    private void buscar(String texto) {
        texto = texto.trim();
        coincidencias.clear();
        actual = -1;
        vista.setCoincidencias(null, -1);
        if (texto.isEmpty() || total == 0) return;
        teclado(false);
        contador.setText("…");
        int gen = ++genBusqueda;
        motor.buscar(texto, gen, new Motor.AlBuscar() {
            @Override public void encontrado(int g, int pag, List<List<RectF>> m) {
                if (g != genBusqueda) return;
                for (List<RectF> r : m) coincidencias.add(new VistaPdf.Coincidencia(pag, r));
                if (actual < 0) {
                    actual = 0;
                    vista.setCoincidencias(coincidencias, 0);
                } else {
                    vista.invalidate();
                }
                contador.setText((actual + 1) + "/" + coincidencias.size() + "…");
            }
            @Override public void terminado(int g) {
                if (g != genBusqueda) return;
                actualizarContador();
            }
        });
    }

    private void saltar(int d) {
        if (coincidencias.isEmpty()) return;
        actual = (actual + d + coincidencias.size()) % coincidencias.size();
        vista.setCoincidencias(coincidencias, actual);
        actualizarContador();
    }

    private void actualizarContador() {
        contador.setText(coincidencias.isEmpty() ? "Sin resultados"
                : (actual + 1) + "/" + coincidencias.size());
    }

    private void cerrarBusqueda() {
        genBusqueda++;
        motor.genBusqueda = genBusqueda;
        coincidencias.clear();
        actual = -1;
        vista.setCoincidencias(null, -1);
        contador.setText("");
        campo.setText("");
        teclado(false);
        barraBusqueda.setVisibility(View.GONE);
    }

    // --- Selección de texto ---

    @Override public void seleccion(Seleccion s, boolean arrastrando) {
        if (s == null || arrastrando) {
            cerrarModo();
        } else if (modo != null) {
            modo.invalidateContentRect();
        } else {
            modo = vista.startActionMode(new ActionMode.Callback2() {
                @Override public boolean onCreateActionMode(ActionMode m, Menu menu) {
                    menu.add(0, S_COPIAR, 0, "Copiar");
                    menu.add(0, S_RESALTAR, 1, "Resaltar");
                    menu.add(0, S_SUBRAYAR, 2, "Subrayar");
                    menu.add(0, S_NOTA, 3, "Nota");
                    menu.add(0, S_COMPARTIR, 4, "Compartir");
                    menu.add(0, S_BUSCAR, 5, "Buscar");
                    return true;
                }
                @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
                @Override public boolean onActionItemClicked(ActionMode m, MenuItem item) {
                    Seleccion sel = vista.getSeleccion();
                    if (sel != null) accionSeleccion(item.getItemId(), sel);
                    vista.borrarSeleccion();
                    return true;
                }
                @Override public void onDestroyActionMode(ActionMode m) {
                    if (modo != m) return; // cerrado por nosotros
                    modo = null;
                    vista.borrarSeleccion();
                }
                @Override public void onGetContentRect(ActionMode m, View v, Rect out) {
                    vista.rectSeleccion(out);
                }
            }, ActionMode.TYPE_FLOATING);
        }
    }

    @Override public void seleccionMovida() {
        if (modo != null) modo.invalidateContentRect();
    }

    private void cerrarModo() {
        if (modo == null) return;
        ActionMode m = modo;
        modo = null;
        m.finish();
    }

    private void accionSeleccion(int id, Seleccion s) {
        String texto = s.texto.trim();
        switch (id) {
            case S_COPIAR:
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Texto del PDF", texto));
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
                break;
            case S_RESALTAR:
                anotar(s, Anotaciones.RESALTE, "");
                break;
            case S_SUBRAYAR:
                anotar(s, Anotaciones.SUBRAYADO, "");
                break;
            case S_NOTA:
                pedirNota("", nota -> anotar(s, Anotaciones.RESALTE, nota));
                break;
            case S_COMPARTIR:
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartir texto"));
                break;
            case S_BUSCAR:
                barraBusqueda.setVisibility(View.VISIBLE);
                campo.setText(texto);
                buscar(texto);
                break;
        }
    }

    @Override public void necesitaOcr(int pag, PointF punto) {
        if (ocrEnCurso) {
            Toast.makeText(this, "Espera a que termine el reconocimiento de texto", Toast.LENGTH_SHORT).show();
            return;
        }
        ocrEnCurso = true;
        mostrarAviso("Esta página no tiene texto. Reconociéndolo…", null, null);
        motor.reconocer(pag, pag, new Motor.AlOcr() {
            @Override public void progreso(int hechas, int t) { }
            @Override public void terminado(String error) {
                ocrEnCurso = false;
                aviso.setVisibility(View.GONE);
                if (error != null) Toast.makeText(LectorActivity.this, error, Toast.LENGTH_LONG).show();
                else vista.seleccionarEn(pag, punto);
            }
        });
    }

    // --- Anotaciones ---

    interface AlEscribir { void listo(String texto); }

    private void anotar(Seleccion s, int tipo, String nota) {
        if (anotaciones == null) return;
        anotaciones.agregar(s, tipo, tipo == Anotaciones.SUBRAYADO ? Anotaciones.COLORES[3]
                : Anotaciones.COLORES[0], nota);
        vista.invalidate();
    }

    private void pedirNota(String actual, AlEscribir cb) {
        EditText e = new EditText(this);
        e.setText(actual);
        e.setMinLines(2);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this)
                .setTitle("Nota")
                .setView(conMargen(e))
                .setPositiveButton("Guardar", (d, w) -> cb.listo(e.getText().toString().trim()))
                .setNegativeButton("Cancelar", null)
                .show();
        e.requestFocus();
    }

    @Override public void anotacionTocada(Anotaciones.Anotacion a) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(24), dp(8), dp(24), 0);
        TextView citado = new TextView(this);
        citado.setText("«" + a.texto.trim() + "»");
        citado.setTypeface(null, Typeface.ITALIC);
        citado.setMaxLines(6);
        v.addView(citado);
        if (!a.nota.isEmpty()) {
            TextView nota = new TextView(this);
            nota.setText(a.nota);
            nota.setTextSize(16);
            nota.setPadding(0, dp(12), 0, 0);
            v.addView(nota);
        }
        new AlertDialog.Builder(this)
                .setTitle((a.tipo == Anotaciones.SUBRAYADO ? "Subrayado" : "Resaltado")
                        + " · página " + (a.pag + 1))
                .setView(v)
                .setPositiveButton(a.nota.isEmpty() ? "Añadir nota" : "Editar nota",
                        (d, w) -> pedirNota(a.nota, t -> {
                            a.nota = t;
                            anotaciones.guardar();
                            vista.invalidate();
                        }))
                .setNegativeButton("Estilo", (d, w) -> elegirEstilo(a))
                .setNeutralButton("Borrar", (d, w) -> {
                    anotaciones.borrar(a);
                    vista.invalidate();
                })
                .show();
    }

    private void elegirEstilo(Anotaciones.Anotacion a) {
        String[] opciones = new String[Anotaciones.COLORES.length + 1];
        System.arraycopy(Anotaciones.NOMBRES_COLOR, 0, opciones, 0, Anotaciones.COLORES.length);
        opciones[opciones.length - 1] = a.tipo == Anotaciones.SUBRAYADO ? "Cambiar a resaltado" : "Cambiar a subrayado";
        new AlertDialog.Builder(this)
                .setItems(opciones, (d, w) -> {
                    if (w < Anotaciones.COLORES.length) a.color = Anotaciones.COLORES[w];
                    else a.tipo = a.tipo == Anotaciones.SUBRAYADO ? Anotaciones.RESALTE : Anotaciones.SUBRAYADO;
                    anotaciones.guardar();
                    vista.invalidate();
                })
                .show();
    }

    private void listaAnotaciones() {
        if (anotaciones == null || anotaciones.lista.isEmpty()) {
            Toast.makeText(this, "Aún no hay anotaciones. Mantén pulsado un texto para resaltarlo.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        List<Anotaciones.Anotacion> lista = anotaciones.lista;
        ArrayAdapter<Anotaciones.Anotacion> ad = new ArrayAdapter<Anotaciones.Anotacion>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, lista) {
            @Override public View getView(int pos, View v, ViewGroup padre) {
                v = super.getView(pos, v, padre);
                Anotaciones.Anotacion a = getItem(pos);
                TextView t1 = v.findViewById(android.R.id.text1), t2 = v.findViewById(android.R.id.text2);
                t1.setText("Pág. " + (a.pag + 1) + " · " + a.texto.replace('\n', ' ').trim());
                t1.setMaxLines(2);
                t2.setText(a.nota.isEmpty() ? (a.tipo == Anotaciones.SUBRAYADO ? "Subrayado" : "Resaltado")
                        : "Nota: " + a.nota);
                t2.setMaxLines(2);
                return v;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle("Anotaciones (" + lista.size() + ")")
                .setAdapter(ad, (d, w) -> vista.irAAnotacion(lista.get(w)))
                .setPositiveButton("Exportar", (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Anotaciones de " + getTitle())
                                .putExtra(Intent.EXTRA_TEXT, anotaciones.exportar(getTitle().toString())),
                        "Exportar anotaciones")))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // --- OCR ---

    private void reconocerTodo() {
        if (total == 0) return;
        if (ocrEnCurso) {
            Toast.makeText(this, "Ya se está reconociendo el texto", Toast.LENGTH_SHORT).show();
            return;
        }
        ocrEnCurso = true;
        ocrTotal = -1;
        mostrarAviso("Preparando el reconocimiento de texto…", "Cancelar", this::cancelarOcr);
        motor.reconocer(0, total - 1, new Motor.AlOcr() {
            @Override public void progreso(int hechas, int t) {
                ocrTotal = t;
                if (t > 0) avisoTexto.setText("Reconociendo texto: " + hechas + " de " + t + " páginas");
            }
            @Override public void terminado(String error) {
                ocrEnCurso = false;
                if (error != null) {
                    mostrarAviso("Error en el OCR: " + error, null, null);
                } else if (ocrTotal == 0) {
                    aviso.setVisibility(View.GONE);
                    Toast.makeText(LectorActivity.this, "Todas las páginas ya tienen texto",
                            Toast.LENGTH_SHORT).show();
                } else {
                    mostrarAviso("Texto reconocido. Ya puedes buscarlo y seleccionarlo.", null, null);
                    aviso.postDelayed(() -> {
                        if (!ocrEnCurso) aviso.setVisibility(View.GONE);
                    }, 5000);
                }
            }
        });
    }

    private void cancelarOcr() {
        motor.cancelarOcr();
        ocrEnCurso = false;
        aviso.setVisibility(View.GONE);
        Toast.makeText(this, "Reconocimiento detenido. Lo ya reconocido se conserva.", Toast.LENGTH_SHORT).show();
    }

    private void mostrarAviso(String texto, String boton, Runnable accion) {
        avisoTexto.setText(texto);
        avisoBoton.setVisibility(boton == null ? View.GONE : View.VISIBLE);
        avisoBoton.setText(boton);
        avisoBoton.setOnClickListener(v -> {
            if (accion != null) accion.run();
        });
        aviso.setVisibility(View.VISIBLE);
    }

    @Override public void onBackPressed() {
        if (vista.colocando()) cancelarFirma();
        else if (vista.getSeleccion() != null) vista.borrarSeleccion();
        else if (barraBusqueda.getVisibility() == View.VISIBLE) cerrarBusqueda();
        else super.onBackPressed();
    }

    // --- Herramientas PDF ---

    private HerramientasPdf herramientas() {
        if (herramientas == null) {
            herramientas = new HerramientasPdf(this, new HerramientasPdf.Documento() {
                @Override public Uri uri() { return uri; }
                @Override public String nombre() { return getTitle().toString(); }
                @Override public int paginas() { return total; }
                @Override public int paginaActual() { return vista.paginaActual(); }
                @Override public String textoOcr(int pag) { return motor.textoOcr(pag); }
                @Override public String clave() { return clavePdf; }
            });
        }
        return herramientas;
    }

    // --- Firma ---

    private void menuFirmar() {
        if (total == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Firmar documento")
                .setItems(new String[] {"Firma manuscrita (dibujar con el dedo)",
                        "Firma digital con certificado electrónico"}, (d, w) -> {
                    if (w == 0) elegirTrazo();
                    else elegirCertificado();
                })
                .show();
    }

    private void elegirTrazo() {
        SelectorFirmas.mostrar(this, t -> colocar(t, null),
                () -> startActivityForResult(new Intent(this, CrearFirmaActivity.class), CREAR_FIRMA));
    }

    private void elegirCertificado() {
        new AlertDialog.Builder(this)
                .setTitle("Certificado electrónico")
                .setItems(new String[] {"Certificado instalado en el móvil", "Archivo de certificado (.p12 / .pfx)"},
                        (d, w) -> {
                            if (w == 0) {
                                Identidad.delSistema(this, (id, error) -> {
                                    if (error != null) mensaje(error);
                                    else if (id != null) confirmarCertificado(id);
                                });
                            } else {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("*/*")
                                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/x-pkcs12",
                                                "application/pkcs12", "application/x-pkcs-12", "application/octet-stream"});
                                startActivityForResult(i, PEDIR_P12);
                            }
                        })
                .show();
    }

    /** Pide la contraseña del .p12 y lo abre en segundo plano. */
    private void abrirP12(Uri p12) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Contraseña del certificado")
                .setView(conMargen(e))
                .setPositiveButton("Abrir", (d, w) -> {
                    char[] clave = e.getText().toString().toCharArray();
                    new Thread(() -> {
                        Identidad id = null;
                        String error = null;
                        try (InputStream in = getContentResolver().openInputStream(p12)) {
                            id = Identidad.dePkcs12(in, clave);
                        } catch (Exception ex) {
                            error = ex.getMessage();
                        } finally {
                            java.util.Arrays.fill(clave, '\0');
                        }
                        Identidad r = id;
                        String err = error;
                        runOnUiThread(() -> {
                            if (r != null) confirmarCertificado(r);
                            else mensaje(err != null ? err : "No se pudo leer el certificado.");
                        });
                    }).start();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        e.requestFocus();
    }

    private void confirmarCertificado(Identidad id) {
        try {
            id.certificado().checkValidity();
        } catch (CertificateExpiredException e) {
            mensaje("El certificado de " + id.nombre() + " está caducado y no sirve para firmar.");
            return;
        } catch (CertificateNotYetValidException e) {
            mensaje("El certificado de " + id.nombre() + " todavía no es válido.");
            return;
        }
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(24), dp(8), dp(24), 0);
        TextView info = new TextView(this);
        info.setText(id.nombre() + "\n\nEmitido por: " + id.emisor() + "\nVálido hasta: "
                + DateFormat.getDateInstance(DateFormat.LONG).format(id.certificado().getNotAfter()));
        v.addView(info);
        // Firma manuscrita opcional dentro del recuadro: "Sin firma manuscrita" o una de las guardadas.
        List<Trazo> firmas = Firmas.lista(this);
        android.widget.Spinner manuscrita = new android.widget.Spinner(this);
        if (!firmas.isEmpty()) {
            List<String> nombres = new ArrayList<>();
            nombres.add("Sin firma manuscrita");
            for (Trazo t : firmas) nombres.add("Incluir «" + t.nombre + "»");
            ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, nombres);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            manuscrita.setAdapter(ad);
            manuscrita.setSelection(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            v.addView(manuscrita, lp);
        }
        new AlertDialog.Builder(this)
                .setTitle("Firmar como")
                .setView(v)
                .setPositiveButton("Colocar firma", (d, w) -> {
                    int i = firmas.isEmpty() ? 0 : manuscrita.getSelectedItemPosition();
                    colocar(i > 0 ? firmas.get(i - 1) : null, id);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Muestra la firma sobre la página para que el usuario la coloque. */
    private void colocar(Trazo t, Identidad id) {
        firmaTrazo = t;
        firmaIdentidad = id;
        cerrarBusqueda();
        if (!barrasVisibles) toque();
        VistaPdf.Contenido contenido;
        if (id == null) {
            contenido = new VistaPdf.Contenido() {
                @Override public void dibujar(android.graphics.Canvas c, RectF r) { t.dibujar(c, r); }
                @Override public float aspecto() { return t.aspecto; }
            };
        } else {
            Apariencia ap = new Apariencia(id.nombre(), new Date(), t);
            contenido = new VistaPdf.Contenido() {
                @Override public void dibujar(android.graphics.Canvas c, RectF r) { ap.dibujar(c, r); }
                @Override public float aspecto() { return Apariencia.ASPECTO; }
            };
        }
        vista.empezarColocacion(contenido);
        barraInferior.setVisibility(View.GONE);
        barraFirma.setVisibility(View.VISIBLE);
    }

    private void cancelarFirma() {
        vista.terminarColocacion();
        firmaTrazo = null;
        firmaIdentidad = null;
        barraFirma.setVisibility(View.GONE);
        barraInferior.setVisibility(View.VISIBLE);
    }

    private void elegirDestinoFirmado() {
        firmaPosicion = new Firmador.Posicion(vista.paginaColocacion(), vista.fraccionColocacion());
        String base = getTitle().toString().replaceAll("(?i)\\.pdf$", "");
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, base + "_firmado.pdf"), GUARDAR_FIRMADO);
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent datos) {
        if (herramientas != null && herramientas.alResultado(pedido, resultado, datos)) return;
        if (resultado != RESULT_OK || datos == null) return;
        if (pedido == CREAR_FIRMA) {
            Trazo t = Firmas.buscar(this, datos.getStringExtra(CrearFirmaActivity.EXTRA_ID));
            if (t != null) colocar(t, null);
            return;
        }
        if (datos.getData() == null) return;
        if (pedido == PEDIR_P12) abrirP12(datos.getData());
        else if (pedido == GUARDAR_FIRMADO && firmaPosicion != null) firmarEn(datos.getData());
    }

    private void firmarEn(Uri destino) {
        Trazo t = firmaTrazo;
        Identidad id = firmaIdentidad;
        Firmador.Posicion pos = firmaPosicion;
        AlertDialog progreso = new AlertDialog.Builder(this)
                .setMessage(id != null ? "Firmando con el certificado…" : "Guardando la firma…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            File copia = new File(getCacheDir(), "para-firmar.pdf");
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(copia)) {
                    if (in == null) throw new java.io.IOException("No se pudo leer el documento original.");
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                try (OutputStream out = getContentResolver().openOutputStream(destino, "wt")) {
                    if (out == null) throw new java.io.IOException("No se pudo crear el archivo de destino.");
                    if (id != null) Firmador.firmar(this, copia, clavePdf, out, pos, id, t);
                    else Firmador.estampar(this, copia, clavePdf, out, pos, t);
                }
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                try {
                    android.provider.DocumentsContract.deleteDocument(getContentResolver(), destino);
                } catch (Exception ignorado) { }
            } finally {
                copia.delete();
            }
            String err = error;
            runOnUiThread(() -> {
                progreso.dismiss();
                if (err != null) {
                    mensaje("No se pudo firmar el documento:\n" + err);
                    return;
                }
                cancelarFirma();
                firmado(destino);
            });
        }).start();
    }

    private void firmado(Uri destino) {
        try {
            getContentResolver().takePersistableUriPermission(destino,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignorado) { }
        new AlertDialog.Builder(this)
                .setTitle("Documento firmado")
                .setMessage("Se ha guardado una copia firmada. El documento original no se ha modificado.")
                .setPositiveButton("Abrir", (d, w) ->
                        startActivity(new Intent(this, LectorActivity.class).setData(destino)))
                .setNeutralButton("Compartir", (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("application/pdf")
                                .putExtra(Intent.EXTRA_STREAM, destino)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir PDF firmado")))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void mensaje(String texto) {
        new AlertDialog.Builder(this).setMessage(texto).setPositiveButton("Aceptar", null).show();
    }

    // --- Ciclo de vida ---

    @Override protected void onPause() {
        super.onPause();
        if (total > 0) Recientes.guardarPagina(this, uri, vista.paginaActual());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (motor != null) motor.cerrar();
    }

    // --- Utilidades ---

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

    private View conMargen(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
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

    private int alturaActionBar() {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            return TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        }
        return dp(56);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
