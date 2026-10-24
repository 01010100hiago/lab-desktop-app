package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Por que e desde quando uma sessão está pausada pelo limite: {@code motivo} é {@code teto} ou {@code freio}. */
public record Pausa(
    @JsonProperty("motivo") String motivo,
    @JsonProperty("em") Instant em) {

    public Pausa {
        motivo = Textos.linha(motivo);
    }
}
