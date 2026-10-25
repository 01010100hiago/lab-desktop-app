package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.ui.telas.ModeloDeLogs.Filtrado;
import br.com.labdesktop.ui.telas.ModeloDeLogs.Preparado;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O texto dos logs preparado para a tela: limpo, só as últimas N linhas, nunca HTML, com filtro e contagem. */
class ModeloDeLogsTest {

    private static String linhas(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("linha ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void ficaComAsUltimasNLinhasEDiz() {
        Preparado p = ModeloDeLogs.preparar(linhas(500), 100);
        assertEquals(100, p.linhas().size());
        assertEquals("linha 401", p.linhas().get(0));
        assertEquals("linha 500", p.linhas().get(99));
        assertTrue(p.cortado());
        assertEquals(500, p.linhasDoOriginal());
    }

    @Test
    void menosLinhasQueOLimiteNaoCorta() {
        Preparado p = ModeloDeLogs.preparar(linhas(30), 100);
        assertEquals(30, p.linhas().size());
        assertFalse(p.cortado());
    }

    @Test
    void oTetoDeLinhasValeMesmoQueOLimiteSejaMaior() {
        Preparado p = ModeloDeLogs.preparar(linhas(5000), 100_000);
        assertEquals(ModeloDeLogs.TETO_DE_LINHAS, p.linhas().size());
        assertTrue(p.cortado());
    }

    @Test
    void trezentosKilobytesNaoViramTrezentosKilobytesNaTela() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            sb.append("2026-10-24T17:00:00.000000000Z mensagem de exemplo com um pouco de texto para encher a linha ").append(i).append('\n');
        }
        assertTrue(sb.length() > 300_000);
        Preparado p = ModeloDeLogs.preparar(sb.toString(), 300);
        assertEquals(300, p.linhas().size());
        int total = p.linhas().stream().mapToInt(String::length).sum();
        assertTrue(total < 60_000, "a tela renderiza " + total + " caracteres, não os 300 mil");
    }

    @Test
    void umaLinhaGiganteEhCortada() {
        String gigante = "x".repeat(300_000);
        Preparado p = ModeloDeLogs.preparar("antes\n" + gigante + "\ndepois\n", 100);
        assertEquals(3, p.linhas().size());
        assertEquals(ModeloDeLogs.TETO_DE_CARACTERES_POR_LINHA, p.linhas().get(1).length());
        assertTrue(p.linhas().get(1).endsWith("…"));
    }

    @Test
    void tiraAsCoresDoTerminalSemDeixarLixo() {
        Preparado p = ModeloDeLogs.preparar("\u001B[31mERROR\u001B[0m falhou \u001B[1;33mwarn\u001B[0m\n", 100);
        assertEquals("ERROR falhou warn", p.linhas().get(0));
    }

    @Test
    void controlesEHtmlNuncaChegamComoSao() {
        Preparado p = ModeloDeLogs.preparar("<html><b>oi</b></html>\nteste\u0007bell‮bidi\n", 100);
        String primeira = p.linhas().get(0);
        assertFalse(primeira.toLowerCase().startsWith("<html"), "o Swing renderizaria HTML: " + primeira);
        assertTrue(primeira.contains("<b>oi</b>"), "o texto continua lá, só não vira HTML");
        assertEquals("testebellbidi", p.linhas().get(1));
    }

    @Test
    void quebrasDeLinhaDeTodosOsSistemas() {
        Preparado p = ModeloDeLogs.preparar("a\r\nb\rc\nd", 100);
        assertEquals(List.of("a", "b", "c", "d"), p.linhas());
    }

    @Test
    void vazioOuSoEspacoNaoTemLinhas() {
        assertTrue(ModeloDeLogs.preparar("", 100).vazio());
        assertTrue(ModeloDeLogs.preparar("  \n \n", 100).vazio());
        assertTrue(ModeloDeLogs.preparar(null, 100).vazio());
    }

    @Test
    void filtroMostraSoAsLinhasQueTemOTexto() {
        Filtrado f = ModeloDeLogs.filtrar(List.of("tudo bem", "ERRO grave", "ok de novo", "outro Erro"), "erro");
        assertEquals("ERRO grave\noutro Erro", f.texto());
        assertEquals(2, f.ocorrencias());
        assertEquals(2, f.linhasQueCasam());
    }

    @Test
    void contaOcorrenciasNaoLinhas() {
        Filtrado f = ModeloDeLogs.filtrar(List.of("a b a b a", "xyz", "a"), "a");
        assertEquals(4, f.ocorrencias(), "3 + 1");
        assertEquals(2, f.linhasQueCasam());
    }

    @Test
    void filtroVazioMostraTudoESemContagem() {
        Filtrado f = ModeloDeLogs.filtrar(List.of("a", "b"), "  ");
        assertEquals("a\nb", f.texto());
        assertEquals(0, f.ocorrencias());
        assertEquals(2, f.linhasQueCasam());
    }

    @Test
    void filtroSemResultado() {
        Filtrado f = ModeloDeLogs.filtrar(List.of("a", "b"), "zzz");
        assertEquals("", f.texto());
        assertEquals(0, f.ocorrencias());
        assertEquals(0, f.linhasQueCasam());
    }

    @Test
    void acentosNaBusca() {
        Filtrado f = ModeloDeLogs.filtrar(List.of("relatório pronto", "RELATÓRIO falhou"), "relatório");
        assertEquals(2, f.linhasQueCasam(), "maiúscula e minúscula juntas, com acento");
    }

    @Test
    void aFixtureSinteticaTemErrosEAvisosParaOFiltroAchar() {
        Preparado p = ModeloDeLogs.preparar(FixturesDeApi.lerRecursoDoProjeto("logs-sintetico.txt"), 1000);
        assertEquals(420, p.linhas().size());
        Filtrado erros = ModeloDeLogs.filtrar(p.linhas(), "error");
        assertTrue(erros.linhasQueCasam() >= 7, "cada 53ª linha é um erro: " + erros.linhasQueCasam());
        assertFalse(erros.texto().contains("\u001B"));
    }
}
