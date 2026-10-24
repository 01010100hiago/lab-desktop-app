package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.util.Agendador;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O log de requisições (modo debug) do {@link ClienteApi}: uma linha por chamada, com método, rota, status e tempo,
 * nunca token, query nem corpo. É o que se lê para provar a carga no servidor.
 */
class ClienteApiRegistroTest {

    private static final String LIMITES = "{\"disponivel\":false,\"freio\":{\"pct\":null,\"ativo\":false}}";

    private AgenteFalso agente;
    private TokenAgente token;
    private ClienteApi api;
    private final List<String> linhas = new CopyOnWriteArrayList<>();

    @BeforeEach
    void abrir() throws IOException {
        agente = new AgenteFalso();
        token = TokenAgente.gerar();
        api = new ClienteApi("lab", agente.porta(), token, PedidoDeCodigo2fa.NUNCA, TimeoutsPorRota.padrao(),
            Agendador.doFundo(), linhas::add);
    }

    @AfterEach
    void fechar() {
        api.close();
        agente.close();
    }

    @Test
    void cadaChamadaVirandoUmaLinhaComStatusETempo() throws Exception {
        agente.responder(p -> Resposta.json(200, LIMITES));

        api.limites();
        api.limites();

        assertEquals(2, linhas.size(), linhas.toString());
        for (String l : linhas) {
            assertTrue(l.matches("\\[lab\\] GET /api/limites -> 200 em \\d+ ms"), l);
        }
    }

    @Test
    void aLinhaNuncaTemTokenNemCabecalhoNemCorpo() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"nome\":\"app-loja\",\"segredo\":\"corpo-da-resposta\"}"));

        try {
            api.projeto("app-loja");
        } catch (Exception ignorada) {
            // o que o agente devolve aqui não importa: o teste olha só a linha de log
        }

        assertEquals(1, linhas.size(), linhas.toString());
        String l = linhas.get(0);
        assertTrue(l.startsWith("[lab] GET /api/projetos/app-loja -> 200 em "), l);
        assertFalse(l.contains(token.cabecalhoAuthorization()) || l.contains("Bearer") || l.contains("lab_"), l);
        assertFalse(l.contains("corpo-da-resposta") || l.contains("User-Agent"), l);
    }

    @Test
    void statusDeErroEFalhaDeRedeTambemFicamRegistrados() throws Exception {
        agente.responder(p -> Resposta.json(401, "{\"erro\":\"token\"}"));
        assertThrows(ErroApi.NaoAutorizado.class, () -> api.limites());
        assertTrue(linhas.get(0).contains("-> 401 em"), linhas.get(0));

        agente.responder(p -> Resposta.json(409, "{\"erro\":\"x\"}"));
        assertThrows(ErroApi.Conflito.class, () -> api.retomar("app-loja", false, true));
        assertTrue(linhas.get(1).startsWith("[lab] POST /api/projetos/app-loja/limite/retomar -> 409 em"), linhas.get(1));

        agente.close(); // o túnel caiu
        assertThrows(ErroRede.class, () -> api.limites());
        String queda = linhas.get(2);
        assertTrue(queda.startsWith("[lab] GET /api/limites -> sem resposta ("), queda);
    }

    @Test
    void oCodigoDo2faNuncaApareceNoLog() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean confirmado = new java.util.concurrent.atomic.AtomicBoolean();
        agente.responder(p -> {
            if ("/api/acesso/2fa".equals(p.caminho())) {
                confirmado.set(true);
                return Resposta.json(200, "{\"ok\":true,\"ativado_ate\":\"2026-11-23T12:00:00.000Z\"}");
            }
            return confirmado.get() ? Resposta.json(200, LIMITES)
                : Resposta.json(401, "{\"erro\":\"x\",\"codigo\":\"totp_requerido\"}");
        });
        PedidoDeCodigo2fa pedido = (servidor, aviso) -> java.util.Optional.of("123456");
        try (ClienteApi comPedido = new ClienteApi("lab", agente.porta(), token, pedido, TimeoutsPorRota.padrao(),
            Agendador.doFundo(), linhas::add)) {
            comPedido.limites();
        }

        assertEquals(3, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).contains("GET /api/limites -> 401"), linhas.get(0));
        assertTrue(linhas.get(1).contains("POST /api/acesso/2fa -> 200"), linhas.get(1));
        assertTrue(linhas.get(2).contains("GET /api/limites -> 200"), linhas.get(2));
        assertFalse(linhas.stream().anyMatch(l -> l.contains("123456")), "o código digitado não vai para o log");
    }

    @Test
    void umRegistroQueQuebraNaoDerrubaAChamada() throws Exception {
        agente.responder(p -> Resposta.json(200, LIMITES));
        try (ClienteApi quebrado = new ClienteApi("lab", agente.porta(), token, PedidoDeCodigo2fa.NUNCA, TimeoutsPorRota.padrao(),
            Agendador.doFundo(), l -> {
                throw new IllegalStateException("disco cheio");
            })) {
            quebrado.limites();
        }
    }
}
