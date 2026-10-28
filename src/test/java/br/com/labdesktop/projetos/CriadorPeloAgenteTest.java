package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.api.TimeoutsPorRota;
import br.com.labdesktop.api.modelo.RespostaNovoProjeto;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.AgenteFalso.Pedido;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.util.Agendador;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Criar projeto pelo agente, contra um agente falso em {@code HttpServer}: 200, 400 (nome inválido, já existe, reservado), {@code guia_ia}
 * true/false/ausente, agente de antes, perda de resposta (prazo) com releitura da lista e sem nunca repetir o pedido.
 */
class CriadorPeloAgenteTest {

    private static final String LISTA_COM = "{\"projetos\":[{\"nome\":\"meu-app\"},{\"nome\":\"outro\"}]}";
    private static final String LISTA_SEM = "{\"projetos\":[{\"nome\":\"outro\"}]}";

    private AgenteFalso agente;
    private ClienteApi api;
    private final AtomicBoolean conectado = new AtomicBoolean(true);
    private CriadorPeloAgente criador;

    @BeforeEach
    void abrir() throws IOException {
        agente = new AgenteFalso();
        api = new ClienteApi("lab", agente.porta(), TokenAgente.gerar(), PedidoDeCodigo2fa.NUNCA, TimeoutsPorRota.limitadoA(Duration.ofMillis(700)),
            Agendador.doFundo());
        criador = new CriadorPeloAgente(new AgenteDeProjetos() {
            @Override
            public boolean conectado() {
                return conectado.get();
            }

            @Override
            public RespostaNovoProjeto criar(String nome, Boolean guiaIa) throws ErroApi {
                return api.criarProjeto(nome, guiaIa);
            }

            @Override
            public List<String> nomes() throws ErroApi {
                return api.projetos().projetos().stream().map(br.com.labdesktop.api.modelo.Projeto::nome).toList();
            }
        });
    }

    @AfterEach
    void fechar() {
        api.close();
        agente.close();
    }

    private List<Pedido> novos() {
        return agente.pedidos.stream().filter(p -> p.metodo().equals("POST") && p.caminho().equals("/api/projetos/novo")).toList();
    }

    private static JsonNode corpo(Pedido p) throws IOException {
        return new ObjectMapper().readTree(p.corpo());
    }

