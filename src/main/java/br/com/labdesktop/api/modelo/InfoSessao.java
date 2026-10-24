package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Se a sessão do Claude está na imagem atual ({@code sessaoInfo} da lista e {@code sessao.info} do detalhe).
 * {@code faltando} é lista de textos livres ("imagem antiga (Claude desatualizado)"): o app só os mostra.
 */
public record InfoSessao(
    @JsonProperty("atualizada") Boolean atualizada,
    @JsonProperty("faltando") List<String> faltando) {

    public InfoSessao {
        faltando = Textos.linhas(faltando);
    }
}
