package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo;
import br.com.labdesktop.arquivos.ResumoDeTamanho;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.dialogos.DialogoApagarArquivos;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeSalvar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeTransferencia;
import br.com.labdesktop.ui.dialogos.DialogoFecharEditor;
import br.com.labdesktop.ui.dialogos.DialogoNome;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Nada de texto miúdo" também no gerenciador de arquivos (o vigia do {@code TesteFontesMinimas}, estendido a esta tela): nenhum
 * rótulo, botão, campo, editor, tabela, árvore nem diálogo com letra menor que 12 px, em 100 % e em 130 %.
 */
@ExtendWith(VerificaEdt.class)
class FontesDoGerenciadorTest {

    private static final String O = CenarioDeSftp.ORBITA;

    @AfterEach
    void voltarA100() {
        Visual.naEdt(() -> Fontes.definirPercentual(100));
    }

    private static float minimoDe(int percentual) {
        return (float) Math.floor(Fontes.MINIMO * percentual / 100f * 2) / 2f;
    }

    private static void varrer(Component c, float minimo, String onde, List<String> ruins) {
        if (!c.isVisible()) {
            return;
        }
        String texto = null;
        Font fonte = c.getFont();
        if (c instanceof Selo s) {
            texto = s.texto();
            fonte = s.fonteDoTexto();
        } else if (c instanceof JLabel l) {
            texto = l.getText();
        } else if (c instanceof AbstractButton b) {
            texto = b.getText();
        } else if (c instanceof JTextComponent t) {
            texto = t.getText().isEmpty() ? "(campo)" : t.getText();
        } else if (c instanceof JComboBox<?>) {
            texto = "(lista)";
        } else if (c instanceof JTable) {
            texto = "(tabela)";
        } else if (c instanceof JTree) {
            texto = "(árvore)";
        }
        if (texto != null && !texto.isEmpty() && fonte != null && fonte.getSize2D() < minimo) {
            ruins.add(onde + ": '" + (texto.length() > 40 ? texto.substring(0, 40) + "…" : texto) + "' em " + c.getClass().getSimpleName()
                + " com " + fonte.getSize2D() + " px");
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                varrer(f, minimo, onde, ruins);
            }
        }
    }

    private static List<String> miudos(Component raiz, int percentual, String onde) {
        List<String> ruins = new ArrayList<>();
        Visual.naEdt(() -> varrer(raiz, minimoDe(percentual), percentual + "% " + onde, ruins));
        return ruins;
    }

    private void conferir(int percentual, Path tmp) throws Exception {
        Visual.naEdt(() -> Fontes.definirPercentual(percentual));
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.mostrarOcultos(true));
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md"));
            Visual.naEdt(() -> c.g.painelDeTransferencias().definir(List.of(
                new Retrato(1, Tipo.ENVIO, "orbita.zip para orbita", Estado.EM_ANDAMENTO, 30, 100, 1000, Duration.ofSeconds(30), 0, 1, 0, 0, "orbita.zip", ""),
                new Retrato(2, Tipo.DOWNLOAD, "docs para Downloads", Estado.CONCLUIDA, 10, 10, 0, null, 1, 1, 0, 0, "", "1 arquivo baixado"))));
            List<String> ruins = new ArrayList<>(miudos(c.g, percentual, "lista"));
            // fonte de verdade da tabela, da árvore e do cabeçalho (os desenhistas de célula não estão na árvore de componentes)
            float minimo = minimoDe(percentual);
            assertTrue(Visual.naEdt(() -> c.g.tabela().getFont().getSize2D()) >= minimo, "fonte da tabela");
            assertTrue(Visual.naEdt(() -> c.g.arvore().getFont().getSize2D()) >= minimo, "fonte da árvore");
            Visual.renderizar(c.g, 884, 634);
            for (int col = 0; col < 6; col++) {
                int coluna = col;
                var cab = Visual.naEdt(() -> c.g.tabela().getTableHeader().getDefaultRenderer().getTableCellRendererComponent(c.g.tabela(), "x", false, false, 0, coluna));
                assertTrue(Visual.naEdt(() -> cab.getFont().getSize2D()) >= minimo, "cabeçalho da coluna " + col);
            }
            // o editor
            c.duploClique("README.md");
            c.esperarQue("editor", () -> !c.g.editoresAbertos().isEmpty());
            Visual.naEdt(() -> c.g.editoresAbertos().values().iterator().next().abrirBusca());
            Visual.renderizar(c.g, 884, 634);
            ruins.addAll(miudos(c.g, percentual, "editor"));
            assertTrue(Visual.naEdt(() -> c.g.editoresAbertos().values().iterator().next().area().getFont().getSize2D()) >= minimo, "fonte do editor");
            // cada estado de cartão
            Visual.naEdt(() -> c.g.abas().selecionar("navegador"));
            Visual.naEdt(() -> c.g.cartao().mostrar("pasta", br.com.labdesktop.ui.Gravidade.ATENCAO, "Frase", "Uma explicação longa que quebra linha.", "Botão", null));
            ruins.addAll(miudos(c.g.cartao(), percentual, "cartão"));
            // os diálogos do gerenciador
            List<br.com.labdesktop.ui.dialogos.Dialogo> dialogos = Visual.naEdt(() -> List.of(
                new DialogoApagarArquivos(List.of(new DialogoApagarArquivos.Item("src", true, false)), new ResumoDeTamanho(10, 2, 5000, true), ok -> { }),
                new DialogoConflitoDeTransferencia(new br.com.labdesktop.arquivos.FilaDeTransferencias.Conflito(
                    br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo.ENVIO, "a.txt", "/x/a.txt", 10, CenaDeArquivos.AGORA, 12, CenaDeArquivos.AGORA),
                    CenaDeArquivos.SP, d -> { }),
                new DialogoConflitoDeSalvar("a.txt", false, r -> { }),
                new DialogoFecharEditor("a.txt", true, r -> { }),
                new DialogoNome("Nova pasta", "pasta-mais", "Nome", "Nome", "x", "Criar pasta", t -> null, t -> { }),
                new DialogoConfirmar("Salvar com sudo?", "chave", "Texto do aviso.", "Salvar com sudo", true, true, ok -> { })));
            for (var d : dialogos) {
                Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560));
                ruins.addAll(miudos(d.raiz(), percentual, "diálogo " + d.titulo()));
            }
            assertEquals(List.of(), ruins, "texto miúdo no gerenciador de arquivos");
        }
    }

    @Test
    void nadaMenorQue12PxEm100Porcento(@TempDir Path tmp) throws Exception {
        conferir(100, tmp);
    }

    @Test
    void nadaMenorQueOMinimoEm130Porcento(@TempDir Path tmp) throws Exception {
        conferir(130, tmp);
    }

    @Test
    void oFatorDoDonoMudaATabelaEAArvoreNaHora(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            float antes = Visual.naEdt(() -> c.g.tabela().getFont().getSize2D());
            int alturaAntes = Visual.naEdt(() -> c.g.tabela().getRowHeight());
            Visual.naEdt(() -> {
                Fontes.definirPercentual(130);
                javax.swing.SwingUtilities.updateComponentTreeUI(c.g);
            });
            assertTrue(Visual.naEdt(() -> c.g.tabela().getFont().getSize2D()) > antes, "a fonte da tabela cresce com o fator");
            assertTrue(Visual.naEdt(() -> c.g.tabela().getRowHeight()) > alturaAntes, "a altura da linha acompanha a fonte");
            assertTrue(Visual.naEdt(() -> c.g.arvore().getRowHeight()) >= 28);
        }
    }
}
