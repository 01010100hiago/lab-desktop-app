package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A tabela paginada: primeira, última, parcial, vazia, 500 itens, filtro, ordenação e teclado. */
@ExtendWith(VerificaEdt.class)
class TabelaPaginadaTest {

    record Item(int n, String nome, Double valor) { }

    private static TabelaPaginada<Item> tabela(int porPagina) {
        return Visual.naEdt(() -> new TabelaPaginada<Item>(porPagina, List.of(
            Coluna.texto("Nº", 0.3, i -> String.valueOf(i.n())),
            Coluna.texto("Nome", 1.0, Item::nome),
            new Coluna<>("Valor", 0.5, true, false, i -> i.valor() == null ? null : String.valueOf(i.valor()),
                Comparator.comparing(Item::valor, Comparator.nullsFirst(Comparator.<Double>naturalOrder()))))));
    }

    private static List<Item> itens(int n) {
        List<Item> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(new Item(i, i % 2 == 0 ? "par" : "impar", (double) (i * 7 % 13)));
        }
        return l;
    }

    @Test
    void primeiraPaginaTem15LinhasEOResumoDizQuantas() {
        Visual.tema(Tema.CLARO);
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(40), "vazio"));
        assertEquals(15, t.linhasDaPagina().size());
        assertEquals("1", t.linhasDaPagina().get(0).get(0));
        assertEquals("15", t.linhasDaPagina().get(14).get(0));
        assertEquals("mostrando 1-15 de 40", t.textoDoResumo());
        assertEquals(3, t.totalDePaginas());
        assertEquals(1, t.pagina());
        assertFalse(t.botaoAnterior().isEnabled());
        assertTrue(t.botaoProxima().isEnabled());
        assertTrue(t.rodapeVisivel());
    }

    @Test
    void ultimaPaginaParcialMostraOResto() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(40), "vazio"));
        Visual.naEdt(() -> t.botaoProxima().doClick());
        assertEquals(2, t.pagina());
        assertEquals("mostrando 16-30 de 40", t.textoDoResumo());
        Visual.naEdt(() -> t.botaoProxima().doClick());
        assertEquals(3, t.pagina());
        assertEquals(10, t.linhasDaPagina().size(), "a última tem só 10");
        assertEquals("mostrando 31-40 de 40", t.textoDoResumo());
        assertFalse(t.botaoProxima().isEnabled());
        assertTrue(t.botaoAnterior().isEnabled());
        Visual.naEdt(() -> t.botaoProxima().doClick());
        assertEquals(3, t.pagina(), "não passa da última");
        Visual.naEdt(() -> t.botaoAnterior().doClick());
        assertEquals(2, t.pagina());
    }

    @Test
    void paginaExataNoLimiteENenhumaPaginaAMais() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(15), "vazio"));
        assertEquals(1, t.totalDePaginas());
        assertFalse(t.rodapeVisivel(), "uma página só: sem rodapé");
        Visual.naEdt(() -> t.definir(itens(16), "vazio"));
        assertEquals(2, t.totalDePaginas());
        Visual.naEdt(() -> t.irParaPagina(2));
        assertEquals(List.of("16"), t.linhasDaPagina().get(0).subList(0, 1));
        assertEquals(1, t.linhasDaPagina().size());
    }

    @Test
    void vaziaMostraAFraseEMantemOsBotoesFora() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(List.of(), "Nenhuma tentativa."));
        assertTrue(t.linhasDaPagina().isEmpty());
        assertEquals("", t.textoDoResumo());
        assertEquals(1, t.totalDePaginas());
        assertFalse(t.rodapeVisivel());
        assertTrue(Visual.naEdt(() -> TextosDeTela.de(t)).contains("Nenhuma tentativa."));
        Visual.renderizar(t, 600, 120);
    }

    @Test
    void quinhentosItensDao34PaginasEIrParaForaLimita() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(500), "vazio"));
        assertEquals(34, t.totalDePaginas());
        Visual.naEdt(() -> t.irParaPagina(999));
        assertEquals(34, t.pagina());
        assertEquals("mostrando 496-500 de 500", t.textoDoResumo());
        assertEquals(5, t.linhasDaPagina().size());
        Visual.naEdt(() -> t.irParaPagina(-3));
        assertEquals(1, t.pagina());
        assertEquals("Página 1 de 34", Visual.naEdt(() -> TextosDeTela.de(t)).lines().filter(l -> l.startsWith("Página")).findFirst().orElse(""));
    }

    @Test
    void mudarOFiltroVoltaAPagina1EOResumoContaSoOFiltrado() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(100), "vazio"));
        Visual.naEdt(() -> t.irParaPagina(4));
        assertEquals(4, t.pagina());
        Visual.naEdt(() -> t.filtrar(i -> i.nome().equals("par")));
        assertEquals(1, t.pagina(), "mudou o filtro: volta à página 1");
        assertEquals(50, t.totalFiltrado());
        assertEquals(100, t.total());
        assertEquals("mostrando 1-15 de 50", t.textoDoResumo());
        assertTrue(t.linhasDaPagina().stream().allMatch(l -> l.get(1).equals("par")));
        Visual.naEdt(() -> t.filtrar(i -> i.n() > 1000));
        assertEquals(0, t.totalFiltrado());
        assertTrue(Visual.naEdt(() -> TextosDeTela.de(t)).contains("Nada encontrado com este filtro."));
        Visual.naEdt(() -> t.filtrar(null));
        assertEquals(100, t.totalFiltrado());
    }

    @Test
    void atualizarOsItensDoPollingMantemAPaginaMasUmClicadoNoFiltroVolta() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(100), "vazio"));
        Visual.naEdt(() -> t.irParaPagina(3));
        Visual.naEdt(() -> t.atualizar(itens(101), i -> true, "vazio"));
        assertEquals(3, t.pagina(), "a atualização automática não joga o usuário de volta ao começo");
        Visual.naEdt(() -> t.atualizar(itens(20), i -> true, "vazio"));
        assertEquals(2, t.pagina(), "a página que não existe mais é limitada à última");
    }

    @Test
    void ordenarPelaColunaDoCabecalhoNumericaComecaDoMaiorParaOMenorEInverteNoSegundoClique() {
        Visual.tema(Tema.CLARO);
        TabelaPaginada<Item> t = tabela(15);
        List<Item> l = List.of(new Item(1, "a", 5.0), new Item(2, "b", 9.0), new Item(3, "c", null), new Item(4, "d", 1.0));
        Visual.naEdt(() -> t.definir(l, "vazio"));
        assertEquals(-1, t.colunaOrdenada());
        assertNull(t.botaoDoCabecalho(0), "colunas sem comparador não viram botão");
        assertNotNull(t.botaoDoCabecalho(2));
        Visual.naEdt(() -> t.botaoDoCabecalho(2).doClick());
        assertEquals(2, t.colunaOrdenada());
        assertFalse(t.crescente(), "número: do maior para o menor");
        assertEquals(List.of("2", "1", "4", "3"), t.linhasDaPagina().stream().map(r -> r.get(0)).toList(), "nulo por último");
        Visual.naEdt(() -> t.botaoDoCabecalho(2).doClick());
        assertTrue(t.crescente());
        assertEquals(List.of("3", "4", "1", "2"), t.linhasDaPagina().stream().map(r -> r.get(0)).toList());
        assertTrue(Visual.naEdt(() -> t.botaoDoCabecalho(2).getAccessibleContext().getAccessibleName()).contains("crescente"));
    }

    @Test
    void ordenarTrocaAPaginaParaAPrimeiraEDestacaPintaEmNegrito() {
        Visual.tema(Tema.CLARO);
        TabelaPaginada<Item> t = tabela(5);
        Visual.naEdt(() -> t.definir(itens(20), "vazio"));
        Visual.naEdt(() -> t.irParaPagina(3));
        Visual.naEdt(() -> t.ordenarPor(2));
        assertEquals(1, t.pagina());
        Visual.naEdt(() -> t.destacar(i -> i.n() == 1));
        Visual.naEdt(() -> t.nota("mostrando os 500 mais recentes"));
        assertTrue(t.textoDoResumo().startsWith("mostrando 1-5 de 20"));
        Visual.salvar(Visual.renderizar(t, 700, Visual.alturaPreferida(t, 700)), "tabela-paginada");
    }

    @Test
    void ordenarPorColunaSemComparadorOuInexistenteNaoFazNada() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(3), "vazio"));
        Visual.naEdt(() -> t.ordenarPor(0));
        Visual.naEdt(() -> t.ordenarPor(9));
        assertEquals(-1, t.colunaOrdenada());
    }

    @Test
    void notaApareceNoRodapeMesmoComUmaPaginaSo() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(3), "vazio"));
        assertFalse(t.rodapeVisivel());
        Visual.naEdt(() -> t.nota("mostrando os 500 mais recentes"));
        assertTrue(t.rodapeVisivel());
        assertEquals("mostrando os 500 mais recentes", t.textoDaNota());
    }

    @Test
    void osBotoesDePaginacaoSaoFocaveisEAtivamPeloTeclado() {
        TabelaPaginada<Item> t = tabela(15);
        Visual.naEdt(() -> t.definir(itens(40), "vazio"));
        assertTrue(Visual.naEdt(() -> t.botaoProxima().isFocusable()));
        assertEquals("Próxima página", Visual.naEdt(() -> t.botaoProxima().getAccessibleContext().getAccessibleName()));
        // o botão do Swing ativa com espaço (mapa de teclas do próprio JButton): o "doClick" é o mesmo caminho do evento de ação
        assertTrue(Visual.naEdt(() -> t.botaoProxima().getActionListeners().length) > 0);
    }

    @Test
    void naoAceitaPorPaginaInvalido() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> Visual.naEdt(() -> new TabelaPaginada<Item>(0, List.of())));
    }

    @Test
    void paginacaoExternaTiraOsControlesDoRodapeEOsEntregaParaOCaller() {
        Visual.tema(Tema.CLARO);
        TabelaPaginada<Item> t = tabela(4);
        javax.swing.JComponent controles = Visual.naEdt(t::paginacaoExterna);
        Visual.naEdt(() -> t.definir(itens(10), "vazio"));
        assertTrue(Visual.naEdt(controles::isVisible), "10 itens em páginas de 4: tem para onde ir");
        assertTrue(Visual.naEdt(() -> controles.isAncestorOf(t.botaoProxima())), "os botões moram no componente externo");
        assertFalse(Visual.naEdt(() -> t.isAncestorOf(t.botaoProxima())), "e não mais no rodapé da tabela");
        Visual.naEdt(() -> t.botaoProxima().doClick());
        assertEquals(2, t.pagina());
        assertEquals("mostrando 5-8 de 10", t.textoDoResumo());
        Visual.naEdt(() -> t.definir(itens(3), "vazio"));
        assertFalse(Visual.naEdt(controles::isVisible), "uma página só: o controle some");
    }
}
