package br.com.labdesktop.api;

import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.teste.MocksDoFront;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Os DTOs do agente a partir de JSON, com o MESMO mapper tolerante do {@code ClienteApi} (o {@code ContratoJson} é
 * do pacote {@code api}; este auxiliar de teste mora aqui para alcançá-lo). Alimenta os testes de tela com dados
 * reais de forma, sem subir servidor.
 */
public final class FixturesDeApi {

    private FixturesDeApi() { }

    /** Interpreta um JSON como o DTO {@code tipo}. */
    public static <T> T de(String json, Class<T> tipo) {
        try {
            return ContratoJson.mapper().readValue(json, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON de teste inválido para " + tipo.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** {@code GET /api/projetos} do mock do front (5 projetos, com teto, atenção e pausados). */
    public static ListaProjetos projetosDoMock() {
        return de(MocksDoFront.ler("projetos.json"), ListaProjetos.class);
    }

    /** {@code GET /api/projetos/lab} do mock. */
    public static DetalheRaiz raizDoMock() {
        return de(MocksDoFront.ler("projetos/lab.json"), DetalheRaiz.class);
    }

    /** {@code GET /api/limites} do mock. */
    public static LimitesConta limitesDoMock() {
        return de(MocksDoFront.ler("limites.json"), LimitesConta.class);
    }
}
