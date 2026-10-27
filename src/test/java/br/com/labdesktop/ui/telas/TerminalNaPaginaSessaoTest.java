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
 * O terminal dentro da aba Sessão do Claude (ARQUITETURA 7): o botão "Terminal" abre um terminal de verdade ligado à sessão tmux do
 * projeto, "Tela em texto" volta à leitura, "Fechar terminal" solta o canal. SEM a trava {@code labdesktop.acoes}: o terminal é do
 * proprietário para operar. Sem o vigia de EDT dos outros testes de tela (o JediTerm pede repintura da thread dele).
 */
class TerminalNaPaginaSessaoTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private CenarioDeTerminal cenario;
    private final LimiteDeAbas limite = new LimiteDeAbas();
    private final AvisosGravados avisos = new AvisosGravados();
    private TelaProjeto tela;

    @BeforeEach
    void preparar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
        DialogosGravados dialogos = new DialogosGravados();
        // acoes = false: a trava dos botões de escrita continua ligada; o terminal não depende dela
        FluxosDeProjeto fluxos = Visual.naEdt(() -> new FluxosDeProjeto(null, avisos, dialogos, false));
        ContextoDeTerminal ctx = new ContextoDeTerminal(new TransporteSsh(cenario.conexao), limite, Preferencias::padrao, url -> { },
            Agendador.doFundo());
        tela = Visual.naEdt(() -> {
            TelaProjeto t = new TelaProjeto(null, "orbita", fluxos, new NavegadorGravado(avisos), avisos, Aba.SESSAO,
                new TelaProjetoTest.SaidaGravada(), () -> null, () -> { }, new CopiadorDeTexto(() -> new Clipboard("teste")),
                ServicosDeArquivos.descartaveis(), EscolhedorLocal.doSistema(() -> null), ctx);
            t.relogio(() -> TelaProjetoTest.AGORA, TelaProjetoTest.SP);
            return t;
        });
        alimentar(FixturesDeApi.detalheDoProjetoSintetico());
        Visual.renderizar(tela, 1100, 700);
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

    private PaginaSessao pagina() {
        return tela.sessao();
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
        assertEquals("docker exec -it -e LANG=C.UTF-8 -e TERM=xterm-256color -e COLORTERM=truecolor lab-claude-orbita tmux -u set-option -t work mouse on ';' attach -t work",
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
        assertEquals("Terminal", Visual.naEdt(() -> pagina().botaoTerminal().getText()));
        assertTrue(Visual.naEdt(pagina().botaoFecharTerminal()::isVisible), "o terminal continua aberto");
        assertTrue(terminal.sessao().estado().ativa());
        assertTrue(Visual.naEdt(() -> pagina().botaoTerminal().getToolTipText()).contains("terminal que já está aberto"));

        Visual.naEdt(() -> pagina().botaoTerminal().doClick(0));
        assertSame(terminal, Visual.naEdt(() -> pagina().painelDoTerminal()), "voltar ao terminal não abre outro");
        assertTrue(Visual.naEdt(pagina()::terminalAFrente));
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
        Visual.naEdt(() -> tela.mostrarAba(Aba.SESSAO));
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
}
