package com.docreader.oficina;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convierte una presentación de PowerPoint 97–2003 (.ppt, [MS-PPT]) en HTML con el mismo aspecto
 * que {@link Pptx}: cada diapositiva es una caja de 960 px de ancho con sus formas en posición
 * absoluta (escalando desde las unidades maestras, 576 por pulgada).
 * <p>
 * El flujo "PowerPoint Document" es una sucesión de registros (cabecera de 8 bytes). La versión
 * vigente se encuentra siguiendo la cadena de UserEditAtom desde el flujo "Current User": cada
 * edición trae un directorio de persistencia (identificador → desplazamiento) que tapa a los
 * anteriores. Si la cadena está rota se recorre el flujo de forma lineal.
 * Sin dependencias de Android, para poder probarlo en la JVM.
 */
final class Ppt {
    private static final double ANCHO = 960;
    private static final String VIEWPORT = "width=1000";
    private static final String CIFRADO_RARO = "Esta presentación está cifrada de una forma que no se puede abrir.";
    /** Contraseñas que PowerPoint y Office usan cuando el archivo se cifró sin pedir ninguna. */
    private static final String[] CLAVES_POR_DEFECTO = {"/01Hannes Ruescher/01", "VelvetSweatshop"};
    private static final String DANADA = "La presentación está dañada y no se puede leer.";

    private static final String CSS =
            "body.ppt{padding:6px 20px 30px;background:#e4e4e4}"
            + ".num{font-size:15px;color:#555;margin:16px 0 6px}"
            + ".diapo{position:relative;width:960px;overflow:hidden;background:#fff;"
            + "border:1px solid #c4c4c4;line-height:1.2}"
            + ".f{position:absolute;box-sizing:border-box}"
            + ".tx{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;"
            + "box-sizing:border-box;overflow-wrap:break-word;color:#000}"
            + ".pa{white-space:pre-wrap;margin:0}"
            + ".im{position:absolute;left:0;top:0;right:0;bottom:0;background-size:100% 100%;"
            + "background-repeat:no-repeat;background-position:center}"
            + ".fijo{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;"
            + "justify-content:center;border:1px dashed #999;color:#666;font-size:16px;"
            + "background:rgba(240,240,240,.6)}"
            + "svg.f{overflow:visible}"
            + ".tarjeta{padding:28px 36px;font-size:24px;line-height:1.3;color:#000}"
            + ".tarjeta h2{font-size:36px;margin:0 0 18px}"
            + ".tarjeta p{margin:0 0 10px;white-space:pre-wrap}"
            + ".extra{width:960px;box-sizing:border-box;background:#fff;margin-top:4px;padding:8px 14px;"
            + "font-size:15px;white-space:pre-wrap;box-shadow:0 1px 5px rgba(0,0,0,.3)}";

    // --- Tipos de registro ---
    private static final int DOCUMENTO = 0x03E8, ATOMO_DOC = 0x03E9, DIAPOSITIVA = 0x03EE, ATOMO_DIAPO = 0x03EF,
            ENTORNO = 0x03F2, PERSISTENCIA = 0x03F3, PATRON = 0x03F8, INFO_PASE = 0x03F9, GRUPO_DIBUJO = 0x040B,
            DIBUJO = 0x040C, FUENTES = 0x07D5, ESQUEMA = 0x07F0, REF_TEXTO = 0x0F9E, CAB_TEXTO = 0x0F9F,
            CARACTERES = 0x0FA0, ESTILO_TEXTO = 0x0FA1, ESTILO_PATRON = 0x0FA3, BYTES = 0x0FA8, FUENTE = 0x0FB7,
            LISTA_TEXTO = 0x0FF0, EDICION = 0x0FF5, USUARIO = 0x0FF6, DIRECTORIO = 0x1772, CIFRADO = 0x2F14;
    // --- Registros de dibujo (OfficeArt) ---
    private static final int DGG = 0xF000, BSTORE = 0xF001, DG = 0xF002, SPGR = 0xF003, SP = 0xF004,
            FBSE = 0xF007, FSPGR = 0xF009, FSP = 0xF00A, FOPT = 0xF00B, CAJA_TEXTO = 0xF00D, ANCLA_HIJO = 0xF00F,
            ANCLA = 0xF010, DATOS_CLIENTE = 0xF011, MARCADOR = 0x0BC3;

    /** Tope de registros leídos en total: un archivo malicioso no puede hacernos trabajar sin fin. */
    private static final int MAX_REGISTROS = 2_000_000;

    /** Esquema de colores por defecto (fondo, texto, sombra, título, relleno, énfasis, vínculos). */
    private static final int[] ESQUEMA_DEFECTO = {
        0xFFFFFF, 0x000000, 0x808080, 0x000000, 0xBBE0E3, 0x333399, 0x009999, 0x99CC00};

    /** Cabecera de un registro: el cuerpo es [ini, fin), ya recortado al contenedor. */
    private static final class Reg {
        final int ver, inst, tipo, pos, ini, fin;

        Reg(int ver, int inst, int tipo, int pos, int ini, int fin) {
            this.ver = ver;
            this.inst = inst;
            this.tipo = tipo;
            this.pos = pos;
            this.ini = ini;
            this.fin = fin;
        }

        int largo() { return fin - ini; }
    }

