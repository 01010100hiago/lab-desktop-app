package br.com.labdesktop.construcao;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A versão do app e o "id da compilação", calculados a cada build a partir do git: {@code <base>.<n>}, em que a base ({@code MAJOR.MINOR}) vem de
 * {@code labdesktop.versaoBase} em {@code gradle.properties} e {@code n} é o número de commits do histórico ({@code git rev-list --count HEAD}). O commit são os 7
 * primeiros dígitos do {@code HEAD}, com {@code -alterado} se a árvore de trabalho tem mudanças não commitadas (nada é escondido). A data é a da compilação, em UTC.
 *
 * <p>Sem git, fora de um repositório, sem commits, com histórico raso (um clone só do último commit: a contagem seria falsa) ou com um commit que não parece um commit,
 * a versão cai em {@code <base>.0} e o commit vale {@value #DESCONHECIDO}. O cálculo nunca falha o build por causa do git. Classe pura: o git entra por {@link Git}.
 */
public final class VersaoDaCompilacao {

    public static final String DESCONHECIDO = "desconhecido";
    public static final String SUFIXO_ALTERADO = "-alterado";

    private static final Pattern BASE = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
    private static final Pattern COMMIT_COMPLETO = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** Quem roda o git (o build passa o {@code providers.exec}; os testes passam respostas prontas). */
    @FunctionalInterface
    public interface Git {
        /** A saída padrão (sem espaços nas pontas) se o git rodou e saiu com zero; vazio se o git não existe, não é um repositório ou falhou. */
        Optional<String> executar(List<String> argumentos);
    }

    private final String base;
    private final int numero;
    private final String commit;
    private final String data;

    private VersaoDaCompilacao(String base, int numero, String commit, String data) {
        this.base = base;
        this.numero = numero;
        this.commit = commit;
        this.data = data;
    }

    /**
     * @param base {@code MAJOR.MINOR}, por exemplo {@code 0.1}; formato errado é erro de configuração (e derruba o build), não do git
     * @param git  como rodar o git neste repositório
     * @param hoje a data da compilação em UTC
     */
    public static VersaoDaCompilacao calcular(String base, Git git, LocalDate hoje) {
        if (base == null || !BASE.matcher(base.strip()).matches()) {
            throw new IllegalArgumentException("labdesktop.versaoBase precisa ser MAJOR.MINOR (por exemplo 0.1), e está: " + base);
        }
        String b = base.strip();
        String data = hoje.toString();
        if (!git.executar(List.of("rev-parse", "--is-shallow-repository")).map("false"::equals).orElse(false)) {
            return semGit(b, data); // sem git, sem repositório, sem commits ou raso (um git antigo repete a opção em vez de responder: também cai aqui)
        }
        int contagem = git.executar(List.of("rev-list", "--count", "HEAD")).map(VersaoDaCompilacao::inteiroPositivo).orElse(0);
        String completo = git.executar(List.of("rev-parse", "HEAD")).filter(s -> COMMIT_COMPLETO.matcher(s).matches()).orElse(null);
        if (contagem <= 0 || completo == null) {
            return semGit(b, data);
        }
        // se o git não consegue dizer o estado da árvore, vale o pior: "alterado" (não afirma o que não sabe)
        boolean alterado = git.executar(List.of("status", "--porcelain", "--untracked-files=normal")).map(s -> !s.isBlank()).orElse(true);
        return new VersaoDaCompilacao(b, contagem, completo.substring(0, 7) + (alterado ? SUFIXO_ALTERADO : ""), data);
    }

    private static VersaoDaCompilacao semGit(String base, String data) {
        return new VersaoDaCompilacao(base, 0, DESCONHECIDO, data);
    }

    private static int inteiroPositivo(String s) {
        try {
            int n = Integer.parseInt(s.strip());
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code MAJOR.MINOR}. */
    public String base() {
        return base;
    }

    public int major() {
        return Integer.parseInt(base.substring(0, base.indexOf('.')));
    }

    public int minor() {
        return Integer.parseInt(base.substring(base.indexOf('.') + 1));
    }

    /** O número de commits, ou 0 quando o git não pôde dizer. */
    public int numero() {
        return numero;
    }

    /** {@code MAJOR.MINOR.n}, por exemplo {@code 0.1.320}. */
    public String versao() {
        return base + "." + numero;
    }

    /** Os 7 primeiros dígitos do commit, com {@code -alterado} se a árvore tinha mudanças; ou {@value #DESCONHECIDO}. */
    public String commit() {
        return commit;
    }

    /** {@code AAAA-MM-DD}, em UTC. */
    public String data() {
        return data;
    }

    /** Se o número e o commit vieram do git (falso na versão de reserva {@code <base>.0}). */
    public boolean veioDoGit() {
        return !DESCONHECIDO.equals(commit);
    }

    @Override
    public String toString() {
        return versao() + " (" + commit + ", " + data + ")";
    }
}
