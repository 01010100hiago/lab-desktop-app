package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.EscolhaDeInstalacao.Tipo;
import br.com.labdesktop.instalador.RelatorioDeVerificacao.Administrador;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O modelo PURO da tela "O que instalar": Completo é todos os itens que a tela oferece, Escolher aceita as combinações válidas e
 * recusa, com uma frase, as que deixariam o servidor pela metade, Só SSH não instala nada, e tudo vem de uma lista de DESCRIÇÕES (um item
 * novo no futuro é uma descrição a mais, sem código novo).
 */
class EscolhaDeInstalacaoTest {

    private static final List<ItemDeInstalacao> CATALOGO = CatalogoDeInstalacao.itens();

    /** Um relatório mínimo: só importam o Docker (falta ou não) e os resultados que o teste quiser filtrar. */
    private static RelatorioDeVerificacao relatorio(boolean dockerAusente) {
        return new RelatorioDeVerificacao(
            List.of(ResultadoDaVerificacao.ok(Item.SISTEMA, "Debian"), ResultadoDaVerificacao.ok(Item.PERMISSAO, "root"),
                ResultadoDaVerificacao.ok(Item.DOCKER, "docker"), ResultadoDaVerificacao.ok(Item.PORTA, "livre"),
                ResultadoDaVerificacao.problema(Item.DISCO, "pouco espaço", null)),
            ModoDaInstalacao.INSTALAR, dockerAusente ? null : "docker", Administrador.ROOT, "debian", !dockerAusente, dockerAusente);
    }

    private static List<String> ids(EscolhaDeInstalacao e) {
        return e.itens().stream().map(ItemDeInstalacao::id).toList();
    }

    // ---- Completo ----

    @Test
    void completoEhTodosOsItensQueATelaOfereceParaOServidor() {
        EscolhaDeInstalacao semDocker = EscolhaDeInstalacao.completo(CATALOGO, relatorio(true));
        assertEquals(Tipo.COMPLETO, semDocker.tipo());
        assertEquals(List.of("docker", "agente"), ids(semDocker), "Docker faltando: o Completo traz o Docker e o agente");
        assertEquals(List.of(PassoDeInstalacao.values()), semDocker.passos(), "e roda TODOS os passos, na ordem");
        assertTrue(semDocker.valida());

        EscolhaDeInstalacao comDocker = EscolhaDeInstalacao.completo(CATALOGO, relatorio(false));
        assertEquals(List.of("agente"), ids(comDocker), "o Docker só aparece se faltar");
        assertFalse(comDocker.passos().contains(PassoDeInstalacao.DOCKER));
        assertEquals(5, comDocker.passos().size());
        assertTrue(comDocker.valida());
    }

    // ---- Escolher: combinações válidas e inválidas ----

    @Test
    void escolherSoODockerEValidoEInstalaSoOPassoDoDocker() {
        EscolhaDeInstalacao e = EscolhaDeInstalacao.escolher(CATALOGO, relatorio(true), Set.of("docker"));
        assertTrue(e.valida(), e.problemas().toString());
        assertEquals(List.of(PassoDeInstalacao.DOCKER), e.passos());
        assertEquals(EnumSet.of(Item.SISTEMA, Item.PERMISSAO, Item.DOCKER), e.verificacoes(), "só as verificações do Docker valem");
    }

    @Test
    void escolherOAgenteSemODockerQueFaltaEInvalidoEExplicaEmPortuguesSimples() {
        EscolhaDeInstalacao e = EscolhaDeInstalacao.escolher(CATALOGO, relatorio(true), Set.of("agente"));
        assertFalse(e.valida(), "o agente precisa do Docker: nunca instala metade");
        assertEquals(1, e.problemas().size());
        String frase = e.problemas().get(0);
        assertTrue(frase.contains("Agente de monitoramento precisa do Docker, que falta neste servidor"), frase);
        assertTrue(frase.contains("Marque o Docker também, ou desmarque o Agente de monitoramento"), frase);
    }

    @Test
    void escolherOAgenteComODockerJaNoServidorEValido() {
        EscolhaDeInstalacao e = EscolhaDeInstalacao.escolher(CATALOGO, relatorio(false), Set.of("agente"));
        assertTrue(e.valida(), e.problemas().toString());
        assertEquals(5, e.passos().size());
    }

    @Test
    void escolherOsDoisJuntosEValidoEMarcarNadaTemExplicacao() {
        assertTrue(EscolhaDeInstalacao.escolher(CATALOGO, relatorio(true), Set.of("docker", "agente")).valida());
        EscolhaDeInstalacao nada = EscolhaDeInstalacao.escolher(CATALOGO, relatorio(true), Set.of());
        assertFalse(nada.valida());
        assertFalse(nada.instalaAlgo());
        assertTrue(nada.problemas().get(0).contains("Marque pelo menos um item"), nada.problemas().toString());
        assertTrue(nada.problemas().get(0).contains("Só SSH"));
    }

