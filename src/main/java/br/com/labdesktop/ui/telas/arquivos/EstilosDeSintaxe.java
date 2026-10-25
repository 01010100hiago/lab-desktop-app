package br.com.labdesktop.ui.telas.arquivos;

import java.util.Locale;
import java.util.Map;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/** Que realce de sintaxe dar a um arquivo, pelo nome (e, para os sem extensão, pelo nome inteiro: Dockerfile, Makefile). */
final class EstilosDeSintaxe {

    private static final Map<String, String> POR_EXTENSAO = Map.ofEntries(
        Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
        Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
        Map.entry("mjs", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
        Map.entry("cjs", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
        Map.entry("jsx", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
        Map.entry("ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
        Map.entry("tsx", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
        Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON),
        Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
        Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML),
        Map.entry("htm", SyntaxConstants.SYNTAX_STYLE_HTML),
        Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS),
        Map.entry("less", SyntaxConstants.SYNTAX_STYLE_LESS),
        Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML),
        Map.entry("svg", SyntaxConstants.SYNTAX_STYLE_XML),
        Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),
        Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML),
        Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
        Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
        Map.entry("bash", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
        Map.entry("zsh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
        Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
        Map.entry("markdown", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
        Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
        Map.entry("env", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
        Map.entry("ini", SyntaxConstants.SYNTAX_STYLE_INI),
        Map.entry("conf", SyntaxConstants.SYNTAX_STYLE_INI),
        Map.entry("cfg", SyntaxConstants.SYNTAX_STYLE_INI),
        Map.entry("toml", SyntaxConstants.SYNTAX_STYLE_INI),
        Map.entry("go", SyntaxConstants.SYNTAX_STYLE_GO),
        Map.entry("rs", SyntaxConstants.SYNTAX_STYLE_RUST),
        Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C),
        Map.entry("h", SyntaxConstants.SYNTAX_STYLE_C),
        Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
        Map.entry("hpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
        Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP),
        Map.entry("php", SyntaxConstants.SYNTAX_STYLE_PHP),
        Map.entry("rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
        Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
        Map.entry("lua", SyntaxConstants.SYNTAX_STYLE_LUA),
        Map.entry("csv", SyntaxConstants.SYNTAX_STYLE_CSV));

    private static final Map<String, String> POR_NOME = Map.of(
        "dockerfile", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE,
        "makefile", SyntaxConstants.SYNTAX_STYLE_MAKEFILE,
        ".env", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE,
        ".bashrc", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
        ".zshrc", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
        ".gitignore", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE,
        "hosts", SyntaxConstants.SYNTAX_STYLE_HOSTS,
        ".htaccess", SyntaxConstants.SYNTAX_STYLE_HTACCESS);

    private EstilosDeSintaxe() { }

    /** O estilo do arquivo; texto simples para o que não se conhece. */
    static String paraArquivo(String nome) {
        String n = nome == null ? "" : nome.toLowerCase(Locale.ROOT);
        String porNome = POR_NOME.get(n);
        if (porNome != null) {
            return porNome;
        }
        if (n.startsWith(".env.")) {
            return SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
        }
        if (n.startsWith("dockerfile.")) {
            return SyntaxConstants.SYNTAX_STYLE_DOCKERFILE;
        }
        int i = n.lastIndexOf('.');
        if (i > 0 && i < n.length() - 1) {
            String porExtensao = POR_EXTENSAO.get(n.substring(i + 1));
            if (porExtensao != null) {
                return porExtensao;
            }
        }
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }
}
