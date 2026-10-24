package br.com.labdesktop.ui.componentes;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * Um {@code CardLayout} que pede a ALTURA do cartão que está à vista, não a do maior de todos (o padrão do Swing deixa
 * a janela do tamanho do cartão mais alto, com um vazio enorme embaixo dos mais curtos), mas mantém a LARGURA do mais
 * largo, para a janela não balançar de um lado para o outro a cada troca.
 */
public final class CartoesAjustaveis extends CardLayout {

    private static final long serialVersionUID = 1L;

    @Override
    public Dimension preferredLayoutSize(Container pai) {
        return medir(pai, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container pai) {
        return medir(pai, true);
    }

    private static Dimension medir(Container pai, boolean minimo) {
        synchronized (pai.getTreeLock()) {
            Insets i = pai.getInsets();
            int largura = 0;
            int altura = 0;
            for (Component c : pai.getComponents()) {
                Dimension d = minimo ? c.getMinimumSize() : c.getPreferredSize();
                largura = Math.max(largura, d.width);
                if (c.isVisible()) {
                    altura = d.height;
                }
            }
            return new Dimension(largura + i.left + i.right, altura + i.top + i.bottom);
        }
    }
}
