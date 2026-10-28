package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.terminal.ComandoDeTerminal.SessaoDoClaude;
import br.com.labdesktop.terminal.ComandoDeTerminal.ShellDoProjeto;
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
    void osDoisShellsSaoShellESoASessaoDoClaudeNao() {
        assertTrue(ShellDoServidor.INSTANCIA.ehShell());
        assertTrue(new ShellDoProjeto("/srv/lab/projects/orbita").ehShell());
        assertFalse(new SessaoDoClaude("orbita").ehShell());
    }

    @Test
    void pastaDeProjetoRuimNaoViraComando() {
        for (String ruim : new String[] {"", "orbita", "srv/lab", "/srv/lab/../etc", "/a b", "/a'b", "/a;b", "/a$(id)", "/a\nb", "/ação", "/..", null}) {
            assertThrows(IllegalArgumentException.class, () -> new ShellDoProjeto(ruim), "aceitou: " + ruim);
            assertFalse(ShellDoProjeto.valida(ruim));
        }
        assertTrue(ShellDoProjeto.valida("/srv/lab/projects/orbita"));
        assertTrue(ShellDoProjeto.valida("/home/labuser/meus.projetos_2/x-y"));
    }

    @Test
    void naPastaDeEsteComputadorEspacoAcentoEDiscoDoWindowsValemMasSeguemSemPontoPontoENuloOuControle() {
        for (String boa : new String[] {"/C:/Users/João Silva/LabLocal/projects/meu app", "/Users/ana/Meus Projetos/ação", "/C:/Users/ana", "/x"}) {
            assertTrue(ShellDoProjeto.valida(boa, true), boa);
            assertEquals(boa, ShellDoProjeto.doComputador(boa).pasta());
            assertTrue(ShellDoProjeto.doComputador(boa).local());
            assertFalse(ShellDoProjeto.valida(boa, false) && boa.contains(" "), "o servidor SSH continua estrito: " + boa);
        }
        for (String ruim : new String[] {"", "relativa/x", "C:\\Users\\ana", "/a/../b", "/a\nb", "/a\u0000b", "/a\tb", "/" + "x".repeat(1100), null}) {
            assertFalse(ShellDoProjeto.valida(ruim, true), "aceitou: " + ruim);
            assertThrows(IllegalArgumentException.class, () -> ShellDoProjeto.doComputador(ruim));
        }
        assertFalse(ShellDoProjeto.valida("/C:/Users/João Silva/x"), "o servidor SSH não aceita acento, espaço nem dois-pontos");
        assertFalse(new ShellDoProjeto("/srv/lab/x").local());
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
