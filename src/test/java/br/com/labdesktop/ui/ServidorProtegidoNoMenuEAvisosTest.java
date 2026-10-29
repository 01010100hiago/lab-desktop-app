package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.RecursosDoAgente;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ProtecaoDoServidor;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.MenuDoServidor.Item;
import br.com.labdesktop.ui.telas.SituacaoDaVisao;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Servidor protegido, as peças PURAS que decidem o que a tela oferece: o menu, o aviso fixo e a situação da Visão geral. Em cada
 * {@link SituacaoDoAgente}, com e sem a marca: protegido nunca oferece Instalar, Atualizar nem Remover agente; sem a marca, tudo igual a antes.
 */
class ServidorProtegidoNoMenuEAvisosTest {

    private static final Instant AGORA = Instant.parse("2026-10-29T12:00:00Z");
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final List<Item> INSTALA_ATUALIZA_REMOVE = List.of(Item.INSTALAR_AGENTE, Item.ATUALIZAR_AGENTE, Item.REMOVER_AGENTE);

    private static final VersaoAgente MONITOR = new VersaoAgente(1, "v22", false, "mac", "desligado", "monitor", RecursosDoAgente.SO_MONITOR, "0.1.0-abc1234");
    private static final VersaoAgente LAB = new VersaoAgente(1, "v22", false, "mac", "desligado", "lab", RecursosDoAgente.TUDO, null);

    private static EstadoServidor remoto(EstadoConexao c, SituacaoDoAgente situacao, VersaoAgente versao, boolean protegido) {
        return new EstadoServidor("s1", "vps-1", c, null, 0, null, null, 1, Estado2fa.DESLIGADO, "mac", versao, null, situacao, false, protegido);
    }

    @Test
    void protegidoNuncaOfereceInstalarAtualizarNemRemoverEmNenhumaSituacaoDoAgente() {
        for (SituacaoDoAgente s : SituacaoDoAgente.values()) {
            for (EstadoConexao c : EstadoConexao.values()) {
                for (VersaoAgente v : new VersaoAgente[] {null, MONITOR, LAB}) {
                    EstadoServidor e = remoto(c, s, v, true);
                    List<Item> itens = MenuDoServidor.itensPara(e);
                    for (Item proibido : INSTALA_ATUALIZA_REMOVE) {
                        assertFalse(itens.contains(proibido), proibido + " apareceu em servidor protegido (" + c + ", " + s + ", " + (v == null ? "sem versão" : v.perfil()) + ")");
                    }
                    assertFalse(e.ofereceInstalarAgente());
                    assertFalse(e.ofereceAtualizarERemoverAgente());
                    ConteudoDosAvisos.AvisoDeConexao aviso = ConteudoDosAvisos.deConexao(e, AGORA, SP);
                    if (aviso != null) {
                        assertFalse(aviso.acoes().contains(Acao.INSTALAR_AGENTE), "o aviso trouxe Instalar agente (" + c + ", " + s + ")");
                    }
                    SituacaoDaVisao visao = SituacaoDaVisao.de(e, false, null, AGORA, SP);
                    assertFalse(visao.oferecerInstalar(), "a Visão geral ofereceu instalar (" + c + ", " + s + ")");
                }
            }
        }
    }

    @Test
    void semAMarcaTudoContinuaComoAntes() {
        // instalar: só quando o SSH está de pé sem agente e a leitura diz que dá
        for (SituacaoDoAgente s : SituacaoDoAgente.values()) {
            EstadoServidor e = remoto(EstadoConexao.SSH_OK_SEM_AGENTE, s, null, false);
            assertEquals(s == SituacaoDoAgente.NAO_INSTALADO || s == SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER || s.oferecerPreparar(), e.ofereceInstalarAgente(), s.name());
            assertEquals(e.ofereceInstalarAgente(), MenuDoServidor.itensPara(e).contains(Item.INSTALAR_AGENTE), s.name());
        }
        // atualizar e remover: só o agente do monitor, respondendo
        assertTrue(MenuDoServidor.itensPara(remoto(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA, MONITOR, false)).containsAll(List.of(Item.ATUALIZAR_AGENTE, Item.REMOVER_AGENTE)));
        assertFalse(MenuDoServidor.itensPara(remoto(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA, LAB, false)).contains(Item.ATUALIZAR_AGENTE));
        assertFalse(MenuDoServidor.itensPara(remoto(EstadoConexao.RECONECTANDO, SituacaoDoAgente.DESCONHECIDA, MONITOR, false)).contains(Item.REMOVER_AGENTE));
    }

