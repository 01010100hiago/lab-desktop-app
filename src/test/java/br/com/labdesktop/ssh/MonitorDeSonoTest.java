package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O {@code MonitorDeSono} ignora o conteúdo do evento (só importa se dormiu ou acordou), então o teste passa
 * {@code null}: criar um {@code SystemSleepEvent} de verdade chama {@code Desktop.getDesktop()}, que falha na JVM sem
 * cabeça em que os testes de tela rodam.
 */
class MonitorDeSonoTest {

    @Test
    void avisaQuemSeInscreveENaoQuemDesistiu() {
        MonitorDeSono m = new MonitorDeSono();
        List<String> eventos = new ArrayList<>();
        Runnable desfazer = m.aoDormir(() -> eventos.add("dormiu"));
        m.aoAcordar(() -> eventos.add("acordou"));

        m.systemAboutToSleep(null);
        m.systemAwoke(null);
        desfazer.run();
        m.systemAboutToSleep(null);

        assertEquals(List.of("dormiu", "acordou"), eventos);
    }

    @Test
    void ouvinteQueLancaNaoImpedeOsOutros() {
        MonitorDeSono m = new MonitorDeSono();
        List<String> eventos = new ArrayList<>();
        m.aoAcordar(() -> {
            throw new IllegalStateException("falha de propósito");
        });
        m.aoAcordar(() -> eventos.add("segundo"));
        m.systemAwoke(null);
        assertEquals(List.of("segundo"), eventos);
    }
}
