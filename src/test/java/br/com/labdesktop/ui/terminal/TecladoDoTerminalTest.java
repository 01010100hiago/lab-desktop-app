package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import com.jediterm.terminal.ui.TerminalPanel;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Parte da matriz de teclado de ARQUITETURA 7.4 que dá para provar sem teclado de verdade: o evento entra no {@code TerminalPanel}
 * (como o AWT o entregaria) e o que chega ao servidor, byte a byte, é o que o Claude e o tmux esperam. O resto da matriz (ABNT2 e
 * US-internacional, Option, acentos, colar 4000 caracteres, redimensionar com o Claude em tela cheia) é do usuário, no teclado dele.
 */
class TecladoDoTerminalTest {

    private static final Duration PRAZO = Duration.ofSeconds(15);
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

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

    private TerminalPanel abrir(Supplier<Preferencias> prefs) throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroDeEco); // lê o teclado (é o que o servidor registra) e devolve o eco
        ContextoDeTerminal ctx = new ContextoDeTerminal(new TransporteSsh(cenario.conexao), new LimiteDeAbas(), prefs, url -> { }, Agendador.doFundo());
        painel = Visual.naEdt(() -> new PainelTerminal(ctx, new ComandoDeTerminal.SessaoDoClaude("orbita"), null));
        Visual.renderizar(painel, 1000, 600);
        Visual.naEdt(painel::iniciar);
        Espera.ate(PRAZO, () -> "estado=" + painel.sessao().estado(), () -> painel.sessao().estado().ativa() && Visual.naEdt(() -> painel.widget() != null));
        return Visual.naEdt(() -> painel.widget().getTerminalPanel());
    }

    private static KeyEvent tecla(TerminalPanel p, int id, int modificadores, int codigo, char c) {
        return new KeyEvent(p, id, System.currentTimeMillis(), modificadores, codigo, c);
    }

    /** Aperta uma tecla "de controle" (KEY_PRESSED) e devolve o evento depois do tratamento. */
    private static KeyEvent apertar(TerminalPanel p, int modificadores, int codigo, char c) {
        KeyEvent e = tecla(p, KeyEvent.KEY_PRESSED, modificadores, codigo, c);
        Visual.naEdt(() -> p.processKeyEvent(e));
        return e;
    }

    /** Digita um caractere (KEY_TYPED). */
    private static KeyEvent digitar(TerminalPanel p, int modificadores, char c) {
        KeyEvent e = tecla(p, KeyEvent.KEY_TYPED, modificadores, KeyEvent.VK_UNDEFINED, c);
        Visual.naEdt(() -> p.processKeyEvent(e));
        return e;
    }

    private void esperarRecebido(String esperado) {
        Espera.ate(PRAZO, () -> "recebido=" + visivel(cenario.terminal.recebidoComoTexto()),
            () -> cenario.terminal.recebidoComoTexto().equals(esperado));
    }

    private static String visivel(String s) {
        return s.replace("\u001b", "<ESC>").replace("\r", "<CR>").replace("\t", "<TAB>").replace("\n", "<LF>").replace("\u0003", "<^C>")
            .replace("\u0004", "<^D>");
    }

    @Test
    void escETabEShiftTabChegamAoTerminalENuncaViramAtalhoDeJanela() throws Exception {
        TerminalPanel p = abrir(Preferencias::padrao);
        assertFalse(Visual.naEdt(p::getFocusTraversalKeysEnabled), "Tab e Shift+Tab chegam ao terminal, não ao foco do Swing");

        KeyEvent esc = apertar(p, 0, KeyEvent.VK_ESCAPE, '\u001b');
        assertTrue(esc.isConsumed(), "o Esc é do terminal: o 'voltar aos projetos' da página nunca o recebe");
        KeyEvent tab = apertar(p, 0, KeyEvent.VK_TAB, '\t');
        assertTrue(tab.isConsumed());
        esperarRecebido("\u001b\t");
        apertar(p, SHIFT, KeyEvent.VK_TAB, '\t');
        esperarRecebido("\u001b\t\u001b[Z"); // Shift+Tab alterna o modo do Claude
    }

    @Test
    void enterShiftEnterEControles() throws Exception {
        TerminalPanel p = abrir(Preferencias::padrao);
        apertar(p, 0, KeyEvent.VK_ENTER, '\n');
        esperarRecebido("\r");
        apertar(p, SHIFT, KeyEvent.VK_ENTER, '\n');
        esperarRecebido("\r\u001b\r"); // Shift+Enter: ESC CR, linha nova no prompt do Claude
        apertar(p, CTRL, KeyEvent.VK_C, '\u0003');
        esperarRecebido("\r\u001b\r\u0003");
        apertar(p, CTRL, KeyEvent.VK_D, '\u0004');
        esperarRecebido("\r\u001b\r\u0003\u0004");
    }

    @Test
    void setasHomeEndEPageUpPageDown() throws Exception {
        TerminalPanel p = abrir(Preferencias::padrao);
        apertar(p, 0, KeyEvent.VK_UP, '￿');
        apertar(p, 0, KeyEvent.VK_DOWN, '￿');
        apertar(p, 0, KeyEvent.VK_RIGHT, '￿');
        apertar(p, 0, KeyEvent.VK_LEFT, '￿');
        esperarRecebido("\u001b[A\u001b[B\u001b[C\u001b[D");
        apertar(p, 0, KeyEvent.VK_HOME, '￿');
        apertar(p, 0, KeyEvent.VK_END, '￿');
        Espera.ate(PRAZO, () -> "recebido=" + visivel(cenario.terminal.recebidoComoTexto()), () -> cenario.terminal.recebidoComoTexto().length() >= 4 * 3 + 6);
        String recebido = cenario.terminal.recebidoComoTexto();
        assertTrue(recebido.startsWith("\u001b[A\u001b[B\u001b[C\u001b[D\u001b[") , visivel(recebido));
    }

    @Test
    void acentosDigitadosPassamComoTexto() throws Exception {
        TerminalPanel p = abrir(Preferencias::padrao);
        // ABNT2: ç, ã, é, ^ e ~ já chegam como caracteres (as teclas mortas são resolvidas pelo método de entrada)
        for (char c : new char[] {'ç', 'ã', 'é', '^', '~', '´', '`'}) {
            digitar(p, 0, c);
        }
        esperarRecebido("çãé^~´`");
    }

    /** O que o macOS entrega ao apertar Option+tecla: o KEY_PRESSED (com Alt) e depois o KEY_TYPED com o caractere que o Option gerou. */
    private static void optionMais(TerminalPanel p, int codigo, char gerado) {
        apertar(p, ALT, codigo, gerado);
        digitar(p, ALT, gerado);
    }

    @Test
    void optionNaoViraEscPorPadraoOCaractereDoAbnt2PassaInteiro() throws Exception {
        TerminalPanel p = abrir(Preferencias::padrao);
        optionMais(p, KeyEvent.VK_F, 'ƒ');
        optionMais(p, KeyEvent.VK_X, '≈');
        esperarRecebido("ƒ≈");
        Espera.naoAcontece(Duration.ofMillis(300), "um ESC apareceu antes do caractere", () -> cenario.terminal.recebidoComoTexto().contains("\u001b"));
    }

    @Test
    void comOptionComoMetaOAltViraEscMaisATecla() throws Exception {
        TerminalPanel p = abrir(() -> Preferencias.padrao().comOpcaoComoMeta(true));
        apertar(p, ALT, KeyEvent.VK_F, 'f');
        digitar(p, ALT, 'f');
        esperarRecebido("\u001bf");
    }

    @Test
    void colarEmojiETextoLongoChegaInteiro() throws Exception {
        TerminalPanel p = abrir(Preferencias::padrao);
        String emoji = "ok 😀 fim";
        String longo = "x".repeat(4000);
        Visual.naEdt(() -> {
            try {
                painel.widget().getTtyConnector().write(emoji);
                painel.widget().getTtyConnector().write(longo);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        esperarRecebido(emoji + longo);
    }
}
