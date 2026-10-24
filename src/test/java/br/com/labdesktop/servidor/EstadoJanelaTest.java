package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A regra única do estado da janela (ARQUITETURA 6.7): minimizada conta como escondida. */
class EstadoJanelaTest {

    @Test
    void visivelEEmFocoEAtiva() {
        assertEquals(EstadoJanela.ATIVA, EstadoJanela.de(true, false, true));
    }

    @Test
    void visivelSemFocoEMinimizadaNaoSaoAMesmaCoisa() {
        assertEquals(EstadoJanela.SEM_FOCO, EstadoJanela.de(true, false, false));
        assertEquals(EstadoJanela.ESCONDIDA, EstadoJanela.de(true, true, false), "no Dock: só LIMITES");
        assertEquals(EstadoJanela.ESCONDIDA, EstadoJanela.de(true, true, true), "mesmo se o Swing ainda disser que está ativa");
    }

    @Test
    void fechadaParaABarraDeMenusEEscondida() {
        assertEquals(EstadoJanela.ESCONDIDA, EstadoJanela.de(false, false, false));
        assertEquals(EstadoJanela.ESCONDIDA, EstadoJanela.de(false, false, true));
        assertEquals(EstadoJanela.ESCONDIDA, EstadoJanela.de(false, true, false));
    }

    @Test
    void soLimitesRodaEscondida() {
        assertEquals(java.util.Optional.empty(), Topico.PROJETOS.intervalo(EstadoJanela.de(true, true, false), false));
        assertEquals(java.util.Optional.empty(), Topico.SERVIDOR.intervalo(EstadoJanela.de(true, true, false), true));
        assertEquals(java.util.Optional.of(java.time.Duration.ofSeconds(60)),
            Topico.LIMITES.intervalo(EstadoJanela.de(true, true, false), false));
    }
}
