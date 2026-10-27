package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.EstadoDoTerminal.Tipo;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import java.awt.event.MouseWheelEvent;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A roda e o trackpad na tela alternativa viram Page Up e Page Down (ARQUITETURA 7.4): o Claude Code desenha em tela alternativa, o tmux
 * não guarda histórico dela e a rolagem da conversa é do Claude. Eventos de roda sintéticos entram no {@code TerminalPanel} como o AWT os
 * entregaria; o que o servidor recebe é conferido byte a byte.
 */
class RodaDoTerminalTest {

    private static final Duration PRAZO = Duration.ofSeconds(15);
    private static final String PAGE_UP = "\u001b[5~";
    private static final String PAGE_DOWN = "\u001b[6~";
    private static final String ALTERNATIVA = "\u001b[?1049h";
    private static final String MOUSE_REPORTING = "\u001b[?1000h\u001b[?1006h";

    private CenarioDeTerminal cenario;
    private PainelTerminal painel;

    @AfterEach
    void fechar() {
        if (painel != null) {
            Visual.naEdt(painel::descartar);
        }
        if (cenario != null) {
            cenario.close();
        }
    }

    /** Abre o terminal; o servidor escreve {@code inicio} (os modos do terminal) e uma marca, e depois só lê o teclado. */
    private TerminalPanel abrir(String inicio, boolean comProxy) throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = comProxy ? CenarioDeTerminal.conectadoComProxy() : CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(s -> {
            s.escrever(inicio + "PRONTO");
            byte[] b = new byte[512];
            while (s.teclado.read(b) != -1) {
                // só lê o teclado: sem eco, para a tela não mudar de modo por causa do que o teste manda
            }
        });
        ContextoDeTerminal ctx = new ContextoDeTerminal(new TransporteSsh(cenario.conexao), new LimiteDeAbas(), Preferencias::padrao, url -> { },
            Agendador.doFundo());
        painel = Visual.naEdt(() -> new PainelTerminal(ctx, new ComandoDeTerminal.SessaoDoClaude("orbita"), null));
        Visual.renderizar(painel, 1000, 600);
        Visual.naEdt(painel::iniciar);
        Espera.ate(PRAZO, () -> "estado=" + painel.sessao().estado(), () -> painel.sessao().estado().ativa() && Visual.naEdt(() -> painel.widget() != null));
        TerminalPanel tp = Visual.naEdt(() -> painel.widget().getTerminalPanel());
        // a marca só aparece depois de os modos terem sido processados, na mesma thread do emulador, em ordem
        Espera.ate(PRAZO, () -> "tela=" + tela(), () -> tela().contains("PRONTO"));
        return tp;
    }

    private String tela() {
        return Visual.naEdt(() -> {
            JediTermWidget w = painel.widget();
            return w == null ? "" : w.getTerminalTextBuffer().getScreenLines();
        });
    }

    /** Uma "roda" de mouse comum: {@code notches} inteiros (negativo = para cima). */
    private static MouseWheelEvent roda(TerminalPanel p, int notches) {
        return new MouseWheelEvent(p, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 20, 20, 20, 20, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notches, notches);
    }

    /** Um evento de trackpad: rotação fracionada (o inteiro de {@code wheelRotation} fica 0, como o macOS manda nos gestos finos). */
    private static MouseWheelEvent trackpad(TerminalPanel p, double precisa) {
        return new MouseWheelEvent(p, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 20, 20, 20, 20, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 0, precisa);
    }

    private static void girar(TerminalPanel p, MouseWheelEvent e) {
        Visual.naEdt(() -> p.dispatchEvent(e));
    }

    private void esperarRecebido(String esperado) {
        Espera.ate(PRAZO, () -> "recebido=" + visivel(cenario.terminal.recebidoComoTexto()),
            () -> cenario.terminal.recebidoComoTexto().equals(esperado));
    }

    private static String visivel(String s) {
        return s.replace("\u001b", "<ESC>");
    }

    @Test
    void naTelaAlternativaARodaParaCimaEPageUpEParaBaixoEPageDown() throws Exception {
        TerminalPanel p = abrir(ALTERNATIVA, false);
        girar(p, roda(p, -3));
        esperarRecebido(PAGE_UP);
        girar(p, roda(p, 3));
        esperarRecebido(PAGE_UP + PAGE_DOWN);
        // um notch sozinho ainda não é uma página
        girar(p, roda(p, -1));
        Espera.naoAcontece(Duration.ofMillis(300), "um notch virou página", () -> cenario.terminal.recebidoComoTexto().length() > (PAGE_UP + PAGE_DOWN).length());
        // três notches seguidos viram uma
        girar(p, roda(p, -1));
        girar(p, roda(p, -1));
        esperarRecebido(PAGE_UP + PAGE_DOWN + PAGE_UP);
    }

    @Test
    void trackpadAcumulaAsFracoesEUmGestoForteDaUmaPaginaSo() throws Exception {
        TerminalPanel p = abrir(ALTERNATIVA, false);
        for (int i = 0; i < 14; i++) {
            girar(p, trackpad(p, -0.2)); // 2,8 notches: ainda não
        }
        Espera.naoAcontece(Duration.ofMillis(300), "disparou antes do limite", () -> !cenario.terminal.recebidoComoTexto().isEmpty());
        girar(p, trackpad(p, -0.2)); // 3,0
        esperarRecebido(PAGE_UP);
        girar(p, trackpad(p, 25.0)); // um gesto fortíssimo para baixo: UMA página, não dezenas
        esperarRecebido(PAGE_UP + PAGE_DOWN);
        Espera.naoAcontece(Duration.ofMillis(300), "mais de uma página por evento", () -> cenario.terminal.recebidoComoTexto().length() > (PAGE_UP + PAGE_DOWN).length());
    }

    @Test
    void naTelaNormalNaoMandaNadaEARolagemNativaDoJediTermContinua() throws Exception {
        TerminalPanel p = abrir("", false); // o shell, com o histórico local
        for (int i = 0; i < 5; i++) {
            girar(p, roda(p, -3));
            girar(p, trackpad(p, -3.0));
        }
        Espera.naoAcontece(Duration.ofMillis(400), "a roda mandou algo ao servidor na tela normal", () -> !cenario.terminal.recebidoComoTexto().isEmpty());
    }

    @Test
    void comMouseReportingLigadoOJediTermRepassaAoAplicativoENaoMandaPageUp() throws Exception {
        TerminalPanel p = abrir(ALTERNATIVA + MOUSE_REPORTING, false);
        girar(p, roda(p, -3));
        girar(p, roda(p, -3));
        Espera.ate(PRAZO, () -> "recebido=" + visivel(cenario.terminal.recebidoComoTexto()), () -> !cenario.terminal.recebidoComoTexto().isEmpty());
        Espera.dormir(300);
        String recebido = cenario.terminal.recebidoComoTexto();
        assertFalse(recebido.contains(PAGE_UP) || recebido.contains(PAGE_DOWN), "com mouse reporting a roda vai ao aplicativo como mouse: " + visivel(recebido));
        assertTrue(recebido.startsWith("\u001b[<") || recebido.startsWith("\u001b[M"), "o relatório de mouse do JediTerm: " + visivel(recebido));
    }

    @Test
    void foraDeAtivaARodaEDescartadaComAFaixaENuncaFicaNaFila() throws Exception {
        TerminalPanel p = abrir(ALTERNATIVA, true);
        cenario.proxy.abrirBuracoNegro();
        cenario.proxy.recusarNovas(true);
        Espera.ate(PRAZO, () -> "estado=" + painel.sessao().estado(), () -> painel.sessao().estado().tipo() == Tipo.REANEXANDO);
        assertFalse(Visual.naEdt(painel::avisoDeTeclasVisivel));
        girar(p, roda(p, -3));
        girar(p, roda(p, 3));
        Espera.ate(PRAZO, "a faixa diz que as teclas foram descartadas", () -> Visual.naEdt(painel::avisoDeTeclasVisivel));
        assertEquals("", cenario.terminal.recebidoComoTexto());

        // a rede volta: o que a roda "mandou" durante a queda não aparece depois
        cenario.proxy.recusarNovas(false);
        Espera.ate(PRAZO, () -> "estado=" + painel.sessao().estado(), () -> painel.sessao().estado().ativa());
        Espera.naoAcontece(Duration.ofMillis(500), "uma página da queda apareceu depois", () -> !cenario.terminal.recebidoComoTexto().isEmpty());
    }
}
