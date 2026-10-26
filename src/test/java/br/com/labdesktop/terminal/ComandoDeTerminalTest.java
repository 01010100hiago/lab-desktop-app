package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.terminal.ComandoDeTerminal.SessaoDoClaude;
import br.com.labdesktop.terminal.ComandoDeTerminal.ShellDoServidor;
import org.junit.jupiter.api.Test;

class ComandoDeTerminalTest {

    @Test
    void nomeDoProjetoRuimNaoViraComando() {
        for (String ruim : new String[] {"a;b", "", "ORBITA", "Orbita", "-x", "a b", "a".repeat(50), "a`id`", "a\nb", "../x", null}) {
            assertThrows(IllegalArgumentException.class, () -> new SessaoDoClaude(ruim), "aceitou: " + ruim);
        }
    }

    @Test
    void nomeValidoPassaNoLimiteDaRegra() {
        assertEquals("orbita", new SessaoDoClaude("orbita").projeto());
        assertEquals("lab", new SessaoDoClaude("lab").projeto(), "a sessão raiz");
        assertEquals(49, new SessaoDoClaude("a".repeat(49)).projeto().length());
        assertEquals("0-9", new SessaoDoClaude("0-9").projeto());
    }

    @Test
    void sohOShellEhShell() {
        assertTrue(ShellDoServidor.INSTANCIA.ehShell());
        assertFalse(new SessaoDoClaude("orbita").ehShell());
    }

    @Test
    void tamanhoNuncaSaiDaFaixa() {
        assertEquals(1, new TamanhoDeTerminal(0, -5).colunas());
        assertEquals(1, new TamanhoDeTerminal(0, -5).linhas());
        assertEquals(1000, new TamanhoDeTerminal(99999, 99999).colunas());
        assertEquals(120, TamanhoDeTerminal.PADRAO.colunas());
        assertEquals(32, TamanhoDeTerminal.PADRAO.linhas());
    }
}
