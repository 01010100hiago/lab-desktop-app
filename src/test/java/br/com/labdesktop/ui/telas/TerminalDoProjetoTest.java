package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.EstadoDoTerminal.Tipo;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.PaginaTerminalDoProjeto.Modo;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A aba Terminal do projeto (ARQUITETURA 7 e 7.8): abre sozinha, o shell do projeto funciona sem Claude, e com Claude há o seletor
 * Shell | Claude (a sessão aberta já vem selecionada). No modo Claude: o botão "Terminal" abre um terminal de verdade ligado à sessão
 * tmux do projeto, "Tela em texto" volta à leitura, "Fechar terminal" solta o canal. SEM a trava {@code labdesktop.acoes}: o terminal
 * é do usuário para operar. Sem o vigia de EDT dos outros testes de tela (o JediTerm pede repintura da thread dele).
 */
class TerminalDoProjetoTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private CenarioDeTerminal cenario;
    private final LimiteDeAbas limite = new LimiteDeAbas();
    private final AvisosGravados avisos = new AvisosGravados();
    private TelaProjeto tela;
    private FluxosDeProjeto fluxos;
    private ContextoDeTerminal ctxDeTerminal;

    @BeforeEach
    void preparar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
        DialogosGravados dialogos = new DialogosGravados();
        // acoes = false: a trava dos botões de escrita continua ligada; o terminal não depende dela
        fluxos = Visual.naEdt(() -> new FluxosDeProjeto(null, avisos, dialogos, false));
        ctxDeTerminal = new ContextoDeTerminal(new TransporteSsh(cenario.conexao), limite, Preferencias::padrao, url -> { },
            Agendador.doFundo());
        tela = criarTela();
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        Visual.renderizar(tela, 1100, 700);
    }

    private TelaProjeto criarTela() {
        return Visual.naEdt(() -> {
            TelaProjeto t = new TelaProjeto(null, "orbita", fluxos, new NavegadorGravado(avisos), avisos, Aba.TERMINAL,
                new TelaProjetoTest.SaidaGravada(), () -> null, () -> { }, new CopiadorDeTexto(() -> new Clipboard("teste")),
                ServicosDeArquivos.descartaveis(), EscolhedorLocal.doSistema(() -> null), ctxDeTerminal);
            t.relogio(() -> TelaProjetoTest.AGORA, TelaProjetoTest.SP);
            return t;
        });
    }

    @AfterEach
    void fechar() {
        Visual.naEdt(tela::encerrar);
        cenario.close();
    }

    private void alimentar(DetalheProjeto d) {
        Visual.naEdt(() -> {
            tela.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.CONECTADO));
            tela.aoReceberDetalhe(d);
        });
    }

    private PaginaTerminalDoProjeto pagina() {
        return tela.terminal();
    }

    private void abrirOTerminal() {
        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        var p = Visual.naEdt(() -> pagina().painelDoTerminal());
        Visual.renderizar(tela, 1100, 700);
        Visual.naEdt(p::iniciar);
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().ativa());
    }

    @Test
    void oBotaoTerminalFuncionaSemATravaDasAcoesEAbreASessaoTmuxDoProjeto() {
        assertFalse(Visual.naEdt(pagina()::loginPendente));
        assertTrue(Visual.naEdt(pagina().botaoTerminal()::isEnabled), "com a sessão aberta e o servidor vivo, mesmo com as ações travadas");
        assertFalse(Visual.naEdt(pagina().botaoFechar()::isVisible), "as ações de escrita continuam atrás da trava");
        assertNull(Visual.naEdt(pagina()::painelDoTerminal));

        abrirOTerminal();
        Espera.ate(PRAZO, "o servidor viu o comando", () -> !cenario.terminal.comandos.isEmpty());
        assertEquals("docker exec -it -e LANG=C.UTF-8 -e TERM=xterm-256color -e COLORTERM=truecolor lab-claude-orbita tmux -u attach -t work",
            cenario.terminal.comandos.get(0));
        assertTrue(Visual.naEdt(pagina()::terminalAFrente));
        assertEquals("Terminal da sessão", Visual.naEdt(pagina()::tituloDaAreaPrincipal));
        assertTrue(Visual.naEdt(pagina().botaoTelaEmTexto()::isVisible));
        assertFalse(Visual.naEdt(pagina().botaoTerminal()::isVisible), "o botão Terminal some enquanto o terminal está na frente");
        assertTrue(Visual.naEdt(pagina().botaoFecharTerminal()::isVisible));
        assertEquals(1, limite.emUso());
    }

    @Test
    void telaEmTextoContinuaComoAlternativaEOTerminalFicaVivoPorTras() {
        abrirOTerminal();
        var terminal = Visual.naEdt(() -> pagina().painelDoTerminal());

        Visual.naEdt(() -> pagina().botaoTelaEmTexto().doClick(0));
        assertFalse(Visual.naEdt(pagina()::terminalAFrente));
        assertEquals("Tela atual da sessão", Visual.naEdt(pagina()::tituloDaAreaPrincipal));
        assertTrue(Visual.naEdt(pagina().botaoTerminal()::isVisible));
        assertEquals("Terminal interativo", Visual.naEdt(() -> pagina().botaoTerminal().getText()));
        assertTrue(Visual.naEdt(pagina().botaoFecharTerminal()::isVisible), "o terminal continua aberto");
        assertTrue(terminal.sessao().estado().ativa());
        assertTrue(Visual.naEdt(() -> pagina().botaoTerminal().getToolTipText()).contains("terminal que já está aberto"));

        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        assertSame(terminal, Visual.naEdt(() -> pagina().painelDoTerminal()), "voltar ao terminal não abre outro");
        assertTrue(Visual.naEdt(pagina()::terminalAFrente));
        Espera.ate(PRAZO, () -> "canais=" + cenario.terminal.canaisAbertos.get(), () -> cenario.terminal.canaisAbertos.get() >= 1); // o servidor conta depois do cliente
        assertEquals(1, cenario.terminal.canaisAbertos.get(), "um canal só");
        assertEquals(1, limite.emUso());
    }

    @Test
    void fecharOTerminalSoltaOCanalDevolveAVagaEVoltaATelaEmTexto() {
        abrirOTerminal();
        Visual.naEdt(() -> pagina().botaoFecharTerminal().doClick(0));
        assertNull(Visual.naEdt(() -> pagina().painelDoTerminal()));
        assertFalse(Visual.naEdt(pagina()::terminalAFrente));
        assertEquals("Tela atual da sessão", Visual.naEdt(pagina()::tituloDaAreaPrincipal));
        assertEquals(0, limite.emUso());
        Espera.ate(PRAZO, () -> "encerrados=" + cenario.terminal.canaisEncerrados.get(), () -> cenario.terminal.canaisEncerrados.get() == 1);
        assertFalse(Visual.naEdt(pagina().botaoFecharTerminal()::isVisible));
        assertTrue(Visual.naEdt(pagina().botaoTerminal()::isVisible));
    }

    @Test
    void sairDaPaginaDoProjetoFechaOTerminalMasTrocarDeAbaNao() {
        abrirOTerminal();
        Visual.naEdt(() -> tela.mostrarAba(Aba.LOGS));
        Visual.naEdt(() -> tela.mostrarAba(Aba.TERMINAL));
        assertNotNull(Visual.naEdt(() -> pagina().painelDoTerminal()), "trocar de aba dentro do projeto não derruba o terminal");
        assertTrue(Visual.naEdt(() -> pagina().painelDoTerminal()).sessao().estado().ativa());
        assertEquals(1, limite.emUso());

        Visual.naEdt(tela::fecharTerminais); // o que a TelaServidor faz ao sair da página do projeto
        assertNull(Visual.naEdt(() -> pagina().painelDoTerminal()));
        assertEquals(0, limite.emUso());
        Espera.ate(PRAZO, () -> "encerrados=" + cenario.terminal.canaisEncerrados.get(), () -> cenario.terminal.canaisEncerrados.get() == 1);
    }

    @Test
    void semSessaoAbertaOBotaoFicaDesabilitadoComOMotivo() {
        alimentar(TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false"));
        assertFalse(Visual.naEdt(pagina().botaoTerminal()::isEnabled));
        assertEquals("Abra a sessão do Claude para usar o terminal.", Visual.naEdt(() -> pagina().botaoTerminal().getToolTipText()));
    }

    @Test
    void limiteDeOitoTerminaisNoServidorRecusaOTerminalDaSessaoComMensagemClara() {
        for (int i = 0; i < 8; i++) {
            assertTrue(limite.reservar());
        }
        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        assertNull(Visual.naEdt(() -> pagina().painelDoTerminal()));
        assertTrue(avisos.disse("atencao", "8 terminais"), "diz com todas as letras que o servidor não comporta mais");
        assertEquals(8, limite.emUso());
        assertEquals(0, cenario.terminal.canaisAbertos.get());
    }

    @Test
    void sessaoQueSumiuEVoltaReanexaSoComUmaSessaoNova() {
        cenario.terminal.roteiro(s -> {
            s.escrever("can't find session: work\r\n");
            s.sair(1);
        });
        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        var p = Visual.naEdt(() -> pagina().painelDoTerminal());
        Visual.renderizar(tela, 1100, 700);
        Visual.naEdt(p::iniciar);
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().tipo() == Tipo.SESSAO_FECHADA);
        assertEquals(1, cenario.terminal.canaisAbertos.get());

        // o detalhe continua dizendo "aberta", com o MESMO desde: o tmux dela pode simplesmente não existir. Nada de laço.
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        Espera.naoAcontece(Duration.ofMillis(400), "reanexou uma sessão que já constava aberta", () -> cenario.terminal.canaisAbertos.get() > 1);

        // a sessão fecha e abre de novo (Reabrir, Retomar: container novo, outro "desde"): reanexa sozinho
        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
        alimentar(TelaProjetoTest.detalheCom("\"desde\": \"2026-10-24T17:10:00.000Z\"", "\"desde\": \"2026-10-24T20:45:00.000Z\""));
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().ativa());
        Espera.ate(PRAZO, () -> "canais=" + cenario.terminal.canaisAbertos.get(), () -> cenario.terminal.canaisAbertos.get() == 2);
    }

    @Test
    void sessaoFechadaEDepoisAbertaDeNovoTambemReanexa() {
        cenario.terminal.roteiro(s -> {
            s.escrever("Error response from daemon: No such container: lab-claude-orbita\r\n");
            s.sair(1);
        });
        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        var p = Visual.naEdt(() -> pagina().painelDoTerminal());
        Visual.renderizar(tela, 1100, 700);
        Visual.naEdt(p::iniciar);
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().tipo() == Tipo.SESSAO_FECHADA);

        alimentar(TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false")); // o servidor viu a sessão fechar
        assertEquals(Tipo.SESSAO_FECHADA, p.sessao().estado().tipo());
        assertEquals(1, cenario.terminal.canaisAbertos.get());

        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
        alimentar(FixturesDeApi.detalheDoProjetoSintetico()); // e abrir de novo
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().ativa());
        Espera.ate(PRAZO, () -> "canais=" + cenario.terminal.canaisAbertos.get(), () -> cenario.terminal.canaisAbertos.get() == 2);
    }

    // ---------------------------------------------------------------------------------------------
    // A aba abre sozinha; o Claude é opcional
    // ---------------------------------------------------------------------------------------------

    /** O detalhe sintético sem o objeto {@code sessao}: um servidor sem Claude. */
    private static DetalheProjeto detalheSemClaude() {
        String json = TelaProjetoTest.detalheEmUmaLinha().replaceAll("\"sessao\": \\{.*?\"info\": (\\{[^}]*\\}|null) \\},", "\"sessao\": null,");
        assertFalse(json.contains("lab-claude-orbita"), "a fixture ainda tem a sessão");
        return FixturesDeApi.de(json, DetalheProjeto.class);
    }

    private void mostrarAAba() {
        Visual.naEdt(() -> pagina().assinar()); // a aba foi para a frente: é o que a faz abrir o terminal
        Visual.renderizar(tela, 1100, 700);
    }

    /** Sem janela de verdade o painel não "aparece" sozinho: faz o que o primeiro layout faria. */
    private void iniciar(java.util.function.Supplier<br.com.labdesktop.ui.terminal.PainelTerminal> qual) {
        var p = Visual.naEdt(qual::get);
        assertNotNull(p, "o terminal devia ter sido aberto sozinho");
        Visual.renderizar(tela, 1100, 700);
        Visual.naEdt(p::iniciar);
    }

    private static final String LINHA_DO_SHELL = "cd '/srv/lab/projects/orbita' 2>/dev/null || { echo 'A pasta do projeto não foi encontrada; "
        + "abrindo na pasta pessoal.'; cd; }; exec \"${SHELL:-/bin/sh}\" -l";

    @Test
    void semClaudeAAbaAbreUmShellNaPastaDoProjetoSozinhaESemNadaDoClaude() {
        alimentar(detalheSemClaude());
        mostrarAAba();
        iniciar(pagina()::painelDoShell);
        Espera.ate(PRAZO, "o servidor viu o shell do projeto", () -> cenario.terminal.comandos.size() == 1);
        assertEquals(LINHA_DO_SHELL, cenario.terminal.comandos.get(0));
        var shell = Visual.naEdt(() -> pagina().painelDoShell());
        assertNotNull(shell);
        Visual.naEdt(shell::iniciar);
        Espera.ate(PRAZO, () -> "estado=" + shell.sessao().estado(), () -> shell.sessao().estado().ativa());
        assertEquals(Modo.SHELL, Visual.naEdt(pagina()::modo));
        assertFalse(Visual.naEdt(pagina().seletor()::isVisible), "sem Claude não há seletor");
        assertNull(Visual.naEdt(pagina()::painelDoTerminal));
        assertEquals(1, limite.emUso());
        Visual.naEdt(pagina()::redesenhar);
        String visivel = Visual.naEdt(() -> textosVisiveis(pagina()));
        assertFalse(visivel.toLowerCase().contains("claude"), "nada do Claude na tela: " + visivel);
        for (var b : java.util.List.of(pagina().botaoAbrir(), pagina().botaoFechar(), pagina().botaoReabrir(), pagina().botaoRetomar(),
            pagina().botaoTerminal(), pagina().botaoTelaEmTexto(), pagina().botaoEnviar(), pagina().botaoEnter(), pagina().botaoEsc())) {
            assertFalse(Visual.naEdt(() -> pagina().aparece(b)), b.getText() + " do Claude não pode aparecer");
        }
        assertFalse(Visual.naEdt(() -> pagina().aparece(pagina().campoDaMensagem())));
        // o Claude não existe nem se alguém pedir (o botão da Visão geral)
        Visual.naEdt(pagina()::escolherOClaude);
        assertEquals(Modo.SHELL, Visual.naEdt(pagina()::modo));
    }

    @Test
    void comASessaoAbertaOClaudeVemSelecionadoEOTerminalDeleAbreSemClique() {
        mostrarAAba();
        iniciar(pagina()::painelDoTerminal);
        Espera.ate(PRAZO, "o servidor viu o comando", () -> !cenario.terminal.comandos.isEmpty());
        assertTrue(cenario.terminal.comandos.get(0).contains("lab-claude-orbita tmux -u attach -t work"), cenario.terminal.comandos.get(0));
        assertEquals(Modo.CLAUDE, Visual.naEdt(pagina()::modo));
        assertTrue(Visual.naEdt(pagina().seletor()::isVisible));
        assertEquals("claude", Visual.naEdt(pagina().seletor()::selecionada));
        assertTrue(Visual.naEdt(pagina()::terminalAFrente));
        assertNotNull(Visual.naEdt(pagina()::painelDoTerminal));
        assertNull(Visual.naEdt(pagina()::painelDoShell), "o shell só abre quando o usuário o escolhe");
        assertEquals(1, limite.emUso());
    }

    @Test
    void semSessaoAbertaOShellVemSelecionadoEOClaudeEUmBotao() {
        alimentar(TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false"));
        mostrarAAba();
        iniciar(pagina()::painelDoShell);
        Espera.ate(PRAZO, "o servidor viu o shell", () -> cenario.terminal.comandos.size() == 1);
        assertEquals(LINHA_DO_SHELL, cenario.terminal.comandos.get(0));
        assertEquals(Modo.SHELL, Visual.naEdt(pagina()::modo));
        assertEquals("shell", Visual.naEdt(pagina().seletor()::selecionada));
        assertTrue(Visual.naEdt(pagina().seletor()::isVisible));
        assertFalse(Visual.naEdt(() -> pagina().aparece(pagina().seloDaSituacao())), "os controles do Claude esperam o usuário escolher o Claude");

        Visual.naEdt(() -> pagina().seletor().botao("claude").doClick());
        assertEquals(Modo.CLAUDE, Visual.naEdt(pagina()::modo));
        assertTrue(Visual.naEdt(() -> pagina().aparece(pagina().seloDaSituacao())), "agora o estado da sessão aparece");
        assertEquals("SESSÃO FECHADA", Visual.naEdt(pagina().seloDaSituacao()::texto));
        assertNull(Visual.naEdt(pagina()::painelDoTerminal), "sem sessão aberta não há terminal do Claude");
        assertNotNull(Visual.naEdt(pagina()::painelDoShell), "o shell continua vivo por trás");
        Espera.ate(PRAZO, "o canal do shell", () -> cenario.terminal.canaisAbertos.get() == 1);
        assertEquals(1, cenario.terminal.canaisAbertos.get());

        Visual.naEdt(() -> pagina().seletor().botao("shell").doClick());
        assertEquals(Modo.SHELL, Visual.naEdt(pagina()::modo));
        assertFalse(Visual.naEdt(() -> pagina().aparece(pagina().seloDaSituacao())));
        assertEquals(1, cenario.terminal.canaisAbertos.get(), "voltar ao shell não abre outro");
    }

    @Test
    void aAbaNoShellNosDoisTemasSaiNosPngsDeTela() {
        cenario.terminal.roteiro(s -> {
            s.escrever("labuser@lab:/srv/lab/projects/orbita$ ls\r\n\u001b[1;34mdocker-compose.yml\u001b[0m  README.md  \u001b[32msrc\u001b[0m\r\n");
            s.escrever("labuser@lab:/srv/lab/projects/orbita$ ");
            ServidorDeTerminalFalso.roteiroQueNaoLe(s);
        });
        alimentar(TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false")); // sessão fechada: abre no Shell, com o seletor
        mostrarAAba();
        iniciar(pagina()::painelDoShell);
        var shell = Visual.naEdt(() -> pagina().painelDoShell());
        Espera.ate(PRAZO, () -> "estado=" + shell.sessao().estado(), () -> shell.sessao().estado().ativa() && Visual.naEdt(() -> shell.widget() != null));
        Espera.ate(PRAZO, "o prompt chegou", () -> Visual.naEdt(() -> shell.widget().getTerminalTextBuffer().getScreenLines()).contains("README.md"));
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(tela));
        Visual.salvar(Visual.renderizar(tela, 1100, 700), "projeto-terminal-shell-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(tela));
        Visual.salvar(Visual.renderizar(tela, 1100, 700), "projeto-terminal-shell-escuro");
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(tela));
    }

    @Test
    void sessaoQueAbreComOClaudeSelecionadoAbreOTerminalSoEFechadoPeloDonoNaoVoltaSozinho() {
        alimentar(TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false"));
        mostrarAAba();
        Visual.naEdt(() -> pagina().seletor().botao("claude").doClick());
        assertNull(Visual.naEdt(pagina()::painelDoTerminal));
        alimentar(FixturesDeApi.detalheDoProjetoSintetico()); // o usuário abriu a sessão
        assertNotNull(Visual.naEdt(pagina()::painelDoTerminal), "a sessão abriu com o Claude à vista: o terminal abre sozinho");
        assertTrue(Visual.naEdt(pagina()::terminalAFrente));

        Visual.naEdt(() -> pagina().botaoFecharTerminal().doClick(0));
        assertNull(Visual.naEdt(pagina()::painelDoTerminal));
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        assertNull(Visual.naEdt(pagina()::painelDoTerminal), "fechado de propósito: as leituras seguintes não o reabrem");
        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        assertNotNull(Visual.naEdt(pagina()::painelDoTerminal), "o botão Terminal abre quando o usuário quiser");
    }

    @Test
    void semAAbaNaTelaNadaAbreEAoSairDaPaginaOsDoisTerminaisFecham() {
        Visual.renderizar(tela, 1100, 700);
        assertNull(Visual.naEdt(pagina()::painelDoTerminal), "aba que ninguém viu não abre canal");
        assertNull(Visual.naEdt(pagina()::painelDoShell));
        assertEquals(0, limite.emUso());

        mostrarAAba();
        Visual.naEdt(() -> pagina().seletor().botao("shell").doClick());
        assertNotNull(Visual.naEdt(pagina()::painelDoShell));
        assertNotNull(Visual.naEdt(pagina()::painelDoTerminal));
        assertEquals(2, limite.emUso());
        iniciar(pagina()::painelDoShell);
        iniciar(pagina()::painelDoTerminal);
        Espera.ate(PRAZO, () -> "canais=" + cenario.terminal.canaisAbertos.get(), () -> cenario.terminal.canaisAbertos.get() == 2);

        Visual.naEdt(tela::fecharTerminais);
        assertNull(Visual.naEdt(pagina()::painelDoShell));
        assertNull(Visual.naEdt(pagina()::painelDoTerminal));
        assertEquals(0, limite.emUso());
        Espera.ate(PRAZO, () -> "encerrados=" + cenario.terminal.canaisEncerrados.get(), () -> cenario.terminal.canaisEncerrados.get() == 2);
    }

    @Test
    void shellComLimiteEstouradoMostraAMensagemSemAbrirCanal() {
        for (int i = 0; i < 8; i++) {
            assertTrue(limite.reservar());
        }
        alimentar(detalheSemClaude());
        mostrarAAba();
        assertNull(Visual.naEdt(pagina()::painelDoShell));
        assertTrue(avisos.disse("atencao", "8 terminais"));
        assertEquals(0, cenario.terminal.canaisAbertos.get());
    }

    @Test
    void oShellAbreMesmoSemODetalheDoProjetoComOAgenteForaDoAr() {
        Visual.naEdt(tela::encerrar);
        tela = criarTela(); // nenhum detalhe chegou: o HTTP do agente não responde
        Visual.naEdt(() -> tela.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.SSH_OK_SEM_AGENTE)));
        Visual.renderizar(tela, 1100, 700);
        assertFalse(Visual.naEdt(() -> pagina().mostraEsqueleto()), "sem esqueleto esperando o agente");
        assertFalse(Visual.naEdt(() -> pagina().semDados().isVisible()), "sem cartão de 'sem dados'");
        assertTrue(Visual.naEdt(() -> pagina().mostraCorpo()));
        assertFalse(Visual.naEdt(() -> pagina().corpoEsmaecido()), "o terminal não é dado antigo");
        mostrarAAba();
        iniciar(pagina()::painelDoShell);
        Espera.ate(PRAZO, "o servidor viu o shell", () -> cenario.terminal.comandos.size() == 1);
        assertEquals(Modo.SHELL, Visual.naEdt(pagina()::modo));
        assertFalse(Visual.naEdt(pagina().seletor()::isVisible), "sem saber se há Claude, só o shell");
        assertEquals(1, limite.emUso());
    }

    @Test
    void oAvisoDoLimiteDeTerminaisSaiUmaVezSoEOResteFicaNoCartao() {
        for (int i = 0; i < 8; i++) {
            assertTrue(limite.reservar());
        }
        alimentar(detalheSemClaude());
        mostrarAAba();
        alimentar(detalheSemClaude());
        alimentar(detalheSemClaude());
        Visual.naEdt(() -> pagina().redesenhar());
        long avisosDoLimite = avisos.todos.stream().filter(a -> a.texto().contains("8 terminais")).count();
        assertEquals(1, avisosDoLimite, "uma vez só, não a cada leitura do detalhe");
        assertTrue(Visual.naEdt(pagina()::textoDoShellSemTerminal).contains("8 terminais"), "o cartão continua dizendo o motivo");
        limite.liberar();
        alimentar(detalheSemClaude());
        assertNotNull(Visual.naEdt(pagina()::painelDoShell), "voltou a ter vaga: abre");
    }

    @Test
    void oAvisoDoLimiteNoCaminhoDoClaudeTambemSaiUmaVezEPeloCliqueSempre() {
        for (int i = 0; i < 8; i++) {
            assertTrue(limite.reservar());
        }
        mostrarAAba(); // sessão aberta: o Claude vem selecionado e tenta abrir o terminal dele
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        assertEquals(1, avisos.todos.stream().filter(a -> a.texto().contains("8 terminais")).count());
        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        assertEquals(2, avisos.todos.stream().filter(a -> a.texto().contains("8 terminais")).count(), "o clique do usuário sempre tem resposta");
    }

    @Test
    void servidorSemClaudeNaoFalaDoClaudeNemNosBackupsNemNosDetalhesTecnicos() {
        String json = TelaProjetoTest.detalheEmUmaLinha().replaceAll("\"sessao\": \\{.*?\"info\": (\\{[^}]*\\}|null) \\},", "\"sessao\": null,")
            .replace("\"backups\": [", "\"backups\": [], \"backups_antigos\": [").replace("\"acessoDocker\": true", "\"acessoDocker\": false");
        alimentar(FixturesDeApi.de(json, DetalheProjeto.class));
        Visual.naEdt(() -> tela.mostrarAba(Aba.BACKUPS));
        Visual.renderizar(tela, 1100, 700);
        String backups = Visual.naEdt(() -> textosVisiveis(tela.backups()));
        assertTrue(backups.contains("Nenhum backup ainda."), backups);
        assertFalse(backups.contains("Claude"), backups);
        Visual.naEdt(() -> tela.visaoGeral().secaoTecnica().definirAberta(true));
        String geral = Visual.naEdt(() -> textosVisiveis(tela.visaoGeral()));
        assertFalse(geral.contains("Claude"), geral);
    }

    @Test
    void aLegendaDoShellDizOQueRealmenteAbriu() {
        alimentar(detalheSemClaude());
        mostrarAAba();
        assertEquals("Shell no servidor, na pasta do projeto", Visual.naEdt(pagina()::legendaDoShell));
        Visual.naEdt(tela::fecharTerminais);

        // a pasta não passa na regra do terminal (acento): cai no shell do servidor e a legenda não mente
        Visual.naEdt(() -> pagina().ctx.pastaDoProjeto = () -> "/srv/lab/projetos/órbita");
        alimentar(detalheSemClaude());
        Visual.naEdt(() -> pagina().soltar());
        mostrarAAba();
        assertTrue(Visual.naEdt(pagina()::legendaDoShell).contains("pasta pessoal"), Visual.naEdt(pagina()::legendaDoShell));
        assertFalse(Visual.naEdt(pagina()::legendaDoShell).contains("na pasta do projeto"));
    }

    @Test
    void voltarParaAAbaOuTrocarOSeletorDevolveOFocoAoTerminalQueJaExiste() {
        alimentar(TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false"));
        mostrarAAba();
        iniciar(pagina()::painelDoShell);
        Espera.ate(PRAZO, "o foco do shell novo", () -> Visual.naEdt(pagina()::pedidosDeFoco) >= 1);
        int antes = Visual.naEdt(pagina()::pedidosDeFoco);

        Visual.naEdt(() -> pagina().soltar()); // saiu da aba (o terminal continua vivo por trás)
        Visual.naEdt(() -> pagina().assinar()); // e voltou
        Espera.ate(PRAZO, "o foco ao voltar", () -> Visual.naEdt(pagina()::pedidosDeFoco) > antes);

        int meio = Visual.naEdt(pagina()::pedidosDeFoco);
        Visual.naEdt(() -> pagina().seletor().botao("claude").doClick());
        Visual.naEdt(() -> pagina().seletor().botao("shell").doClick());
        Espera.ate(PRAZO, "o foco ao voltar para o Shell no seletor", () -> Visual.naEdt(pagina()::pedidosDeFoco) > meio);
    }

    private static String textosVisiveis(java.awt.Component raiz) {
        StringBuilder sb = new StringBuilder();
        coletarTextos(raiz, sb);
        return sb.toString();
    }

    private static void coletarTextos(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof javax.swing.AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n').append(b.getToolTipText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo s) {
            sb.append(s.texto()).append('\n');
        }
        if (c instanceof java.awt.Container k && !(c instanceof br.com.labdesktop.ui.terminal.PainelTerminal)) {
            for (java.awt.Component f : k.getComponents()) {
                coletarTextos(f, sb);
            }
        }
    }
}
