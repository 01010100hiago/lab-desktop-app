package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /api/projetos} (CONTRATO-API 3.7): os projetos e o resumo do servidor (com
 * {@code limites_plano}). Custa um {@code docker ps} mais um {@code docker exec} por sessão aberta, por isso só
 * é consultado com a tela visível.
 */
public record ListaProjetos(
    @JsonProperty("projetos") List<Projeto> projetos,
    @JsonProperty("servidor") InfoServidor servidor) {

    public ListaProjetos {
        projetos = Textos.lista(projetos);
    }
}
