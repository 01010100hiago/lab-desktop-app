package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Resposta de {@code iniciar}, {@code parar} e {@code reiniciar}: {@code {"ok":true,"saida":"[docker-compose.yml] ..."}}. */
public record ResultadoAcao(
    @JsonProperty("ok") Boolean ok,
    @JsonProperty("saida") String saida) {

    public ResultadoAcao {
        saida = Textos.bloco(saida);
    }
}
