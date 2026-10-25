package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * O gráfico de barras simples da página do projeto (os últimos 30 dias de consumo) em Java2D: uma barra por item, na cor de
 * acento do tema (lida a cada pintura, então a troca de tema repinta sozinha), o maior valor escrito no alto e o zero embaixo,
 * três rótulos no eixo (primeiro, do meio e último) e a explicação de cada barra na dica ao passar o mouse. Dia sem valor
 * ganha só um traço no chão: zero não some, mas também não vira uma barra.
 *
 * <p>Um gráfico nunca vem sozinho: quem o usa escreve ao lado os totais, porque a informação não pode depender de olhar as
 * alturas. Os itens vêm prontos ({@link Barra}); este componente não conhece custo, dia nem moeda.
 */
public final class GraficoDeBarras extends ComponenteSimples {

    private static final long serialVersionUID = 1L;
    private static final int ALTURA_PREFERIDA = 124;
    private static final int FOLGA_ENTRE_BARRAS = 3;
    private static final int ALTURA_DO_EIXO = 18;
    private static final int MARGEM_DO_TOPO = 14;

    /**
     * Uma barra.
     *
     * @param rotulo o texto do eixo ({@code 23 de set})
     * @param valor  a altura (0 ou mais; {@code NaN} conta como 0)
     * @param dica   a explicação completa, para a dica e para o leitor de tela
     */
    public record Barra(String rotulo, double valor, String dica) { }

    private List<Barra> barras = List.of();
    private DoubleFunction<String> formatador = v -> String.valueOf(v);
    private int sobMouse = -1;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public GraficoDeBarras() {
        setOpaque(false);
        MouseAdapter ouvinte = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mudarDestaque(indiceNoPonto(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mudarDestaque(-1);
            }
        };
        addMouseListener(ouvinte);
        addMouseMotionListener(ouvinte);
        setToolTipText("");
    }

    /**
     * @param novas      as barras, da esquerda para a direita
     * @param formatador como escrever o valor do alto do gráfico ({@code US$ 5,20})
     * @param resumo     o texto para o leitor de tela ("Consumo dos últimos 30 dias: ...")
     */
    public void definir(List<Barra> novas, DoubleFunction<String> formatador, String resumo) {
        this.barras = List.copyOf(novas);
        this.formatador = formatador;
        this.sobMouse = -1;
        getAccessibleContext().setAccessibleName(resumo);
        getAccessibleContext().setAccessibleDescription(resumo);
        repaint();
    }

    public List<Barra> barras() {
        return barras;
    }

    private double maximo() {
        double m = 0;
        for (Barra b : barras) {
            if (!Double.isNaN(b.valor())) {
                m = Math.max(m, b.valor());
            }
        }
        return m;
    }

    private void mudarDestaque(int indice) {
        if (indice != sobMouse) {
            sobMouse = indice;
            repaint();
        }
    }

    /** A barra sob o x do mouse, ou -1 (fora do gráfico). */
    public int indiceNoPonto(int x) {
        if (barras.isEmpty() || getWidth() <= 0 || x < 0 || x >= getWidth()) {
            return -1;
        }
        return Math.min(barras.size() - 1, x * barras.size() / getWidth());
    }

    /** A dica da barra {@code i}. */
    public String dicaDa(int i) {
        return i < 0 || i >= barras.size() ? null : barras.get(i).dica();
    }

    /** A altura, em pixels, da barra {@code i} na largura e altura de hoje (0 para valor zero: só o traço no chão). */
    public int alturaDaBarra(int i) {
        double max = maximo();
        int util = Math.max(1, getHeight() - ALTURA_DO_EIXO - MARGEM_DO_TOPO);
        double v = i < 0 || i >= barras.size() ? 0 : barras.get(i).valor();
        if (max <= 0 || Double.isNaN(v) || v <= 0) {
            return 0;
        }
        return Math.max(2, (int) Math.round(util * v / max));
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        return dicaDa(indiceNoPonto(e.getX()));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(240, ALTURA_PREFERIDA);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(120, 90);
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font fonte = getFont().deriveFont(10.5f);
            g2.setFont(fonte);
            FontMetrics fm = g2.getFontMetrics();
            int chao = h - ALTURA_DO_EIXO;
            Color acento = Tokens.cor(Tokens.ACENTO);
            Color trilho = Tokens.cor(Tokens.BORDA_FORTE);
            // linha do chão e a do topo (o maior valor)
            g2.setColor(Tokens.cor(Tokens.BORDA_SUTIL));
            g2.fillRect(0, chao, w, 1);
            g2.setColor(Tokens.cor(Tokens.TEXTO3));
            g2.drawString(formatador.apply(maximo()), 0, fm.getAscent());
            if (barras.isEmpty()) {
                return;
            }
            int n = barras.size();
            double passo = (double) w / n;
            for (int i = 0; i < n; i++) {
                int x = (int) Math.round(i * passo) + FOLGA_ENTRE_BARRAS / 2;
                int largura = Math.max(2, (int) Math.round(passo) - FOLGA_ENTRE_BARRAS);
                int alt = alturaDaBarra(i);
                if (alt == 0) {
                    g2.setColor(trilho);
                    g2.fillRect(x, chao - 1, largura, 1);
                    continue;
                }
                g2.setColor(i == sobMouse ? Tokens.cor(Tokens.ACENTO_FORTE) : acento);
                g2.fill(new RoundRectangle2D.Float(x, chao - alt, largura, alt, 3, 3));
            }
            // três rótulos no eixo: o primeiro, o do meio e o último (nunca todos: 30 textos não cabem)
            g2.setColor(Tokens.cor(Tokens.TEXTO3));
            int baseline = h - fm.getDescent() - 2;
            escreverNoEixo(g2, fm, barras.get(0).rotulo(), 0, w, baseline, 0);
            if (n > 2) {
                escreverNoEixo(g2, fm, barras.get(n / 2).rotulo(), (int) Math.round((n / 2 + 0.5) * passo), w, baseline, 1);
            }
            if (n > 1) {
                escreverNoEixo(g2, fm, barras.get(n - 1).rotulo(), w, w, baseline, 2);
            }
        } finally {
            g2.dispose();
        }
    }

    /** {@code ancora}: 0 começa no x, 1 centraliza nele, 2 termina nele. */
    private static void escreverNoEixo(Graphics2D g2, FontMetrics fm, String texto, int x, int largura, int baseline, int ancora) {
        if (texto == null) {
            return;
        }
        int tw = fm.stringWidth(texto);
        int inicio = ancora == 0 ? x : ancora == 1 ? x - tw / 2 : x - tw;
        g2.drawString(texto, Math.max(0, Math.min(inicio, largura - tw)), baseline);
    }
}
