package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ErroApi.NaoEncontrado;
import br.com.labdesktop.api.ErroApi.RespostaInvalida;
import br.com.labdesktop.api.ErroApi.ServidorErro;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.AgenteFalso.Pedido;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** As rotas da página do projeto no {@link ClienteApi}: consumo, logs, tela, teclas e reiniciar container. */
class ClienteApiDoProjetoTest {

    private AgenteFalso agente;
    private ClienteApi api;

    @BeforeEach
    void abrir() throws IOException {
        agente = new AgenteFalso();
        api = new ClienteApi("lab", agente.porta(), TokenAgente.gerar(), PedidoDeCodigo2fa.NUNCA);
    }

    @AfterEach
    void fechar() {
        api.close();
        agente.close();
    }

    private Pedido ultimo() {
        return agente.pedidos.get(agente.pedidos.size() - 1);
    }

    @Test
    void consumoDoProjetoParseiaAFixture() throws Exception {
        agente.responder(p -> Resposta.json(200, FixturesDeApi.lerRecursoDoProjeto("consumo-sintetico.json")));
        TokensDoProjeto t = api.tokensDoProjeto("orbita");
        assertEquals("GET", ultimo().metodo());
        assertEquals("/api/tokens/orbita", ultimo().caminhoComQuery());
        assertEquals(30, t.porDia().size());
        assertEquals("claude-opus-4-8", t.modelos().get(0).modelo());
    }

    @Test
    void consumoDeProjetoQueNaoExisteEUmNaoEncontrado() {
        agente.responder(p -> Resposta.json(404, "{\"erro\":\"Projeto não existe.\"}"));
        assertThrows(NaoEncontrado.class, () -> api.tokensDoProjeto("fantasma"));
    }

    @Test
    void nomeInvalidoNemSaiDoApp() {
        assertThrows(IllegalArgumentException.class, () -> api.tokensDoProjeto("../etc"));
        assertThrows(IllegalArgumentException.class, () -> api.logs("orbita", "x; ls", 100));
        assertThrows(IllegalArgumentException.class, () -> api.reiniciarContainer("orbita", "a b"));
        assertTrue(agente.pedidos.isEmpty(), "nada foi enviado ao agente");
    }

    @Test
    void logsPedemContainerELinhasELimitamO50A2000() throws Exception {
        agente.responder(p -> Resposta.texto(200, "linha 1\nlinha 2\n"));
        assertEquals("linha 1\nlinha 2\n", api.logs("orbita", "orbita-web-1", 300));
        assertEquals("/api/projetos/orbita/logs?container=orbita-web-1&linhas=300", ultimo().caminhoComQuery());
        api.logs("orbita", "orbita-web-1", 5);
        assertEquals("/api/projetos/orbita/logs?container=orbita-web-1&linhas=50", ultimo().caminhoComQuery());
        api.logs("orbita", "orbita-web-1", 99999);
        assertEquals("/api/projetos/orbita/logs?container=orbita-web-1&linhas=2000", ultimo().caminhoComQuery());
    }

    @Test
    void logsAceitamMaisQueOLimiteDoJsonMasNaoMaisQue8MiB() throws Exception {
        String tresMiB = "x".repeat(3 * 1024 * 1024);
        agente.responder(p -> Resposta.texto(200, tresMiB));
        assertEquals(tresMiB.length(), api.logs("orbita", "orbita-web-1", 300).length(), "o teto de texto é o do agente, 8 MiB");
        String nove = "x".repeat(9 * 1024 * 1024);
        agente.responder(p -> Resposta.texto(200, nove));
        assertThrows(RespostaInvalida.class, () -> api.logs("orbita", "orbita-web-1", 300));
    }

    @Test
    void logsDeContainerQueNaoEDoProjetoSaoErroDoAgente() {
        agente.responder(p -> Resposta.json(400, "{\"erro\":\"Container não pertence a este projeto.\"}"));
        ServidorErro e = assertThrows(ServidorErro.class, () -> api.logs("orbita", "intruso-1", 100));
        assertEquals("Container não pertence a este projeto.", e.mensagem());
    }

    @Test
    void logsVaziosViramTextoVazio() throws Exception {
        agente.responder(p -> Resposta.texto(200, ""));
        assertEquals("", api.logs("orbita", "orbita-cache-1", 100));
    }

