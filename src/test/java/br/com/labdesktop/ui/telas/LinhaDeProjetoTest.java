package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.telas.LinhaDeProjeto.TipoDeClaude;
import org.junit.jupiter.api.Test;

/** A tradução de {@code linhaProjeto} do index.html: o que cada coluna mostra, em texto. */
class LinhaDeProjetoTest {

    private static Projeto projeto(String json) {
        return FixturesDeApi.de(json, Projeto.class);
    }

    private static String base(String extras) {
        return "{\"nome\":\"x-api\",\"descricao\":\"API principal\",\"composes\":[\"docker-compose.yml\"],"
            + "\"containers\":[{\"nome\":\"a\",\"estado\":\"running\"},{\"nome\":\"b\",\"estado\":\"exited\"}],"
            + "\"rodando\":true,\"urls\":[\"lab-x.example.com\",\"x.example.com\"],\"mexido\":1,\"sessaoClaude\":false,"
            + "\"acessoDocker\":true,\"cpu\":1.34,\"mem\":432013312" + extras + "}";
    }

    @Test
    void estadoDoProjetoSegueARegraDoFront() {
        assertEquals(EstadoProjeto.PARCIAL, LinhaDeProjeto.de(projeto(base(""))).estado());
        assertEquals(Gravidade.ATENCAO, LinhaDeProjeto.de(projeto(base(""))).gravidadeDoEstado());
        Projeto semCompose = projeto("{\"nome\":\"p\",\"composes\":[],\"containers\":[]}");
        assertEquals(EstadoProjeto.SEM_COMPOSE, LinhaDeProjeto.de(semCompose).estado());
        assertNull(LinhaDeProjeto.de(semCompose).containers(), "sem compose a coluna é o travessão");
        Projeto nunca = projeto("{\"nome\":\"p\",\"composes\":[\"c.yml\"],\"containers\":[]}");
        assertEquals(Gravidade.NEUTRO, LinhaDeProjeto.de(nunca).gravidadeDoEstado());
        assertEquals("0/0", LinhaDeProjeto.de(nunca).containers());
        Projeto noAr = projeto("{\"nome\":\"p\",\"composes\":[\"c.yml\"],\"containers\":[{\"estado\":\"running\"}]}");
        assertTrue(LinhaDeProjeto.de(noAr).estaNoAr());
        assertEquals(Gravidade.OK, LinhaDeProjeto.de(noAr).gravidadeDoEstado());
    }

    @Test
    void colunasNumericasEmPortuguesComVirgula() {
        LinhaDeProjeto l = LinhaDeProjeto.de(projeto(base("")));
        assertEquals("1/2", l.containers());
        assertEquals("1,3%", l.cpu());
        assertEquals("412 MB", l.memoria());
        LinhaDeProjeto semLeitura = LinhaDeProjeto.de(projeto("{\"nome\":\"p\",\"composes\":[\"c.yml\"],\"containers\":[]}"));
        assertNull(semLeitura.cpu());
        assertNull(semLeitura.memoria());
    }

    @Test
    void consumoDeSeteDiasComLimiteEBarra() {
        LinhaDeProjeto l = LinhaDeProjeto.de(projeto(base(",\"tokens\":{\"semana_custo_usd\":0.39,\"semana_saida\":11200,\"hoje_custo_usd\":0.1},"
            + "\"limite\":{\"teto_pct\":20,\"usado_pp\":16.2,\"estado\":\"atencao\",\"liberado\":false}")));
        assertEquals("US$ 0,39", l.consumo().custo());
        assertEquals(" · 11,2 mil", l.consumo().tokens());
        assertEquals("16% de 20% da semana", l.limite().texto());
        assertEquals(Severidade.ALTA, l.limite().severidade());
        assertTrue(l.limite().temBarra());
        assertEquals(0.81, l.limite().fracao(), 1e-9);
        assertFalse(l.limite().pausado());
        assertTrue(l.oferecerDefinirLimite());
    }

    @Test
    void semConsumoSemLimiteESemTetoNaoDesenhaBarra() {
        LinhaDeProjeto vazio = LinhaDeProjeto.de(projeto(base("")));
        assertNull(vazio.consumo());
        assertNull(vazio.limite());
        assertFalse(vazio.oferecerDefinirLimite());
        LinhaDeProjeto semTeto = LinhaDeProjeto.de(projeto(base(",\"limite\":{\"teto_pct\":null,\"usado_pp\":3.1,\"estado\":\"ok\"}")));
        assertEquals("sem limite · 3,1% da semana", semTeto.limite().texto());
        assertFalse(semTeto.limite().temBarra());
    }

    @Test
    void limiteLiberadoDizLiberadoNoTexto() {
        LinhaDeProjeto l = LinhaDeProjeto.de(projeto(base(",\"limite\":{\"teto_pct\":20,\"usado_pp\":25,\"estado\":\"ok\",\"liberado\":true}")));
        assertTrue(l.limite().texto().endsWith(" · liberado"), l.limite().texto());
        assertTrue(l.limite().liberado());
    }

    @Test
    void gitEUrls() {
        LinhaDeProjeto l = LinhaDeProjeto.de(projeto(base(",\"git\":{\"branch\":\"main\",\"commit\":\"a1b2c3d\",\"sujo\":true}")));
        assertEquals("main@a1b2c3d", l.git());
        assertTrue(l.gitSujo());
        assertEquals("lab-x.example.com", l.urlPrincipal());
        assertEquals(1, l.urlsExtras());
        LinhaDeProjeto sem = LinhaDeProjeto.de(projeto("{\"nome\":\"p\",\"composes\":[],\"containers\":[]}"));
        assertNull(sem.git());
        assertNull(sem.urlPrincipal());
        assertEquals(0, sem.urlsExtras());
    }

