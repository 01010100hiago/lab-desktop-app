package br.com.labdesktop.ui.componentes;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Montagem de painéis em linha ou coluna com espaçamento fixo (o {@code gap} do CSS), sem dependência nova de
 * layout. Os painéis saem transparentes.
 */
public final class Caixa {

    private Caixa() { }

    /** Componentes lado a lado, centralizados na vertical, com {@code gap} px entre eles. */
    public static JPanel linha(int gap, Component... filhos) {
        JPanel p = new Painel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        boolean primeiro = true;
        for (Component c : filhos) {
            if (c == null) {
                continue;
            }
            if (!primeiro && gap > 0) {
                p.add(Box.createRigidArea(new Dimension(gap, 0)));
            }
            if (c instanceof JComponent j) {
                j.setAlignmentY(Component.CENTER_ALIGNMENT);
            }
            p.add(c);
            primeiro = false;
        }
        return p;
    }

    /** Componentes um sobre o outro, alinhados à esquerda, com {@code gap} px entre eles. */
    public static JPanel coluna(int gap, Component... filhos) {
        JPanel p = new Painel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        boolean primeiro = true;
        for (Component c : filhos) {
            if (c == null) {
                continue;
            }
            if (!primeiro && gap > 0) {
                p.add(Box.createRigidArea(new Dimension(0, gap)));
            }
            if (c instanceof JComponent j) {
                j.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            p.add(c);
            primeiro = false;
        }
        return p;
    }

    /** Uma "mola": empurra o que vem depois para o fim da linha. */
    public static Component mola() {
        return Box.createHorizontalGlue();
    }

    /** Mola vertical. */
    public static Component molaVertical() {
        return Box.createVerticalGlue();
    }

    /** Um espaço fixo horizontal. */
    public static Component espaco(int largura) {
        return Box.createRigidArea(new Dimension(largura, 0));
    }

    /** Um espaço fixo vertical. */
    public static Component espacoVertical(int altura) {
        return Box.createRigidArea(new Dimension(0, altura));
    }
}
