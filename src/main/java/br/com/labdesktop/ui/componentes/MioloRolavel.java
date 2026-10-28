package br.com.labdesktop.ui.componentes;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.function.IntSupplier;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * O miolo de um diálogo que pode ficar mais alto que a tela: um {@code JScrollPane} sem moldura e sem fundo, que pede a
 * altura do conteúdo ATÉ um teto e, passando dele, rola. Os botões do rodapé ficam FORA daqui, então "Testar conexão",
 * "Voltar" e "Cancelar" nunca saem da tela quando o usuário abre "Ver comando" e as "Opções avançadas" ao mesmo tempo.
 *
 * <p>O conteúdo acompanha a largura da janela (nunca aparece barra horizontal) e fica colado no topo quando a janela é
 * mais alta que ele. O teto vem de quem chama, porque só ele sabe quanto da tela o diálogo e o rodapé já ocupam.
 */
public final class MioloRolavel extends JScrollPane {

    private static final long serialVersionUID = 1L;

    private final IntSupplier alturaMaxima;

    /** O conteúdo mais um {@link Scrollable} que o segue na largura. */
    private static final class Vista extends Painel implements Scrollable {
        private static final long serialVersionUID = 1L;

        private final boolean preencher;

        Vista(JComponent conteudo, boolean preencher) {
            super(new BorderLayout());
            this.preencher = preencher;
            add(conteudo, preencher ? BorderLayout.CENTER : BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visivel, int orientacao, int direcao) {
            return 24;
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
            // Preenchendo: se a janela é mais alta que o conteúdo, o conteúdo ESTICA até o fim dela (a página usa a altura toda); se não cabe, rola como sempre.
            return preencher && getParent() instanceof javax.swing.JViewport vp && vp.getHeight() >= getPreferredSize().height;
        }
    }

    /**
     * @param conteudo     o miolo do diálogo
     * @param alturaMaxima a altura máxima que o miolo pode pedir (lida a cada medida, porque a tela e o rodapé mudam)
     */
    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public MioloRolavel(JComponent conteudo, IntSupplier alturaMaxima) {
        this(conteudo, alturaMaxima, false);
    }

    /**
     * @param preencherAltura o conteúdo ESTICA quando a janela é mais alta que ele (o Resumo faz o Registro ocupar a altura que sobra); com {@code false} fica colado no topo
     */
    @SuppressWarnings("this-escape")
    public MioloRolavel(JComponent conteudo, IntSupplier alturaMaxima, boolean preencherAltura) {
        super(new Vista(conteudo, preencherAltura), VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
        this.alturaMaxima = alturaMaxima;
        setBorder(javax.swing.BorderFactory.createEmptyBorder());
        setViewportBorder(javax.swing.BorderFactory.createEmptyBorder());
        setOpaque(false);
        getViewport().setOpaque(false);
        getVerticalScrollBar().setUnitIncrement(24);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        int teto = alturaMaxima.getAsInt();
        if (d.height <= teto) {
            return d;
        }
        // Já vai rolar: a barra tira uma faixa da largura, e o texto corrido não pode ser espremido por causa dela.
        JScrollBar barra = getVerticalScrollBar();
        int faixa = barra.isVisible() ? 0 : barra.getPreferredSize().width;
        return new Dimension(d.width + faixa, teto);
    }

    /** {@code true} se o conteúdo é mais alto que o teto e, portanto, rola. */
    public boolean rola() {
        JComponent vista = (JComponent) getViewport().getView();
        return vista.getPreferredSize().height > alturaMaxima.getAsInt();
    }

    /** Volta ao começo: ao trocar de passo o usuário lê a página nova do alto. */
    public void rolarParaOTopo() {
        getVerticalScrollBar().setValue(0);
    }
}
