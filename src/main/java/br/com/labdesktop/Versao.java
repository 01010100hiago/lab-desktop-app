package br.com.labdesktop;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Nome, versão e id da compilação do app. Tudo vem do manifesto do jar, gravado pelo build ({@code build.gradle.kts}): {@code Implementation-Version} (por exemplo
 * {@code 0.1.320}: a base mais o número de commits do git), {@code Build-Commit} (os 7 primeiros dígitos do commit, com {@code -alterado} se a árvore tinha mudanças não
 * commitadas) e {@code Build-Date} (a data da compilação, em UTC). Rodando fora do jar (testes, IDE) a versão é {@code "dev"}; no {@code ./gradlew run} o build passa também o
 * commit e a data por propriedades do sistema ({@code labdesktop.build.commit} e {@code labdesktop.build.data}).
 */
public final class Versao {

    public static final String NOME = "Lab Desktop";

    /** O que vale quando não há versão no manifesto (testes, IDE). */
    public static final String DEV = "dev";
    /** O commit quando o build não pôde saber (sem git, ou histórico raso). */
    public static final String COMMIT_DESCONHECIDO = "desconhecido";

    private static final String PROPRIEDADE_COMMIT = "labdesktop.build.commit";
    private static final String PROPRIEDADE_DATA = "labdesktop.build.data";
    private static final Pattern VERSAO_VALIDA = Pattern.compile("\\d+(\\.\\d+){1,3}");
    private static final Pattern COMMIT_VALIDO = Pattern.compile("[0-9a-f]{7}(-alterado)?|" + COMMIT_DESCONHECIDO);
    private static final Pattern DATA_VALIDA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private Versao() { }

    /**
     * O que o manifesto diz da compilação, já conferido: valor ausente ou com cara de lixo vira "sem informação" (nunca vai para o cabeçalho {@code User-Agent} nem para a tela
     * como veio). Público para os testes montarem um manifesto de mentira.
     *
     * @param versao {@code 0.1.320}, ou {@value #DEV}
     * @param commit commit curto (com {@code -alterado} se for o caso), {@value #COMMIT_DESCONHECIDO}, ou {@code null} se não há
     * @param data   {@code AAAA-MM-DD}, ou {@code null} se não há
     */
    public record Compilacao(String versao, String commit, String data) {

        /** Sem manifesto (testes e IDE), com o commit e a data que o build do {@code run} tenha passado. */
        static Compilacao dev(String commit, String data) {
            return new Compilacao(DEV, valido(commit, COMMIT_VALIDO), valido(data, DATA_VALIDA));
        }

        /** Do manifesto do jar. Sem {@code Implementation-Version} é {@link #dev}. */
        public static Compilacao de(Manifest manifesto) {
            Attributes a = manifesto == null ? null : manifesto.getMainAttributes();
            String versao = a == null ? null : valido(a.getValue("Implementation-Version"), VERSAO_VALIDA);
            if (versao == null) {
                return dev(null, null);
            }
            return new Compilacao(versao, valido(a.getValue("Build-Commit"), COMMIT_VALIDO), valido(a.getValue("Build-Date"), DATA_VALIDA));
        }

        /**
         * Do manifesto do jar que contém o recurso dado (a URL de uma classe do app, {@code jar:file:...!/pacote/Classe.class}); uma URL que não é de jar (a pasta de classes
         * do Gradle, de uma IDE) ou um jar ilegível dão {@link #dev}.
         */
        public static Compilacao doJarDe(URL recurso) {
            if (recurso == null || !"jar".equals(recurso.getProtocol())) {
                return dev(null, null);
            }
            String texto = recurso.toExternalForm();
            int corte = texto.indexOf("!/");
            if (corte < 0) {
                return dev(null, null);
            }
            try {
                URLConnection conexao = URI.create(texto.substring(0, corte + 2) + "META-INF/MANIFEST.MF").toURL().openConnection();
                conexao.setUseCaches(false); // não deixa o jar aberto e preso no cache do Java (no Windows isso trava a troca do arquivo)
                try (InputStream in = conexao.getInputStream()) {
                    return de(new Manifest(in));
                }
            } catch (IOException | RuntimeException e) {
                return dev(null, null);
            }
        }

