package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.telas.SituacaoDaVisao.Modo;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Os estados dos dados: carregando, normal, sem conexão, agente fora. */
class SituacaoDaVisaoTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");
    private static final Instant LEITURA = Instant.parse("2026-10-24T20:57:10Z");

    static EstadoServidor estado(EstadoConexao c, Falha falha) {
        return new EstadoServidor("s1", "lab", c, falha, 0, null, LEITURA, 1, Estado2fa.DESLIGADO, "mac", null, null);
    }

    @Test
    void semEstadoNemDadosCarrega() {
        assertEquals(Modo.CARREGANDO, SituacaoDaVisao.de(null, false, null, AGORA, SP).modo());
        assertEquals(Modo.CARREGANDO, SituacaoDaVisao.de(estado(EstadoConexao.CONECTANDO, null), false, null, AGORA, SP).modo());
        assertEquals(Modo.CARREGANDO, SituacaoDaVisao.de(estado(EstadoConexao.CONECTADO, null), false, null, AGORA, SP).modo());
    }

    @Test
    void conectadoComDadosENormalSemAviso() {
        var s = SituacaoDaVisao.de(estado(EstadoConexao.CONECTADO, null), true, LEITURA, AGORA, SP);
        assertEquals(Modo.NORMAL, s.modo());
        assertFalse(s.temAviso());
        assertFalse(s.esmaecer());
    }

    @Test
    void semConexaoComDadosMostraAHoraDosUltimosDadosEEsmaece() {
        for (EstadoConexao c : new EstadoConexao[] {EstadoConexao.RECONECTANDO, EstadoConexao.DESCONECTADO, EstadoConexao.SUSPENSO,
            EstadoConexao.ERRO_AUTENTICACAO, EstadoConexao.TOKEN_RECUSADO}) {
            var s = SituacaoDaVisao.de(estado(c, null), true, LEITURA, AGORA, SP);
            assertEquals(Modo.SEM_CONEXAO_COM_DADOS, s.modo(), c.name());
            assertEquals("Sem conexão com o servidor: mostrando os últimos dados de 17:57", s.titulo(), c.name());
            assertTrue(s.esmaecer(), c.name());
            assertFalse(s.texto().isBlank(), "explica o motivo: " + c);
        }
    }

    @Test
    void semConexaoSemDadosDizQueAsInformacoesAparecemQuandoVoltar() {
        var s = SituacaoDaVisao.de(estado(EstadoConexao.RECONECTANDO, null), false, null, AGORA, SP);
        assertEquals(Modo.SEM_CONEXAO_SEM_DADOS, s.modo());
        assertEquals("Sem conexão com o servidor", s.titulo());
        assertTrue(s.texto().contains("as informações aparecem aqui"));
        assertFalse(s.esmaecer());
    }

    @Test
    void agenteDesatualizadoExplicaQueFaltaOAgente() {
        var falha = new Falha(Falha.Tipo.AGENTE_DESATUALIZADO, "404");
        var s = SituacaoDaVisao.de(estado(EstadoConexao.SSH_OK_SEM_AGENTE, falha), false, null, AGORA, SP);
        assertEquals(Modo.SEM_AGENTE_SEM_DADOS, s.modo());
        assertTrue(s.texto().contains("é de uma versão antiga"), s.texto());
        assertTrue(s.texto().contains("Falta publicar o painel no servidor"), s.texto());
        var comDados = SituacaoDaVisao.de(estado(EstadoConexao.SSH_OK_SEM_AGENTE, falha), true, LEITURA, AGORA, SP);
        assertEquals(Modo.SEM_AGENTE_COM_DADOS, comDados.modo());
        assertTrue(comDados.titulo().contains("17:57"));
        assertTrue(comDados.esmaecer());
    }

    @Test
    void agenteForaDoArDizQueTentaDeNovoSozinho() {
        var s = SituacaoDaVisao.de(estado(EstadoConexao.SSH_OK_SEM_AGENTE, new Falha(Falha.Tipo.AGENTE_FORA, "x")), false, null, AGORA, SP);
        assertEquals(Modo.SEM_AGENTE_SEM_DADOS, s.modo());
        assertTrue(s.texto().contains("não está respondendo"));
        assertTrue(s.texto().contains("tenta de novo sozinho"));
    }
}
