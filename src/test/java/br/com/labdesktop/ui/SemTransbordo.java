package br.com.labdesktop.ui;

import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;

/**
 * O detector de transbordo: numa árvore já dimensionada e disposta, acha (1) barra de rolagem HORIZONTAL à vista, (2) conteúdo de uma área rolante mais LARGO que a janela
 * dela, (3) componente que sai para fora da largura do que o contém e (4) TEXTO CORTADO: um parágrafo ({@code TextoCorrido}) cuja altura dada pelo layout é menor que a que o
 * texto pede naquela largura (a linha que quebrou e perdeu a última palavra), e um rótulo ou botão mais largo que o espaço que recebeu (cortado com reticências), a não ser que
 * o rótulo tenha sido declarado {@code truncavel} de propósito. Sai a lista de achados com o caminho do componente, vazia quando nada transborda. Na EDT.
 */
public final class SemTransbordo {

    private SemTransbordo() { }

    public static List<String> achados(JComponent raiz) {
        List<String> achados = new ArrayList<>();
        Visual.layoutar(raiz);
        varrer(raiz, raiz.getClass().getSimpleName(), achados);
        return achados;
    }

    /**
     * Pinta o parágrafo numa imagem MAIS ALTA que a caixa dele (sem recorte) e procura tinta abaixo da caixa: é o que o usuário perderia. É a prova pelo que é pintado, que não
     * depende de a conta de altura do layout estar certa.
     */
    private static boolean tintaForaDaCaixa(TextoCorrido t) {
        int w = t.getWidth();
        int h = t.getHeight();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h + 120, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setClip(0, 0, w, h + 120);
            t.getUI().paint(g, t); // sem o recorte do componente: o que passa da caixa aparece na imagem
        } finally {
            g.dispose();
        }
        for (int y = h; y < img.getHeight(); y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) > 40) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String resumo(String texto) {
        String t = texto.strip().replace('\n', ' ');
        return t.length() > 50 ? t.substring(0, 47) + "..." : t;
    }

    private static String nome(Component c) {
        Class<?> tipo = c.getClass();
        while (tipo.getSimpleName().isEmpty() && tipo.getSuperclass() != null) {
            tipo = tipo.getSuperclass();
        }
        String texto = c instanceof javax.swing.JLabel l && l.getText() != null ? "[" + l.getText() + "]"
            : c instanceof javax.swing.AbstractButton b && b.getText() != null ? "[" + b.getText() + "]" : "";
        return tipo.getSimpleName() + texto;
    }

    private static void varrer(Component c, String caminho, List<String> achados) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof TextoCorrido t && t.getWidth() > 0 && !t.getText().isBlank()) {
            int pede = t.alturaParaLargura(t.getWidth());
            if (pede > t.getHeight() + 1) {
                achados.add(caminho + ": texto cortado, pede " + pede + " px de altura e recebeu " + t.getHeight() + " px (" + resumo(t.getText()) + ")");
            } else if (tintaForaDaCaixa(t)) {
                // a conta de altura diz que cabe, mas o texto PINTADO quebrou em mais linhas (a palavra que cabia por 1 px na conta e não cabe na pintura)
                achados.add(caminho + ": texto cortado na pintura, a caixa tem " + t.getHeight() + " px e o texto passa dela (" + resumo(t.getText()) + ")");
            }
        } else if (c instanceof Rotulo r && r.getWidth() > 0 && r.getText() != null && !r.getText().isBlank() && !r.ehTruncavel()
            && r.getPreferredSize().width > r.getWidth() + 1) {
            achados.add(caminho + ": rótulo cortado, pede " + r.getPreferredSize().width + " px de largura e recebeu " + r.getWidth() + " px (" + resumo(r.getText()) + ")");
        } else if (c instanceof javax.swing.AbstractButton b && b.getWidth() > 0 && b.getText() != null && !b.getText().isBlank()
            && b.getPreferredSize().width > b.getWidth() + 1) {
            achados.add(caminho + ": botão cortado, pede " + b.getPreferredSize().width + " px de largura e recebeu " + b.getWidth() + " px (" + resumo(b.getText()) + ")");
        }
        if (c instanceof JScrollPane rolagem) {
            if (rolagem.getHorizontalScrollBar().isVisible()) {
                achados.add(caminho + ": barra de rolagem horizontal à vista");
            }
            Component vista = rolagem.getViewport().getView();
            if (vista != null && rolagem.getViewport().getWidth() > 0 && vista.getWidth() > rolagem.getViewport().getWidth() + 1) {
                achados.add(caminho + ": o conteúdo (" + vista.getWidth() + " px) é mais largo que a janela da rolagem (" + rolagem.getViewport().getWidth() + " px)");
            }
        }
        if (c instanceof Container k) {
            boolean ehVista = c instanceof JViewport; // a vista de uma rolagem pode ser maior que a janela dela, por definição
            for (Component f : k.getComponents()) {
                if (!f.isVisible() || f.getWidth() <= 0) {
                    continue;
                }
                if (!ehVista && k.getWidth() > 0 && (f.getX() < -1 || f.getX() + f.getWidth() > k.getWidth() + 1)) {
                    achados.add(caminho + " > " + nome(f) + ": sai da largura de " + nome(k) + " (x=" + f.getX() + ", largura=" + f.getWidth() + ", pai=" + k.getWidth() + ")");
                }
                varrer(f, caminho + " > " + nome(f), achados);
            }
        }
    }
}
