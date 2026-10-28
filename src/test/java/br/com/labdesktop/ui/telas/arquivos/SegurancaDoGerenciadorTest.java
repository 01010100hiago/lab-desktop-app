package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Tudo o que vem do servidor é TEXTO: nome com HTML, com direção de texto trocada, com quebra de linha; conteúdo de arquivo; cores sem azul. */
@ExtendWith(VerificaEdt.class)
class SegurancaDoGerenciadorTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static CenaDeArquivos cena(Path tmp, CenaDeArquivos.Preparo preparo) throws Exception {
        Visual.tema(Tema.CLARO);
        CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", preparo, true);
        c.esperarNormal();
        return c;
    }

    @Test
    void nomeComHtmlOuDirecaoDeTextoTrocadaViraTextoNaTabelaENaDica(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, s -> {
            s.escrever(O + "/<html><h1>GRANDE</h1>.txt", "x");
            s.escrever(O + "/relatorio‮txt.exe", "x"); // U+202E esconde a extensão de verdade
        })) {
            assertTrue(c.nomes().stream().anyMatch(n -> n.startsWith("<html>")));
            TabelaDeEntradas t = Visual.naEdt(c.g::tabela);
            for (int i = 0; i < Visual.naEdt(() -> t.itens().size()); i++) {
                int linha = i;
                String exibido = Visual.naEdt(() -> t.textoDaCelula(t.itens().get(linha), 0));
                assertFalse(exibido.toLowerCase().startsWith("<html"), exibido);
                assertFalse(exibido.contains("‮"), "o caractere de direção foi retirado da exibição");
            }
            // o desenhista da célula desliga o HTML (um JLabel com texto "<html>" renderizaria a página)
            Visual.renderizar(c.g, 884, 634);
            var celula = Visual.naEdt(() -> (javax.swing.JComponent) t.prepareRenderer(t.getCellRenderer(0, 0), 0, 0));
            assertEquals(Boolean.TRUE, Visual.naEdt(() -> celula.getClientProperty("html.disable")));
            // a dica também é texto
            String dica = Visual.naEdt(() -> t.getToolTipText(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, 40,
                t.getCellRect(0, 0, true).y + 5, 0, false)));
            assertNotNull(dica);
            assertFalse(dica.toLowerCase().startsWith("<html"), dica);
        }
    }

    @Test
    void nomeComQuebraDeLinhaApareceMasNaoSePodeAbrirRenomearNemApagar(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, s -> s.escrever(O + "/a\nb.txt", "x"))) {
            assertTrue(c.nomes().stream().anyMatch(n -> n.contains("\n")), "aparece na lista (o usuário precisa saber que existe)");
            String exibido = Visual.naEdt(() -> c.g.tabela().textoDaCelula(c.g.tabela().itens().stream().filter(e -> e.nome().contains("\n")).findFirst().orElseThrow(), 0));
            assertFalse(exibido.contains("\n"), "mas a tabela mostra numa linha só");
            EntradaRemota estranho = Visual.naEdt(() -> c.g.tabela().itens().stream().filter(e -> e.nome().contains("\n")).findFirst().orElseThrow());
            Visual.naEdt(() -> c.g.abrirEntrada(estranho));
            Espera.ate(CenaDeArquivos.PRAZO, "recusa ao abrir", () -> c.avisos.disse("erro", "caracteres que não posso usar"));
            Visual.naEdt(() -> c.g.apagar(List.of(estranho)));
            Espera.ate(CenaDeArquivos.PRAZO, "recusa ao apagar", () -> c.avisos.todos.stream().filter(a -> a.texto().contains("não posso usar")).count() >= 2
                || c.avisos.disse("atencao", "caracteres que não posso usar"));
            assertTrue(Files.exists(c.sftp.orbita().resolve("a\nb.txt")), "nada foi apagado");
        }
    }

    @Test
    void oConteudoDoArquivoNuncaViraHtmlNemExecuta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, s -> s.escrever(O + "/<b>perigo.html",
            "<html><body><script>alert(1)</script><img src=\"http://exemplo.invalid/x.png\"></body></html>\n"))) {
            c.duploClique("<b>perigo.html");
            c.esperarQue("aberto", () -> !c.g.editoresAbertos().isEmpty());
            EditorDeTexto e = Visual.naEdt(() -> c.g.editoresAbertos().values().iterator().next());
            assertTrue(Visual.naEdt(() -> e.area().getText()).startsWith("<html><body><script>"), "o conteúdo é texto cru");
            assertEquals(Boolean.TRUE, Visual.naEdt(() -> e.area().getClientProperty("html.disable")));
            assertEquals("text/html", Visual.naEdt(() -> e.area().getSyntaxEditingStyle()), "realce de HTML, que só colore");
            assertFalse(Visual.naEdt(() -> e.area().getHyperlinksEnabled()), "links dentro do arquivo não são clicáveis");
            assertFalse(Visual.naEdt(() -> c.g.abas().botao("editor:1").getText().toLowerCase().startsWith("<html")));
            Visual.renderizar(c.g, 884, 634); // pintar não pode estourar nem buscar nada na rede
        }
    }

    @Test
    void asCoresDoEditorNaoTemAzulNemCianoNosDoisTemas(@TempDir Path tmp) throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            AreaDeEdicao area = Visual.naEdt(AreaDeEdicao::new);
            List<String> azuis = new ArrayList<>();
            Visual.naEdt(() -> {
                SyntaxScheme esquema = area.getSyntaxScheme();
                for (int i = 0; i < esquema.getStyleCount(); i++) {
                    var st = esquema.getStyle(i);
                    if (st != null && st.foreground != null && azul(st.foreground)) {
                        azuis.add("estilo " + i + " " + hex(st.foreground));
                    }
                }
                for (Color cor : new Color[] {area.getBackground(), area.getForeground(), area.getCaretColor(), area.getSelectionColor(),
                    area.getCurrentLineHighlightColor(), area.getMatchedBracketBGColor(), area.getMatchedBracketBorderColor(),
                    area.getHyperlinkForeground()}) {
                    if (cor != null && azul(cor)) {
                        azuis.add("cor " + hex(cor));
                    }
                }
                return null;
            });
            assertEquals(List.of(), azuis, "tema " + tema);
        }
        assertNotNull(Tokens.DESTAQUE);
    }

    private static boolean azul(Color c) {
        if (c.getAlpha() < 8) {
            return false;
        }
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        float matiz = hsb[0] * 360f;
        return matiz >= 165f && matiz <= 275f && hsb[1] >= 0.15f && hsb[2] >= 0.15f;
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    @Test
    void esqueletoEFiltroNaoExecutamNada(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, CenaDeArquivos::projetoDeExemplo)) {
            Visual.naEdt(() -> c.g.campoDeFiltro().setText("<html><script>"));
            assertEquals(List.of(), c.nomes());
            assertEquals("Nada encontrado com este filtro.", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            // Esc no filtro limpa
            var acao = Visual.naEdt(() -> c.g.campoDeFiltro().getActionMap().get(c.g.campoDeFiltro().getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                .get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))));
            Visual.naEdt(() -> acao.actionPerformed(null));
            assertEquals("", Visual.naEdt(() -> c.g.campoDeFiltro().getText()));
        }
    }
}
