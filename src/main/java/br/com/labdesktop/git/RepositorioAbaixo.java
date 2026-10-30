package br.com.labdesktop.git;

/**
 * Um repositório Git que mora numa SUBPASTA do projeto (a pasta principal não tem {@code .git}, mas {@code app/} tem).
 *
 * @param caminho       o caminho da subpasta dentro do projeto, sem barra no fim ({@code app} ou {@code servicos/api}); sempre passou por {@link ValidadorDeGit#caminhoRelativoValido}
 * @param branch        o branch atual, quando já foi lido; {@code null} se ainda não leu ou se a cabeça está solta
 * @param ultimo        o último commit, quando já foi lido; {@code null} se ainda não leu ou se o repositório não tem commits
 * @param lido          a leitura leve (branch e último commit) já foi tentada
 * @param foraDoProjeto o {@code .git} dele é um arquivo que leva para FORA do projeto: o app não lê (nem a leitura leve)
 */
public record RepositorioAbaixo(String caminho, String branch, Commit ultimo, boolean lido, boolean foraDoProjeto) {

    /** A frase que a lista e a aba dizem de um repositório cujo {@code .git} leva para fora do projeto. */
    public static final String FRASE_DE_FORA = "repositório aponta para fora do projeto; não leio por segurança";

    public RepositorioAbaixo(String caminho, String branch, Commit ultimo, boolean lido) {
        this(caminho, branch, ultimo, lido, false);
    }

    /** Só o caminho, sem ter lido nada. */
    public static RepositorioAbaixo semLeitura(String caminho) {
        return new RepositorioAbaixo(caminho, null, null, false, false);
    }

    /** Um repositório que aponta para fora do projeto. */
    public static RepositorioAbaixo apontandoParaFora(String caminho) {
        return new RepositorioAbaixo(caminho, null, null, false, true);
    }

    /** Como a lista mostra o caminho: com barra no fim. */
    public String comBarra() {
        return caminho + "/";
    }
}
