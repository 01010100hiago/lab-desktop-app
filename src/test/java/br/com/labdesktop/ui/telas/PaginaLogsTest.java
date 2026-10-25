package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ErroApi.ServidorErro;
import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.TelaProjetoTest.Cena;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.DataFlavor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A aba Logs: filtro, contagem, limite de linhas, container sem logs, erro do agente, rolagem interna e "seguir o fim". O que
 * vem do agente é entregue por {@code receber}, como o tópico e a leitura avulsa fazem; a carga e a assinatura estão em
 * {@code NavegacaoDoProjetoTest}.
 */
@ExtendWith(VerificaEdt.class)
class PaginaLogsTest {

    private static final String LOGS = FixturesDeApi.lerRecursoDoProjeto("logs-sintetico.txt");

    private static Cena cena() {
        Visual.tema(Tema.CLARO);
        return new Cena(true, Aba.LOGS).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
    }

    private static int linhasDaArea(PaginaLogs p) {
        String t = Visual.naEdt(p::textoDaArea);
        return t.isEmpty() ? 0 : t.split("\n", -1).length;
    }

    @Test
    void oComboTemOsContainersEPreferePrimeiroQueRoda() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        var combo = Visual.naEdt(p::comboDeContainers);
        assertEquals(List.of("orbita-web-1", "orbita-fila-1", "orbita-banco-1", "orbita-cache-1"),
            Visual.naEdt(() -> java.util.stream.IntStream.range(0, combo.getItemCount()).mapToObj(combo::getItemAt).toList()));
        assertEquals("orbita-web-1", Visual.naEdt(p::containerEscolhido));
        assertEquals("orbita-web-1", Visual.naEdt(() -> (String) combo.getSelectedItem()));
    }

    @Test
    void ePrimeiroContainerParadoSoSeNenhumRoda() {
        DetalheProjeto parado = TelaProjetoTest.detalheCom("\"estado\": \"running\"", "\"estado\": \"exited\"");
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.LOGS).com(EstadoConexao.CONECTADO, parado);
        assertEquals("orbita-web-1", Visual.naEdt(c.tela.logs()::containerEscolhido), "nenhum roda: o primeiro");
        DetalheProjeto soUmRoda = TelaProjetoTest.detalheCom("\"nome\": \"orbita-web-1\", \"estado\": \"running\"", "\"nome\": \"orbita-web-1\", \"estado\": \"exited\"");
        Cena c2 = new Cena(true, Aba.LOGS).com(EstadoConexao.CONECTADO, soUmRoda);
        assertEquals("orbita-fila-1", Visual.naEdt(c2.tela.logs()::containerEscolhido), "o primeiro que roda");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void asOpcoesDeLinhasSao100300E1000ComPadraoDe300() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        var combo = Visual.naEdt(p::comboDeLinhas);
        assertEquals(List.of(100, 300, 1000), Visual.naEdt(() -> java.util.stream.IntStream.range(0, combo.getItemCount()).mapToObj(combo::getItemAt).toList()));
        assertEquals(300, Visual.naEdt(p::linhasPedidas));
        assertEquals("300 linhas", Visual.naEdt(() -> {
            javax.swing.ListCellRenderer renderer = combo.getRenderer();
            var r = renderer.getListCellRendererComponent(new javax.swing.JList(), combo.getSelectedItem(), 1, false, false);
            return ((javax.swing.JLabel) r).getText();
        }));
    }

    @Test
    void mostraSoAsUltimasNLinhasEDizOTotal() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber(LOGS, 300));
        assertEquals(300, linhasDaArea(p));
        assertTrue(Visual.naEdt(p::textoDaArea).endsWith(LOGS.strip().substring(LOGS.strip().lastIndexOf('\n') + 1)), "a última linha é a mais nova");
        assertEquals("300 linhas (as últimas 300 de 420)", Visual.naEdt(p::textoDaContagem));
        Visual.naEdt(() -> p.receber(LOGS, 100));
        assertEquals(100, linhasDaArea(p));
        Visual.naEdt(() -> p.receber(LOGS, 1000));
        assertEquals(420, linhasDaArea(p));
        assertEquals("420 linhas", Visual.naEdt(p::textoDaContagem), "menos linhas que o pedido: sem o aviso de corte");
    }

    @Test
    void logsDeTrezentosKilobytesNaoViramTrezentosKilobytesNaTela() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            sb.append("2026-10-24T17:00:00.000000000Z mensagem de exemplo com bastante texto para encher a linha número ").append(i).append('\n');
        }
        assertTrue(sb.length() > 300_000);
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber(sb.toString(), 1000));
        assertEquals(1000, linhasDaArea(p));
        assertTrue(Visual.naEdt(p::textoDaArea).length() < 150_000, "só as últimas 1000 linhas chegam ao componente de texto");
    }

    @Test
    void filtroMostraSoAsLinhasQueCasamEContaAsOcorrencias() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber(LOGS, 1000));
        Visual.naEdt(() -> p.campoDoFiltro().setText("error"));
        String area = Visual.naEdt(p::textoDaArea);
        assertTrue(area.lines().allMatch(l -> l.toLowerCase().contains("error")), area);
        long linhas = area.lines().count();
        assertTrue(linhas >= 7, "cada 53ª linha é um erro: " + linhas);
        assertEquals(linhas + " ocorrências em " + linhas + " linhas", Visual.naEdt(p::textoDaContagem));
        assertFalse(Visual.naEdt(p::mostraMensagem));
    }

    @Test
    void filtroEIncrementalACadaLetra() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber("alfa\nalto\nbravo\n", 1000));
        Visual.naEdt(() -> p.campoDoFiltro().setText("a"));
        assertEquals(3, linhasDaArea(p), "alfa, alto e bravo têm 'a'");
        Visual.naEdt(() -> p.campoDoFiltro().setText("al"));
        assertEquals(2, linhasDaArea(p));
        Visual.naEdt(() -> p.campoDoFiltro().setText("alf"));
        assertEquals(1, linhasDaArea(p));
        assertEquals("1 ocorrência em 1 linha", Visual.naEdt(p::textoDaContagem), "singular quando é um");
        Visual.naEdt(() -> p.campoDoFiltro().setText(""));
        assertEquals(3, linhasDaArea(p), "limpar o filtro devolve tudo");
        assertEquals("3 linhas", Visual.naEdt(p::textoDaContagem));
    }

    @Test
    void filtroSemResultadoDizQueNenhumaLinhaTemOTexto() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber(LOGS, 1000));
        Visual.naEdt(() -> p.campoDoFiltro().setText("zzzzzz"));
        assertTrue(Visual.naEdt(p::mostraMensagem));
        assertEquals("Nenhuma linha tem “zzzzzz”.", Visual.naEdt(p::textoDaMensagem));
        assertEquals("0 ocorrências", Visual.naEdt(p::textoDaContagem));
        assertFalse(Visual.naEdt(p.botaoCopiar()::isEnabled), "nada para copiar");
    }

    @Test
    void containerQueNuncaEscreveuNadaDizIsso() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber("", 300));
        assertTrue(Visual.naEdt(p::mostraMensagem));
        assertEquals("Este container ainda não escreveu nada nos logs.", Visual.naEdt(p::textoDaMensagem));
        assertFalse(Visual.naEdt(p.botaoCopiar()::isEnabled));
    }

    @Test
    void erroDoAgenteViraFraseNaAreaENaoUmaTelaVazia() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.falhou(new ServidorErro(400, null, "Container não pertence a este projeto.")));
        assertTrue(Visual.naEdt(p::mostraMensagem));
        assertEquals("Não consegui ler os logs: Container não pertence a este projeto.", Visual.naEdt(p::textoDaMensagem));
        assertEquals("", Visual.naEdt(p::textoDaContagem));
        Visual.naEdt(() -> p.receber(LOGS, 100));
        assertFalse(Visual.naEdt(p::mostraMensagem), "o Atualizar seguinte que der certo limpa o erro");
    }

    @Test
    void textoDoDockerComErroDentroDoCorpoApareceComoVeio() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber("Error response from daemon: No such container: orbita-web-1\n", 300));
        assertEquals("Error response from daemon: No such container: orbita-web-1", Visual.naEdt(p::textoDaArea));
    }

    @Test
    void htmlNosLogsApareceComoTexto() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber("<html><body><h1>oi</h1></body></html>\n", 300));
        String t = Visual.naEdt(p::textoDaArea);
        assertFalse(t.toLowerCase().startsWith("<html"));
        assertTrue(t.contains("<h1>oi</h1>"));
        assertEquals(Boolean.TRUE, Visual.naEdt(() -> c.tela.logs().rolagemDosLogs().getViewport().getView() instanceof javax.swing.JComponent j
            ? j.getClientProperty("html.disable") : null));
    }

    @Test
    void semContainersADiferencaEntreSemComposeEAindaNaoIniciado() {
        String json = TelaProjetoTest.detalheEmUmaLinha().replaceFirst("\"containers\": \\[.*?\\], \"urls\"", "\"containers\": [], \"urls\"");
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.LOGS).com(EstadoConexao.CONECTADO, FixturesDeApi.de(json, DetalheProjeto.class));
        PaginaLogs p = c.tela.logs();
        assertEquals("Este projeto ainda não tem containers. Os logs aparecem quando ele for iniciado.", Visual.naEdt(p::textoDaMensagem));
        assertFalse(Visual.naEdt(p.comboDeContainers()::isEnabled));
        assertFalse(Visual.naEdt(p.botaoAtualizar()::isEnabled));
        String semCompose = json.replaceFirst("\"composes\": \\[.*?\\], \"containers\"", "\"composes\": [], \"containers\"");
        Cena c2 = new Cena(true, Aba.LOGS).com(EstadoConexao.CONECTADO, FixturesDeApi.de(semCompose, DetalheProjeto.class));
        assertEquals("Este projeto não tem containers: não há logs para ver.", Visual.naEdt(c2.tela.logs()::textoDaMensagem));
    }

    @Test
    void copiarLevaOQueEstaNaAreaParaAAreaDeTransferencia() throws Exception {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber("primeira\nsegunda\n", 300));
        assertTrue(Visual.naEdt(p.botaoCopiar()::isEnabled));
        Visual.naEdt(() -> p.botaoCopiar().doClick());
        assertEquals("primeira\nsegunda", c.area.getData(DataFlavor.stringFlavor));
        assertEquals("Copiado", Visual.naEdt(() -> p.botaoCopiar().getText()));
        // com filtro, copia o que o usuário está vendo
        Visual.naEdt(() -> p.campoDoFiltro().setText("seg"));
        Visual.naEdt(() -> p.botaoCopiar().doClick());
        assertEquals("segunda", c.area.getData(DataFlavor.stringFlavor));
    }

    @Test
    void aAtualizacaoAutomaticaSoEhUmTopicoQuandoMarcada() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        assertEquals(java.util.Set.of(), Visual.naEdt(p::topicosProprios), "desmarcada: nenhum tópico");
        Visual.naEdt(() -> p.caixaAutomatica().setSelected(true));
        assertEquals(java.util.Set.of("logs:orbita:orbita-web-1:300"), Visual.naEdt(p::topicosProprios));
        Visual.naEdt(() -> p.comboDeLinhas().setSelectedItem(1000));
        assertEquals(java.util.Set.of("logs:orbita:orbita-web-1:1000"), Visual.naEdt(p::topicosProprios), "a chave leva as linhas");
        assertFalse(Visual.naEdt(p::automaticoAssinado), "sem servidor (teste) e sem tela visível, nada assina");
    }

    @Test
    void aAreaRolaPorDentroESeguirOFimLevaAoFimDosLogs() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.receber(LOGS, 1000));
        Visual.renderizar(c.tela, 1100 - BarraServidores.LARGURA, 672);
        var barra = Visual.naEdt(() -> p.rolagemDosLogs().getVerticalScrollBar());
        assertTrue(Visual.naEdt(barra::isVisible), "420 linhas não cabem: a rolagem é da área");
        assertFalse(Visual.naEdt(() -> p.rolagem().getVerticalScrollBar().isVisible()), "a página, não");
        Visual.naEdt(() -> p.caixaSeguir().doClick()); // desmarca
        Visual.naEdt(() -> p.caixaSeguir().doClick()); // marca de novo: vai ao fim
        Visual.naEdt(() -> { });
        assertTrue(Visual.naEdt(() -> barra.getValue() + barra.getVisibleAmount() >= barra.getMaximum() - 2),
            "valor " + Visual.naEdt(barra::getValue) + " de " + Visual.naEdt(barra::getMaximum));
    }

    @Test
    void aAreaNuncaMoveOTextoSozinha() {
        Cena c = cena();
        var area = Visual.naEdt(() -> (br.com.labdesktop.ui.componentes.AreaMonoespacada) c.tela.logs().rolagemDosLogs().getViewport().getView());
        assertEquals(javax.swing.text.DefaultCaret.NEVER_UPDATE, Visual.naEdt(() -> ((javax.swing.text.DefaultCaret) area.getCaret()).getUpdatePolicy()),
            "sem 'seguir o fim', a rolagem fica onde o usuário deixou");
        assertFalse(Visual.naEdt(area::isEditable));
    }

    @Test
    void acaoDeAtualizarETrocarDeContainerNaoQuebramSemServidor() {
        Cena c = cena();
        PaginaLogs p = c.tela.logs();
        Visual.naEdt(() -> p.botaoAtualizar().doClick());
        Visual.naEdt(() -> p.comboDeContainers().setSelectedItem("orbita-fila-1"));
        assertEquals("orbita-fila-1", Visual.naEdt(p::containerEscolhido));
        assertTrue(Visual.naEdt(() -> Arrays.asList(1, 2).size() == 2));
    }

    @Test
    void cabeSemRolagemDaPaginaEm1100x700EEm1440x860() {
        for (int[] j : new int[][] {{1100, 700}, {1440, 860}}) {
            Cena c = cena();
            Visual.naEdt(() -> c.tela.logs().receber(LOGS, 1000));
            Visual.renderizar(c.tela, j[0] - BarraServidores.LARGURA, j[1] - 28);
            assertFalse(Visual.naEdt(() -> c.tela.logs().rolagem().getVerticalScrollBar().isVisible()), "página sem rolagem em " + j[0]);
            assertFalse(Visual.naEdt(() -> c.tela.logs().rolagem().rola()));
            int alturaDaArea = Visual.naEdt(() -> c.tela.logs().rolagemDosLogs().getHeight());
            assertTrue(alturaDaArea >= 250, "a área de logs tem altura de verdade (" + alturaDaArea + ") em " + j[0] + "x" + j[1]);
        }
    }

    @Test
    void aAreaCresceComAJanela() {
        Cena pequena = cena();
        Visual.renderizar(pequena.tela, 1100 - BarraServidores.LARGURA, 700 - 28);
        Cena grande = cena();
        Visual.renderizar(grande.tela, 1440 - BarraServidores.LARGURA, 860 - 28);
        int a = Visual.naEdt(() -> pequena.tela.logs().rolagemDosLogs().getHeight());
        int b = Visual.naEdt(() -> grande.tela.logs().rolagemDosLogs().getHeight());
        assertTrue(b > a + 100, "a área de logs preenche a janela maior (" + a + " e " + b + ")");
    }
}