    @Test
    void protegidoComAgenteMonitorRespondendoTiraAtualizarERemoverMasMantemEditarERemoverServidor() {
        List<Item> sem = MenuDoServidor.itensPara(remoto(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA, MONITOR, false));
        List<Item> com = MenuDoServidor.itensPara(remoto(EstadoConexao.CONECTADO, SituacaoDoAgente.DESCONHECIDA, MONITOR, true));
        assertTrue(sem.contains(Item.ATUALIZAR_AGENTE));
        assertFalse(com.contains(Item.ATUALIZAR_AGENTE));
        assertFalse(com.contains(Item.REMOVER_AGENTE));
        // o usuário precisa chegar ao Editar para desmarcar, e tirar o servidor do app continua valendo
        assertTrue(com.contains(Item.EDITAR));
        assertTrue(com.contains(Item.REMOVER));
    }

    @Test
    void oAvisoDeAgenteNaoInstaladoDizQueEProtegidoESemBotao() {
        EstadoServidor e = remoto(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.NAO_INSTALADO, null, true);
        ConteudoDosAvisos.AvisoDeConexao aviso = ConteudoDosAvisos.deConexao(e, AGORA, SP);
        assertEquals(List.of(), aviso.acoes());
        assertEquals(Gravidade.NEUTRO, aviso.gravidade());
        assertEquals(ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_AGENTE, aviso.texto());
        assertTrue(aviso.texto().contains("protegido"));
        assertTrue(aviso.texto().contains("Editar servidor"), "diz como mudar");
        assertTrue(aviso.titulo().contains("vps-1"));

        // sem a marca: o aviso de sempre, com o botão
        ConteudoDosAvisos.AvisoDeConexao igual = ConteudoDosAvisos.deConexao(remoto(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.NAO_INSTALADO, null, false), AGORA, SP);
        assertEquals(List.of(Acao.INSTALAR_AGENTE), igual.acoes());
        assertEquals(ConteudoDosAvisos.TEXTO_DO_AGENTE_NAO_INSTALADO, igual.texto());
        assertEquals(Gravidade.ATENCAO, igual.gravidade());
    }

    @Test
    void semPermissaoNoDockerEProtegidoNaoOfereceInstalarEDizPorque() {
        EstadoServidor e = remoto(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER, null, true);
        ConteudoDosAvisos.AvisoDeConexao aviso = ConteudoDosAvisos.deConexao(e, AGORA, SP);
        assertEquals(List.of(), aviso.acoes());
        assertEquals(ProtecaoDoServidor.TEXTO_DO_AVISO_SEM_PERMISSAO, aviso.texto());
        assertFalse(aviso.texto().contains("Clique em Instalar agente"), "o texto de sempre manda clicar num botão que não existe aqui");
        assertEquals(List.of(Acao.INSTALAR_AGENTE), ConteudoDosAvisos.deConexao(remoto(EstadoConexao.SSH_OK_SEM_AGENTE, SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER, null, false), AGORA, SP).acoes());
    }

    @Test
    void aVisaoGeralProtegidaFalaDaProtecaoENaoTemBotao() {
        for (SituacaoDoAgente s : List.of(SituacaoDoAgente.NAO_INSTALADO, SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER)) {
            SituacaoDaVisao v = SituacaoDaVisao.de(remoto(EstadoConexao.SSH_OK_SEM_AGENTE, s, null, true), false, null, AGORA, SP);
            assertEquals(SituacaoDaVisao.Modo.AGENTE_NAO_INSTALADO, v.modo());
            assertFalse(v.oferecerInstalar());
            assertTrue(v.temAviso());
            assertTrue(v.titulo().contains("protegido"), v.titulo());
            assertTrue(v.titulo().contains("Editar servidor"), "a linha no lugar do botão diz como mudar: " + v.titulo());
            assertTrue(v.texto().contains("Editar servidor"), v.texto());
            SituacaoDaVisao igual = SituacaoDaVisao.de(remoto(EstadoConexao.SSH_OK_SEM_AGENTE, s, null, false), false, null, AGORA, SP);
            assertTrue(igual.oferecerInstalar(), "sem a marca o botão continua");
        }
    }