    @Test
    void criaComOk200() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true,\"guia_ia\":true}"));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", true);
        assertEquals(FluxoDeCriacao.Tipo.CRIADO, d.tipo());
        assertEquals("Projeto meu-app criado.", d.mensagem());
        assertEquals(List.of(), d.avisos());
        assertEquals(1, novos().size());
        assertEquals("POST", novos().get(0).metodo());
        assertEquals("application/json", novos().get(0).cabecalho("Content-Type"));
        assertEquals("meu-app", corpo(novos().get(0)).get("nome").asText());
    }

    @Test
    void guiaIaVaiComoFoiEscolhidoEAusenteQuandoACaixaNaoApareceu() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true,\"guia_ia\":true}"));
        criador.criar("a1", true);
        criador.criar("a2", false);
        criador.criar("a3", null);
        List<Pedido> n = novos();
        assertEquals(3, n.size());
        assertTrue(corpo(n.get(0)).get("guia_ia").isBoolean() && corpo(n.get(0)).get("guia_ia").asBoolean());
        assertTrue(corpo(n.get(1)).get("guia_ia").isBoolean() && !corpo(n.get(1)).get("guia_ia").asBoolean());
        assertFalse(corpo(n.get(2)).has("guia_ia"), "sem escolha, o campo nem vai (o agente entende como com guia)");
        assertEquals("{\"nome\":\"a3\"}", n.get(2).corpo());
    }

    @Test
    void agenteDeAntesQueIgnoraSemGuiaAvisaODono() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true}")); // sem guia_ia na resposta
        CriadorDeProjeto.Resultado r = criador.criar("meu-app", false);
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("CLAUDE.md"), r.avisos().get(0));
        assertEquals(List.of(), criador.criar("meu-app2", true).avisos(), "com guia não há o que avisar");
        assertEquals(List.of(), criador.criar("meu-app3", null).avisos());
    }

    @Test
    void agenteQueEntendeConfirmaEnaoHaAviso() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"ok\":true,\"guia_ia\":false}"));
        assertEquals(List.of(), criador.criar("meu-app", false).avisos());
    }

    @Test
    void nomeInvalidoNemSaiDoApp() {
        for (String ruim : List.of("Meu-App", "meu app", "../x", "", "a".repeat(50), "abc-", "-abc")) {
            FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> criador.criar(ruim, null), ruim);
            assertEquals(FalhaDoProjeto.Tipo.RECUSADO, f.tipo());
        }
        assertTrue(agente.pedidos.isEmpty(), "nada foi enviado");
    }

    @Test
    void erro400DoAgenteAparecePeloTextoDele() {
        agente.responder(p -> Resposta.json(400, "{\"erro\":\"Já existe um projeto com esse nome.\"}"));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.FALHOU, d.tipo());
        assertEquals("Já existe um projeto com esse nome.", d.mensagem());
        agente.responder(p -> Resposta.json(400, "{\"erro\":\"Esse nome é reservado pela infraestrutura do lab.\"}"));
        assertEquals("Esse nome é reservado pela infraestrutura do lab.", FluxoDeCriacao.criar(criador, "painel", null).mensagem());
        agente.responder(p -> Resposta.json(400, "{\"erro\":\"Nome inválido: use letras minúsculas, números e hífen (ex.: meu-projeto).\"}"));
        assertTrue(FluxoDeCriacao.criar(criador, "ok", null).mensagem().startsWith("Nome inválido"));
        assertEquals(3, novos().size(), "cada um foi enviado uma vez só");
    }

    @Test
    void fraseDoAgenteEhTextoNuncaHtmlOuSegredo() {
        agente.responder(p -> Resposta.json(400, "{\"erro\":\"<html><b>oi</b> Authorization: Bearer lab_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG\"}"));
        String m = FluxoDeCriacao.criar(criador, "meu-app", null).mensagem();
        assertFalse(m.startsWith("<html"), m);
        assertFalse(m.contains("lab_abcdefghijklmnopqrstuvwxyz0123"), m);
    }

    @Test
    void semConexaoNadaEnviado() {
        conectado.set(false);
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.FALHOU, d.tipo());
        assertTrue(d.mensagem().startsWith("Sem conexão"), d.mensagem());
        assertTrue(agente.pedidos.isEmpty());
    }

    @Test
    void naoAutorizadoVaiParaOFluxoPadrao() {
        agente.responder(p -> Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}"));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.NAO_AUTORIZADO, d.tipo());
        agente.responder(p -> Resposta.json(401, "{\"erro\":\"Código do autenticador necessário.\",\"codigo\":\"totp_requerido\"}"));
        FluxoDeCriacao.Desfecho d2 = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.NAO_AUTORIZADO, d2.tipo());
        assertTrue(d2.mensagem().contains("código do autenticador"));
    }

    @Test
    void agenteSemARotaEUmaFrase() {
        agente.responder(p -> Resposta.json(404, "{\"erro\":\"Rota não existe.\"}"));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.FALHOU, d.tipo());
        assertTrue(d.mensagem().contains("não tem esta função"));
    }

    // ---------------------------------------------------------------------------------------------
    // perda de resposta
    // ---------------------------------------------------------------------------------------------

    @Test
    void prazoEstouradoRelaALista_eOProjetoApareceu() {
        agente.responder(p -> p.metodo().equals("POST") ? Resposta.comAtraso(Resposta.json(200, "{\"ok\":true}"), 3000) : Resposta.json(200, LISTA_COM));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.CRIADO, d.tipo());
        assertTrue(d.avisos().get(0).contains("demorou"), d.avisos().toString());
        assertEquals(1, novos().size(), "o pedido NÃO foi repetido");
    }

    @Test
    void prazoEstouradoRelaALista_eNaoApareceuAindaDizTalvez() {
        agente.responder(p -> p.metodo().equals("POST") ? Resposta.comAtraso(Resposta.json(200, "{\"ok\":true}"), 3000) : Resposta.json(200, LISTA_SEM));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.TALVEZ, d.tipo());
        assertTrue(d.mensagem().contains("Talvez já tenha sido criado"), d.mensagem());
        assertTrue(d.mensagem().contains("confira a lista"));
        assertTrue(d.listaPrecisaRelerSe());
        assertEquals(1, novos().size());
    }

    @Test
    void prazoEstouradoELeituraFalhaDizTalvezTambem() {
        agente.responder(p -> p.metodo().equals("POST") ? Resposta.comAtraso(Resposta.json(200, "{\"ok\":true}"), 3000) : Resposta.semResposta());
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.TALVEZ, d.tipo());
        assertTrue(d.mensagem().contains("não deu para conferir a lista"), d.mensagem());
        assertEquals(1, novos().size());
    }

    @Test
    void conexaoCortadaNoMeioETaoIncertaQuantoOPrazo() {
        agente.responder(p -> p.metodo().equals("POST") ? Resposta.semResposta() : Resposta.json(200, LISTA_COM));
        assertEquals(FluxoDeCriacao.Tipo.CRIADO, FluxoDeCriacao.criar(criador, "meu-app", null).tipo());
        assertEquals(1, novos().size());
    }

    @Test
    void erro503NaoRepeteERelaALista() {
        agente.responder(p -> p.metodo().equals("POST") ? Resposta.json(503, "{\"erro\":\"ocupado\"}") : Resposta.json(200, LISTA_SEM));
        FluxoDeCriacao.Desfecho d = FluxoDeCriacao.criar(criador, "meu-app", null);
        assertEquals(FluxoDeCriacao.Tipo.TALVEZ, d.tipo());
        assertEquals(1, novos().size());
    }

    @Test
    void existeRelaOAgente() {
        agente.responder(p -> Resposta.json(200, LISTA_COM));
        assertEquals(java.util.Optional.of(true), criador.existe("meu-app"));
        assertEquals(java.util.Optional.of(false), criador.existe("fantasma"));
        agente.responder(p -> Resposta.semResposta());
        assertEquals(java.util.Optional.empty(), criador.existe("meu-app"));
    }
}
