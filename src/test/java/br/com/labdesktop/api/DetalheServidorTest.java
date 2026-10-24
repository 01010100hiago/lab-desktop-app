package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import org.junit.jupiter.api.Test;

/** O DTO de {@code /api/servidor/detalhe} com a fixture SINTÉTICA (mesma estrutura dos arquivos reais, valores inventados). */
class DetalheServidorTest {

    @Test
    void parseiaAFixtureSinteticaInteira() {
        DetalheServidor d = FixturesDeApi.detalheSintetico();

        assertEquals("servidor-exemplo", d.hostname());
        assertEquals(3, d.sensores().size());
        assertEquals("Package id 0", d.sensores().get(1).rotulo());
        assertEquals(62.0, d.sensores().get(1).c());
        assertEquals(87.0, d.sensores().get(1).max());
        assertEquals(105.0, d.sensores().get(1).crit());
        assertEquals(Boolean.TRUE, d.energia().tomada());
        assertTrue(d.energia().baterias().isEmpty());
        assertEquals("Fabricante Exemplo", d.maquina().fabricante());
        assertEquals(4, d.maquina().nucleos());
        assertEquals(8L * 1024 * 1024 * 1024, d.maquina().memoriaTotal());
        assertEquals("x64", d.maquina().arquitetura());
        assertEquals(2, d.consumo().size());
        assertNull(d.consumo().get(1).projeto(), "containers sem dono");
        assertEquals("sessao", d.consumo().get(0).containers().get(1).tipo());
        assertNull(d.consumo().get(1).containers().get(0).cpu());
    }

    @Test
    void naoTrazMaxNemCritDoSensorAcpitzNemCpuDoProcessoNaPrimeiraVarredura() {
        DetalheServidor d = FixturesDeApi.detalheSintetico();
        assertNull(d.sensores().get(0).max());
        assertNull(d.sensores().get(0).crit());
        assertEquals(54.0, d.sensores().get(0).c());
        assertNull(d.processos().get(0).cpu(), "cpu null na primeira varredura");
        assertNotNull(d.processos().get(0).mem());
    }

    @Test
    void osBlocosEmComumVemIguaisAoServidor() {
        DetalheServidor d = FixturesDeApi.detalheSintetico();
        InfoServidor s = FixturesDeApi.servidorSintetico();
        assertEquals(s.cpu().nucleos(), d.cpu().nucleos());
        assertEquals(s.memoria().total(), d.memoria().total());
        assertEquals(60, d.historico().cpu().size());
        assertEquals(10, d.historico().passoS());
        assertNull(d.historico().temp().get(0), "valores do histórico podem ser nulos");
        assertEquals("alfa", d.processos().get(0).projeto());
        assertEquals("(servidor)", d.processos().get(3).projeto());
        assertEquals(2, d.sessoes().size());
        assertEquals(63.0, d.temperatura());
    }

    @Test
    void campoNovoDoAgenteEIgnoradoENadaViraErro() {
        // a fixture traz "campo_novo_do_futuro": se o mapper reprovasse desconhecidos, nem parsearia
        assertNotNull(FixturesDeApi.detalheSintetico());
        DetalheServidor vazio = FixturesDeApi.de("{}", DetalheServidor.class);
        assertTrue(vazio.sensores().isEmpty());
        assertTrue(vazio.processos().isEmpty());
        assertNull(vazio.energia());
        assertNull(vazio.maquina());
        assertNull(vazio.temperatura());
    }

    @Test
    void tomadaNulaECamposDeTipoErradoViramNuloSemDerrubar() {
        DetalheServidor d = FixturesDeApi.de("{\"energia\":{\"tomada\":null,\"baterias\":[{\"nome\":\"BAT0\",\"carga\":\"cheia\"}]},"
            + "\"sensores\":[{\"chip\":\"c\",\"rotulo\":\"r\",\"c\":\"quente\"}],\"maquina\":{\"nucleos\":\"oito\"}}", DetalheServidor.class);
        assertNull(d.energia().tomada());
        assertEquals("BAT0", d.energia().baterias().get(0).nome());
        assertNull(d.energia().baterias().get(0).carga());
        assertNull(d.sensores().get(0).c());
        assertNull(d.maquina().nucleos());
    }

    @Test
    void textoHostilDoServidorNaoViraHtml() {
        DetalheServidor d = FixturesDeApi.de("{\"hostname\":\"<html><b>x</b>\",\"sensores\":[{\"rotulo\":\"<html>quente\"}]}", DetalheServidor.class);
        assertFalse(d.hostname().toLowerCase().startsWith("<html"));
        assertFalse(d.sensores().get(0).rotulo().toLowerCase().startsWith("<html"));
    }

    @Test
    void oClienteBuscaARotaDeDetalheEParseia() throws Exception {
        try (AgenteFalso agente = new AgenteFalso()) {
            agente.responder(p -> Resposta.json(200, FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json")));
            try (ClienteApi api = new ClienteApi("lab", agente.porta(), TokenAgente.gerar(), PedidoDeCodigo2fa.NUNCA)) {
                DetalheServidor d = api.servidorDetalhe();
                assertEquals("servidor-exemplo", d.hostname());
                assertEquals("/api/servidor/detalhe", agente.pedidos.get(0).caminho());
                assertEquals("GET", agente.pedidos.get(0).metodo());
            }
        }
    }
}
