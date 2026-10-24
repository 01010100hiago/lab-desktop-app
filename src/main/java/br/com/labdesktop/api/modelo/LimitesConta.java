package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/limites} (CONTRATO-API 3.4): os limites do plano, o freio e as sessões pausadas. Também vem
 * dentro de {@code servidor.limites_plano} em {@code /api/projetos}. Sem leitura válida,
 * {@code disponivel} é {@code false} e só {@code freio}, {@code pausados} e {@code por_projeto} existem.
 *
 * <p>Regras do front que o app repete (ARQUITETURA 8.2): a faixa de crédito aparece com a pior janela a partir
 * de 75 % ({@code perigo} a partir de 90 %), só com {@code disponivel} e sem {@code antiga}. O app NÃO recalcula
 * os 30 min: usa o campo {@code antiga} do agente.
 *
 * @param antiga leitura com mais de 30 min
 */
public record LimitesConta(
    @JsonProperty("disponivel") Boolean disponivel,
    @JsonProperty("antiga") Boolean antiga,
    @JsonProperty("lido_em") Instant lidoEm,
    @JsonProperty("fonte") Fonte fonte,
    @JsonProperty("cinco_horas") Janela cincoHoras,
    @JsonProperty("semana") Janela semana,
    @JsonProperty("leituras") List<Leitura> leituras,
    @JsonProperty("freio") Freio freio,
    @JsonProperty("pausados") List<Pausado> pausados,
    @JsonProperty("por_projeto") Map<String, LimiteProjeto> porProjeto) {

    /** Limiar da faixa de crédito. */
    public static final double LIMIAR_ATENCAO = 75.0;
    /** Limiar de perigo. */
    public static final double LIMIAR_PERIGO = 90.0;

    public LimitesConta {
        leituras = Textos.lista(leituras);
        pausados = Textos.lista(pausados);
        porProjeto = Textos.mapa(porProjeto);
    }

    /** Uma janela do plano: % usado da conta e quando zera. */
    public record Janela(
        @JsonProperty("usado_pct") Double usadoPct,
        @JsonProperty("zera_em") Instant zeraEm) {
    }

    /** Qual sessão gravou a leitura. */
    public record Fonte(
        @JsonProperty("projeto") String projeto,
        @JsonProperty("sessao") String sessao,
        @JsonProperty("modelo") String modelo) {

        public Fonte {
            projeto = Textos.linha(projeto);
            sessao = Textos.linha(sessao);
            modelo = Textos.linha(modelo);
        }
    }

    /** Uma leitura recente de uma sessão. */
    public record Leitura(
        @JsonProperty("projeto") String projeto,
        @JsonProperty("lido_em") Instant lidoEm) {

        public Leitura {
            projeto = Textos.linha(projeto);
        }
    }

    /** O limite de um projeto, como o agente o resume em {@code por_projeto}. */
    public record LimiteProjeto(
        @JsonProperty("usado_pp") Double usadoPp,
        @JsonProperty("teto_pct") Integer tetoPct,
        @JsonProperty("estado") String estado) {

        public LimiteProjeto {
            estado = Textos.linha(estado);
        }
    }

    /** A leitura vale para exibir a faixa de crédito. */
    public boolean leituraUtil() {
        return Boolean.TRUE.equals(disponivel) && !Boolean.TRUE.equals(antiga);
    }

    /** O maior percentual usado entre as duas janelas, ou {@code null} se a leitura não vale. */
    public Double piorJanelaPct() {
        if (!leituraUtil()) {
            return null;
        }
        Double a = cincoHoras == null ? null : cincoHoras.usadoPct();
        Double b = semana == null ? null : semana.usadoPct();
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }

    /** {@code true} se a faixa de crédito deve aparecer (pior janela a partir de 75 %). */
    public boolean faixaDeCredito() {
        Double p = piorJanelaPct();
        return p != null && p >= LIMIAR_ATENCAO;
    }

    /** {@code true} se a faixa deve estar em perigo (a partir de 90 %). */
    public boolean emPerigo() {
        Double p = piorJanelaPct();
        return p != null && p >= LIMIAR_PERIGO;
    }
}
