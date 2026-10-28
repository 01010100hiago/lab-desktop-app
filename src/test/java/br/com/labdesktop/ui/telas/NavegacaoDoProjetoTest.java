package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Entrar e sair da página de um projeto com servidor VIVO (SSH embutido e o agente de teste): clicar na linha, Enter, "‹ Projetos"
 * e Esc, a aba lembrada por projeto, o projeto que some do servidor, e a prova da carga: cada aba só consulta o que usa, e só
 * enquanto está na tela. As ações ficam LIGADAS ({@code labdesktop.acoes}) para provar que os botões da linha seguem funcionando.
 */
@ExtendWith(VerificaEdt.class)
class NavegacaoDoProjetoTest {

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private NavegadorGravado navegador;
    private PainelPrincipal painel;
    private String acoesAntes;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        acoesAntes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
        cenario = new CenarioDeUi();
        cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
        cenario.agente.detalhes.put("fit-app", MocksDoFront.ler("projetos/fit-app.json"));
        navegador = new NavegadorGravado(avisos);
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, dialogos, navegador));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(() -> painel.encerrar());
        cenario.close();
        if (acoesAntes == null) {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        } else {
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, acoesAntes);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------------------------------

    private TelaServidor servidor() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    private TelaProjeto projeto() {
        return Visual.naEdt(() -> servidor().projetoAberto());
    }

    /** Mostra a aba Projetos e espera a lista chegar pelo túnel (a tela só assina com janela, então o teste assina por ela). */
    private TelaProjetos listaPronta() {
        Visual.naEdt(painel::mostrarProjetos);
        TelaProjetos lista = Visual.naEdt(() -> servidor().projetos());
        Visual.naEdt(lista::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "lista: " + Visual.naEdt(lista::nomesExibidos),
            () -> Visual.naEdt(lista::nomesExibidos).contains("app-loja"));
        return lista;
    }

    private static void clicar(Component alvo) {
        Visual.naEdt(() -> alvo.dispatchEvent(new MouseEvent(alvo, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 1, false,
            MouseEvent.BUTTON1)));
    }

    /** O que um clique na janela faria: mostra a página do projeto e liga as assinaturas da página e da aba que está na frente. */
    private TelaProjeto abrirPeloLab(String nome) {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, nome));
        TelaProjeto t = projeto();
        Visual.naEdt(t::assinar);
        Visual.naEdt(() -> t.pagina(t.abaAtual()).assinar());
        return t;
    }

    private void esperarDetalhe(TelaProjeto t) {
        Espera.ate(Duration.ofSeconds(20), () -> "detalhe de " + t.projeto(),
            () -> Visual.naEdt(() -> t.visaoGeral().mostraCorpo()));
    }

    /** A aba abre o terminal do Claude sozinha (a sessão está aberta); estes testes querem a tela em texto, que vem por HTTP. */
    private static void telaEmTexto(TelaProjeto t) {
        Visual.naEdt(() -> {
            if (t.terminal().terminalAFrente()) {
                t.terminal().botaoTelaEmTexto().doClick(0);
            }
        });
    }

    private int leituras(String trecho) {
        return (int) cenario.agente.leiturasDeProjeto.stream().filter(l -> l.contains(trecho)).count();
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir
    // ---------------------------------------------------------------------------------------------

    @Test
    void cliqueEmQualquerPontoDaLinhaAbreAPaginaDoProjetoNoMesmoPainelDoServidor() {
        TelaProjetos lista = listaPronta();
        assertNull(projeto());
        LinhaProjetoVisual l = Visual.naEdt(() -> lista.linha("app-loja"));
        clicar(Visual.naEdt(l::rotuloDoNome));
        TelaProjeto t = projeto();
        assertNotNull(t, "o clique no nome abriu a página");
        assertEquals("app-loja", Visual.naEdt(t::projeto));
        assertEquals(Aba.VISAO_GERAL, Visual.naEdt(t::abaAtual), "a primeira vez abre a Visão geral");
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()), "no mesmo painel do servidor");
        assertTrue(Visual.naEdt(t::isVisible));
        // as abas do servidor saíram do caminho: quem está na frente é a página do projeto
        assertSame(t, Visual.naEdt(() -> servidor().projetoAberto()));
    }

    @Test
    void cliqueNoEstadoEmDescricaoOuNoFundoDaLinhaTambemAbre() {
        TelaProjetos lista = listaPronta();
        LinhaProjetoVisual l = Visual.naEdt(() -> lista.linha("app-loja"));
        for (Component alvo : new Component[] {Visual.naEdt(l::rotuloDoEstado), Visual.naEdt(l::rotuloDaDescricao), l}) {
            Visual.naEdt(() -> servidor().voltarParaALista());
            assertNull(projeto());
            clicar(alvo);
            assertNotNull(projeto(), "clicou em " + alvo.getClass().getSimpleName());
        }
    }

    @Test
    void osBotoesDaLinhaContinuamFuncionandoSemAbrirAPagina() {
        TelaProjetos lista = listaPronta();
        LinhaProjetoVisual l = Visual.naEdt(() -> lista.linha("app-loja"));
        assertTrue(Visual.naEdt(l.botaoCompose()::isVisible), "as ações estão ligadas neste teste");
        clicar(l.botaoCompose());
        Visual.naEdt(() -> l.botaoCompose().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "a ação de compose chegou ao agente",
            () -> cenario.recebeu("POST", "/api/projetos/app-loja/parar") || cenario.recebeu("POST", "/api/projetos/app-loja/iniciar"));
        assertNull(projeto(), "o botão fez o que faz e não abriu a página");
        clicar(l.botaoUrl());
        Visual.naEdt(() -> l.botaoUrl().doClick());
        assertNull(projeto());
        clicar(l.campoDoCodigo());
        assertNull(projeto(), "nem o campo do código de login");
        clicar(l.botaoMenu());
        assertNull(projeto(), "nem o menu de mais ações");
        assertFalse(navegador.abertas.isEmpty(), "o link do endereço público abriu o navegador do sistema");
    }

    @Test
    void enterComALinhaFocadaAbreEALinhaTemFocoCursorDeMaoEDescricao() {
        TelaProjetos lista = listaPronta();
        LinhaProjetoVisual l = Visual.naEdt(() -> lista.linha("app-loja"));
        assertTrue(Visual.naEdt(l::isFocusable), "dá para chegar na linha com Tab");
        assertEquals(java.awt.Cursor.HAND_CURSOR, Visual.naEdt(() -> l.getCursor().getType()), "cursor de mão");
        assertEquals("abrir-projeto", Visual.naEdt(() -> l.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
            .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0))));
        assertEquals(java.awt.Cursor.HAND_CURSOR, Visual.naEdt(() -> l.rotuloDoNome().getCursor().getType()), "o cursor vale nos filhos");
        Visual.naEdt(() -> l.getActionMap().get("abrir-projeto").actionPerformed(null));
        assertNotNull(projeto());
        assertEquals("app-loja", Visual.naEdt(projeto()::projeto));
    }

    // ---------------------------------------------------------------------------------------------
    // Sair
    // ---------------------------------------------------------------------------------------------

    @Test
    void voltarEEscLevamDeVoltaAListaDoServidor() {
        listaPronta();
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        TelaProjeto t = projeto();
        Visual.naEdt(() -> t.botaoVoltar().doClick());
        assertNull(projeto());
        assertEquals(TelaServidor.Aba.PROJETOS, Visual.naEdt(servidor()::abaAtual));
        assertEquals(TelaServidor.Aba.PROJETOS, Visual.naEdt(servidor()::abaMarcada), "o rótulo de Projetos volta marcado");
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        Visual.naEdt(() -> projeto().aoApertarEsc(null));
        assertNull(projeto(), "Esc também volta");
    }

    @Test
    void trocarParaOutraAbaDoServidorFechaAPaginaDoProjeto() {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        assertNotNull(projeto());
        Visual.naEdt(painel::mostrarResumo);
        assertNull(projeto());
        assertEquals(TelaServidor.Aba.RESUMO, Visual.naEdt(servidor()::abaAtual));
    }

    @Test
    void aAbaEscolhidaFicaLembradaPorProjeto() {
        listaPronta();
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        Visual.naEdt(() -> projeto().abas().botao("logs").doClick());
        assertEquals(Aba.LOGS, servidor().abaLembradaDe("app-loja"));
        Visual.naEdt(() -> projeto().botaoVoltar().doClick());
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "fit-app"));
        assertEquals(Aba.VISAO_GERAL, Visual.naEdt(projeto()::abaAtual), "outro projeto, outra lembrança: começa na Visão geral");
        Visual.naEdt(() -> projeto().abas().botao("consumo").doClick());
        Visual.naEdt(() -> projeto().botaoVoltar().doClick());
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        assertEquals(Aba.LOGS, Visual.naEdt(projeto()::abaAtual), "app-loja volta na aba Logs");
        assertEquals(Aba.LOGS, Visual.naEdt(projeto()::abaMarcada));
        Visual.naEdt(() -> projeto().botaoVoltar().doClick());
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "fit-app"));
        assertEquals(Aba.CONSUMO, Visual.naEdt(projeto()::abaAtual), "fit-app volta na Consumo");
    }

    @Test
    void aUltimaPaginaPodeSerReabertaPeloAtalhoDoMenu() {
        assertFalse(Visual.naEdt(painel::temUltimoProjeto));
        assertFalse(Visual.naEdt(painel::temProjetoAberto));
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        assertTrue(Visual.naEdt(painel::temProjetoAberto));
        Visual.naEdt(() -> projeto().botaoVoltar().doClick());
        assertFalse(Visual.naEdt(painel::temProjetoAberto));
        assertTrue(Visual.naEdt(painel::temUltimoProjeto));
        Visual.naEdt(painel::mostrarUltimoProjeto);
        assertEquals("app-loja", Visual.naEdt(projeto()::projeto));
        Visual.naEdt(painel::mostrarProjetos);
        assertNull(projeto(), "Cmd+1 volta para a lista também com um projeto aberto");
    }

    @Test
    void oMenuVisualizarGanhaOsAtalhosCoerentesEHabilitaSoQuandoFazSentido() {
        var menu = Visual.naEdt(() -> new br.com.labdesktop.ui.MenuApp(null, painel,
            new br.com.labdesktop.ui.GerenciadorTema(Tema.CLARO, false), new br.com.labdesktop.ui.PreferenciasAtuais(null, null),
            () -> { }, () -> { }));
        int cmd = br.com.labdesktop.ui.componentes.Teclas.comando(); // o MenuApp usa a tecla de comando do sistema
        var ultimo = Visual.naEdt(menu::itemUltimoProjeto);
        var voltar = Visual.naEdt(menu::itemVoltarAosProjetos);
        assertEquals("Último projeto aberto", Visual.naEdt(ultimo::getText));
        assertEquals("Voltar à lista de projetos", Visual.naEdt(voltar::getText));
        assertEquals(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_4, cmd), Visual.naEdt(ultimo::getAccelerator), "Cmd+4, depois de Cmd+1, 2 e 3");
        assertEquals(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_OPEN_BRACKET, cmd), Visual.naEdt(voltar::getAccelerator), "Cmd+[ é o 'voltar' do macOS");

        Visual.naEdt(menu::atualizarHabilitacaoDoProjeto);
        assertFalse(Visual.naEdt(ultimo::isEnabled), "nenhum projeto foi aberto ainda");
        assertFalse(Visual.naEdt(voltar::isEnabled));
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        Visual.naEdt(menu::atualizarHabilitacaoDoProjeto);
        assertTrue(Visual.naEdt(voltar::isEnabled), "com uma página de projeto aberta dá para voltar");
        Visual.naEdt(() -> voltar.doClick());
        assertNull(projeto());
        Visual.naEdt(menu::atualizarHabilitacaoDoProjeto);
        assertFalse(Visual.naEdt(voltar::isEnabled));
        assertTrue(Visual.naEdt(ultimo::isEnabled));
        Visual.naEdt(() -> ultimo.doClick());
        assertEquals("app-loja", Visual.naEdt(projeto()::projeto));
    }

    @Test
    void voltarAoServidorDepoisDeIrParaOutroLugarMostraOProjetoQueEstavaAberto() {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
        Visual.naEdt(() -> painel.barra().item(CofreFalso.ID).clicarComOBotaoEsquerdo());
        assertNotNull(projeto(), "a página do projeto é lembrada como a aba do servidor");
        assertEquals("app-loja", Visual.naEdt(projeto()::projeto));
    }

    @Test
    void projetoQueSomeDoServidorVoltaParaAListaComAviso() {
        listaPronta();
        TelaProjeto t = abrirPeloLab("app-loja");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("logs").doClick());
        cenario.agente.excecoes = p -> p.caminho().equals("/api/projetos/app-loja") ? Resposta.json(404, "{\"erro\":\"Projeto não existe.\"}") : null;
        Visual.naEdt(() -> cenario.lab.atualizarAgora());
        Espera.ate(Duration.ofSeconds(20), () -> "a lista voltou", () -> projeto() == null);
        assertTrue(avisos.disse("atencao", "O projeto app-loja não existe mais no servidor"), avisos.todos.toString());
        assertEquals(TelaServidor.Aba.PROJETOS, Visual.naEdt(servidor()::abaAtual));
        assertEquals(Aba.VISAO_GERAL, servidor().abaLembradaDe("app-loja"), "o que lembrava do projeto que sumiu foi esquecido");
    }

    @Test
    void servidorQueSaiDaListaFechaAPaginaDoProjetoSemSobrarAssinatura() {
        TelaProjeto t = abrirPeloLab("app-loja");
        esperarDetalhe(t);
        assertTrue(Visual.naEdt(t::assinada));
        Visual.naEdt(() -> servidor().soltar());
        assertFalse(Visual.naEdt(t::assinada));
    }

    // ---------------------------------------------------------------------------------------------
    // A carga: cada aba só consulta o que usa, e só enquanto está na tela
    // ---------------------------------------------------------------------------------------------

    private void esperarParar(String descricao, int antes, String trecho) {
        Espera.naoAcontece(Duration.ofMillis(1300), descricao, () -> leituras(trecho) > antes + 1);
    }

    @Test
    void aPaginaAssinaSoODetalheEAVisaoGeralNaoPedeMaisNada() {
        TelaProjeto t = abrirPeloLab("orbita");
        assertEquals(java.util.Set.of("detalhe:orbita"), t.topicos());
        esperarDetalhe(t);
        assertTrue(leituras("/api/projetos/orbita") >= 1);
        Espera.naoAcontece(Duration.ofMillis(1000), "consumo, logs ou tela na Visão geral",
            () -> leituras("/api/tokens/") > 0 || leituras("/logs") > 0 || leituras("/claude/tela") > 0);
        assertEquals(java.util.Set.of(), Visual.naEdt(t.pagina(Aba.VISAO_GERAL)::topicosProprios));
    }

    @Test
    void consumoSoConsultaComAAbaNaTela() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        assertEquals(0, leituras("/api/tokens/orbita"));
        Visual.naEdt(() -> t.abas().botao("consumo").doClick());
        Visual.naEdt(() -> t.consumo().assinar());
        Espera.ate(Duration.ofSeconds(20), () -> "o consumo chegou", () -> leituras("/api/tokens/orbita") >= 1);
        assertEquals(java.util.Set.of("consumo:orbita"), Visual.naEdt(t.consumo()::topicosProprios));
        Espera.ate(Duration.ofSeconds(20), () -> "os totais na tela", () -> Visual.naEdt(() -> t.consumo().totais().cartoes().get(2).textoDoValor()).equals("US$ 68,20"));
        assertEquals(3, Visual.naEdt(() -> t.consumo().fichaDoHarness().pares().size()));
        int antes = leituras("/api/tokens/orbita");
        Visual.naEdt(() -> t.consumo().soltar());
        assertEquals(java.util.Set.of(), Visual.naEdt(t.consumo()::topicosProprios));
        esperarParar("consumo depois de soltar", antes, "/api/tokens/orbita");
    }

    @Test
    void telaDoClaudeSoComSessaoAbertaEAbaNaTela() {
        cenario.agente.telas.put("orbita", FixturesDeApi.lerRecursoDoProjeto("tela-executando.json"));
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        assertEquals(0, leituras("/claude/tela"), "na Visão geral ninguém lê a tela");
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().assinar());
        telaEmTexto(t);
        Espera.ate(Duration.ofSeconds(20), () -> "a tela chegou", () -> Visual.naEdt(t.terminal()::textoDaTela).contains("Bash(npm test)"));
        assertTrue(Visual.naEdt(t.terminal()::telaAssinada));
        assertEquals("EXECUTANDO", Visual.naEdt(() -> t.terminal().seloDeAtividade().texto()));
        // a aba sai da tela: a assinatura cai
        int antes = leituras("/claude/tela");
        Visual.naEdt(() -> t.terminal().soltar());
        assertFalse(Visual.naEdt(t.terminal()::telaAssinada));
        esperarParar("tela depois de soltar a aba", antes, "/claude/tela");
    }

    @Test
    void sessaoFechadaNaoCustaUmaConsultaDeTela() {
        cenario.agente.detalhes.put("orbita", TelaProjetoTest.detalheEmUmaLinha().replace("\"aberta\": true", "\"aberta\": false"));
        TelaProjeto t = abrirPeloLab("orbita");
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().escolherOClaude()); // com a sessão fechada a aba abre no Shell
        Visual.naEdt(() -> t.terminal().assinar());
        Espera.ate(Duration.ofSeconds(20), () -> "detalhe", () -> Visual.naEdt(() -> t.terminal().seloDaSituacao().texto()).equals("SESSÃO FECHADA"));
        assertFalse(Visual.naEdt(t.terminal()::telaAssinada));
        Espera.naoAcontece(Duration.ofMillis(1200), "tela de sessão fechada", () -> leituras("/claude/tela") > 0);
    }

    @Test
    void sessaoQueFechaNoServidorDeixaDeConsultarATela() {
        cenario.agente.telas.put("orbita", FixturesDeApi.lerRecursoDoProjeto("tela-executando.json"));
        TelaProjeto t = abrirPeloLab("orbita");
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().assinar());
        telaEmTexto(t);
        Espera.ate(Duration.ofSeconds(20), () -> "a tela chegou", () -> Visual.naEdt(t.terminal()::telaAssinada));
        cenario.agente.detalhes.put("orbita", TelaProjetoTest.detalheEmUmaLinha().replace("\"aberta\": true", "\"aberta\": false"));
        cenario.agente.telas.remove("orbita");
        Visual.naEdt(() -> cenario.lab.atualizarAgora());
        Espera.ate(Duration.ofSeconds(20), () -> "a assinatura da tela caiu", () -> !Visual.naEdt(t.terminal()::telaAssinada));
        assertEquals("SESSÃO FECHADA", Visual.naEdt(() -> t.terminal().seloDaSituacao().texto()));
        int antes = leituras("/claude/tela");
        esperarParar("tela depois de fechar", antes, "/claude/tela");
    }

    @Test
    void logsLeUmaVezAoAbrirEAtualizarEAutomaticoSoQuandoMarcado() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("logs").doClick());
        Visual.naEdt(() -> t.logs().assinar());
        String rota = "/api/projetos/orbita/logs?container=orbita-web-1&linhas=300";
        Espera.ate(Duration.ofSeconds(20), () -> "a leitura avulsa dos logs", () -> leituras(rota) == 1);
        Espera.ate(Duration.ofSeconds(20), () -> "o texto na área", () -> Visual.naEdt(t.logs()::textoDaContagem).contains("300 linhas"));
        Espera.naoAcontece(Duration.ofMillis(1200), "repetição sem a caixa de atualizar", () -> leituras(rota) > 1);
        assertFalse(Visual.naEdt(t.logs()::automaticoAssinado));

        Visual.naEdt(() -> t.logs().caixaAutomatica().doClick());
        assertTrue(Visual.naEdt(t.logs()::automaticoAssinado));
        Espera.ate(Duration.ofSeconds(20), () -> "o tópico leu na hora que foi marcado", () -> leituras(rota) >= 2);
        Visual.naEdt(() -> t.logs().caixaAutomatica().doClick());
        assertFalse(Visual.naEdt(t.logs()::automaticoAssinado));
        int antes = leituras(rota);
        esperarParar("logs depois de desmarcar", antes, rota);
    }

    @Test
    void logsComAutomaticoMarcadoParamQuandoAAbaSaiDaTela() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("logs").doClick());
        Visual.naEdt(() -> t.logs().assinar());
        Visual.naEdt(() -> t.logs().caixaAutomatica().doClick());
        Espera.ate(Duration.ofSeconds(20), () -> "assinou", () -> Visual.naEdt(t.logs()::automaticoAssinado));
        Visual.naEdt(() -> t.logs().soltar());
        assertFalse(Visual.naEdt(t.logs()::automaticoAssinado), "aba fora da tela: o tópico cai");
        int antes = leituras("/logs");
        esperarParar("logs com a aba escondida", antes, "/logs");
    }

    @Test
    void trocarOContainerOuAsLinhasLeDeNovoOQueFoiPedido() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("logs").doClick());
        Visual.naEdt(() -> t.logs().assinar());
        Espera.ate(Duration.ofSeconds(20), () -> "primeira leitura", () -> leituras("container=orbita-web-1&linhas=300") == 1);
        Visual.naEdt(() -> t.logs().comboDeContainers().setSelectedItem("orbita-fila-1"));
        Espera.ate(Duration.ofSeconds(20), () -> "outro container", () -> leituras("container=orbita-fila-1&linhas=300") == 1);
        Visual.naEdt(() -> t.logs().comboDeLinhas().setSelectedItem(1000));
        Espera.ate(Duration.ofSeconds(20), () -> "outras linhas", () -> leituras("container=orbita-fila-1&linhas=1000") == 1);
        Espera.ate(Duration.ofSeconds(20), () -> "contagem", () -> Visual.naEdt(t.logs()::textoDaContagem).startsWith("420 linhas"));
    }

    @Test
    void containerSemLogsEContainerQueOAgenteRecusa() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("logs").doClick());
        Visual.naEdt(() -> t.logs().assinar());
        Espera.ate(Duration.ofSeconds(20), () -> "primeira leitura", () -> leituras("container=orbita-web-1&linhas=300") == 1);
        Visual.naEdt(() -> t.logs().comboDeContainers().setSelectedItem("orbita-cache-1"));
        Espera.ate(Duration.ofSeconds(20), () -> "container sem logs", () -> Visual.naEdt(t.logs()::textoDaMensagem)
            .equals("Este container ainda não escreveu nada nos logs."));
        // o agente passa a recusar o container (ele deixou de ser do projeto)
        cenario.agente.logs.remove("orbita/orbita-cache-1");
        Visual.naEdt(() -> t.logs().botaoAtualizar().doClick());
        Espera.ate(Duration.ofSeconds(20), () -> "erro do agente", () -> Visual.naEdt(t.logs()::textoDaMensagem)
            .equals("Não consegui ler os logs: Container não pertence a este projeto."));
    }

    @Test
    void backupsNaoConsultamNadaEOsArquivosVaoPeloSftpENaoPeloAgente() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        int antes = cenario.agente.leiturasDeProjeto.size();
        Visual.naEdt(() -> t.abas().botao(Aba.BACKUPS.id()).doClick());
        Visual.naEdt(() -> t.pagina(Aba.BACKUPS).assinar());
        assertEquals(java.util.Set.of(), Visual.naEdt(t.pagina(Aba.BACKUPS)::topicosProprios));
        assertEquals(5, Visual.naEdt(() -> t.backups().tabela().linhasDaPagina().size()));
        Visual.naEdt(() -> t.abas().botao(Aba.ARQUIVOS.id()).doClick());
        assertNull(t.pagina(Aba.ARQUIVOS), "a aba Arquivos não é uma PaginaDoProjeto: é o gerenciador de arquivos");
        Espera.naoAcontece(Duration.ofMillis(800), "leitura fora o detalhe",
            () -> cenario.agente.leiturasDeProjeto.stream().skip(antes).anyMatch(l -> !l.equals("/api/projetos/orbita")));
    }

    @Test
    void aPaginaEscondidaNaoConsultaNada() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(t::soltar);
        assertFalse(Visual.naEdt(t::assinada));
        int antes = leituras("/api/projetos/orbita");
        Espera.naoAcontece(Duration.ofMillis(1300), "detalhe com a página fora da tela", () -> leituras("/api/projetos/orbita") > antes + 1);
    }

    // ---------------------------------------------------------------------------------------------
    // Ações da página, de ponta a ponta
    // ---------------------------------------------------------------------------------------------

    private javax.swing.AbstractButton botaoComNome(PaginaDoProjeto p, String nomeAcessivel) {
        return Visual.naEdt(() -> (javax.swing.AbstractButton) p.componentes().stream().filter(x -> x instanceof javax.swing.AbstractButton b
            && nomeAcessivel.equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow());
    }

    @Test
    void reiniciarUmContainerChamaARotaDoContainer() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        var b = botaoComNome(t.visaoGeral(), "Reiniciar o container orbita-fila-1");
        Visual.naEdt(() -> b.doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "restart no agente", () -> cenario.agente.containersReiniciados.contains("orbita/orbita-fila-1"));
        assertEquals(java.util.List.of("orbita/orbita-fila-1"), cenario.agente.containersReiniciados, "só o container clicado");
        Espera.ate(Duration.ofSeconds(15), () -> "aviso", () -> avisos.disse("ok", "orbita: orbita-fila-1 reiniciado."));
        assertTrue(cenario.recebeu("POST", "/api/projetos/orbita/containers/orbita-fila-1/reiniciar"));
    }

    @Test
    void reiniciarContainerQueFalhaAvisaOErro() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/reiniciar") ? Resposta.json(500, "{\"erro\":\"Error response from daemon: boom\"}") : null;
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> botaoComNome(t.visaoGeral(), "Reiniciar o container orbita-web-1").doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "aviso de erro", () -> avisos.disse("erro", "Erro em orbita: Error response from daemon: boom"));
    }

    @Test
    void cliqueRepetidoEmReiniciarNaoDisparaDoisRestarts() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/reiniciar") ? Resposta.comAtraso(Resposta.json(200, "{\"ok\":true}"), 500) : null;
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        var b = botaoComNome(t.visaoGeral(), "Reiniciar o container orbita-web-1");
        Visual.naEdt(() -> {
            b.doClick();
            b.doClick();
        });
        Espera.ate(Duration.ofSeconds(15), () -> "restart", () -> avisos.disse("ok", "orbita-web-1 reiniciado."));
        assertEquals(1, cenario.pedidos("POST", "/api/projetos/orbita/containers/orbita-web-1/reiniciar").size());
    }

    @Test
    void enviarMensagemVaiParaOAgenteELimpaOCampoSoQuandoDeuCerto() {
        cenario.agente.telas.put("orbita", FixturesDeApi.lerRecursoDoProjeto("tela-esperando.json"));
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().campoDaMensagem().setText("  olá, rode os testes  "));
        Visual.naEdt(() -> t.terminal().botaoEnviar().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "mensagem no agente", () -> cenario.agente.teclasRecebidas.size() == 1);
        String corpo = cenario.agente.teclasRecebidas.get(0);
        assertTrue(corpo.contains("\"texto\":\"olá, rode os testes\""), corpo);
        assertTrue(corpo.contains("\"enter\":true"), corpo);
        Espera.ate(Duration.ofSeconds(15), () -> "campo limpo", () -> Visual.naEdt(() -> t.terminal().campoDaMensagem().getText()).isEmpty());
    }

    @Test
    void mensagemQueOAgenteRecusaNaoApagaOQueOUsuarioEscreveu() {
        // sem tela no agente: a sessão "não está aberta" para o agente, mesmo com o detalhe dizendo que sim (corrida real)
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().campoDaMensagem().setText("não perca isto"));
        Visual.naEdt(() -> t.terminal().botaoEnviar().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "aviso", () -> avisos.disse("erro", "Erro em orbita: Sessão não está aberta."));
        assertEquals("não perca isto", Visual.naEdt(() -> t.terminal().campoDaMensagem().getText()));
        assertTrue(cenario.agente.teclasRecebidas.isEmpty());
    }

    @Test
    void asTeclasEnterEEscChegamComoTecla() {
        cenario.agente.telas.put("orbita", FixturesDeApi.lerRecursoDoProjeto("tela-pergunta.json"));
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().botaoEnter().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "Enter", () -> cenario.agente.teclasRecebidas.contains("{\"tecla\":\"Enter\"}"));
        Visual.naEdt(() -> t.terminal().botaoEsc().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "Esc", () -> cenario.agente.teclasRecebidas.contains("{\"tecla\":\"Escape\"}"));
        assertEquals(2, cenario.agente.teclasRecebidas.size());
    }

    @Test
    void abrirEFecharASessaoPelaAbaChamamAsRotas() {
        cenario.agente.detalhes.put("orbita", TelaProjetoTest.detalheEmUmaLinha().replace("\"aberta\": true", "\"aberta\": false"));
        TelaProjeto t = abrirPeloLab("orbita");
        Visual.naEdt(() -> t.abas().botao("terminal").doClick());
        Visual.naEdt(() -> t.terminal().escolherOClaude()); // com a sessão fechada a aba abre no Shell
        Espera.ate(Duration.ofSeconds(20), () -> "detalhe", () -> Visual.naEdt(t.terminal().botaoAbrir()::isVisible));
        Visual.naEdt(() -> t.terminal().botaoAbrir().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "abrir", () -> cenario.recebeu("POST", "/api/projetos/orbita/claude/abrir"));
    }

    @Test
    void retomarComOLimiteAindaEstourandoAbreODialogoDo409() {
        cenario.agente.detalhes.put("orbita", br.com.labdesktop.ui.telas.TelaProjetoTest.detalheEmUmaLinha()
            .replaceFirst("\"limite\": \\{.*\\}\\s*\\}\\s*$", java.util.regex.Matcher.quoteReplacement("\"limite\": " + TelaProjetoTest.LIMITE_PAUSADO + " }"))
            .replace("\"aberta\": true", "\"aberta\": false"));
        TelaProjeto t = abrirPeloLab("orbita");
        Visual.naEdt(() -> t.abas().botao("consumo").doClick());
        Espera.ate(Duration.ofSeconds(20), () -> "limite pausado", () -> Visual.naEdt(t.consumo().botaoRetomar()::isVisible));
        Visual.naEdt(() -> t.consumo().botaoRetomar().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "diálogo do 409", () -> dialogos.mostrados.stream()
            .anyMatch(d -> d instanceof br.com.labdesktop.ui.dialogos.DialogoLimiteExcedido));
        assertTrue(cenario.recebeu("POST", "/api/projetos/orbita/limite/retomar"));
    }

    @Test
    void liberarConfirmadoMandaLiberarTrue() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/limite/retomar") && p.corpo().contains("\"liberar\":true")
            ? Resposta.json(200, "{\"ok\":true,\"limite\":null,\"sessao\":{\"aberta\":true,\"container\":\"lab-claude-orbita\",\"url\":\"/sessao/orbita\"}}") : null;
        TelaProjeto t = abrirPeloLab("orbita");
        Visual.naEdt(() -> t.abas().botao("consumo").doClick());
        Espera.ate(Duration.ofSeconds(20), () -> "limite", () -> Visual.naEdt(t.consumo().botaoLiberar()::isVisible));
        Visual.naEdt(() -> t.consumo().botaoLiberar().doClick());
        br.com.labdesktop.ui.dialogos.DialogoConfirmar d = dialogos.ultimoComo(br.com.labdesktop.ui.dialogos.DialogoConfirmar.class);
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "retomar com liberar", () -> cenario.pedidos("POST", "/api/projetos/orbita/limite/retomar").stream()
            .anyMatch(p -> p.corpo().contains("\"liberar\":true")));
        Espera.ate(Duration.ofSeconds(15), () -> "aviso", () -> avisos.disse("ok", "liberada até a semana virar"));
    }

    @Test
    void definirLimitePelaAbaAbreODialogoEEnviaOTeto() {
        TelaProjeto t = abrirPeloLab("orbita");
        Visual.naEdt(() -> t.abas().botao("consumo").doClick());
        Espera.ate(Duration.ofSeconds(20), () -> "limite", () -> Visual.naEdt(t.consumo().botaoDefinirLimite()::isVisible));
        Visual.naEdt(() -> t.consumo().botaoDefinirLimite().doClick());
        assertNotNull(dialogos.ultimoComo(br.com.labdesktop.ui.dialogos.DialogoLimite.class));
    }

    @Test
    void oBotaoPararDaVisaoGeralChamaOCompose() {
        TelaProjeto t = abrirPeloLab("orbita");
        esperarDetalhe(t);
        assertEquals("Parar projeto", Visual.naEdt(() -> t.visaoGeral().botaoCompose().getText()));
        Visual.naEdt(() -> t.visaoGeral().botaoCompose().doClick());
        Espera.ate(Duration.ofSeconds(15), () -> "parar", () -> cenario.recebeu("POST", "/api/projetos/orbita/parar"));
        Espera.ate(Duration.ofSeconds(15), () -> "aviso", () -> avisos.disse("ok", "orbita: parado."));
    }
}
