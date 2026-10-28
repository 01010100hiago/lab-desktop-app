package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** O aviso de sair do app com algo pendente: só aparece quando há o que perder, e diz o quê. */
class PendenciasTest {

    @Test
    void semNadaPendenteNaoHaAvisoESomaZera() {
        assertTrue(Pendencias.NENHUMAS.vazio());
        assertTrue(new Pendencias(0, 0).somar(Pendencias.NENHUMAS).vazio());
        assertFalse(new Pendencias(1, 0).vazio());
        assertFalse(new Pendencias(0, 2).vazio());
    }

    @Test
    void somaJuntaTransferenciasEEditores() {
        assertEquals(new Pendencias(3, 2), new Pendencias(1, 2).somar(new Pendencias(2, 0)));
    }

    @Test
    void oTextoDizOQuePerde() {
        assertEquals("1 transferência de arquivos ainda está andando. Se sair agora, ela para no meio.", new Pendencias(1, 0).texto());
        assertEquals("2 transferências de arquivos ainda estão andando. Se sair agora, elas param no meio.", new Pendencias(2, 0).texto());
        assertEquals("1 arquivo aberto no editor tem alterações que você não salvou. Se sair agora, o que você digitou se perde.", new Pendencias(0, 1).texto());
        assertTrue(new Pendencias(0, 3).texto().startsWith("3 arquivos abertos no editor têm alterações"));
        String os_dois = new Pendencias(2, 1).texto();
        assertTrue(os_dois.contains("2 transferências de arquivos ainda estão andando e 1 arquivo aberto no editor"), os_dois);
    }
}
