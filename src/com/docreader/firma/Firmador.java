package com.docreader.firma;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;

import com.tom_roush.harmony.awt.geom.AffineTransform;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.common.PDStream;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Date;

/**
 * Escribe firmas en un PDF con PdfBox. Siempre se guarda una copia; el original no se toca.
 * La posición llega en fracciones de la página tal como se ve (con su rotación aplicada).
 */
public final class Firmador {
    private static final String FUENTE = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    /** Rectángulo en fracciones 0..1 de la página vista (origen arriba a la izquierda). */
    public static final class Posicion {
        public final int pag;
        public final RectF r;
        public Posicion(int pag, RectF r) {
            this.pag = pag;
            this.r = r;
        }
    }

    private Firmador() { }

    private static void iniciar(Context c) {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(c.getApplicationContext());
    }

    /** Con contraseña si la tiene; la copia firmada conserva la protección del original. */
    private static PDDocument cargar(File origen, String clave) throws IOException {
        try {
            return PDDocument.load(origen, clave == null ? "" : clave);
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new IOException("La contraseña del PDF no es correcta.", e);
        } catch (IOException e) {
            throw new IOException("No se pudo leer el PDF para firmarlo: " + e.getMessage(), e);
        }
    }

    /** Firma manuscrita incrustada como trazos vectoriales en el contenido de la página. */
    public static void estampar(Context c, File origen, String clave, OutputStream destino, Posicion pos, Trazo t)
            throws IOException {
        iniciar(c);
        try (PDDocument doc = cargar(origen, clave)) {
            PDPage pagina = doc.getPage(pos.pag);
            Geometria g = new Geometria(pagina, pos.r);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pagina,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.saveGraphicsState();
                cs.transform(g.matriz(true));
                t.aPdf(cs, 0, 0, g.ancho, g.alto);
                cs.restoreGraphicsState();
            }
            if (doc.getSignatureDictionaries().isEmpty() && !doc.isEncrypted()) {
                doc.save(destino);
            } else {
                // Firmas digitales previas (no invalidarlas) o PDF cifrado (conservar su protección):
                // guardado incremental.
                marcarRuta(doc, pagina);
                doc.saveIncremental(destino);
            }
        }
    }

    /** Firma digital PAdES (ETSI.CAdES.detached) con un recuadro visible. */
    public static void firmar(Context c, File origen, String clave, OutputStream destino, Posicion pos,
                              Identidad id, Trazo trazo) throws IOException {
        iniciar(c);
        Date ahora = new Date();
        Apariencia ap = new Apariencia(id.nombre(), ahora, trazo);
        try (PDDocument doc = cargar(origen, clave);
             SignatureOptions op = new SignatureOptions()) {
            PDSignature s = new PDSignature();
            s.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            s.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            s.setName(id.nombre());
            Calendar cal = Calendar.getInstance();
            cal.setTime(ahora);
            s.setSignDate(cal);

            op.setPreferredSignatureSize(0x5000);
            op.setPage(pos.pag);
            op.setVisualSignature(plantilla(c, doc.getPage(pos.pag), pos.r, ap));
            doc.addSignature(s, contenido -> Cms.firmar(contenido, id), op);
            doc.saveIncremental(destino);
        }
    }

    /** Documento auxiliar con el campo de firma y su aspecto; PdfBox lo copia al documento real. */
    private static InputStream plantilla(Context c, PDPage origen, RectF r, Apariencia ap) throws IOException {
        Geometria g = new Geometria(origen, r);
        try (PDDocument doc = new PDDocument()) {
            PDPage pagina = new PDPage(origen.getMediaBox());
            doc.addPage(pagina);
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDSignatureField campo = new PDSignatureField(form);
            PDAnnotationWidget widget = campo.getWidgets().get(0);
            form.setSignaturesExist(true);
            form.setAppendOnly(true);
            form.getCOSObject().setDirect(true);
            form.getFields().add(campo);
            widget.setRectangle(g.rectUsuario());

            PDFormXObject xo = new PDFormXObject(new PDStream(doc));
            xo.setResources(new PDResources());
            xo.setFormType(1);
            xo.setBBox(new PDRectangle(g.ancho, g.alto));
            Matrix m = g.matriz(false);
            xo.setMatrix(new AffineTransform(m.getValue(0, 0), m.getValue(0, 1), m.getValue(1, 0),
                    m.getValue(1, 1), 0, 0));
            PDAppearanceDictionary apd = new PDAppearanceDictionary();
            apd.getCOSObject().setDirect(true);
            PDAppearanceStream as = new PDAppearanceStream(xo.getCOSObject());
            apd.setNormalAppearance(as);
            widget.setAppearance(apd);

            PDFont fuente;
            try (InputStream f = c.getAssets().open(FUENTE)) {
                fuente = PDType0Font.load(doc, f);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, as)) {
                ap.aPdf(cs, fuente, g.ancho, g.alto);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    /** Marca como modificados la página y sus antecesores (necesario para saveIncremental). */
    private static void marcarRuta(PDDocument doc, PDPage pagina) {
        doc.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
        COSDictionary d = pagina.getCOSObject();
        while (d != null) {
            d.setNeedToBeUpdated(true);
            d = d.getCOSDictionary(COSName.PARENT);
        }
        COSBase contenido = pagina.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        if (contenido instanceof COSArray) ((COSArray) contenido).setNeedToBeUpdated(true);
    }

    /**
     * Traduce un rectángulo de la página vista (fracciones, origen arriba a la izquierda) al
     * espacio de usuario del PDF (puntos, origen abajo a la izquierda), teniendo en cuenta la
     * CropBox y la rotación de la página.
     */
    private static final class Geometria {
        final float ancho, alto; // tamaño del rectángulo en puntos, tal como se ve
        private final float llx, lly, w, h, anchoVisto, altoVisto;
        private final int rot;
        private final RectF r;

        Geometria(PDPage p, RectF r) {
            PDRectangle cb = p.getCropBox();
            llx = cb.getLowerLeftX();
            lly = cb.getLowerLeftY();
            w = cb.getWidth();
            h = cb.getHeight();
            rot = ((p.getRotation() % 360) + 360) % 360;
            boolean girada = rot == 90 || rot == 270;
            anchoVisto = girada ? h : w;
            altoVisto = girada ? w : h;
            this.r = r;
            ancho = r.width() * anchoVisto;
            alto = r.height() * altoVisto;
        }

        /** Punto de la página vista (fracciones) → espacio de usuario. */
        PointF usuario(float fx, float fy) {
            float dx = fx * anchoVisto, dy = fy * altoVisto, ux, uy;
            switch (rot) {
                case 90: ux = dy; uy = dx; break;
                case 180: ux = w - dx; uy = dy; break;
                case 270: ux = w - dy; uy = h - dx; break;
                default: ux = dx; uy = h - dy; break;
            }
            return new PointF(llx + ux, lly + uy);
        }

        /** Matriz que lleva la caja local (0..ancho, 0..alto, y hacia arriba) a la página. */
        Matrix matriz(boolean conTraslacion) {
            PointF p00 = usuario(r.left, r.bottom), p10 = usuario(r.right, r.bottom), p01 = usuario(r.left, r.top);
            float a = (p10.x - p00.x) / ancho, b = (p10.y - p00.y) / ancho;
            float cc = (p01.x - p00.x) / alto, d = (p01.y - p00.y) / alto;
            return conTraslacion ? new Matrix(a, b, cc, d, p00.x, p00.y) : new Matrix(a, b, cc, d, 0, 0);
        }

        PDRectangle rectUsuario() {
            PointF a = usuario(r.left, r.top), b = usuario(r.right, r.bottom);
            float x0 = Math.min(a.x, b.x), y0 = Math.min(a.y, b.y);
            return new PDRectangle(x0, y0, Math.abs(b.x - a.x), Math.abs(b.y - a.y));
        }
    }
}
