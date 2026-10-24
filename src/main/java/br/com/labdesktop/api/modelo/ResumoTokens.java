package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** O consumo resumido que a lista de projetos traz por projeto ({@code tokens}); US$ equivalente de API. */
public record ResumoTokens(
    @JsonProperty("semana_custo_usd") Double semanaCustoUsd,
    @JsonProperty("semana_saida") Long semanaSaida,
    @JsonProperty("hoje_custo_usd") Double hojeCustoUsd) {
}
