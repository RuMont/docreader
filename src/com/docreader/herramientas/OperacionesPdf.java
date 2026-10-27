package com.docreader.herramientas;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Extraer, dividir y fusionar PDF con PdfBox. Siempre se escriben archivos nuevos. */
public final class OperacionesPdf {
    /** Da el flujo de salida para cada parte (índice desde 0) al dividir. */
    public interface Destinos { OutputStream abrir(int parte, int[] rango) throws IOException; }

    public interface Progreso { boolean seguir(int hecho, int total); }

    private OperacionesPdf() { }

    /** Contraseña que falta o no vale; FusionarActivity la pide para ese archivo. */
    public static final class ClaveNecesaria extends IOException {
        public final int indice;
        ClaveNecesaria(int indice, boolean incorrecta) {
            super(incorrecta ? "Contraseña incorrecta." : "El PDF está protegido con contraseña.");
            this.indice = indice;
        }
    }

    /**
     * Abre un PDF, con su contraseña si la tiene. Lo que se genere a partir de él sale sin
     * contraseña (las páginas copiadas a un documento nuevo ya van descifradas).
     */
    static PDDocument cargar(File f, String clave) throws IOException {
        return cargar(f, clave, 0);
    }

    static PDDocument cargar(File f, String clave, int indice) throws IOException {
        PDDocument d;
        try {
            d = PDDocument.load(f, clave == null ? "" : clave, MemoryUsageSetting.setupMixed(64L << 20));
        } catch (InvalidPasswordException e) {
            throw new ClaveNecesaria(indice, clave != null);
        } catch (IOException e) {
            throw new IOException("No se pudo leer el PDF: " + e.getMessage(), e);
        }
        if (d.isEncrypted()) d.setAllSecurityToBeRemoved(true);
        return d;
    }

    /** Todas las páginas de los rangos, en ese orden, en un único PDF nuevo. */
    public static void extraer(File origen, String clave, List<int[]> rangos, OutputStream destino)
            throws IOException {
        try (PDDocument src = cargar(origen, clave); PDDocument nuevo = new PDDocument()) {
            copiarInfo(src, nuevo);
            for (int[] r : rangos) for (int i = r[0]; i <= r[1]; i++) nuevo.importPage(src.getPage(i));
            nuevo.save(destino);
        }
    }

    /** Un PDF por rango. */
    public static void dividir(File origen, String clave, List<int[]> rangos, Destinos destinos,
                               Progreso progreso) throws IOException {
        try (PDDocument src = cargar(origen, clave)) {
            for (int k = 0; k < rangos.size(); k++) {
                if (progreso != null && !progreso.seguir(k, rangos.size())) return;
                int[] r = rangos.get(k);
                try (PDDocument parte = new PDDocument(); OutputStream out = destinos.abrir(k, r)) {
                    copiarInfo(src, parte);
                    for (int i = r[0]; i <= r[1]; i++) parte.importPage(src.getPage(i));
                    parte.save(out);
                }
            }
            if (progreso != null) progreso.seguir(rangos.size(), rangos.size());
        }
    }

    /**
     * Une los PDF en el orden dado. "claves" tiene la contraseña de cada uno (o null). Los que
     * están protegidos se descifran antes a un archivo temporal; el resultado va sin contraseña.
     */
    public static void fusionar(List<File> entradas, List<String> claves, OutputStream destino) throws IOException {
        List<File> listos = new java.util.ArrayList<>(), temporales = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < entradas.size(); i++) {
                File f = entradas.get(i);
                try (PDDocument d = cargar(f, claves.get(i), i)) {
                    if (d.getNumberOfPages() == 0) throw new IOException("Uno de los PDF no tiene páginas.");
                    if (d.isEncrypted()) {
                        File t = new File(f.getPath() + ".descifrado");
                        d.save(t);
                        temporales.add(t);
                        f = t;
                    }
                }
                listos.add(f);
            }
            unir(listos, destino);
        } finally {
            for (File t : temporales) t.delete();
        }
    }

    private static void unir(List<File> entradas, OutputStream destino) throws IOException {
        PDFMergerUtility m = new PDFMergerUtility();
        for (File f : entradas) m.addSource(f);
        m.setDestinationStream(destino);
        m.mergeDocuments(MemoryUsageSetting.setupMixed(64L << 20));
    }

    /**
     * Copia protegida con contraseña (AES-256). "claveActual" es la del original si la tiene.
     * Devuelve true si el PDF tenía firmas digitales (dejan de ser válidas al cifrarlo de nuevo).
     */
    public static boolean proteger(File origen, String claveActual, String nueva, boolean imprimir,
                                   boolean copiar, boolean modificar, OutputStream destino) throws IOException {
        try (PDDocument d = cargar(origen, claveActual)) {
            boolean firmas = !d.getSignatureDictionaries().isEmpty();
            com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission p =
                    new com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission();
            p.setCanPrint(imprimir);
            p.setCanPrintFaithful(imprimir);
            p.setCanExtractContent(copiar);
            p.setCanExtractForAccessibility(true); // lectores de pantalla, siempre
            p.setCanModify(modificar);
            p.setCanModifyAnnotations(modificar);
            p.setCanFillInForm(modificar);
            p.setCanAssembleDocument(modificar);
            // Contraseña de propietario aleatoria: los permisos no se pueden cambiar sin ella.
            byte[] azar = new byte[24];
            new java.security.SecureRandom().nextBytes(azar);
            String propietario = java.util.Base64.getEncoder().encodeToString(azar);
            com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy pol =
                    new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(propietario, nueva, p);
            pol.setEncryptionKeyLength(256);
            d.setAllSecurityToBeRemoved(false);
            d.protect(pol);
            d.save(destino);
            return firmas;
        }
    }

    /** Copia sin contraseña. Devuelve true si tenía firmas digitales (dejan de ser válidas). */
    public static boolean quitarClave(File origen, String clave, OutputStream destino) throws IOException {
        try (PDDocument d = cargar(origen, clave)) {
            boolean firmas = !d.getSignatureDictionaries().isEmpty();
            d.setAllSecurityToBeRemoved(true);
            d.save(destino);
            return firmas;
        }
    }

    private static void copiarInfo(PDDocument src, PDDocument dst) {
        PDDocumentInformation i = src.getDocumentInformation();
        PDDocumentInformation n = new PDDocumentInformation();
        if (i.getTitle() != null) n.setTitle(i.getTitle());
        if (i.getAuthor() != null) n.setAuthor(i.getAuthor());
        n.setProducer("DocReader");
        dst.setDocumentInformation(n);
    }
}
