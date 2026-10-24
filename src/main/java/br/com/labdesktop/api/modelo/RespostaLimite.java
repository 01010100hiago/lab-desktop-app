package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Resposta de {@code PUT /api/projetos/:p/limite}: {@code {"ok":true,"limite":LIMITE}}. */
public record RespostaLimite(
    @JsonProperty("ok") Boolean ok,
    @JsonProperty("limite") Limite limite) {
}
