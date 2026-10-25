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

    /** {@code GET /api/servidor}, fixture SINTÉTICA (valores inventados) em {@code src/test/resources/servidor}. */
    public static br.com.labdesktop.api.modelo.InfoServidor servidorSintetico() {
        return de(lerRecurso("servidor-sintetico.json"), br.com.labdesktop.api.modelo.InfoServidor.class);
    }

    /** {@code GET /api/servidor/detalhe}, fixture SINTÉTICA. */
    public static br.com.labdesktop.api.modelo.DetalheServidor detalheSintetico() {
        return de(lerRecurso("servidor-detalhe-sintetico.json"), br.com.labdesktop.api.modelo.DetalheServidor.class);
    }

    /** O detalhe do projeto SINTÉTICO {@code orbita} (nada vem de um servidor real). */
    public static br.com.labdesktop.api.modelo.DetalheProjeto detalheDoProjetoSintetico() {
        return de(lerRecursoDoProjeto("detalhe-sintetico.json"), br.com.labdesktop.api.modelo.DetalheProjeto.class);
    }

    /** O consumo do projeto sintético ({@code /api/tokens/orbita}): 30 dias com zeros, 4 modelos, 6 sessões e o Harness. */
    public static br.com.labdesktop.api.modelo.TokensDoProjeto consumoSintetico() {
        return de(lerRecursoDoProjeto("consumo-sintetico.json"), br.com.labdesktop.api.modelo.TokensDoProjeto.class);
    }

    /** A tela do Claude sintética: {@code tela-executando}, {@code tela-pergunta}, {@code tela-esperando} ou {@code tela-login}. */
    public static br.com.labdesktop.api.modelo.TelaClaude telaSintetica(String nome) {
        return de(lerRecursoDoProjeto(nome + ".json"), br.com.labdesktop.api.modelo.TelaClaude.class);
    }

    /** O texto de um arquivo de {@code src/test/resources/projeto}. */
    public static String lerRecursoDoProjeto(String nome) {
        try (java.io.InputStream in = FixturesDeApi.class.getResourceAsStream("/projeto/" + nome)) {
            if (in == null) {
                throw new IllegalStateException("fixture ausente: " + nome);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O texto de um arquivo de {@code src/test/resources/servidor}. */
    public static String lerRecurso(String nome) {
        try (java.io.InputStream in = FixturesDeApi.class.getResourceAsStream("/servidor/" + nome)) {
            if (in == null) {
                throw new IllegalStateException("fixture ausente: " + nome);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
