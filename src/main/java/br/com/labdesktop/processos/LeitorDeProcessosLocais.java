package br.com.labdesktop.processos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A lista de processos DESTE computador para a aba Processos de "Este computador". O agente que roda no Docker não serve: no Mac e no Windows os
 * processos que ele enxerga são os da máquina virtual do Docker, e um número daquela máquina é de OUTRO processo aqui. Por isso a lista vem do
 * próprio sistema, pelo Java, e o número que a tela mostra é o número deste computador.
 *
 * <p>A CPU é a fração do tempo de processador que o programa usou desde a leitura anterior ({@code null} na primeira vez); a lista vem do mais
 * ocupado para o menos. Memória o Java não informa: {@code null}.
 */
public final class LeitorDeProcessosLocais {

    /** Uma linha da lista. */
    public record Linha(ProcessoAlvo alvo, Double cpu, Long mem) { }

    /** No máximo isto aparece: o computador tem centenas, e a lista é para achar o que se quer encerrar. */
    public static final int MAXIMO = 300;

    private final ProcessosDoSistema sistema;
    private final Supplier<Instant> relogio;
    private final Map<Long, Duration> anterior = new HashMap<>();
    private Instant quandoAnterior;

    public LeitorDeProcessosLocais(ProcessosDoSistema sistema, Supplier<Instant> relogio) {
        this.sistema = sistema;
        this.relogio = relogio;
    }

    public synchronized List<Linha> ler() {
        Instant agora = relogio.get();
        double segundos = quandoAnterior == null ? 0 : Duration.between(quandoAnterior, agora).toMillis() / 1000.0;
        Map<Long, Duration> novo = new HashMap<>();
        List<ProcessosDoSistema.Info> todos = sistema.todos();
        List<Linha> linhas = new ArrayList<>();
        for (ProcessosDoSistema.Info i : todos) {
            if (i.comando().isBlank()) {
                continue;
            }
            Double cpu = null;
            if (i.cpuTotal() != null) {
                novo.put(i.pid(), i.cpuTotal());
                Duration antes = anterior.get(i.pid());
                if (antes != null && segundos > 0.2) {
                    cpu = Math.max(0, (i.cpuTotal().toMillis() - antes.toMillis()) / 1000.0 / segundos * 100.0);
                }
            }
            Integer pid = i.pid() > Integer.MAX_VALUE ? null : (int) i.pid();
            linhas.add(new Linha(new ProcessoAlvo(pid, ProcessoAlvo.nomeCurto(i.comando()), i.usuario(), null, null), cpu, null));
        }
        anterior.clear();
        anterior.putAll(novo);
        quandoAnterior = agora;
        Map<Long, Duration> total = novo;
        linhas.sort(Comparator.comparing((Linha l) -> l.cpu() == null ? -1.0 : l.cpu()).reversed()
            .thenComparing(l -> -total.getOrDefault(l.alvo().pid() == null ? -1L : (long) l.alvo().pid(), Duration.ZERO).toMillis()));
        return linhas.size() > MAXIMO ? List.copyOf(linhas.subList(0, MAXIMO)) : List.copyOf(linhas);
    }
}
