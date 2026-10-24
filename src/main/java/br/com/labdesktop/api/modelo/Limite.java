package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * O teto semanal de um projeto (CONTRATO-API 3.7, {@code LIMITE}).
 *
 * @param tetoPct  teto em pontos percentuais da semana da conta; {@code null} sem teto
 * @param usadoPp  pontos percentuais da semana já consumidos (fatia ESTIMADA)
 * @param estado   {@code ok}, {@code atencao} ou {@code pausado}
 * @param liberado isento de teto e freio até a semana virar
 */
public record Limite(
    @JsonProperty("teto_pct") Integer tetoPct,
    @JsonProperty("usado_pp") Double usadoPp,
    @JsonProperty("estado") String estado,
    @JsonProperty("liberado") Boolean liberado,
    @JsonProperty("pausa") Pausa pausa,
    @JsonProperty("zera_em") Instant zeraEm,
    @JsonProperty("desde") Instant desde) {

    public Limite {
        estado = Textos.linha(estado);
    }

    public boolean pausado() {
        return "pausado".equals(estado);
    }

    /** Fração do teto já usada (0 a 1 ou mais), ou {@code null} sem teto ou sem leitura. */
    public Double fracaoDoTeto() {
        if (tetoPct == null || tetoPct <= 0 || usadoPp == null) {
            return null;
        }
        return usadoPp / tetoPct;
    }
}
