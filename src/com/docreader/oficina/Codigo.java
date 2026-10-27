package com.docreader.oficina;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Visor de código fuente y de archivos de configuración. Detecta el lenguaje por el nombre del
 * archivo, la extensión, el tipo MIME o la línea "#!", y lo resalta con un analizador léxico
 * propio por familias de lenguajes (sin bibliotecas). Todo el análisis es lineal en la longitud
 * del texto: cada analizador avanza siempre y solo mira hacia delante un trozo acotado.
 */
final class Codigo {
    private Codigo() { }

    /** A partir de este tamaño se muestran los números de línea pero sin colores (memoria). */
    private static final int MAX_RESALTADO = 1_500_000;

    // Familias de analizador.
    static final int GENERICO = 0, MARCADO = 1, CSS = 2, JSON = 3, YAML = 4, INI = 5, TOML = 6, DIFF = 7;

    // Clases de ficha; el índice da la clase CSS.
    static final int COM = 1, CAD = 2, NUM = 3, CLAVE = 4, TIPO = 5, FUN = 6, META = 7, VAR = 8,
            ATR = 9, ETQ = 10, REGEX = 11, SECC = 12, MAS = 13, MENOS = 14;
    private static final String[] CLASES = {"", "c", "s", "n", "k", "t", "f", "m", "v", "a", "g", "r", "h", "di", "dd"};

    // --- Paletas ---

    private static final String TOKENS_CLARO =
            ".c{color:#6e7781;font-style:italic}.s{color:#0a3069}.n{color:#0550ae}.k{color:#cf222e}"
            + ".t{color:#953800}.f{color:#8250df}.m{color:#9a6700}.v{color:#bc4c00}.a{color:#0550ae}"
            + ".g{color:#116329}.r{color:#116329}.h{color:#0550ae;font-weight:bold}"
            + ".di{color:#116329;background:#dafbe1}.dd{color:#82071e;background:#ffebe9}";

    private static final String TOKENS_OSCURO =
            ".c{color:#8b949e;font-style:italic}.s{color:#a5d6ff}.n{color:#79c0ff}.k{color:#ff7b72}"
            + ".t{color:#ffa657}.f{color:#d2a8ff}.m{color:#e3b341}.v{color:#f0883e}.a{color:#79c0ff}"
            + ".g{color:#7ee787}.r{color:#7ee787}.h{color:#79c0ff;font-weight:bold}"
            + ".di{color:#aff5b4;background:#033a16}.dd{color:#ffdcd7;background:#67060c}";

    /** Colores de los bloques de código dentro de otros documentos (Markdown). */
    static final String CSS_TOKENS = TOKENS_CLARO;

    /**
     * Visor con números de línea: el número es contenido generado (::before con un contador),
     * así que no se selecciona, no se copia y la búsqueda no lo encuentra. Sin "Ajustar líneas"
     * las líneas no se parten y el bloque se desplaza en horizontal con el canal de números fijo.
     */
    private static final String CSS_VISOR =
            "body.codigo{padding:0;background:#fff;color:#1f2328}"
            + ".fuente{font-family:monospace;font-size:13px;line-height:1.5;white-space:pre;overflow-x:auto;"
            + "tab-size:4;padding:6px 0 28px;overflow-wrap:normal;"
            + "background:linear-gradient(to right,#f6f8fa calc(var(--d) + 18px),#d8dee4 0,#d8dee4 calc(var(--d) + 19px),transparent 0)}"
            + ".lin{counter-reset:ln;width:max-content;min-width:100%}"
            + ".lin>div{counter-increment:ln;padding:0 14px 0 calc(var(--d) + 29px)}"
            + ".lin>div::before{content:counter(ln);display:inline-block;box-sizing:content-box;width:var(--d);"
            + "padding:0 10px 0 8px;margin-left:calc(-1 * (var(--d) + 29px));margin-right:11px;text-align:right;"
            + "color:#8c959f;background:#f6f8fa;position:sticky;left:0;-webkit-user-select:none;user-select:none}"
            + ".nota{margin:12px 14px}"
            + TOKENS_CLARO;

    private static final String CSS_AJUSTAR =
            ".fuente{white-space:pre-wrap;overflow-wrap:anywhere;overflow-x:hidden}.lin{width:auto}";

    private static final String CSS_NOCHE =
            "html{background:#0d1117}body.codigo{background:#0d1117;color:#e6edf3}"
            + ".fuente{background:linear-gradient(to right,#161b22 calc(var(--d) + 18px),#30363d 0,#30363d calc(var(--d) + 19px),transparent 0)}"
            + ".lin>div::before{color:#6e7681;background:#161b22}"
            + ".nota{color:#8b949e}"
            + TOKENS_OSCURO;

    // --- Lenguajes ---

    /** Configuración de un lenguaje: familia de analizador y detalles léxicos. */
    static final class Lenguaje {
        final String id;
        final int familia;
        /** Marcadores de comentario de línea; con "^" delante solo cuentan a principio de línea. */
        String[] lineas = {};
        /** Pares apertura/cierre de comentarios de bloque (mismo convenio "^"). */
        String[] bloques = {};
        String comillas = "\"'";
        Set<String> claves = Collections.emptySet(), tipos = Collections.emptySet();
        boolean sinMayus;      // palabras clave sin distinguir mayúsculas
        boolean mayusTipos;    // Identificadores con mayúscula inicial = tipos
        boolean triples;       // """ y '''
        boolean plantilla;     // `...` en varias líneas
        boolean dolar;         // variables $x, ${x}, $(x)
        boolean dolarIdent;    // $ forma parte de los identificadores (JavaScript)
        boolean arroba;        // @Anotaciones / @decoradores
        boolean almohadilla;   // #directivas del preprocesador
        boolean regex;         // /expresiones/ de JavaScript
        boolean heredoc;       // <<FIN … FIN y reglas de "#" de la shell
        boolean crudaSimple;   // '...' sin escapes y en varias líneas (shell)
        boolean vidas;         // 'a de Rust
        boolean macros;        // nombre! de Rust
        boolean simbolos;      // :simbolo, @var de Ruby
        boolean porcentaje;    // %VAR% de los .bat
        boolean clavesInicio;  // palabras clave solo a principio de línea (Dockerfile)
        boolean dobladas;      // '' dentro de '...' (SQL)
        boolean sinEscapes;    // \ no escapa (bat, SQL)
        boolean largasLua;     // [[...]] de Lua
        boolean multilinea;    // "..." en varias líneas
        boolean soloComentarios;
        boolean html;          // marcado con <script> y <style> dentro
        boolean scss, less;
        boolean pagina;        // admite "Ver como página"

        Lenguaje(String id, int familia) {
            this.id = id;
            this.familia = familia;
        }

        boolean esSvg() { return id.equals("svg"); }
    }

    private static final Map<String, Lenguaje> POR_EXT = new HashMap<>();
    private static final Map<String, Lenguaje> POR_NOMBRE = new HashMap<>();
    private static final Map<String, Lenguaje> POR_ETIQUETA = new HashMap<>();
    private static final Map<String, Lenguaje> POR_MIME = new HashMap<>();

    private static Set<String> palabras(String s) {
        return s.isEmpty() ? Collections.emptySet() : new HashSet<>(Arrays.asList(s.trim().split("\\s+")));
    }

    private static Lenguaje def(String id, int familia, String claves, String tipos) {
        Lenguaje l = new Lenguaje(id, familia);
        l.claves = palabras(claves);
        l.tipos = palabras(tipos);
        return l;
    }

    private static void ext(Lenguaje l, String... extensiones) {
        for (String e : extensiones) POR_EXT.put(e, l);
    }

    private static void alias(Lenguaje l, String... etiquetas) {
        for (String e : etiquetas) POR_ETIQUETA.put(e, l);
    }

    private static void mime(Lenguaje l, String... tipos) {
        for (String m : tipos) POR_MIME.put(m, l);
    }

    private static final String[] C_COMENTARIOS = {"//"};
    private static final String[] C_BLOQUES = {"/*", "*/"};

    static final Lenguaje JS, TS, CSS_L, SCSS, LESS, JSON_L, HTML_L, XML_L, SH, VB;

