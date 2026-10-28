package br.com.labdesktop.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;

/**
 * O detector de transbordo: numa árvore já dimensionada e disposta, acha (1) barra de rolagem HORIZONTAL à vista, (2) conteúdo de uma área rolante mais LARGO que a janela
 * dela e (3) componente que sai para fora da largura do que o contém. Sai a lista de achados com o caminho do componente, vazia quando nada transborda. Na EDT.
 */
public final class SemTransbordo {

    private SemTransbordo() { }

    public static List<String> achados(JComponent raiz) {
        List<String> achados = new ArrayList<>();
        Visual.layoutar(raiz);
        varrer(raiz, raiz.getClass().getSimpleName(), achados);
        return achados;
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