    @Test
    void esteComputadorNuncaEProtegidoEPrepararContinua() {
        EstadoServidor local = new EstadoServidor("s2", "Este computador", EstadoConexao.SSH_OK_SEM_AGENTE, null, 0, null, null, 1, Estado2fa.DESLIGADO, null, null, null,
            SituacaoDoAgente.LOCAL_NAO_CRIADO, true, false);
        assertTrue(MenuDoServidor.itensPara(local).contains(Item.PREPARAR_COMPUTADOR));
        assertTrue(SituacaoDaVisao.de(local, false, null, AGORA, SP).oferecerInstalar());
    }

    private static EstadoServidor comFalha(EstadoConexao c, br.com.labdesktop.ssh.Falha falha, boolean protegido) {
        return new EstadoServidor("s1", "vps-1", c, falha, 0, null, null, 1, Estado2fa.DESLIGADO, "mac", null, null, SituacaoDoAgente.DESCONHECIDA, false, protegido);
    }

    @Test
    void protegidoNuncaOfereceInstalarAChaveComSenhaEmEstadoNenhum() {
        for (EstadoConexao c : EstadoConexao.values()) {
            for (br.com.labdesktop.ssh.Falha falha : new br.com.labdesktop.ssh.Falha[] {null,
                new br.com.labdesktop.ssh.Falha(br.com.labdesktop.ssh.Falha.Tipo.CHAVE_RECUSADA, "O servidor recusou a chave.")}) {
                EstadoServidor e = comFalha(c, falha, true);
                assertFalse(MenuDoServidor.itensPara(e).contains(Item.INSTALAR_CHAVE), "o menu ofereceu a chave em " + c);
                ConteudoDosAvisos.AvisoDeConexao aviso = ConteudoDosAvisos.deConexao(e, AGORA, SP);
                if (aviso != null) {
                    assertFalse(aviso.acoes().contains(Acao.INSTALAR_CHAVE), "o aviso ofereceu a chave em " + c);
                }
                assertFalse(SituacaoDaVisao.de(e, false, null, AGORA, SP).oferecerInstalarChave(), "a Visão geral ofereceu a chave em " + c);
            }
        }
    }

    @Test
    void protegidoComChaveRecusadaDizPorqueNaoHaBotaoEOCaminho() {
        var recusada = new br.com.labdesktop.ssh.Falha(br.com.labdesktop.ssh.Falha.Tipo.CHAVE_RECUSADA, "O servidor recusou a chave.");
        ConteudoDosAvisos.AvisoDeConexao aviso = ConteudoDosAvisos.deConexao(comFalha(EstadoConexao.ERRO_AUTENTICACAO, recusada, true), AGORA, SP);
        assertEquals(List.of(Acao.TENTAR_AGORA), aviso.acoes());
        assertTrue(aviso.texto().contains(ProtecaoDoServidor.CHAVE_RECUSADA_PROTEGIDO), aviso.texto());
        assertFalse(aviso.texto().contains("Instalar a chave com a senha"), "não manda clicar num botão que não existe: " + aviso.texto());
        assertTrue(SituacaoDaVisao.de(comFalha(EstadoConexao.ERRO_AUTENTICACAO, recusada, true), false, null, AGORA, SP).texto().contains("protegido"));
    }

    @Test
    void semAMarcaAChaveRecusadaContinuaComOBotaoEOMenuComoAntes() {
        var recusada = new br.com.labdesktop.ssh.Falha(br.com.labdesktop.ssh.Falha.Tipo.CHAVE_RECUSADA, "O servidor recusou a chave.");
        EstadoServidor e = comFalha(EstadoConexao.ERRO_AUTENTICACAO, recusada, false);
        assertTrue(MenuDoServidor.itensPara(e).contains(Item.INSTALAR_CHAVE));
        assertEquals(List.of(Acao.INSTALAR_CHAVE, Acao.TENTAR_AGORA), ConteudoDosAvisos.deConexao(e, AGORA, SP).acoes());
        assertTrue(SituacaoDaVisao.de(e, false, null, AGORA, SP).oferecerInstalarChave());
        assertTrue(MenuDoServidor.itensPara(comFalha(EstadoConexao.CONECTADO, null, false)).contains(Item.INSTALAR_CHAVE), "e em qualquer estado, ao lado de Editar");
    }

    @Test
    void oSeloEOTextoDeAjudaDizemOQueDeFatoEBloqueado() {
        assertTrue(ProtecaoDoServidor.EXPLICACAO_DO_SELO.contains("O app não instala, atualiza nem remove o agente, o Docker ou o Git, e não muda chaves nem a senha de acesso neste servidor."),
            ProtecaoDoServidor.EXPLICACAO_DO_SELO);
    }
}