    /** Se lanza al pasar del tope de registros; se recoge como archivo dañado. */
    private static final class Agotado extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Agotado() { super(null, null, false, false); }
    }

    /** Propiedades de párrafo (TextPFException) y de carácter (TextCFException). Null = heredar. */
    private static final class Est {
        Integer vineta, usaFuente, usaColor, usaTam, carVineta, fuenteVineta, tamVineta, colorVineta;
        Integer alin, interlinea, antes, despues, margen, sangria;
        Integer negrita, cursiva, subrayado, fuente, tam, color, pos;

        /** Copia en this los campos que aún no tiene. */
        void heredar(Est o) {
            if (o == null) return;
            if (vineta == null) vineta = o.vineta;
            if (usaFuente == null) usaFuente = o.usaFuente;
            if (usaColor == null) usaColor = o.usaColor;
            if (usaTam == null) usaTam = o.usaTam;
            if (carVineta == null) carVineta = o.carVineta;
            if (fuenteVineta == null) fuenteVineta = o.fuenteVineta;
            if (tamVineta == null) tamVineta = o.tamVineta;
            if (colorVineta == null) colorVineta = o.colorVineta;
            if (alin == null) alin = o.alin;
            if (interlinea == null) interlinea = o.interlinea;
            if (antes == null) antes = o.antes;
            if (despues == null) despues = o.despues;
            if (margen == null) margen = o.margen;
            if (sangria == null) sangria = o.sangria;
            if (negrita == null) negrita = o.negrita;
            if (cursiva == null) cursiva = o.cursiva;
            if (subrayado == null) subrayado = o.subrayado;
            if (fuente == null) fuente = o.fuente;
            if (tam == null) tam = o.tam;
            if (color == null) color = o.color;
            if (pos == null) pos = o.pos;
        }
    }

    /** Un texto: tipo (título, cuerpo…), caracteres y, si lo hay, su StyleTextPropAtom. */
    private static final class Bloque {
        int tipo = 4;
        String texto = "";
        Reg estilo;
    }

    /** Entrada de una SlideListWithText: la diapositiva (o patrón) y los textos que la siguen. */
    private static final class Entrada {
        int persist, id;
        final List<Bloque> bloques = new ArrayList<>();
    }

    /** Patrón (MainMaster) o patrón de título (Slide): esquema, estilos de texto y formas. */
    private static final class Patron {
        Reg reg, dibujo;
        Patron padre;
        int flags = 7;
        int[] esquema;
        final Est[][] estilos = new Est[9][];
        /** Propiedades de cada forma del patrón por spid, para los marcadores que la citan. */
        final Map<Integer, Map<Integer, Integer>> formas = new HashMap<>();
    }

    /** Forma de OfficeArt ya leída. */
    private static final class Forma {
        int tipo, spid, flags, marcador = -1;
        final Map<Integer, Integer> props = new HashMap<>();
        Map<Integer, Integer> maestra;
        double[] cliente, hijo, grupo;
        Reg caja;
    }

    /** Transformación de coordenadas de un grupo a la diapositiva: X = tx + sx·x. */
    private static final class Transf {
        static final Transf IDENTIDAD = new Transf(1, 1, 0, 0);
        final double sx, sy, tx, ty;

        Transf(double sx, double sy, double tx, double ty) {
            this.sx = sx;
            this.sy = sy;
            this.tx = tx;
            this.ty = ty;
        }

        double[] aplicar(double[] r) { return new double[] {tx + sx * r[0], ty + sy * r[1], sx * r[2], sy * r[3]}; }
    }

    /** Contexto de la diapositiva o patrón que se está dibujando. */
    private static final class Ctx {
        int[] esquema = ESQUEMA_DEFECTO;
        Patron patron;
        Entrada entrada;
        boolean[] usados;
        boolean esPatron;
        final Set<String> textos = new HashSet<>();
        final List<Bloque> sueltos = new ArrayList<>();
    }

    private final Cfb cfb;
    private final byte[] d;
    private final String base;
    private byte[] imagenes;
    /** Cifrado: contraseña dada, aviso del "Current User", clave validada y fotos ya descifradas. */
    private String clave;
    private boolean tokenCifrado;
    private Cripto.ClaveRc4 rc4;
    private final Set<Integer> fotosDescifradas = new HashSet<>();
    private boolean danado;
    private int registros;
    private final Map<Integer, Integer> persist = new HashMap<>();
    private int docRef = -1;
    private double ancho = 5760, alto = 4320, k = ANCHO / 5760;
    private final List<String> fuentes = new ArrayList<>();
    private final Map<Integer, Integer> porDefecto = new HashMap<>();
    /** Imágenes del BStore: {0 = en el flujo principal / 1 = en "Pictures", desplazamiento}. */
    private final List<int[]> blips = new ArrayList<>();
    private final Map<Integer, String> clasesImagen = new HashMap<>();
    private final StringBuilder cssImagenes = new StringBuilder();
    /** slideId del patrón → identificador de persistencia. */
    private final Map<Integer, Integer> idPatrones = new HashMap<>();
    private final Map<Integer, Patron> patrones = new HashMap<>();
    private final Patron estilosDoc = new Patron();
    private final List<Html.Titulo> indice = new ArrayList<>();
    private int hechas;

    private Ppt(Cfb cfb, byte[] d, String base) {
        this.cfb = cfb;
        this.d = d;
        this.base = base;
    }

    static Html.Documento convertir(Cfb cfb) throws Fallo { return convertir(cfb, null); }

    /**
     * Igual, con la contraseña de una presentación cifrada (RC4 CryptoAPI). Si falta o no es
     * correcta lanza {@link FalloClave}; el flujo "EncryptedSummary" no hace falta.
     */
    static Html.Documento convertir(Cfb cfb, String clave) throws Fallo {
        // Los archivos guardados "para PowerPoint 95 y 97" llevan la versión 97 en un almacén aparte.
        String base = cfb.existe("PP97_DUALSTORAGE/PowerPoint Document") ? "PP97_DUALSTORAGE/" : "";
        byte[] d = cfb.flujo(base + "PowerPoint Document");
        byte[] cu = cfb.flujo(base + "Current User");
        boolean atomoUsuario = cu != null && cu.length >= 20 && Cfb.u16(cu, 2) == USUARIO;
        if (cfb.existe("PP40") || (d != null && !atomoUsuario && base.isEmpty() && cfb.existe("Header"))) {
            throw new Fallo("Esta presentación es de PowerPoint 95 o anterior y no se puede abrir. "
                    + "Guárdala en un formato más reciente con PowerPoint o LibreOffice.");
        }
        if (d == null || d.length < 8) throw new Fallo("El archivo no es una presentación de PowerPoint válida.");
        Ppt x = new Ppt(cfb, d, base);
        x.clave = clave;
        StringBuilder sb = new StringBuilder();
        try {
            x.procesar(atomoUsuario ? cu : null, sb);
        } catch (RuntimeException | StackOverflowError e) {
            if (x.hechas == 0) throw new Fallo(x.tokenCifrado && x.rc4 == null ? CIFRADO_RARO : DANADA);
            x.danado = true;
        }
        if (x.danado) sb.append(Html.NOTA_DANADA);
        return new Html.Documento(sb.toString(), CSS + x.cssImagenes, VIEWPORT, "ppt").conIndice(x.indice);
    }

    // --- Registros ---

    /** Registro que empieza en "off" sin pasar de "limite", o null si no cabe su cabecera. */
    private Reg reg(byte[] b, int off, int limite, boolean marcar) {
        if (off < 0 || off > limite - 8 || limite > b.length) return null;
        if (++registros > MAX_REGISTROS) throw new Agotado();
        int vi = Cfb.u16(b, off);
        long fin = (long) off + 8 + (Cfb.i32(b, off + 4) & 0xFFFFFFFFL);
        if (fin > limite) {
            if (marcar) danado = true;
            fin = limite;
        }
        return new Reg(vi & 0xF, vi >>> 4, Cfb.u16(b, off + 2), off, off + 8, (int) fin);
    }

    private Reg reg(int off) { return reg(d, off, d.length, true); }

    /** Hijos de un contenedor (vacío si no lo es). */
    private List<Reg> hijos(Reg r) {
        List<Reg> l = new ArrayList<>();
        if (r == null || r.ver != 0xF) return l;
        int off = r.ini;
        Reg h;
        while ((h = reg(d, off, r.fin, true)) != null) {
            l.add(h);
            off = h.fin;
        }
        return l;
    }

    private static Reg hijo(List<Reg> l, int tipo) {
        for (Reg r : l) if (r.tipo == tipo) return r;
        return null;
    }

    /** Objeto persistente del tipo pedido, o null. */
    private Reg objeto(Integer id, int tipo) {
        Integer off = id == null ? null : persist.get(id);
        Reg r = off == null ? null : reg(off);
        return r != null && r.tipo == tipo ? r : null;
    }

    private int i32(int off, int fin) { return off >= 0 && off + 4 <= fin ? Cfb.i32(d, off) : 0; }

    private int u16(int off, int fin) { return off >= 0 && off + 2 <= fin ? Cfb.u16(d, off) : 0; }

    // --- Documento ---

    private void procesar(byte[] cu, StringBuilder sb) throws Fallo {
        boolean ok = cu != null && ediciones(cu);
        List<Reg> nivel = null;
        if (!ok) {
            // Sin "Current User" válido: se busca la última edición recorriendo el flujo.
            nivel = superior();
            for (int i = nivel.size() - 1; i >= 0 && !ok; i--) {
                if (nivel.get(i).tipo == EDICION) ok = cadena(nivel.get(i).pos);
            }
            danado = true;
        }
        Reg doc = ok ? objeto(docRef, DOCUMENTO) : null;
        if (doc == null) {
            danado = true;
            if (nivel == null) nivel = superior();
            for (Reg r : nivel) if (r.tipo == DOCUMENTO) doc = r;
        }
        List<Reg> sueltas = new ArrayList<>();
        if (doc == null || persist.isEmpty()) {
            if (nivel == null) nivel = superior();
            for (Reg r : nivel) if (r.tipo == DIAPOSITIVA) sueltas.add(r);
        }
        if (doc == null && sueltas.isEmpty()) throw new Fallo(DANADA);

        List<Entrada> lista = new ArrayList<>();
        if (doc != null) documento(doc, lista);
        k = ANCHO / ancho;
        if (lista.isEmpty()) {
            for (Reg r : sueltas) {
                Entrada e = new Entrada();
                e.persist = -1;
                lista.add(e);
            }
        }
        if (lista.isEmpty()) {
            sb.append("<p class=nota>La presentación no tiene diapositivas.</p>");
            return;
        }
        int total = lista.size();
        boolean emparejar = persist.isEmpty() && sueltas.size() == total;
        for (int i = 0; i < total; i++) {
            Entrada e = lista.get(i);
            int antes = sb.length();
            try {
                Reg r = emparejar || e.persist < 0 ? (i < sueltas.size() ? sueltas.get(i) : null)
                        : objeto(e.persist, DIAPOSITIVA);
                diapositiva(r, e, i + 1, total, sb);
            } catch (RuntimeException | StackOverflowError ex) {
                danado = true;
                sb.setLength(antes);
                if (ex instanceof Agotado) {
                    sb.append("<p class=nota>La presentación es demasiado compleja: no se muestran más diapositivas.</p>");
                    break;
                }
                tarjeta(e, i + 1, total, false, sb);
            }
            hechas++;
        }
    }

    /** Registros de primer nivel del flujo, uno tras otro hasta donde se puedan leer. */
    private List<Reg> superior() {
        List<Reg> l = new ArrayList<>();
        int off = 0;
        Reg r;
        while ((r = reg(off)) != null && l.size() < 200_000) {
            l.add(r);
            off = r.fin;
        }
        return l;
    }

    /** Sigue la cadena de ediciones desde el CurrentUserAtom. */
    private boolean ediciones(byte[] cu) throws Fallo {
        tokenCifrado = Cfb.i32(cu, 12) == 0xF3D1C4DF;
        boolean ok = cadena(Cfb.i32(cu, 16));
        if (tokenCifrado && rc4 == null) throw new Fallo(ok ? CIFRADO_RARO : DANADA);
        return ok;
    }

    /** Lee las ediciones desde "inicio" hacia atrás y monta el directorio de persistencia. */
    private boolean cadena(int inicio) throws Fallo {
        List<Reg> eds = new ArrayList<>();
        Set<Integer> vistos = new HashSet<>();
        int off = inicio;
        boolean rota = false;
        while (eds.size() < 10_000) {
            Reg r = vistos.add(off) ? reg(off) : null;
            if (r == null || r.tipo != EDICION || r.largo() < 28) {
                rota = true;
                break;
            }
            eds.add(r);
            off = Cfb.i32(d, r.ini + 8);
            if (off <= 0) break;
        }
        if (eds.isEmpty()) return false;
        persist.clear();
        if (rota) {
            // Cadena rota o con ciclos: se parte de todos los directorios del flujo, en orden.
            danado = true;
            for (Reg r : superior()) if (r.tipo == DIRECTORIO) directorio(r);
        }
        // De la más antigua a la más reciente: las posteriores tapan a las anteriores.
        for (int i = eds.size() - 1; i >= 0; i--) {
            Reg dir = reg(Cfb.i32(d, eds.get(i).ini + 12));
            if (dir == null || dir.tipo != DIRECTORIO) danado = true;
            else directorio(dir);
        }
        Reg ultima = eds.get(0);
        docRef = Cfb.i32(d, ultima.ini + 16);
        if (ultima.largo() >= 32) {
            int id = Cfb.i32(d, ultima.ini + 28);
            Reg cifrado = id != 0 ? objeto(id, CIFRADO) : null;
            if (cifrado != null) descifrar(cifrado);
        }
        return true;
    }

    // --- Cifrado ([MS-PPT] 2.3.7: RC4 CryptoAPI) ---

    /**
     * Valida la contraseña con el CryptSession10Container y descifra en el sitio cada objeto
     * persistente: RC4 con el número de bloque igual a su identificador, desde su cabecera.
     */
    private void descifrar(Reg sesion) throws Fallo {
        int v = sesion.ini;
        if (v + 12 > sesion.fin) throw new Fallo(CIFRADO_RARO);
        int mayor = Cfb.u16(d, v), menor = Cfb.u16(d, v + 2);
        if (mayor < 2 || mayor > 4 || menor != 2) throw new Fallo(CIFRADO_RARO);
        byte[] info = java.util.Arrays.copyOfRange(d, sesion.ini, sesion.fin);
        // Tamaños de cabecera, sal y hash razonables antes de dárselos a Cripto.
        long cab = Cfb.i32(info, 8) & 0xFFFFFFFFL, ver = 12 + cab;
        if (cab < 32 || ver + 40 > info.length || Cfb.i32(info, (int) ver) != 16) throw new Fallo(CIFRADO_RARO);
        int hash = Cfb.i32(info, (int) ver + 36);
        if (hash < 16 || hash > 32 || ver + 40 + hash > info.length) throw new Fallo(CIFRADO_RARO);
        for (String c : clave != null ? new String[] {clave} : CLAVES_POR_DEFECTO) {
            rc4 = Cripto.rc4CryptoApi(info, 4, c);
            if (rc4 != null) break;
        }
        if (rc4 == null) throw FalloClave.para(clave);
        Set<Integer> hechos = new HashSet<>();
        hechos.add(sesion.pos);
        for (Map.Entry<Integer, Integer> e : persist.entrySet()) {
            int off = e.getValue();
            if (off < 0 || off > d.length - 8 || !hechos.add(off)) continue;
            if (++registros > MAX_REGISTROS) throw new Agotado();
            Cripto.Rc4 r = rc4.bloque(e.getKey());
            r.aplicar(d, off, 8);
            long n = Math.min(Cfb.i32(d, off + 4) & 0xFFFFFFFFL, d.length - off - 8L);
            r.aplicar(d, off + 8, (int) n);
        }
    }

    /**
     * Descifra una imagen del flujo "Pictures": cabecera, identificadores, etiqueta (o cabecera
     * de metarchivo) y datos van cada uno con el RC4 del bloque 0 recién empezado.
     */
    private void descifrarFoto(byte[] p, int off) {
        if (off < 0 || off > p.length - 8 || !fotosDescifradas.add(off)) return;
        trozo(p, off, 8);
        int inst = Cfb.u16(p, off) >>> 4, tipo = Cfb.u16(p, off + 2);
        long fin = Math.min(off + 8L + (Cfb.i32(p, off + 4) & 0xFFFFFFFFL), p.length);
        int o = off + 8;
        for (int i = (inst & 1) != 0 ? 2 : 1; i > 0; i--, o += 16) trozo(p, o, 16);
        int extra = tipo >= 0xF01A && tipo <= 0xF01C ? 34 : 1;
        trozo(p, o, extra);
        o += extra;
        trozo(p, o, (int) (fin - o));
    }

    private void trozo(byte[] p, int off, int n) {
        n = Math.min(n, p.length - off);
        if (off >= 0 && n > 0) rc4.bloque(0).aplicar(p, off, n);
    }

    /** PersistDirectoryAtom: grupos de identificadores consecutivos con sus desplazamientos. */
    private void directorio(Reg dir) {
        int p = dir.ini;
        while (p + 4 <= dir.fin) {
            int v = Cfb.i32(d, p), id = v & 0xFFFFF, n = v >>> 20;
            p += 4;
            for (int j = 0; j < n && p + 4 <= dir.fin; j++, p += 4) persist.put(id + j, Cfb.i32(d, p));
        }
    }

    private void documento(Reg doc, List<Entrada> diapositivas) {
        List<Entrada> maestras = new ArrayList<>();
        for (Reg r : hijos(doc)) {
            switch (r.tipo) {
                case ATOMO_DOC: {
                    int w = i32(r.ini, r.fin), h = i32(r.ini + 4, r.fin);
                    if (w > 50 && h > 50 && w < 1_000_000 && h < 1_000_000) {
                        ancho = w;
                        alto = h;
                    }
                    break;
                }
                case LISTA_TEXTO:
                    if (r.inst == 0) listaTexto(r, diapositivas);
                    else if (r.inst == 1) listaTexto(r, maestras);
                    break;
                case ENTORNO:
                    for (Reg h : hijos(r)) {
                        if (h.tipo == FUENTES) {
                            for (Reg f : hijos(h)) {
                                if (f.tipo != FUENTE) continue;
                                int n = 0;
                                while (n < 32 && f.ini + 2 * n + 2 <= f.fin && Cfb.u16(d, f.ini + 2 * n) != 0) n++;
                                fuentes.add(new String(d, f.ini, 2 * n, StandardCharsets.UTF_16LE));
                            }
                        } else if (h.tipo == ESTILO_PATRON) {
                            estiloPatron(h, estilosDoc);
                        }
                    }
                    break;
                case GRUPO_DIBUJO: grupoDibujo(r); break;
                default: break;
            }
        }
        for (Entrada e : maestras) idPatrones.put(e.id, e.persist);
    }

    /** SlideListWithText: SlidePersistAtom seguidos de los textos de esa diapositiva. */
    private void listaTexto(Reg r, List<Entrada> l) {
        Entrada e = null;
        Bloque b = null;
        for (Reg h : hijos(r)) {
            switch (h.tipo) {
                case PERSISTENCIA:
                    e = new Entrada();
                    e.persist = i32(h.ini, h.fin);
                    e.id = i32(h.ini + 12, h.fin);
                    l.add(e);
                    b = null;
                    break;
                case CAB_TEXTO:
                    if (e == null) break;
                    b = new Bloque();
                    b.tipo = i32(h.ini, h.fin);
                    e.bloques.add(b);
                    break;
                case CARACTERES: case BYTES:
                    if (b != null) b.texto = texto(h);
                    break;
                case ESTILO_TEXTO:
                    if (b != null) b.estilo = h;
                    break;
                default: break;
            }
        }
    }

    private String texto(Reg r) {
        if (r.tipo == BYTES) return new String(d, r.ini, r.largo(), StandardCharsets.ISO_8859_1);
        return new String(d, r.ini, r.largo() & ~1, StandardCharsets.UTF_16LE);
    }

    /** DrawingGroup: almacén de imágenes (BStore) y propiedades por defecto de las formas. */
    private void grupoDibujo(Reg r) {
        for (Reg dgg : hijos(r)) {
            if (dgg.tipo != DGG) continue;
            for (Reg h : hijos(dgg)) {
                if (h.tipo == FOPT) {
                    propiedades(h, porDefecto);
                } else if (h.tipo == BSTORE) {
                    for (Reg e : hijos(h)) {
                        if (e.tipo == FBSE && e.largo() >= 36) {
                            int nombre = d[e.ini + 33] & 0xFF, dentro = e.ini + 36 + nombre;
                            if (dentro + 8 <= e.fin) blips.add(new int[] {0, dentro});
                            else blips.add(new int[] {1, Cfb.i32(d, e.ini + 28)});
                        } else {
                            blips.add(new int[] {0, e.pos});
                        }
                    }
                }
            }
        }
    }

    // --- Patrones ---

    private Patron patron(int id) {
        if (patrones.containsKey(id)) return patrones.get(id);
        patrones.put(id, null); // evita ciclos patrón de título ↔ patrón
        Integer persistId = idPatrones.get(id);
        Reg r = objeto(persistId, PATRON);
        if (r == null) r = objeto(persistId, DIAPOSITIVA);
        if (r == null) return null;
        Patron p = new Patron();
        p.reg = r;
        int padre = 0;
        for (Reg h : hijos(r)) {
            switch (h.tipo) {
                case ATOMO_DIAPO:
                    padre = i32(h.ini + 12, h.fin);
                    p.flags = u16(h.ini + 20, h.fin);
                    break;
                case ESQUEMA: if (h.inst == 1) p.esquema = esquema(h); break;
                case ESTILO_PATRON: estiloPatron(h, p); break;
                case DIBUJO: p.dibujo = h; formasPatron(h, p, 0); break;
                default: break;
            }
        }
        if (r.tipo == DIAPOSITIVA && padre != 0 && padre != id) p.padre = patron(padre);
        if (p.esquema == null) p.esquema = p.padre != null ? p.padre.esquema : ESQUEMA_DEFECTO;
        patrones.put(id, p);
        return p;
    }

    /** Patrón de una diapositiva; si su referencia no vale, el primero que haya. */
    private Patron patronDe(int id) {
        Patron p = idPatrones.containsKey(id) ? patron(id) : null;
        for (Integer otro : idPatrones.keySet()) {
            if (p != null) break;
            p = patron(otro);
        }
        return p;
    }

    /** Recoge las propiedades de las formas del patrón (por spid) para heredarlas. */
    private void formasPatron(Reg r, Patron p, int prof) {
        if (prof > 12) return;
        for (Reg h : hijos(r)) {
            if (h.tipo == SP) {
                Forma f = leerForma(h);
                p.formas.put(f.spid, f.props);
            } else if (h.ver == 0xF) {
                formasPatron(h, p, prof + 1);
            }
        }
    }

    private int[] esquema(Reg r) {
        int[] e = new int[8];
        for (int i = 0; i < 8; i++) {
            int o = r.ini + 4 * i;
            if (o + 3 > r.fin) return null;
            e[i] = (d[o] & 0xFF) << 16 | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF);
        }
        return e;
    }

    /** TextMasterStyleAtom: hasta 5 niveles de propiedades de párrafo y carácter. */
    private void estiloPatron(Reg r, Patron p) {
        if (r.inst < 0 || r.inst >= 9) return;
        Est[] niveles = new Est[5];
        int n = u16(r.ini, r.fin), off = r.ini + 2;
        for (int i = 0; i < n && i < 5 && off < r.fin; i++) {
            int lv = i;
            if (r.inst >= 5) {
                lv = Math.min(4, u16(off, r.fin));
                off += 2;
            }
            Est e = new Est();
            off = leerPf(off, r.fin, e);
            if (off < 0) break;
            off = leerCf(off, r.fin, e);
            if (off < 0) break;
            niveles[lv] = e;
        }
        p.estilos[r.inst] = niveles;
    }

    /** Estilo de patrón del tipo de texto y nivel, subiendo por patrones y tipos de los que hereda. */
    private Est estiloHeredado(Patron p, int tipo, int nivel) {
        Est r = new Est();
        int base = tipo == 6 ? 0 : tipo == 5 || tipo == 7 || tipo == 8 ? 1 : tipo == 3 ? 4 : tipo;
        for (int vuelta = 0; p != null && vuelta < 4; vuelta++, p = p.padre) añadirEstilo(r, p, tipo, base, nivel);
        añadirEstilo(r, estilosDoc, tipo, base, nivel);
        return r;
    }

    private static void añadirEstilo(Est r, Patron p, int tipo, int base, int nivel) {
        for (int t : new int[] {tipo, base}) {
            if (t < 0 || t >= 9 || p.estilos[t] == null) continue;
            for (int lv = nivel; lv >= 0; lv--) r.heredar(p.estilos[t][lv]);
        }
    }

    // --- Propiedades de texto ---

    private int leerPf(int off, int fin, Est e) {
        if (off + 4 > fin) return -1;
        int m = Cfb.i32(d, off);
        off += 4;
        int[] v = new int[1];
        if ((m & 0xF) != 0) {
            if ((off = leer(off, fin, 2, v)) < 0) return -1;
            if ((m & 1) != 0) e.vineta = v[0] & 1;
            if ((m & 2) != 0) e.usaFuente = v[0] >> 1 & 1;
            if ((m & 4) != 0) e.usaColor = v[0] >> 2 & 1;
            if ((m & 8) != 0) e.usaTam = v[0] >> 3 & 1;
        }
        if ((m & 0x80) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.carVineta = v[0]; }
        if ((m & 0x10) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.fuenteVineta = v[0]; }
        if ((m & 0x40) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.tamVineta = (int) (short) v[0]; }
        if ((m & 0x20) != 0) { if ((off = leer(off, fin, 4, v)) < 0) return -1; e.colorVineta = v[0]; }
        if ((m & 0x800) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.alin = v[0]; }
        if ((m & 0x1000) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.interlinea = (int) (short) v[0]; }
        if ((m & 0x2000) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.antes = (int) (short) v[0]; }
        if ((m & 0x4000) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.despues = (int) (short) v[0]; }
        if ((m & 0x100) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.margen = (int) (short) v[0]; }
        if ((m & 0x400) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.sangria = (int) (short) v[0]; }
        if ((m & 0x8000) != 0) off += 2;
        if ((m & 0x100000) != 0) {
            if ((off = leer(off, fin, 2, v)) < 0) return -1;
            off += 4 * v[0];
        }
        if ((m & 0x10000) != 0) off += 2;
        if ((m & 0xE0000) != 0) off += 2;
        if ((m & 0x200000) != 0) off += 2;
        return off <= fin ? off : -1;
    }

    private int leerCf(int off, int fin, Est e) {
        if (off + 4 > fin) return -1;
        int m = Cfb.i32(d, off);
        off += 4;
        int[] v = new int[1];
        if ((m & 0xFFFF) != 0) {
            if ((off = leer(off, fin, 2, v)) < 0) return -1;
            if ((m & 1) != 0) e.negrita = v[0] & 1;
            if ((m & 2) != 0) e.cursiva = v[0] >> 1 & 1;
            if ((m & 4) != 0) e.subrayado = v[0] >> 2 & 1;
        }
        if ((m & 0x10000) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.fuente = v[0]; }
        if ((m & 0x200000) != 0) off += 2;
        if ((m & 0x400000) != 0) off += 2;
        if ((m & 0x800000) != 0) off += 2;
        if ((m & 0x20000) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.tam = v[0]; }
        if ((m & 0x40000) != 0) { if ((off = leer(off, fin, 4, v)) < 0) return -1; e.color = v[0]; }
        if ((m & 0x80000) != 0) { if ((off = leer(off, fin, 2, v)) < 0) return -1; e.pos = (int) (short) v[0]; }
        return off <= fin ? off : -1;
    }

    private int leer(int off, int fin, int n, int[] v) {
        if (off < 0 || off + n > fin) return -1;
        v[0] = n == 2 ? Cfb.u16(d, off) : Cfb.i32(d, off);
        return off + n;
    }

    // --- Diapositivas ---

    private void diapositiva(Reg r, Entrada e, int n, int total, StringBuilder sb) {
        boolean oculta = false;
        int flags = 7, idPatron = 0;
        int[] propio = null;
        Reg dibujo = null;
        for (Reg h : hijos(r)) {
            switch (h.tipo) {
                case ATOMO_DIAPO:
                    idPatron = i32(h.ini + 12, h.fin);
                    flags = u16(h.ini + 20, h.fin);
                    break;
                case INFO_PASE: oculta = (u16(h.ini + 10, h.fin) & 4) != 0; break;
                case DIBUJO: dibujo = h; break;
                case ESQUEMA: if (h.inst == 1) propio = esquema(h); break;
                default: break;
            }
        }
        titulo(e, n);
        if (r == null || dibujo == null) {
            tarjeta(e, n, total, oculta, sb);
            return;
        }
        Ctx c = new Ctx();
        c.patron = patronDe(idPatron);
        c.esquema = (flags & 2) == 0 && propio != null ? propio
                : c.patron != null && c.patron.esquema != null ? c.patron.esquema : propio != null ? propio : ESQUEMA_DEFECTO;
        c.entrada = e;
        c.usados = new boolean[e.bloques.size()];

        StringBuilder formas = new StringBuilder();
        String[] fondo = dibujo(dibujo, c, formas);
        if ((flags & 4) != 0 || fondo == null) {
            String[] fp = fondoPatron(c.patron, c.esquema);
            if (fp != null || (flags & 4) != 0) fondo = fp;
        }
        StringBuilder deco = new StringBuilder();
        if ((flags & 1) != 0) decoracion(c.patron, c.esquema, deco, 0);

        // Textos de la lista que ninguna forma ha usado (y que no estén ya dibujados).
        List<Bloque> sueltos = new ArrayList<>(c.sueltos);
        for (int i = 0; i < e.bloques.size(); i++) {
            Bloque b = e.bloques.get(i);
            if (!c.usados[i] && !b.texto.trim().isEmpty() && !c.textos.contains(b.texto.trim())) sueltos.add(b);
        }
        if (formas.length() == 0 && !sueltos.isEmpty()) {
            tarjeta(e, n, total, oculta, sb);
            return;
        }
        etiqueta(n, total, oculta, sb);
        sb.append("<div class=diapo style=\"height:").append(Html.px(alto * k)).append("px;background:")
                .append(fondo != null ? fondo[0] : "#FFFFFF").append("\">");
        if (fondo != null && fondo[1] != null) sb.append(fondo[1]);
        sb.append(deco).append(formas).append("</div>");
        if (!sueltos.isEmpty()) {
            sb.append("<div class=extra>");
            for (int i = 0; i < sueltos.size(); i++) {
                if (i > 0) sb.append('\n');
                Html.esc(sb, limpio(sueltos.get(i).texto));
            }
            sb.append("</div>");
        }
    }

    private static void etiqueta(int n, int total, boolean oculta, StringBuilder sb) {
        sb.append("<div class=num id=d").append(n).append(">Diapositiva ").append(n).append(" de ").append(total);
        if (oculta) sb.append(" · oculta");
        sb.append("</div>");
    }

    /** Añade el título de la diapositiva al índice. */
    private void titulo(Entrada e, int n) {
        for (Bloque b : e.bloques) {
            if ((b.tipo == 0 || b.tipo == 6) && !b.texto.trim().isEmpty()) {
                String t = limpio(b.texto).replace('\n', ' ').trim();
                if (t.length() > 80) t = t.substring(0, 80) + "…";
                indice.add(new Html.Titulo(1, n + ". " + t, "d" + n));
                return;
            }
        }
    }

    /** Diapositiva sin diseño utilizable: título y textos en orden, en una "tarjeta". */
    private void tarjeta(Entrada e, int n, int total, boolean oculta, StringBuilder sb) {
        etiqueta(n, total, oculta, sb);
        sb.append("<div class=diapo style=\"min-height:").append(Html.px(alto * k)).append("px\"><div class=tarjeta>");
        for (Bloque b : e.bloques) {
            String t = limpio(b.texto).trim();
            if (t.isEmpty()) continue;
            boolean tit = b.tipo == 0 || b.tipo == 6;
            sb.append(tit ? "<h2>" : "<p>");
            Html.esc(sb, t);
            sb.append(tit ? "</h2>" : "</p>");
        }
        sb.append("</div></div>");
    }

    /** Texto sin caracteres de control; los saltos de párrafo y de línea quedan como '\n'. */
    private static String limpio(String s) {
        StringBuilder r = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\r' || ch == 0x0B) r.append('\n');
            else if (ch >= 0x20 || ch == '\t' || ch == '\n') r.append(ch);
        }
        return r.toString();
    }

    /** Fondo del patrón (o de su patrón padre si el de título lo toma de él). */
    private String[] fondoPatron(Patron p, int[] esquema) {
        for (int vuelta = 0; p != null && vuelta < 4; vuelta++, p = p.padre) {
            if (p.reg.tipo == DIAPOSITIVA && (p.flags & 4) != 0 && p.padre != null) continue;
            Ctx c = new Ctx();
            c.esquema = esquema;
            c.esPatron = true;
            return p.dibujo != null ? fondoDe(p.dibujo, c) : null;
        }
        return null;
    }

    private String[] fondoDe(Reg dibujo, Ctx c) {
        for (Reg dg : hijos(dibujo)) {
            if (dg.tipo != DG) continue;
            for (Reg h : hijos(dg)) {
                if (h.tipo == SP) {
                    Forma f = leerForma(h);
                    if ((f.flags & 0x400) != 0) return fondo(f, c);
                }
            }
        }
        return null;
    }

    /** Formas del patrón que no son marcadores de posición (logotipos, franjas…). */
    private void decoracion(Patron p, int[] esquema, StringBuilder out, int prof) {
        if (p == null || p.dibujo == null || prof > 3) return;
        if (p.reg.tipo == DIAPOSITIVA && (p.flags & 1) != 0) decoracion(p.padre, esquema, out, prof + 1);
        Ctx c = new Ctx();
        c.esquema = esquema;
        c.patron = p;
        c.esPatron = true;
        dibujo(p.dibujo, c, out);
    }

    /** PPDrawing: dibuja las formas y devuelve el fondo propio {css, capa de imagen}, si lo hay. */
    private String[] dibujo(Reg r, Ctx c, StringBuilder out) {
        String[] fondo = null;
        for (Reg dg : hijos(r)) {
            if (dg.tipo != DG) continue;
            for (Reg h : hijos(dg)) {
                if (h.tipo == SPGR) {
                    grupo(h, Transf.IDENTIDAD, c, out, 0);
                } else if (h.tipo == SP) {
                    Forma f = leerForma(h);
                    if ((f.flags & 0x400) != 0) fondo = fondo(f, c);
                }
            }
        }
        return fondo;
    }

    private void grupo(Reg r, Transf t, Ctx c, StringBuilder out, int prof) {
        Transf tg = t;
        boolean primero = true;
        for (Reg h : hijos(r)) {
            if (h.tipo == SP) {
                Forma f = leerForma(h);
                if (primero && (f.flags & 1) != 0) {
                    // La primera forma describe el propio grupo: su sitio y su sistema de coordenadas.
                    double[] a = ancla(f, t), g = f.grupo;
                    if (a != null && g != null) {
                        double sx = g[2] > 0 ? a[2] / g[2] : 1, sy = g[3] > 0 ? a[3] / g[3] : 1;
                        tg = new Transf(sx, sy, a[0] - g[0] * sx, a[1] - g[1] * sy);
                    }
                } else {
                    try {
                        forma(f, tg, c, out);
                    } catch (Agotado a) {
                        throw a;
                    } catch (RuntimeException e) {
                        danado = true; // se omite la forma que no se ha podido interpretar
                    }
                }
                primero = false;
            } else if (h.tipo == SPGR && prof < 16) {
                grupo(h, tg, c, out, prof + 1);
                primero = false;
            }
        }
    }

    private Forma leerForma(Reg sp) {
        Forma f = new Forma();
        for (Reg h : hijos(sp)) {
            switch (h.tipo) {
                case FSP:
                    f.tipo = h.inst;
                    f.spid = i32(h.ini, h.fin);
                    f.flags = i32(h.ini + 4, h.fin);
                    break;
                case FOPT: case 0xF121: case 0xF122: propiedades(h, f.props); break;
                case ANCLA:
                    if (h.largo() >= 16) {
                        f.cliente = rect(i32(h.ini + 4, h.fin), i32(h.ini, h.fin), i32(h.ini + 8, h.fin), i32(h.ini + 12, h.fin));
                    } else if (h.largo() >= 8) {
                        f.cliente = rect((short) u16(h.ini + 2, h.fin), (short) u16(h.ini, h.fin),
                                (short) u16(h.ini + 4, h.fin), (short) u16(h.ini + 6, h.fin));
                    }
                    break;
                case ANCLA_HIJO:
                    if (h.largo() >= 16) f.hijo = rect(i32(h.ini, h.fin), i32(h.ini + 4, h.fin), i32(h.ini + 8, h.fin), i32(h.ini + 12, h.fin));
                    break;
                case FSPGR:
                    if (h.largo() >= 16) f.grupo = rect(i32(h.ini, h.fin), i32(h.ini + 4, h.fin), i32(h.ini + 8, h.fin), i32(h.ini + 12, h.fin));
                    break;
                case DATOS_CLIENTE: {
                    Reg m = hijo(hijos(h), MARCADOR);
                    if (m != null && m.largo() >= 5) f.marcador = d[m.ini + 4] & 0xFF;
                    break;
                }
                case CAJA_TEXTO: f.caja = h; break;
                default: break;
            }
        }
        return f;
    }

    /** {x, y, ancho, alto} a partir de izquierda, arriba, derecha y abajo. */
    private static double[] rect(int l, int t, int r, int b) {
        return new double[] {l, t, Math.max(0, (double) r - l), Math.max(0, (double) b - t)};
    }

    /** OfficeArtFOPT: propiedades simples (las complejas solo se saltan). */
    private void propiedades(Reg r, Map<Integer, Integer> m) {
        int n = Math.min(r.inst, r.largo() / 6);
        for (int i = 0; i < n; i++) {
            int o = r.ini + 6 * i;
            m.put(Cfb.u16(d, o) & 0x3FFF, Cfb.i32(d, o + 2));
        }
    }

    private static double[] ancla(Forma f, Transf t) {
        if (f.hijo != null && (t != Transf.IDENTIDAD || f.cliente == null)) return t.aplicar(f.hijo);
        return f.cliente;
    }

    /** Propiedad de la forma, de su forma maestra o de los valores por defecto del documento. */
    private Integer prop(Forma f, int id) {
        Integer v = f.props.get(id);
        if (v == null && f.maestra != null) v = f.maestra.get(id);
        if (v == null) v = porDefecto.get(id);
        return v;
    }

    /** Bandera de un grupo de propiedades booleanas (el bit "usar" va 16 posiciones más arriba). */
    private Boolean bandera(Forma f, int id, int bit) {
        Boolean r = bandera(f.props, id, bit);
        if (r == null) r = bandera(f.maestra, id, bit);
        return r != null ? r : bandera(porDefecto, id, bit);
    }

    private static Boolean bandera(Map<Integer, Integer> m, int id, int bit) {
        Integer v = m == null ? null : m.get(id);
        return v != null && (v & bit << 16) != 0 ? (v & bit) != 0 : null;
    }

    private void forma(Forma f, Transf t, Ctx c, StringBuilder out) {
        if ((f.flags & (0x8 | 0x400 | 0x1)) != 0) return; // borrada, fondo o grupo
        if (c.esPatron && f.marcador >= 0) return; // los marcadores del patrón solo dan formato
        Integer maestra = f.props.get(0x0301);
        if (maestra != null && c.patron != null) {
            for (Patron p = c.patron; p != null && f.maestra == null; p = p.padre == c.patron ? null : p.padre) {
                f.maestra = p.formas.get(maestra);
            }
        }
        Bloque b = bloque(f, c);
        double[] a = ancla(f, t);
        if (a == null) {
            if (b != null && !c.esPatron) c.sueltos.add(b);
            return;
        }
        double x = a[0] * k, y = a[1] * k, w = a[2] * k, h = a[3] * k;
        Integer giro = prop(f, 0x0004);
        double rot = giro != null ? giro / 65536.0 : 0;
        rot = ((rot % 360) + 360) % 360;
        if ((rot >= 45 && rot < 135) || (rot >= 225 && rot < 315)) {
            // El ancla guarda la caja ya girada: se recupera la original alrededor del centro.
            x += (w - h) / 2;
            y += (h - w) / 2;
            double tmp = w;
            w = h;
            h = tmp;
        }
        boolean volteoH = (f.flags & 0x40) != 0, volteoV = (f.flags & 0x80) != 0;

        Boolean conLinea = bandera(f, 0x01FF, 0x8);
        String colorLinea = Boolean.FALSE.equals(conLinea) ? null : color(prop(f, 0x01C0), c.esquema, 0x08000001);
        Integer ancho = prop(f, 0x01CB);
        double grosor = Math.max(1, (ancho != null && ancho > 0 ? ancho : 9525) / 1587.5 * k);

        if (f.tipo == 20 || (f.tipo >= 32 && f.tipo <= 40) || (f.flags & 0x100) != 0) {
            if (colorLinea == null) return;
            double x1 = volteoH ? w : 0, x2 = volteoH ? 0 : w, y1 = volteoV ? h : 0, y2 = volteoV ? 0 : h;
            out.append("<svg class=f style=\"left:").append(Html.px(x)).append("px;top:").append(Html.px(y))
                    .append("px\" width=\"").append(Html.px(Math.max(w, 1))).append("\" height=\"")
                    .append(Html.px(Math.max(h, 1))).append("\"><line x1=\"").append(Html.px(x1))
                    .append("\" y1=\"").append(Html.px(y1)).append("\" x2=\"").append(Html.px(x2))
                    .append("\" y2=\"").append(Html.px(y2)).append("\" stroke=\"#").append(colorLinea)
                    .append("\" stroke-width=\"").append(Html.px(grosor)).append("\"/></svg>");
            return;
        }

        Integer pib = prop(f, 0x0104);
        String imagen = pib != null ? claseImagen(pib) : null;
        boolean imagenRota = pib != null && imagen == null, ole = (f.flags & 0x10) != 0;
        String relleno = null, rellenoImagen = null;
        if (!Boolean.FALSE.equals(bandera(f, 0x01BF, 0x10)) && (f.props.containsKey(0x0181)
                || f.props.containsKey(0x0180) || bandera(f, 0x01BF, 0x10) != null)) {
            Integer tipoRelleno = prop(f, 0x0180);
            int tr = tipoRelleno != null ? tipoRelleno : 0;
            if (tr >= 1 && tr <= 3) {
                Integer blip = prop(f, 0x0186);
                rellenoImagen = blip != null ? claseImagen(blip) : null;
            }
            if (rellenoImagen == null) relleno = color(prop(f, 0x0181), c.esquema, 0x08000004);
            Integer opacidad = prop(f, 0x0182);
            if (relleno != null && opacidad != null && opacidad >= 0 && opacidad < 65536) {
                int v = Integer.parseInt(relleno, 16);
                relleno = "rgba(" + (v >> 16) + ',' + (v >> 8 & 0xFF) + ',' + (v & 0xFF) + ','
                        + Html.px(opacidad / 65536.0) + ')';
            } else if (relleno != null) {
                relleno = "#" + relleno;
            }
        }
        if (imagen != null || imagenRota) {
            relleno = null; // el relleno de un marco de imagen queda debajo de la foto
            rellenoImagen = null;
        }
        if (f.tipo == 0 && imagen == null) {
            // Forma libre: su caja no se parece al dibujo, así que solo se muestra su texto.
            relleno = colorLinea = rellenoImagen = null;
        }
        boolean hayTexto = b != null && !b.texto.trim().isEmpty();
        if (relleno == null && rellenoImagen == null && colorLinea == null && imagen == null && !imagenRota
                && !ole && !hayTexto) {
            return;
        }

        out.append("<div class=f style=\"left:").append(Html.px(x)).append("px;top:").append(Html.px(y))
                .append("px;width:").append(Html.px(w)).append("px;height:").append(Html.px(h)).append("px;");
        if (relleno != null) out.append("background:").append(relleno).append(';');
        if (colorLinea != null) {
            Integer raya = prop(f, 0x01CE);
            out.append("border:").append(Html.px(grosor)).append(raya != null && raya != 0 ? "px dashed #" : "px solid #")
                    .append(colorLinea).append(';');
        }
        if (f.tipo == 3) out.append("border-radius:50%;overflow:hidden;");
        else if (f.tipo == 2) out.append("border-radius:").append(Html.px(Math.min(w, h) / 6)).append("px;overflow:hidden;");
        if (rot != 0) out.append("transform:rotate(").append(Html.px(rot)).append("deg);");
        if (imagen != null) out.append("overflow:hidden;");
        out.append("\">");
        if (rellenoImagen != null) out.append("<div class=\"im ").append(rellenoImagen).append("\"></div>");
        if (imagen != null) {
            out.append("<div class=\"im ").append(imagen).append("\" style=\"");
            recorte(f, out);
            if (volteoH || volteoV) {
                out.append("transform:scale(").append(volteoH ? -1 : 1).append(',').append(volteoV ? -1 : 1).append(')');
            }
            out.append("\"></div>");
        } else if (imagenRota || (ole && !hayTexto)) {
            out.append("<div class=fijo>[").append(ole ? "Objeto" : "Imagen").append("]</div>");
        }
        if (hayTexto) {
            c.textos.add(b.texto.trim());
            texto(f, b, c, out);
        }
        out.append("</div>");
    }

    /** Recorte de una imagen (fracciones 16.16 de cada borde) como desplazamiento de la capa. */
    private void recorte(Forma f, StringBuilder out) {
        double[] r = new double[4]; // arriba, abajo, izquierda, derecha
        boolean hay = false;
        for (int i = 0; i < 4; i++) {
            Integer v = f.props.get(0x0100 + i);
            if (v != null && v != 0) {
                r[i] = Math.max(-1, Math.min(0.99, v / 65536.0));
                hay = true;
            }
        }
        double fh = 1 - r[2] - r[3], fv = 1 - r[0] - r[1];
        if (!hay || fh <= 0.01 || fv <= 0.01) return;
        out.append("left:").append(Html.px(-r[2] / fh * 100)).append("%;right:").append(Html.px(-r[3] / fh * 100))
                .append("%;top:").append(Html.px(-r[0] / fv * 100)).append("%;bottom:")
                .append(Html.px(-r[1] / fv * 100)).append("%;");
    }

    /** Fondo {css, capa de imagen o null} de la forma de fondo de una diapositiva o patrón. */
    private String[] fondo(Forma f, Ctx c) {
        if (Boolean.FALSE.equals(bandera(f, 0x01BF, 0x10))) return new String[] {"#FFFFFF", null};
        Integer tr = f.props.get(0x0180);
        int tipo = tr != null ? tr : 0;
        String c1 = color(f.props.get(0x0181), c.esquema, 0x08000000);
        if (c1 == null) c1 = "FFFFFF";
        if (tipo >= 1 && tipo <= 3) {
            Integer blip = f.props.get(0x0186);
            String cl = blip != null ? claseImagen(blip) : null;
            // Las texturas se repiten como mosaico; las imágenes ocupan toda la diapositiva.
            if (cl != null) return new String[] {"#" + c1, "<div class=\"im " + cl + (tipo == 2
                    ? "\" style=\"background-size:auto;background-repeat:repeat\"></div>" : "\"></div>")};
        } else if (tipo >= 4 && tipo <= 8) {
            String c2 = color(f.props.get(0x0183), c.esquema, 0x08000000);
            if (c2 != null && !c2.equals(c1)) return new String[] {"linear-gradient(#" + c1 + ",#" + c2 + ")", null};
        }
        return new String[] {"#" + c1, null};
    }

    /** OfficeArtCOLORREF → "RRGGBB" (con el esquema de la diapositiva), o null. */
    private static String color(Integer v, int[] esquema, int defecto) {
        int x = v != null ? v : defecto, banderas = x >>> 24;
        if ((banderas & 0x08) != 0) {
            int i = x & 0xFF;
            return i < 8 ? hex(esquema[i]) : null;
        }
        if ((banderas & 0x10) != 0) return v != null ? color(null, esquema, defecto) : null;
        return hex((x & 0xFF) << 16 | (x & 0xFF00) | (x >> 16 & 0xFF));
    }

    /** ColorIndexStruct del texto → "RRGGBB", o null. */
    private static String colorTexto(Integer v, int[] esquema) {
        if (v == null) return null;
        int i = v >>> 24;
        if (i == 0xFE) return hex((v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16 & 0xFF));
        return i < 8 ? hex(esquema[i]) : null;
    }

    private static String hex(int rgb) {
        String s = Integer.toHexString(rgb & 0xFFFFFF).toUpperCase(java.util.Locale.ROOT);
        return "000000".substring(s.length()) + s;
    }

    // --- Texto de las formas ---

    /** Texto de la caja de la forma: propio o, por OutlineTextRefAtom, de la lista de la diapositiva. */
    private Bloque bloque(Forma f, Ctx c) {
        if (f.caja == null) return null;
        Bloque b = null;
        for (Reg h : hijos(f.caja)) {
            switch (h.tipo) {
                case CAB_TEXTO:
                    b = new Bloque();
                    b.tipo = i32(h.ini, h.fin);
                    break;
                case CARACTERES: case BYTES:
                    if (b == null) b = new Bloque();
                    b.texto = texto(h);
                    break;
                case ESTILO_TEXTO: if (b != null) b.estilo = h; break;
                case REF_TEXTO: {
                    int i = i32(h.ini, h.fin);
                    if (c.entrada != null && i >= 0 && i < c.entrada.bloques.size()) {
                        c.usados[i] = true;
                        return c.entrada.bloques.get(i);
                    }
                    break;
                }
                default: break;
            }
        }
        return b;
    }

    private void texto(Forma f, Bloque b, Ctx c, StringBuilder out) {
        double[] m = new double[4];
        int[] ids = {0x0081, 0x0082, 0x0083, 0x0084};
        int[] def = {91440, 45720, 91440, 45720};
        for (int i = 0; i < 4; i++) {
            Integer v = prop(f, ids[i]);
            m[i] = Math.max(0, (v != null ? v : def[i]) / 1587.5 * k);
        }
        Integer anclaje = prop(f, 0x0087), ajuste = prop(f, 0x0085);
        int an = anclaje != null ? anclaje : 0;
        out.append("<div class=tx style=\"padding:").append(Html.px(m[1])).append("px ").append(Html.px(m[2]))
                .append("px ").append(Html.px(m[3])).append("px ").append(Html.px(m[0])).append("px;");
        if (an == 1 || an == 4 || an == 7) out.append("justify-content:center;");
        else if (an == 2 || an == 5 || an == 8) out.append("justify-content:flex-end;");
        if (ajuste != null && ajuste == 2) out.append("white-space:nowrap;");
        out.append("\">");
        parrafos(b, c, out);
        out.append("</div>");
    }

    /** Párrafos con las corridas del StyleTextPropAtom y la herencia de los estilos del patrón. */
    private void parrafos(Bloque b, Ctx c, StringBuilder out) {
        String s = b.texto;
        int n = s.length();
        int[] idxPf = new int[n + 1], idxCf = new int[n + 1];
        List<Est> pfs = new ArrayList<>(), cfs = new ArrayList<>();
        List<Integer> niveles = new ArrayList<>();
        java.util.Arrays.fill(idxPf, -1);
        java.util.Arrays.fill(idxCf, -1);
        if (b.estilo != null) {
            Reg r = b.estilo;
            int off = r.ini, pos = 0;
            while (pos <= n && off + 6 <= r.fin) {
                long cuenta = Cfb.i32(d, off) & 0xFFFFFFFFL;
                int lv = Cfb.u16(d, off + 4);
                Est e = new Est();
                off = leerPf(off + 6, r.fin, e);
                if (off < 0) break;
                for (long i = 0; i < cuenta && pos <= n; i++) idxPf[pos++] = pfs.size();
                pfs.add(e);
                niveles.add(Math.min(4, lv));
            }
            pos = 0;
            while (off > 0 && pos <= n && off + 4 <= r.fin) {
                long cuenta = Cfb.i32(d, off) & 0xFFFFFFFFL;
                Est e = new Est();
                off = leerCf(off + 4, r.fin, e);
                if (off < 0) break;
                for (long i = 0; i < cuenta && pos <= n; i++) idxCf[pos++] = cfs.size();
                cfs.add(e);
            }
        }
        boolean titulo = b.tipo == 0 || b.tipo == 6;
        int inicio = 0;
        while (inicio <= n) {
            int fin = inicio;
            while (fin < n && s.charAt(fin) != '\r' && s.charAt(fin) != '\n') fin++;
            int ip = idxPf[inicio];
            int nivel = ip >= 0 ? niveles.get(ip) : 0;
            Est base = estiloHeredado(c.patron, b.tipo, nivel);
            Est pf = new Est();
            if (ip >= 0) pf.heredar(pfs.get(ip));
            int ic = idxCf[inicio];
            if (ic >= 0) pf.heredar(cfs.get(ic)); // la primera corrida da el tamaño de la línea
            pf.heredar(base);
            parrafo(s, inicio, fin, pf, base, idxCf, cfs, titulo, c, out);
            inicio = fin + 1;
            if (fin == n) break;
        }
    }

    private void parrafo(String s, int inicio, int fin, Est pf, Est base, int[] idxCf, List<Est> cfs,
                         boolean titulo, Ctx c, StringBuilder out) {
        double tam = (pf.tam != null && pf.tam > 0 && pf.tam < 4000 ? pf.tam : titulo ? 44 : 18) * 8 * k;
        out.append("<div class=pa style=\"font-size:").append(Html.px(tam)).append("px;");
        int alin = pf.alin != null ? pf.alin : 0;
        if (alin == 1) out.append("text-align:center;");
        else if (alin == 2) out.append("text-align:right;");
        else if (alin >= 3) out.append("text-align:justify;");
        double margen = pf.margen != null ? Math.max(0, pf.margen * k) : 0;
        double sangria = pf.sangria != null ? Math.max(0, pf.sangria * k) : margen;
        if (margen > 0) out.append("padding-left:").append(Html.px(margen)).append("px;");
        if (sangria != margen) out.append("text-indent:").append(Html.px(sangria - margen)).append("px;");
        if (pf.interlinea != null) {
            int v = pf.interlinea;
            if (v < 0) out.append("line-height:").append(Html.px(-v * k)).append("px;");
            else if (v > 0) out.append("line-height:").append(Html.px(Math.max(0.6, 1.2 * v / 100))).append(';');
        }
        espacio("margin-top", pf.antes, out);
        espacio("margin-bottom", pf.despues, out);
        out.append("\">");
        boolean hayTexto = false;
        for (int i = inicio; i < fin && !hayTexto; i++) hayTexto = s.charAt(i) > ' ';
        if (!hayTexto) {
            out.append("<br></div>");
            return;
        }
        String colorBase = colorTexto(pf.color, c.esquema);
        if (colorBase == null) colorBase = hex(c.esquema[titulo ? 3 : 1]);
        if (pf.vineta != null && pf.vineta == 1) {
            String col = pf.usaColor != null && pf.usaColor == 1 ? colorTexto(pf.colorVineta, c.esquema) : null;
            out.append("<span style=\"display:inline-block;text-indent:0;color:#").append(col != null ? col : colorBase)
                    .append(';');
            if (margen > sangria) out.append("min-width:").append(Html.px(margen - sangria)).append("px;");
            else out.append("margin-right:.4em;");
            if (pf.usaTam != null && pf.usaTam == 1 && pf.tamVineta != null && pf.tamVineta > 0 && pf.tamVineta <= 400) {
                out.append("font-size:").append(Html.px(tam * pf.tamVineta / 100)).append("px;");
            }
            out.append("\">").append(vineta(pf)).append("</span>");
        }
        int i = inicio;
        while (i < fin) {
            int j = i + 1;
            while (j < fin && idxCf[j] == idxCf[i]) j++;
            Est r = new Est();
            if (idxCf[i] >= 0) r.heredar(cfs.get(idxCf[i]));
            r.heredar(base);
            corrida(s.substring(i, j), r, titulo, c, out);
            i = j;
        }
        out.append("</div>");
    }

    private void espacio(String css, Integer v, StringBuilder out) {
        if (v == null || v == 0) return;
        out.append(css).append(':');
        if (v < 0) out.append(Html.px(-v * k)).append("px;");
        else out.append(Html.px(v / 100.0 * 1.2)).append("em;");
    }

    private String vineta(Est pf) {
        int ch = pf.carVineta != null && (pf.usaFuente == null || pf.usaFuente == 1 || pf.carVineta >= 0x20)
                ? pf.carVineta : 0x2022;
        String fuente = pf.usaFuente != null && pf.usaFuente == 1 && pf.fuenteVineta != null
                && pf.fuenteVineta < fuentes.size() ? fuentes.get(pf.fuenteVineta) : "";
        if (ch >= 0xF000 && ch <= 0xF0FF) ch -= 0xF000;
        if (fuente.startsWith("Wingdings") || fuente.equals("Symbol") || fuente.equals("Monotype Sorts")) {
            switch (ch) {
                case 0xA7: case 0x6E: return "■";
                case 0x71: case 0x6F: return "□";
                case 0x76: return "❖";
                case 0xD8: case 0xE0: case 0xE8: return "➢";
                case 0xFC: return "✓";
                case 0xA8: case 0x75: return "◆";
                default: return "•";
            }
        }
        if (ch < 0x20 || (ch >= 0xD800 && ch < 0xE000)) ch = 0x2022;
        return Html.esc(String.valueOf((char) ch));
    }

    private void corrida(String t, Est r, boolean titulo, Ctx c, StringBuilder out) {
        StringBuilder st = new StringBuilder();
        double tam = (r.tam != null && r.tam > 0 && r.tam < 4000 ? r.tam : titulo ? 44 : 18) * 8 * k;
        int pos = r.pos != null ? r.pos : 0;
        st.append("font-size:").append(Html.px(pos != 0 ? tam * 0.7 : tam)).append("px;");
        if (r.negrita != null && r.negrita == 1) st.append("font-weight:bold;");
        if (r.cursiva != null && r.cursiva == 1) st.append("font-style:italic;");
        if (r.subrayado != null && r.subrayado == 1) st.append("text-decoration:underline;");
        String col = colorTexto(r.color, c.esquema);
        st.append("color:#").append(col != null ? col : hex(c.esquema[titulo ? 3 : 1])).append(';');
        if (r.fuente != null && r.fuente < fuentes.size()) {
            String nombre = fuentes.get(r.fuente).replaceAll("[^\\p{L}\\p{N} _-]", "");
            if (!nombre.isEmpty()) st.append("font-family:'").append(nombre).append("',sans-serif;");
        }
        if (pos > 0) st.append("vertical-align:super;");
        else if (pos < 0) st.append("vertical-align:sub;");
        out.append("<span style=\"").append(st).append("\">");
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == 0x0B) out.append("<br>");
            else if (ch >= 0x20 || ch == '\t') Html.esc(out, String.valueOf(ch));
        }
        out.append("</span>");
    }

    // --- Imágenes ---

    /** Clase CSS con la imagen número "pib" (1 en adelante) del BStore; cada imagen se incrusta una vez. */
    private String claseImagen(int pib) {
        String cl = clasesImagen.get(pib);
        if (cl != null) return cl.isEmpty() ? null : cl;
        String uri = null;
        if (pib >= 1 && pib <= blips.size()) {
            int[] bl = blips.get(pib - 1);
            if (bl[0] == 1 && imagenes == null) imagenes = cfb.flujo(base + "Pictures");
            byte[] fuente = bl[0] == 0 ? d : imagenes;
            if (fuente != null && fuente == imagenes && rc4 != null) descifrarFoto(fuente, bl[1]);
            if (fuente != null) {
                try {
                    uri = uriBlip(fuente, bl[1]);
                } catch (Agotado a) {
                    throw a;
                } catch (RuntimeException e) {
                    uri = null;
                }
            }
        }
        cl = uri == null ? "" : "i" + clasesImagen.size();
        clasesImagen.put(pib, cl);
        if (uri != null) cssImagenes.append('.').append(cl).append("{background-image:url(\"").append(uri).append("\")}");
        return uri == null ? null : cl;
    }

    /** OfficeArtBlip → URI data: (JPEG, PNG y DIB como BMP; los metarchivos no se muestran). */
    private String uriBlip(byte[] b, int off) {
        Reg r = reg(b, off, b.length, false);
        if (r == null) return null;
        int datos = r.ini + ((r.inst & 1) != 0 ? 32 : 16) + 1;
        if (datos >= r.fin) return null;
        String mime;
        byte[] bytes;
        switch (r.tipo) {
            case 0xF01D: case 0xF02A:
                mime = "image/jpeg";
                if ((b[datos] & 0xFF) != 0xFF) return null;
                bytes = java.util.Arrays.copyOfRange(b, datos, r.fin);
                break;
            case 0xF01E:
                mime = "image/png";
                if ((b[datos] & 0xFF) != 0x89) return null;
                bytes = java.util.Arrays.copyOfRange(b, datos, r.fin);
                break;
            case 0xF01F:
                mime = "image/bmp";
                bytes = bmp(b, datos, r.fin);
                if (bytes == null) return null;
                break;
            default: return null;
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** Antepone a un DIB la cabecera de archivo BMP de 14 bytes. */
    private static byte[] bmp(byte[] b, int ini, int fin) {
        int n = fin - ini;
        if (n < 16) return null;
        int cab = Cfb.i32(b, ini);
        if (cab < 12 || cab > n) return null;
        int bits, usados = 0, compresion = 0;
        if (cab == 12) {
            bits = Cfb.u16(b, ini + 10);
        } else {
            if (cab < 36) return null;
            bits = Cfb.u16(b, ini + 14);
            compresion = Cfb.i32(b, ini + 16);
            usados = Cfb.i32(b, ini + 32);
        }
        long paleta = (usados > 0 && usados <= 256 ? usados : bits <= 8 ? 1 << bits : 0) * (cab == 12 ? 3 : 4);
        if (compresion == 3 && cab == 40) paleta += 12;
        long desp = 14 + cab + paleta;
        if (desp > 14 + n) return null;
        byte[] r = new byte[14 + n];
        r[0] = 'B';
        r[1] = 'M';
        escribir32(r, 2, r.length);
        escribir32(r, 10, (int) desp);
        System.arraycopy(b, ini, r, 14, n);
        return r;
    }

    private static void escribir32(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >>> 8 * i);
    }
}
