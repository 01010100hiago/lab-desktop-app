package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.ErroApi.NaoEncontrado;
import br.com.labdesktop.api.ErroApi.RespostaInvalida;
import br.com.labdesktop.api.ErroApi.ServidorErro;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pequenos comportamentos do modelo e da hierarquia de erros (ARQUITETURA 6.5). */
class ModeloEErroApiTest {

    private static JsonNode json(String texto) throws Exception {
        return Json.mapper().readTree(texto);
    }

    @Test
    void versaoIncompativelOuAusenteNaoEhCompativel() {
        assertTrue(new VersaoAgente(1, "v22", false, null, "desligado").compativel());
        assertFalse(new VersaoAgente(2, "v22", false, null, "desligado").compativel());
        assertFalse(new VersaoAgente(null, null, null, null, null).compativel());
    }

    @Test
    void versaoComPerfilERecursosParseiaOsDoisCasosENuncaEscondeSemOAgenteDizer() throws Exception {
        VersaoAgente monitor = Json.mapper().readValue("{\"api\":1,\"painel\":\"v22\",\"exigeToken\":false,\"cliente\":null,\"totp\":\"desligado\","
            + "\"perfil\":\"monitor\",\"recursos\":{\"claude\":false,\"projetos\":false,\"monitor\":true},\"agente\":\"0.1.0-abc1234\"}", VersaoAgente.class);
        assertTrue(monitor.perfilMonitor());
        assertEquals(Boolean.FALSE, monitor.temClaude());
        assertFalse(monitor.temProjetos());
        assertEquals("0.1.0-abc1234", monitor.agente());
        assertEquals(br.com.labdesktop.api.modelo.RecursosDoAgente.SO_MONITOR, monitor.recursos());

        VersaoAgente lab = Json.mapper().readValue("{\"api\":1,\"painel\":\"v22\",\"perfil\":\"lab\",\"recursos\":{\"claude\":true,\"projetos\":true,\"monitor\":true},"
            + "\"agente\":null}", VersaoAgente.class);
        assertFalse(lab.perfilMonitor());
        assertEquals(Boolean.TRUE, lab.temClaude());
        assertTrue(lab.temProjetos());

        // agente de antes dos recursos: o app assume o comportamento de antes (desconhecido nunca esconde nada)
        VersaoAgente antigo = Json.mapper().readValue("{\"api\":1,\"painel\":\"v22\",\"exigeToken\":false,\"cliente\":null,\"totp\":\"desligado\"}", VersaoAgente.class);
        assertNull(antigo.perfil());
        assertNull(antigo.recursos());
        assertNull(antigo.temClaude());
        assertTrue(antigo.temProjetos());
        assertFalse(antigo.perfilMonitor());

        // recursos parcial: o que falta é desconhecido
        VersaoAgente parcial = Json.mapper().readValue("{\"api\":1,\"recursos\":{\"monitor\":true}}", VersaoAgente.class);
        assertNull(parcial.temClaude());
        assertTrue(parcial.temProjetos());
        // campo desconhecido de um agente mais novo não derruba
        VersaoAgente novo = Json.mapper().readValue("{\"api\":1,\"recursos\":{\"claude\":true,\"coisa-nova\":1},\"outro\":2}", VersaoAgente.class);
        assertEquals(Boolean.TRUE, novo.temClaude());
        // texto do servidor é limpo na construção
        assertEquals("monitor", new VersaoAgente(1, "v", false, null, "x", "monitor\u202E", null, null).perfil());
    }

    @Test
    void fracaoDoTetoSoComTetoELeitura() {
        assertEquals(0.5, new Limite(20, 10.0, "ok", false, null, null, null).fracaoDoTeto());
        assertNull(new Limite(null, 10.0, "ok", false, null, null, null).fracaoDoTeto());
        assertNull(new Limite(20, null, "ok", false, null, null, null).fracaoDoTeto());
        assertNull(new Limite(0, 5.0, "ok", false, null, null, null).fracaoDoTeto());
        assertTrue(new Limite(20, 22.0, "pausado", false, null, null, Instant.EPOCH).pausado());
    }