    @Test
    void colunaClaudeTemQuatroRamosNaOrdemDoFront() {
        // login pendente vence tudo
        LinhaDeProjeto login = LinhaDeProjeto.de(projeto(base(",\"login\":{\"url\":\"https://claude.ai/oauth/authorize?x=1\"},\"sessaoClaude\":true")));
        assertEquals(TipoDeClaude.LOGIN_PENDENTE, login.claude().tipo());
        assertTrue(login.temAlerta());
        assertEquals("https://claude.ai/oauth/authorize?x=1", login.urlDeLogin());

        // sessão aberta, atualizada: oferece reiniciar a sessão no menu
        LinhaDeProjeto aberta = LinhaDeProjeto.de(projeto(base(",\"sessaoClaude\":true,\"sessaoInfo\":{\"atualizada\":true,\"faltando\":[]}").replace("\"sessaoClaude\":false,", "")));
        assertEquals(TipoDeClaude.SESSAO_ABERTA, aberta.claude().tipo());
        assertFalse(aberta.claude().desatualizada());
        assertTrue(aberta.oferecerReiniciarSessao());
        assertTrue(aberta.oferecerFecharSessao());
        assertFalse(aberta.oferecerAbrirClaude());

        // desatualizada: selo com o que falta, botão Reabrir, sem "reiniciar" no menu
        LinhaDeProjeto velha = LinhaDeProjeto.de(projeto(base(",\"sessaoClaude\":true,\"sessaoInfo\":{\"atualizada\":false,\"faltando\":[\"imagem antiga (Claude desatualizado)\",\"x\"]}")
            .replace("\"sessaoClaude\":false,", "")));
        assertTrue(velha.claude().desatualizada());
        assertEquals("imagem antiga (Claude desatualizado), x", velha.claude().motivoDesatualizada());
        assertFalse(velha.oferecerReiniciarSessao());

        // pausada pelo teto: selo + Retomar, e NÃO oferece "abrir Claude" (o servidor responderia 409)
        LinhaDeProjeto pausada = LinhaDeProjeto.de(projeto(base(",\"limite\":{\"teto_pct\":20,\"usado_pp\":22,\"estado\":\"pausado\","
            + "\"pausa\":{\"motivo\":\"teto\",\"em\":\"2026-10-24T13:00:00Z\"}}")));
        assertEquals(TipoDeClaude.PAUSADA, pausada.claude().tipo());
        assertEquals("pausada pelo limite", pausada.claude().rotuloDaPausa());
        assertFalse(pausada.claude().pausadaPorFreio());
        assertFalse(pausada.oferecerAbrirClaude());
        assertNotNull(pausada.claude().pausadoEm());

        LinhaDeProjeto freio = LinhaDeProjeto.de(projeto(base(",\"limite\":{\"teto_pct\":null,\"usado_pp\":2,\"estado\":\"pausado\","
            + "\"pausa\":{\"motivo\":\"freio\",\"em\":\"2026-10-24T13:00:00Z\"}}")));
        assertEquals("pausada pelo freio", freio.claude().rotuloDaPausa());
        assertTrue(freio.claude().pausadaPorFreio());

        // nada
        LinhaDeProjeto nada = LinhaDeProjeto.de(projeto(base("")));
        assertEquals(TipoDeClaude.NENHUMA, nada.claude().tipo());
        assertTrue(nada.oferecerAbrirClaude());
    }

    @Test
    void botoesDaLinhaIniciarOuPararSegundoOsContainers() {
        LinhaDeProjeto algum = LinhaDeProjeto.de(projeto(base("")));
        assertTrue(algum.mostrarParar(), "parcial: Parar");
        assertFalse(algum.mostrarIniciar());
        LinhaDeProjeto parado = LinhaDeProjeto.de(projeto("{\"nome\":\"p\",\"composes\":[\"c.yml\"],\"containers\":[{\"estado\":\"exited\"}]}"));
        assertTrue(parado.mostrarIniciar());
        LinhaDeProjeto semCompose = LinhaDeProjeto.de(projeto("{\"nome\":\"p\",\"composes\":[],\"containers\":[]}"));
        assertFalse(semCompose.mostrarIniciar());
        assertFalse(semCompose.mostrarParar());
        assertFalse(semCompose.temMenu(), "sem compose, sem sessão e sem limite não há o que pôr no menu");
    }

    @Test
    void textoHostilDoServidorChegaNeutralizado() {
        Projeto hostil = projeto("{\"nome\":\"x\",\"descricao\":\"<html><img src='http://evil/x.png'>oi\\u202Eevil\","
            + "\"composes\":[\"c.yml\"],\"containers\":[{\"nome\":\"<html>c\",\"estado\":\"running\"}],"
            + "\"git\":{\"branch\":\"<html>main\",\"commit\":\"abc\"}}");
        LinhaDeProjeto l = LinhaDeProjeto.de(hostil);
        assertFalse(TextoSeguro.pareceHtml(l.descricao()), l.descricao());
        assertFalse(l.descricao().contains("‮"), "bidi removido");
        assertFalse(TextoSeguro.pareceHtml(l.git()), l.git());
    }

    @Test
    void oRetratoEIgualParaOMesmoDadoEDiferenteQuandoOCpuMuda() {
        LinhaDeProjeto a = LinhaDeProjeto.de(projeto(base("")));
        LinhaDeProjeto b = LinhaDeProjeto.de(projeto(base("")));
        assertEquals(a, b, "a lista não redesenha a linha que não mudou");
        LinhaDeProjeto c = LinhaDeProjeto.de(projeto(base("").replace("1.34", "9.9")));
        assertFalse(a.equals(c));
    }
}
