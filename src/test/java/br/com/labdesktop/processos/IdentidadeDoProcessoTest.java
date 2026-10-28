package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A identidade lida do {@code ps}: o que vale, o que é recusado e a conferência de nome. */
class IdentidadeDoProcessoTest {

    @Test
    void leASaidaDoPsComEspacosIrregulares() {
        IdentidadeDoProcesso i = IdentidadeDoProcesso.deSaidaDoPs("  4242  1000 Tue Oct  8 12:00:01 2026 node\n", 4242).orElseThrow();
        assertEquals(4242, i.pid());
        assertEquals("1000", i.uid());
        assertEquals("Tue Oct 8 12:00:01 2026", i.inicio());
        assertEquals("node", i.nome());
        assertEquals("4242 1000 Tue Oct 8 12:00:01 2026 node", i.linha(), "a linha que o servidor compara tem um espaço só");
    }

    @Test
    void nomeComEspacoFicaInteiro() {
        IdentidadeDoProcesso i = IdentidadeDoProcesso.deSaidaDoPs("4243 1000 Tue Oct 28 12:00:01 2026 Web Content", 4243).orElseThrow();
        assertEquals("Web Content", i.nome());
    }

    @Test
    void saidaVaziaOuDeOutroNumeroOuMalFormadaNaoViraIdentidade() {
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs("", 10).isEmpty());
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs("\n\n", 10).isEmpty());
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs("11 1000 Tue Oct 28 12:00:01 2026 node", 10).isEmpty(), "o ps respondeu por outro número");
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs("10 root Tue Oct 28 12:00:01 2026 node", 10).isEmpty(), "dono tem de ser número");
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs("10 1000 Ter 28 out 12:00:01 2026 node", 10).isEmpty(), "data fora do formato em inglês");
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs("usage: ps [-aACcEefhjlMmrSTvwXx] ...", 10).isEmpty());
        assertTrue(IdentidadeDoProcesso.deSaidaDoPs(null, 10).isEmpty());
    }

    @Test
    void naoAceitaControleNoNome() {
        assertThrows(IllegalArgumentException.class, () -> new IdentidadeDoProcesso(10, "1000", "Tue Oct 28 12:00:01 2026", "no\u0007me"));
        assertThrows(IllegalArgumentException.class, () -> new IdentidadeDoProcesso(10, "1000", "Tue Oct 28 12:00:01 2026", ""));
    }

    @Test
    void conferenciaDeNomeAceitaOQueOPsCorta() {
        assertTrue(IdentidadeDoProcesso.nomeConfere("postgres: writer process", "postgres"));
        assertTrue(IdentidadeDoProcesso.nomeConfere("containerd-shim-runc-v2", "containerd-shim-"), "o comm do Linux tem 15 letras");
        assertTrue(IdentidadeDoProcesso.nomeConfere("node", "/usr/bin/node"), "no Mac vem o caminho inteiro");
        assertTrue(IdentidadeDoProcesso.nomeConfere("NODE", "node"));
        assertFalse(IdentidadeDoProcesso.nomeConfere("node", "python3"));
        assertFalse(IdentidadeDoProcesso.nomeConfere("", "node"));
        assertFalse(IdentidadeDoProcesso.nomeConfere("node", ""));
    }
}