    static {
        String cClaves = "auto break case const continue default do else enum extern for goto if inline "
                + "register restrict return sizeof static struct switch typedef union volatile while "
                + "true false NULL nullptr _Bool _Alignas _Alignof _Atomic _Generic _Noreturn _Static_assert _Thread_local";
        String cTipos = "char short int long float double void signed unsigned bool size_t ssize_t ptrdiff_t "
                + "int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t intptr_t uintptr_t "
                + "wchar_t FILE va_list off_t pid_t time_t";
        Lenguaje c = def("c", GENERICO, cClaves, cTipos);
        c.lineas = C_COMENTARIOS;
        c.bloques = C_BLOQUES;
        c.almohadilla = true;
        ext(c, "c", "h", "m");
        alias(c, "c", "h", "objc", "objective-c");
        mime(c, "text/x-c", "text/x-csrc", "text/x-chdr", "text/x-objcsrc");

        Lenguaje cpp = def("cpp", GENERICO, cClaves + " class namespace template typename public private protected "
                + "virtual override final new delete this throw try catch using operator friend explicit "
                + "constexpr consteval constinit noexcept static_cast dynamic_cast reinterpret_cast const_cast "
                + "decltype mutable concept requires co_await co_return co_yield export import module and or not xor",
                cTipos + " string wstring vector map set unordered_map unordered_set list deque array pair tuple "
                + "optional variant unique_ptr shared_ptr weak_ptr std char8_t char16_t char32_t");
        cpp.lineas = C_COMENTARIOS;
        cpp.bloques = C_BLOQUES;
        cpp.almohadilla = true;
        ext(cpp, "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ino", "mm", "inl", "ipp");
        alias(cpp, "cpp", "c++", "cc", "cxx", "hpp", "arduino", "ino");
        mime(cpp, "text/x-c++src", "text/x-c++hdr", "text/x-c++");

        Lenguaje cs = def("cs", GENERICO, "abstract as base break case catch checked class const continue default "
                + "delegate do else enum event explicit extern false finally fixed for foreach goto if implicit in "
                + "interface internal is lock namespace new null operator out override params private protected "
                + "public readonly ref return sealed sizeof stackalloc static struct switch this throw true try "
                + "typeof unchecked unsafe using virtual volatile while var async await dynamic get set value "
                + "yield partial record init where nameof when required global",
                "bool byte char decimal double float int long object sbyte short string uint ulong ushort void nint nuint");
        cs.lineas = C_COMENTARIOS;
        cs.bloques = C_BLOQUES;
        cs.almohadilla = true;
        cs.mayusTipos = true;
        ext(cs, "cs", "csx");
        alias(cs, "cs", "csharp", "c#");
        mime(cs, "text/x-csharp");

        Lenguaje java = def("java", GENERICO, "abstract assert break case catch class const continue default do "
                + "else enum extends final finally for goto if implements import instanceof interface native new "
                + "package private protected public return static strictfp super switch synchronized this throw "
                + "throws transient try volatile while var record sealed permits yield true false null non-sealed",
                "boolean byte char short int long float double void");
        java.lineas = C_COMENTARIOS;
        java.bloques = C_BLOQUES;
        java.arroba = true;
        java.mayusTipos = true;
        java.triples = true;
        ext(java, "java", "jav", "aidl");
        alias(java, "java");
        mime(java, "text/x-java", "text/x-java-source", "text/java");

        Lenguaje kotlin = def("kotlin", GENERICO, "as break class continue do else false for fun if in interface "
                + "is null object package return super this throw true try typealias typeof val var when while by "
                + "catch constructor delegate dynamic field file finally get import init param property receiver "
                + "set setparam where actual abstract annotation companion const crossinline data enum expect "
                + "external final infix inline inner internal lateinit noinline open operator out override "
                + "private protected public reified sealed suspend tailrec vararg value it",
                "Int Long Short Byte Double Float Boolean Char String Unit Any Nothing Array List Map Set");
        kotlin.lineas = C_COMENTARIOS;
        kotlin.bloques = C_BLOQUES;
        kotlin.arroba = true;
        kotlin.mayusTipos = true;
        kotlin.triples = true;
        kotlin.dolar = true;
        ext(kotlin, "kt", "kts", "ktm");
        alias(kotlin, "kotlin", "kt", "kts");
        mime(kotlin, "text/x-kotlin");

        Lenguaje groovy = def("groovy", GENERICO, "abstract as assert break case catch class const continue def "
                + "default do else enum extends false final finally for goto if implements import in instanceof "
                + "interface new null package private protected public return static super switch this throw "
                + "throws trait true try var while apply plugin dependencies repositories android",
                "boolean byte char short int long float double void String Object");
        groovy.lineas = C_COMENTARIOS;
        groovy.bloques = C_BLOQUES;
        groovy.arroba = true;
        groovy.mayusTipos = true;
        groovy.triples = true;
        groovy.dolar = true;
        ext(groovy, "groovy", "gradle", "gvy", "gy", "gsh");
        alias(groovy, "groovy", "gradle");
        mime(groovy, "text/x-groovy");

        Lenguaje swift = def("swift", GENERICO, "associatedtype class deinit enum extension fileprivate func "
                + "import init inout internal let open operator private protocol public rethrows static struct "
                + "subscript typealias var break case continue default defer do else fallthrough for guard if in "
                + "repeat return switch where while as catch false is nil super self Self throw throws true try "
                + "async await some any mutating nonmutating override final lazy weak unowned convenience required",
                "Int Int8 Int16 Int32 Int64 UInt Double Float Bool String Character Void Any AnyObject");
        swift.lineas = C_COMENTARIOS;
        swift.bloques = C_BLOQUES;
        swift.arroba = true;
        swift.mayusTipos = true;
        swift.triples = true;
        swift.almohadilla = true;
        ext(swift, "swift");
        alias(swift, "swift");
        mime(swift, "text/x-swift");

        Lenguaje dart = def("dart", GENERICO, "abstract as assert async await break case catch class const "
                + "continue covariant default deferred do dynamic else enum export extends extension external "
                + "factory false final finally for get hide if implements import in interface is late library "
                + "mixin new null on operator part required rethrow return set show static super switch sync "
                + "this throw true try typedef var while with yield",
                "int double num bool void String List Map Set Future Stream Object Function dynamic");
        dart.lineas = C_COMENTARIOS;
        dart.bloques = C_BLOQUES;
        dart.arroba = true;
        dart.mayusTipos = true;
        dart.triples = true;
        dart.dolar = true;
        ext(dart, "dart");
        alias(dart, "dart", "flutter");
        mime(dart, "text/x-dart", "application/dart");

        Lenguaje go = def("go", GENERICO, "break case chan const continue default defer else fallthrough for "
                + "func go goto if import interface map package range return select struct switch type var "
                + "true false nil iota",
                "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string "
                + "uint uint8 uint16 uint32 uint64 uintptr any comparable append cap clear close complex copy "
                + "delete imag len make max min new panic print println real recover");
        go.lineas = C_COMENTARIOS;
        go.bloques = C_BLOQUES;
        go.plantilla = true;
        ext(go, "go");
        alias(go, "go", "golang");
        mime(go, "text/x-go");

        Lenguaje rust = def("rust", GENERICO, "as async await break const continue crate dyn else enum extern "
                + "false fn for if impl in let loop match mod move mut pub ref return self Self static struct "
                + "super trait true type unsafe use where while macro_rules union yield",
                "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option "
                + "Result Box Rc Arc RefCell Cell HashMap HashSet Some None Ok Err");
        rust.lineas = C_COMENTARIOS;
        rust.bloques = C_BLOQUES;
        rust.comillas = "\"'";
        rust.vidas = true;
        rust.macros = true;
        rust.mayusTipos = true;
        rust.multilinea = true;
        rust.almohadilla = true;
        ext(rust, "rs");
        alias(rust, "rust", "rs");
        mime(rust, "text/x-rust", "text/rust");

        String jsClaves = "break case catch class const continue debugger default delete do else export extends "
                + "finally for function if import in instanceof let new return super switch this throw try typeof "
                + "var void while with yield async await of static get set true false null undefined NaN Infinity "
                + "from as";
        String jsTipos = "Array Object String Number Boolean Promise Map Set WeakMap WeakSet Date Math JSON "
                + "RegExp Error TypeError Symbol BigInt Proxy Reflect console window document globalThis "
                + "require module exports process";
        JS = def("js", GENERICO, jsClaves, jsTipos);
        JS.lineas = C_COMENTARIOS;
        JS.bloques = C_BLOQUES;
        JS.plantilla = true;
        JS.regex = true;
        JS.dolarIdent = true;
        JS.arroba = true;
        ext(JS, "js", "mjs", "cjs", "jsx", "es6", "jsm");
        alias(JS, "js", "javascript", "jsx", "mjs", "cjs", "node", "es6", "ecmascript");
        mime(JS, "application/javascript", "text/javascript", "application/x-javascript",
                "application/ecmascript", "text/ecmascript", "text/jsx");

        TS = def("ts", GENERICO, jsClaves + " interface type enum implements namespace declare abstract "
                + "private protected public readonly keyof infer is satisfies override module unique asserts",
                jsTipos + " any unknown never string number boolean symbol bigint object void Record Partial "
                + "Readonly Pick Omit Exclude Extract ReturnType");
        TS.lineas = C_COMENTARIOS;
        TS.bloques = C_BLOQUES;
        TS.plantilla = true;
        TS.regex = true;
        TS.dolarIdent = true;
        TS.arroba = true;
        ext(TS, "ts", "tsx", "mts", "cts");
        alias(TS, "ts", "typescript", "tsx", "mts", "cts");
        mime(TS, "application/typescript", "application/x-typescript", "text/typescript", "text/x-typescript");

        Lenguaje py = def("python", GENERICO, "False None True and as assert async await break class continue "
                + "def del elif else except finally for from global if import in is lambda nonlocal not or pass "
                + "raise return try while with yield match case self cls",
                "print len range int str float list dict set tuple bool bytes bytearray type object isinstance "
                + "issubclass open super enumerate zip map filter sorted reversed min max sum abs any all repr "
                + "hasattr getattr setattr delattr input iter next round format id hash callable vars dir "
                + "Exception ValueError TypeError KeyError IndexError RuntimeError OSError StopIteration "
                + "NotImplementedError AttributeError ImportError staticmethod classmethod property");
        py.lineas = new String[] {"#"};
        py.triples = true;
        py.arroba = true;
        ext(py, "py", "pyw", "pyi", "pyx", "pxd", "gyp", "gypi", "bzl", "sconstruct", "sconscript");
        alias(py, "py", "python", "python3", "py3", "python2", "gyp", "bazel", "starlark", "sage");
        mime(py, "text/x-python", "text/x-python3", "text/x-script.python", "application/x-python");

        Lenguaje rb = def("ruby", GENERICO, "alias and begin break case class def defined? do else elsif end "
                + "ensure false for if in module next nil not or redo rescue retry return self super then true "
                + "undef unless until when while yield __method__ __FILE__ __LINE__",
                "require require_relative attr_accessor attr_reader attr_writer puts print p include extend "
                + "private public protected lambda proc raise loop new");
        rb.lineas = new String[] {"#"};
        rb.bloques = new String[] {"^=begin", "=end"};
        rb.simbolos = true;
        rb.mayusTipos = true;
        rb.multilinea = true;
        rb.dolar = true;
        ext(rb, "rb", "rake", "gemspec", "ru", "rbw", "podspec", "jbuilder");
        alias(rb, "rb", "ruby", "rake", "gemspec");
        mime(rb, "text/x-ruby", "application/x-ruby", "text/ruby");
        POR_NOMBRE.put("gemfile", rb);
        POR_NOMBRE.put("rakefile", rb);
        POR_NOMBRE.put("vagrantfile", rb);
        POR_NOMBRE.put("podfile", rb);
        POR_NOMBRE.put("brewfile", rb);
        POR_NOMBRE.put("fastfile", rb);

        Lenguaje php = def("php", GENERICO, "abstract and array as break callable case catch class clone const "
                + "continue declare default do echo else elseif empty enddeclare endfor endforeach endif "
                + "endswitch endwhile extends final finally fn for foreach function global goto if implements "
                + "include include_once instanceof insteadof interface isset list match namespace new or print "
                + "private protected public readonly require require_once return static switch throw trait try "
                + "unset use var while xor yield true false null TRUE FALSE NULL self parent enum",
                "int float bool string void mixed object iterable never");
        php.lineas = new String[] {"//", "#"};
        php.bloques = C_BLOQUES;
        php.dolar = true;
        php.multilinea = true;
        php.mayusTipos = true;
        php.arroba = false;
        ext(php, "php", "php3", "php4", "php5", "php7", "php8", "phtml", "phps", "inc");
        alias(php, "php");
        mime(php, "text/x-php", "application/x-php", "application/x-httpd-php", "text/php", "application/php");

        Lenguaje perl = def("perl", GENERICO, "my our local sub if elsif else unless while until for foreach "
                + "do last next redo return use no require package BEGIN END and or not eq ne lt gt le ge cmp",
                "print printf say die warn eval defined undef ref scalar shift unshift push pop splice keys values "
                + "each exists delete open close chomp chop split join map grep sort reverse length substr "
                + "lc uc sprintf bless wantarray qw");
        perl.lineas = new String[] {"#"};
        perl.bloques = new String[] {"^=pod", "=cut", "^=head", "=cut", "^=begin", "=cut"};
        perl.dolar = true;
        perl.multilinea = true;
        ext(perl, "pl", "pm", "t", "pod", "psgi");
        alias(perl, "perl", "pl", "pm");
        mime(perl, "text/x-perl", "application/x-perl", "text/x-perl-script");

        Lenguaje lua = def("lua", GENERICO, "and break do else elseif end false for function goto if in local "
                + "nil not or repeat return then true until while self",
                "print pairs ipairs require type tostring tonumber table string math os io coroutine "
                + "setmetatable getmetatable rawget rawset pcall xpcall error assert select unpack next");
        lua.bloques = new String[] {"--[[", "]]", "--[==[", "]==]", "--[=[", "]=]"};
        lua.lineas = new String[] {"--"};
        lua.largasLua = true;
        ext(lua, "lua", "luau", "rockspec");
        alias(lua, "lua", "luau");
        mime(lua, "text/x-lua", "application/x-lua");

        Lenguaje r = def("r", GENERICO, "if else repeat while function for in next break TRUE FALSE NULL Inf "
                + "NaN NA NA_integer_ NA_real_ NA_character_ return",
                "library require c list data.frame matrix vector print paste paste0 cat length names "
                + "sapply lapply apply function mean sum");
        r.lineas = new String[] {"#"};
        r.multilinea = true;
        ext(r, "r");
        alias(r, "r", "rscript", "splus");
        mime(r, "text/x-r", "text/x-r-source");

        Lenguaje sql = def("sql", GENERICO, "select from where and or not insert into values update set delete "
                + "create table drop alter add column primary key foreign references index view as join inner "
                + "left right outer full cross on group by order having limit offset union all distinct case "
                + "when then else end is null in exists between like ilike glob begin commit rollback "
                + "transaction if default unique check constraint database schema trigger procedure function "
                + "returns return declare cast with recursive asc desc true false grant revoke truncate "
                + "rename to using natural over partition window replace pragma vacuum explain analyze "
                + "autoincrement auto_increment temporary temp each row before after instead of for do "
                + "language returning conflict nothing upsert merge matched",
                "integer int varchar char text boolean bool date time timestamp timestamptz interval real float "
                + "double precision numeric decimal serial bigserial bigint smallint tinyint blob clob json "
                + "jsonb uuid bytea count sum avg min max coalesce nullif now lower upper length substr "
                + "substring trim round abs");
        sql.lineas = new String[] {"--"};
        sql.bloques = C_BLOQUES;
        sql.comillas = "'\"`";
        sql.sinMayus = true;
        sql.dobladas = true;
        sql.sinEscapes = true;
        sql.multilinea = true;
        ext(sql, "sql", "ddl", "dml", "pgsql", "mysql", "psql", "sqlite");
        alias(sql, "sql", "mysql", "postgres", "postgresql", "pgsql", "sqlite", "plsql", "tsql", "mssql");
        mime(sql, "application/sql", "text/x-sql", "application/x-sql", "text/sql");

        SH = def("sh", GENERICO, "if then else elif fi case esac for while until do done in function select "
                + "time return break continue local export readonly declare typeset unset shift exit source "
                + "alias unalias trap eval exec set let coproc",
                "echo printf read cd pwd pushd popd test cat grep egrep fgrep sed awk ls rm cp mv mkdir rmdir "
                + "chmod chown ln find xargs sort uniq head tail cut tr wc tee sudo apt apt-get pkg git curl "
                + "wget tar gzip unzip zip make sleep kill ps env basename dirname true false command type "
                + "getopts wait jobs bg fg umask ulimit mktemp date touch which whoami");
        SH.lineas = new String[] {"#"};
        SH.dolar = true;
        SH.heredoc = true;
        SH.crudaSimple = true;
        SH.multilinea = true;
        ext(SH, "sh", "bash", "zsh", "ksh", "ash", "dash", "fish", "command", "tool", "bats", "ebuild", "eclass");
        alias(SH, "sh", "bash", "shell", "zsh", "ksh", "console", "shellsession", "shell-session", "terminal",
                "fish", "termux");
        mime(SH, "application/x-sh", "application/x-shellscript", "text/x-sh", "text/x-shellscript",
                "application/x-bash", "text/x-script.sh");
        for (String n : new String[] {".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".bash_aliases",
                ".profile", ".zshrc", ".zshenv", ".zprofile", ".zlogin", ".zlogout", ".kshrc", ".mkshrc",
                ".xinitrc", ".xprofile", ".xsession", "pkgbuild", "apkbuild", "configure", "gradlew",
                "build.sh", ".envrc", "bashrc", "zshrc", "profile"}) {
            POR_NOMBRE.put(n, SH);
        }

        Lenguaje ps = def("powershell", GENERICO, "begin break catch class continue data do dynamicparam else "
                + "elseif end exit filter finally for foreach from function if in param process return switch "
                + "throw trap try until using var while workflow parallel sequence inlinescript",
                "write-host write-output get-item get-childitem set-location new-object select-object "
                + "where-object foreach-object import-module invoke-command");
        ps.lineas = new String[] {"#"};
        ps.bloques = new String[] {"<#", "#>"};
        ps.dolar = true;
        ps.sinMayus = true;
        ps.multilinea = true;
        ps.dobladas = true;
        ext(ps, "ps1", "psm1", "psd1");
        alias(ps, "ps1", "powershell", "pwsh", "posh");
        mime(ps, "application/x-powershell", "text/x-powershell");

        // Visual Basic: VBA (macros de Office), VBScript, VB.NET y LibreOffice Basic.
        VB = def("vb", GENERICO, "and as byref byval call case const declare dim do each else elseif end enum "
                + "erase error event exit explicit false for friend function get global gosub goto if implements in "
                + "is let like lib loop me mod new next not nothing null on option optional or paramarray preserve "
                + "private property public raiseevent redim resume return select set static step stop sub then to "
                + "true type typeof until wend while with withevents xor imports inherits module namespace class "
                + "try catch finally throw using handles overrides shared readonly",
                "boolean byte currency date decimal double integer long longlong longptr object single string "
                + "variant collection range worksheet workbook application");
        VB.lineas = new String[] {"'", "^rem"};
        VB.comillas = "\"";
        VB.sinMayus = true;
        VB.dobladas = true;
        VB.sinEscapes = true;
        ext(VB, "bas", "cls", "frm", "vb", "vbs", "vba", "xba");
        alias(VB, "vb", "vba", "vbs", "vbscript", "vbnet", "vb.net", "basic", "starbasic");
        mime(VB, "text/vbscript", "text/x-vb", "application/x-vbscript");

        Lenguaje bat = def("bat", GENERICO, "if else for in do goto call exit set setlocal endlocal echo off on "
                + "not exist defined errorlevel equ neq lss leq gtr geq shift pushd popd cd chdir pause start "
                + "title cls copy del move ren md rd mkdir rmdir type choice",
                "");
        bat.lineas = new String[] {"^rem", "^@rem", "^::"};
        bat.comillas = "\"";
        bat.sinMayus = true;
        bat.porcentaje = true;
        bat.sinEscapes = true;
        ext(bat, "bat", "cmd", "btm");
        alias(bat, "bat", "cmd", "batch", "dosbatch", "winbatch");
        mime(bat, "application/x-bat", "application/bat", "text/x-bat", "application/x-msdos-batch");

        Lenguaje docker = def("dockerfile", GENERICO, "from run cmd label maintainer expose env add copy "
                + "entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as", "");
        docker.lineas = new String[] {"#"};
        docker.sinMayus = true;
        docker.clavesInicio = true;
        docker.dolar = true;
        docker.heredoc = true;
        ext(docker, "dockerfile", "containerfile");
        alias(docker, "dockerfile", "docker", "containerfile");
        mime(docker, "text/x-dockerfile");
        POR_NOMBRE.put("dockerfile", docker);
        POR_NOMBRE.put("containerfile", docker);

        Lenguaje make = def("make", GENERICO, "ifeq ifneq ifdef ifndef else endif include -include sinclude "
                + "define endef export unexport override vpath private",
                "wildcard patsubst subst filter filter-out sort word words dir notdir suffix basename addsuffix "
                + "addprefix join foreach call shell origin error warning info abspath realpath strip findstring");
        make.lineas = new String[] {"#"};
        make.dolar = true;
        make.heredoc = true;
        make.multilinea = false;
        ext(make, "mk", "mak", "make", "makefile");
        alias(make, "make", "makefile", "mk", "mf");
        mime(make, "text/x-makefile");
        for (String n : new String[] {"makefile", "gnumakefile", "bsdmakefile", "kbuild", "android.mk", "application.mk"}) {
            POR_NOMBRE.put(n, make);
        }

        Lenguaje cmake = def("cmake", GENERICO, "if else elseif endif foreach endforeach while endwhile function "
                + "endfunction macro endmacro return break continue block endblock",
                "set unset project add_executable add_library target_link_libraries include_directories "
                + "cmake_minimum_required find_package option message install add_subdirectory "
                + "target_include_directories target_compile_options target_compile_definitions "
                + "add_definitions add_compile_options list string file include configure_file "
                + "add_custom_command add_custom_target set_target_properties get_filename_component");
        cmake.lineas = new String[] {"#"};
        cmake.dolar = true;
        cmake.sinMayus = true;
        cmake.multilinea = true;
        ext(cmake, "cmake");
        alias(cmake, "cmake");
        mime(cmake, "text/x-cmake");
        POR_NOMBRE.put("cmakelists.txt", cmake);

        // --- Marcado ---
        HTML_L = new Lenguaje("html", MARCADO);
        HTML_L.html = true;
        HTML_L.pagina = true;
        ext(HTML_L, "html", "htm", "xhtml", "xht", "shtml", "jsp", "asp", "aspx", "erb", "ejs", "hbs",
                "handlebars", "mustache", "twig", "njk", "jinja", "jinja2", "liquid", "cshtml", "razor");
        alias(HTML_L, "html", "htm", "xhtml", "jinja", "jinja2", "erb", "ejs", "handlebars", "hbs", "liquid", "twig");
        mime(HTML_L, "text/html", "application/xhtml+xml");
        // .html y .htm se pueden ver como página; las plantillas no.
        Lenguaje plantillas = new Lenguaje("html-plantilla", MARCADO);
        plantillas.html = true;
        ext(plantillas, "jsp", "asp", "aspx", "erb", "ejs", "hbs", "handlebars", "mustache", "twig", "njk",
                "jinja", "jinja2", "liquid", "cshtml", "razor", "shtml");

        Lenguaje vue = new Lenguaje("vue", MARCADO);
        vue.html = true;
        ext(vue, "vue", "svelte", "astro");
        alias(vue, "vue", "svelte", "astro");
        mime(vue, "text/x-vue");

        XML_L = new Lenguaje("xml", MARCADO);
        ext(XML_L, "xml", "xsd", "xsl", "xslt", "dtd", "plist", "rss", "atom", "xaml", "xlf", "xliff", "csproj",
                "vbproj", "fsproj", "props", "targets", "nuspec", "resx", "wsdl", "pom", "iml", "manifest",
                "storyboard", "xib", "kml", "gpx", "opml", "fxml", "tld", "jnlp", "mxml", "glade", "ui", "qrc",
                "rdf", "owl", "launch", "urdf", "tmx", "xspf", "musicxml", "nfo");
        alias(XML_L, "xml", "xsd", "xsl", "xslt", "plist", "rss", "atom", "xaml", "wsdl", "pom", "svg", "mathml");
        mime(XML_L, "application/xml", "text/xml", "application/rss+xml", "application/atom+xml",
                "application/xslt+xml", "application/x-plist", "application/mathml+xml", "application/rdf+xml",
                "application/xml-dtd");
        Lenguaje svg = new Lenguaje("svg", MARCADO);
        svg.html = true;
        svg.pagina = true;
        ext(svg, "svg");
        mime(svg, "image/svg+xml");

        // --- Estilos ---
        CSS_L = new Lenguaje("css", CSS);
        ext(CSS_L, "css");
        alias(CSS_L, "css");
        mime(CSS_L, "text/css");
        SCSS = new Lenguaje("scss", CSS);
        SCSS.scss = true;
        ext(SCSS, "scss", "sass");
        alias(SCSS, "scss", "sass");
        mime(SCSS, "text/x-scss", "text/x-sass");
        LESS = new Lenguaje("less", CSS);
        LESS.less = true;
        ext(LESS, "less", "styl");
        alias(LESS, "less", "stylus");
        mime(LESS, "text/x-less");

        // --- Datos y configuración ---
        JSON_L = new Lenguaje("json", JSON);
        ext(JSON_L, "json", "jsonc", "json5", "jsonl", "ndjson", "geojson", "webmanifest", "har", "ipynb",
                "babelrc", "eslintrc", "prettierrc", "jshintrc", "tsbuildinfo", "map", "code-workspace",
                "sublime-settings", "sublime-project", "arb", "avsc", "topojson");
        alias(JSON_L, "json", "jsonc", "json5", "jsonl", "ndjson", "geojson");
        mime(JSON_L, "application/json", "text/json", "text/x-json", "application/x-json", "application/ld+json",
                "application/geo+json", "application/manifest+json", "application/vnd.api+json",
                "application/x-ipynb+json", "application/json5", "application/jsonc");
        for (String n : new String[] {".babelrc", ".eslintrc", ".prettierrc", ".jshintrc", ".swcrc",
                "composer.lock", ".watchmanconfig", "flake.lock"}) {
            POR_NOMBRE.put(n, JSON_L);
        }

        Lenguaje yaml = new Lenguaje("yaml", YAML);
        ext(yaml, "yaml", "yml", "clang-format", "clang-tidy", "gemrc", "condarc");
        alias(yaml, "yaml", "yml");
        mime(yaml, "application/x-yaml", "application/yaml", "text/yaml", "text/x-yaml");
        for (String n : new String[] {".clang-format", ".clang-tidy", ".gemrc", ".condarc", ".yamllint",
                "pubspec.lock", ".travis.yml"}) {
            POR_NOMBRE.put(n, yaml);
        }

        Lenguaje toml = new Lenguaje("toml", TOML);
        ext(toml, "toml", "tml");
        alias(toml, "toml");
        mime(toml, "application/toml", "text/x-toml", "application/x-toml");
        for (String n : new String[] {"cargo.lock", "pipfile", "poetry.lock", "uv.lock"}) POR_NOMBRE.put(n, toml);

        Lenguaje ini = new Lenguaje("ini", INI);
        ext(ini, "ini", "cfg", "conf", "config", "cnf", "properties", "prop", "env", "desktop",
                "service", "socket", "timer", "mount", "target", "path", "inf", "reg", "editorconfig",
                "gitconfig", "gitmodules", "npmrc", "yarnrc", "pylintrc", "flake8", "coveragerc", "lst", "rc",
                "tmux", "theme", "pro", "pri", "hgrc", "wgetrc", "curlrc", "nanorc", "vimrc");
        alias(ini, "ini", "cfg", "conf", "config", "properties", "dosini", "env", "dotenv", "editorconfig",
                "gitconfig", "systemd", "desktop", "toml-ish", "nginx", "apache", "apacheconf");
        mime(ini, "text/x-ini", "text/ini", "application/x-ini", "text/x-properties", "text/x-java-properties",
                "application/x-wine-extension-ini", "application/textedit", "application/x-desktop",
                "text/x-dotenv");
        for (String n : new String[] {".env", ".editorconfig", ".gitconfig", ".gitmodules", ".npmrc", ".yarnrc",
                ".pylintrc", ".flake8", ".coveragerc", ".hgrc", ".wgetrc", ".curlrc", ".nanorc", ".inputrc",
                ".tmux.conf", ".gdbinit", ".pypirc", ".netrc", ".htaccess", ".mailmap", "setup.cfg", "tox.ini",
                "pytest.ini", "gradle.properties", "local.properties", "gradle-wrapper.properties",
                "termux.properties", "colors.properties", "nginx.conf", "sshd_config", "ssh_config",
                "config", "hosts", "fstab", "crontab", ".vimrc", ".exrc", ".muttrc", ".screenrc"}) {
            POR_NOMBRE.put(n, ini);
        }

        Lenguaje ignorar = new Lenguaje("ignore", GENERICO);
        ignorar.lineas = new String[] {"^#"};
        ignorar.soloComentarios = true;
        ext(ignorar, "gitignore", "dockerignore", "npmignore", "gitattributes", "hgignore", "eslintignore",
                "prettierignore", "gcloudignore", "slugignore", "helmignore", "stignore");
        alias(ignorar, "gitignore", "ignore", "gitattributes");
        for (String n : new String[] {".gitignore", ".dockerignore", ".npmignore", ".gitattributes", ".hgignore",
                ".eslintignore", ".prettierignore", ".gcloudignore", ".stignore", "codeowners", ".nvmrc",
                ".python-version", ".ruby-version", ".node-version", ".tool-versions", "requirements.txt",
                "procfile", ".gitkeep", ".keep"}) {
            POR_NOMBRE.put(n, ignorar);
        }

        Lenguaje diff = new Lenguaje("diff", DIFF);
        ext(diff, "diff", "patch", "rej", "debdiff");
        alias(diff, "diff", "patch", "udiff");
        mime(diff, "text/x-diff", "text/x-patch", "text/diff", "application/x-patch");
    }

