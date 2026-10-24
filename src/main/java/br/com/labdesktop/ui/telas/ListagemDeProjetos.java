package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.Projeto;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Busca, filtro rápido e ordem da lista (ARQUITETURA 8.3), a mesma regra do {@code index.html}: "no ar" primeiro,
 * depois por nome (pt-BR); filtros Todos, No ar, Com Claude e Pausados; busca por trecho do nome.
 */
public final class ListagemDeProjetos {

    /** Os botões de filtro rápido. */
    public enum Filtro {
        TODOS("Todos"),
        NO_AR("No ar"),
        COM_CLAUDE("Com Claude"),
        PAUSADOS("Pausados");

        private final String rotulo;

        Filtro(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }

        public String dica() {
            return this == PAUSADOS ? "Projetos com a sessão do Claude pausada pelo limite ou pelo freio" : null;
        }
    }

    private static final Collator ORDEM_PT_BR = ordemPtBr();

    private ListagemDeProjetos() { }

    private static Collator ordemPtBr() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        c.setStrength(Collator.SECONDARY); // "á" e "a" juntos; maiúscula e minúscula juntas
        return c;
    }

    /** Aplica a busca e o filtro e ordena. A lista de entrada não é alterada. */
    public static List<LinhaDeProjeto> visiveis(List<LinhaDeProjeto> todas, Filtro filtro, String busca) {
        String termo = busca == null ? "" : busca.strip().toLowerCase(Locale.ROOT);
        List<LinhaDeProjeto> saida = new ArrayList<>();
        for (LinhaDeProjeto l : todas) {
            if (!termo.isEmpty() && !l.nome().toLowerCase(Locale.ROOT).contains(termo)) {
                continue;
            }
            boolean passa = switch (filtro) {
                case TODOS -> true;
                case NO_AR -> l.estaNoAr();
                case COM_CLAUDE -> l.sessaoAberta();
                case PAUSADOS -> l.limite() != null && l.limite().pausado();
            };
            if (passa) {
                saida.add(l);
            }
        }
        saida.sort(Comparator.comparing(LinhaDeProjeto::estaNoAr).reversed()
            .thenComparing(LinhaDeProjeto::nome, ORDEM_PT_BR));
        return saida;
    }

    /** {@code 7 projetos · 4 no ar · 2 com Claude aberto · 1 pausado}: a linha de resumo ao lado do filtro. */
    public static String resumo(List<Projeto> projetos) {
        int total = projetos.size();
        long rodando = projetos.stream().filter(p -> Boolean.TRUE.equals(p.rodando())).count();
        long comClaude = projetos.stream().filter(Projeto::temSessaoClaude).count();
        long pausados = projetos.stream().filter(Projeto::pausado).count();
        return total + (total == 1 ? " projeto" : " projetos") + " · " + rodando + " no ar · " + comClaude
            + " com Claude aberto" + (pausados > 0 ? " · " + pausados + (pausados == 1 ? " pausado" : " pausados") : "");
    }
}
