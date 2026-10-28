package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * O "girando" de um botão com uma ação em andamento (o {@code aria-busy} do painel web): um arco que gira, na cor
 * do texto do componente. Iniciar, parar e reiniciar projeto podem segurar a requisição por minutos, então o botão
 * mostra isto no lugar do ícone e fica desabilitado até a ação terminar.
 */
public final class IconeOcupado implements Icon {

    private final int tamanho;
    private final String tokenDaCor;

    public IconeOcupado(int tamanho, String tokenDaCor) {
        this.tamanho = tamanho;
        this.tokenDaCor = tokenDaCor;
    }

    /** Cor do texto do componente que o desenha. */
    public IconeOcupado(int tamanho) {
        this(tamanho, null);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(tokenDaCor != null ? Tokens.cor(tokenDaCor) : c.getForeground());
            float largura = Math.max(1.6f, tamanho / 8f);
            g2.setStroke(new BasicStroke(largura, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double angulo = (System.nanoTime() / 4_000_000L % 360);
            double d = tamanho - largura;
            g2.draw(new Arc2D.Double(x + largura / 2, y + largura / 2, d, d, -angulo, 270, Arc2D.OPEN));
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return tamanho;
    }

    @Override
    public int getIconHeight() {
        return tamanho;
    }

    /**
     * Mantém o componente repintando (cerca de 20 quadros por segundo) enquanto a ação dura E o componente está na tela: se o botão sai da tela (a aba foi trocada, a janela
     * escondida) o relógio para, e volta quando ele reaparece. Antes ele rodava até a ação terminar, repintando o que ninguém via.
     *
     * @return o que para a animação de vez; chame na EDT quando a ação terminar
     */
    public static Runnable animar(JComponent c) {
        Timer t = new Timer(50, e -> c.repaint());
        java.awt.event.HierarchyListener ouvinte = e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (c.isShowing()) {
                    t.start();
                } else {
                    t.stop();
                }
            }
        };
        c.addHierarchyListener(ouvinte);
        if (c.isShowing()) {
            t.start();
        }
        return () -> {
            t.stop();
            c.removeHierarchyListener(ouvinte);
        };
    }
}
