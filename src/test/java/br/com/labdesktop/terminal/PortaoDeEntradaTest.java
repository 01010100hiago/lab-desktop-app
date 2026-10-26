package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.AgendadorFalso;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** O portão que ordena o teclado do usuário e os envios da barra lateral (ARQUITETURA 7.5). */
class PortaoDeEntradaTest {

    private static final Duration PRAZO = Duration.ofSeconds(5);

    private final AgendadorFalso relogio = new AgendadorFalso();
    private final AtomicReference<EstadoDoTerminal> estado = new AtomicReference<>(EstadoDoTerminal.ATIVA);
    private final PortaoDeEntrada portao = new PortaoDeEntrada(relogio, estado::get);
    private final List<String> executados = new CopyOnWriteArrayList<>();

    private CompletableFuture<String> enviar(String nome) {
        return portao.enviar(() -> {
            executados.add(nome);
            return nome;
        });
    }

    @Test
    void semTeclaDoDonoEnviaNaHora() throws Exception {
        assertEquals("a", enviar("a").get());
        assertEquals(List.of("a"), executados);
    }

    @Test
    void seODonoDigitouHaMenosDe800msOEnvioEsperaOSilencio() throws Exception {
        portao.aoDigitar();
        assertTrue(portao.donoDigitandoAgora());
        CompletableFuture<String> f = enviar("a");
        Espera.naoAcontece(Duration.ofMillis(150), "enviou durante a digitação", f::isDone);

        relogio.avancar(Duration.ofMillis(799));
        Espera.naoAcontece(Duration.ofMillis(150), "enviou 1 ms antes do silêncio completo", f::isDone);
        assertTrue(executados.isEmpty());

        relogio.avancar(Duration.ofMillis(1));
        assertEquals("a", f.get());
        assertFalse(portao.donoDigitandoAgora());
    }

    @Test
    void digitarDeNovoEmpurraOEnvioParaDepoisDeOutros800ms() throws Exception {
        portao.aoDigitar();
        CompletableFuture<String> f = enviar("a");
        relogio.avancar(Duration.ofMillis(500));
        portao.aoDigitar(); // o usuário continuou escrevendo
        relogio.avancar(Duration.ofMillis(300)); // venceria o primeiro alarme (800 ms desde a primeira tecla)
        Espera.naoAcontece(Duration.ofMillis(150), "enviou com o usuário ainda digitando", f::isDone);
        relogio.avancar(Duration.ofMillis(500)); // 800 ms desde a segunda tecla
        assertEquals("a", f.get());
    }

    @Test
    void osEnviosRodamUmDeCadaVezEATerceiroEhRecusado() throws Exception {
        CountDownLatch solta = new CountDownLatch(1);
        CountDownLatch comecou = new CountDownLatch(1);
        CompletableFuture<String> primeiro = portao.enviar(() -> {
            comecou.countDown();
            solta.await();
            executados.add("primeiro");
            return "primeiro";
        });
        assertTrue(comecou.await(5, java.util.concurrent.TimeUnit.SECONDS));
        CompletableFuture<String> segundo = enviar("segundo");
        CompletableFuture<String> terceiro = enviar("terceiro");

        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, terceiro::get);
        assertInstanceOf(PortaoDeEntrada.OcupadoException.class, e.getCause());
        assertEquals(2, portao.pendentes());
        assertFalse(segundo.isDone(), "o segundo espera o primeiro terminar");
        assertTrue(executados.isEmpty());

        solta.countDown();
        assertEquals("primeiro", primeiro.get());
        assertEquals("segundo", segundo.get());
        assertEquals(List.of("primeiro", "segundo"), executados, "na ordem, nunca em paralelo");
        Espera.ate(PRAZO, "fila vazia", () -> portao.pendentes() == 0);
        assertEquals("quarto", enviar("quarto").get(), "com a fila vazia, aceita de novo");
    }

    @Test
    void nuncaEnviaEnquantoOTerminalReanexa() {
        estado.set(EstadoDoTerminal.reanexando(2));
        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, () -> enviar("a").get());
        assertInstanceOf(PortaoDeEntrada.TerminalReanexandoException.class, e.getCause());
        assertTrue(executados.isEmpty());
        assertEquals(0, portao.pendentes());
    }

    @Test
    void oQueEsperavaNaFilaFalhaQuandoOTerminalPassaAReanexar() {
        portao.aoDigitar();
        CompletableFuture<String> f = enviar("a");
        estado.set(EstadoDoTerminal.reanexando(1));
        portao.estadoMudou();
        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, f::get);
        assertInstanceOf(PortaoDeEntrada.TerminalReanexandoException.class, e.getCause());
        relogio.avancar(Duration.ofSeconds(5));
        assertTrue(executados.isEmpty(), "nada pode ter sido enviado depois");
    }

    @Test
    void falhaDoEnvioChegaAoFuturoENaoTravaAFila() throws Exception {
        CompletableFuture<String> ruim = portao.enviar(() -> {
            throw new java.io.IOException("agente fora");
        });
        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, ruim::get);
        assertEquals("agente fora", e.getCause().getMessage());
        assertEquals("b", enviar("b").get());
    }
}
