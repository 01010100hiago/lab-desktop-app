package br.com.labdesktop.teste;

import br.com.labdesktop.api.modelo.HistoricoDoAgente;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * O histórico de longo prazo SINTÉTICO que o agente falso devolve (nada vem de servidor real): a grade de cada janela com o que o agente de verdade entregaria: CPU em ondas e um
 * pico, memória quase fixa, disco subindo devagar (cerca de 1 ponto percentual por dia, para a estimativa ter o que dizer), escrita em picos, e rede com Download maior que Upload.
 * As primeiras células das janelas grandes vêm SEM dado ({@code null}): o agente ainda não gravava naquele começo. O 1 mês cobre a janela inteira só no JSON; o teste vê o que quiser.
 */
public final class HistoricoSintetico {

    private HistoricoSintetico() { }

    /** O JSON do corpo de {@code GET /api/servidor/historico?janela=<codigo>}, como o agente o manda. */
    public static String json(JanelaDeTempo janela, Instant agora) {
        return json(janela, agora, 0);
    }

    /** O mesmo JSON, com as primeiras {@code semDadoNoComeco} células sem dado. */
    public static String json(JanelaDeTempo janela, Instant agora, int semDadoNoComeco) {
        HistoricoDoAgente h = dados(janela, agora, semDadoNoComeco);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"janela\":\"").append(janela.codigo()).append("\",\"passo_s\":").append(h.passoS()).append(",\"inicio\":\"").append(h.inicio()).append("\",\"fim\":\"").append(h.fim())
            .append("\",\"quando\":\"").append(h.quando()).append('"');
        campo(sb, "cpu", h.cpu());
        campo(sb, "cpu_max", h.cpuMax());
        campo(sb, "mem", h.mem());
        campo(sb, "disco_uso", h.discoUso());
        campo(sb, "disco_escrita_bps", h.discoEscritaBps());
        campo(sb, "disco_escrita_max_bps", h.discoEscritaMaxBps());
        campo(sb, "rx_bps", h.rxBps());
        campo(sb, "tx_bps", h.txBps());
        return sb.append('}').toString();
    }

    private static void campo(StringBuilder sb, String nome, List<Double> v) {
        sb.append(",\"").append(nome).append("\":[");
        for (int i = 0; i < v.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(v.get(i) == null ? "null" : String.valueOf(v.get(i)));
        }
        sb.append(']');
    }

    /**
     * @param semDadoNoComeco quantas células do começo vêm {@code null}; zero em todas as janelas pequenas (o agente já gravava) e, nas grandes, o que o teste pedir
     */
    public static HistoricoDoAgente dados(JanelaDeTempo janela, Instant agora, int semDadoNoComeco) {
        int n = janela.pontos();
        long passo = janela.passoDoAgente().toSeconds();
        Instant inicio = agora.minusSeconds(passo * n);
        List<Double> cpu = new ArrayList<>();
        List<Double> cpuMax = new ArrayList<>();
        List<Double> mem = new ArrayList<>();
        List<Double> disco = new ArrayList<>();
        List<Double> escrita = new ArrayList<>();
        List<Double> escritaMax = new ArrayList<>();
        List<Double> rx = new ArrayList<>();
        List<Double> tx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean vazio = i < semDadoNoComeco;
            double onda = Math.sin(i / 6.0);
            cpu.add(vazio ? null : round(0.18 + 0.08 * onda));
            cpuMax.add(vazio ? null : round(i == n / 2 ? 0.91 : 0.35 + 0.10 * onda));
            mem.add(vazio ? null : round(0.58 + 0.01 * Math.sin(i / 20.0)));
            disco.add(vazio ? null : round(0.70 + 0.01 * (i * passo / 86_400.0)));
            escrita.add(vazio ? null : (double) Math.round(900_000 + 600_000 * Math.max(0, onda)));
            escritaMax.add(vazio ? null : (double) Math.round(i == n / 3 ? 41_000_000 : 3_000_000 + 1_000_000 * Math.max(0, onda)));
            rx.add(vazio ? null : (double) Math.round(500_000 + 400_000 * Math.max(0, Math.sin(i / 4.0))));
            tx.add(vazio ? null : (double) Math.round(90_000 + 60_000 * Math.max(0, Math.cos(i / 5.0))));
        }
        return new HistoricoDoAgente(janela.codigo(), (int) passo, inicio, agora, agora, cpu, cpuMax, mem, disco, escrita, escritaMax, rx, tx);
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000d;
    }
}
