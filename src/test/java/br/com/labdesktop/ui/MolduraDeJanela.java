package br.com.labdesktop.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * A moldura de janela do macOS desenhada por Java2D, para as imagens da galeria do README parecerem a janela de verdade: barra de título com as
 * três bolinhas, título centralizado em cinza, cantos arredondados, borda de 1 px e sombra suave, com o fundo transparente fora da janela.
 *
 * <p>Duas formas: {@link Tipo#JANELA} (as três bolinhas ativas: vermelho, amarelo e verde) e {@link Tipo#DIALOGO} (só o vermelho ativo; minimizar e ampliar
 * ficam cinza, como num diálogo do Swing no macOS). Os dois temas (claro e escuro) têm as suas cores. É só desenho de teste: o app de verdade usa a barra
 * de título do próprio sistema.
 */
public final class MolduraDeJanela {

    /** Que moldura desenhar. */
    public enum Tipo { JANELA, DIALOGO }

    /** Espaço transparente em volta da janela, onde cai a sombra. */
    public static final int MARGEM = 36;
    /** Altura da barra de título. */
    public static final int BARRA = 28;
    /** Raio dos cantos da janela. */
    public static final int RAIO = 10;
    /** Diâmetro de cada bolinha. */
    private static final int BOLINHA = 12;

    private MolduraDeJanela() { }

