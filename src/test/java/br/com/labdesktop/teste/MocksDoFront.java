package br.com.labdesktop.teste;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Carrega os JSONs copiados do mock do front web ({@code src/test/resources/mocks-do-front}). Servem para provar
 * a TOLERÂNCIA do cliente às divergências do mock em relação ao agente real. Não são o contrato.
 */
public final class MocksDoFront {

    private MocksDoFront() { }

    public static String ler(String caminho) {
        try (InputStream in = MocksDoFront.class.getResourceAsStream("/mocks-do-front/" + caminho)) {
            if (in == null) {
                throw new IllegalStateException("mock ausente: " + caminho);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
