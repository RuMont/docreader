package com.docreader.herramientas;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Convierte un PDF en un .docx editable: reconstruye párrafos (uniendo las líneas y los guiones
 * de fin de línea) y detecta títulos por el tamaño de letra. Las páginas sin texto (escaneadas)
 * usan el texto del OCR si existe; si no, se insertan como imagen.
 */
public final class PdfADocx {
    /** Lo que solo sabe dar la parte Android: OCR guardado e imagen de una página. */
    public interface Paginas {
        /** Texto reconocido por OCR (párrafos separados por "\n\n") o null si no hay. */
        String textoOcr(int pag);
        /** JPEG de la página; tam recibe {ancho, alto} en píxeles. null si no se pudo. */
        byte[] imagen(int pag, int[] tam);
    }

    public interface Progreso { boolean seguir(int hecho, int total); }

    private PdfADocx() { }

    public static void convertir(File origen, String clave, OutputStream destino, String titulo, Paginas paginas,
                                 Progreso progreso) throws IOException {
        try (PDDocument doc = OperacionesPdf.cargar(origen, clave)) {
            int total = doc.getNumberOfPages();
            Extractor ex = new Extractor();
            EscritorDocx w = new EscritorDocx();
            for (int p = 0; p < total; p++) {
                if (progreso != null && !progreso.seguir(p, total)) throw new IOException("Cancelado");
                List<Parrafo> ps = ex.pagina(doc, p);
                if (p > 0) w.saltoDePagina();
                if (letras(ps) >= 20) {
                    escribir(w, ps, ex.tamCuerpo(ps));
                    continue;
                }
                String ocr = paginas == null ? null : paginas.textoOcr(p);
                if (ocr != null && ocr.trim().length() >= 20) {
                    for (String par : ocr.split("\n\\s*\n")) {
                        String t = par.replace('\n', ' ').trim();
                        if (!t.isEmpty()) w.parrafo(EscritorDocx.NORMAL, t, false);
                    }
                    continue;
                }
                int[] tam = new int[2];
                byte[] jpeg = paginas == null ? null : paginas.imagen(p, tam);
                if (jpeg != null) w.imagen(jpeg, tam[0], tam[1]);
                else escribir(w, ps, ex.tamCuerpo(ps));
            }
            if (progreso != null) progreso.seguir(total, total);
            w.escribir(destino, titulo);
        }
    }

    private static int letras(List<Parrafo> ps) {
        int n = 0;
        for (Parrafo p : ps) n += p.texto.length();
        return n;
    }

    private static void escribir(EscritorDocx w, List<Parrafo> ps, float cuerpo) {
        for (Parrafo p : ps) {
            String t = p.texto.toString().trim();
            if (t.isEmpty()) continue;
            int estilo = EscritorDocx.NORMAL;
            boolean corto = t.length() <= 120 && !t.endsWith(".");
            if (cuerpo > 0 && corto) {
                float r = p.tam / cuerpo;
                if (r >= 1.5f) estilo = EscritorDocx.TITULO1;
                else if (r >= 1.2f) estilo = EscritorDocx.TITULO2;
                else if (p.negrita && t.length() <= 80) estilo = EscritorDocx.TITULO3;
            }
            w.parrafo(estilo, t, p.negrita && estilo == EscritorDocx.NORMAL);
        }
    }

    private static final class Parrafo {
        final StringBuilder texto = new StringBuilder();
        float tam;          // tamaño de letra más frecuente (aprox.: el mayor visto)
        int caracteres, enNegrita;
        boolean negrita;
    }

    /** PDFTextStripper que, en vez de texto plano, construye párrafos con su tamaño de letra. */
    private static final class Extractor extends PDFTextStripper {
        private List<Parrafo> parrafos;
        private Parrafo actual;
        private int lineas, lineaChars, lineaNegrita; // del párrafo y de la línea en curso

        Extractor() throws IOException {
            super();
            setSortByPosition(false);
            setShouldSeparateByBeads(true);
        }

