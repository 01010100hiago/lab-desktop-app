package br.com.labdesktop.git;

/**
 * Um repositório Git que mora numa SUBPASTA do projeto (a pasta principal não tem {@code .git}, mas {@code app/} tem).
 *
 * @param caminho o caminho da subpasta dentro do projeto, sem barra no fim ({@code app} ou {@code servicos/api}); sempre passou por {@link ValidadorDeGit#caminhoRelativoValido}
 * @param branch  o branch atual, quando já foi lido; {@code null} se ainda não leu ou se a cabeça está solta
 * @param ultimo  o último commit, quando já foi lido; {@code null} se ainda não leu ou se o repositório não tem commits
 * @param lido    a leitura leve (branch e último commit) já foi tentada
 */
public record RepositorioAbaixo(String caminho, String branch, Commit ultimo, boolean lido) {

    /** Só o caminho, sem ter lido nada. */
    public static RepositorioAbaixo semLeitura(String caminho) {
        return new RepositorioAbaixo(caminho, null, null, false);
    }

    /** Como a lista mostra o caminho: com barra no fim. */
    public String comBarra() {
        return caminho + "/";
    }
}
