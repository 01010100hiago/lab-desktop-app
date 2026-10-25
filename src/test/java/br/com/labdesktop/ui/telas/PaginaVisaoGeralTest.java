package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.TelaProjetoTest.Cena;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A aba Visão geral do projeto: cabeçalho, ações, endereços, indicadores, tabela de containers e o encaixe numa tela só. */
@ExtendWith(VerificaEdt.class)
class PaginaVisaoGeralTest {

    private static Cena cena(boolean acoes, DetalheProjeto d) {
        Visual.tema(Tema.CLARO);
        return new Cena(acoes, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, d);
    }

    private static PaginaVisaoGeral pagina(Cena c) {
        return c.tela.visaoGeral();
    }

    // ---------------------------------------------------------------------------------------------
    // Cabeçalho
    // ---------------------------------------------------------------------------------------------

    @Test
    void cabecalhoMostraDescricaoGitTamanhoMexidoEComposes() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaVisaoGeral p = pagina(c);
        assertEquals("Painel de relatórios de exemplo (dados inventados para os testes do app).", Visual.naEdt(p::textoDaDescricao));
        assertEquals("main@a1b2c3d", Visual.naEdt(p::textoDoGit));
        assertTrue(textoDaPagina(p).contains("não commitado"));
        assertEquals("·  tamanho ainda não medido  ·  mexido há 3 d", Visual.naEdt(p::textoDaMeta));
        assertEquals("Compose: docker-compose.yml, docker-compose.dev.yml  ·  Redes: orbita_default", Visual.naEdt(p::textoDaLinhaDiscreta));
    }

    @Test
    void tamanhoMedidoApareceEmPortugues() {
        Cena c = cena(true, TelaProjetoTest.detalheCom("\"tamanho\": null", "\"tamanho\": 1284193456"));
        assertTrue(Visual.naEdt(pagina(c)::textoDaMeta).contains("1,2 GB"), Visual.naEdt(pagina(c)::textoDaMeta));
    }

    @Test
    void semGitSemDescricaoSemRedesNaoQuebram() {
        String json = TelaProjetoTest.detalheEmUmaLinha()
            .replaceFirst("\"git\": \\{.*?\\}, \"backups\"", "\"git\": null, \"backups\"")
            .replace("Painel de relatórios de exemplo (dados inventados para os testes do app).", "")
            .replace("\"redes\": [ \"orbita_default\" ]", "\"redes\": []")
            .replace("\"acessoDocker\": true", "\"acessoDocker\": false");
        Cena c = cena(true, FixturesDeApi.de(json, DetalheProjeto.class));
        PaginaVisaoGeral p = pagina(c);
        assertEquals("Sem descrição", Visual.naEdt(p::textoDaDescricao));
        assertEquals("sem git", Visual.naEdt(p::textoDoGit));
        assertFalse(textoDaPagina(p).contains("não commitado"));
        String discreta = Visual.naEdt(p::textoDaLinhaDiscreta);
        assertTrue(discreta.contains("a sessão do Claude não tem acesso ao Docker deste projeto"), discreta);
        assertFalse(discreta.contains("Redes"), discreta);
    }

    private static String textoDaPagina(PaginaVisaoGeral p) {
        return Visual.naEdt(p::textos);
    }

    // ---------------------------------------------------------------------------------------------
    // Endereços públicos
    // ---------------------------------------------------------------------------------------------

    @Test
    void enderecoPublicoEClicavelEAbreHttps() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        var botoes = Visual.naEdt(() -> java.util.Arrays.stream(pagina(c).enderecos().getComponents())
            .filter(x -> x instanceof javax.swing.AbstractButton).map(x -> (javax.swing.AbstractButton) x).toList());
        assertEquals(1, botoes.size());
        assertEquals("orbita-lab.example.com", Visual.naEdt(() -> botoes.get(0).getText()));
        Visual.naEdt(() -> botoes.get(0).doClick());
        assertEquals(List.of("https://orbita-lab.example.com"), c.navegador.abertas.stream().map(java.net.URI::toString).toList());
    }

    @Test
    void enderecoForaDaListaDePermitidosNaoAbreEAvisa() {
        Cena c = cena(true, TelaProjetoTest.detalheCom("[ \"orbita-lab.example.com\" ]", TelaProjetoTest.HOSTS_FORA_DA_LISTA));
        var botoes = Visual.naEdt(() -> java.util.Arrays.stream(pagina(c).enderecos().getComponents())
            .filter(x -> x instanceof javax.swing.AbstractButton).map(x -> (javax.swing.AbstractButton) x).toList());
        assertEquals(2, botoes.size());
        Visual.naEdt(() -> botoes.get(1).doClick());
        assertTrue(c.navegador.abertas.isEmpty(), "malvado.example.org não é *.example.com");
        assertTrue(c.avisos.disse("atencao", "lista de endereços permitidos"), c.avisos.todos.toString());
        Visual.naEdt(() -> botoes.get(0).doClick());
        assertEquals(1, c.navegador.abertas.size(), "o permitido abre");
    }

    @Test
    void maisDeTresEnderecosViramMaisN() {
        String hosts = "[ \"a.example.com\", \"b.example.com\", \"c.example.com\", \"d.example.com\", \"e.example.com\" ]";
        Cena c = cena(true, TelaProjetoTest.detalheCom("[ \"orbita-lab.example.com\" ]", hosts));
        PaginaVisaoGeral p = pagina(c);
        var botoes = Visual.naEdt(() -> java.util.Arrays.stream(p.enderecos().getComponents())
            .filter(x -> x instanceof javax.swing.AbstractButton).toList());
        assertEquals(3, botoes.size());
        assertTrue(Visual.naEdt(() -> br.com.labdesktop.ui.TextosDeTela.de(p.enderecos())).contains("+2"));
    }

    @Test
    void semEnderecoDizSemEnderecoPublico() {
        Cena c = cena(true, TelaProjetoTest.detalheCom("[ \"orbita-lab.example.com\" ]", "[]"));
        assertTrue(Visual.naEdt(() -> br.com.labdesktop.ui.TextosDeTela.de(pagina(c).enderecos())).contains("sem endereço público"));
    }

    // ---------------------------------------------------------------------------------------------
    // Botões de ação
    // ---------------------------------------------------------------------------------------------

    @Test
    void somenteLeituraNaoTemBotaoQueMudeOServidorEDizPorQue() {
        Cena c = cena(false, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaVisaoGeral p = pagina(c);
        for (var b : List.of(p.botaoCompose(), p.botaoReiniciar(), p.botaoAbrirClaude(), p.botaoRetomar())) {
            assertFalse(Visual.naEdt(b::isVisible), b.getText());
        }
        assertTrue(Visual.naEdt(p::mostraAvisoDeSomenteLeitura));
        long reiniciarPorContainer = Visual.naEdt(() -> p.componentes().stream()
            .filter(x -> x instanceof javax.swing.AbstractButton b && b.isVisible() && "Reiniciar".equals(b.getText())).count());
        assertEquals(0, reiniciarPorContainer, "nem o Reiniciar de cada container");
    }

    @Test
    void comAcoesOProjetoRodandoOferecePararReiniciarEVerSessao() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaVisaoGeral p = pagina(c);
        assertTrue(Visual.naEdt(p.botaoCompose()::isVisible));
        assertEquals("Parar", Visual.naEdt(() -> p.botaoCompose().getText()));
        assertTrue(Visual.naEdt(p.botaoReiniciar()::isVisible));
        assertFalse(Visual.naEdt(p.botaoAbrirClaude()::isVisible), "já tem sessão aberta");
        assertTrue(Visual.naEdt(p.botaoVerSessao()::isVisible));
        assertFalse(Visual.naEdt(p.botaoRetomar()::isVisible));
        assertFalse(Visual.naEdt(p::mostraAvisoDeSomenteLeitura));
    }

    @Test
    void projetoParadoSemSessaoOfereceIniciarEAbrirClaude() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false");
        Cena c = cena(true, FixturesDeApi.de(TelaProjetoTest.detalheEmUmaLinha().replace("\"aberta\": true", "\"aberta\": false")
            .replace("\"estado\": \"running\"", "\"estado\": \"exited\""), DetalheProjeto.class));
        PaginaVisaoGeral p = pagina(c);
        assertEquals("Iniciar", Visual.naEdt(() -> p.botaoCompose().getText()));
        assertTrue(Visual.naEdt(p.botaoAbrirClaude()::isVisible));
        assertFalse(Visual.naEdt(p.botaoVerSessao()::isVisible));
        assertNotNull(d);
    }

    @Test
    void sessaoPausadaOfereceRetomarENaoAbrir() {
        Cena c = cena(true, TelaProjetoTest.detalhePausado());
        PaginaVisaoGeral p = pagina(c);
        assertTrue(Visual.naEdt(p.botaoRetomar()::isVisible));
        assertFalse(Visual.naEdt(p.botaoAbrirClaude()::isVisible));
    }

    @Test
    void semComposeNaoOfereceIniciarNemReiniciar() {
        String json = TelaProjetoTest.detalheEmUmaLinha().replaceFirst("\"composes\": \\[.*?\\], \"containers\"", "\"composes\": [], \"containers\"");
        Cena c = cena(true, FixturesDeApi.de(json, DetalheProjeto.class));
        assertFalse(Visual.naEdt(pagina(c).botaoReiniciar()::isVisible));
        assertEquals("sem arquivo compose", Visual.naEdt(pagina(c)::textoDaLinhaDiscreta).split("  ·  ")[0]);
    }

    // ---------------------------------------------------------------------------------------------
    // Alerta de sessão desatualizada
    // ---------------------------------------------------------------------------------------------

    private static DetalheProjeto desatualizada() {
        return TelaProjetoTest.detalheCom("\"atualizada\": true, \"faltando\": []",
            "\"atualizada\": false, \"faltando\": [\"imagem antiga (Claude desatualizado)\"]");
    }

    @Test
    void sessaoDesatualizadaMostraOAlertaComOMotivoEOBotaoReabrir() {
        Cena c = cena(true, desatualizada());
        PaginaVisaoGeral p = pagina(c);
        assertTrue(Visual.naEdt(p.alerta()::isVisible));
        assertEquals("A sessão do Claude está desatualizada", Visual.naEdt(p.alerta()::textoDoTitulo));
        assertTrue(Visual.naEdt(p.alerta()::textoDaFrase).contains("Falta: imagem antiga (Claude desatualizado)."));
        assertTrue(Visual.naEdt(p.alerta()::textoDaFrase).contains("interrompe a tarefa em andamento"));
        assertTrue(Visual.naEdt(p.botaoReabrir()::isShowing) || Visual.naEdt(() -> p.botaoReabrir().getParent() != null));
        Visual.naEdt(() -> p.botaoReabrir().doClick());
        DialogoConfirmar d = c.dialogos.ultimoComo(DialogoConfirmar.class);
        assertNotNull(d, "Reabrir pergunta antes, avisando que a tarefa é interrompida");
    }

    @Test
    void semAcoesOAlertaInformaMasNaoTemBotao() {
        Cena c = cena(false, desatualizada());
        PaginaVisaoGeral p = pagina(c);
        assertTrue(Visual.naEdt(p.alerta()::isVisible));
        assertNull(Visual.naEdt(() -> p.botaoReabrir().getParent()), "o botão nem entra na faixa");
    }

    private static void assertNull(Object o, String m) {
        org.junit.jupiter.api.Assertions.assertNull(o, m);
    }

    @Test
    void sessaoAtualizadaNaoMostraAlerta() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        assertFalse(Visual.naEdt(pagina(c).alerta()::isVisible));
    }

    // ---------------------------------------------------------------------------------------------
    // Indicadores
    // ---------------------------------------------------------------------------------------------

    @Test
    void cincoIndicadoresComOsValoresDoProjeto() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        var cartoes = Visual.naEdt(() -> pagina(c).faixaDeKpis().cartoes());
        assertEquals(5, cartoes.size());
        assertEquals(List.of("Parcial", "3 de 4", "2,5%", "403 MB", "2,0% de 20%"),
            cartoes.stream().map(k -> Visual.naEdt(k::textoDoValor)).toList());
        assertEquals("ATENÇÃO", Visual.naEdt(cartoes.get(0)::textoDoSelo));
        assertEquals("OK", Visual.naEdt(cartoes.get(4)::textoDoSelo));
        assertFalse(Visual.naEdt(() -> cartoes.get(2).isVisible() && cartoes.get(2).getAccessibleContext().getAccessibleName().contains("OK")),
            "CPU e memória só informam o número");
    }

    @Test
    void indicadorDeConsumoLevaAAbaDeConsumo() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        var consumo = Visual.naEdt(() -> pagina(c).faixaDeKpis().cartoes().get(4));
        assertEquals("Consumo e limite", Visual.naEdt(consumo::destino));
        Visual.naEdt(consumo::clicar);
        assertEquals(Aba.CONSUMO, c.tela.abaAtual());
        assertEquals(Aba.CONSUMO, Visual.naEdt(c.tela::abaMarcada));
        var estado = Visual.naEdt(() -> pagina(c).faixaDeKpis().cartoes().get(0));
        assertEquals(null, Visual.naEdt(estado::destino), "o estado não leva a lugar nenhum");
    }

    @Test
    void indicadorDePausadoDizPausado() {
        Cena c = cena(true, TelaProjetoTest.detalhePausado());
        var consumo = Visual.naEdt(() -> pagina(c).faixaDeKpis().cartoes().get(4));
        assertEquals("PAUSADO", Visual.naEdt(consumo::textoDoSelo));
        assertEquals("22% de 20%", Visual.naEdt(consumo::textoDoValor));
    }

    // ---------------------------------------------------------------------------------------------
    // Tabela de containers
    // ---------------------------------------------------------------------------------------------

    @Test
    void tabelaDeContainersTemUmaLinhaPorContainerEmPalavrasSimples() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        List<List<String>> linhas = Visual.naEdt(() -> pagina(c).tabelaDeContainers().linhasDaPagina());
        assertEquals(4, linhas.size());
        assertEquals(List.of("orbita-web-1", "rodando", "1,4%", "174 MB", "3040 → 3000", "orbita-web:latest", "há 3 d"), linhas.get(0));
        assertEquals(List.of("orbita-banco-1", "rodando", "0,8%", "137 MB", "5440 → 5432", "postgres:16-alpine", "há 3 d"), linhas.get(2));
        assertEquals(List.of("orbita-cache-1", "parado", "—", "—", "—", "redis:7-alpine", "há 5 d"), linhas.get(3));
        assertTrue(Visual.naEdt(() -> java.util.Arrays.asList(pagina(c).textos().split("\n"))).contains("3 de 4 rodando"),
            "o resumo ao lado do título da caixa");
    }

    @Test
    void cadaContainerTemOSeuBotaoReiniciarQuandoHaAcoes() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        long botoes = Visual.naEdt(() -> pagina(c).componentes().stream()
            .filter(x -> x instanceof javax.swing.AbstractButton b && b.isVisible() && "Reiniciar".equals(b.getText())).count());
        assertEquals(1 + 4, botoes, "o do projeto inteiro e um por container");
        var primeiro = Visual.naEdt(() -> pagina(c).componentes().stream().filter(x -> x instanceof javax.swing.AbstractButton b
            && b.getAccessibleContext().getAccessibleName() != null
            && b.getAccessibleContext().getAccessibleName().startsWith("Reiniciar o container ")).findFirst().orElseThrow());
        assertEquals("Reiniciar o container orbita-web-1", Visual.naEdt(() -> ((javax.swing.AbstractButton) primeiro).getAccessibleContext().getAccessibleName()));
    }

    @Test
    void maisContainersQueACabeEmUmaPaginaSaoPaginados() {
        Cena c = cena(true, TelaProjetoTest.detalheComContainers(14));
        var tabela = Visual.naEdt(() -> pagina(c).tabelaDeContainers());
        assertEquals(PaginaVisaoGeral.CONTAINERS_POR_PAGINA, Visual.naEdt(() -> tabela.linhasDaPagina().size()));
        assertEquals("mostrando 1-5 de 14", Visual.naEdt(tabela::textoDoResumo));
        assertEquals(3, Visual.naEdt(tabela::totalDePaginas));
        Visual.naEdt(() -> tabela.botaoProxima().doClick());
        assertEquals("mostrando 6-10 de 14", Visual.naEdt(tabela::textoDoResumo));
        assertEquals("orbita-servico6-1", Visual.naEdt(() -> tabela.linhasDaPagina().get(0).get(0)));
    }

    @Test
    void aPaginaDaTabelaSobreviveAoPollingDe10s() {
        Cena c = cena(true, TelaProjetoTest.detalheComContainers(14));
        var tabela = Visual.naEdt(() -> pagina(c).tabelaDeContainers());
        Visual.naEdt(() -> tabela.botaoProxima().doClick());
        c.com(EstadoConexao.CONECTADO, TelaProjetoTest.detalheComContainers(14));
        assertEquals(2, Visual.naEdt(tabela::pagina), "o usuário estava na página 2 e continua nela");
    }

    @Test
    void semContainersADiferencaEntreNuncaSubiuESemCompose() {
        String json = TelaProjetoTest.detalheEmUmaLinha().replaceFirst("\"containers\": \\[.*?\\], \"urls\"", "\"containers\": [], \"urls\"");
        Cena c = cena(true, FixturesDeApi.de(json, DetalheProjeto.class));
        assertEquals(0, Visual.naEdt(() -> pagina(c).tabelaDeContainers().linhasDaPagina().size()));
        assertTrue(textoDaPagina(pagina(c)).contains("Nenhum container criado ainda"));
    }

    // ---------------------------------------------------------------------------------------------
    // Cabe numa tela só, sem rolagem
    // ---------------------------------------------------------------------------------------------

    private static void conferirQueCabe(int larguraDaJanela, int alturaDaJanela, DetalheProjeto d) {
        Cena c = cena(true, d);
        int largura = larguraDaJanela - BarraServidores.LARGURA;
        int altura = alturaDaJanela - 28; // a barra de título da janela
        int pedida = Visual.alturaPreferida(c.tela, largura);
        assertTrue(pedida <= altura, "a Visão geral pede " + pedida + " px de altura, mas a janela de " + larguraDaJanela + "x"
            + alturaDaJanela + " dá " + altura);
        Visual.renderizar(c.tela, largura, altura);
        PaginaVisaoGeral p = pagina(c);
        assertFalse(Visual.naEdt(() -> p.rolagem().getVerticalScrollBar().isVisible()), "a Visão geral não rola");
        assertFalse(Visual.naEdt(() -> p.rolagem().getHorizontalScrollBar().isVisible()), "nem na horizontal");
        assertFalse(Visual.naEdt(() -> p.rolagem().rola()));
        Visual.naEdt(() -> {
            int doConteudo = p.pilha().getPreferredSize().height;
            assertTrue(doConteudo + c.tela.topoDaPagina().getPreferredSize().height <= altura, "conteúdo " + doConteudo);
        });
    }

    @Test
    void cabeSemRolagemEm1100x700() {
        conferirQueCabe(1100, 700, FixturesDeApi.detalheDoProjetoSintetico());
    }

    @Test
    void cabeSemRolagemEm1440x860() {
        conferirQueCabe(1440, 860, FixturesDeApi.detalheDoProjetoSintetico());
    }

    @Test
    void cabeMesmoNoPiorCasoEm1100x700() {
        // 14 containers (uma página cheia), sessão desatualizada com motivo comprido, três endereços, git sujo e sessão pausada de lado
        String falta = "imagem antiga (Claude desatualizado), plugins do harness fora da versão, extensão do projeto sem atualizar";
        String json = TelaProjetoTest.detalheEmUmaLinha()
            .replace("\"atualizada\": true, \"faltando\": []", "\"atualizada\": false, \"faltando\": [\"" + falta + "\"]")
            .replace("[ \"orbita-lab.example.com\" ]", "[ \"orbita-lab.example.com\", \"orbita-api.example.com\", \"orbita-painel-de-controle.example.com\", \"d.example.com\" ]");
        DetalheProjeto base = FixturesDeApi.de(json, DetalheProjeto.class);
        DetalheProjeto muitos = TelaProjetoTest.detalheComContainers(14);
        assertEquals(4, base.containers().size());
        assertEquals(14, muitos.containers().size());
        String comTudo = json.replaceFirst("\"containers\": \\[.*?\\], \"urls\"", java.util.regex.Matcher.quoteReplacement(
            "\"containers\": " + containersEmJson(muitos) + ", \"urls\""));
        conferirQueCabe(1100, 700, FixturesDeApi.de(comTudo, DetalheProjeto.class));
    }

    private static String containersEmJson(DetalheProjeto d) {
        try {
            return br.com.labdesktop.util.Json.mapper().writeValueAsString(d.containers());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void asColunasDaTabelaNaoCortamNada() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.renderizar(c.tela, 1100 - BarraServidores.LARGURA, 672);
        Visual.naEdt(() -> {
            for (Component x : pagina(c).componentes()) {
                if (x instanceof javax.swing.JLabel l && l.isShowing() && l.getText() != null && l.getText().startsWith("orbita-")) {
                    assertTrue(l.getWidth() >= l.getPreferredSize().width, "'" + l.getText() + "' cortado: " + l.getWidth() + " < "
                        + l.getPreferredSize().width);
                }
            }
        });
    }
}