        List<Parrafo> pagina(PDDocument doc, int p) throws IOException {
            parrafos = new ArrayList<>();
            actual = null;
            setStartPage(p + 1);
            setEndPage(p + 1);
            getText(doc);
            cerrar();
            return parrafos;
        }

        /** Tamaño de letra del texto normal: la mediana ponderada por caracteres. */
        float tamCuerpo(List<Parrafo> ps) {
            List<float[]> l = new ArrayList<>();
            int total = 0;
            for (Parrafo p : ps) {
                l.add(new float[] {p.tam, p.caracteres});
                total += p.caracteres;
            }
            if (total == 0) return 0;
            l.sort((a, b) -> Float.compare(a[0], b[0]));
            int acum = 0;
            for (float[] x : l) {
                acum += (int) x[1];
                if (acum * 2 >= total) return x[0];
            }
            return l.get(l.size() - 1)[0];
        }

        private void cerrar() {
            if (actual != null) {
                int n = actual.texto.length();
                if (n > 0 && actual.texto.charAt(n - 1) == '\u0000') actual.texto.setCharAt(n - 1, '-');
            }
            if (actual != null && actual.texto.toString().trim().length() > 0) {
                actual.negrita = actual.caracteres > 0 && actual.enNegrita * 10 >= actual.caracteres * 8;
                parrafos.add(actual);
            }
            actual = null;
        }

        private Parrafo par() {
            if (actual == null) {
                actual = new Parrafo();
                lineas = 0;
                lineaChars = lineaNegrita = 0;
            }
            return actual;
        }

        @Override protected void writeParagraphStart() { cerrar(); }

        @Override protected void writeParagraphEnd() { cerrar(); }

        @Override protected void writePageStart() { }

        @Override protected void writePageEnd() { cerrar(); }

        @Override protected void writeWordSeparator() {
            Parrafo p = par();
            if (p.texto.length() > 0 && p.texto.charAt(p.texto.length() - 1) != ' ') p.texto.append(' ');
        }

        @Override protected void writeLineSeparator() {
            Parrafo p = par();
            // Una primera línea corta y toda en negrita es un subtítulo: va en su propio párrafo.
            boolean subtitulo = lineas == 0 && lineaChars > 0 && lineaChars <= 80 && lineaNegrita == lineaChars;
            lineas++;
            lineaChars = lineaNegrita = 0;
            if (subtitulo) {
                cerrar();
                return;
            }
            int n = p.texto.length();
            // "pala-\nbra" → "palabra"; si no, las líneas del párrafo se unen con un espacio.
            if (n >= 2 && p.texto.charAt(n - 1) == '-' && Character.isLetter(p.texto.charAt(n - 2))) {
                p.texto.setLength(n - 1);
                p.texto.append('\u0000'); // marca: se decide al ver la siguiente letra
            } else if (n > 0 && p.texto.charAt(n - 1) != ' ') {
                p.texto.append(' ');
            }
        }

        @Override protected void writeString(String texto, List<TextPosition> pos) {
            Parrafo p = par();
            int n = p.texto.length();
            if (n > 0 && p.texto.charAt(n - 1) == '\u0000') {
                // Si sigue en minúscula era un guion de partición; si no, se conserva el guion.
                boolean minuscula = !texto.isEmpty() && Character.isLowerCase(texto.charAt(0));
                p.texto.setLength(n - 1);
                if (!minuscula) p.texto.append("- ");
            }
            p.texto.append(texto);
            for (TextPosition t : pos) {
                float tam = Math.abs(t.getFontSizeInPt());
                if (tam <= 0) tam = t.getHeight();
                if (tam > p.tam) p.tam = tam;
                p.caracteres++;
                lineaChars++;
                String fuente = t.getFont() != null && t.getFont().getName() != null
                        ? t.getFont().getName().toLowerCase(Locale.ROOT) : "";
                if (fuente.contains("bold") || fuente.contains("black") || fuente.contains("heavy")) {
                    p.enNegrita++;
                    lineaNegrita++;
                }
            }
        }

        @Override protected void writeString(String texto) { par().texto.append(texto); }
    }
}
