package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * O gráfico pequeno de linha do painel web em Java2D: a série como curva suave
 * com a área embaixo em degradê, uma linha tracejada de referência (o limite de alerta) e o último valor num ponto com
 * anel. Reutilizável: quem usa só entrega os números e, se quiser, a escala fixa e a cor (por token do tema, lida a
 * cada pintura, então a troca de tema repinta sozinha).
 *
 * <p>Valores {@code null} ou {@code NaN} são descartados (como no painel web). Com menos de 2 valores válidos não há
 * linha para traçar: aparece só uma linha-base discreta. Série constante cai no meio da faixa, sem dividir por zero.
 * Um gráfico nunca vem sozinho: quem o usa escreve ao lado o valor atual e o mínimo e o máximo, porque a informação
 * não pode depender de olhar uma curva.
 */
public final class Sparkline extends ComponenteSimples {

    private static final long serialVersionUID = 1L;
    private static final int MARGEM = 3;
    private static final float ESPESSURA = 2f;

    private List<Double> valores = List.of();
    private Double minimo;
    private Double maximo;
    private Double referencia;
    private String tokenDaCor = Tokens.ACENTO;
    private int altura = 44;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public Sparkline() {
        setOpaque(false);
    }

    /** A cor da linha, por token do tema. */
    public Sparkline cor(String token) {
        this.tokenDaCor = token;
        repaint();
        return this;
    }

    /** Fixa a escala (por exemplo 0 e 1 para frações); {@code null} deixa a escala seguir os dados. */
    public Sparkline escala(Double min, Double max) {
        this.minimo = min;
        this.maximo = max;
        repaint();
        return this;
    }

    /** Uma linha tracejada num valor (o limite de alerta); {@code null} sem linha. */
    public Sparkline referencia(Double valor) {
        this.referencia = valor;
        repaint();
        return this;
    }

    /** A altura que o gráfico pede (a largura segue o layout). */
    public Sparkline altura(int pixels) {
        this.altura = pixels;
        revalidate();
        return this;
    }

    /** Troca a série. Nulos e NaN saem. */
    public void definir(List<? extends Number> novos) {
        List<Double> limpos = new ArrayList<>();
        if (novos != null) {
            for (Number n : novos) {
                if (n != null && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) {
                    limpos.add(n.doubleValue());
                }
            }
        }
        this.valores = List.copyOf(limpos);
        repaint();
    }

    /** O texto que o leitor de tela lê no lugar da curva. */
    public void descrever(String texto) {
        getAccessibleContext().setAccessibleName(texto);
    }

    /** Quantos valores válidos a série tem. */
    public int quantidadeDePontos() {
        return valores.size();
    }

    /** Os valores válidos, na ordem. */
    public List<Double> valores() {
        return valores;
    }

    /**
     * Os pontos da curva em pixels para uma área {@code largura} x {@code altura}: vazio com menos de 2 valores. Público
     * para o teste provar que a geometria é finita e cabe na área mesmo com série constante.
     */
    public List<Point2D.Double> pontos(int largura, int altura) {
        int n = valores.size();
        if (n < 2) {
            return List.of();
        }
        double min = minimo != null ? minimo : valores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = maximo != null ? maximo : valores.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= min) {
            max = min + 1;
            if (minimo == null) {
                min = min - 1;
            }
        }
        if (minimo == null || maximo == null) {
            double folga = (max - min) * 0.08;
            min -= folga;
            max += folga;
        }
        double util = Math.max(1, altura - 2.0 * MARGEM);
        double largUtil = Math.max(1, largura - 2.0 * MARGEM);
        List<Point2D.Double> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double x = MARGEM + largUtil * i / (n - 1);
            double frac = (valores.get(i) - min) / (max - min);
            double y = MARGEM + util * (1 - Math.max(0, Math.min(1, frac)));
            pts.add(new Point2D.Double(x, y));
        }
        return pts;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(120, altura);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(40, altura);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, altura);
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
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Color linha = Tokens.cor(tokenDaCor);
            List<Point2D.Double> pts = pontos(w, h);
            if (pts.isEmpty()) {
                // Sem dados: só uma linha-base discreta (como o painel web).
                g2.setColor(new Color(linha.getRed(), linha.getGreen(), linha.getBlue(), 64));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(0, h - 1, w, h - 1);
                return;
            }
            Path2D curva = new Path2D.Double();
            Point2D.Double p0 = pts.get(0);
            curva.moveTo(p0.x, p0.y);
            for (int i = 1; i < pts.size(); i++) {
                Point2D.Double a = pts.get(i - 1);
                Point2D.Double b = pts.get(i);
                curva.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2);
                if (i == pts.size() - 1) {
                    curva.quadTo(b.x, b.y, b.x, b.y);
                }
            }
            Path2D area = new Path2D.Double(curva);
            Point2D.Double ultimo = pts.get(pts.size() - 1);
            area.lineTo(ultimo.x, h);
            area.lineTo(p0.x, h);
            area.closePath();
            g2.setPaint(new GradientPaint(0, 0, new Color(linha.getRed(), linha.getGreen(), linha.getBlue(), 72),
                0, h, new Color(linha.getRed(), linha.getGreen(), linha.getBlue(), 0)));
            g2.fill(area);

            if (referencia != null) {
                double y = yDaReferencia(h);
                if (y >= 0 && y <= h) {
                    Color c = Tokens.cor(Tokens.TEXTO3);
                    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 180));
                    g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 3f}, 0f));
                    g2.drawLine(0, (int) Math.round(y), w, (int) Math.round(y));
                }
            }
            g2.setColor(linha);
            g2.setStroke(new BasicStroke(ESPESSURA, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(curva);

            double r = ESPESSURA + 2;
            g2.setColor(Tokens.cor(Tokens.SUPERFICIE));
            g2.fill(new Ellipse2D.Double(ultimo.x - r, ultimo.y - r, 2 * r, 2 * r));
            g2.setColor(linha);
            g2.fill(new Ellipse2D.Double(ultimo.x - ESPESSURA - 0.5, ultimo.y - ESPESSURA - 0.5, 2 * ESPESSURA + 1, 2 * ESPESSURA + 1));
        } finally {
            g2.dispose();
        }
    }

    /** Onde a referência cai, na mesma escala dos pontos (fora da faixa devolve um valor fora da área). */
    private double yDaReferencia(int h) {
        double min = minimo != null ? minimo : valores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = maximo != null ? maximo : valores.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= min) {
            max = min + 1;
            if (minimo == null) {
                min = min - 1;
            }
        }
        if (minimo == null || maximo == null) {
            double folga = (max - min) * 0.08;
            min -= folga;
            max += folga;
        }
        double util = Math.max(1, h - 2.0 * MARGEM);
        return MARGEM + util * (1 - (referencia - min) / (max - min));
    }
}
