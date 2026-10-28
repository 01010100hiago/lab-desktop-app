package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** O sono sem evento do sistema (Windows e Linux): o relógio que salta entre duas batidas vira "dormiu e acordou". Relógio falso, nenhuma espera. */
class VigiaDeRelogioTest {

    private final AtomicLong relogio = new AtomicLong(1_000_000);
    private final List<String> eventos = new ArrayList<>();
    private final MonitorDeSono monitor = new MonitorDeSono();
    private final VigiaDeRelogio vigia = new VigiaDeRelogio(monitor, relogio::get, Duration.ofSeconds(5), Duration.ofSeconds(15));

    VigiaDeRelogioTest() {
        monitor.aoDormir(() -> eventos.add("dormiu"));
        monitor.aoAcordar(() -> eventos.add("acordou"));
    }

    @Test
    void batidasNormaisNaoDisparamNada() {
        for (int i = 0; i < 100; i++) {
            relogio.addAndGet(5_000);
            vigia.conferir();
        }
        relogio.addAndGet(5_000 + 15_000); // exatamente no limite ainda é normal (atraso de agenda)
        vigia.conferir();
        assertEquals(List.of(), eventos);
    }

    @Test
    void relogioQueSaltaDormiuEAcordouNaOrdemCerta() {
        vigia.conferir();
        relogio.addAndGet(3_600_000); // uma hora de tampa fechada
        vigia.conferir();
        assertEquals(List.of("dormiu", "acordou"), eventos);
        // e a batida seguinte, já normal, não dispara de novo
        relogio.addAndGet(5_000);
        vigia.conferir();
        assertEquals(2, eventos.size());
    }

    @Test
    void doisSonosSeguidosDisparamDuasVezes() {
        relogio.addAndGet(60_000);
        vigia.conferir();
        relogio.addAndGet(5_000);
        vigia.conferir();
        relogio.addAndGet(600_000);
        vigia.conferir();
        assertEquals(List.of("dormiu", "acordou", "dormiu", "acordou"), eventos);
    }

    @Test
    void relogioQueVoltaParaTrasNaoEhSono() {
        relogio.addAndGet(-120_000); // o NTP acertou o relógio para trás
        vigia.conferir();
        assertEquals(List.of(), eventos);
    }

    @Test
    void ouvinteQueFalhaNaoImpedeOsOutros() {
        monitor.aoDormir(() -> {
            throw new IllegalStateException("falha de um ouvinte");
        });
        monitor.aoAcordar(() -> eventos.add("acordou-2"));
        relogio.addAndGet(100_000);
        vigia.conferir();
        assertEquals(List.of("dormiu", "acordou", "acordou-2"), eventos);
    }

    @Test
    void aVersaoDeProducaoNascePerguntaNaoSegurandoOProcessoAberto() throws Exception {
        try (VigiaDeRelogio v = VigiaDeRelogio.iniciar(new MonitorDeSono())) {
            org.junit.jupiter.api.Assertions.assertNotNull(v);
            Thread t = Thread.getAllStackTraces().keySet().stream().filter(x -> x.getName().equals("vigia-de-relogio")).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertTrue(t.isDaemon(), "thread daemon: não impede o app de fechar");
        }
    }
}
