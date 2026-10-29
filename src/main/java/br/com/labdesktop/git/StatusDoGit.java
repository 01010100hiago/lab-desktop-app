package br.com.labdesktop.git;

import java.util.List;

/**
 * O {@code git status --porcelain=v2 --branch} inteiro.
 *
 * @param branch     o nome do branch, ou {@code null} se a cabeça está solta
 * @param cabecaSolta a pasta está num commit solto (sem branch)
 * @param commitAtual o hash inteiro da ponta, ou {@code null} se o repositório ainda não tem commits
 * @param upstream    o branch remoto que este acompanha, ou {@code null}
 * @param aFrente     commits locais que o remoto não tem ({@code -1} se não há remoto)
 * @param atras       commits do remoto que faltam aqui ({@code -1} se não há remoto)
 * @param pendencias  as alterações ainda não commitadas (sem os arquivos ignorados)
 */
public record StatusDoGit(String branch, boolean cabecaSolta, String commitAtual, String upstream, int aFrente, int atras, List<Pendencia> pendencias) {

    public StatusDoGit {
        pendencias = List.copyOf(pendencias);
    }

    public boolean semCommits() {
        return commitAtual == null;
    }

    public int alteracoes() {
        return pendencias.size();
    }
}
