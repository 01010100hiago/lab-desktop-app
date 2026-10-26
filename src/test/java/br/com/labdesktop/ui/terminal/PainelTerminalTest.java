package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.EstadoDoTerminal;
import br.com.labdesktop.terminal.EstadoDoTerminal.Tipo;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.ui.JediTermWidget;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.swing.JComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * O {@link PainelTerminal} com o JediTerm de verdade, sem janela, contra um servidor SSH embutido (e, para a queda de rede, atrás de um
 * proxy TCP que sabe fazer buraco negro). Não usa o vigia de EDT dos outros testes de tela: o emulador do JediTerm pede repintura da
 * thread dele, o que é dele e não do app.
 */
class PainelTerminalTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private CenarioDeTerminal cenario;
    private PainelTerminal painel;
    private final List<String> titulos = new CopyOnWriteArrayList<>();
    private final List<String> links = new CopyOnWriteArrayList<>();
    private final List<EstadoDoTerminal> estados = new CopyOnWriteArrayList<>();

    @AfterEach
    void fechar() {
        if (painel != null) {
            Visual.naEdt(painel::descartar);
        }
        if (cenario != null) {
            cenario.close();
        }
    }

    private PainelTerminal novoPainel(ComandoDeTerminal comando, Supplier<Preferencias> prefs) throws Exception {
        Visual.tema(Tema.CLARO);
        ContextoDeTerminal ctx = new ContextoDeTerminal(new TransporteSsh(cenario.conexao), new LimiteDeAbas(), prefs, links::add,
            Agendador.doFundo());
        painel = Visual.naEdt(() -> new PainelTerminal(ctx, comando, new PainelTerminal.Saida() {
            @Override
            public void aoMudarTitulo(String titulo) {
                titulos.add(titulo);
            }

            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) {
                estados.add(estado);
            }
        }));
        return painel;
    }

    private PainelTerminal doClaude() throws Exception {
        return novoPainel(new ComandoDeTerminal.SessaoDoClaude("orbita"), Preferencias::padrao);
    }

    private static void abrir(PainelTerminal p, int largura, int altura) {
        Visual.renderizar(p, largura, altura);
        Visual.naEdt(p::iniciar);
        esperarAtiva(p);
    }

    /** Abre sem esperar ATIVA: para o servidor que fecha o canal assim que ele abre. */
    private static void abrirSemEsperar(PainelTerminal p, int largura, int altura) {
        Visual.renderizar(p, largura, altura);
        Visual.naEdt(p::iniciar);
    }

    private static void esperarAtiva(PainelTerminal p) {
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().ativa() && Visual.naEdt(() -> p.widget() != null));
    }

    private static String tela(PainelTerminal p) {
        return Visual.naEdt(() -> {
            JediTermWidget w = p.widget();
            return w == null ? "" : w.getTerminalTextBuffer().getScreenLines();
        });
    }

    private static void esperarTela(PainelTerminal p, String trecho) {
        Espera.ate(PRAZO, () -> "tela=" + tela(p), () -> tela(p).contains(trecho));
    }

    @Test
    void abreMedeOTamanhoDepoisDoLayoutEMostraOTexto() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(s -> {
            s.escrever("Olá, ação ✓\r\n");
            ServidorDeTerminalFalso.roteiroQueNaoLe(s);
        });
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        esperarTela(p, "Olá, ação ✓");
        // o servidor recebeu o tamanho MEDIDO no componente, não o padrão de 120 por 32
        String inicial = cenario.terminal.tamanhosIniciais.get(0);
        assertEquals(p.tamanhoAtual().colunas() + "x" + p.tamanhoAtual().linhas(), inicial);
        assertTrue(p.tamanhoAtual().colunas() > 100 && p.tamanhoAtual().colunas() != 120, "medido: " + p.tamanhoAtual());
        assertEquals(1, cenario.terminal.canaisAbertos.get(), "um canal só: não abriu com um tamanho e reabriu com outro");
        assertFalse(p.faixaVisivel(), "terminal ativo: nenhuma faixa");
    }

    @Test
    void semMedidaUsa120Por32() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        PainelTerminal p = doClaude();
        Visual.naEdt(p::iniciar); // sem tamanho nenhum: o painel nunca foi dimensionado
        esperarAtiva(p);
        assertEquals("120x32", cenario.terminal.tamanhosIniciais.get(0));
    }

    @Test
    void redimensionarAJanelaChegaAoServidorComoWindowChange() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        Visual.renderizar(p, 700, 420);
        Espera.ate(PRAZO, () -> "janelas=" + cenario.terminal.janelas,
            () -> cenario.terminal.janelas.stream().anyMatch(j -> Integer.parseInt(j.split("x")[0]) < 100));
        String encolhida = cenario.terminal.janelas.stream().filter(j -> Integer.parseInt(j.split("x")[0]) < 100).findFirst().orElseThrow();
        int colunas = Integer.parseInt(encolhida.split("x")[0]);
        assertTrue(colunas > 60, "o terminal encolheu com a janela, e não demais: " + encolhida);
        Espera.ate(PRAZO, () -> "tamanho conhecido=" + p.tamanhoAtual(), () -> p.tamanhoAtual().colunas() < 100);
    }

    @Test
    void teclasChegamEPortaoNotaQueODonoDigitou() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        Visual.naEdt(() -> {
            try {
                p.widget().getTtyConnector().write("ação\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Espera.ate(PRAZO, "teclado chegou", () -> cenario.terminal.recebidoComoTexto().equals("ação\n"));

        assertFalse(p.portao().donoDigitandoAgora());
        Visual.naEdt(() -> {
            var tp = p.widget().getTerminalPanel();
            tp.processKeyEvent(new KeyEvent(tp, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'x'));
        });
        assertTrue(p.portao().donoDigitandoAgora(), "o teclado do usuário é notado pelo portão de entrada");
    }

    @Test
    void quedaDeRedeDescartaTeclasReanexaSozinhoComWidgetNovoENadaAparecaDepois() throws Exception {
        cenario = CenarioDeTerminal.conectadoComProxy();
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        JediTermWidget primeiro = Visual.naEdt(p::widget);
        Visual.naEdt(() -> {
            try {
                primeiro.getTtyConnector().write("antes\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Espera.ate(PRAZO, "antes chegou", () -> cenario.terminal.recebidoComoTexto().equals("antes\n"));

        // a rede cai: a conexão vira buraco negro e a volta fica recusada; o heartbeat percebe e derruba a sessão
        cenario.proxy.abrirBuracoNegro();
        cenario.proxy.recusarNovas(true);
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().tipo() == Tipo.REANEXANDO);
        Espera.ate(PRAZO, "faixa de reconexão", () -> Visual.naEdt(p::faixaVisivel));
        assertTrue(Visual.naEdt(p::textoDaFaixaAgora).startsWith("Conexão perdida. Reconectando"), Visual.naEdt(p::textoDaFaixaAgora));
        assertSame(primeiro, Visual.naEdt(p::widget), "durante a queda o widget antigo continua à vista (dá para ler e copiar)");

        // teclas na queda: descartadas, com aviso; nada vai ao servidor
        for (String tecla : new String[] {"perdida1", "1\n", "\r"}) {
            Visual.naEdt(() -> assertThrows(IOException.class, () -> primeiro.getTtyConnector().write(tecla)));
        }
        Espera.ate(PRAZO, "aviso de teclas descartadas", () -> Visual.naEdt(p::avisoDeTeclasVisivel));
        assertEquals("antes\n", cenario.terminal.recebidoComoTexto());

        // a rede volta: o terminal reanexa sozinho, com outro widget
        cenario.proxy.recusarNovas(false);
        esperarAtiva(p);
        Espera.ate(PRAZO, "widget novo", () -> Visual.naEdt(() -> p.widget() != primeiro));
        assertNotSame(primeiro, Visual.naEdt(p::widget), "cada anexação cria um JediTermWidget novo");
        assertFalse(Visual.naEdt(p::faixaVisivel));
        assertFalse(Visual.naEdt(p::avisoDeTeclasVisivel));
        assertEquals(2, cenario.terminal.comandos.size(), "reanexou ao mesmo comando");
        assertEquals(cenario.terminal.comandos.get(0), cenario.terminal.comandos.get(1));

        // o que se digitou durante a queda NUNCA aparece, nem depois de reconectar
        Visual.naEdt(() -> {
            try {
                p.widget().getTtyConnector().write("depois\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Espera.ate(PRAZO, () -> "recebido=" + cenario.terminal.recebidoComoTexto(), () -> cenario.terminal.recebidoComoTexto().contains("depois"));
        Espera.naoAcontece(Duration.ofMillis(300), "algo a mais", () -> cenario.terminal.recebidoComoTexto().length() > "antes\ndepois\n".length());
        assertEquals("antes\ndepois\n", cenario.terminal.recebidoComoTexto());
    }

    @Test
    void sessaoQueNaoExisteMostraAFaixaSemLacoEOReconectarTentaDeNovo() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(ServidorDeTerminalFalso.imprimirESair("Error response from daemon: No such container: lab-claude-orbita\r\n", 1));
        PainelTerminal p = doClaude();
        abrirSemEsperar(p, 1000, 600);
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().tipo() == Tipo.SESSAO_FECHADA);
        Espera.ate(PRAZO, "faixa", () -> Visual.naEdt(p::faixaVisivel));
        assertTrue(Visual.naEdt(p::textoDaFaixaAgora).contains("não está aberta"), Visual.naEdt(p::textoDaFaixaAgora));
        assertTrue(Visual.naEdt(() -> p.botaoReconectar().isVisible()));
        Espera.naoAcontece(Duration.ofMillis(2600), "reanexou sozinho uma sessão que não existe", () -> cenario.terminal.canaisAbertos.get() > 1);

        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
        Visual.naEdt(() -> p.botaoReconectar().doClick(0));
        esperarAtiva(p);
        assertEquals(2, cenario.terminal.canaisAbertos.get());
        assertFalse(Visual.naEdt(p::faixaVisivel));
    }

    @Test
    void shellQueSaiMostraOCodigoEOferecePAbrirDeNovo() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(ServidorDeTerminalFalso.imprimirESair("logout\r\n", 5));
        PainelTerminal p = novoPainel(ComandoDeTerminal.ShellDoServidor.INSTANCIA, Preferencias::padrao);
        abrirSemEsperar(p, 1000, 600);
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().tipo() == Tipo.ENCERRADA);
        Espera.ate(PRAZO, "faixa", () -> Visual.naEdt(p::faixaVisivel));
        assertEquals("O terminal terminou (código 5).", Visual.naEdt(p::textoDaFaixaAgora));
        assertEquals("Abrir de novo", Visual.naEdt(() -> p.botaoReconectar().getText()));
        assertEquals(1, cenario.terminal.shellsAbertos.get());
    }

    @Test
    void bloquearOCofreDescartaOWidgetEFechaOCanal() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        JediTermWidget w = Visual.naEdt(p::widget);
        cenario.conexao.bloquear();
        Espera.ate(PRAZO, () -> "estado=" + p.sessao().estado(), () -> p.sessao().estado().tipo() == Tipo.BLOQUEADA);
        Espera.ate(PRAZO, "widget descartado", () -> Visual.naEdt(() -> p.widget() == null));
        assertEquals("O cofre foi bloqueado: o terminal foi fechado.", Visual.naEdt(p::textoDaFaixaAgora));
        assertFalse(Visual.naEdt(() -> w.isSessionRunning()), "o emulador parou");
        assertFalse(Visual.naEdt(() -> p.botaoReconectar().isVisible()), "com o cofre bloqueado não há como reconectar");
        Espera.naoAcontece(Duration.ofMillis(300), "reabriu", () -> cenario.terminal.canaisAbertos.get() > 1);
    }

    @Test
    void descartarFechaOCanalEOWidgetEEIdempotente() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        JediTermWidget w = Visual.naEdt(p::widget);
        Visual.naEdt(p::descartar);
        Visual.naEdt(p::descartar);
        assertNull(Visual.naEdt(p::widget));
        assertTrue(Visual.naEdt(p::descartado));
        assertFalse(Visual.naEdt(() -> w.isSessionRunning()));
        Espera.ate(PRAZO, "o canal fechou no servidor", () -> cenario.terminal.sessoes.get(0).saiu() || !w.getTtyConnector().isConnected());
        Espera.naoAcontece(Duration.ofMillis(300), "reabriu depois de descartado", () -> cenario.terminal.canaisAbertos.get() > 1);
    }

    @Test
    void tituloDoProgramaRemotoChegaLimpoELinksSoHttpComCmd() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(s -> {
            s.escrever("\u001b]0;titulo bom\u0007");
            s.escrever("\u001b]2;ab‮cd\u0001ef\u0007");
            ServidorDeTerminalFalso.roteiroQueNaoLe(s);
        });
        PainelTerminal p = doClaude();
        abrir(p, 1000, 600);
        Espera.ate(PRAZO, () -> "titulos=" + titulos, () -> titulos.size() >= 2);
        assertEquals("titulo bom", titulos.get(0));
        assertEquals("abcdef", titulos.get(1), "bidi e controles saem do título");
    }

    @Test
    void soUrlsHttpEHttpsViramLinkEOCliqueSoAbreComCmd() throws Exception {
        cenario = CenarioDeTerminal.conectado();
        PainelTerminal p = doClaude();
        LinkResult r = p.filtroDeLinks().apply("veja https://example.com/a?b=1, depois http://example.org/y). e file:///etc/passwd ssh://h javascript:alert(1)");
        assertNotNull(r);
        assertEquals(2, r.getItems().size());
        assertEquals("veja ".length(), r.getItems().get(0).getStartOffset());
        assertEquals("veja https://example.com/a?b=1".length(), r.getItems().get(0).getEndOffset(), "a vírgula da frase fica de fora");
        assertNull(p.filtroDeLinks().apply("nada aqui: ftp://x file:///etc"));

        // fora de um clique (nenhum evento em curso) ou num clique sem Cmd, NÃO abre
        Visual.naEdt(() -> r.getItems().get(0).getLinkInfo().navigate());
        assertTrue(links.isEmpty());
        JComponent origem = Visual.naEdt(() -> new JComponent() { private static final long serialVersionUID = 1L; });
        int cmd = br.com.labdesktop.ui.componentes.Teclas.comando();
        MouseEvent semCmd = new MouseEvent(origem, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false);
        MouseEvent comCmd = new MouseEvent(origem, MouseEvent.MOUSE_CLICKED, 0L, cmd, 1, 1, 1, false);
        p.abrirLink("https://example.com/a?b=1", semCmd);
        p.abrirLink("https://example.com/a?b=1", null);
        assertTrue(links.isEmpty());
        assertFalse(PainelTerminal.ehCliqueComComando(semCmd));
        assertFalse(PainelTerminal.ehCliqueComComando(new KeyEvent(origem, KeyEvent.KEY_PRESSED, 0L, cmd, KeyEvent.VK_A, 'a')),
            "só clique de mouse conta");
        // Cmd+clique abre
        assertTrue(PainelTerminal.ehCliqueComComando(comCmd));
        p.abrirLink("https://example.com/a?b=1", comCmd);
        assertEquals(List.of("https://example.com/a?b=1"), links);
    }

    private static void assertSame(Object esperado, Object atual, String msg) {
        org.junit.jupiter.api.Assertions.assertSame(esperado, atual, msg);
    }
}