    /** Põe a moldura em volta do conteúdo (a imagem de dentro da janela, sem a barra de título). */
    public static BufferedImage aplicar(BufferedImage conteudo, String titulo, boolean escuro, Tipo tipo) {
        int cw = conteudo.getWidth();
        int ch = conteudo.getHeight();
        int larguraJanela = cw;
        int alturaJanela = BARRA + ch;
        int w = larguraJanela + 2 * MARGEM;
        int h = alturaJanela + 2 * MARGEM;
        BufferedImage saida = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        Shape janela = new RoundRectangle2D.Float(MARGEM, MARGEM, larguraJanela, alturaJanela, RAIO * 2f, RAIO * 2f);

        // 1. sombra: duas camadas (uma larga e suave, uma curta e mais escura), como a do macOS
        sombra(saida, larguraJanela, alturaJanela, 22, 12, escuro ? 0.60f : 0.34f);
        sombra(saida, larguraJanela, alturaJanela, 4, 2, escuro ? 0.45f : 0.22f);

        // 2. o corpo da janela (conteúdo e barra) numa imagem à parte, recortada pelos cantos arredondados com serrilhado suave
        BufferedImage corpo = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D c = corpo.createGraphics();
        try {
            ajustar(c);
            c.drawImage(conteudo, MARGEM, MARGEM + BARRA, null);
            Color topo = escuro ? new Color(60, 60, 62) : new Color(238, 238, 240);
            Color fundo = escuro ? new Color(52, 52, 54) : new Color(228, 228, 231);
            c.setPaint(new GradientPaint(0, MARGEM, topo, 0, MARGEM + BARRA, fundo));
            c.fillRect(MARGEM, MARGEM, larguraJanela, BARRA);
            c.setColor(escuro ? new Color(20, 20, 21) : new Color(200, 200, 203));
            c.fillRect(MARGEM, MARGEM + BARRA - 1, larguraJanela, 1);
            c.setComposite(java.awt.AlphaComposite.DstIn);
            c.setColor(Color.BLACK);
            c.fill(janela);
        } finally {
            c.dispose();
        }
        Graphics2D g = saida.createGraphics();
        try {
            ajustar(g);
            g.drawImage(corpo, 0, 0, null);
            // 3. bolinhas
            int cy = MARGEM + BARRA / 2;
            bolinha(g, MARGEM + 20, cy, new Color(255, 95, 87), new Color(226, 70, 63));
            if (tipo == Tipo.JANELA) {
                bolinha(g, MARGEM + 40, cy, new Color(254, 188, 46), new Color(222, 161, 35));
                bolinha(g, MARGEM + 60, cy, new Color(40, 200, 64), new Color(26, 171, 41));
            } else {
                Color cinza = escuro ? new Color(88, 88, 90) : new Color(206, 206, 208);
                Color borda = escuro ? new Color(70, 70, 72) : new Color(186, 186, 188);
                bolinha(g, MARGEM + 40, cy, cinza, borda);
                bolinha(g, MARGEM + 60, cy, cinza, borda);
            }

            // 4. título centralizado, em cinza
            g.setFont(new Font("Inter", Font.BOLD, 13));
            g.setColor(escuro ? new Color(205, 205, 208) : new Color(92, 92, 96));
            FontMetrics fm = g.getFontMetrics();
            int tx = MARGEM + (larguraJanela - fm.stringWidth(titulo)) / 2;
            int ty = MARGEM + (BARRA - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(titulo, tx, ty);

            // 5. borda de 1 px (e, no escuro, o anel preto de fora que o macOS desenha)
            if (escuro) {
                g.setColor(new Color(0, 0, 0, 170));
                g.draw(new RoundRectangle2D.Float(MARGEM - 0.5f, MARGEM - 0.5f, larguraJanela + 1f, alturaJanela + 1f, RAIO * 2f + 1f, RAIO * 2f + 1f));
                g.setColor(new Color(255, 255, 255, 46));
            } else {
                g.setColor(new Color(0, 0, 0, 70));
            }
            g.draw(new RoundRectangle2D.Float(MARGEM + 0.5f, MARGEM + 0.5f, larguraJanela - 1f, alturaJanela - 1f, RAIO * 2f - 1f, RAIO * 2f - 1f));
        } finally {
            g.dispose();
        }
        return saida;
    }

    private static void ajustar(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void bolinha(Graphics2D g, int cx, int cy, Color cor, Color borda) {
        g.setColor(cor);
        g.fill(new Ellipse2D.Float(cx - BOLINHA / 2f, cy - BOLINHA / 2f, BOLINHA, BOLINHA));
        g.setColor(borda);
        g.draw(new Ellipse2D.Float(cx - BOLINHA / 2f + 0.25f, cy - BOLINHA / 2f + 0.25f, BOLINHA - 0.5f, BOLINHA - 0.5f));
    }

    /** Pinta uma sombra preta e borrada do formato da janela, deslocada para baixo. */
    private static void sombra(BufferedImage destino, int larguraJanela, int alturaJanela, int raioDoBorrao, int desloque, float opacidade) {
        int w = destino.getWidth();
        int h = destino.getHeight();
        float[] alfa = new float[w * h];
        BufferedImage forma = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = forma.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fill(new RoundRectangle2D.Float(MARGEM, MARGEM + desloque, larguraJanela, alturaJanela, RAIO * 2f, RAIO * 2f));
        g.dispose();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alfa[y * w + x] = (forma.getRGB(x, y) >>> 24) / 255f;
            }
        }
        for (int passada = 0; passada < 3; passada++) { // três passadas de média aproximam um borrão gaussiano
            alfa = media(alfa, w, h, Math.max(1, raioDoBorrao / 2), true);
            alfa = media(alfa, w, h, Math.max(1, raioDoBorrao / 2), false);
        }
        Graphics2D d = destino.createGraphics();
        d.setComposite(java.awt.AlphaComposite.SrcOver);
        d.dispose();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float a = alfa[y * w + x] * opacidade;
                if (a <= 0.002f) {
                    continue;
                }
                int atual = destino.getRGB(x, y);
                float aa = atual >>> 24 & 0xFF;
                aa = aa / 255f;
                float novo = a + aa * (1 - a);
                int pa = Math.min(255, Math.round(novo * 255));
                destino.setRGB(x, y, pa << 24); // a sombra é preta: só o alfa conta
            }
        }
    }

    private static float[] media(float[] origem, int w, int h, int raio, boolean horizontal) {
        float[] r = new float[origem.length];
        int janela = 2 * raio + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                float soma = 0;
                for (int x = -raio; x <= raio; x++) {
                    soma += origem[y * w + Math.min(w - 1, Math.max(0, x))];
                }
                for (int x = 0; x < w; x++) {
                    r[y * w + x] = soma / janela;
                    soma += origem[y * w + Math.min(w - 1, x + raio + 1)] - origem[y * w + Math.max(0, x - raio)];
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                float soma = 0;
                for (int y = -raio; y <= raio; y++) {
                    soma += origem[Math.min(h - 1, Math.max(0, y)) * w + x];
                }
                for (int y = 0; y < h; y++) {
                    r[y * w + x] = soma / janela;
                    soma += origem[Math.min(h - 1, y + raio + 1) * w + x] - origem[Math.max(0, y - raio) * w + x];
                }
            }
        }
        return r;
    }
}
