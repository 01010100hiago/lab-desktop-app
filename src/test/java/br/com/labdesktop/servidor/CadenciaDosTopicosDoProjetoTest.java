package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A carga da página do projeto: o detalhe é o único assunto da página; consumo, tela do Claude e logs são das abas e só rodam
 * com a aba na tela. Quais abas assinam o quê está em {@code TelaProjetoTest}; aqui está a cadência de cada tópico.
 */
class CadenciaDosTopicosDoProjetoTest {

    @Test
    void detalheDoProjetoTem10sAtivoE60sSemFocoEParaEscondido() {
        Topico<?> t = Topico.detalhe("orbita");
        assertEquals("detalhe:orbita", t.chave());
        assertEquals(Optional.of(Duration.ofSeconds(10)), t.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(Duration.ofSeconds(60)), t.intervalo(EstadoJanela.SEM_FOCO, false));
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.ESCONDIDA, false));
    }

    @Test
    void consumoTem60sNaAberturaEParaSemFocoEEscondido() {
        Topico<?> t = Topico.consumo("orbita");
        assertEquals("consumo:orbita", t.chave());
        assertEquals(Optional.of(Duration.ofSeconds(60)), t.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.SEM_FOCO, false), "o cache do agente só muda de minuto em minuto");
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.ESCONDIDA, false));
        assertEquals(Optional.of(Duration.ofSeconds(60)), t.intervalo(EstadoJanela.SEM_FOCO, true), "o Monitor numa segunda tela");
    }

    @Test
    void telaDoClaudeTem3sAtivoE18sSemFocoEParaEscondida() {
        Topico<?> t = Topico.tela("orbita");
        assertEquals("tela:orbita", t.chave());
        assertEquals(Optional.of(Duration.ofSeconds(3)), t.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(Duration.ofSeconds(18)), t.intervalo(EstadoJanela.SEM_FOCO, false), "um sexto da cadência");
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.ESCONDIDA, false));
    }

    @Test
    void logsTem5sSoAtivoEParaSemFocoEEscondido() {
        Topico<?> t = Topico.logs("orbita", "orbita-web-1", 300);
        assertEquals("logs:orbita:orbita-web-1:300", t.chave());
        assertEquals(Optional.of(Duration.ofSeconds(5)), t.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.SEM_FOCO, false));
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.ESCONDIDA, false));
    }

    @Test
    void asChavesSaoPorProjetoContainerELinhas() {
        assertFalse(Topico.logs("orbita", "orbita-web-1", 300).chave().equals(Topico.logs("orbita", "orbita-web-1", 100).chave()));
        assertFalse(Topico.logs("orbita", "orbita-web-1", 300).chave().equals(Topico.logs("orbita", "orbita-fila-1", 300).chave()));
        assertFalse(Topico.consumo("orbita").chave().equals(Topico.consumo("outro").chave()));
        assertFalse(Topico.tela("orbita").chave().equals(Topico.detalhe("orbita").chave()));
    }

    @Test
    void nomeForaDaRegraDoAgenteNuncaViraTopico() {
        assertThrows(IllegalArgumentException.class, () -> Topico.consumo("Orbita"));
        assertThrows(IllegalArgumentException.class, () -> Topico.tela("../x"));
        assertThrows(IllegalArgumentException.class, () -> Topico.logs("orbita", "web; rm -rf /", 100));
        assertThrows(IllegalArgumentException.class, () -> Topico.logs("orbita", null, 100));
    }

    @Test
    void sessaoNaoAbertaEUmValorDoTopicoNaoUmaFalha() throws Exception {
        try (AgenteFalso agente = new AgenteFalso()) {
            ClienteApi api = new ClienteApi("lab", agente.porta(), TokenAgente.gerar(), PedidoDeCodigo2fa.NUNCA);
            try {
                agente.responder(p -> Resposta.json(404, "{\"erro\":\"Sessão não está aberta.\"}"));
                VisaoDaTela fechada = Topico.tela("orbita").consulta().consultar(api);
                assertTrue(fechada.estaFechada(), "o 404 de sessão fechada é o fluxo normal: três deles não dobram o intervalo");
                assertNull(fechada.tela());

                agente.responder(p -> Resposta.json(200, FixturesDeApi.lerRecursoDoProjeto("tela-executando.json")));
                VisaoDaTela aberta = Topico.tela("orbita").consulta().consultar(api);
                assertFalse(aberta.estaFechada());
                assertNotNull(aberta.tela().executando());

                agente.responder(p -> Resposta.json(500, "{\"erro\":\"falhou\"}"));
                assertThrows(ErroApi.ServidorErro.class, () -> Topico.tela("orbita").consulta().consultar(api), "o resto continua sendo erro");
            } finally {
                api.close();
            }
        }
    }
}