    @Test
    void telaDoClaudeParseiaEOFechadoEUm404() throws Exception {
        agente.responder(p -> Resposta.json(200, FixturesDeApi.lerRecursoDoProjeto("tela-pergunta.json")));
        TelaClaude t = api.telaDoClaude("orbita");
        assertEquals("/api/projetos/orbita/claude/tela", ultimo().caminhoComQuery());
        assertEquals("Quer continuar?", t.pergunta().titulo());
        assertEquals(3, t.pergunta().opcoes().size());
        agente.responder(p -> Resposta.json(404, "{\"erro\":\"Sessão não está aberta.\"}"));
        NaoEncontrado e = assertThrows(NaoEncontrado.class, () -> api.telaDoClaude("orbita"));
        assertEquals("Sessão não está aberta.", e.mensagem());
    }

    @Test
    void textoDaTelaNuncaChegaComoHtml() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"tela\":\"<html><b>oi</b>\",\"executando\":\"<html>x\"}"));
        TelaClaude t = api.telaDoClaude("orbita");
        assertFalse(t.tela().toLowerCase().startsWith("<html"));
        assertFalse(t.executando().toLowerCase().startsWith("<html"));
    }

    @Test
    void mensagemVaiComEnterTrueETeclaSoAsPermitidas() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true}"));
        api.enviarMensagem("orbita", "  oi, tudo bem?  ");
        assertEquals("POST", ultimo().metodo());
        assertEquals("/api/projetos/orbita/claude/teclas", ultimo().caminhoComQuery());
        JsonNode corpo = br.com.labdesktop.util.Json.mapper().readTree(ultimo().corpo());
        assertEquals("oi, tudo bem?", corpo.get("texto").asText());
        assertTrue(corpo.get("enter").asBoolean());
        api.enviarTecla("orbita", "Escape");
        assertEquals("{\"tecla\":\"Escape\"}", ultimo().corpo());
        api.enviarTecla("orbita", "Enter");
        assertEquals("{\"tecla\":\"Enter\"}", ultimo().corpo());
        int antes = agente.pedidos.size();
        assertThrows(IllegalArgumentException.class, () -> api.enviarTecla("orbita", "Delete"));
        assertThrows(IllegalArgumentException.class, () -> api.enviarTecla("orbita", null));
        assertThrows(IllegalArgumentException.class, () -> api.enviarMensagem("orbita", "   "));
        assertThrows(IllegalArgumentException.class, () -> api.enviarMensagem("orbita", "x".repeat(ClienteApi.TEXTO_MAXIMO_DA_MENSAGEM + 1)));
        assertEquals(antes, agente.pedidos.size(), "o app confere antes: nada sai com tecla ou texto que o agente recusaria");
    }

    @Test
    void mensagemNoLimiteExatoPassa() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true}"));
        api.enviarMensagem("orbita", "x".repeat(ClienteApi.TEXTO_MAXIMO_DA_MENSAGEM));
        assertEquals(1, agente.pedidos.size());
    }

    @Test
    void sessaoFechadaNaoAceitaMensagem() {
        agente.responder(p -> Resposta.json(404, "{\"erro\":\"Sessão não está aberta.\"}"));
        assertThrows(NaoEncontrado.class, () -> api.enviarMensagem("orbita", "oi"));
    }

    @Test
    void reiniciarContainerChamaARotaCerta() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true}"));
        api.reiniciarContainer("orbita", "orbita-web-1");
        assertEquals("POST", ultimo().metodo());
        assertEquals("/api/projetos/orbita/containers/orbita-web-1/reiniciar", ultimo().caminhoComQuery());
    }

    @Test
    void reiniciarContainerFalhoNaoReenviaSozinho() {
        agente.responder(p -> Resposta.json(500, "{\"erro\":\"Error response from daemon\"}"));
        assertThrows(ServidorErro.class, () -> api.reiniciarContainer("orbita", "orbita-web-1"));
        assertEquals(1, agente.pedidos.size(), "POST nunca é reenviado sozinho");
    }

    @Test
    void oDtoDosArquivosAceitaTamanhoQuandoOAgentePassarAMandar() {
        var d = FixturesDeApi.de("{\"arquivos\":[{\"nome\":\"a\",\"tipo\":\"arquivo\",\"tamanho\":2048},{\"nome\":\"b\",\"tipo\":\"pasta\"}]}",
            br.com.labdesktop.api.modelo.DetalheProjeto.class);
        assertEquals(2048L, d.arquivos().get(0).tamanho());
        assertEquals(null, d.arquivos().get(1).tamanho());
    }
}
