package br.com.labdesktop.ssh;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Descobre que o computador DORMIU quando o sistema não avisa (ARQUITETURA 6.3 e 11.4). No macOS o Java entrega o evento de sono ({@code SystemSleepListener}); no Windows e no Linux ele não
 * existe, e sem aviso o app só perceberia a sessão SSH morta pelo heartbeat, quase um minuto depois de acordar.
 *
 * <p>A ideia mais simples e segura: uma tarefa que anda a cada {@link #INTERVALO} (5 s) e olha o RELÓGIO DE PAREDE. Com o computador dormindo nenhuma thread roda, então ao acordar o relógio
 * andou muito mais que o intervalo: se a diferença entre duas batidas passa de intervalo mais {@link #TOLERANCIA} (15 s), o computador esteve parado (ou o relógio foi mudado à mão: o
 * resultado, reconectar, é o mesmo e inofensivo). Nada de comando, PowerShell nem API nativa. Uma pausa longa de coleta de lixo ou de CPU saturada também dispara: custa uma reconexão.
 * O relógio é injetado ({@code LongSupplier}) e {@link #conferir()} é público para o teste andar sem esperar.
 */
public final class VigiaDeRelogio implements AutoCloseable {

    public static final Duration INTERVALO = Duration.ofSeconds(5);
    public static final Duration TOLERANCIA = Duration.ofSeconds(15);

    private static final Logger LOG = LoggerFactory.getLogger(VigiaDeRelogio.class);

    private final MonitorDeSono monitor;
    private final LongSupplier relogioEmMilissegundos;
    private final long limiteEmMilissegundos;
    private long ultima;
    private ScheduledExecutorService executor;

    public VigiaDeRelogio(MonitorDeSono monitor, LongSupplier relogioEmMilissegundos, Duration intervalo, Duration tolerancia) {
        this.monitor = monitor;
        this.relogioEmMilissegundos = relogioEmMilissegundos;
        this.limiteEmMilissegundos = intervalo.plus(tolerancia).toMillis();
        this.ultima = relogioEmMilissegundos.getAsLong();
    }

    /** O de produção: relógio de parede, 5 s mais 15 s, numa thread de fundo que não segura o app aberto. */
    public static VigiaDeRelogio iniciar(MonitorDeSono monitor) {
        VigiaDeRelogio v = new VigiaDeRelogio(monitor, System::currentTimeMillis, INTERVALO, TOLERANCIA);
        v.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vigia-de-relogio");
            t.setDaemon(true);
            return t;
        });
        v.executor.scheduleWithFixedDelay(v::conferir, INTERVALO.toMillis(), INTERVALO.toMillis(), TimeUnit.MILLISECONDS);
        return v;
    }

    /** Uma batida: se o relógio andou mais que o permitido desde a última, o computador esteve parado. */
    public synchronized void conferir() {
        long agora = relogioEmMilissegundos.getAsLong();
        long passou = agora - ultima;
        ultima = agora;
        if (passou > limiteEmMilissegundos) {
            LOG.info("O relógio andou {} s entre duas batidas de {} s: o computador dormiu.", passou / 1000, INTERVALO.toSeconds());
            monitor.suspensaoDetectada();
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
