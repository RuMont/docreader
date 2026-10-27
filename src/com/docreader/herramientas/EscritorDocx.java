package com.docreader.herramientas;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Genera un .docx sencillo: párrafos, títulos, saltos de página e imágenes JPEG. */
final class EscritorDocx {
    static final int NORMAL = 0, TITULO1 = 1, TITULO2 = 2, TITULO3 = 3;

    private final StringBuilder cuerpo = new StringBuilder();
    private final List<byte[]> imagenes = new ArrayList<>();
    private boolean hayContenido;

    void parrafo(int estilo, String texto, boolean negrita) {
        cuerpo.append("<w:p>");
        if (estilo != NORMAL) cuerpo.append("<w:pPr><w:pStyle w:val=\"Ttulo").append(estilo).append("\"/></w:pPr>");
        cuerpo.append("<w:r>");
        if (negrita && estilo == NORMAL) cuerpo.append("<w:rPr><w:b/></w:rPr>");
        cuerpo.append("<w:t xml:space=\"preserve\">");
        esc(cuerpo, texto);
        cuerpo.append("</w:t></w:r></w:p>");
        hayContenido = true;
    }

    void saltoDePagina() {
        if (!hayContenido) return;
        cuerpo.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
    }

    /** Imagen JPEG a lo ancho de la zona de texto, manteniendo su proporción. */
    void imagen(byte[] jpeg, int anchoPx, int altoPx) {
        imagenes.add(jpeg);
        int n = imagenes.size();
        long ancho = 5_760_000L; // 16 cm en EMU (A4 con márgenes de 2,5 cm)
        long alto = ancho * altoPx / Math.max(1, anchoPx);
        long maxAlto = 8_640_000L; // 24 cm
        if (alto > maxAlto) {
            ancho = ancho * maxAlto / alto;
            alto = maxAlto;
        }
        cuerpo.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>")
                .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                .append("<wp:extent cx=\"").append(ancho).append("\" cy=\"").append(alto).append("\"/>")
                .append("<wp:docPr id=\"").append(n).append("\" name=\"Página ").append(n).append("\"/>")
                .append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">")
                .append("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:nvPicPr><pic:cNvPr id=\"").append(n).append("\" name=\"imagen").append(n)
                .append(".jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>")
                .append("<pic:blipFill><a:blip r:embed=\"rIdImg").append(n).append("\"/>")
                .append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(ancho)
                .append("\" cy=\"").append(alto).append("\"/></a:xfrm>")
                .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
                .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        hayContenido = true;
    }

    void escribir(OutputStream destino, String titulo) throws IOException {
        ZipOutputStream z = new ZipOutputStream(destino);
        entrada(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>");
        entrada(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>");
        StringBuilder t = new StringBuilder();
        esc(t, titulo);
        entrada(z, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + t + "</dc:title>"
                + "<dc:creator>DocReader</dc:creator></cp:coreProperties>");

        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdEstilos\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        for (int i = 1; i <= imagenes.size(); i++) {
            rels.append("<Relationship Id=\"rIdImg").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/imagen")
                    .append(i).append(".jpeg\"/>");
        }
        rels.append("</Relationships>");
        entrada(z, "word/_rels/document.xml.rels", rels.toString());
        entrada(z, "word/styles.xml", ESTILOS);
        entrada(z, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">"
                + "<w:body>" + cuerpo
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>");
        for (int i = 0; i < imagenes.size(); i++) {
            z.putNextEntry(new ZipEntry("word/media/imagen" + (i + 1) + ".jpeg"));
            z.write(imagenes.get(i));
            z.closeEntry();
        }
        z.finish();
    }

    private static void entrada(ZipOutputStream z, String nombre, String xml) throws IOException {
        z.putNextEntry(new ZipEntry(nombre));
        z.write(xml.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void esc(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    // Caracteres de control no válidos en XML 1.0 (salvo tabulador) se omiten.
                    if (c >= 0x20 || c == '\t') sb.append(c);
            }
        }
    }

    private static final String ESTILOS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>"
            + "<w:sz w:val=\"22\"/><w:lang w:val=\"es-ES\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
            + titulo(1, 32, 360) + titulo(2, 26, 240) + titulo(3, 24, 200)
            + "</w:styles>";

    private static String titulo(int n, int medioPuntos, int antes) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"Ttulo" + n + "\"><w:name w:val=\"heading " + n + "\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
                + "<w:pPr><w:keepNext/><w:spacing w:before=\"" + antes + "\" w:after=\"120\"/><w:outlineLvl w:val=\""
                + (n - 1) + "\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"" + medioPuntos
                + "\"/></w:rPr></w:style>";
    }
}
