package br.com.labdesktop.git;

/**
 * O que a linha "Código" da Visão geral precisa saber de um projeto.
 *
 * @param estado      em que situação a pasta está
 * @param branch      o branch atual ({@code null} se não vale)
 * @param cabecaSolta a pasta está num commit solto
 * @param ultimo      o último commit, ou {@code null} (repositório sem commits, ou sem Git)
 * @param alteracoes  quantas alterações ainda não commitadas ({@code 0} se não vale)
 * @param falha       a explicação, para os estados que não são "tem Git"/"sem Git"; senão {@code null}
 * @param pastaPai    quando a pasta está DENTRO de outro repositório: o começo do caminho dentro dele (como o git informa); senão {@code null}
 */
public record ResumoDoRepositorio(Estado estado, String branch, boolean cabecaSolta, Commit ultimo, int alteracoes, FalhaDoGit falha, String pastaPai) {

    /** Os estados da linha "Código". */
    public enum Estado {
        /** A pasta é a raiz de um repositório. */
        COM_GIT,
        /** A pasta não é repositório (nem está dentro de um). */
        SEM_GIT,
        /** A pasta está DENTRO de um repositório que é de uma pasta de cima. */
        DENTRO_DE_OUTRO,
        /** Não há git nesta máquina. */
        GIT_NAO_INSTALADO,
        /** O git recusa por causa do dono da pasta. */
        PROPRIEDADE_DUVIDOSA,
        /** Não deu para ler (conexão, permissão, prazo...). */
        ERRO
    }

    public static ResumoDoRepositorio semGit() {
        return new ResumoDoRepositorio(Estado.SEM_GIT, null, false, null, 0, null, null);
    }

    public static ResumoDoRepositorio de(Estado estado, FalhaDoGit falha) {
        return new ResumoDoRepositorio(estado, null, false, null, 0, falha, null);
    }

    public boolean temGit() {
        return estado == Estado.COM_GIT;
    }

    public boolean semCommits() {
        return estado == Estado.COM_GIT && ultimo == null;
    }
}
