package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FundoTest {

    @AfterEach
    void restaurar() {
        Fundo.definirEdt(Runnable::run);
        Fundo.encerrar();
    }

    @Test
    void naEdtUsaODespachanteInjetado() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        CountDownLatch pronto = new CountDownLatch(1);
        Fundo.definirEdt(tarefa -> {
            // Despachante de teste: roda a tarefa numa thread com nome conhecido.
            Thread t = new Thread(tarefa, "edt-falsa");
            t.start();
        });
        Fundo.naEdt(() -> {
            thread.set(Thread.currentThread());
            pronto.countDown();
        });
        assertTrue(pronto.await(5, TimeUnit.SECONDS));
        assertEquals("edt-falsa", thread.get().getName());
    }

    @Test
    void semDespachanteExecutaNaPropriaThread() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        Fundo.naEdt(() -> thread.set(Thread.currentThread()));
        assertSame(Thread.currentThread(), thread.get());
    }

    @Test
    void emFundoRodaEmThreadVirtualNomeada() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        Fundo.emFundo(() -> thread.set(Thread.currentThread())).get(5, TimeUnit.SECONDS);
        assertTrue(thread.get().isVirtual());
        assertTrue(thread.get().getName().startsWith("io-virtual-"), thread.get().getName());
    }

    @Test
    void emFundoComResultadoDevolveOValor() throws Exception {
        assertEquals(42, Fundo.emFundoComResultado(() -> 42).get(5, TimeUnit.SECONDS));
    }

    @Test
    void aguardarDevolveAExcecaoOriginalSemEmbrulho() {
        CompletableFuture<String> falha = Fundo.emFundoComResultado(() -> {
            throw new IllegalStateException("boom");
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Fundo.aguardar(falha));
        assertEquals("boom", e.getMessage());
    }

    @Test
    void aguardarEmbrulhaExcecaoVerificadaEmRuntime() {
        CompletableFuture<String> falha = Fundo.emFundoComResultado(() -> {
            throw new IOException("disco");
        });
        RuntimeException e = assertThrows(RuntimeException.class, () -> Fundo.aguardar(falha));
        assertTrue(e.getCause() instanceof IOException);
    }

    @Test
    void agendaTemThreadsDaemon() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        Fundo.agenda().schedule(() -> thread.set(Thread.currentThread()), 1, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        assertTrue(thread.get().isDaemon());
        assertTrue(thread.get().getName().startsWith("agenda-"));
    }
}
