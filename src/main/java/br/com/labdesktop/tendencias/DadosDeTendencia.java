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
 * @param agora    a ÚLTIMA leitura real de cada métrica (a que o agente acabou de gravar): é o "agora" de cada gráfico, igual aos indicadores da tela. Vazio num agente que não a manda:
 *                 aí o gráfico mostra o último valor da série e o chama de "último", nunca de "agora"
 * @param semRede  "Este computador": a rede que o agente mede é a do CONTAINER dele (o Docker não o liga à rede do computador), então Download e Upload NÃO são do computador e
 *                 ficam de fora (nas telas, no relatório e no CSV)
 */
public record DadosDeTendencia(JanelaDeTempo janela, Instant inicio, Duration passo, Map<Metrica, List<Double>> valores, Map<Metrica, List<Double>> maximos, Instant quando,
                               boolean deLongoPrazo, Map<Metrica, Double> agora, boolean semRede) {

    /** Sem o "agora" real e com a rede (o que os dados de antes tinham). */
    public DadosDeTendencia(JanelaDeTempo janela, Instant inicio, Duration passo, Map<Metrica, List<Double>> valores, Map<Metrica, List<Double>> maximos, Instant quando,
                            boolean deLongoPrazo) {
        this(janela, inicio, passo, valores, maximos, quando, deLongoPrazo, Map.of(), false);
    }

    public DadosDeTendencia {
        Map<Metrica, List<Double>> v = new EnumMap<>(Metrica.class);
        valores.forEach((k, l) -> v.put(k, l == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(l))));
        valores = java.util.Collections.unmodifiableMap(v);
        Map<Metrica, List<Double>> m = new EnumMap<>(Metrica.class);
        maximos.forEach((k, l) -> m.put(k, l == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(l))));
        maximos = java.util.Collections.unmodifiableMap(m);
        Map<Metrica, Double> a = new EnumMap<>(Metrica.class);
        if (agora != null) {
            agora.forEach((k, x) -> {
                if (x != null && !x.isNaN() && !x.isInfinite()) {
                    a.put(k, x);
                }
            });
        }
        agora = java.util.Collections.unmodifiableMap(a);
    }

    /** As métricas que este conjunto de dados mostra, na ordem da grade: as seis, ou as quatro da máquina quando {@code semRede}. */
    public List<Metrica> metricas() {
        List<Metrica> l = new ArrayList<>(List.of(Metrica.values()));
        if (semRede) {
            l.remove(Metrica.DOWNLOAD);
            l.remove(Metrica.UPLOAD);
        }
        return List.copyOf(l);
    }

    /** O mesmo dado com o "agora" trocado por estes valores onde eles existem (a tela usa a leitura que alimenta os indicadores, para o número do gráfico nunca divergir do indicador). */
    public DadosDeTendencia comAgora(Map<Metrica, Double> novos) {
        Map<Metrica, Double> a = new EnumMap<>(Metrica.class);
        a.putAll(agora);
        novos.forEach((m, v) -> {
            if (v != null && temDado(m)) { // métrica sem nenhuma leitura no período continua "sem dado ainda"
                a.put(m, v);
            }
        });
        return new DadosDeTendencia(janela, inicio, passo, valores, maximos, quando, deLongoPrazo, a, semRede);
    }

    /** O mesmo dado para "Este computador": Download e Upload (que seriam os do container do agente, não os do computador) saem. */
    public DadosDeTendencia semAsRedes() {
        Map<Metrica, List<Double>> v = new EnumMap<>(Metrica.class);
        v.putAll(valores);
        v.remove(Metrica.DOWNLOAD);
        v.remove(Metrica.UPLOAD);
        Map<Metrica, Double> a = new EnumMap<>(Metrica.class);
        a.putAll(agora);
        a.remove(Metrica.DOWNLOAD);
        a.remove(Metrica.UPLOAD);
        return new DadosDeTendencia(janela, inicio, passo, v, maximos, quando, deLongoPrazo, a, true);
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
        Map<Metrica, Double> ag = new EnumMap<>(Metrica.class);
        if (h.agora() != null) {
            ag.put(Metrica.CPU, h.agora().cpu());
            ag.put(Metrica.MEMORIA, h.agora().mem());
            ag.put(Metrica.DISCO, h.agora().discoUso());
            ag.put(Metrica.ESCRITA_NO_DISCO, h.agora().discoEscritaBps());
            ag.put(Metrica.DOWNLOAD, h.agora().rxBps());
            ag.put(Metrica.UPLOAD, h.agora().txBps());
        }
        return new DadosDeTendencia(janela, inicio, passo, v, m, quando, true, ag, false);
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
        // nos 10 minutos antigos o último ponto É o de agora (o indicador da tela sai da mesma leitura)
        Map<Metrica, Double> ag = new EnumMap<>(Metrica.class);
        v.forEach((m, lista) -> {
            for (int i = lista.size() - 1; i >= 0; i--) {
                if (lista.get(i) != null) {
                    ag.put(m, lista.get(i));
                    break;
                }
            }
        });
        return new DadosDeTendencia(JanelaDeTempo.DEZ_MINUTOS, quando.minus(passo.multipliedBy(n)), passo, v, Map.of(), quando, false, ag, false);
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
        for (Metrica m : metricas()) {
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
