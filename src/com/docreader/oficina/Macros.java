package com.docreader.oficina;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Código de las macros de un documento, solo para leerlo: nunca se ejecuta nada.
 * <ul>
 *   <li>VBA (MS-OVBA) de .xlsm/.docm/.pptm (parte vbaProject.bin) y de .xls/.doc (almacén
 *       _VBA_PROJECT_CUR/VBA o Macros/VBA): el flujo "dir" dice qué módulos hay y dónde empieza
 *       su código, que va comprimido con el algoritmo propio de VBA.</li>
 *   <li>LibreOffice Basic de OpenDocument (Basic/biblioteca/módulo.xml, texto plano en XML).</li>
 * </ul>
 */
final class Macros {
    private Macros() { }

    static final class Modulo {
        final String nombre, codigo, tipo;

        Modulo(String nombre, String codigo, String tipo) {
            this.nombre = nombre;
            this.codigo = codigo;
            this.tipo = tipo;
        }
    }

    private static final String[] PARTES_VBA = {"xl/vbaProject.bin", "word/vbaProject.bin", "ppt/vbaProject.bin"};
    private static final byte[] NOMBRE_VBA = "vbaProject.bin".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NOMBRE_BASIC = "Basic/script-lc.xml".getBytes(StandardCharsets.US_ASCII);

