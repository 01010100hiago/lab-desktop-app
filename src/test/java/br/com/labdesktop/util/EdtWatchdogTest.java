package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.AgendadorFalso;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EdtWatchdogTest {

    private AgendadorFalso relogio;
    private ExecutorService edtFalsa;
    private final List<String> relatorios = new CopyOnWriteArrayList<>();

    @BeforeEach
    void preparar() {
        relogio = new AgendadorFalso();
        edtFalsa = Executors.newSingleThreadExecutor(r -> new Thread(r, "edt-falsa"));
        Fundo.definirEdt(edtFalsa);
    }

    @AfterEach
    void limpar() {
        Fundo.definirEdt(Runnable::run);
        edtFalsa.shutdownNow();
    }

    private EdtWatchdog vigia() {
        return new EdtWatchdog(relogio, Duration.ofSeconds(1), Duration.ofMillis(500), relatorios::add);
    }

    private void esperarAEdtVoltarAoOcioso() throws Exception {
        edtFalsa.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void edtLivreNuncaRelata() throws Exception {
        try (EdtWatchdog v = vigia()) {
            v.iniciar();
            for (int i = 0; i < 5; i++) {
                esperarAEdtVoltarAoOcioso();
                relogio.avancar(Duration.ofSeconds(1));
            }
        }
        assertTrue(relatorios.isEmpty(), relatorios.toString());
    }

    @Test
    void edtPresaRelataUmaVezComAPilhaDeQuemPrende() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try (EdtWatchdog v = vigia()) {
            v.iniciar();
            esperarAEdtVoltarAoOcioso(); // o primeiro Runnable do vigia já rodou: ele sabe qual é a thread da EDT
            edtFalsa.execute(() -> {
                dentro.countDown();
                try {
                    soltar.await(); // "E/S na EDT"
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(dentro.await(5, TimeUnit.SECONDS));

            relogio.avancar(Duration.ofSeconds(1)); // posta o Runnable do próximo tique, que fica na fila
            relogio.avancar(Duration.ofMillis(600)); // passou do limite sem rodar
            assertEquals(1, relatorios.size(), relatorios.toString());
            assertTrue(relatorios.get(0).contains("500 ms"));
            assertTrue(relatorios.get(0).contains("CountDownLatch"), "a pilha mostra onde a EDT está presa");

            // Mais tiques com a EDT ainda presa: a mesma trava não é relatada de novo.
            relogio.avancar(Duration.ofSeconds(3));
            assertEquals(1, relatorios.size(), "uma trava, um relatório");
        } finally {
            soltar.countDown();
        }
    }

    @Test
    void depoisDeDestravarUmaNovaTravaVoltaARelatar() throws Exception {
        try (EdtWatchdog v = vigia()) {
            v.iniciar();
            for (int rodada = 1; rodada <= 2; rodada++) {
                esperarAEdtVoltarAoOcioso();
                CountDownLatch dentro = new CountDownLatch(1);
                CountDownLatch soltar = new CountDownLatch(1);
                edtFalsa.execute(() -> {
                    dentro.countDown();
                    try {
                        soltar.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(dentro.await(5, TimeUnit.SECONDS));
                relogio.avancar(Duration.ofSeconds(1));
                relogio.avancar(Duration.ofMillis(600));
                int esperado = rodada;
                Espera.ate(Duration.ofSeconds(5), () -> relatorios.toString(), () -> relatorios.size() == esperado);
                soltar.countDown();
                esperarAEdtVoltarAoOcioso();
            }
        }
        assertEquals(2, relatorios.size());
    }

    @Test
    void fecharParaDeVigiar() throws Exception {
        EdtWatchdog v = vigia();
        v.iniciar();
        esperarAEdtVoltarAoOcioso();
        v.close();
        int pendentesAntes = relogio.pendentes();
        relogio.avancar(Duration.ofSeconds(10));
        assertTrue(relatorios.isEmpty());
        assertTrue(relogio.pendentes() <= pendentesAntes, "nada novo é agendado depois de fechar");
    }

    @Test
    void temposNaoPositivosSaoRecusados() {
        assertThrows(IllegalArgumentException.class,
            () -> new EdtWatchdog(relogio, Duration.ZERO, Duration.ofMillis(1), s -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> new EdtWatchdog(relogio, Duration.ofSeconds(1), Duration.ofMillis(-1), s -> { }));
    }
}
