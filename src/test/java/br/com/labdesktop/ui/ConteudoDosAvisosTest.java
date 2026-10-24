package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.ConteudoDosAvisos.Aviso;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeConexao;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeCredito;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDePausas;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O que a faixa de aviso diz: conexão, crédito a partir de 75 % (perigo a partir de 90 %) e sessões pausadas. */
class ConteudoDosAvisosTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final VersaoAgente VERSAO = new VersaoAgente(1, "v22", false, "mac", "desligado");

    private static EstadoServidor estado(EstadoConexao c, Falha falha, LimitesConta limites) {
        return new EstadoServidor("s1", "lab", c, falha, 0, null, null, 1, Estado2fa.DESLIGADO, "mac-de-teste", VERSAO, limites);
    }

    private static LimitesConta limites(double cinco, double semana, boolean disponivel, boolean antiga, String pausados) {
        String json = "{\"disponivel\":" + disponivel + ",\"antiga\":" + antiga + ",\"lido_em\":\"2026-10-24T14:57:00Z\","
            + "\"cinco_horas\":{\"usado_pct\":" + cinco + ",\"zera_em\":\"2026-10-24T17:10:00Z\"},"
            + "\"semana\":{\"usado_pct\":" + semana + ",\"zera_em\":\"2026-10-29T18:00:00Z\"},"
            + "\"freio\":{\"pct\":null,\"ativo\":false},\"pausados\":" + pausados + ",\"por_projeto\":{"
            + "\"app-loja\":{\"usado_pp\":22.1,\"teto_pct\":20,\"estado\":\"pausado\"}}}";
        return FixturesDeApi.de(json, LimitesConta.class);
    }

    @Test
    void creditoAcendeEm75ESobeParaPerigoEm90() {
        assertNull(ConteudoDosAvisos.deCredito(limites(74.9, 40, true, false, "[]"), AGORA, FUSO), "74,9% ainda não avisa");
        AvisoDeCredito amarelo = ConteudoDosAvisos.deCredito(limites(40, 75.0, true, false, "[]"), AGORA, FUSO);
        assertNotNull(amarelo);
        assertEquals(Gravidade.ATENCAO, amarelo.gravidade());
        assertEquals("Crédito do Claude acima de 75%", amarelo.titulo());
        AvisoDeCredito quase = ConteudoDosAvisos.deCredito(limites(40, 89.9, true, false, "[]"), AGORA, FUSO);
        assertEquals(Gravidade.ATENCAO, quase.gravidade(), "89,9% ainda é atenção");
        AvisoDeCredito perigo = ConteudoDosAvisos.deCredito(limites(40, 90.0, true, false, "[]"), AGORA, FUSO);
        assertEquals(Gravidade.PERIGO, perigo.gravidade());
        assertEquals("Crédito do Claude quase no fim", perigo.titulo());
    }

    @Test
    void asPartesDizemAJanelaOPercentualEQuandoZera() {
        AvisoDeCredito a = ConteudoDosAvisos.deCredito(limites(42, 78, true, false, "[]"), AGORA, FUSO);
        assertEquals(List.of("janela de 5 h em 42%", "semana em 78% (zera em 5 d 3 h)"), a.partes(),
            "só a janela acima de 75% leva o 'zera em'");
    }

    @Test
    void semLeituraOuLeituraAntigaNaoAvisaCredito() {
        assertNull(ConteudoDosAvisos.deCredito(limites(99, 99, false, false, "[]"), AGORA, FUSO));
        assertNull(ConteudoDosAvisos.deCredito(limites(99, 99, true, true, "[]"), AGORA, FUSO), "leitura com mais de 30 min não vale");
        assertNull(ConteudoDosAvisos.deCredito(null, AGORA, FUSO));
    }

    @Test
    void pausasValemMesmoSemLeituraDoPlano() {
        LimitesConta l = limites(1, 1, false, false,
            "[{\"projeto\":\"app-loja\",\"motivo\":\"teto\",\"em\":\"2026-10-24T14:00:00Z\"},"
                + "{\"projeto\":\"lab\",\"motivo\":\"freio\",\"em\":\"2026-10-24T14:00:00Z\"}]");
        AvisoDePausas p = ConteudoDosAvisos.dePausas(l);
        assertNotNull(p);
        assertEquals("Sessões pausadas", p.titulo());
        assertEquals("pausada pelo limite (22% de 20%)", p.itens().get(0).motivo());
        assertEquals("sessão do lab", p.itens().get(1).nome());
        assertEquals("lab", p.itens().get(1).projeto(), "o nome real vai para o Retomar");
        assertEquals("pausada pelo freio global", p.itens().get(1).motivo());
        assertEquals(0, p.maisN());
    }

    @Test
    void umaSoPausadaUsaOSingularEMaisDeTresViraEMaisN() {
        LimitesConta uma = limites(1, 1, true, false, "[{\"projeto\":\"a\",\"motivo\":\"freio\"}]");
        assertEquals("Sessão pausada", ConteudoDosAvisos.dePausas(uma).titulo());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            sb.append(i > 0 ? "," : "").append("{\"projeto\":\"p").append(i).append("\",\"motivo\":\"freio\"}");
        }
        AvisoDePausas muitas = ConteudoDosAvisos.dePausas(limites(1, 1, true, false, sb.append("]").toString()));
        assertEquals(3, muitas.itens().size());
        assertEquals(2, muitas.maisN());
        assertNull(ConteudoDosAvisos.dePausas(limites(1, 1, true, false, "[]")));
    }

    @Test
    void cadaEstadoDeConexaoTemSuaFaixaEAAcaoCerta() {
        assertNull(ConteudoDosAvisos.deConexao(estado(EstadoConexao.CONECTADO, null, null), AGORA, FUSO));
        assertNull(ConteudoDosAvisos.deConexao(estado(EstadoConexao.CONECTANDO, null, null), AGORA, FUSO));

        AvisoDeConexao reconectando = ConteudoDosAvisos.deConexao(new EstadoServidor("s1", "lab", EstadoConexao.RECONECTANDO,
            new Falha(Falha.Tipo.REDE, "sem rota"), 3, AGORA.plusSeconds(8), Instant.parse("2026-10-24T14:05:00Z"), 1,
            Estado2fa.DESLIGADO, "mac", null, null), AGORA, ZoneId.of("UTC"));
        assertEquals("Sem conexão com lab desde 14:05", reconectando.titulo());
        assertTrue(reconectando.texto().contains("tentativa 3, próxima em 8 s"), reconectando.texto());
        assertTrue(reconectando.texto().contains("última leitura"), "nunca tela vazia: " + reconectando.texto());
        assertEquals(List.of(Acao.TENTAR_AGORA), reconectando.acoes());

        assertEquals(List.of(Acao.TENTAR_AGORA), ConteudoDosAvisos.deConexao(
            estado(EstadoConexao.ERRO_AUTENTICACAO, new Falha(Falha.Tipo.CHAVE_RECUSADA, "recusada"), null), AGORA, FUSO).acoes());
        assertEquals(List.of(Acao.CONFERIR_IMPRESSAO), ConteudoDosAvisos.deConexao(
            estado(EstadoConexao.ERRO_CHAVE_HOST, new Falha(Falha.Tipo.PIN_AUSENTE, "sem pin"), null), AGORA, FUSO).acoes());
        AvisoDeConexao mudou = ConteudoDosAvisos.deConexao(
            estado(EstadoConexao.ERRO_CHAVE_HOST, new Falha(Falha.Tipo.PIN_DIVERGENTE, "mudou"), null), AGORA, FUSO);
        assertEquals(List.of(Acao.REMOVER_SERVIDOR), mudou.acoes(),
            "chave que mudou NÃO tem 'aceitar mesmo assim': o único caminho é remover e adicionar de novo");
        assertEquals(Gravidade.PERIGO, mudou.gravidade());
        assertFalse(mudou.texto().toLowerCase().contains("etapa"), "numeração interna do projeto não diz nada ao usuário: " + mudou.texto());
        assertTrue(mudou.texto().contains("Remover servidor") && mudou.texto().contains("adicione-o de novo"), mudou.texto());
        assertTrue(mudou.texto().contains("Se não reinstalou, não conecte"), mudou.texto());
        assertFalse(mudou.texto().contains("só pode"), "Editar o endereço também troca a impressão: nada de 'só pode': " + mudou.texto());
        assertEquals(List.of(Acao.REGISTRAR_TOKEN), ConteudoDosAvisos.deConexao(
            estado(EstadoConexao.TOKEN_RECUSADO, new Falha(Falha.Tipo.TOKEN_RECUSADO, "401"), null), AGORA, FUSO).acoes());
        AvisoDeConexao codigo = ConteudoDosAvisos.deConexao(
            estado(EstadoConexao.AGUARDANDO_2FA, new Falha(Falha.Tipo.CODIGO_2FA_PENDENTE, "pede"), null), AGORA, FUSO);
        assertEquals("Código do autenticador pendente", codigo.titulo());
        assertEquals(List.of(Acao.DIGITAR_CODIGO), codigo.acoes());
        assertEquals(List.of(Acao.CONECTAR), ConteudoDosAvisos.deConexao(estado(EstadoConexao.DESCONECTADO, null, null), AGORA, FUSO).acoes());
        assertEquals(List.of(), ConteudoDosAvisos.deConexao(estado(EstadoConexao.SUSPENSO, null, null), AGORA, FUSO).acoes());
        AvisoDeConexao antigo = ConteudoDosAvisos.deConexao(
            estado(EstadoConexao.SSH_OK_SEM_AGENTE, new Falha(Falha.Tipo.AGENTE_DESATUALIZADO, "404"), null), AGORA, FUSO);
        assertTrue(antigo.titulo().contains("antes do app nativo"), antigo.titulo());
    }

    @Test
    void semTokenOferecRegistrarETokenEstandoOkNaoAvisaNada() {
        EstadoServidor semToken = new EstadoServidor("s1", "lab", EstadoConexao.CONECTADO, null, 0, null, null, 1,
            Estado2fa.DESLIGADO, null, VERSAO, null);
        AvisoDeConexao a = ConteudoDosAvisos.deConexao(semToken, AGORA, FUSO);
        assertNotNull(a);
        assertEquals(List.of(Acao.REGISTRAR_TOKEN), a.acoes());
        assertNull(ConteudoDosAvisos.deConexao(estado(EstadoConexao.CONECTADO, null, null), AGORA, FUSO));
    }

    @Test
    void aOrdemEConexaoCreditoPausas() {
        LimitesConta l = limites(90, 95, true, false, "[{\"projeto\":\"app-loja\",\"motivo\":\"teto\"}]");
        List<Aviso> avisos = ConteudoDosAvisos.de(estado(EstadoConexao.RECONECTANDO, new Falha(Falha.Tipo.REDE, "x"), l), AGORA, FUSO);
        assertEquals(3, avisos.size());
        assertTrue(avisos.get(0) instanceof AvisoDeConexao);
        assertTrue(avisos.get(1) instanceof AvisoDeCredito);
        assertTrue(avisos.get(2) instanceof AvisoDePausas);
        assertFalse(ConteudoDosAvisos.de(estado(EstadoConexao.CONECTADO, null, null), AGORA, FUSO).stream().anyMatch(x -> x instanceof AvisoDeCredito));
    }
}
