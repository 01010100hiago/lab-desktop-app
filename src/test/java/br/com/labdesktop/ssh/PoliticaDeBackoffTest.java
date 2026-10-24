package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoliticaDeBackoffTest {

    private static PoliticaDeBackoff semJitter() {
        return new PoliticaDeBackoff(Duration.ofSeconds(1), () -> 0.5); // 0,5 = meio da faixa = fator 1,0
    }

    @Test
    void degrausSaoUmDoisQuatroOitoQuinzeTrintaSessentaEFicaEmSessenta() {
        PoliticaDeBackoff p = semJitter();
        List<Long> segundos = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            segundos.add(p.proximo().toSeconds());
        }
        assertEquals(List.of(1L, 2L, 4L, 8L, 15L, 30L, 60L, 60L, 60L), segundos);
        assertEquals(9, p.tentativas());
    }

    @Test
    void jitterFicaDentroDeMaisOuMenosVintePorCento() {
        Duration minimo = new PoliticaDeBackoff(Duration.ofSeconds(1), () -> 0.0).proximo();
        Duration maximo = new PoliticaDeBackoff(Duration.ofSeconds(1), () -> 0.999999).proximo();
        assertEquals(800, minimo.toMillis());
        assertTrue(maximo.toMillis() >= 1199 && maximo.toMillis() <= 1200, "máximo: " + maximo);
    }

    @Test
    void zerarVoltaAoPrimeiroDegrau() {
        PoliticaDeBackoff p = semJitter();
        p.proximo();
        p.proximo();
        p.proximo();
        p.zerar();
        assertEquals(0, p.tentativas());
        assertEquals(1, p.proximo().toSeconds());
    }

    @Test
    void escalaPermiteTestarEmMilissegundos() {
        PoliticaDeBackoff p = new PoliticaDeBackoff(Duration.ofMillis(10), () -> 0.5);
        assertEquals(10, p.proximo().toMillis());
        assertEquals(20, p.proximo().toMillis());
    }

    @Test
    void padraoUsaSorteioRealDentroDaFaixa() {
        for (int i = 0; i < 50; i++) {
            long ms = PoliticaDeBackoff.padrao().proximo().toMillis();
            assertTrue(ms >= 800 && ms <= 1200, "ms=" + ms);
        }
    }
}
