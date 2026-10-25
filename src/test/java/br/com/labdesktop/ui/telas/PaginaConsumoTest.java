package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoLimite;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.TelaProjetoTest.Cena;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A aba Consumo e limite: totais, barras dos 30 dias com zeros, limite da semana (ok, atenção, pausado, liberado), tabelas e Harness. */
@ExtendWith(VerificaEdt.class)
class PaginaConsumoTest {

    private static Cena cena(boolean acoes, DetalheProjeto d, TokensDoProjeto t) {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(acoes, Aba.CONSUMO).com(EstadoConexao.CONECTADO, d);
        return t == null ? c : c.comConsumo(t);
    }

    private static Cena padrao() {
        return cena(true, FixturesDeApi.detalheDoProjetoSintetico(), FixturesDeApi.consumoSintetico());
    }

    // ---------------------------------------------------------------------------------------------
    // Totais e gráfico
    // ---------------------------------------------------------------------------------------------

    @Test
    void osQuatroTotaisEmDolarEquivalenteDeApiComTokensERespostas() {
        PaginaConsumo p = padrao().tela.consumo();
        var cartoes = Visual.naEdt(() -> p.totais().cartoes());
        assertEquals(List.of("US$ 0,00", "US$ 4,10", "US$ 68,20", "US$ 422,84"), cartoes.stream().map(k -> Visual.naEdt(k::textoDoValor)).toList());
        assertEquals(List.of("HOJE", "7 DIAS", "30 DIAS", "TOTAL"), cartoes.stream().map(k -> Visual.naEdt(() -> k.kpi().rotulo())).toList());
        assertTrue(Visual.naEdt(cartoes.get(1)::textoDaLegenda).contains("40 respostas"));
        assertTrue(Visual.naEdt(() -> cartoes.get(1).kpi().dica()).contains("equivalente de API"));
    }

    @Test
    void ateOConsumoChegarOsTotaisMostramTracoEODiaCalculando() {
        PaginaConsumo p = cena(true, FixturesDeApi.detalheDoProjetoSintetico(), null).tela.consumo();
        assertEquals("—", Visual.naEdt(() -> p.totais().cartoes().get(0).textoDoValor()));
        assertEquals("Calculando o consumo…", Visual.naEdt(p::textoDoAvisoDoGrafico));
        assertEquals(0, Visual.naEdt(() -> p.grafico().barras().size()));
        assertEquals(0, Visual.naEdt(() -> p.tabelaDeModelos().total()));
    }

    @Test
    void ograficoTem30BarrasEOsDiasSemUsoNaoSomem() {
        Cena c = padrao();
        Visual.renderizar(c.tela, 1440 - BarraServidores.LARGURA, 832);
        var g = Visual.naEdt(() -> c.tela.consumo().grafico());
        assertEquals(30, Visual.naEdt(() -> g.barras().size()));
        assertEquals(0, Visual.naEdt(() -> g.alturaDaBarra(0)), "dia sem uso: só o traço no chão");
        assertEquals("25 de set: sem consumo", Visual.naEdt(() -> g.dicaDa(0)));
        assertTrue(Visual.naEdt(() -> g.alturaDaBarra(21)) > Visual.naEdt(() -> g.alturaDaBarra(2)), "16 de out (US$ 8,40) é mais alto que 27 de set (US$ 3,20)");
        assertEquals(Visual.naEdt(() -> g.alturaDaBarra(21)), Visual.naEdt(() -> g.getHeight()) - 18 - 14, "o maior dia usa a altura toda");
        assertTrue(Visual.naEdt(() -> g.dicaDa(21)).startsWith("16 de out: US$ 8,40"));
    }

    @Test
    void passarOMousePorCimaDaBarraExplicaODia() {
        Cena c = padrao();
        Visual.renderizar(c.tela, 1440 - BarraServidores.LARGURA, 832);
        var g = Visual.naEdt(() -> c.tela.consumo().grafico());
        int largura = Visual.naEdt(g::getWidth);
        int x = (int) (largura * (21 + 0.5) / 30);
        assertEquals(21, Visual.naEdt(() -> g.indiceNoPonto(x)));
        String dica = Visual.naEdt(() -> g.getToolTipText(new java.awt.event.MouseEvent(g, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, x, 20, 0, false)));
        assertTrue(dica.startsWith("16 de out: US$ 8,40"), dica);
        assertEquals(-1, Visual.naEdt(() -> g.indiceNoPonto(-5)));
        assertEquals(-1, Visual.naEdt(() -> g.indiceNoPonto(largura + 5)));
    }