    @Test
    void mensagemDeErroVemLimpaERedigida() throws Exception {
        JsonNode corpo = json("{\"erro\":\"<html><b>falhou</b> Authorization: Bearer lab_" + "Q".repeat(43) + "\\u202E\"}");
        ServidorErro e = new ServidorErro(500, corpo, corpo.get("erro").asText());
        assertFalse(br.com.labdesktop.seguranca.TextoSeguro.pareceHtml(e.mensagem()), e.mensagem());
        assertFalse(e.mensagem().contains("QQQQQQ"));
        assertFalse(e.mensagem().contains("\u202E"));
        assertEquals(500, e.status());
    }

    @Test
    void mensagemVaziaViraTextoPadraoELongaEhTruncada() {
        assertEquals("Erro na chamada ao agente.", new ServidorErro(500, null, "  ").mensagem());
        assertTrue(new ServidorErro(500, null, "x".repeat(5000)).mensagem().length() <= 300);
    }

    @Test
    void naoAutorizadoDistingueTokenRecusadoDeCodigoDoAutenticador() throws Exception {
        NaoAutorizado token = new NaoAutorizado(json("{\"erro\":\"Token ausente ou inválido.\"}"), "Token ausente ou inválido.");
        assertTrue(token.tokenRecusado());
        assertFalse(token.totpRequerido());
        NaoAutorizado totp = new NaoAutorizado(json("{\"codigo\":\"totp_requerido\"}"), "Confirme o código do autenticador.");
        assertTrue(totp.totpRequerido());
        assertFalse(totp.tokenRecusado());
        assertEquals(401, totp.status());
    }

    @Test
    void conflitoLeOsNumerosDoCorpoETolerarAusencia() throws Exception {
        Conflito c = new Conflito(json("{\"erro\":\"x\",\"usado_pp\":22.4,\"teto_pct\":20,\"pct_conta\":92,\"freio_pct\":null}"), "x");
        assertEquals(22.4, c.usadoPp());
        assertEquals(20, c.tetoPct());
        assertEquals(92.0, c.pctConta());
        assertNull(c.freioPct(), "freio desligado vem como null");
        assertTrue(c.aindaEstoura());
        assertFalse(new Conflito(null, "sem corpo").aindaEstoura());
        assertFalse(new Conflito(json("[1]"), "array").aindaEstoura());
    }

    @Test
    void servidorErroLeOAguardeEOCalculando() throws Exception {
        ServidorErro bloqueio = new ServidorErro(429, json("{\"codigo\":\"totp_bloqueado\",\"aguarde_s\":60}"), "Muitas tentativas.");
        assertEquals(60, bloqueio.aguardeSegundos());
        assertEquals(0, new ServidorErro(429, null, "x").aguardeSegundos());
        assertTrue(new ServidorErro(503, null, "calculando").calculando());
        assertFalse(new ServidorErro(500, null, "x").calculando());
    }

    @Test
    void naoEncontradoReconheceSessaoNaoAberta() {
        assertTrue(new NaoEncontrado(null, "Sessão não está aberta.").sessaoNaoAberta());
        assertFalse(new NaoEncontrado(null, "Projeto não existe.").sessaoNaoAberta());
    }

    @Test
    void erroDeRedeETemStatusZeroECausa() {
        Exception causa = new java.io.IOException("reset");
        ErroRede e = new ErroRede("Sem resposta.", causa);
        assertEquals(0, e.status());
        assertEquals(causa, e.getCause());
        assertNull(e.codigo());
        assertEquals(0, new RespostaInvalida(0, "x", null).status());
    }

    @Test
    void leituraLimitadaRecusaAcimaDoTetoESemEstourar() throws Exception {
        byte[] ok = LeituraLimitada.ler(new java.io.ByteArrayInputStream(new byte[100]), 100);
        assertEquals(100, ok.length);
        org.junit.jupiter.api.Assertions.assertThrows(LeituraLimitada.AcimaDoTeto.class,
            () -> LeituraLimitada.ler(new java.io.ByteArrayInputStream(new byte[101]), 100));
    }
}
