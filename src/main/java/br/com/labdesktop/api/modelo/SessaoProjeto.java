package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A sessão do Claude de um projeto no detalhe: {@code sessao} do projeto comum e {@code sessaoClaude} da raiz.
 * {@code url} é o caminho do front web ({@code /sessao/<p>}) e o app não o usa (abre o terminal por SSH).
 */
public record SessaoProjeto(
    @JsonProperty("aberta") Boolean aberta,
    @JsonProperty("container") String container,
    @JsonProperty("desde") Instant desde,
    @JsonProperty("url") String url,
    @JsonProperty("info") InfoSessao info) {

    public SessaoProjeto {
        container = Textos.linha(container);
        url = Textos.linha(url);
    }

    public boolean estaAberta() {
        return Boolean.TRUE.equals(aberta);
    }
}
