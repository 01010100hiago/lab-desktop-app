package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.componentes.GraficoDeBarras.Barra;
import br.com.labdesktop.ui.telas.ModeloDeConsumo.BlocoDoLimite;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O consumo do projeto: o DTO, as barras dos 30 dias (com zeros), os totais, o Harness e o bloco do limite. */
class ModeloDeConsumoTest {

    static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Test
    void oDtoLeAFixtureSinteticaInteira() {
        TokensDoProjeto t = FixturesDeApi.consumoSintetico();
        assertEquals("orbita", t.nome());
        assertEquals(30, t.porDia().size());
        assertEquals(4, t.modelos().size());
        assertEquals(6, t.sessoes().size());
        assertNotNull(t.totais().tudo().custoUsd());
        assertEquals("exemplo-org/orbita", t.harness().repo());
        assertEquals(12, t.harness().entregas().size());
        assertTrue(t.temConsumo());
    }

    @Test
    void campoNovoEAusenteNaoDerrubamOLeitor() {
        TokensDoProjeto t = FixturesDeApi.de("{\"nome\":\"x\",\"campo_novo\":{\"a\":1},\"totais\":{\"hoje\":{\"custo_usd\":1.5,\"novo\":2}}}",
            TokensDoProjeto.class);
        assertEquals(1.5, t.totais().hoje().custoUsd());
        assertNull(t.totais().semana());
        assertTrue(t.porDia().isEmpty() && t.modelos().isEmpty() && t.sessoes().isEmpty(), "listas nunca são null");
        assertNull(t.harness());
        assertFalse(t.temConsumo());
    }

    @Test
    void mergeEBooleanoOuTextoCurto() {
        TokensDoProjeto t = FixturesDeApi.de("{\"harness\":{\"entregas\":[{\"merge\":true},{\"merge\":false},{\"merge\":\"em revisão\"},{\"merge\":null}]}}",
            TokensDoProjeto.class);
        var e = t.harness().entregas();
        assertEquals("mesclado", e.get(0).mergeEmTexto());
        assertEquals("não mesclado", e.get(1).mergeEmTexto());
        assertEquals("em revisão", e.get(2).mergeEmTexto());
        assertNull(e.get(3).mergeEmTexto());
    }

    @Test
    void textoDoServidorEntraLimpoNoDto() {
        TokensDoProjeto t = FixturesDeApi.de("{\"modelos\":[{\"modelo\":\"<html><b>x</b>\"}],\"sessoes\":[{\"id\":\"a\\u001b[31mb\"}]}", TokensDoProjeto.class);
        assertFalse(t.modelos().get(0).modelo().toLowerCase().startsWith("<html"));
        assertEquals("ab", t.sessoes().get(0).id().replace("[31m", ""), "o escape saiu");
    }

    // ---------------------------------------------------------------------------------------------
    // Barras
    // ---------------------------------------------------------------------------------------------

    @Test
    void sao30BarrasComOsDiasSemUsoEmZero() {
        List<Barra> b = ModeloDeConsumo.barras(FixturesDeApi.consumoSintetico());
        assertEquals(30, b.size());
        assertEquals("25 de set", b.get(0).rotulo());
        assertEquals("24 de out", b.get(29).rotulo());
        assertEquals(0.0, b.get(0).valor());
        assertEquals("25 de set: sem consumo", b.get(0).dica(), "zero não some: a dica diz que não houve consumo");
        long zeros = b.stream().filter(x -> x.valor() == 0).count();
        assertEquals(13, zeros, "os dias da fixture sem uso");
        assertEquals(3.2, b.get(2).valor(), 1e-9);
        assertTrue(b.get(2).dica().startsWith("27 de set: US$ 3,20, "), b.get(2).dica());
        assertTrue(b.get(2).dica().contains("respostas"));
    }

    @Test
    void semConsumoNenhumTodasAsBarrasSaoZero() {
        TokensDoProjeto vazio = FixturesDeApi.de(
            "{\"por_dia\":[{\"dia\":\"2026-10-23\",\"custo_usd\":0},{\"dia\":\"2026-10-24\",\"custo_usd\":null}]}", TokensDoProjeto.class);
        List<Barra> b = ModeloDeConsumo.barras(vazio);
        assertEquals(2, b.size());
        assertTrue(b.stream().allMatch(x -> x.valor() == 0));
        assertFalse(vazio.temConsumo());
        assertEquals("Consumo dos últimos 2 dias: US$ 0,00 no total; sem uso no período.", ModeloDeConsumo.resumoDoGrafico(vazio));
    }

    @Test
    void resumoELegendaDizemOTotalEODiaMaisCaro() {
        TokensDoProjeto t = FixturesDeApi.consumoSintetico();
        assertEquals("Consumo dos últimos 30 dias: US$ 68,20 no total; o dia mais caro foi 16 de out, com US$ 8,40.",
            ModeloDeConsumo.resumoDoGrafico(t));
        assertEquals("US$ 68,20", ModeloDeConsumo.totalDoGrafico(t));
        assertEquals("16 de out (US$ 8,40)", ModeloDeConsumo.diaMaisCaro(t));
        assertEquals("US$ 8,40", ModeloDeConsumo.valorDoAlto(8.4));
    }

    @Test
    void semDadoAindaNaoQuebra() {
        assertTrue(ModeloDeConsumo.barras(null).isEmpty());
        assertEquals("Consumo dos últimos 30 dias: sem dados.", ModeloDeConsumo.resumoDoGrafico(null));
        assertEquals(List.of(), ModeloDeConsumo.harness(null));
    }

    // ---------------------------------------------------------------------------------------------
    // Totais, Harness, tabelas
    // ---------------------------------------------------------------------------------------------