    @Test
    void oGraficoPintaNaCorDeAcentoDoTemaNosDoisTemas() {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            Cena c = new Cena(true, Aba.CONSUMO).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico())
                .comConsumo(FixturesDeApi.consumoSintetico());
            var g = Visual.naEdt(() -> c.tela.consumo().grafico());
            java.awt.image.BufferedImage img = Visual.renderizar(g, 600, 124);
            int acento = Visual.contarPixels(img, br.com.labdesktop.ui.tema.Tokens.cor(br.com.labdesktop.ui.tema.Tokens.ACENTO), 4);
            assertTrue(acento > 600, tema + ": as barras usam o acento do tema (" + acento + " pixels)");
        }
    }

    @Test
    void oGraficoTemTextoParaOLeitorDeTela() {
        PaginaConsumo p = padrao().tela.consumo();
        String nome = Visual.naEdt(() -> p.grafico().getAccessibleContext().getAccessibleName());
        assertTrue(nome.startsWith("Consumo dos últimos 30 dias: US$ 68,20 no total"), nome);
        assertEquals("US$ 68,20 em 30 dias · maior dia: 16 de out (US$ 8,40)", Visual.naEdt(p::textoDoAvisoDoGrafico));
    }

    @Test
    void semConsumoNenhumOGraficoDizIsso() {
        TokensDoProjeto zeros = FixturesDeApi.de("{\"nome\":\"orbita\",\"por_dia\":[{\"dia\":\"2026-10-23\",\"custo_usd\":0},{\"dia\":\"2026-10-24\",\"custo_usd\":0}]}",
            TokensDoProjeto.class);
        PaginaConsumo p = cena(true, FixturesDeApi.detalheDoProjetoSintetico(), zeros).tela.consumo();
        assertEquals("Nenhum consumo registrado nos últimos 30 dias.", Visual.naEdt(p::textoDoAvisoDoGrafico));
        assertEquals(2, Visual.naEdt(() -> p.grafico().barras().size()));
        assertTrue(Visual.naEdt(() -> p.grafico().barras().stream().allMatch(b -> b.valor() == 0)));
    }

    @Test
    void harnessEmUmaLinhaEOnlySeVier() {
        PaginaConsumo p = padrao().tela.consumo();
        assertEquals("Harness: 12 entregas · 14 iniciados · 143,2 mil tokens", Visual.naEdt(p::textoDoHarness));
        Cena sem = cena(true, FixturesDeApi.detalheDoProjetoSintetico(), FixturesDeApi.de("{\"nome\":\"orbita\"}", TokensDoProjeto.class));
        assertEquals(null, Visual.naEdt(sem.tela.consumo()::textoDoHarness));
    }

    // ---------------------------------------------------------------------------------------------
    // Limite da semana
    // ---------------------------------------------------------------------------------------------

    @Test
    void limiteDentroDoTetoMostraBarraSeloEBotoes() {
        PaginaConsumo p = padrao().tela.consumo();
        assertEquals("2,0% de 20%", Visual.naEdt(p::textoDoValorDoLimite));
        assertEquals("DENTRO DO LIMITE", Visual.naEdt(() -> p.seloDoLimite().texto()));
        assertTrue(Visual.naEdt(p.barraDoLimite()::isVisible));
        assertEquals(1.96 / 20, Visual.naEdt(() -> p.barraDoLimite().fracao()), 1e-9);
        assertEquals(Severidade.NORMAL, Visual.naEdt(() -> p.barraDoLimite().severidade()));
        assertTrue(Visual.naEdt(p::textoDaExplicacaoDoLimite).contains("Semana zera em 4 d 21 h"));
        assertTrue(Visual.naEdt(p.botaoDefinirLimite()::isVisible));
        assertFalse(Visual.naEdt(p.botaoRetomar()::isVisible), "não está pausado");
        assertTrue(Visual.naEdt(p.botaoLiberar()::isVisible), "tem teto e não está liberado");
    }

    @Test
    void limitePausadoMostraPausadoBarraCheiaERetomar() {
        PaginaConsumo p = cena(true, TelaProjetoTest.detalhePausado(), FixturesDeApi.consumoSintetico()).tela.consumo();
        assertEquals("22% de 20%", Visual.naEdt(p::textoDoValorDoLimite));
        assertEquals("PAUSADO", Visual.naEdt(() -> p.seloDoLimite().texto()));
        assertEquals(1.0, Visual.naEdt(() -> p.barraDoLimite().fracao()), "a barra nunca passa de cheia");
        assertEquals(Severidade.CRITICA, Visual.naEdt(() -> p.barraDoLimite().severidade()));
        assertTrue(Visual.naEdt(p.botaoRetomar()::isVisible));
        assertTrue(Visual.naEdt(p.botaoLiberar()::isVisible));
        assertTrue(Visual.naEdt(p.botaoDefinirLimite()::isVisible));
        assertTrue(Visual.naEdt(p::textoDaExplicacaoDoLimite).contains("chegou ao limite"));
    }

    @Test
    void limitePertoDoTetoEAtencaoEBarraAlta() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"usado_pp\": 1.96, \"estado\": \"ok\"", "\"usado_pp\": 16.5, \"estado\": \"atencao\"");
        PaginaConsumo p = cena(true, d, FixturesDeApi.consumoSintetico()).tela.consumo();
        assertEquals("ATENÇÃO", Visual.naEdt(() -> p.seloDoLimite().texto()));
        assertEquals(Severidade.ALTA, Visual.naEdt(() -> p.barraDoLimite().severidade()));
    }

    @Test
    void limiteLiberadoNaoOfereceLiberarDeNovo() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"liberado\": false", "\"liberado\": true");
        PaginaConsumo p = cena(true, d, FixturesDeApi.consumoSintetico()).tela.consumo();
        assertEquals("LIBERADO", Visual.naEdt(() -> p.seloDoLimite().texto()));
        assertFalse(Visual.naEdt(p.botaoLiberar()::isVisible));
    }

    @Test
    void semTetoNaoTemBarraNemLiberar() {
        PaginaConsumo p = cena(true, TelaProjetoTest.detalheSemTeto(), FixturesDeApi.consumoSintetico()).tela.consumo();
        assertEquals("SEM LIMITE", Visual.naEdt(() -> p.seloDoLimite().texto()));
        assertFalse(Visual.naEdt(p.barraDoLimite()::isVisible));
        assertFalse(Visual.naEdt(p.botaoLiberar()::isVisible));
        assertTrue(Visual.naEdt(p.botaoDefinirLimite()::isVisible), "mas dá para definir um");
    }

    @Test
    void semAcoesOLimiteSoInformaEDizPorQue() {
        PaginaConsumo p = cena(false, TelaProjetoTest.detalhePausado(), FixturesDeApi.consumoSintetico()).tela.consumo();
        assertFalse(Visual.naEdt(p.botaoDefinirLimite()::isVisible));
        assertFalse(Visual.naEdt(p.botaoRetomar()::isVisible));
        assertFalse(Visual.naEdt(p.botaoLiberar()::isVisible));
        assertTrue(Visual.naEdt(p::somenteLeituraVisivel));
        assertEquals("PAUSADO", Visual.naEdt(() -> p.seloDoLimite().texto()), "a informação continua");
    }

    @Test
    void definirLimiteAbreODialogoExistenteComOLimiteDeHoje() {
        Cena c = padrao();
        Visual.naEdt(() -> c.tela.consumo().botaoDefinirLimite().doClick());
        assertNotNull(c.dialogos.ultimoComo(DialogoLimite.class));
    }

    @Test
    void liberarPerguntaAntesEExplicaOQueAcontece() {
        Cena c = padrao();
        Visual.naEdt(() -> c.tela.consumo().botaoLiberar().doClick());
        DialogoConfirmar d = c.dialogos.ultimoComo(DialogoConfirmar.class);
        assertNotNull(d);
        assertEquals("Liberar", Visual.naEdt(() -> d.botaoConfirmar().getText()));
        assertTrue(Visual.naEdt(() -> br.com.labdesktop.ui.TextosDeTela.de(d.raiz())).contains("até a semana do plano virar"));
        assertTrue(c.avisos.todos.isEmpty(), "cancelar não faz nada");
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(c.avisos.todos.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Tabelas
    // ---------------------------------------------------------------------------------------------

    @Test
    void tabelaPorModeloEPorSessaoPaginada() {
        PaginaConsumo p = padrao().tela.consumo();
        var modelos = Visual.naEdt(p::tabelaDeModelos);
        assertEquals(4, Visual.naEdt(() -> modelos.linhasDaPagina().size()));
        assertEquals(List.of("claude-opus-4-8", "3.100", "1,12 mi", "US$ 171,25"), Visual.naEdt(() -> modelos.linhasDaPagina().get(0)));
        var sessoes = Visual.naEdt(p::tabelaDeSessoes);
        assertEquals(4, Visual.naEdt(() -> sessoes.linhasDaPagina().size()));
        assertEquals("mostrando 1-4 de 6", Visual.naEdt(sessoes::textoDoResumo));
        assertEquals(2, Visual.naEdt(sessoes::totalDePaginas));
        List<String> primeira = Visual.naEdt(() -> sessoes.linhasDaPagina().get(0));
        assertEquals("s0a1b2c0", primeira.get(0));
        assertEquals("24/10 10:00", primeira.get(1), "13:00 UTC no fuso de São Paulo");
        assertEquals("2 h 20 min", primeira.get(2));
        assertEquals("claude-opus-4-8, claude-sonnet-5-5", primeira.get(3));
        assertEquals("direta", primeira.get(7));
    }

    @Test
    void ordenarOsModelosPeloNome() {
        PaginaConsumo p = padrao().tela.consumo();
        var modelos = Visual.naEdt(p::tabelaDeModelos);
        Visual.naEdt(() -> modelos.botaoDoCabecalho(0).doClick());
        assertEquals("claude-fable-ex", Visual.naEdt(() -> modelos.linhasDaPagina().get(0).get(0)), "de A a Z");
        Visual.naEdt(() -> modelos.botaoDoCabecalho(3).doClick());
        assertEquals("claude-opus-4-8", Visual.naEdt(() -> modelos.linhasDaPagina().get(0).get(0)), "o custo, do maior para o menor");
    }

    @Test
    void subAbasTrocamEntreModeloESessao() {
        PaginaConsumo p = padrao().tela.consumo();
        var modelos = Visual.naEdt(p::tabelaDeModelos);
        var sessoes = Visual.naEdt(p::tabelaDeSessoes);
        assertTrue(Visual.naEdt(modelos::isShowing) || Visual.naEdt(modelos::isVisible));
        Visual.naEdt(() -> p.subAbas().botao(PaginaConsumo.SUB_SESSOES).doClick());
        assertTrue(Visual.naEdt(sessoes::isVisible));
        assertFalse(Visual.naEdt(modelos::isVisible));
        Visual.naEdt(() -> p.subAbas().botao(PaginaConsumo.SUB_MODELOS).doClick());
        assertTrue(Visual.naEdt(modelos::isVisible));
    }

    @Test
    void semModeloNemSessaoDizOQueFalta() {
        PaginaConsumo p = cena(true, FixturesDeApi.detalheDoProjetoSintetico(), FixturesDeApi.de("{\"nome\":\"orbita\"}", TokensDoProjeto.class)).tela.consumo();
        assertEquals(0, Visual.naEdt(() -> p.tabelaDeModelos().linhasDaPagina().size()));
        assertTrue(Visual.naEdt(() -> TelaProjetoTest.textos(p.tabelaDeModelos())).contains("Nenhum modelo usado ainda neste projeto."));
        assertTrue(Visual.naEdt(() -> TelaProjetoTest.textos(p.tabelaDeSessoes())).contains("Nenhuma sessão do Claude registrada neste projeto."));
    }

    @Test
    void topicosProprios() {
        Cena c = padrao();
        assertEquals(java.util.Set.of(), Visual.naEdt(c.tela.consumo()::topicosProprios), "sem a aba na tela, nada assinado");
        assertFalse(Visual.naEdt(c.tela.consumo()::consumoAssinado));
        assertEquals(java.util.Set.of("detalhe:orbita"), c.tela.topicos());
    }

    // ---------------------------------------------------------------------------------------------
    // Cabe
    // ---------------------------------------------------------------------------------------------

    @Test
    void cabeEm1100x700ComOLimitePausadoEEm1440x860() {
        for (int[] j : new int[][] {{1100, 700}, {1440, 860}}) {
            Cena c = cena(true, TelaProjetoTest.detalhePausado(), FixturesDeApi.consumoSintetico());
            int pedida = Visual.alturaPreferida(c.tela, j[0] - BarraServidores.LARGURA);
            assertTrue(pedida <= j[1] - 28, "o Consumo pede " + pedida + " px, a janela " + j[0] + "x" + j[1] + " dá " + (j[1] - 28));
            Visual.renderizar(c.tela, j[0] - BarraServidores.LARGURA, j[1] - 28);
            assertFalse(Visual.naEdt(() -> c.tela.consumo().rolagem().getVerticalScrollBar().isVisible()), "sem rolagem em " + j[0]);
        }
    }

    @Test
    void asColunasDeLimiteEGraficoTemAProporcaoFixaMesmoComTextoLongo() {
        Cena c = cena(true, TelaProjetoTest.detalhePausado(), FixturesDeApi.consumoSintetico());
        Visual.renderizar(c.tela, 1100 - BarraServidores.LARGURA, 672);
        int limite = Visual.naEdt(() -> {
            var r = c.tela.consumo().componentes().stream().filter(x -> "bloco-Limite da semana".equals(x.getName())).findFirst().orElseThrow();
            return r.getWidth();
        });
        int grafico = Visual.naEdt(() -> {
            var r = c.tela.consumo().componentes().stream().filter(x -> "bloco-Últimos 30 dias".equals(x.getName())).findFirst().orElseThrow();
            return r.getWidth();
        });
        double proporcao = (double) limite / (limite + grafico);
        assertEquals(0.44, proporcao, 0.02, "limite " + limite + ", gráfico " + grafico);
    }
}
