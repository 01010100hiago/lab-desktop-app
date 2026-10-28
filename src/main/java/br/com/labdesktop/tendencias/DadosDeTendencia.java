package br.com.labdesktop.tendencias;

import br.com.labdesktop.api.modelo.HistoricoDoAgente;
import br.com.labdesktop.api.modelo.InfoServidor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * As seis séries de uma janela, já no que o app usa: uma grade regular de células de {@code passo}, a partir de {@code inicio}, com {@code null} onde não há dado. Vem do
 * histórico de longo prazo do agente ({@link #deHistorico}) ou, num agente de antes, dos 10 minutos de {@code /api/servidor} ({@link #dosDezMinutos}), que não tem Disco nem
 * Escrita (essas séries vêm vazias: a tela diz "sem dado ainda", sem erro). Imutável e puro.
 *
 * @param janela   o período pedido
 * @param inicio   o início da primeira célula
 * @param passo    o tamanho de cada célula
 * @param valores  a série de cada métrica (média da célula; o valor instantâneo nos 10 minutos antigos); métrica sem lista = sem dado
 * @param maximos  o maior valor de cada célula, só para as métricas com {@link Metrica#temMaximo()} (e só se o agente mandou)
 * @param quando   o instante da leitura
 * @param deLongoPrazo veio do histórico gravado pelo agente (e não dos 10 minutos antigos)
 */
public record DadosDeTendencia(JanelaDeTempo janela, Instant inicio, Duration passo, Map<Metrica, List<Double>> valores, Map<Metrica, List<Double>> maximos, Instant quando,
                               boolean deLongoPrazo) {

    public DadosDeTendencia {
        Map<Metrica, List<Double>> v = new EnumMap<>(Metrica.class);
        valores.forEach((k, l) -> v.put(k, l == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(l))));
        valores = java.util.Collections.unmodifiableMap(v);
        Map<Metrica, List<Double>> m = new EnumMap<>(Metrica.class);
        maximos.forEach((k, l) -> m.put(k, l == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(l))));
        maximos = java.util.Collections.unmodifiableMap(m);
    }

    /** Sem nada ainda (carregando): as seis séries vazias. */
    public static DadosDeTendencia vazio(JanelaDeTempo janela, Instant agora) {
        return new DadosDeTendencia(janela, agora.minus(janela.duracao()), janela.passoDoAgente(), Map.of(), Map.of(), agora, false);
    }

    /** O histórico de longo prazo do agente. Passo ou início ausentes (agente com defeito): usa o passo esperado da janela e termina no {@code quando}. */
    public static DadosDeTendencia deHistorico(JanelaDeTempo janela, HistoricoDoAgente h, Instant agora) {
        Duration passo = h.passoS() != null && h.passoS() > 0 ? Duration.ofSeconds(h.passoS()) : janela.passoDoAgente();
        int n = h.cpu().size();
        for (List<Double> l : List.of(h.mem(), h.discoUso(), h.discoEscritaBps(), h.rxBps(), h.txBps())) {
            n = Math.max(n, l.size());
        }
        Instant quando = h.quando() != null ? h.quando() : agora;
        Instant inicio = h.inicio() != null ? h.inicio() : quando.minus(passo.multipliedBy(n));
        Map<Metrica, List<Double>> v = new EnumMap<>(Metrica.class);
        v.put(Metrica.CPU, h.cpu());
        v.put(Metrica.MEMORIA, h.mem());
        v.put(Metrica.DISCO, h.discoUso());
        v.put(Metrica.ESCRITA_NO_DISCO, h.discoEscritaBps());
        v.put(Metrica.DOWNLOAD, h.rxBps());
        v.put(Metrica.UPLOAD, h.txBps());
        Map<Metrica, List<Double>> m = new EnumMap<>(Metrica.class);
        m.put(Metrica.CPU, h.cpuMax());
        m.put(Metrica.ESCRITA_NO_DISCO, h.discoEscritaMaxBps());
        return new DadosDeTendencia(janela, inicio, passo, v, m, quando, true);
    }

    /**
     * Os 10 minutos de {@code /api/servidor} (agente de antes do histórico de longo prazo): CPU, Memória, Download e Upload. Sem Disco nem Escrita. O último ponto é o de agora.
     */
    public static DadosDeTendencia dosDezMinutos(InfoServidor.Historico h, Instant quando) {
        Duration passo = h != null && h.passoS() != null && h.passoS() > 0 ? Duration.ofSeconds(h.passoS()) : Duration.ofSeconds(10);
        Map<Metrica, List<Double>> v = new EnumMap<>(Metrica.class);
        int n = 0;
        if (h != null) {
            v.put(Metrica.CPU, h.cpu());
            v.put(Metrica.MEMORIA, h.mem());
            v.put(Metrica.DOWNLOAD, h.rxBps());
            v.put(Metrica.UPLOAD, h.txBps());
            n = Math.max(Math.max(h.cpu().size(), h.mem().size()), Math.max(h.rxBps().size(), h.txBps().size()));
        }
        return new DadosDeTendencia(JanelaDeTempo.DEZ_MINUTOS, quando.minus(passo.multipliedBy(n)), passo, v, Map.of(), quando, false);
    }

    /** A série como lista de {@link Amostra}, com o instante de cada célula (o INÍCIO dela). Vazia se a métrica não veio. */
    public List<Amostra> amostras(Metrica m) {
        return paraAmostras(valores.getOrDefault(m, List.of()));
    }

    /** Os máximos por célula (CPU e escrita), como amostras; vazia se o agente não os mandou. */
    public List<Amostra> amostrasDeMaximo(Metrica m) {
        return paraAmostras(maximos.getOrDefault(m, List.of()));
    }

    private List<Amostra> paraAmostras(List<Double> lista) {
        List<Amostra> saida = new ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            saida.add(new Amostra(inicio.plus(passo.multipliedBy(i)), lista.get(i)));
        }
        return saida;
    }

    /** O fim do período (o fim da última célula). */
    public Instant fim() {
        int n = 0;
        for (List<Double> l : valores.values()) {
            n = Math.max(n, l.size());
        }
        return inicio.plus(passo.multipliedBy(n));
    }

    /** Tem algum valor de alguma métrica? */
    public boolean temAlgumDado() {
        for (Metrica m : Metrica.values()) {
            if (amostras(m).stream().anyMatch(Amostra::valida)) {
                return true;
            }
        }
        return false;
    }

    /** A métrica tem pelo menos um valor? (a tela diz "sem dado ainda" quando não.) */
    public boolean temDado(Metrica m) {
        return amostras(m).stream().anyMatch(Amostra::valida);
    }
}