    // --- Detección ---

    private static String extension(String nombre) {
        if (nombre == null) return "";
        int i = nombre.lastIndexOf('.');
        return i >= 0 ? nombre.substring(i + 1).trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Lenguaje por el nombre del archivo (nombres especiales como "Dockerfile" o ".gitignore") y,
     * si no, por la extensión. null si no es un archivo de código conocido.
     */
    static Lenguaje porArchivo(String nombre) {
        if (nombre == null) return null;
        String n = nombre.trim().toLowerCase(Locale.ROOT);
        int barra = n.lastIndexOf('/');
        if (barra >= 0) n = n.substring(barra + 1);
        Lenguaje l = POR_NOMBRE.get(n);
        if (l != null) return l;
        // Dockerfile.dev, Makefile.am, .env.local, .bashrc.bak…
        if (n.startsWith("dockerfile") || n.endsWith(".dockerfile")) return POR_NOMBRE.get("dockerfile");
        if (n.startsWith("makefile") || n.equals("makefile.am") || n.equals("makefile.in")) return POR_NOMBRE.get("makefile");
        if (n.startsWith(".env.") || n.equals("env") || n.startsWith("env.")) return POR_NOMBRE.get(".env");
        if (n.startsWith("requirements") && n.endsWith(".txt")) return POR_NOMBRE.get("requirements.txt");
        if (n.startsWith("jenkinsfile")) return POR_EXT.get("groovy");
        if (n.startsWith(".") && n.indexOf('.', 1) < 0) {
            // Archivos de punto sin extensión: .bashrc → por su nombre sin el punto.
            Lenguaje p = POR_EXT.get(n.substring(1));
            if (p != null) return p;
        }
        return POR_EXT.get(extension(n));
    }

    /** Lenguaje por el tipo MIME (text/x-python, application/json…). null si no se conoce. */
    static Lenguaje porMime(String mime) {
        if (mime == null) return null;
        String m = mime.toLowerCase(Locale.ROOT);
        int pc = m.indexOf(';');
        if (pc >= 0) m = m.substring(0, pc).trim();
        Lenguaje l = POR_MIME.get(m);
        if (l != null) return l;
        if (m.endsWith("+json")) return JSON_L;
        if (m.endsWith("+xml") && !m.startsWith("image/")) return XML_L;
        return null;
    }

    /** Lenguaje por la etiqueta de un bloque de código de Markdown (```python). */
    static Lenguaje porEtiqueta(String etiqueta) {
        if (etiqueta == null || etiqueta.isEmpty()) return null;
        String e = etiqueta.trim().toLowerCase(Locale.ROOT);
        if (e.startsWith("{") && e.endsWith("}")) e = e.substring(1, e.length() - 1).trim();
        if (e.startsWith(".")) e = e.substring(1);
        if (e.startsWith("language-")) e = e.substring(9);
        Lenguaje l = POR_ETIQUETA.get(e);
        return l != null ? l : POR_EXT.get(e);
    }

    /** Lenguaje por la primera línea "#!/usr/bin/env python3" de un archivo sin extensión conocida. */
    static Lenguaje porShebang(String texto) {
        if (texto == null || !texto.startsWith("#!")) return null;
        int fin = texto.indexOf('\n');
        String l = (fin < 0 ? texto : texto.substring(0, Math.min(fin, 200))).toLowerCase(Locale.ROOT);
        if (l.contains("python")) return POR_ETIQUETA.get("python");
        if (l.contains("node") || l.contains("deno") || l.contains("bun")) return l.contains("ts-node") ? TS : JS;
        if (l.contains("ruby")) return POR_ETIQUETA.get("ruby");
        if (l.contains("perl")) return POR_ETIQUETA.get("perl");
        if (l.contains("php")) return POR_ETIQUETA.get("php");
        if (l.contains("lua")) return POR_ETIQUETA.get("lua");
        if (l.contains("rscript")) return POR_ETIQUETA.get("r");
        if (l.contains("pwsh") || l.contains("powershell")) return POR_ETIQUETA.get("powershell");
        if (l.contains("make")) return POR_ETIQUETA.get("make");
        if (l.contains("sh") || l.contains("bash") || l.contains("zsh") || l.contains("ksh") || l.contains("fish")) return SH;
        return null;
    }

    // --- Conversión ---

    /** Documento del visor de código: líneas numeradas y resaltadas. */
    static Html.Documento convertir(byte[] datos, Lenguaje len) {
        String s = Texto.decodificar(datos);
        boolean cortado = s.length() > Texto.MAX_CARACTERES;
        if (cortado) s = s.substring(0, Texto.MAX_CARACTERES);
        s = normalizarSaltos(s);
        if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        boolean colores = s.length() <= MAX_RESALTADO;

        Fichas f = new Fichas();
        if (colores) analizarSeguro(len, s, f);

        int lineas = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lineas++;
        int cifras = Math.max(2, Integer.toString(lineas).length());

        StringBuilder sb = new StringBuilder(s.length() + s.length() / 2 + lineas * 12 + 512);
        sb.append("<div class=fuente style=\"--d:").append(cifras).append("ch\"><div class=lin><div>");
        emitir(sb, s, f, true);
        sb.append("</div></div></div>");
        if (s.isEmpty()) sb.append("<p class=nota>El archivo está vacío.</p>");
        if (cortado) sb.append("<p class=nota>El archivo es muy largo: se muestra solo el principio, sin colores.</p>");
        else if (!colores) sb.append("<p class=nota>El archivo es muy grande: se muestra sin colores.</p>");

        Html.Documento d = new Html.Documento(sb.toString(), CSS_VISOR, Html.VIEWPORT_MOVIL, "codigo");
        d.cssNoche = CSS_NOCHE;
        d.cssAjustar = CSS_AJUSTAR;
        if (len.pagina) {
            if (len.esSvg()) {
                d.paginaWeb = "<style>body{margin:0;padding:12px;box-sizing:border-box;min-height:100vh;"
                        + "display:flex;align-items:center;justify-content:center;background:#fff}"
                        + "img{max-width:100%;height:auto}</style><img alt=\"\" src=\"data:image/svg+xml;base64,"
                        + Base64.getEncoder().encodeToString(datos) + "\">";
            } else {
                d.paginaWeb = s;
            }
        }
        return d;
    }

    static String normalizarSaltos(String s) {
        if (s.indexOf('\r') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') continue;
                sb.append('\n');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Fragmento resaltado para meter en un &lt;pre&gt; (bloques de código de Markdown). */
    static void resaltar(StringBuilder sb, String s, Lenguaje len) {
        Fichas f = new Fichas();
        if (s.length() <= MAX_RESALTADO) analizarSeguro(len, s, f);
        emitir(sb, s, f, false);
    }

    /** Analiza sin dejar que un fallo inesperado impida mostrar el texto (se muestra sin colores). */
    private static void analizarSeguro(Lenguaje len, String s, Fichas f) {
        try {
            analizar(len, s, 0, s.length(), f);
        } catch (RuntimeException e) {
            f.n = 0;
        }
    }

    /**
     * Escribe el texto con sus fichas. Con porLineas, cada salto de línea cierra la línea
     * ("&lt;/div&gt;&lt;div&gt;") y una ficha que cruza varias líneas se reabre en cada una.
     */
    private static void emitir(StringBuilder sb, String s, Fichas f, boolean porLineas) {
        int pos = 0;
        for (int k = 0; k < f.n; k += 3) {
            int a = Math.max(f.d[k], pos), b = Math.min(f.d[k + 1], s.length());
            if (b <= a) continue;
            trozo(sb, s, pos, a, 0, porLineas);
            trozo(sb, s, a, b, f.d[k + 2], porLineas);
            pos = b;
        }
        trozo(sb, s, pos, s.length(), 0, porLineas);
    }

    private static void trozo(StringBuilder sb, String s, int a, int b, int clase, boolean porLineas) {
        if (b <= a) return;
        if (!porLineas || clase == 0) {
            if (clase != 0) sb.append("<span class=").append(CLASES[clase]).append('>');
            if (porLineas) escLineas(sb, s, a, b);
            else esc(sb, s, a, b);
            if (clase != 0) sb.append("</span>");
            return;
        }
        int i = a;
        while (i < b) {
            int nl = s.indexOf('\n', i);
            int fin = nl < 0 || nl >= b ? b : nl;
            if (fin > i) {
                sb.append("<span class=").append(CLASES[clase]).append('>');
                esc(sb, s, i, fin);
                sb.append("</span>");
            }
            if (fin < b) sb.append("</div><div>");
            i = fin + 1;
        }
    }

    private static void escLineas(StringBuilder sb, String s, int a, int b) {
        int i = a;
        while (i < b) {
            int nl = s.indexOf('\n', i);
            int fin = nl < 0 || nl >= b ? b : nl;
            esc(sb, s, i, fin);
            if (fin < b) sb.append("</div><div>");
            i = fin + 1;
        }
    }

    static void esc(StringBuilder sb, String s, int a, int b) {
        for (int i = a; i < b; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case 0: break;
                default: sb.append(c);
            }
        }
    }

    // --- Fichas ---

    /** Lista de fichas (inicio, fin, clase) en orden y sin solapes. */
    static final class Fichas {
        int[] d = new int[192];
        int n;

        void add(int ini, int fin, int clase) {
            if (fin <= ini || clase == 0) return;
            if (n > 0 && ini < d[n - 2]) {
                // Nunca debería pasar; por si acaso, se recorta para mantener el orden.
                ini = d[n - 2];
                if (fin <= ini) return;
            }
            if (n + 3 > d.length) d = Arrays.copyOf(d, d.length * 2);
            d[n++] = ini;
            d[n++] = fin;
            d[n++] = clase;
        }
    }

    static void analizar(Lenguaje l, String s, int ini, int fin, Fichas f) {
        switch (l.familia) {
            case MARCADO: marcado(l, s, ini, fin, f); break;
            case CSS: css(l, s, ini, fin, f); break;
            case JSON: json(s, ini, fin, f); break;
            case YAML: yaml(s, ini, fin, f); break;
            case INI: ini(s, ini, fin, f); break;
            case TOML: toml(s, ini, fin, f); break;
            case DIFF: diff(s, ini, fin, f); break;
            default: generico(l, s, ini, fin, f);
        }
    }

    private static int finLinea(String s, int i, int fin) {
        int nl = s.indexOf('\n', i);
        return nl < 0 || nl > fin ? fin : nl;
    }

    private static boolean esIdentIni(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean esIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // --- Analizador genérico (familia C, scripts, shell, SQL…) ---

    private static final String PREVIO_REGEX = "(,=:[!&|?{};+-*%~^";
    private static final Set<String> PALABRAS_REGEX = palabras(
            "return typeof case in of yield await void delete throw new else do instanceof");

    private static void generico(Lenguaje L, String s, int ini, int fin, Fichas f) {
        int i = ini;
        boolean inicio = true;
        char previo = 0;
        String palabraPrevia = null;
        String heredoc = null;
        while (i < fin) {
            char c = s.charAt(i);
            if (c == '\n') {
                i++;
                inicio = true;
                if (heredoc != null) {
                    i = cuerpoHeredoc(s, i, fin, heredoc, f);
                    heredoc = null;
                }
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == 0x0B) {
                i++;
                continue;
            }
            boolean alInicio = inicio;
            inicio = false;

            // Comentarios de bloque y de línea.
            int k = comentario(L, s, i, fin, alInicio, f);
            if (k > i) {
                i = k;
                continue;
            }
            if (L.soloComentarios) {
                i = finLinea(s, i, fin);
                continue;
            }

            // Cadenas.
            if (L.triples && (c == '"' || c == '\'') && i + 2 < fin && s.charAt(i + 1) == c && s.charAt(i + 2) == c) {
                int e = finTriple(s, i + 3, fin, c);
                f.add(i, e, CAD);
                i = e;
                previo = '"';
                continue;
            }
            if (c == '`' && L.plantilla) {
                int e = i + 1;
                while (e < fin && s.charAt(e) != '`') e += s.charAt(e) == '\\' && !L.id.equals("go") ? 2 : 1;
                e = Math.min(fin, e + 1);
                f.add(i, e, CAD);
                i = e;
                previo = '"';
                continue;
            }
            if (L.comillas.indexOf(c) >= 0) {
                if (c == '\'' && L.vidas && i + 1 < fin && esIdentIni(s.charAt(i + 1))) {
                    int e = i + 1;
                    while (e < fin && esIdent(s.charAt(e))) e++;
                    if (e >= fin || s.charAt(e) != '\'') {
                        f.add(i, e, TIPO); // 'a (tiempo de vida)
                        i = e;
                        previo = 'a';
                        continue;
                    }
                }
                if (c == '\'' && L.crudaSimple) {
                    int e = s.indexOf('\'', i + 1);
                    e = e < 0 || e >= fin ? fin : e + 1;
                    f.add(i, e, CAD);
                    i = e;
                    previo = '"';
                    continue;
                }
                i = cadena(L, s, i, fin, c, f);
                previo = '"';
                continue;
            }
            if (L.largasLua && c == '[' && i + 1 < fin && (s.charAt(i + 1) == '[' || s.charAt(i + 1) == '=')) {
                int j = i + 1, iguales = 0;
                while (j < fin && s.charAt(j) == '=') {
                    j++;
                    iguales++;
                }
                if (j < fin && s.charAt(j) == '[') {
                    StringBuilder z = new StringBuilder("]");
                    for (int q = 0; q < iguales; q++) z.append('=');
                    String cierre = z.append(']').toString();
                    int e = s.indexOf(cierre, j + 1);
                    e = e < 0 || e >= fin ? fin : Math.min(fin, e + cierre.length());
                    f.add(i, e, CAD);
                    i = e;
                    previo = '"';
                    continue;
                }
            }

            // Heredoc de la shell: <<FIN, <<-FIN, <<'FIN'.
            if (L.heredoc && c == '<' && i + 2 < fin && s.charAt(i + 1) == '<' && s.charAt(i + 2) != '<') {
                int j = i + 2;
                if (j < fin && (s.charAt(j) == '-' || s.charAt(j) == '~')) j++;
                while (j < fin && s.charAt(j) == ' ') j++;
                char q = j < fin ? s.charAt(j) : 0;
                if (q == '\'' || q == '"' || q == '\\') j++;
                int p0 = j;
                while (j < fin && esIdent(s.charAt(j))) j++;
                if (j > p0 && j - p0 <= 64) {
                    heredoc = s.substring(p0, j);
                    if ((q == '\'' || q == '"') && j < fin && s.charAt(j) == q) j++;
                    f.add(i, j, META);
                    i = j;
                    previo = 'a';
                    continue;
                }
            }

            // Variables.
            if (c == '$' && L.dolar) {
                int e = finVariable(s, i, fin);
                if (e > i + 1) {
                    f.add(i, e, VAR);
                    i = e;
                    previo = 'a';
                    continue;
                }
            }
            if (L.porcentaje && c == '%') {
                int e = finPorcentaje(s, i, fin);
                if (e > i + 1) {
                    f.add(i, e, VAR);
                    i = e;
                    previo = 'a';
                    continue;
                }
            }
            if (L.simbolos) {
                if (c == ':' && i + 1 < fin && esIdentIni(s.charAt(i + 1)) && previo != ':'
                        && (i == ini || s.charAt(i - 1) != ':') && !esIdent(previoChar(s, i, ini))) {
                    int e = i + 1;
                    while (e < fin && (esIdent(s.charAt(e)) || s.charAt(e) == '?' || s.charAt(e) == '!')) e++;
                    f.add(i, e, VAR);
                    i = e;
                    previo = 'a';
                    continue;
                }
                if (c == '@') {
                    int e = i + 1;
                    if (e < fin && s.charAt(e) == '@') e++;
                    int p0 = e;
                    while (e < fin && esIdent(s.charAt(e))) e++;
                    if (e > p0) {
                        f.add(i, e, VAR);
                        i = e;
                        previo = 'a';
                        continue;
                    }
                }
            }
            // @Anotaciones y @decoradores.
            if (L.arroba && c == '@' && i + 1 < fin && esIdentIni(s.charAt(i + 1))) {
                int e = i + 1;
                while (e < fin && (esIdent(s.charAt(e)) || (s.charAt(e) == '.' && e + 1 < fin && esIdentIni(s.charAt(e + 1))))) e++;
                f.add(i, e, META);
                i = e;
                previo = 'a';
                continue;
            }
            // #include y demás directivas; #[atributos] de Rust.
            if (L.almohadilla && c == '#' && alInicio) {
                int e = finLinea(s, i, fin);
                int com = s.indexOf("//", i);
                if (com >= 0 && com < e) e = com;
                com = s.indexOf("/*", i);
                if (com >= 0 && com < e) e = com;
                f.add(i, e, META);
                i = e;
                previo = 0;
                continue;
            }

            // Números.
            if (Character.isDigit(c) || (c == '.' && i + 1 < fin && Character.isDigit(s.charAt(i + 1)))) {
                int e = finNumero(s, i, fin);
                f.add(i, e, NUM);
                i = e;
                previo = '0';
                continue;
            }

            // Identificadores y palabras clave.
            if (esIdentIni(c) || (c == '$' && L.dolarIdent)) {
                int e = i + 1;
                while (e < fin) {
                    char d = s.charAt(e);
                    if (esIdent(d) || (d == '$' && L.dolarIdent) || (d == '-' && L.id.equals("make") && e + 1 < fin && esIdent(s.charAt(e + 1)))) e++;
                    else break;
                }
                if (L.simbolos && e < fin && (s.charAt(e) == '?' || s.charAt(e) == '!')
                        && (e + 1 >= fin || s.charAt(e + 1) != '=')) e++;
                int clase = 0;
                // Tras "." es un miembro (obj.delete, archivo.zip); en la shell, tras "-" o "/" es una opción o ruta.
                char antes = i > ini ? s.charAt(i - 1) : ' ';
                boolean miembro = (antes == '.' && (i - 2 < ini || s.charAt(i - 2) != '.'))
                        || (L.heredoc && (antes == '-' || antes == '/' || antes == '=' || antes == ':'));
                String w = e - i <= 32 && !miembro ? s.substring(i, e) : null;
                String wk = w == null ? null : L.sinMayus ? w.toLowerCase(Locale.ROOT) : w;
                if (wk != null && L.claves.contains(wk) && (!L.clavesInicio || alInicio)) {
                    clase = CLAVE;
                } else if (wk != null && L.tipos.contains(wk)) {
                    clase = TIPO;
                } else if (L.macros && e < fin && s.charAt(e) == '!' && (e + 1 >= fin || s.charAt(e + 1) != '=')) {
                    e++;
                    clase = FUN;
                } else {
                    int p = e;
                    while (p < fin && p - e < 16 && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                    if (p < fin && s.charAt(p) == '(' && !L.heredoc) clase = FUN;
                    else if (L.mayusTipos && Character.isUpperCase(c)) clase = todoMayusculas(s, i, e) ? NUM : TIPO;
                }
                f.add(i, e, clase);
                previo = 'a';
                palabraPrevia = w;
                i = e;
                continue;
            }

            // Expresiones regulares de JavaScript.
            if (L.regex && c == '/' && i + 1 < fin && s.charAt(i + 1) != '/' && s.charAt(i + 1) != '*'
                    && (previo == 0 || PREVIO_REGEX.indexOf(previo) >= 0
                    || (previo == 'a' && palabraPrevia != null && PALABRAS_REGEX.contains(palabraPrevia)))) {
                int e = finRegex(s, i, fin);
                if (e > i) {
                    f.add(i, e, REGEX);
                    i = e;
                    previo = '"';
                    continue;
                }
            }

            previo = c;
            i++;
        }
    }

    private static char previoChar(String s, int i, int ini) {
        return i > ini ? s.charAt(i - 1) : ' ';
    }

    private static boolean todoMayusculas(String s, int a, int b) {
        if (b - a < 2) return false;
        boolean letra = false;
        for (int i = a; i < b; i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) return false;
            if (Character.isLetter(c)) letra = true;
        }
        return letra;
    }

    /** Si en i empieza un comentario, lo marca y devuelve dónde acaba; si no, devuelve i. */
    private static int comentario(Lenguaje L, String s, int i, int fin, boolean alInicio, Fichas f) {
        for (int k = 0; k + 1 < L.bloques.length; k += 2) {
            String a = L.bloques[k];
            boolean soloInicio = a.startsWith("^");
            if (soloInicio) a = a.substring(1);
            if ((!soloInicio || alInicio) && s.regionMatches(L.sinMayus, i, a, 0, a.length())) {
                String z = L.bloques[k + 1];
                int e = indexOf(s, z, i + a.length(), fin, L.sinMayus);
                e = e < 0 ? fin : Math.min(fin, e + z.length());
                if (soloInicio) e = finLinea(s, e, fin); // =end y =cut ocupan la línea
                f.add(i, e, COM);
                return e;
            }
        }
        for (String m : L.lineas) {
            boolean soloInicio = m.startsWith("^");
            if (soloInicio) m = m.substring(1);
            if (soloInicio && !alInicio) continue;
            if (!s.regionMatches(L.sinMayus, i, m, 0, m.length())) continue;
            int tras = i + m.length();
            // "rem" debe ir seguido de espacio o fin de línea; "#" de la shell, precedido de espacio.
            if (Character.isLetter(m.charAt(m.length() - 1)) && tras < fin && esIdent(s.charAt(tras))) continue;
            if (m.equals("#") && L.heredoc && i > 0) {
                char p = s.charAt(i - 1);
                if (!(Character.isWhitespace(p) || p == ';' || p == '|' || p == '&' || p == '(')) continue;
            }
            int e = finLinea(s, i, fin);
            f.add(i, e, COM);
            return e;
        }
        return i;
    }

    private static int indexOf(String s, String z, int desde, int fin, boolean sinMayus) {
        if (!sinMayus) {
            int e = s.indexOf(z, desde);
            return e < 0 || e + z.length() > fin ? -1 : e;
        }
        for (int j = desde; j + z.length() <= fin; j++) if (s.regionMatches(true, j, z, 0, z.length())) return j;
        return -1;
    }

    private static int finTriple(String s, int j, int fin, char q) {
        while (j < fin) {
            char d = s.charAt(j);
            if (d == '\\') {
                j += 2;
                continue;
            }
            if (d == q && j + 2 < fin && s.charAt(j + 1) == q && s.charAt(j + 2) == q) {
                j += 3;
                while (j < fin && s.charAt(j) == q) j++; // """"" (comilla al final del contenido)
                return j;
            }
            j++;
        }
        return fin;
    }

    /** Cadena entre comillas; en las de la shell, PHP o Kotlin marca aparte las $variables. */
    private static int cadena(Lenguaje L, String s, int i, int fin, char q, Fichas f) {
        int j = i + 1, trozo = i;
        boolean variables = L.dolar && q == '"';
        while (j < fin) {
            char d = s.charAt(j);
            if (d == '\\' && !L.sinEscapes) {
                j += 2;
                continue;
            }
            if (d == q) {
                if (L.dobladas && j + 1 < fin && s.charAt(j + 1) == q) {
                    j += 2;
                    continue;
                }
                j++;
                break;
            }
            if (d == '\n' && !L.multilinea) break;
            if (variables && d == '$' && L.heredoc && j + 1 < fin && s.charAt(j + 1) == '(') {
                j = finSubshell(s, j + 2, fin); // "$(orden "con comillas")" sigue siendo la misma cadena
                continue;
            }
            if (variables && d == '$') {
                int e = finVariable(s, j, fin);
                if (e > j + 1) {
                    f.add(trozo, j, CAD);
                    f.add(j, e, VAR);
                    trozo = j = e;
                    continue;
                }
            }
            j++;
        }
        j = Math.min(j, fin);
        f.add(trozo, j, CAD);
        return j;
    }

    /** Fin de "$(…)" desde dentro del paréntesis, saltando comillas y paréntesis anidados. */
    private static int finSubshell(String s, int j, int fin) {
        int prof = 1;
        char comilla = 0;
        while (j < fin) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (comilla != 0) {
                if (c == comilla) comilla = 0;
            } else if (c == '"' || c == '\'') {
                comilla = c;
            } else if (c == '(') {
                prof++;
            } else if (c == ')' && --prof == 0) {
                return j + 1;
            }
            j++;
        }
        return fin;
    }

    /** $nombre, ${…}, $(NOMBRE), $1, $@… Devuelve i si no hay variable. */
    private static int finVariable(String s, int i, int fin) {
        if (i + 1 >= fin) return i;
        char c = s.charAt(i + 1);
        if (c == '{' || c == '(') {
            char cierre = c == '{' ? '}' : ')';
            int j = i + 2;
            while (j < fin && j - i < 200) {
                char d = s.charAt(j);
                if (d == cierre) return j + 1;
                if (d == '\n' || (c == '(' && !(esIdent(d) || d == '.' || d == '-'))) break;
                if (c == '{' && (d == '{' || d == '$')) break;
                j++;
            }
            return i; // $( comando ) o ${ sin cerrar: no se marca
        }
        if (esIdentIni(c)) {
            int j = i + 2;
            while (j < fin && esIdent(s.charAt(j))) j++;
            return j;
        }
        if (Character.isDigit(c) || "@#?$!*-".indexOf(c) >= 0) return i + 2;
        return i;
    }

    /** %VAR%, %1, %%i, %~dp0 de los archivos .bat. */
    private static int finPorcentaje(String s, int i, int fin) {
        int j = i + 1;
        if (j < fin && s.charAt(j) == '%') j++;
        if (j < fin && s.charAt(j) == '~') {
            j++;
            while (j < fin && Character.isLetter(s.charAt(j))) j++;
            return j < fin && Character.isDigit(s.charAt(j)) ? j + 1 : j;
        }
        if (j < fin && Character.isDigit(s.charAt(j))) return j + 1;
        int p0 = j;
        while (j < fin && j - p0 < 64 && (esIdent(s.charAt(j)) || s.charAt(j) == '-')) j++;
        if (j > p0 && s.charAt(i + 1) == '%') return j; // %%i
        if (j > p0 && j < fin && s.charAt(j) == '%') return j + 1;
        return i;
    }

    private static int finNumero(String s, int i, int fin) {
        int j = i;
        boolean punto = false;
        boolean hex = s.charAt(i) == '0' && i + 1 < fin && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X');
        while (j < fin) {
            char d = s.charAt(j);
            if (Character.isLetterOrDigit(d) || d == '_') {
                j++;
                if (!hex && (d == 'e' || d == 'E') && j < fin && (s.charAt(j) == '+' || s.charAt(j) == '-')
                        && j + 1 < fin && Character.isDigit(s.charAt(j + 1))) {
                    j++;
                }
            } else if (d == '.' && !punto && j + 1 < fin && Character.isDigit(s.charAt(j + 1))) {
                punto = true;
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    private static int finRegex(String s, int i, int fin) {
        int j = i + 1;
        boolean clase = false;
        while (j < fin) {
            char d = s.charAt(j);
            if (d == '\n') return i;
            if (d == '\\') {
                j += 2;
                continue;
            }
            if (d == '[') clase = true;
            else if (d == ']') clase = false;
            else if (d == '/' && !clase) {
                j++;
                while (j < fin && Character.isLetter(s.charAt(j))) j++;
                return j;
            }
            j++;
        }
        return i;
    }

    /** Cuerpo de un heredoc desde i (principio de línea) hasta la línea con la palabra final. */
    private static int cuerpoHeredoc(String s, int i, int fin, String palabra, Fichas f) {
        int p = i;
        while (p < fin) {
            int eol = finLinea(s, p, fin);
            int a = p, b = eol;
            while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
            while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
            if (b - a == palabra.length() && s.regionMatches(a, palabra, 0, palabra.length())) {
                f.add(i, p, CAD);
                f.add(a, b, META);
                return eol;
            }
            p = eol + 1;
        }
        f.add(i, fin, CAD);
        return fin;
    }

    // --- Marcado: HTML, XML, SVG, Vue ---

    private static void marcado(Lenguaje L, String s, int ini, int fin, Fichas f) {
        int i = ini;
        while (i < fin) {
            char c = s.charAt(i);
            if (c == '&') {
                int e = finEntidad(s, i, fin);
                if (e > i) f.add(i, e, NUM);
                i = Math.max(e, i + 1);
                continue;
            }
            if (c != '<') {
                i++;
                continue;
            }
            if (s.startsWith("<!--", i)) {
                int e = s.indexOf("-->", i + 4);
                e = e < 0 || e + 3 > fin ? fin : e + 3;
                f.add(i, e, COM);
                i = e;
                continue;
            }
            if (s.startsWith("<![CDATA[", i)) {
                int e = s.indexOf("]]>", i + 9);
                f.add(i, Math.min(fin, i + 9), META);
                if (e < 0 || e + 3 > fin) {
                    i = fin;
                } else {
                    f.add(e, e + 3, META);
                    i = e + 3;
                }
                continue;
            }
            if (i + 1 < fin && (s.charAt(i + 1) == '!' || s.charAt(i + 1) == '?')) {
                String cierre = s.charAt(i + 1) == '?' ? "?>" : ">";
                int e = s.indexOf(cierre, i + 2);
                e = e < 0 || e + cierre.length() > fin ? fin : e + cierre.length();
                f.add(i, e, META);
                i = e;
                continue;
            }
            int j = i + 1;
            boolean cierre = false;
            if (j < fin && s.charAt(j) == '/') {
                cierre = true;
                j++;
            }
            if (j >= fin || !esIdentIni(s.charAt(j))) {
                i++;
                continue;
            }
            int n0 = j;
            while (j < fin && (esIdent(s.charAt(j)) || s.charAt(j) == '-' || s.charAt(j) == ':' || s.charAt(j) == '.')) j++;
            String etiqueta = j - n0 <= 16 ? s.substring(n0, j).toLowerCase(Locale.ROOT) : "";
            f.add(i, j, ETQ);
            String lang = null;
            boolean autocierre = false;
            while (j < fin) {
                char d = s.charAt(j);
                if (d == '>') {
                    f.add(j, j + 1, ETQ);
                    j++;
                    break;
                }
                if (d == '/' && j + 1 < fin && s.charAt(j + 1) == '>') {
                    f.add(j, j + 2, ETQ);
                    j += 2;
                    autocierre = true;
                    break;
                }
                if (d == '<') break;
                if (Character.isWhitespace(d) || d == '/' || d == '=') {
                    j++;
                    continue;
                }
                if (d == '"' || d == '\'') {
                    int e = s.indexOf(d, j + 1);
                    e = e < 0 || e >= fin ? fin : e + 1;
                    f.add(j, e, CAD);
                    j = e;
                    continue;
                }
                int a0 = j;
                while (j < fin) {
                    char x = s.charAt(j);
                    if (Character.isWhitespace(x) || x == '=' || x == '>' || x == '<' || x == '"' || x == '\''
                            || (x == '/' && j + 1 < fin && s.charAt(j + 1) == '>')) break;
                    j++;
                }
                f.add(a0, j, ATR);
                boolean esLang = (j - a0 == 4 && s.regionMatches(true, a0, "lang", 0, 4))
                        || (j - a0 == 4 && s.regionMatches(true, a0, "type", 0, 4));
                int p = j;
                while (p < fin && Character.isWhitespace(s.charAt(p))) p++;
                if (p < fin && s.charAt(p) == '=') {
                    p++;
                    while (p < fin && Character.isWhitespace(s.charAt(p))) p++;
                    if (p < fin && (s.charAt(p) == '"' || s.charAt(p) == '\'')) {
                        char q = s.charAt(p);
                        int e = s.indexOf(q, p + 1);
                        e = e < 0 || e >= fin ? fin : e + 1;
                        f.add(p, e, CAD);
                        if (esLang && e - p <= 64) lang = s.substring(p + 1, Math.max(p + 1, e - 1)).toLowerCase(Locale.ROOT);
                        j = e;
                    } else {
                        int v0 = p;
                        while (p < fin && !Character.isWhitespace(s.charAt(p)) && s.charAt(p) != '>') p++;
                        f.add(v0, p, CAD);
                        if (esLang && p - v0 <= 64) lang = s.substring(v0, p).toLowerCase(Locale.ROOT);
                        j = p;
                    }
                }
            }
            i = j;
            if (L.html && !cierre && !autocierre && (etiqueta.equals("script") || etiqueta.equals("style"))) {
                int e = buscarCierre(s, i, fin, etiqueta);
                Lenguaje sub;
                if (etiqueta.equals("style")) {
                    sub = lang != null && lang.contains("scss") ? SCSS : lang != null && lang.contains("less") ? LESS : CSS_L;
                } else if (lang == null || lang.contains("javascript") || lang.equals("module") || lang.equals("js")
                        || lang.equals("jsx") || lang.contains("babel")) {
                    sub = JS;
                } else if (lang.equals("ts") || lang.contains("typescript") || lang.equals("tsx")) {
                    sub = TS;
                } else if (lang.contains("json")) {
                    sub = JSON_L;
                } else if (lang.contains("html") || lang.contains("template")) {
                    sub = HTML_L;
                } else {
                    sub = null;
                }
                if (sub != null && e > i) analizar(sub, s, i, e, f);
                i = e;
            }
        }
    }

    private static int buscarCierre(String s, int i, int fin, String etiqueta) {
        int k = i;
        while (true) {
            k = s.indexOf("</", k);
            if (k < 0 || k >= fin) return fin;
            if (s.regionMatches(true, k + 2, etiqueta, 0, etiqueta.length())) return k;
            k += 2;
        }
    }

    private static int finEntidad(String s, int i, int fin) {
        int j = i + 1;
        if (j < fin && s.charAt(j) == '#') j++;
        int p0 = j;
        while (j < fin && j - p0 < 32 && Character.isLetterOrDigit(s.charAt(j))) j++;
        return j > p0 && j < fin && s.charAt(j) == ';' ? j + 1 : i;
    }

    // --- CSS, SCSS, LESS ---

    private static void css(Lenguaje L, String s, int ini, int fin, Fichas f) {
        int i = ini, prof = 0;
        boolean inicioDecl = true, decl = false, valor = false;
        while (i < fin) {
            char c = s.charAt(i);
            char sig = i + 1 < fin ? s.charAt(i + 1) : 0;
            if (c == '/' && sig == '*') {
                int e = s.indexOf("*/", i + 2);
                e = e < 0 || e + 2 > fin ? fin : e + 2;
                f.add(i, e, COM);
                i = e;
                continue;
            }
            if (c == '/' && sig == '/' && (L.scss || L.less) && (i == ini || s.charAt(i - 1) != ':')) {
                int e = finLinea(s, i, fin);
                f.add(i, e, COM);
                i = e;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < fin && s.charAt(j) != c && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(fin, j + 1);
                f.add(i, j, CAD);
                i = j;
                continue;
            }
            if (inicioDecl) {
                inicioDecl = false;
                valor = false;
                decl = (prof > 0 || (c == '$' && L.scss) || (c == '@' && L.less)) && esDeclaracion(s, i, fin);
            }
            if (c == '{') {
                prof++;
                inicioDecl = true;
                i++;
                continue;
            }
            if (c == '}') {
                if (prof > 0) prof--;
                inicioDecl = true;
                i++;
                continue;
            }
            if (c == ';') {
                inicioDecl = true;
                i++;
                continue;
            }
            if (c == '@' && i + 1 < fin && (esIdentIni(sig) || sig == '-')) {
                int e = finIdentCss(s, i + 1, fin);
                int p = e;
                while (p < fin && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                boolean variable = L.less && (valor || (p < fin && s.charAt(p) == ':'));
                f.add(i, e, variable ? VAR : CLAVE);
                if (!variable) valor = true; // el resto de "@media (…)" o "@include x()" se trata como valor
                i = e;
                continue;
            }
            if (c == '$' && L.scss && i + 1 < fin && (esIdentIni(sig) || sig == '-')) {
                int e = finIdentCss(s, i + 1, fin);
                f.add(i, e, VAR);
                i = e;
                continue;
            }
            if (c == '#' && sig == '{') { // interpolación de SCSS
                int e = s.indexOf('}', i);
                e = e < 0 || e >= fin ? fin : e + 1;
                f.add(i, e, VAR);
                i = e;
                continue;
            }
            if (decl && !valor) {
                if (c == ':') {
                    valor = true;
                    i++;
                    continue;
                }
                if (esIdentIni(c) || c == '-' || c == '*') {
                    int e = finIdentCss(s, i + 1, fin);
                    f.add(i, e, ATR);
                    i = e;
                    continue;
                }
                i++;
                continue;
            }
            if (valor) {
                if (c == '#') {
                    int e = i + 1;
                    while (e < fin && Character.isLetterOrDigit(s.charAt(e))) e++;
                    f.add(i, e, NUM);
                    i = e;
                    continue;
                }
                if (Character.isDigit(c) || ((c == '.' || c == '-' || c == '+') && Character.isDigit(sig))) {
                    int e = i + 1;
                    while (e < fin && (Character.isDigit(s.charAt(e)) || s.charAt(e) == '.')) e++;
                    while (e < fin && (Character.isLetter(s.charAt(e)) || s.charAt(e) == '%')) e++;
                    f.add(i, e, NUM);
                    i = e;
                    continue;
                }
                if (c == '!') {
                    int e = i + 1;
                    while (e < fin && Character.isLetter(s.charAt(e))) e++;
                    f.add(i, e, CLAVE);
                    i = e;
                    continue;
                }
                if (esIdentIni(c) || c == '-') {
                    int e = finIdentCss(s, i + 1, fin);
                    if (e < fin && s.charAt(e) == '(') {
                        f.add(i, e, FUN);
                        if (e - i == 3 && s.regionMatches(true, i, "url", 0, 3)) {
                            int z = s.indexOf(')', e);
                            int nl = finLinea(s, e, fin);
                            z = z < 0 || z > nl ? nl : z;
                            f.add(e + 1, z, CAD);
                            i = z;
                            continue;
                        }
                    }
                    i = e;
                    continue;
                }
                i++;
                continue;
            }
            // Selectores.
            if ((c == '.' || c == '#') && i + 1 < fin && (esIdentIni(sig) || sig == '-')) {
                int e = finIdentCss(s, i + 1, fin);
                f.add(i, e, TIPO);
                i = e;
                continue;
            }
            if (c == ':') {
                int e = i + 1;
                if (e < fin && s.charAt(e) == ':') e++;
                int p0 = e;
                e = finIdentCss(s, e, fin);
                if (e > p0) f.add(i, e, CLAVE);
                i = Math.max(e, i + 1);
                continue;
            }
            if (c == '[') {
                int e = s.indexOf(']', i);
                int nl = finLinea(s, i, fin);
                e = e < 0 || e > nl ? nl : e + 1;
                f.add(i, e, ATR);
                i = e;
                continue;
            }
            if (esIdentIni(c)) {
                int e = finIdentCss(s, i + 1, fin);
                f.add(i, e, ETQ);
                i = e;
                continue;
            }
            if (Character.isDigit(c)) {
                int e = i + 1;
                while (e < fin && (Character.isDigit(s.charAt(e)) || s.charAt(e) == '.' || s.charAt(e) == '%')) e++;
                f.add(i, e, NUM);
                i = e;
                continue;
            }
            i++;
        }
    }

    private static int finIdentCss(String s, int j, int fin) {
        while (j < fin && (esIdent(s.charAt(j)) || s.charAt(j) == '-')) j++;
        return j;
    }

    /** true si la sentencia que empieza en i es "propiedad: valor" (acaba en ; o }) y no un selector ({). */
    private static boolean esDeclaracion(String s, int i, int fin) {
        int j = i;
        while (j < fin) {
            char c = s.charAt(j);
            if (c == ';' || c == '}') return true;
            if (c == '{') {
                if (j > i && s.charAt(j - 1) == '#') { // #{…} de SCSS
                    int e = s.indexOf('}', j);
                    if (e < 0) return true;
                    j = e + 1;
                    continue;
                }
                return false;
            }
            if (c == '"' || c == '\'') {
                int e = s.indexOf(c, j + 1);
                if (e < 0) return true;
                j = e + 1;
                continue;
            }
            j++;
        }
        return true;
    }

    // --- JSON ---

    private static void json(String s, int ini, int fin, Fichas f) {
        int i = ini;
        while (i < fin) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < fin && s.charAt(j) != c && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(fin, j + 1);
                int p = j;
                while (p < fin && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                f.add(i, j, p < fin && s.charAt(p) == ':' ? ATR : CAD);
                i = j;
                continue;
            }
            if (c == '/' && i + 1 < fin && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '*')) {
                int e;
                if (s.charAt(i + 1) == '/') {
                    e = finLinea(s, i, fin);
                } else {
                    e = s.indexOf("*/", i + 2);
                    e = e < 0 || e + 2 > fin ? fin : e + 2;
                }
                f.add(i, e, COM);
                i = e;
                continue;
            }
            if (Character.isDigit(c) || ((c == '-' || c == '+' || c == '.') && i + 1 < fin && Character.isDigit(s.charAt(i + 1)))) {
                int e = i + 1;
                while (e < fin && (Character.isLetterOrDigit(s.charAt(e)) || "+-.".indexOf(s.charAt(e)) >= 0)) e++;
                f.add(i, e, NUM);
                i = e;
                continue;
            }
            if (esIdentIni(c) || c == '$') {
                int e = i + 1;
                while (e < fin && (esIdent(s.charAt(e)) || s.charAt(e) == '$')) e++;
                int len = e - i;
                int clase = 0;
                if ((len == 4 && (s.startsWith("true", i) || s.startsWith("null", i))) || (len == 5 && s.startsWith("false", i))) {
                    clase = CLAVE;
                } else if ((len == 3 && s.startsWith("NaN", i)) || (len == 8 && s.startsWith("Infinity", i))) {
                    clase = NUM;
                } else {
                    int p = e;
                    while (p < fin && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                    if (p < fin && s.charAt(p) == ':') clase = ATR; // claves sin comillas (JSON5)
                }
                f.add(i, e, clase);
                i = e;
                continue;
            }
            i++;
        }
    }

    // --- YAML ---

    private static final Pattern NUMERO_YAML = Pattern.compile(
            "[-+]?(?:\\d[\\d_]*(?:\\.\\d*)?(?:[eE][-+]?\\d+)?|\\.\\d+|0x[0-9a-fA-F_]+|0o[0-7_]+|\\.inf|\\.Inf|\\.INF)|\\.nan|\\.NaN|\\.NAN"
            + "|\\d{4}-\\d\\d-\\d\\d(?:[Tt ][\\d:.]+(?:Z|[-+]\\d\\d(?::?\\d\\d)?)?)?");
    private static final Set<String> CONST_YAML = palabras(
            "true false True False TRUE FALSE null Null NULL ~ yes no Yes No YES NO on off On Off ON OFF");

    private static void yaml(String s, int ini, int fin, Fichas f) {
        int i = ini, bloque = -1;
        while (i < fin) {
            int eol = finLinea(s, i, fin);
            int j = i;
            while (j < eol && s.charAt(j) == ' ') j++;
            int sangria = j - i;
            if (bloque >= 0) {
                if (j >= eol || sangria > bloque) {
                    f.add(j, eol, CAD);
                    i = eol + 1;
                    continue;
                }
                bloque = -1;
            }
            if (j >= eol) {
                i = eol + 1;
                continue;
            }
            if (sangria == 0 && (s.startsWith("---", i) || s.startsWith("...", i))
                    && (i + 3 >= eol || s.charAt(i + 3) == ' ')) {
                f.add(i, i + 3, META);
                j = i + 3;
            }
            char c = j < eol ? s.charAt(j) : 0;
            if (c == '#') {
                f.add(j, eol, COM);
                i = eol + 1;
                continue;
            }
            if (c == '%' && sangria == 0) {
                f.add(j, eol, META);
                i = eol + 1;
                continue;
            }
            while (j < eol && s.charAt(j) == '-' && (j + 1 == eol || s.charAt(j + 1) == ' ')) {
                j++;
                while (j < eol && s.charAt(j) == ' ') j++;
            }
            int k = claveYaml(s, j, eol);
            if (k >= 0) {
                f.add(j, k, ATR);
                j = k + 1;
            }
            if (valorYaml(s, j, eol, f)) bloque = sangria;
            i = eol + 1;
        }
    }

    /** Posición de los dos puntos si la línea (desde j) empieza por "clave:"; si no, -1. */
    private static int claveYaml(String s, int j, int eol) {
        if (j >= eol) return -1;
        char c = s.charAt(j);
        int p = j;
        if (c == '"' || c == '\'') {
            int e = s.indexOf(c, j + 1);
            if (e < 0 || e >= eol) return -1;
            p = e + 1;
            while (p < eol && s.charAt(p) == ' ') p++;
            return p < eol && s.charAt(p) == ':' && (p + 1 == eol || s.charAt(p + 1) == ' ' || s.charAt(p + 1) == '\t') ? p : -1;
        }
        if ("[{&*!|>%@`#,".indexOf(c) >= 0) return -1;
        while (p < eol) {
            char d = s.charAt(p);
            if (d == ':' && (p + 1 == eol || s.charAt(p + 1) == ' ' || s.charAt(p + 1) == '\t')) return p;
            if (d == '#' && p > j && s.charAt(p - 1) == ' ') return -1;
            p++;
        }
        return -1;
    }

    /** Marca el valor de una línea de YAML; devuelve true si empieza un escalar de bloque (| o >). */
    private static boolean valorYaml(String s, int j, int eol, Fichas f) {
        int flujo = 0;
        int inicio = j;
        while (j < eol) {
            char c = s.charAt(j);
            if (c == ' ' || c == '\t') {
                j++;
                continue;
            }
            if (c == '#' && (j == inicio || s.charAt(j - 1) == ' ' || s.charAt(j - 1) == '\t')) {
                f.add(j, eol, COM);
                return false;
            }
            if (c == '"' || c == '\'') {
                int e = j + 1;
                while (e < eol) {
                    char d = s.charAt(e);
                    if (d == '\\' && c == '"') {
                        e += 2;
                        continue;
                    }
                    if (d == c) {
                        if (c == '\'' && e + 1 < eol && s.charAt(e + 1) == '\'') {
                            e += 2;
                            continue;
                        }
                        break;
                    }
                    e++;
                }
                e = Math.min(eol, e + 1);
                int p = e;
                while (p < eol && s.charAt(p) == ' ') p++;
                f.add(j, e, flujo > 0 && p < eol && s.charAt(p) == ':' ? ATR : CAD);
                j = e;
                continue;
            }
            if (c == '&' || c == '*' || c == '!') {
                int e = j + 1;
                while (e < eol && !Character.isWhitespace(s.charAt(e)) && ",[]{}".indexOf(s.charAt(e)) < 0) e++;
                f.add(j, e, c == '!' ? META : VAR);
                j = e;
                continue;
            }
            if ((c == '|' || c == '>') && flujo == 0) {
                int e = j + 1;
                while (e < eol && (Character.isDigit(s.charAt(e)) || s.charAt(e) == '+' || s.charAt(e) == '-')) e++;
                int p = e;
                while (p < eol && s.charAt(p) == ' ') p++;
                if (p == eol || s.charAt(p) == '#') {
                    f.add(j, e, META);
                    if (p < eol) f.add(p, eol, COM);
                    return true;
                }
            }
            if (c == '[' || c == '{') {
                flujo++;
                j++;
                continue;
            }
            if (c == ']' || c == '}') {
                if (flujo > 0) flujo--;
                j++;
                continue;
            }
            if (c == ',' && flujo > 0) {
                j++;
                continue;
            }
            // Escalar sin comillas.
            int e = j;
            while (e < eol) {
                char d = s.charAt(e);
                if (d == '#' && e > j && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t')) break;
                if (flujo > 0 && (d == ',' || d == ']' || d == '}')) break;
                if (flujo > 0 && d == ':' && (e + 1 == eol || s.charAt(e + 1) == ' ')) break;
                e++;
            }
            int b = e;
            while (b > j && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
            int clase;
            if (flujo > 0 && e < eol && s.charAt(e) == ':') clase = ATR;
            else if (b - j <= 40 && CONST_YAML.contains(s.substring(j, b))) clase = CLAVE;
            else if (b - j <= 40 && NUMERO_YAML.matcher(s.substring(j, b)).matches()) clase = NUM;
            else clase = CAD;
            f.add(j, b, clase);
            j = Math.max(e, j + 1);
        }
        return false;
    }

    // --- INI, .properties, .conf, .env ---

    private static void ini(String s, int ini, int fin, Fichas f) {
        int i = ini;
        while (i < fin) {
            int eol = finLinea(s, i, fin);
            int j = i;
            while (j < eol && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
            if (j >= eol) {
                i = eol + 1;
                continue;
            }
            char c = s.charAt(j);
            if (c == '#' || c == ';' || c == '!' && j == i || (c == '/' && j + 1 < eol && s.charAt(j + 1) == '/')) {
                f.add(j, eol, COM);
                i = eol + 1;
                continue;
            }
            if (c == '[') {
                int e = s.lastIndexOf(']', eol - 1);
                e = e < j ? eol : e + 1;
                f.add(j, e, SECC);
                valorIni(s, e, eol, f);
                i = eol + 1;
                continue;
            }
            if (c == '<') { // <VirtualHost …> de Apache
                int e = s.indexOf('>', j);
                e = e < 0 || e > eol ? eol : e + 1;
                f.add(j, e, ETQ);
                i = eol + 1;
                continue;
            }
            if (s.startsWith("export ", j)) {
                f.add(j, j + 6, CLAVE);
                j += 7;
                while (j < eol && s.charAt(j) == ' ') j++;
            }
            int sep = -1;
            for (int p = j; p < eol; p++) {
                char d = s.charAt(p);
                if (d == '=' || d == ':') {
                    sep = p;
                    break;
                }
                if (d == ' ' || d == '\t') {
                    // "clave = valor" o "directiva valor" (nginx, ssh_config…)
                    int q = p;
                    while (q < eol && (s.charAt(q) == ' ' || s.charAt(q) == '\t')) q++;
                    sep = q < eol && (s.charAt(q) == '=' || s.charAt(q) == ':') ? q : p;
                    break;
                }
                if (d == '"' || d == '\'' || d == '{' || d == '}') break;
            }
            if (sep > j) {
                int b = sep;
                while (b > j && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
                f.add(j, b, ATR);
                valorIni(s, sep + 1, eol, f);
            } else {
                valorIni(s, j, eol, f);
            }
            i = eol + 1;
        }
    }

    private static void valorIni(String s, int j, int eol, Fichas f) {
        while (j < eol && (s.charAt(j) == ' ' || s.charAt(j) == '\t' || s.charAt(j) == '=')) j++;
        while (j < eol) {
            char c = s.charAt(j);
            if ((c == '#' || c == ';') && j > 0 && (s.charAt(j - 1) == ' ' || s.charAt(j - 1) == '\t')) {
                f.add(j, eol, COM);
                return;
            }
            if (c == '"' || c == '\'') {
                int e = j + 1;
                while (e < eol && s.charAt(e) != c) e += s.charAt(e) == '\\' ? 2 : 1;
                e = Math.min(eol, e + 1);
                f.add(j, e, CAD);
                j = e;
                continue;
            }
            if (c == '$' || c == '%') {
                int e = c == '$' ? finVariable(s, j, eol) : finPorcentaje(s, j, eol);
                if (e > j + 1) {
                    f.add(j, e, VAR);
                    j = e;
                    continue;
                }
            }
            if (Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '+') {
                int e = j;
                while (e < eol && !Character.isWhitespace(s.charAt(e)) && ";,\"'$".indexOf(s.charAt(e)) < 0) e++;
                if (e == j) e = j + 1;
                String w = e - j <= 12 ? s.substring(j, e).toLowerCase(Locale.ROOT) : "";
                int clase = 0;
                if (w.equals("true") || w.equals("false") || w.equals("yes") || w.equals("no") || w.equals("on")
                        || w.equals("off") || w.equals("null") || w.equals("none")) {
                    clase = CLAVE;
                } else if (pareceNumero(s, j, e)) {
                    clase = NUM;
                }
                f.add(j, e, clase);
                j = e;
                continue;
            }
            j++;
        }
    }

    /** 42, -3.5, 0x1F, 512M, 30s… */
    private static boolean pareceNumero(String s, int a, int b) {
        if (b <= a || b - a > 24) return false;
        int i = a;
        if (s.charAt(i) == '-' || s.charAt(i) == '+') i++;
        if (i >= b || !Character.isDigit(s.charAt(i))) return false;
        if (i + 1 < b && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            i += 2;
            while (i < b && Character.digit(s.charAt(i), 16) >= 0) i++;
            return i == b;
        }
        while (i < b && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) i++;
        int unidad = i;
        while (i < b && Character.isLetter(s.charAt(i))) i++;
        return i == b && i - unidad <= 3;
    }

    // --- TOML ---

    private static void toml(String s, int ini, int fin, Fichas f) {
        int i = ini;
        boolean inicio = true;
        while (i < fin) {
            char c = s.charAt(i);
            if (c == '\n') {
                inicio = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                continue;
            }
            boolean alInicio = inicio;
            inicio = false;
            if (c == '#') {
                int e = finLinea(s, i, fin);
                f.add(i, e, COM);
                i = e;
                continue;
            }
            if (alInicio && c == '[') {
                int eol = finLinea(s, i, fin);
                int e = s.indexOf(']', i);
                e = e < 0 || e > eol ? eol : e + 1;
                if (e < eol && s.charAt(e) == ']') e++;
                f.add(i, e, SECC);
                i = e;
                continue;
            }
            if (c == '"' || c == '\'') {
                int e;
                if (i + 2 < fin && s.charAt(i + 1) == c && s.charAt(i + 2) == c) {
                    e = finTriple(s, i + 3, fin, c);
                } else {
                    e = i + 1;
                    while (e < fin && s.charAt(e) != c && s.charAt(e) != '\n') e += s.charAt(e) == '\\' && c == '"' ? 2 : 1;
                    e = Math.min(fin, e + 1);
                }
                int p = e;
                while (p < fin && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                f.add(i, e, p < fin && (s.charAt(p) == '=' || s.charAt(p) == '.') ? ATR : CAD);
                i = e;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+') {
                int e = i;
                while (e < fin && (Character.isLetterOrDigit(s.charAt(e)) || "_-.:+".indexOf(s.charAt(e)) >= 0)) e++;
                // Fechas con hora: 1979-05-27T07:32:00 o "1979-05-27 07:32:00".
                int p = e;
                while (p < fin && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                int clase;
                if (p < fin && s.charAt(p) == '=') {
                    clase = ATR;
                } else {
                    String w = e - i <= 40 ? s.substring(i, e) : "";
                    if (w.equals("true") || w.equals("false")) clase = CLAVE;
                    else if (w.equals("inf") || w.equals("nan") || w.equals("+inf") || w.equals("-inf")
                            || (Character.isDigit(c) || ((c == '-' || c == '+') && e > i + 1 && Character.isDigit(s.charAt(i + 1))))) clase = NUM;
                    else clase = 0;
                }
                f.add(i, e, clase);
                i = Math.max(e, i + 1);
                continue;
            }
            i++;
        }
    }

    // --- Diff / patch ---

    private static void diff(String s, int ini, int fin, Fichas f) {
        int i = ini;
        while (i < fin) {
            int eol = finLinea(s, i, fin);
            if (eol > i) {
                char c = s.charAt(i);
                if (s.startsWith("+++", i) || s.startsWith("---", i) || s.startsWith("diff ", i)
                        || s.startsWith("index ", i) || s.startsWith("new file", i) || s.startsWith("deleted file", i)
                        || s.startsWith("similarity", i) || s.startsWith("rename ", i) || s.startsWith("Only in", i)) {
                    f.add(i, eol, META);
                } else if (s.startsWith("@@", i)) {
                    f.add(i, eol, SECC);
                } else if (c == '+' || c == '>') {
                    f.add(i, eol, MAS);
                } else if (c == '-' || c == '<') {
                    f.add(i, eol, MENOS);
                } else if (c == '\\') {
                    f.add(i, eol, COM);
                }
            }
            i = eol + 1;
        }
    }
}
