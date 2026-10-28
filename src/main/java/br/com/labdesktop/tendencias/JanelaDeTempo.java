package br.com.labdesktop.tendencias;

import java.time.Duration;
import java.util.Optional;

/**
 * Os cinco períodos das Tendências, com o código que o agente entende em {@code GET /api/servidor/historico?janela=}: 10 minutos, 1 hora, 1 dia, 1 semana e 1 mês (30 dias).
 * Puro: sem Swing e sem rede.
 */
public enum JanelaDeTempo {

    DEZ_MINUTOS("10m", "10 min", "últimos 10 minutos", Duration.ofMinutes(10), Duration.ofSeconds(10), Duration.ofSeconds(10)),
    UMA_HORA("1h", "1 hora", "última hora", Duration.ofHours(1), Duration.ofSeconds(20), Duration.ofSeconds(30)),
    UM_DIA("1d", "1 dia", "último dia", Duration.ofDays(1), Duration.ofMinutes(5), Duration.ofSeconds(60)),
    UMA_SEMANA("7d", "1 semana", "última semana", Duration.ofDays(7), Duration.ofMinutes(40), Duration.ofMinutes(5)),
    UM_MES("30d", "1 mês", "último mês", Duration.ofDays(30), Duration.ofHours(3), Duration.ofMinutes(10));

    private final String codigo;
    private final String rotuloCurto;
    private final String rotuloLongo;
    private final Duration duracao;
    private final Duration passoDoAgente;
    private final Duration cadencia;

    JanelaDeTempo(String codigo, String rotuloCurto, String rotuloLongo, Duration duracao, Duration passoDoAgente, Duration cadencia) {
        this.codigo = codigo;
        this.rotuloCurto = rotuloCurto;
        this.rotuloLongo = rotuloLongo;
        this.duracao = duracao;
        this.passoDoAgente = passoDoAgente;
        this.cadencia = cadencia;
    }

    /** O código do agente ({@code 10m}, {@code 1h}, {@code 1d}, {@code 7d}, {@code 30d}). */
    public String codigo() {
        return codigo;
    }

    /** O rótulo do botão do seletor ("10 min", "1 hora"...). */
    public String rotuloCurto() {
        return rotuloCurto;
    }

    /** O período por extenso, para frases ("últimos 10 minutos", "última semana"). */
    public String rotuloLongo() {
        return rotuloLongo;
    }

    public Duration duracao() {
        return duracao;
    }

    /** O passo de cada ponto que o agente devolve nesta janela (a grade tem {@code duracao / passo} pontos, nunca mais de 300). */
    public Duration passoDoAgente() {
        return passoDoAgente;
    }

    /**
     * De quanto em quanto tempo vale pedir de novo com a tela visível: o agente só tem ponto novo a cada passo da faixa (10 s, 1 min, 10 min, 1 h), então pedir um mês a cada 10 s seria
     * só carga. Sem foco o {@code Sondador} multiplica por 6.
     */
    public Duration cadenciaDeConsulta() {
        return cadencia;
    }

    /** Quantos pontos a grade tem. */
    public int pontos() {
        return (int) (duracao.toSeconds() / passoDoAgente.toSeconds());
    }

    /** O padrão do seletor: os últimos 10 minutos. */
    public static JanelaDeTempo padrao() {
        return DEZ_MINUTOS;
    }

    /** A janela de um código (da memória do app ou do agente); vazio se não é uma das cinco. */
    public static Optional<JanelaDeTempo> doCodigo(String codigo) {
        if (codigo != null) {
            for (JanelaDeTempo j : values()) {
                if (j.codigo.equals(codigo)) {
                    return Optional.of(j);
                }
            }
        }
        return Optional.empty();
    }

    /** O que é maior que um dia (a estimativa do disco só vale com pelo menos 24 h de dados). */
    public boolean cobreUmDiaOuMais() {
        return duracao.compareTo(Duration.ofDays(1)) >= 0;
    }
}
