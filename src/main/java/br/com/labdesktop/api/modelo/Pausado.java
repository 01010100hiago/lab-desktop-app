package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Um projeto com a sessão pausada ({@code pausados} de {@code /api/limites}). */
public record Pausado(
    @JsonProperty("projeto") String projeto,
    @JsonProperty("motivo") String motivo,
    @JsonProperty("em") Instant em) {

    public Pausado {
        projeto = Textos.linha(projeto);
        motivo = Textos.linha(motivo);
    }
}