    @Test
    void marcarUmItemQueATelaNaoOfereceNaoValeOServidorJaTemODocker() {
        assertThrows(IllegalArgumentException.class, () -> EscolhaDeInstalacao.escolher(CATALOGO, relatorio(false), Set.of("docker")));
        assertThrows(IllegalArgumentException.class, () -> EscolhaDeInstalacao.escolher(CATALOGO, relatorio(true), Set.of("inventado")));
    }

    // ---- Só SSH ----

    @Test
    void soSshNaoInstalaNadaENuncaTemProblema() {
        EscolhaDeInstalacao e = EscolhaDeInstalacao.soSsh();
        assertEquals(Tipo.SO_SSH, e.tipo());
        assertFalse(e.instalaAlgo());
        assertEquals(List.of(), e.passos());
        assertEquals(Set.of(), e.verificacoes());
        assertEquals(List.of(), e.itens());
        assertTrue(e.valida());
    }

    // ---- a lista de descrições ----

    @Test
    void todoPassoDoInstaladorPertenceAExatamenteUmItemEOsItensSaoIndependentesNosPassos() {
        Set<PassoDeInstalacao> vistos = new HashSet<>();
        for (ItemDeInstalacao i : CATALOGO) {
            for (PassoDeInstalacao p : i.passos()) {
                assertTrue(vistos.add(p), "o passo " + p + " está em mais de um item");
            }
        }
        assertEquals(EnumSet.allOf(PassoDeInstalacao.class), vistos, "nenhum passo real fica de fora, e nenhum item inventa passo");
        // a imagem, o container, a sonda e o token não existem sozinhos: são UM item (o agente), que depende do Docker
        ItemDeInstalacao agente = CATALOGO.stream().filter(i -> i.id().equals("agente")).findFirst().orElseThrow();
        assertEquals(List.of(PassoDeInstalacao.ENVIAR_PACOTE, PassoDeInstalacao.CONSTRUIR_IMAGEM, PassoDeInstalacao.SUBIR_CONTAINER,
            PassoDeInstalacao.SONDAR, PassoDeInstalacao.REGISTRAR_TOKEN), agente.passos());
        assertEquals(List.of("docker"), agente.dependeDe());
    }

    @Test
    void umItemNovoNaListaFuncionaSemCodigoNovoComDependenciaEPassosProprios() {
        // Um item hipotético que depende do agente e usa um passo "emprestado" só para o teste: o modelo não conhece item pelo nome.
        ItemDeInstalacao extra = new ItemDeInstalacao("extra", "Extra", "uma coisa a mais", "nada de novo", "nada",
            List.of("agente"), List.of(PassoDeInstalacao.REGISTRAR_TOKEN), EnumSet.of(Item.DISCO), r -> true, r -> false);
        List<ItemDeInstalacao> catalogo = new java.util.ArrayList<>(CATALOGO);
        catalogo.add(extra);
        RelatorioDeVerificacao docker = relatorio(false);

        assertEquals(List.of("agente", "extra"), ids(EscolhaDeInstalacao.completo(catalogo, docker)));
        EscolhaDeInstalacao semOAgente = EscolhaDeInstalacao.escolher(catalogo, docker, Set.of("extra"));
        assertFalse(semOAgente.valida());
        assertTrue(semOAgente.problemas().get(0).contains("Extra precisa do Agente de monitoramento, que falta neste servidor"), semOAgente.problemas().toString());
        assertTrue(EscolhaDeInstalacao.escolher(catalogo, docker, Set.of("agente", "extra")).valida());
        // e uma dependência que nem existe no catálogo trava (nunca instala sem saber o que falta)
        ItemDeInstalacao orfao = new ItemDeInstalacao("orfao", "Órfão", "x", "x", "x", List.of("fantasma"), List.of(PassoDeInstalacao.SONDAR),
            EnumSet.noneOf(Item.class), r -> true, r -> false);
        assertFalse(EscolhaDeInstalacao.escolher(List.of(orfao), docker, Set.of("orfao")).valida());
    }

    @Test
    void asVerificacoesDeUmItemNaoEscolhidoNaoBloqueiamQuemNaoOEscolheu() {
        RelatorioDeVerificacao completo = relatorio(true);
        assertTrue(completo.bloqueado(), "o relatório completo tem um problema (o espaço em disco)");
        RelatorioDeVerificacao soDocker = completo.somenteDe(EscolhaDeInstalacao.escolher(CATALOGO, completo, Set.of("docker")).verificacoes());
        assertFalse(soDocker.bloqueado(), "o espaço do disco é do agente: quem só instala o Docker não é barrado por ele");
        assertEquals(List.of(Item.SISTEMA, Item.PERMISSAO, Item.DOCKER), soDocker.resultados().stream().map(ResultadoDaVerificacao::item).toList());
        assertTrue(completo.somenteDe(EscolhaDeInstalacao.completo(CATALOGO, completo).verificacoes()).bloqueado(), "com o agente, o problema vale");
        assertEquals(completo.dockerAusente(), soDocker.dockerAusente(), "os fatos do servidor não mudam");
    }
}
