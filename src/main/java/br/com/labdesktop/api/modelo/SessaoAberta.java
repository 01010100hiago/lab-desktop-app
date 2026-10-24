package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Resposta de {@code claude/abrir} e {@code claude/reabrir}: {@code {"ok":true,"url":"/sessao/<p>"}} (a URL é do front web e não é usada). */
public record SessaoAberta(
    @JsonProperty("ok") Boolean ok,
    @JsonProperty("url") String url) {

    public SessaoAberta {
        url = Textos.linha(url);
    }
}
