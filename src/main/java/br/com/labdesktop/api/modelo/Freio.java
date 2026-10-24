package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** O freio global da conta (CONTRATO-API 3.4): desligado vem como {@code pct: null, ativo: false}. */
public record Freio(
    @JsonProperty("pct") Integer pct,
    @JsonProperty("ativo") Boolean ativo) {
}
