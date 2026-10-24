package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Resposta 200 de {@code POST /api/projetos/:p/limite/retomar} (CONTRATO-API 4.10). {@code sessao} é {@code null}
 * com {@code reabrir: false}. O 409 "ainda acima do limite" NÃO vem aqui: é um {@code ErroApi.Conflito}.
 */
public record RespostaRetomar(
    @JsonProperty("ok") Boolean ok,
    @JsonProperty("limite") Limite limite,
    @JsonProperty("sessao") SessaoReaberta sessao) {

    /** A sessão que o Retomar reabriu (ou a que já existia). */
    public record SessaoReaberta(
        @JsonProperty("aberta") Boolean aberta,
        @JsonProperty("container") String container,
        @JsonProperty("url") String url) {

        public SessaoReaberta {
            container = Textos.linha(container);
            url = Textos.linha(url);
        }
    }
}
