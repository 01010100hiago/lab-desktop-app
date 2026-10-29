package br.com.labdesktop.git;

import java.util.List;

/**
 * Uma fatia da lista de commits.
 *
 * @param commits  os commits desta fatia, do mais novo para o mais antigo
 * @param temMais  há commits mais antigos ({@code Carregar mais})
 */
public record PaginaDeCommits(List<Commit> commits, boolean temMais) {

    public PaginaDeCommits {
        commits = List.copyOf(commits);
    }
}
