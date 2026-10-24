package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.Painel;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JComponent;

/** Segura um componente na largura que ele pede, colado à esquerda (um selo numa coluna de {@code BoxLayout} esticaria). */
final class AEsquerda extends Painel {

    private static final long serialVersionUID = 1L;

    AEsquerda(JComponent filho) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        add(filho);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
