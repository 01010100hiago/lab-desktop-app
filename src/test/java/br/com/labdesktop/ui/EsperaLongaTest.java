package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** O modelo puro do cursor de espera: só depois de 300 ms de ação rodando, e sai com a última que termina. */
class EsperaLongaTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void semAcaoNaoHaCursorDeEspera() {
        EsperaLonga e = new EsperaLonga();
        assertFalse(e.haAcaoRodando());
        assertFalse(e.cursorDeEsperaAgora(T0.plusSeconds(60)));
    }

    @Test
    void aAcaoRapidaNuncaFazOCursorPiscar() {
        EsperaLonga e = new EsperaLonga();
        e.comecou(T0);
        assertTrue(e.haAcaoRodando());
        assertFalse(e.cursorDeEsperaAgora(T0.plusMillis(299)));
        e.terminou();
        assertFalse(e.cursorDeEsperaAgora(T0.plusMillis(400)), "terminou antes: nenhum cursor");
    }

    @Test
    void passadoOAtrasoOCursorEntraESaiQuandoATerminaUltima() {
        EsperaLonga e = new EsperaLonga();
        e.comecou(T0);
        assertTrue(e.cursorDeEsperaAgora(T0.plusMillis(300)));
        e.comecou(T0.plusMillis(500));
        e.terminou(); // a mais antiga terminou; a outra ainda está dentro do atraso
        assertFalse(e.cursorDeEsperaAgora(T0.plusMillis(700)));
        assertTrue(e.cursorDeEsperaAgora(T0.plusMillis(800)));
        e.terminou();
        assertFalse(e.cursorDeEsperaAgora(T0.plusSeconds(10)));
        e.terminou(); // terminar a mais não quebra
        assertFalse(e.haAcaoRodando());
    }
}