    @Test
    void quatroTotaisSemSelo() {
        var k = ModeloDeConsumo.totais(FixturesDeApi.consumoSintetico());
        assertEquals(List.of("HOJE", "7 DIAS", "30 DIAS", "TOTAL"), k.stream().map(x -> x.rotulo()).toList());
        assertEquals("US$ 0,00", k.get(0).valor());
        assertEquals("US$ 68,20", k.get(2).valor());
        assertTrue(k.stream().allMatch(x -> x.semSelo()), "um total não é um estado");
        assertTrue(k.get(1).legenda().contains("respostas"), k.get(1).legenda());
        assertTrue(k.get(1).dica().contains("equivalente de API"), "o custo é sempre dito como equivalente de API");
    }

    @Test
    void totaisAindaCalculandoMostramTraco() {
        var k = ModeloDeConsumo.totais(null);
        assertEquals(4, k.size());
        assertTrue(k.stream().allMatch(x -> x.valor().equals("—") && x.legenda().equals("sem leitura")));
    }

    @Test
    void harnessEmItensSeparados() {
        assertEquals(List.of(List.of("Entregas", "12"), List.of("Iniciados", "14"), List.of("Tokens", "143,2 mil")),
            ModeloDeConsumo.harness(FixturesDeApi.consumoSintetico()));
        assertEquals(List.of(), ModeloDeConsumo.harness(FixturesDeApi.de("{\"harness\":{\"entregas\":[]}}", TokensDoProjeto.class)),
            "sem nada de Harness, os itens somem");
    }

    @Test
    void duracaoEAtribuicao() {
        assertEquals("2 h 20 min", ModeloDeConsumo.duracao(Instant.parse("2026-10-24T13:00:00Z"), Instant.parse("2026-10-24T15:20:00Z")));
        assertEquals("menos de 1 min", ModeloDeConsumo.duracao(Instant.parse("2026-10-24T13:00:00Z"), Instant.parse("2026-10-24T13:00:30Z")));
        assertEquals("—", ModeloDeConsumo.duracao(null, Instant.now()));
        assertEquals("—", ModeloDeConsumo.duracao(Instant.parse("2026-10-24T13:00:00Z"), Instant.parse("2026-10-24T12:00:00Z")));
        assertEquals("direta", ModeloDeConsumo.atribuicao("direta"));
        assertEquals("estimada", ModeloDeConsumo.atribuicao("estimada"));
        assertEquals("—", ModeloDeConsumo.atribuicao(null));
    }

    // ---------------------------------------------------------------------------------------------
    // Limite
    // ---------------------------------------------------------------------------------------------

    private static Limite limite(Integer teto, double usado, String estado, boolean liberado, String motivoDaPausa) {
        return new Limite(teto, usado, estado, liberado,
            motivoDaPausa == null ? null : new br.com.labdesktop.api.modelo.Pausa(motivoDaPausa, AGORA.minusSeconds(1800)),
            Instant.parse("2026-10-29T18:00:00Z"), null);
    }

    @Test
    void limiteDentroDoTeto() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limite(20, 14, "ok", false, null), AGORA, SP);
        assertEquals("14% de 20%", b.valor());
        assertEquals("dentro do limite", b.estado());
        assertEquals(Gravidade.OK, b.gravidade());
        assertTrue(b.temBarra());
        assertEquals(0.7, b.fracao(), 1e-9);
        assertEquals(Severidade.NORMAL, b.severidade());
        assertTrue(b.explicacao().contains("Semana zera em 4 d 21 h"), b.explicacao());
        assertFalse(b.pausado());
    }

    @Test
    void limitePertoDoTetoEAtencao() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limite(20, 16.5, "atencao", false, null), AGORA, SP);
        assertEquals("atenção", b.estado());
        assertEquals(Gravidade.ATENCAO, b.gravidade());
        assertEquals(Severidade.ALTA, b.severidade());
        assertTrue(b.explicacao().contains("perto do limite"));
    }

    @Test
    void limitePausadoPeloTeto() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limite(20, 22.4, "pausado", false, "teto"), AGORA, SP);
        assertEquals("22% de 20%", b.valor());
        assertEquals("pausado", b.estado());
        assertEquals(Gravidade.PERIGO, b.gravidade());
        assertEquals(1.0, b.fracao(), "a barra nunca passa de cheia");
        assertEquals(Severidade.CRITICA, b.severidade());
        assertTrue(b.pausado());
        assertTrue(b.explicacao().contains("chegou ao limite"), b.explicacao());
    }

    @Test
    void limitePausadoPeloFreioDizDoFreio() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limite(20, 3, "pausado", false, "freio"), AGORA, SP);
        assertTrue(b.explicacao().contains("freio da conta"), b.explicacao());
    }

    @Test
    void limiteLiberadoEhOkSemTeto() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limite(20, 30, "ok", true, null), AGORA, SP);
        assertEquals("liberado", b.estado());
        assertTrue(b.liberado());
        assertTrue(b.explicacao().contains("Sem limite e sem freio até a semana virar"), b.explicacao());
    }

    @Test
    void semTetoNaoTemBarra() {
        BlocoDoLimite b = ModeloDeConsumo.limite(limite(null, 3, "ok", false, null), AGORA, SP);
        assertEquals("sem limite", b.estado());
        assertFalse(b.temBarra());
        assertFalse(b.temTeto());
        assertTrue(b.valor().startsWith("sem limite"), b.valor());
    }

    @Test
    void semLeituraDoLimite() {
        BlocoDoLimite b = ModeloDeConsumo.limite(null, AGORA, SP);
        assertEquals("—", b.valor());
        assertEquals("sem leitura", b.estado());
        assertFalse(b.temBarra());
    }
}
