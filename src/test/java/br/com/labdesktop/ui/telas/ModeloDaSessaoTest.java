package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.ui.telas.ModeloDaSessao.Atividade;
import br.com.labdesktop.ui.telas.ModeloDaSessao.Situacao;
import org.junit.jupiter.api.Test;

/** As decisões da parte Claude da aba Terminal: em que pé está, login pendente e o que o Claude faz agora. */
class ModeloDaSessaoTest {

    @Test
    void abertaFechadaEPausada() {
        assertEquals(Situacao.ABERTA, ModeloDaSessao.situacao(ModeloDoProjeto.de(FixturesDeApi.detalheDoProjetoSintetico())));
        DetalheProjeto fechada = TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false");
        assertEquals(Situacao.FECHADA, ModeloDaSessao.situacao(ModeloDoProjeto.de(fechada)));
        assertEquals(Situacao.PAUSADA_PELO_LIMITE, ModeloDaSessao.situacao(ModeloDoProjeto.de(TelaProjetoTest.detalhePausado())));
        DetalheProjeto freio = FixturesDeApi.de(TelaProjetoTest.detalheEmUmaLinha().replace("\"aberta\": true", "\"aberta\": false")
            .replace("\"estado\": \"ok\"", "\"estado\": \"pausado\"").replace("\"pausa\": null", "\"pausa\": {\"motivo\": \"freio\", \"em\": null}"),
            DetalheProjeto.class);
        assertEquals(Situacao.PAUSADA_PELO_FREIO, ModeloDaSessao.situacao(ModeloDoProjeto.de(freio)));
    }

    @Test
    void osRotulosSaoOsDoPainelWeb() {
        assertEquals("sessão aberta", Situacao.ABERTA.rotulo());
        assertEquals("sessão fechada", Situacao.FECHADA.rotulo());
        assertEquals("pausada pelo limite", Situacao.PAUSADA_PELO_LIMITE.rotulo());
        assertEquals("pausada pelo freio", Situacao.PAUSADA_PELO_FREIO.rotulo());
        assertEquals("pergunta pendente", Atividade.PERGUNTA_PENDENTE.rotulo());
        assertEquals("executando", Atividade.EXECUTANDO.rotulo());
        assertEquals("esperando você", Atividade.ESPERANDO_VOCE.rotulo());
        assertEquals("ocupado", Atividade.OCUPADO.rotulo());
    }

    @Test
    void loginPendentePelaListaOuPelaTela() {
        assertFalse(ModeloDaSessao.loginPendente(FixturesDeApi.telaSintetica("tela-executando"), null));
        assertFalse(ModeloDaSessao.loginPendente(null, null));
        assertTrue(ModeloDaSessao.loginPendente(FixturesDeApi.telaSintetica("tela-login"), null), "a tela pede o código");
        assertTrue(ModeloDaSessao.loginPendente(FixturesDeApi.telaSintetica("tela-executando"), "https://claude.ai/oauth/authorize?x=1"),
            "a lista já tinha visto o login");
        assertFalse(ModeloDaSessao.loginPendente(null, "  "));
    }

    @Test
    void atividadeSegueAOrdemDoPainelWeb() {
        assertEquals(Atividade.PERGUNTA_PENDENTE, ModeloDaSessao.atividade(FixturesDeApi.telaSintetica("tela-pergunta")));
        assertEquals(Atividade.EXECUTANDO, ModeloDaSessao.atividade(FixturesDeApi.telaSintetica("tela-executando")));
        assertEquals(Atividade.ESPERANDO_VOCE, ModeloDaSessao.atividade(FixturesDeApi.telaSintetica("tela-esperando")));
        assertEquals(Atividade.OCUPADO, ModeloDaSessao.atividade(FixturesDeApi.telaSintetica("tela-login")));
    }
}
