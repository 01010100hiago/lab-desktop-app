package br.com.labdesktop.git;

import java.util.List;

/**
 * O que a linha "Código" da Visão geral e a aba Git precisam saber de um projeto.
 *
 * @param estado      em que situação a pasta está
 * @param branch      o branch atual ({@code null} se não vale)
 * @param cabecaSolta a pasta está num commit solto
 * @param ultimo      o último commit, ou {@code null} (repositório sem commits, ou sem Git)
 * @param alteracoes  quantas alterações ainda não commitadas ({@code 0} se não vale)
 * @param falha       a explicação, para os estados que não são "tem Git"/"sem Git"; senão {@code null}
 * @param pastaPai    quando a pasta está DENTRO de outro repositório: o começo do caminho dentro dele (como o git informa); senão {@code null}
 * @param subpasta    quando o Git lido é o de uma SUBPASTA do projeto ({@code app}): o caminho dela; {@code null} quando é o da pasta principal
 * @param abaixo      os repositórios que a pasta principal (que não tem {@code .git}) tem em subpastas; vazio nos outros casos. Com {@link Estado#COM_GIT} e {@code subpasta} preenchida, é a lista
 *                    de onde o usuário pode trocar de repositório
 * @param abaixoCortado havia mais repositórios que o limite da lista ({@link RepositoriosAbaixo#LIMITE})
 * @param ilegiveis   quantos repositórios de subpasta têm nome que o app não mostra com segurança (ficam fora da lista, mas existem)
 */
public record ResumoDoRepositorio(Estado estado, String branch, boolean cabecaSolta, Commit ultimo, int alteracoes, FalhaDoGit falha, String pastaPai, String subpasta,
                                  List<RepositorioAbaixo> abaixo, boolean abaixoCortado, int ilegiveis) {

    /** Os estados da linha "Código". */
    public enum Estado {
        /** A pasta é a raiz de um repositório (ou, com {@code subpasta}, a raiz do repositório de uma subpasta). */
        COM_GIT,
        /** A pasta não é repositório (nem está dentro de um) e não há repositório em subpastas. */
        SEM_GIT,
        /** A pasta principal não tem Git, mas há repositório em subpasta(s) e nenhum foi escolhido ainda. */
        REPOSITORIOS_ABAIXO,
        /** A pasta está DENTRO de um repositório que é de uma pasta de cima. */
        DENTRO_DE_OUTRO,
        /** Não há git nesta máquina. */
        GIT_NAO_INSTALADO,
        /** O git recusa por causa do dono da pasta. */
        PROPRIEDADE_DUVIDOSA,
        /** Não deu para ler (conexão, permissão, prazo...). */
        ERRO
    }

    public ResumoDoRepositorio {
        abaixo = abaixo == null ? List.of() : List.copyOf(abaixo);
    }

    /** Sem os de nome ilegível (nenhum). */
    public ResumoDoRepositorio(Estado estado, String branch, boolean cabecaSolta, Commit ultimo, int alteracoes, FalhaDoGit falha, String pastaPai, String subpasta, List<RepositorioAbaixo> abaixo,
                               boolean abaixoCortado) {
        this(estado, branch, cabecaSolta, ultimo, alteracoes, falha, pastaPai, subpasta, abaixo, abaixoCortado, 0);
    }

    /** Sem repositório em subpasta: o que toda leitura da pasta principal sem subpasta devolve. */
    public ResumoDoRepositorio(Estado estado, String branch, boolean cabecaSolta, Commit ultimo, int alteracoes, FalhaDoGit falha, String pastaPai) {
        this(estado, branch, cabecaSolta, ultimo, alteracoes, falha, pastaPai, null, List.of(), false, 0);
    }

    public static ResumoDoRepositorio semGit() {
        return new ResumoDoRepositorio(Estado.SEM_GIT, null, false, null, 0, null, null);
    }

    public static ResumoDoRepositorio de(Estado estado, FalhaDoGit falha) {
        return new ResumoDoRepositorio(estado, null, false, null, 0, falha, null);
    }

    /** A pasta principal não tem Git e há repositório(s) em subpasta(s): a lista (com o que já se leu de cada um). */
    public static ResumoDoRepositorio repositoriosAbaixo(List<RepositorioAbaixo> abaixo, boolean cortado) {
        return repositoriosAbaixo(abaixo, cortado, 0);
    }

    /** Como {@link #repositoriosAbaixo(List, boolean)}, dizendo também quantos repositórios de nome ilegível ficaram fora da lista. */
    public static ResumoDoRepositorio repositoriosAbaixo(List<RepositorioAbaixo> abaixo, boolean cortado, int ilegiveis) {
        return new ResumoDoRepositorio(Estado.REPOSITORIOS_ABAIXO, null, false, null, 0, null, null, null, abaixo, cortado, ilegiveis);
    }

    /** Este mesmo resumo, agora dizendo que foi lido na subpasta {@code sub} e qual é a lista de onde ela veio. */
    public ResumoDoRepositorio naSubpasta(String sub, List<RepositorioAbaixo> todos, boolean cortado) {
        return new ResumoDoRepositorio(estado, branch, cabecaSolta, ultimo, alteracoes, falha, pastaPai, sub, todos, cortado, ilegiveis);
    }

    public boolean temGit() {
        return estado == Estado.COM_GIT;
    }

    public boolean semCommits() {
        return estado == Estado.COM_GIT && ultimo == null;
    }

    /** O Git lido é o de uma subpasta do projeto. */
    public boolean emSubpasta() {
        return subpasta != null;
    }
}
