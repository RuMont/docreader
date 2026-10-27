package com.docreader.firma;

import android.app.Activity;
import android.security.KeyChain;

import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.security.auth.x500.X500Principal;

/** Certificado de firma: clave privada y cadena de certificados. */
public final class Identidad {
    public interface AlElegir { void listo(Identidad id, String error); }

    final PrivateKey clave;
    final X509Certificate[] cadena;

    private Identidad(PrivateKey clave, X509Certificate[] cadena) {
        this.clave = clave;
        this.cadena = cadena;
    }

    public X509Certificate certificado() { return cadena[0]; }

    /** Nombre del titular (CN del certificado). */
    public String nombre() { return cn(cadena[0].getSubjectX500Principal()); }

    public String emisor() { return cn(cadena[0].getIssuerX500Principal()); }

    /** Certificados instalados en Android (Ajustes › Seguridad › Instalar certificado). */
    public static void delSistema(Activity a, AlElegir cb) {
        KeyChain.choosePrivateKeyAlias(a, alias -> {
            if (alias == null) {
                a.runOnUiThread(() -> cb.listo(null, null));
                return;
            }
            // KeyChain no se puede usar en el hilo principal; este callback ya va en otro hilo.
            Identidad id = null;
            String error = null;
            try {
                PrivateKey k = KeyChain.getPrivateKey(a, alias);
                X509Certificate[] c = KeyChain.getCertificateChain(a, alias);
                if (k == null || c == null || c.length == 0) error = "No se pudo acceder al certificado.";
                else id = new Identidad(k, c);
            } catch (Exception e) {
                error = "No se pudo acceder al certificado: " + e.getMessage();
            }
            Identidad r = id;
            String err = error;
            a.runOnUiThread(() -> cb.listo(r, err));
        }, new String[] {"RSA", "EC"}, null, null, null);
    }

    /** Archivo .p12 / .pfx. Lanza IOException con un mensaje claro si la contraseña no vale. */
    public static Identidad dePkcs12(InputStream in, char[] clave) throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, clave);
            for (String alias : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) continue;
                Key k = ks.getKey(alias, clave);
                Certificate[] c = ks.getCertificateChain(alias);
                if (!(k instanceof PrivateKey) || c == null || c.length == 0) continue;
                X509Certificate[] x = new X509Certificate[c.length];
                for (int i = 0; i < c.length; i++) x[i] = (X509Certificate) c[i];
                return new Identidad((PrivateKey) k, x);
            }
            throw new IOException("El archivo no contiene ningún certificado con clave privada.");
        } catch (IOException e) {
            if (e.getCause() instanceof java.security.UnrecoverableKeyException
                    || String.valueOf(e.getMessage()).toLowerCase().contains("password")) {
                throw new IOException("Contraseña incorrecta.");
            }
            throw e;
        } catch (java.security.UnrecoverableKeyException e) {
            throw new IOException("Contraseña incorrecta.");
        } catch (Exception e) {
            throw new IOException("No se pudo leer el certificado: " + e.getMessage());
        }
    }

    /** CN de un nombre X.500 (o el nombre completo si no tiene CN). */
    static String cn(X500Principal p) {
        String n = p.getName(X500Principal.RFC2253);
        int i = 0;
        while (i < n.length()) {
            int fin = i;
            StringBuilder valor = new StringBuilder();
            boolean escape = false;
            int igual = n.indexOf('=', i);
            if (igual < 0) break;
            String tipo = n.substring(i, igual).trim();
            for (fin = igual + 1; fin < n.length(); fin++) {
                char c = n.charAt(fin);
                if (escape) {
                    valor.append(c);
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == ',' || c == '+') {
                    break;
                } else {
                    valor.append(c);
                }
            }
            if (tipo.equalsIgnoreCase("CN")) return valor.toString().trim();
            i = fin + 1;
        }
        return n;
    }
}
