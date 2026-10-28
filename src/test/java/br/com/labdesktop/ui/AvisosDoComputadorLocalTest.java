package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.RecursosDoAgente;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.telas.SituacaoDaVisao;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A fala do servidor "Este computador": Docker e laboratório, nunca SSH; o botão é Preparar este computador. */
class AvisosDoComputadorLocalTest {

    private static final Instant AGORA = Instant.parse("2026-10-28T12:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private static EstadoServidor local(EstadoConexao c, SituacaoDoAgente s, Falha falha, VersaoAgente v) {
        return new EstadoServidor("s1", "Este computador", c, falha, 0, null, null, 1, Estado2fa.DESLIGADO, "mac", v, null, s, true);
    }

    private static EstadoServidor remoto(EstadoConexao c, SituacaoDoAgente s) {
        return new EstadoServidor("s2", "vps-1", c, null, 0, null, null, 1, Estado2fa.DESLIGADO, "mac", null, null, s, false);
    }

    @Test
    void cadaCausaTemAFraseEOBotaoPrepararEnadaDeSsh() {
        for (SituacaoDoAgente s : List.of(SituacaoDoAgente.LOCAL_NAO_CRIADO, SituacaoDoAgente.LOCAL_DOCKER_PARADO, SituacaoDoAgente.LOCAL_SEM_DOCKER)) {
            EstadoServidor e = local(EstadoConexao.SSH_OK_SEM_AGENTE, s, null, null);
            var aviso = ConteudoDosAvisos.deConexao(e, AGORA, FUSO);
            assertEquals(List.of(Acao.PREPARAR_COMPUTADOR), aviso.acoes(), s.toString());
            assertEquals("Preparar este computador", aviso.acoes().get(0).rotulo());
            String tudo = aviso.titulo() + " " + aviso.texto();
            assertFalse(tudo.toLowerCase().contains("ssh"), tudo);
            assertFalse(tudo.toLowerCase().contains("servidor"), "fala de computador e de laboratório: " + tudo);
            if (s != SituacaoDoAgente.LOCAL_DOCKER_PARADO) { // abrir o Docker não instala nada: o texto dele não precisa dizer isso
                assertTrue(aviso.texto().contains("sua confirmação") || aviso.texto().contains("confirmar"), "diz que nada se instala sem confirmação: " + aviso.texto());
            }
        }
        assertTrue(ConteudoDosAvisos.deConexao(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_NAO_CRIADO, null, null), AGORA, FUSO).titulo()
            .contains("laboratório local ainda não foi criado"));
        assertTrue(ConteudoDosAvisos.deConexao(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_SEM_DOCKER, null, null), AGORA, FUSO).titulo()
            .contains("Docker não está instalado"));
        assertTrue(ConteudoDosAvisos.deConexao(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_DOCKER_PARADO, null, null), AGORA, FUSO).titulo()
            .contains("Docker não está rodando"));
    }

    @Test
    void agenteCriadoMasMudoOfereceTentarAgoraEPrepararQueORecriaSeForPreciso() {
        EstadoServidor e = local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.INSTALADO_SEM_RESPOSTA,
            new Falha(Falha.Tipo.LOCAL_AGENTE_FORA, "O agente do laboratório local não responde."), null);
        var aviso = ConteudoDosAvisos.deConexao(e, AGORA, FUSO);
        assertEquals(List.of(Acao.PREPARAR_COMPUTADOR, Acao.TENTAR_AGORA), aviso.acoes(), "a faixa aponta para Preparar, não só para Tentar agora");
        assertFalse((aviso.titulo() + aviso.texto()).toLowerCase().contains("ssh"));
    }

    @Test
    void containerParadoEUsuarioSemPermissaoNoDockerTemFraseAcaoPrepararEOComandoDoGrupo() {
        var parado = ConteudoDosAvisos.deConexao(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_CONTAINER_PARADO, null, null), AGORA, FUSO);
        assertEquals(List.of(Acao.PREPARAR_COMPUTADOR, Acao.TENTAR_AGORA), parado.acoes());
        assertTrue(parado.titulo().contains("parado"), parado.titulo());
        assertTrue(parado.texto().contains("recria o agente") && parado.texto().contains("confirmação"), parado.texto());

        var semPermissao = ConteudoDosAvisos.deConexao(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_SEM_PERMISSAO, null, null), AGORA, FUSO);
        assertEquals(List.of(Acao.PREPARAR_COMPUTADOR, Acao.TENTAR_AGORA), semPermissao.acoes());
        assertTrue(semPermissao.texto().contains("sudo usermod -aG docker $USER"), semPermissao.texto());
        assertFalse(semPermissao.titulo().contains("não está rodando"), "permissão não é Docker parado: " + semPermissao.titulo());
        assertTrue(SituacaoDoAgente.LOCAL_SEM_PERMISSAO.oferecerPreparar() && SituacaoDoAgente.LOCAL_CONTAINER_PARADO.oferecerPreparar());
        assertEquals("Sem permissão no Docker", ApresentacaoDoEstado.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_SEM_PERMISSAO, null, null)).texto());
        assertEquals("Agente local parado", ApresentacaoDoEstado.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_CONTAINER_PARADO, null, null)).texto());
    }

    @Test
    void oRemotoContinuaComAsFrasesDeSempre() {
        var aviso = ConteudoDosAvisos.deConexao(remoto(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.NAO_INSTALADO), AGORA, FUSO);
        assertEquals(List.of(Acao.INSTALAR_AGENTE), aviso.acoes());
        assertEquals(ConteudoDosAvisos.TEXTO_DO_AGENTE_NAO_INSTALADO, aviso.texto());
    }

    @Test
    void oEstadoNaBarraDizOQueFalta() {
        assertEquals("Falta criar o laboratório", ApresentacaoDoEstado.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_NAO_CRIADO, null, null)).texto());
        assertEquals("Docker parado", ApresentacaoDoEstado.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_DOCKER_PARADO, null, null)).texto());
        assertEquals("Docker não instalado", ApresentacaoDoEstado.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_SEM_DOCKER, null, null)).texto());
        assertEquals("Agente local não responde",
            ApresentacaoDoEstado.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.INSTALADO_SEM_RESPOSTA, null, null)).texto());
        assertEquals("Conectado", ApresentacaoDoEstado.de(local(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA, null, null)).texto());
    }

    @Test
    void aVisaoGeralFalaDeLaboratorioEOBotaoMudaDeNome() {
        SituacaoDaVisao s = SituacaoDaVisao.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_NAO_CRIADO, null, null), false, null, AGORA, FUSO);
        assertEquals(SituacaoDaVisao.Modo.LOCAL_A_PREPARAR, s.modo());
        assertTrue(s.modo().naoInstalado());
        assertTrue(s.oferecerInstalar(), "o botão do aviso existe (e se chama Preparar este computador)");
        assertFalse((s.titulo() + s.texto()).toLowerCase().contains("ssh"));
        SituacaoDaVisao mudo = SituacaoDaVisao.de(local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.INSTALADO_SEM_RESPOSTA, null, null), false, null, AGORA, FUSO);
        assertFalse(mudo.texto().toLowerCase().contains("servidor responde"), mudo.texto());
        assertFalse(mudo.oferecerInstalar());
    }

    @Test
    void oMenuDoLocalTemPrepararERemoverLaboratorioSemEditarNemInstalarAgente() {
        EstadoServidor naoCriado = local(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.LOCAL_NAO_CRIADO, null, null);
        List<Item> itens = MenuDoServidor.itensPara(naoCriado);
        assertEquals(List.of(Item.ATUALIZAR, Item.CONECTAR, Item.PREPARAR_COMPUTADOR, Item.REMOVER), itens);
        assertFalse(itens.contains(Item.REMOVER_LABORATORIO), "sem agente criado não há o que remover");

        VersaoAgente monitor = new VersaoAgente(1, "v22", false, "mac", "desligado", "monitor", RecursosDoAgente.SO_MONITOR, "0.1.0-abc1234");
        List<Item> no_ar = MenuDoServidor.itensPara(local(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA, null, monitor));
        assertTrue(no_ar.containsAll(List.of(Item.PREPARAR_COMPUTADOR, Item.REMOVER_LABORATORIO, Item.REMOVER)));
        assertFalse(no_ar.contains(Item.ATUALIZAR_AGENTE));
        assertFalse(no_ar.contains(Item.REMOVER_AGENTE));
        assertFalse(no_ar.contains(Item.EDITAR));
        assertFalse(no_ar.contains(Item.INSTALAR_AGENTE));

        // o remoto nada ganhou
        assertFalse(MenuDoServidor.itensPara(remoto(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA)).contains(Item.PREPARAR_COMPUTADOR));
        assertTrue(MenuDoServidor.itensPara(remoto(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA)).contains(Item.EDITAR));
    }
}