        private static String valido(String valor, Pattern formato) {
            if (valor == null) {
                return null;
            }
            String v = valor.strip();
            return formato.matcher(v).matches() ? v : null;
        }

        /** Se o build soube de que commit saiu o código (falso para {@value #COMMIT_DESCONHECIDO} e para a falta do dado). */
        public boolean temCommit() {
            return commit != null && !COMMIT_DESCONHECIDO.equals(commit);
        }

        public String commitOuDesconhecido() {
            return commit == null ? COMMIT_DESCONHECIDO : commit;
        }

        public String dataOuDesconhecida() {
            return data == null ? "desconhecida" : data;
        }

        /** {@code 0.1.320 (a207ad4)}; {@code dev} se não há commit. */
        public String comCommit() {
            return commit == null ? versao : versao + " (" + (temCommit() ? commit : "commit " + COMMIT_DESCONHECIDO) + ")";
        }

        /** {@code LabDesktop/0.1.320}: só a versão (nem commit nem dado do computador). */
        public String userAgent() {
            return "LabDesktop/" + versao;
        }

        /** {@code 0.1.320 (a207ad4, 2026-10-30)}; só com o que se sabe: {@code dev}, {@code dev (a207ad4-alterado)} ou {@code 0.1.0 (commit desconhecido, 2026-10-30)}. */
        public String completa() {
            StringBuilder partes = new StringBuilder();
            if (commit != null) {
                partes.append(temCommit() ? commit : "commit " + COMMIT_DESCONHECIDO);
            }
            if (data != null) {
                partes.append(partes.isEmpty() ? "" : ", ").append(data);
            }
            return partes.isEmpty() ? versao : versao + " (" + partes + ")";
        }
    }

    /** Lida uma vez (a leitura do jar não acontece de novo, nem na EDT: a primeira chamada é da partida). */
    private static final class Lida {
        static final Compilacao DADOS = ler();

        private static Compilacao ler() {
            Compilacao doJar = Compilacao.doJarDe(Versao.class.getResource(Versao.class.getSimpleName() + ".class"));
            if (!DEV.equals(doJar.versao())) {
                return doJar;
            }
            return Compilacao.dev(System.getProperty(PROPRIEDADE_COMMIT), System.getProperty(PROPRIEDADE_DATA));
        }
    }

    /** A compilação que está rodando. */
    public static Compilacao compilacao() {
        return Lida.DADOS;
    }

    /** Por exemplo {@code 0.1.320}, ou {@code dev}. */
    public static String atual() {
        return compilacao().versao();
    }

    /** O commit da compilação ({@code a207ad4}, ou {@code a207ad4-alterado}); {@value #COMMIT_DESCONHECIDO} se o build não soube ou se não há manifesto. */
    public static String commit() {
        return compilacao().commitOuDesconhecido();
    }

    /** A data da compilação ({@code 2026-10-30}, em UTC); {@code desconhecida} se não há manifesto. */
    public static String data() {
        return compilacao().dataOuDesconhecida();
    }

    /** {@code 0.1.320 (a207ad4, 2026-10-30)}; é o que o {@code --versao} imprime depois do nome. */
    public static String completa() {
        return compilacao().completa();
    }

    /** Para o log: a versão e o commit ({@code 0.1.320 (a207ad4)}), sem a data. */
    public static String comCommit() {
        return compilacao().comCommit();
    }

    /** Valor do cabeçalho {@code User-Agent} das chamadas ao agente (CONTRATO-API 1.1): só a versão, sem commit nem dado do computador. */
    public static String userAgent() {
        return compilacao().userAgent();
    }
}
