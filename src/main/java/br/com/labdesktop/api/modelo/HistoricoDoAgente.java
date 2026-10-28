package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/servidor/historico?janela=} (CONTRATO-API 3.2.1): o histórico de métricas que o agente grava, de 10 minutos a 1 mês, numa GRADE
 * regular: um valor por célula de {@code passo_s} segundos a partir de {@code inicio}, {@code null} onde não há dado. As frações ({@code cpu}, {@code mem},
 * {@code disco_uso}) vão de 0 a 1; as taxas ({@code disco_escrita_bps}, {@code rx_bps}, {@code tx_bps}) são BYTES por segundo. {@code cpu_max} e
 * {@code disco_escrita_max_bps} são o maior valor visto em cada célula (as outras séries são a média). Toda lista pode faltar (agente que não manda o campo): vira vazia.
 */
public record HistoricoDoAgente(
    @JsonProperty("janela") String janela,
    @JsonProperty("passo_s") Integer passoS,
    @JsonProperty("inicio") Instant inicio,
    @JsonProperty("fim") Instant fim,
    @JsonProperty("quando") Instant quando,
    @JsonProperty("cpu") List<Double> cpu,
    @JsonProperty("cpu_max") List<Double> cpuMax,
    @JsonProperty("mem") List<Double> mem,
    @JsonProperty("disco_uso") List<Double> discoUso,
    @JsonProperty("disco_escrita_bps") List<Double> discoEscritaBps,
    @JsonProperty("disco_escrita_max_bps") List<Double> discoEscritaMaxBps,
    @JsonProperty("rx_bps") List<Double> rxBps,
    @JsonProperty("tx_bps") List<Double> txBps,
    @JsonProperty("agora") Agora agora) {

    /**
     * A ÚLTIMA leitura real que o agente gravou (não a média da última célula, que num período de um mês cobre 3 horas): é o "agora" dos gráficos, o mesmo valor dos indicadores.
     * Agente de antes desta correção não manda o campo ({@code null}).
     */
    public record Agora(
        @JsonProperty("t") Instant t,
        @JsonProperty("cpu") Double cpu,
        @JsonProperty("mem") Double mem,
        @JsonProperty("disco_uso") Double discoUso,
        @JsonProperty("disco_escrita_bps") Double discoEscritaBps,
        @JsonProperty("rx_bps") Double rxBps,
        @JsonProperty("tx_bps") Double txBps) { }

    /** Sem o campo {@code agora} (agente anterior, e os testes de antes). */
    public HistoricoDoAgente(String janela, Integer passoS, Instant inicio, Instant fim, Instant quando, List<Double> cpu, List<Double> cpuMax, List<Double> mem,
                             List<Double> discoUso, List<Double> discoEscritaBps, List<Double> discoEscritaMaxBps, List<Double> rxBps, List<Double> txBps) {
        this(janela, passoS, inicio, fim, quando, cpu, cpuMax, mem, discoUso, discoEscritaBps, discoEscritaMaxBps, rxBps, txBps, null);
    }

    public HistoricoDoAgente {
        janela = Textos.linha(janela);
        cpu = vazia(cpu);
        cpuMax = vazia(cpuMax);
        mem = vazia(mem);
        discoUso = vazia(discoUso);
        discoEscritaBps = vazia(discoEscritaBps);
        discoEscritaMaxBps = vazia(discoEscritaMaxBps);
        rxBps = vazia(rxBps);
        txBps = vazia(txBps);
    }

    private static List<Double> vazia(List<Double> l) {
        return l == null ? List.of() : l; // pode ter null no meio: célula sem dado
    }
}
