package br.com.labdesktop.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vigia de latência da EDT, SÓ para desenvolvimento (ARQUITETURA 6.6): a cada {@code periodo} posta um
 * {@code Runnable} na EDT (por {@link Fundo#naEdt}); se ele ainda não rodou passado o {@code limite}, a EDT
 * está presa e o stack dela naquele instante vai para o log. Pega o que faz a janela engasgar: E/S, KDF ou
 * {@code sleep} na EDT, que são proibidos (ARQUITETURA 1.3, princípio 2).
 *
 * <p>Não importa Swing: descobre a thread da EDT pelo primeiro {@code Runnable} que roda nela (ou, antes
 * disso, pelo nome {@code AWT-EventQueue-*}). O tempo vem de um {@link Agendador}, então os testes não
 * esperam de verdade.
 */
public final class EdtWatchdog implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EdtWatchdog.class);
    private static final int MAX_FRAMES = 30;

    /** Intervalo entre sondagens e limite de latência do projeto (ARQUITETURA 6.6): 1 s e 500 ms. */
    public static final Duration PERIODO_PADRAO = Duration.ofSeconds(1);
    public static final Duration LIMITE_PADRAO = Duration.ofMillis(500);

    private final Agendador agendador;
    private final Duration periodo;
    private final Duration limite;
    private final Consumer<String> aoTravar;

    private final AtomicReference<Thread> edt = new AtomicReference<>();
    private final AtomicBoolean travadaJaRelatada = new AtomicBoolean();
    private volatile boolean ligado;
    private volatile Agendador.Cancelavel proximo;

    public EdtWatchdog(Agendador agendador, Duration periodo, Duration limite, Consumer<String> aoTravar) {
        if (periodo.isZero() || periodo.isNegative() || limite.isZero() || limite.isNegative()) {
            throw new IllegalArgumentException("periodo e limite devem ser positivos");
        }
        this.agendador = agendador;
        this.periodo = periodo;
        this.limite = limite;
        this.aoTravar = aoTravar;
    }

    /** Com os tempos do projeto, relógio do sistema e o relatório indo para o log em WARN. */
    public static EdtWatchdog emDesenvolvimento() {
        return new EdtWatchdog(Agendador.doFundo(), PERIODO_PADRAO, LIMITE_PADRAO, LOG::warn);
    }

    /** Começa a vigiar. Idempotente. */
    public synchronized void iniciar() {
        if (!ligado) {
            ligado = true;
            tique();
        }
    }

    @Override
    public synchronized void close() {
        ligado = false;
        Agendador.Cancelavel c = proximo;
        if (c != null) {
            c.cancelar();
        }
    }

    private synchronized void tique() {
        if (!ligado) {
            return;
        }
        AtomicBoolean rodou = new AtomicBoolean();
        Fundo.naEdt(() -> {
            edt.compareAndSet(null, Thread.currentThread());
            rodou.set(true);
            travadaJaRelatada.set(false);
        });
        agendador.agendar(limite, () -> conferir(rodou));
        proximo = agendador.agendar(periodo, this::tique);
    }

    private void conferir(AtomicBoolean rodou) {
        if (!ligado || rodou.get() || !travadaJaRelatada.compareAndSet(false, true)) {
            return; // rodou a tempo, ou esta mesma trava já foi relatada
        }
        Thread t = edt.get();
        if (t == null) {
            t = Thread.getAllStackTraces().keySet().stream()
                .filter(x -> x.getName().startsWith("AWT-EventQueue-")).findFirst().orElse(null);
        }
        String pilha = t == null
            ? "(thread da EDT desconhecida)"
            : Arrays.stream(t.getStackTrace()).limit(MAX_FRAMES).map(f -> "    at " + f)
                .reduce((a, b) -> a + "\n" + b).orElse("    (sem frames)");
        aoTravar.accept("A EDT não respondeu em " + limite.toMillis() + " ms. Pilha dela agora:\n" + pilha);
    }
}
