package com.docreader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

/**
 * Estilo común de la app: paleta neutra con un único acento discreto (la misma que
 * res/values*\/colores.xml), barras con una línea fina en vez de sombras, botones y botones de icono.
 * Sigue el modo claro/oscuro del sistema; el «Modo noche» de los documentos es aparte.
 */
public final class Estilo {
    private Estilo() { }

    public static boolean oscuro(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int fondo(Context c) { return oscuro(c) ? 0xFF141415 : 0xFFF6F6F4; }
    public static int superficie(Context c) { return oscuro(c) ? 0xFF1C1C1E : 0xFFFFFFFF; }
    /** Superficie un poco más marcada: chips, campos, elementos seleccionados. */
    public static int superficie2(Context c) { return oscuro(c) ? 0xFF26262A : 0xFFEFEFEC; }
    public static int linea(Context c) { return oscuro(c) ? 0xFF2E2E32 : 0xFFE3E3DF; }
    public static int texto(Context c) { return oscuro(c) ? 0xFFECECEE : 0xFF1D1D1F; }
    public static int texto2(Context c) { return oscuro(c) ? 0xFF9A9AA0 : 0xFF6E6E73; }
    public static int acento(Context c) { return oscuro(c) ? 0xFFA9BDD1 : 0xFF44617B; }
    public static int sobreAcento(Context c) { return oscuro(c) ? 0xFF141415 : 0xFFFFFFFF; }
    public static int acentoSuave(Context c) { return oscuro(c) ? 0xFF26313C : 0xFFE3E9EF; }
    public static int avisoFondo(Context c) { return oscuro(c) ? 0xFF332C1C : 0xFFF7F0DD; }
    public static int avisoTexto(Context c) { return oscuro(c) ? 0xFFE8D9A8 : 0xFF5E4B16; }
    static int onda(Context c) { return oscuro(c) ? 0x1FFFFFFF : 0x14000000; }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Fondo de barra: superficie con una línea fina arriba (barras inferiores) o abajo (superiores). */
    public static Drawable barra(Context c, boolean lineaArriba) {
        LayerDrawable l = new LayerDrawable(new Drawable[] {
                new ColorDrawable(linea(c)), new ColorDrawable(superficie(c))});
        int g = Math.max(1, dp(c, 0.75f));
        if (lineaArriba) l.setLayerInset(1, 0, g, 0, 0);
        else l.setLayerInset(1, 0, 0, 0, g);
        return l;
    }

    public static GradientDrawable redondo(int color, float radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radio);
        return g;
    }

    /** Fondo con efecto al tocar: rectángulo redondeado (radio en px; color 0 = transparente). */
    public static Drawable tocable(Context c, int color, float radio) {
        GradientDrawable forma = redondo(color, radio);
        GradientDrawable mascara = redondo(0xFFFFFFFF, radio);
        return new RippleDrawable(ColorStateList.valueOf(onda(c)), color == 0 ? null : forma, mascara);
    }

    /** Botón de icono redondo y sin borde, de 44 dp (tamaño cómodo para el dedo). */
    public static ImageButton icono(Context c, Icono.Tipo tipo, String descripcion, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageDrawable(new Icono(c, tipo, texto2(c)));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(onda(c)), null, null));
        b.setContentDescription(descripcion);
        b.setTooltipText(descripcion);
        int p = dp(c, 10);
        b.setPadding(p, p, p, p);
        b.setMinimumWidth(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    /** Botón de texto con el color de acento (acciones secundarias). */
    public static Button textoBoton(Context c, String texto, View.OnClickListener l) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(texto);
        b.setAllCaps(false);
        b.setTextColor(acento(c));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(dp(c, 44));
        b.setMinimumWidth(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    /** Botón principal: relleno de acento, esquinas redondeadas y un icono opcional. */
    public static Button principal(Context c, String texto, Icono.Tipo icono, View.OnClickListener l) {
        Button b = new Button(c, null, 0);
        b.setText(texto);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(sobreAcento(c));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(tocable(c, acento(c), dp(c, 14)));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        if (icono != null) {
            Icono i = new Icono(c, icono, sobreAcento(c));
            i.setBounds(0, 0, dp(c, 22), dp(c, 22));
            b.setCompoundDrawablesRelative(i, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 10));
        }
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    /** Botón secundario: tono suave del acento. */
    public static Button tonal(Context c, String texto, Icono.Tipo icono, View.OnClickListener l) {
        Button b = principal(c, texto, null, l);
        b.setTextSize(15);
        b.setTextColor(acento(c));
        b.setBackground(tocable(c, acentoSuave(c), dp(c, 14)));
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        if (icono != null) {
            Icono i = new Icono(c, icono, acento(c));
            i.setBounds(0, 0, dp(c, 20), dp(c, 20));
            b.setCompoundDrawablesRelative(i, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 8));
        }
        return b;
    }

    /** Título de sección en versalitas pequeñas («RECIENTES»). */
    public static TextView seccion(Context c, String texto) {
        TextView t = new TextView(c);
        t.setText(texto.toUpperCase(java.util.Locale.ROOT));
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(texto2(c));
        return t;
    }

    /** Campo de búsqueda de las barras: sin subrayado, sobre un fondo suave redondeado. */
    public static void campo(Context c, TextView campo) {
        campo.setBackground(redondo(superficie2(c), dp(c, 10)));
        campo.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        campo.setTextColor(texto(c));
        campo.setHintTextColor(texto2(c));
        campo.setTextSize(15);
    }
}
