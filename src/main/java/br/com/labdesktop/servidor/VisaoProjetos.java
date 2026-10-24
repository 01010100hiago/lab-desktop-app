package br.com.labdesktop.servidor;

import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.ListaProjetos;

/**
 * O que a tela Projetos mostra: a lista de {@code /api/projetos} e a sessão raiz de {@code /api/projetos/lab}
 * (a raiz NÃO está na lista; o front a busca junto, e a falha dessa busca vira {@code null} sem erro de tela).
 */
public record VisaoProjetos(ListaProjetos lista, DetalheRaiz raiz) {
}
