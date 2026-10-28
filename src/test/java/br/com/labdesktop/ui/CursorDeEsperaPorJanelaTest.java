package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.ui.Tarefas.Decisao;
import org.junit.jupiter.api.Test;

/** A regra do cursor de espera janela por janela: a janela que reaparece durante a espera também o recebe, e nenhuma o guarda depois que a espera acaba. */
class CursorDeEsperaPorJanelaTest {

    @Test
    void comEsperaAJanelaAVistaSemCursorDeEsperaORecebe() {
        assertEquals(Decisao.POR_ESPERA, Tarefas.decidir(true, false, true, false), "a janela que apareceu depois de a espera começar");
        assertEquals(Decisao.NADA, Tarefas.decidir(true, true, true, false), "já tem");
        assertEquals(Decisao.NADA, Tarefas.decidir(true, false, false, false), "escondida: espera aparecer");
    }

    @Test
    void semEsperaOCursorSaiDeTodaJanelaQueOTemInclusiveEscondida() {
        assertEquals(Decisao.TIRAR_ESPERA, Tarefas.decidir(false, true, true, true));
        assertEquals(Decisao.TIRAR_ESPERA, Tarefas.decidir(false, true, false, true), "estava escondida quando a espera acabou");
        assertEquals(Decisao.TIRAR_ESPERA, Tarefas.decidir(false, true, true, false), "reapareceu depois, ainda com o cursor de antes");
        assertEquals(Decisao.NADA, Tarefas.decidir(false, false, true, true));
    }
}
