package br.com.labdesktop.teste;

import br.com.labdesktop.util.Agendador;
import java.time.Duration;
import java.time.Instant;
import java.util.PriorityQueue;

/**
 * Relógio e timers de mentira: o tempo só anda quando o teste manda ({@link #avancar}). As tarefas rodam na
 * thread do teste, em ordem de vencimento, então um teste de "dobra o intervalo depois de 3 falhas" é
 * determinístico e instantâneo.
 */
public final class AgendadorFalso implements Agendador {

    private static final class Tarefa implements Comparable<Tarefa> {
        final Instant vence;
        final long ordem;
        final Runnable acao;
        boolean cancelada;

        Tarefa(Instant vence, long ordem, Runnable acao) {
            this.vence = vence;
            this.ordem = ordem;
            this.acao = acao;
        }

        @Override
        public int compareTo(Tarefa o) {
            int c = vence.compareTo(o.vence);
            return c != 0 ? c : Long.compare(ordem, o.ordem);
        }
    }

    private final PriorityQueue<Tarefa> fila = new PriorityQueue<>();
    private Instant agora = Instant.parse("2026-10-20T12:00:00Z");
    private long contador;

    @Override
    public synchronized Instant agora() {
        return agora;
    }

    @Override
    public synchronized Cancelavel agendar(Duration atraso, Runnable tarefa) {
        Tarefa t = new Tarefa(agora.plus(atraso.isNegative() ? Duration.ZERO : atraso), contador++, tarefa);
        fila.add(t);
        return () -> {
            synchronized (AgendadorFalso.this) {
                t.cancelada = true;
            }
        };
    }

    /** Anda o relógio, rodando em ordem tudo o que vence até o novo instante (inclusive o que for agendado no caminho). */
    public void avancar(Duration d) {
        Instant alvo;
        synchronized (this) {
            alvo = agora.plus(d);
        }
        while (true) {
            Tarefa t;
            synchronized (this) {
                t = fila.peek();
                if (t == null || t.vence.isAfter(alvo)) {
                    agora = alvo;
                    return;
                }
                fila.poll();
                if (t.cancelada) {
                    continue;
                }
                if (t.vence.isAfter(agora)) {
                    agora = t.vence;
                }
            }
            t.acao.run();
        }
    }

    /** Roda o que já venceu agora (sem andar o relógio). */
    public void executarVencidas() {
        avancar(Duration.ZERO);
    }

    /** Quantos timers ainda esperam (os cancelados não contam). */
    public synchronized int pendentes() {
        return (int) fila.stream().filter(t -> !t.cancelada).count();
    }
}
