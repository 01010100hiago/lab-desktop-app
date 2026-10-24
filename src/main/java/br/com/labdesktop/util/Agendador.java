package br.com.labdesktop.util;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relógio e timer injetáveis. Existe para o {@code Sondador} e a {@code ConexaoSsh} poderem ser testados com
 * tempo falso: um teste de "dobra o intervalo depois de 3 falhas" não pode esperar 60 segundos de verdade.
 *
 * <p>Regra de uso (ARQUITETURA 6.6): a tarefa agendada só DISPARA; o trabalho de verdade (rede, disco) vai
 * para {@link Fundo#ioVirtual()}. Os timers rodam nas duas threads do pool {@code agenda}, que ficam presas
 * se alguém bloquear nelas.
 */
public interface Agendador {

    /** O instante atual segundo este agendador (o do sistema, ou o relógio falso do teste). */
    Instant agora();

    /** Roda {@code tarefa} depois de {@code atraso} (zero ou negativo = o quanto antes). */
    Cancelavel agendar(Duration atraso, Runnable tarefa);

    /** Um timer que ainda pode ser desfeito. */
    @FunctionalInterface
    interface Cancelavel {
        /** Desfaz o timer se ainda não disparou. Idempotente. */
        void cancelar();
    }

    /** O agendador de produção: relógio do sistema e o pool {@code agenda} de {@link Fundo}. */
    static Agendador doFundo() {
        return DoFundo.INSTANCIA;
    }

    /** Implementação de produção. Fica aqui dentro para a interface não vazar detalhe de executor. */
    final class DoFundo implements Agendador {

        private static final Logger LOG = LoggerFactory.getLogger(Agendador.class);
        private static final DoFundo INSTANCIA = new DoFundo();

        private DoFundo() { }

        @Override
        public Instant agora() {
            return Instant.now();
        }

        @Override
        public Cancelavel agendar(Duration atraso, Runnable tarefa) {
            long ms = Math.max(0, atraso.toMillis());
            ScheduledFuture<?> futuro = Fundo.agenda().schedule(() -> {
                try {
                    tarefa.run();
                } catch (RuntimeException e) {
                    // Uma tarefa que lança não pode matar a thread do pool (são só duas).
                    LOG.warn("Tarefa agendada falhou: {}", e.toString());
                }
            }, ms, TimeUnit.MILLISECONDS);
            return () -> futuro.cancel(false);
        }
    }
}
