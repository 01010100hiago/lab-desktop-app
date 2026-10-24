package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** O log de requisições (modo debug): só método, rota, status e tempo, e só quando o usuário liga. */
class LogDeRequisicoesTest {

    private static final String NIVEL = "org.slf4j.simpleLogger.log." + LogDeRequisicoes.NOME_DO_LOGGER;

    private String propriedadeAntes;
    private String nivelAntes;

    private void guardar() {
        propriedadeAntes = System.getProperty(LogDeRequisicoes.PROPRIEDADE);
        nivelAntes = System.getProperty(NIVEL);
    }

    @AfterEach
    void restaurar() {
        // São propriedades globais da JVM de teste.
        restaurar(LogDeRequisicoes.PROPRIEDADE, propriedadeAntes);
        restaurar(NIVEL, nivelAntes);
    }

    private static void restaurar(String chave, String valor) {
        if (valor == null) {
            System.clearProperty(chave);
        } else {
            System.setProperty(chave, valor);
        }
    }

    @Test
    void aLinhaTemMetodoRotaStatusETempoESemAQuery() {
        assertEquals("[lab] GET /api/projetos -> 200 em 54 ms", LogDeRequisicoes.linha("lab", "GET", "/api/projetos", "200", 54));
        assertEquals("[lab] GET /api/projetos/app-loja/logs -> 200 em 7 ms",
            LogDeRequisicoes.linha("lab", "GET", "/api/projetos/app-loja/logs?container=app-loja-web&linhas=500", "200", 7));
        assertEquals("[lab] POST /api/acesso/2fa -> 401 em 3 ms", LogDeRequisicoes.linha("lab", "POST", "/api/acesso/2fa", "401", 3));
    }

    @Test
    void nomeDeServidorComQuebraDeLinhaNaoFabricaOutraLinhaDeLog() {
        String linha = LogDeRequisicoes.linha("lab\n[x] GET /falso -> 200", "GET", "/api/limites", "200", 1);
        assertFalse(linha.contains("\n"), linha);
        assertFalse(LogDeRequisicoes.linha(null, "GET", "/api/limites", "200", 1).contains("null"));
    }

    @Test
    void soLigaComPropriedadeOuVariavelDeAmbienteComValorDeVerdade() {
        assertFalse(LogDeRequisicoes.solicitado(null, null));
        assertFalse(LogDeRequisicoes.solicitado("", ""));
        assertFalse(LogDeRequisicoes.solicitado("false", "0"));
        assertFalse(LogDeRequisicoes.solicitado("  ", " FALSE "));
        assertTrue(LogDeRequisicoes.solicitado("true", null));
        assertTrue(LogDeRequisicoes.solicitado(null, "1"));
        assertTrue(LogDeRequisicoes.solicitado("1", "0"));
    }

    @Test
    void ligarSeSolicitadoPoeSoOLoggerDasRequisicoesEmDebug() {
        guardar();
        System.clearProperty(LogDeRequisicoes.PROPRIEDADE);
        System.clearProperty(NIVEL);
        if (System.getenv(LogDeRequisicoes.VARIAVEL) == null) { // com a variável no ambiente do desenvolvedor, "desligado" não existe
            assertFalse(LogDeRequisicoes.ligarSeSolicitado());
            assertNull(System.getProperty(NIVEL), "desligado por padrão: nenhum nível mexido");
        }

        System.setProperty(LogDeRequisicoes.PROPRIEDADE, "true");
        assertTrue(LogDeRequisicoes.ligarSeSolicitado());
        assertEquals("debug", System.getProperty(NIVEL));
        assertNull(System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"), "o resto do app continua em info");
    }
}
