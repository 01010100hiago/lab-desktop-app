package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.ApresentacaoDoEstado.Apresentacao;
import br.com.labdesktop.ui.componentes.Icones;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Estado da conexão: SEMPRE texto e ícone (nunca só cor), para todos os 11 estados. */
class ApresentacaoDoEstadoTest {

    static EstadoServidor estado(EstadoConexao c, Falha falha) {
        return new EstadoServidor("s1", "lab", c, falha, 0, null, null, 1, Estado2fa.DESLIGADO, "mac", null, null);
    }

    @Test
    void todoEstadoTemTextoEIconeQueExiste() {
        var icones = Icones.nomes();
        for (EstadoConexao c : EstadoConexao.values()) {
            Apresentacao a = ApresentacaoDoEstado.de(estado(c, null));
            assertFalse(a.texto().isBlank(), "texto de " + c);
            assertNotNull(a.icone(), "ícone de " + c);
            assertTrue(icones.contains(a.icone()), "o ícone '" + a.icone() + "' de " + c + " não existe nos SVGs");
            assertNotNull(a.gravidade());
        }
    }

    @Test
    void pesoVisualDosEstadosPrincipais() {
        assertEquals(Gravidade.OK, ApresentacaoDoEstado.de(estado(EstadoConexao.CONECTADO, null)).gravidade());
        assertEquals("Conectado", ApresentacaoDoEstado.de(estado(EstadoConexao.CONECTADO, null)).texto());
        assertEquals(Gravidade.ATENCAO, ApresentacaoDoEstado.de(estado(EstadoConexao.RECONECTANDO, null)).gravidade());
        assertEquals(Gravidade.PERIGO, ApresentacaoDoEstado.de(estado(EstadoConexao.ERRO_AUTENTICACAO, null)).gravidade());
        assertEquals(Gravidade.PERIGO, ApresentacaoDoEstado.de(estado(EstadoConexao.TOKEN_RECUSADO, null)).gravidade());
        assertEquals(Gravidade.NEUTRO, ApresentacaoDoEstado.de(estado(EstadoConexao.SUSPENSO, null)).gravidade());
    }

    @Test
    void hostSemPinEhConfirmarEPinDivergenteEPerigo() {
        Apresentacao semPin = ApresentacaoDoEstado.de(estado(EstadoConexao.ERRO_CHAVE_HOST, new Falha(Falha.Tipo.PIN_AUSENTE, "x")));
        Apresentacao mudou = ApresentacaoDoEstado.de(estado(EstadoConexao.ERRO_CHAVE_HOST, new Falha(Falha.Tipo.PIN_DIVERGENTE, "x")));
        assertEquals("Confirmar servidor", semPin.texto());
        assertEquals(Gravidade.ATENCAO, semPin.gravidade());
        assertEquals("Chave do servidor mudou", mudou.texto());
        assertEquals(Gravidade.PERIGO, mudou.gravidade());
    }

    @Test
    void agenteDesatualizadoEAgenteForaTemTextosDiferentes() {
        Apresentacao antigo = ApresentacaoDoEstado.de(estado(EstadoConexao.SSH_OK_SEM_AGENTE, new Falha(Falha.Tipo.AGENTE_DESATUALIZADO, "404")));
        Apresentacao fora = ApresentacaoDoEstado.de(estado(EstadoConexao.SSH_OK_SEM_AGENTE, new Falha(Falha.Tipo.AGENTE_FORA, "sem resposta")));
        assertEquals("Agente desatualizado", antigo.texto());
        assertEquals("Agente não responde", fora.texto());
    }

    @Test
    void reconectandoMostraATentativaEAgenteIncompativelAparece() {
        EstadoServidor r = new EstadoServidor("s1", "lab", EstadoConexao.RECONECTANDO, null, 3, Instant.EPOCH, Instant.EPOCH, 1,
            Estado2fa.DESLIGADO, "mac", null, null);
        assertEquals("Reconectando (3)", ApresentacaoDoEstado.de(r).texto());
        EstadoServidor incompativel = new EstadoServidor("s1", "lab", EstadoConexao.CONECTADO, null, 0, null, null, 1,
            Estado2fa.DESLIGADO, "mac", new VersaoAgente(2, "v22", false, "mac", "desligado"), FixturesDeApi.limitesDoMock());
        assertEquals("Agente incompatível", ApresentacaoDoEstado.de(incompativel).texto());
    }
}
