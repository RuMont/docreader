package com.docreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Reconocimiento de texto con Tesseract (español + inglés), totalmente local. */
final class Ocr {
    private static final String IDIOMAS = "spa+eng";
    private static final String[] MODELOS = {"spa.traineddata", "eng.traineddata"};

    private final Context ctx;
    private TessBaseAPI tess;

    Ocr(Context c) {
        ctx = c.getApplicationContext();
    }

    /** Debe llamarse siempre desde el mismo hilo. */
    CapaOcr reconocer(Bitmap bmp, float escala) throws IOException {
        if (tess == null) iniciar();
        tess.setImage(bmp);
        tess.getUTF8Text();
        List<CapaOcr.Palabra> palabras = new ArrayList<>();
        ResultIterator it = tess.getResultIterator();
        if (it != null) {
            it.begin();
            do {
                String t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                if (t == null || t.trim().isEmpty()) continue;
                Rect r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                boolean nueva = it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                palabras.add(new CapaOcr.Palabra(t.trim(), new RectF(r.left / escala, r.top / escala,
                        r.right / escala, r.bottom / escala), nueva || palabras.isEmpty()));
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));
            it.delete();
        }
        tess.clear();
        return new CapaOcr(palabras);
    }

    private void iniciar() throws IOException {
        File dir = new File(ctx.getFilesDir(), "tessdata");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No se pudo preparar el OCR");
        for (String m : MODELOS) {
            File f = new File(dir, m);
            if (f.isFile() && f.length() > 0) continue;
            File tmp = new File(dir, m + ".tmp");
            try (InputStream in = ctx.getAssets().open("tessdata/" + m);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(f)) throw new IOException("No se pudo preparar el OCR");
        }
        TessBaseAPI t;
        try {
            t = new TessBaseAPI();
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("El OCR no está disponible en este procesador");
        }
        if (!t.init(ctx.getFilesDir().getPath(), IDIOMAS, TessBaseAPI.OEM_LSTM_ONLY)) {
            t.recycle();
            throw new IOException("No se pudo iniciar el OCR");
        }
        t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
        tess = t;
    }

    void detener() {
        TessBaseAPI t = tess;
        if (t != null) t.stop();
    }

    void cerrar() {
        if (tess != null) {
            tess.recycle();
            tess = null;
        }
    }
}
