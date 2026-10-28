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
        EsperaLonga.Acao a = e.comecou(T0);
        assertTrue(e.haAcaoRodando());
        assertFalse(e.cursorDeEsperaAgora(T0.plusMillis(299)));
        e.terminou(a);
        assertFalse(e.cursorDeEsperaAgora(T0.plusMillis(400)), "terminou antes: nenhum cursor");
    }

    @Test
    void passadoOAtrasoOCursorEntraESaiQuandoATerminaUltima() {
        EsperaLonga e = new EsperaLonga();
        EsperaLonga.Acao primeira = e.comecou(T0);
        assertTrue(e.cursorDeEsperaAgora(T0.plusMillis(300)));
        EsperaLonga.Acao segunda = e.comecou(T0.plusMillis(500));
        e.terminou(primeira); // a mais antiga terminou; a outra ainda está dentro do atraso
        assertFalse(e.cursorDeEsperaAgora(T0.plusMillis(700)));
        assertTrue(e.cursorDeEsperaAgora(T0.plusMillis(800)));
        e.terminou(segunda);
        assertFalse(e.cursorDeEsperaAgora(T0.plusSeconds(10)));
        e.terminou(segunda); // terminar de novo não quebra
        assertFalse(e.haAcaoRodando());
    }

    /** Antes terminava SEMPRE a mais antiga, então uma ação curta que acabava primeiro apagava o início da longa e o cursor sumia cedo (ou nunca vinha). */
    @Test
    void aAcaoCurtaQueTerminaPrimeiroNaoApagaOInicioDaLonga() {
        EsperaLonga e = new EsperaLonga();
        EsperaLonga.Acao longa = e.comecou(T0);
        EsperaLonga.Acao curta = e.comecou(T0.plusMillis(100));
        e.terminou(curta);
        assertTrue(e.haAcaoRodando());
        assertTrue(e.cursorDeEsperaAgora(T0.plusMillis(300)), "a longa começou em T0: passado o atraso, o cursor entra");
        e.terminou(longa);
        assertFalse(e.haAcaoRodando());
    }

    @Test
    void duasAcoesComecadasNoMesmoInstanteSaoDuas() {
        EsperaLonga e = new EsperaLonga();
        EsperaLonga.Acao a = e.comecou(T0);
        EsperaLonga.Acao b = e.comecou(T0);
        e.terminou(a);
        assertTrue(e.haAcaoRodando());
        e.terminou(b);
        assertFalse(e.haAcaoRodando());
    }
}
