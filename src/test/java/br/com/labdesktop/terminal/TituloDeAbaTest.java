package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** O título que o servidor manda por OSC é texto hostil até prova em contrário (ARQUITETURA 7.4). */
class TituloDeAbaTest {

    @Test
    void tiraControlesEBidiEMantemOTexto() {
        assertEquals("abcdef", TituloDeAba.limpar("ab‮cd\u0001ef", "Terminal 1"));
        assertEquals("labuser@lab: ~/srv", TituloDeAba.limpar("labuser@lab: ~/srv", "Terminal 1"));
        assertEquals("ação", TituloDeAba.limpar("ação", "x"));
        assertEquals("a b", TituloDeAba.limpar("a\nb", "x"), "quebra de linha vira espaço");
        assertFalse(TituloDeAba.limpar("\u001b[31mvermelho", "x").contains("\u001b"));
    }

    @Test
    void naoDeixaOTituloVirarHtml() {
        String limpo = TituloDeAba.limpar("<html><img src='http://evil/x'>", "x");
        assertFalse(limpo.toLowerCase().startsWith("<html"), "um rótulo que começa com <html é desenhado como HTML");
        assertTrue(limpo.contains("img"));
    }

    @Test
    void vazioOuSoLixoViraOPadraoELongoEhCortado() {
        assertEquals("Terminal 2", TituloDeAba.limpar("", "Terminal 2"));
        assertEquals("Terminal 2", TituloDeAba.limpar(null, "Terminal 2"));
        assertEquals("Terminal 2", TituloDeAba.limpar("‮\u0007  ", "Terminal 2"));
        String longo = TituloDeAba.limpar("x".repeat(200), "p");
        assertEquals(TituloDeAba.MAXIMO, longo.length());
        assertTrue(longo.endsWith("…"));
    }
}
