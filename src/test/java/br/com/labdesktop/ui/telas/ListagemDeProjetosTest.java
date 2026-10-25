package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.ui.telas.ListagemDeProjetos.Filtro;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Busca, filtro rápido e ordem da lista (a mesma regra do index.html). */
class ListagemDeProjetosTest {

    private static LinhaDeProjeto linha(String nome, boolean noAr, boolean sessao, boolean pausado) {
        String estadoDosContainers = noAr ? "running" : "exited";
        String limite = pausado ? ",\"limite\":{\"teto_pct\":20,\"usado_pp\":22,\"estado\":\"pausado\"}" : "";
        return LinhaDeProjeto.de(FixturesDeApi.de("{\"nome\":\"" + nome + "\",\"composes\":[\"c.yml\"],\"containers\":[{\"estado\":\""
            + estadoDosContainers + "\"}],\"sessaoClaude\":" + sessao + limite + "}", Projeto.class));
    }

    private static final List<LinhaDeProjeto> TODAS = List.of(
        linha("zeta", false, false, false), linha("alfa", true, true, false), linha("Ágata", false, false, true),
        linha("beta", true, false, false), linha("portal", false, true, true));

    private static List<String> nomes(List<LinhaDeProjeto> l) {
        return l.stream().map(LinhaDeProjeto::nome).toList();
    }

    @Test
    void noArPrimeiroDepoisPorNomeIgnorandoAcentoEMaiuscula() {
        assertEquals(List.of("alfa", "beta", "Ágata", "portal", "zeta"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.TODOS, "")));
    }

    @Test
    void filtrosRapidos() {
        assertEquals(List.of("alfa", "beta"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.NO_AR, "")));
        assertEquals(List.of("alfa", "portal"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.COM_CLAUDE, "")));
        assertEquals(List.of("Ágata", "portal"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.PAUSADOS, "")));
    }

    @Test
    void buscaPorTrechoDoNomeSemDiferenciarMaiuscula() {
        assertEquals(List.of("alfa", "portal"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.TODOS, "AL")), "AL casa com alfa e com port-al");
        assertEquals(List.of("beta"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.TODOS, "  bet ")));
        assertEquals(List.of(), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.TODOS, "nada")));
        assertEquals(List.of("alfa"), nomes(ListagemDeProjetos.visiveis(TODAS, Filtro.COM_CLAUDE, "alf")), "busca e filtro juntos");
    }

    @Test
    void resumoNoSingularEPlural() {
        ListaProjetos um = FixturesDeApi.de("{\"projetos\":[{\"nome\":\"a\",\"rodando\":true,\"sessaoClaude\":true,"
            + "\"limite\":{\"teto_pct\":1,\"usado_pp\":2,\"estado\":\"pausado\"}}],\"servidor\":{}}", ListaProjetos.class);
        assertEquals("1 projeto, 1 no ar, 1 com Claude aberto, 1 pausado", ListagemDeProjetos.resumo(um.projetos()));
        ListaProjetos zero = FixturesDeApi.de("{\"projetos\":[],\"servidor\":{}}", ListaProjetos.class);
        assertEquals("0 projetos, 0 no ar, 0 com Claude aberto", ListagemDeProjetos.resumo(zero.projetos()));
        assertEquals(List.of("0 projetos", "0 no ar", "0 com Claude aberto"), ListagemDeProjetos.partesDoResumo(zero.projetos()),
            "cada dado é um item separado, sem pontinho colando frases; o pausado só aparece quando existe");
        assertEquals(List.of("1 projeto", "1 no ar", "1 com Claude aberto", "1 pausado"), ListagemDeProjetos.partesDoResumo(um.projetos()));
    }
}