    /**
     * ¿Trae el archivo macros con código? Solo se descomprime el proyecto de macros (pequeño) si
     * existe; en un zip basta buscar su nombre, que va sin comprimir en el directorio central.
     * Los documentos cifrados no se comprueban (habría que descifrarlos otra vez).
     */
    static boolean hay(byte[] datos) {
        try {
            if (Cfb.es(datos)) {
                Cfb cfb = Cfb.leer(datos);
                if (Cripto.esOoxmlCifrado(cfb) || almacenVba(cfb) == null) return false;
            } else if (!contiene(datos, NOMBRE_VBA) && !contiene(datos, NOMBRE_BASIC)) {
                return false;
            }
            return !extraer(datos, null).isEmpty();
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    /** Módulos con código. Lista vacía si el proyecto existe pero está vacío. */
    static List<Modulo> extraer(byte[] datos, String clave) throws Fallo, java.io.IOException {
        if (Cfb.es(datos)) {
            Cfb cfb = Cfb.leer(datos);
            if (Cripto.esOoxmlCifrado(cfb)) return extraer(Cripto.descifrarOoxml(cfb, clave), clave);
            String vba = almacenVba(cfb);
            if (vba == null) throw new Fallo("El documento no tiene macros.");
            return vba(cfb, vba);
        }
        for (String p : PARTES_VBA) {
            byte[] bin = Paquete.leerEntrada(datos, p);
            if (bin != null) {
                Cfb cfb = Cfb.leer(bin);
                String vba = almacenVba(cfb);
                if (vba == null) throw new Fallo("El proyecto de macros está dañado.");
                return vba(cfb, vba);
            }
        }
        Paquete pq = Paquete.leer(new ByteArrayInputStream(datos));
        if (pq.existe("Basic/script-lc.xml")) {
            if (OdfCifrado.cifrado(pq)) pq = OdfCifrado.descifrar(pq, clave);
            return basic(pq);
        }
        throw new Fallo("El documento no tiene macros.");
    }

    /** Ruta del almacén "VBA" (el que contiene el flujo "dir"), o null. */
    private static String almacenVba(Cfb cfb) {
        for (String r : cfb.rutas()) {
            if (r.equals("vba/dir")) return "vba";
            if (r.endsWith("/vba/dir")) return r.substring(0, r.length() - 4);
        }
        return null;
    }

    // --- VBA ---

    private static List<Modulo> vba(Cfb cfb, String almacen) throws Fallo {
        byte[] dirComprimido = cfb.flujo(almacen + "/dir");
        if (dirComprimido == null) throw new Fallo("El proyecto de macros está dañado.");
        byte[] dir = descomprimir(dirComprimido, 0);

        Charset juego = StandardCharsets.ISO_8859_1;
        List<Modulo> modulos = new ArrayList<>();
        String nombre = null, nombreU = null, flujo = null, flujoU = null, tipo = "Módulo";
        long desplazamiento = 0;
        int i = 0;
        while (i + 6 <= dir.length) {
            int id = u16(dir, i);
            long tam = u32(dir, i + 2);
            int datos = i + 6;
            if (id == 0x0009) { // PROJECTVERSION: el campo de tamaño vale 4 pero los datos son 6 bytes
                i = datos + 6;
                continue;
            }
            if (tam < 0 || datos + tam > dir.length) break;
            int t = (int) tam;
            switch (id) {
                case 0x0003: // PROJECTCODEPAGE
                    juego = juego(u16(dir, datos));
                    break;
                case 0x0019: // MODULENAME: empieza un módulo
                    nombre = new String(dir, datos, t, juego);
                    nombreU = flujo = flujoU = null;
                    desplazamiento = 0;
                    tipo = "Módulo";
                    break;
                case 0x0047: nombreU = new String(dir, datos, t, StandardCharsets.UTF_16LE); break;
                case 0x001A: flujo = new String(dir, datos, t, juego); break;
                case 0x0032: flujoU = new String(dir, datos, t, StandardCharsets.UTF_16LE); break;
                case 0x0031: desplazamiento = u32(dir, datos); break;
                case 0x0022: tipo = "Clase o documento"; break;
                case 0x002B: { // fin del módulo
                    String f = flujoU != null && !flujoU.isEmpty() ? flujoU : flujo;
                    byte[] bruto = f == null ? null : cfb.flujo(almacen + "/" + f);
                    if (bruto == null && flujo != null) bruto = cfb.flujo(almacen + "/" + flujo);
                    String n = nombreU != null && !nombreU.isEmpty() ? nombreU : nombre != null ? nombre : f;
                    if (bruto != null && n != null) {
                        String codigo = codigoModulo(bruto, desplazamiento, juego);
                        if (codigo == null) codigo = "' (No se pudo leer el código de este módulo: está dañado.)";
                        codigo = limpiar(codigo);
                        if (!codigo.isEmpty()) modulos.add(new Modulo(n, codigo, tipo));
                    }
                    nombre = nombreU = flujo = flujoU = null;
                    break;
                }
                default:
                    break;
            }
            i = datos + t;
        }
        return modulos;
    }

    /**
     * Código de un módulo a partir de su desplazamiento. Algunos programas escriben mal ese dato:
     * si ahí no hay código, se busca el bloque comprimido cuyo texto empiece por "Attribute VB_Name"
     * (el código fuente siempre empieza así), como hacen otros lectores.
     */
    private static String codigoModulo(byte[] bruto, long desplazamiento, Charset juego) {
        if (desplazamiento >= 0 && desplazamiento < bruto.length) {
            try {
                String s = new String(descomprimir(bruto, (int) desplazamiento), juego);
                if (s.startsWith("Attribute")) return s;
            } catch (Fallo ignorado) { }
        }
        for (int i = 0; i + 3 < bruto.length; i++) {
            // Firma 1 y cabecera de bloque con los bits 12–14 a 011 (MS-OVBA 2.4.1.1.5).
            if (bruto[i] != 1 || (u16(bruto, i + 1) & 0x7000) != 0x3000) continue;
            try {
                byte[] d = descomprimir(bruto, i);
                String s = new String(d, juego);
                if (s.startsWith("Attribute VB_Name")) return s;
            } catch (Fallo ignorado) { }
        }
        return null;
    }

    /**
     * Descompresión de MS-OVBA 2.4.1: un byte de firma (1) y bloques de hasta 4096 bytes; en los
     * comprimidos, cada byte de banderas indica si los 8 elementos siguientes son literales o
     * referencias (desplazamiento, longitud) a lo ya descomprimido dentro del mismo bloque.
     */
    static byte[] descomprimir(byte[] d, int inicio) throws Fallo {
        if (inicio >= d.length || d[inicio] != 1) throw new Fallo("Código de macro dañado.");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, (d.length - inicio) * 2));
        byte[] buf = new byte[Math.min(64 << 20, Math.max(4096, (d.length - inicio) * 8))];
        int n = 0, p = inicio + 1;
        while (p + 2 <= d.length) {
            int cab = u16(d, p);
            int tamBloque = (cab & 0x0FFF) + 3;
            boolean comprimido = (cab & 0x8000) != 0;
            int finBloque = Math.min(d.length, p + tamBloque);
            p += 2;
            int inicioDescomp = n;
            if (n + 4096 > buf.length) {
                out.write(buf, 0, n);
                // Se conserva lo justo para las referencias: no salen del bloque actual.
                n = 0;
                inicioDescomp = 0;
            }
            if (!comprimido) {
                int c = Math.min(4096, d.length - p);
                System.arraycopy(d, p, buf, n, c);
                n += c;
                p += 4096;
                continue;
            }
            while (p < finBloque) {
                int banderas = d[p++] & 0xFF;
                for (int b = 0; b < 8 && p < finBloque; b++) {
                    if ((banderas & (1 << b)) == 0) {
                        if (n - inicioDescomp >= 4096) break;
                        buf[n++] = d[p++];
                    } else {
                        if (p + 2 > finBloque) {
                            p = finBloque;
                            break;
                        }
                        int ficha = u16(d, p);
                        p += 2;
                        int dif = n - inicioDescomp;
                        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(0, dif - 1)));
                        int mascaraLargo = 0xFFFF >>> bits;
                        int largo = (ficha & mascaraLargo) + 3;
                        int desp = (ficha >>> (16 - bits)) + 1;
                        if (desp > dif) throw new Fallo("Código de macro dañado.");
                        for (int k = 0; k < largo && n - inicioDescomp < 4096; k++, n++) buf[n] = buf[n - desp];
                    }
                }
            }
            p = finBloque;
        }
        out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Quita las líneas "Attribute VB_…" internas y los saltos sobrantes del principio y del final. */
    private static String limpiar(String codigo) {
        StringBuilder sb = new StringBuilder(codigo.length());
        // Algunos programas rellenan el final con bytes nulos.
        for (String l : Codigo.normalizarSaltos(codigo.replace("\u0000", "")).split("\n", -1)) {
            if (l.startsWith("Attribute ")) continue; // metadatos que el editor de VBA tampoco muestra
            sb.append(l).append('\n');
        }
        String s = sb.toString();
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == ' ')) a++;
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static Charset juego(int pagina) {
        String n;
        switch (pagina) {
            case 65001: return StandardCharsets.UTF_8;
            case 1200: return StandardCharsets.UTF_16LE;
            case 932: n = "windows-31j"; break;
            case 936: n = "GBK"; break;
            case 949: n = "x-windows-949"; break;
            case 950: n = "x-windows-950"; break;
            case 10000: n = "x-MacRoman"; break;
            default: n = "windows-" + pagina;
        }
        try {
            return Charset.forName(n);
        } catch (Exception e) {
            return Charset.forName("windows-1252");
        }
    }

    // --- LibreOffice Basic ---

    private static List<Modulo> basic(Paquete pq) {
        List<Modulo> modulos = new ArrayList<>();
        for (String r : pq.rutas()) {
            String l = r.toLowerCase(Locale.ROOT);
            if (!l.startsWith("basic/") || !l.endsWith(".xml") || l.endsWith("/script-lb.xml")
                    || l.equals("basic/script-lc.xml")) {
                continue;
            }
            byte[] b = pq.bytes(r);
            if (b == null) continue;
            String xml = new String(b, StandardCharsets.UTF_8);
            int ini = xml.indexOf("<script:module");
            if (ini < 0) continue;
            int cierreInicio = xml.indexOf('>', ini);
            int fin = xml.indexOf("</script:module>", cierreInicio);
            if (cierreInicio < 0 || fin < 0) continue;
            String codigo = desescapar(xml.substring(cierreInicio + 1, fin)).trim();
            if (codigo.isEmpty()) continue;
            String biblioteca = r.substring(6, Math.max(6, r.lastIndexOf('/')));
            String modulo = r.substring(r.lastIndexOf('/') + 1, r.length() - 4);
            modulos.add(new Modulo(biblioteca.equals("Standard") ? modulo : biblioteca + "." + modulo,
                    codigo, "Basic"));
        }
        return modulos;
    }

    private static String desescapar(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int pc = c == '&' ? s.indexOf(';', i) : -1;
            if (pc < 0 || pc - i > 10) {
                sb.append(c);
                continue;
            }
            String e = s.substring(i + 1, pc);
            String r = null;
            switch (e) {
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "amp": r = "&"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                default:
                    try {
                        if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                        else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                    } catch (IllegalArgumentException ignorado) { }
            }
            if (r == null) {
                sb.append(c);
            } else {
                sb.append(r);
                i = pc;
            }
        }
        return sb.toString();
    }

    // --- Vista ---

    /** Documento del visor de código con una pestaña por módulo. */
    static Html.Documento documento(List<Modulo> modulos) throws Fallo {
        if (modulos.isEmpty()) {
            throw new Fallo("El documento tiene un proyecto de macros, pero no contiene código "
                    + "(o solo tiene código compilado, que no se puede mostrar).");
        }
        String[] nombres = new String[modulos.size()], cuerpos = new String[modulos.size()];
        Html.Documento primero = null;
        for (int k = 0; k < modulos.size(); k++) {
            Modulo m = modulos.get(k);
            Html.Documento d = Codigo.convertir(m.codigo.getBytes(StandardCharsets.UTF_8), Codigo.VB);
            if (primero == null) primero = d;
            nombres[k] = m.nombre;
            cuerpos[k] = "<p class=nota>" + Html.esc(m.tipo) + " · solo lectura: DocReader no ejecuta macros.</p>"
                    + d.cuerpo;
        }
        Html.Documento r = new Html.Documento("", primero.css, primero.viewport, primero.clase, nombres, cuerpos);
        r.cssNoche = primero.cssNoche;
        r.cssAjustar = primero.cssAjustar;
        return r;
    }

    // --- Utilidades ---

    private static boolean contiene(byte[] d, byte[] patron) {
        int n = d.length - patron.length;
        externo:
        for (int i = 0; i <= n; i++) {
            if (d[i] != patron[0]) continue;
            for (int j = 1; j < patron.length; j++) if (d[i + j] != patron[j]) continue externo;
            return true;
        }
        return false;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }
}
