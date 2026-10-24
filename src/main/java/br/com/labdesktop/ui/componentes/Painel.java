package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;

/**
 * Um painel que pinta com os tokens do tema: fundo, borda de 1 px e cantos arredondados opcionais (os "cartões" e
 * "caixas" do painel web). As cores são lidas na hora de pintar, nunca guardadas, então a troca de tema vale na
 * hora.
 */
public class Painel extends JPanel {

    private static final long serialVersionUID = 1L;

    private String tokenDoFundo;
    private String tokenDaBorda;
    private int raio;
    private int friso;
    private String tokenDoFriso;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public Painel() {
        super();
        setOpaque(false);
    }

    @SuppressWarnings("this-escape")
    public Painel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    /** Fundo sólido por token (sem cantos arredondados, a menos que {@link #raio} também seja usado). */
    public Painel fundo(String token) {
        this.tokenDoFundo = token;
        repaint();
        return this;
    }

    /** Borda de 1 px por token. */
    public Painel borda(String token) {
        this.tokenDaBorda = token;
        repaint();
        return this;
    }

    /** Cantos arredondados (raio em px). */
    public Painel raio(int pixels) {
        this.raio = pixels;
        repaint();
        return this;
    }

    /** Um friso vertical de {@code largura} px à esquerda (a linha com alerta de login, "inset 3px"). */
    public Painel friso(int largura, String token) {
        this.friso = largura;
        this.tokenDoFriso = token;
        repaint();
        return this;
    }

    public Painel semFundo() {
        this.tokenDoFundo = null;
        repaint();
        return this;
    }

    @Override
    public Color getBackground() {
        return tokenDoFundo != null ? Tokens.cor(tokenDoFundo) : super.getBackground();
    }

    private RoundRectangle2D forma() {
        return new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, raio * 2f, raio * 2f);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (tokenDoFundo != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tokens.cor(tokenDoFundo));
                g2.fill(raio > 0 ? forma() : new java.awt.Rectangle(0, 0, getWidth(), getHeight()));
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }

    /**
     * Os filhos são recortados pelos cantos arredondados e a borda e o friso são pintados POR CIMA deles: assim um
     * cabeçalho de fundo quadrado dentro de um cartão redondo não vaza pelos cantos nem apaga a borda.
     */
    @Override
    protected void paintChildren(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            if (raio > 0) {
                g2.clip(forma());
            }
            super.paintChildren(g2);
        } finally {
            g2.dispose();
        }
        if (tokenDaBorda != null || friso > 0) {
            Graphics2D b = (Graphics2D) g.create();
            try {
                b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (friso > 0 && tokenDoFriso != null) {
                    b.setClip(raio > 0 ? forma() : new java.awt.Rectangle(0, 0, w, h));
                    b.setColor(Tokens.cor(tokenDoFriso));
                    b.fillRect(0, 0, friso, h);
                    b.setClip(null);
                }
                if (tokenDaBorda != null) {
                    b.setColor(Tokens.cor(tokenDaBorda));
                    b.setStroke(new BasicStroke(1f));
                    b.draw(raio > 0 ? forma() : new java.awt.Rectangle(0, 0, w - 1, h - 1));
                }
            } finally {
                b.dispose();
            }
        }
    }
}
