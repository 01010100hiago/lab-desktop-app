package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * A mini-barra do painel ({@code .barra}): trilho arredondado com o preenchimento na cor da severidade (acento,
 * âmbar, terracota). Fina (4 px), padrão (6 px) ou grossa (10 px), com um traço opcional que marca onde o freio
 * global pausa as sessões. A barra SEMPRE vem com o número ao lado (quem a usa mostra o texto): severidade nunca
 * é só cor. Sem leitura, a barra não é desenhada como 0 %: fica vazia e o texto diz "sem leitura".
 */
public final class BarraSeveridade extends JComponent {

    private static final long serialVersionUID = 1L;

    /** As três alturas do painel. */
    public enum Tamanho {
        FINA(4), PADRAO(6), GROSSA(10);

        private final int altura;

        Tamanho(int altura) {
            this.altura = altura;
        }
    }

    private final Tamanho tamanho;
    private double fracao;
    private Severidade severidade = Severidade.NORMAL;
    private Double marca;

    public BarraSeveridade(Tamanho tamanho) {
        this.tamanho = tamanho;
        setOpaque(false);
    }

    /**
     * @param fracao     de 0 a 1 (fora disso, limita); {@code NaN} desenha só o trilho
     * @param severidade cor do preenchimento
     * @param descricao  texto para leitores de tela ("42% usado, zera em 2 h")
     */
    public void definir(double fracao, Severidade severidade, String descricao) {
        this.fracao = Double.isNaN(fracao) ? 0 : Math.max(0, Math.min(1, fracao));
        this.severidade = severidade == null ? Severidade.NORMAL : severidade;
        getAccessibleContext().setAccessibleDescription(descricao);
        repaint();
    }

    /** O traço do freio global, em fração de 0 a 1; {@code null} sem marca. */
    public void marca(Double fracaoDaMarca) {
        this.marca = fracaoDaMarca == null ? null : Math.max(0, Math.min(1, fracaoDaMarca));
        repaint();
    }

    public double fracao() {
        return fracao;
    }

    public Severidade severidade() {
        return severidade;
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PROGRESS_BAR;
                }
            };
        }
        return accessibleContext;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(80, tamanho.altura);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(20, tamanho.altura);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, tamanho.altura);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = tamanho.altura;
            int y = (getHeight() - h) / 2;
            g2.setColor(Tokens.cor(Tokens.TRILHO));
            g2.fillRoundRect(0, y, w, h, h, h);
            int preenchido = (int) Math.round(w * fracao);
            if (preenchido > 0) {
                g2.setColor(Tokens.cor(severidade.tokenDaCor()));
                g2.fillRoundRect(0, y, Math.max(preenchido, h), h, h, h);
            }
            if (marca != null) {
                int x = (int) Math.round((w - 2) * marca);
                g2.setColor(Tokens.cor(Tokens.TEXTO));
                g2.fillRect(x, y - 2, 2, h + 4);
            }
        } finally {
            g2.dispose();
        }
    }
}
