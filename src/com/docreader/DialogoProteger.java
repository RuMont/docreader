package com.docreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Diálogo para poner contraseña a un documento: contraseña dos veces, indicador de fortaleza,
 * aviso de que no se puede recuperar y, para PDF, los permisos (imprimir, copiar, modificar).
 */
public final class DialogoProteger {
    public interface AlAceptar { void listo(String clave, Permisos permisos); }

    /** Qué se permite hacer con el PDF sin la contraseña de propietario. */
    public static final class Permisos {
        public boolean imprimir = true, copiar = true, modificar = true;
    }

    private DialogoProteger() { }

    public static void mostrar(Activity a, String nombre, boolean conPermisos, AlAceptar cb) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Math.round(24 * d), Math.round(8 * d), Math.round(24 * d), 0);

        EditText clave = campo(a, "Contraseña");
        EditText repetir = campo(a, "Repite la contraseña");
        TextView fuerza = new TextView(a);
        fuerza.setPadding(0, Math.round(4 * d), 0, Math.round(8 * d));
        v.addView(clave);
        v.addView(repetir);
        v.addView(fuerza);

        Permisos permisos = new Permisos();
        CheckBox imprimir = new CheckBox(a), copiar = new CheckBox(a), modificar = new CheckBox(a);
        if (conPermisos) {
            TextView t = new TextView(a);
            t.setText("Con la contraseña abierta, permitir:");
            t.setAlpha(0.75f);
            v.addView(t);
            imprimir.setText("Imprimir");
            copiar.setText("Copiar texto e imágenes");
            modificar.setText("Modificar, anotar y rellenar formularios");
            for (CheckBox c : new CheckBox[] {imprimir, copiar, modificar}) {
                c.setChecked(true);
                v.addView(c);
            }
        }

        TextView aviso = new TextView(a);
        aviso.setText("Importante: si olvidas la contraseña, nadie podrá abrir la copia protegida, "
                + "tampoco DocReader. El documento original no se modifica.");
        aviso.setTextColor(0xFFD32F2F);
        aviso.setPadding(0, Math.round(10 * d), 0, 0);
        v.addView(aviso);

        clave.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int j, int k) { }
            @Override public void onTextChanged(CharSequence s, int i, int j, int k) { }
            @Override public void afterTextChanged(Editable s) {
                int f = fortaleza(s.toString());
                String[] textos = {"", "Muy débil", "Débil", "Aceptable", "Fuerte", "Muy fuerte"};
                int[] colores = {0, 0xFFD32F2F, 0xFFE64A19, 0xFFF9A825, 0xFF7CB342, 0xFF388E3C};
                fuerza.setText(s.length() == 0 ? "" : "Fortaleza: " + textos[f]);
                fuerza.setTextColor(colores[f]);
            }
        });

        android.widget.ScrollView desplazable = new android.widget.ScrollView(a);
        desplazable.addView(v);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Proteger «" + nombre + "»")
                .setView(desplazable)
                .setPositiveButton("Proteger", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String c1 = clave.getText().toString(), c2 = repetir.getText().toString();
            if (c1.isEmpty()) {
                clave.setError("Escribe una contraseña");
                return;
            }
            if (!c1.equals(c2)) {
                repetir.setError("Las contraseñas no coinciden");
                return;
            }
            permisos.imprimir = imprimir.isChecked();
            permisos.copiar = copiar.isChecked();
            permisos.modificar = modificar.isChecked();
            dlg.dismiss();
            cb.listo(c1, permisos);
        }));
        dlg.show();
        clave.requestFocus();
    }

    private static EditText campo(Activity a, String pista) {
        EditText e = new EditText(a);
        e.setHint(pista);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    /** 1 (muy débil) a 5 (muy fuerte), según longitud y variedad de caracteres. */
    static int fortaleza(String s) {
        if (s.isEmpty()) return 0;
        int tipos = 0;
        if (s.matches(".*[a-zñ].*")) tipos++;
        if (s.matches(".*[A-ZÑ].*")) tipos++;
        if (s.matches(".*\\d.*")) tipos++;
        if (s.matches(".*[^a-zA-ZñÑ\\d].*")) tipos++;
        int puntos = s.length() >= 16 ? 3 : s.length() >= 12 ? 2 : s.length() >= 8 ? 1 : 0;
        puntos += Math.max(0, tipos - 1);
        return Math.max(1, Math.min(5, puntos));
    }
}
