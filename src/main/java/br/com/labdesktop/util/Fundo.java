package br.com.labdesktop.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Onde o trabalho roda (ARQUITETURA 6.6): rede e disco em threads virtuais, timers num pool pequeno e
 * qualquer toque em componente Swing na EDT.
 *
 * <p>Este pacote não importa Swing (regra de camadas, ARQUITETURA 4.3). O despacho para a EDT entra pelo
 * {@link #definirEdt(Executor)}, que o {@code Principal} liga a {@code SwingUtilities::invokeLater}.
 * Sem ninguém ter ligado (testes), {@link #naEdt(Runnable)} executa na própria thread de quem chamou:
 * assim a lógica de domínio roda em teste sem janela. Isso esconde bug de thread, por isso os testes de
 * tela usam o despachante real.
 */
public final class Fundo {

    private static final AtomicReference<Executor> EDT = new AtomicReference<>(Runnable::run);
    private static final AtomicInteger CONTADOR_AGENDA = new AtomicInteger();

    // Preguiçosos: o primeiro uso cria; quem nunca agenda nada não paga as duas threads.
    private static volatile ExecutorService ioVirtual;
    private static volatile ScheduledExecutorService agenda;

    private Fundo() { }

    /** Liga o despachante da EDT. Em produção: {@code SwingUtilities::invokeLater}. */
    public static void definirEdt(Executor despachante) {
        EDT.set(java.util.Objects.requireNonNull(despachante, "despachante"));
    }

    /** Roda na EDT (ou onde o despachante injetado mandar). Nunca bloqueia quem chama. */
    public static void naEdt(Runnable tarefa) {
        EDT.get().execute(tarefa);
    }

    /**
     * Executor de threads virtuais para E/S bloqueante ({@code ClienteApi}, {@code ExecRemoto}, consultas do
     * Sondador). Bloquear aqui é permitido e barato. Um executor só, compartilhado.
     */
    public static ExecutorService ioVirtual() {
        ExecutorService e = ioVirtual;
        if (e == null) {
            synchronized (Fundo.class) {
                e = ioVirtual;
                if (e == null) {
                    // Nome "io-virtual-N" para aparecer em dump de threads e no log.
                    ThreadFactory fabrica = Thread.ofVirtual().name("io-virtual-", 0).factory();
                    e = Executors.newThreadPerTaskExecutor(fabrica);
                    ioVirtual = e;
                }
            }
        }
        return e;
    }

    /**
     * Pool de 2 threads daemon para timers (polling do Sondador, backoff). Só dispara: o trabalho de
     * verdade vai para {@link #ioVirtual()}.
     */
    public static ScheduledExecutorService agenda() {
        ScheduledExecutorService e = agenda;
        if (e == null) {
            synchronized (Fundo.class) {
                e = agenda;
                if (e == null) {
                    e = Executors.newScheduledThreadPool(2, r -> {
                        Thread t = new Thread(r, "agenda-" + CONTADOR_AGENDA.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    agenda = e;
                }
            }
        }
        return e;
    }

    /** Roda em thread virtual. O futuro completa com erro se a tarefa lançar. */
    public static CompletableFuture<Void> emFundo(Runnable tarefa) {
        return CompletableFuture.runAsync(tarefa, ioVirtual());
    }

    /**
     * Roda em thread virtual e devolve o resultado. Nome diferente de {@link #emFundo(Runnable)} de propósito:
     * com os dois sobrecarregados, uma lambda de uma linha fica ambígua para quem lê.
     */
    public static <T> CompletableFuture<T> emFundoComResultado(Callable<T> tarefa) {
        CompletableFuture<T> futuro = new CompletableFuture<>();
        ioVirtual().execute(() -> {
            try {
                futuro.complete(tarefa.call());
            } catch (Throwable t) {
                futuro.completeExceptionally(t);
            }
        });
        return futuro;
    }

    /**
     * Espera o futuro SEM embrulhar a causa em {@link CompletionException}. Só para thread de fundo.
     * Lança a exceção original se for não verificada; senão, embrulha em {@link RuntimeException}.
     */
    public static <T> T aguardar(CompletableFuture<T> futuro) {
        try {
            return futuro.join();
        } catch (CompletionException e) {
            Throwable causa = e.getCause() != null ? e.getCause() : e;
            if (causa instanceof RuntimeException re) {
                throw re;
            }
            if (causa instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(causa);
        }
    }

    /** Derruba os executores (encerramento do app e fim de testes). Recriados sob demanda no próximo uso. */
    public static void encerrar() {
        synchronized (Fundo.class) {
            if (ioVirtual != null) {
                ioVirtual.shutdownNow();
                ioVirtual = null;
            }
            if (agenda != null) {
                agenda.shutdownNow();
                agenda = null;
            }
        }
    }
}
