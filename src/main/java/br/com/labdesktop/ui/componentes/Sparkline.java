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
    /** A série inteira COM os buracos ({@code null}), na ordem do tempo: só existe em {@link #definirComLacunas}; senão é a própria {@link #valores}. */
    private List<Double> grade = List.of();
    private boolean comLacunas;
    private boolean carregando;
    private boolean tracejado;
    private transient java.util.function.IntFunction<String> descricaoDoPonto;
    private Double minimo;
    private Double maximo;
    private Double referencia;
    private String tokenDaCor = Tokens.DESTAQUE;
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

    /** Linha tracejada: um jeito de distinguir dois gráficos que não dependa só da cor (Upload, que acompanha o Download). */
    public Sparkline tracejado(boolean sim) {
        this.tracejado = sim;
        repaint();
        return this;
    }

    /**
     * Mostra o esqueleto (barras cinzas) no lugar da curva, até a primeira resposta de um período novo. Não mexe nos valores: ao voltar a {@code false}, a curva volta.
     */
    public Sparkline carregando(boolean sim) {
        this.carregando = sim;
        repaint();
        return this;
    }

    /**
     * Quem descreve o ponto sob o mouse (índice da série COM os buracos, do primeiro ao último): vira a dica ao passar o mouse ("28/10 11:40: 12%"). {@code null} desliga.
     * A dica só muda a dica da própria peça: nada de rede nem de tela.
     */
    public Sparkline descricaoDoPonto(java.util.function.IntFunction<String> quem) {
        this.descricaoDoPonto = quem;
        if (quem != null && getMouseMotionListeners().length == 0) {
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    atualizarDica(e.getX());
                }
            });
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        }
        return this;
    }

    private void atualizarDica(int x) {
        int n = grade.size();
        if (descricaoDoPonto == null || n == 0 || getWidth() <= 0) {
            return;
        }
        double largUtil = Math.max(1, getWidth() - 2.0 * MARGEM);
        int i = n == 1 ? 0 : (int) Math.round((x - MARGEM) / largUtil * (n - 1));
        i = Math.max(0, Math.min(n - 1, i));
        String d = descricaoDoPonto.apply(i);
        if (d != null && !d.isEmpty()) {
            setToolTipText(d);
        }
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
        this.grade = this.valores;
        this.comLacunas = false;
        repaint();
    }

    /**
     * Troca a série MANTENDO os buracos: um {@code null} (ou NaN) é "sem dado neste instante", e a linha se parte ali em vez de ligar os dois lados. A posição de cada ponto
     * é o índice dele na série inteira, então a curva fica alinhada ao tempo mesmo com buracos.
     */
    public void definirComLacunas(List<? extends Number> novos) {
        List<Double> g = new ArrayList<>();
        List<Double> limpos = new ArrayList<>();
        if (novos != null) {
            for (Number n : novos) {
                boolean valido = n != null && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue());
                g.add(valido ? n.doubleValue() : null);
                if (valido) {
                    limpos.add(n.doubleValue());
                }
            }
        }
        this.grade = java.util.Collections.unmodifiableList(g);
        this.valores = List.copyOf(limpos);
        this.comLacunas = true;
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

    /** O mínimo e o máximo da escala (já com a folga de 8% quando a escala segue os dados). */
    private double[] escala() {
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
        return new double[] {min, max};
    }

    /** Os trechos contínuos da série (um por pedaço entre buracos), em pixels. Sem buracos é um trecho só, igual aos {@link #pontos}. */
    List<List<Point2D.Double>> trechos(int largura, int altura) {
        int n = grade.size();
        List<List<Point2D.Double>> trechos = new ArrayList<>();
        if (valores.isEmpty() || n == 0) {
            return trechos;
        }
        double[] e = escala();
        double util = Math.max(1, altura - 2.0 * MARGEM);
        double largUtil = Math.max(1, largura - 2.0 * MARGEM);
        List<Point2D.Double> atual = null;
        for (int i = 0; i < n; i++) {
            Double v = grade.get(i);
            if (v == null) {
                atual = null;
                continue;
            }
            double x = n == 1 ? MARGEM + largUtil / 2 : MARGEM + largUtil * i / (n - 1);
            double frac = (v - e[0]) / (e[1] - e[0]);
            double y = MARGEM + util * (1 - Math.max(0, Math.min(1, frac)));
            if (atual == null) {
                atual = new ArrayList<>();
                trechos.add(atual);
            }
            atual.add(new Point2D.Double(x, y));
        }
        return trechos;
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
            if (carregando) {
                pintarEsqueleto(g2, w, h);
                return;
            }
            List<List<Point2D.Double>> trechos = trechos(w, h);
            boolean temCurva = valores.size() >= 2 || (comLacunas && valores.size() == 1);
            if (trechos.isEmpty() || !temCurva) {
                // Sem dados: só uma linha-base discreta (como o painel web).
                g2.setColor(new Color(linha.getRed(), linha.getGreen(), linha.getBlue(), 64));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(0, h - 1, w, h - 1);
                return;
            }
            if (referencia != null) {
                double y = yDaReferencia(h);
                if (y >= 0 && y <= h) {
                    Color c = Tokens.cor(Tokens.TEXTO3);
                    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 180));
                    g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 3f}, 0f));
                    g2.drawLine(0, (int) Math.round(y), w, (int) Math.round(y));
                }
            }
            BasicStroke traco = tracejado
                ? new BasicStroke(ESPESSURA, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 4f}, 0f)
                : new BasicStroke(ESPESSURA, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            Point2D.Double ultimo = null;
            for (List<Point2D.Double> pts : trechos) {
                ultimo = pts.get(pts.size() - 1);
                if (pts.size() == 1) {
                    g2.setColor(linha);
                    g2.fill(new Ellipse2D.Double(ultimo.x - ESPESSURA, ultimo.y - ESPESSURA, 2 * ESPESSURA, 2 * ESPESSURA));
                    continue;
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
                area.lineTo(ultimo.x, h);
                area.lineTo(p0.x, h);
                area.closePath();
                g2.setPaint(new GradientPaint(0, 0, new Color(linha.getRed(), linha.getGreen(), linha.getBlue(), 72),
                    0, h, new Color(linha.getRed(), linha.getGreen(), linha.getBlue(), 0)));
                g2.fill(area);
                g2.setColor(linha);
                g2.setStroke(traco);
                g2.draw(curva);
            }
            double r = ESPESSURA + 2;
            g2.setColor(Tokens.cor(Tokens.SUPERFICIE));
            g2.fill(new Ellipse2D.Double(ultimo.x - r, ultimo.y - r, 2 * r, 2 * r));
            g2.setColor(linha);
            g2.fill(new Ellipse2D.Double(ultimo.x - ESPESSURA - 0.5, ultimo.y - ESPESSURA - 0.5, 2 * ESPESSURA + 1, 2 * ESPESSURA + 1));
        } finally {
            g2.dispose();
        }
    }

    /** Barras cinzas no lugar da curva, enquanto o período novo ainda não chegou. */
    private void pintarEsqueleto(Graphics2D g2, int w, int h) {
        g2.setColor(Tokens.cor(Tokens.ESQUELETO_A));
        int larg = Math.max(30, w - 2 * MARGEM);
        g2.fillRoundRect(MARGEM, h / 2 - 9, larg, 6, 6, 6);
        g2.fillRoundRect(MARGEM, h / 2 + 2, Math.max(20, larg * 2 / 3), 6, 6, 6);
    }

    /** Onde a referência cai, na mesma escala dos pontos (fora da faixa devolve um valor fora da área). */
    private double yDaReferencia(int h) {
        double[] e = escala();
        double util = Math.max(1, h - 2.0 * MARGEM);
        return MARGEM + util * (1 - (referencia - e[0]) / (e[1] - e[0]));
    }
}
