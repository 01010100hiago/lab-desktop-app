package br.com.labdesktop.ui.componentes;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * A área rolável de uma página (as abas do projeto): sem moldura e sem fundo, acompanha a largura da janela (nunca aparece
 * barra horizontal) e SÓ rola na vertical quando o conteúdo é mais alto que a janela. Quando a janela é mais alta, o conteúdo
 * ou fica colado no topo (a página termina antes do fim da janela) ou, com {@code preencheAAltura}, é esticado até o fim:
 * é o que deixa a caixa de logs e a tela do Claude crescerem com a janela em vez de terem uma altura fixa que sobra.
 */
public final class AreaDaPagina extends JScrollPane {

    private static final long serialVersionUID = 1L;

    private final boolean preencheAAltura;

    /** O conteúdo mais um {@link Scrollable} que o segue na largura (e na altura, se pedir). */
    private final class Vista extends Painel implements Scrollable {
        private static final long serialVersionUID = 1L;

        Vista(JComponent conteudo) {
            super(new BorderLayout());
            add(conteudo, preencheAAltura ? BorderLayout.CENTER : BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visivel, int orientacao, int direcao) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visivel, int orientacao, int direcao) {
            return orientacao == SwingConstants.VERTICAL ? Math.max(24, visivel.height - 24) : visivel.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return preencheAAltura && getParent() instanceof JViewport v && v.getHeight() >= getPreferredSize().height;
        }
    }

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public AreaDaPagina(JComponent conteudo, boolean preencheAAltura) {
        super(VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
        this.preencheAAltura = preencheAAltura;
        setViewportView(new Vista(conteudo));
        setBorder(null);
        setViewportBorder(null);
        setOpaque(false);
        getViewport().setOpaque(false);
        getVerticalScrollBar().setUnitIncrement(18);
    }

    /** {@code true} se o conteúdo é mais alto que a janela da área e, portanto, rola. */
    public boolean rola() {
        return getViewport().getView().getPreferredSize().height > getViewport().getHeight() && getViewport().getHeight() > 0;
    }

    /** A altura que o conteúdo pede. */
    public int alturaDoConteudo() {
        return getViewport().getView().getPreferredSize().height;
    }
}
