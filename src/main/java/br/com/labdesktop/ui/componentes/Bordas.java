package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

/**
 * Bordas que leem a cor do TOKEN na hora de pintar. {@code BorderFactory.createLineBorder(Tokens.cor(...))} guarda a cor do tema que valia na construção: o usuário troca claro e
 * escuro com a janela aberta e o contorno ficava com a cor de antes. Estas não guardam cor nenhuma.
 */
public final class Bordas {

    private Bordas() { }

    /** Um contorno de 1 px nos quatro lados, na cor do token. */
    public static Border contorno(String token) {
        return new BordaDoTema(1, 1, 1, 1, token);
    }

    /** Linhas de 1 px só nos lados com valor maior que zero (largura da linha em cada lado), na cor do token: o que o {@code MatteBorder} faria. */
    public static Border lados(int cima, int esquerda, int baixo, int direita, String token) {
        return new BordaDoTema(cima, esquerda, baixo, direita, token);
    }

    private static final class BordaDoTema extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private final int cima;
        private final int esquerda;
        private final int baixo;
        private final int direita;
        private final String token;

        BordaDoTema(int cima, int esquerda, int baixo, int direita, String token) {
            this.cima = cima;
            this.esquerda = esquerda;
            this.baixo = baixo;
            this.direita = direita;
            this.token = token;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int largura, int altura) {
            g.setColor(Tokens.cor(token));
            g.fillRect(x, y, largura, cima);
            g.fillRect(x, y + altura - baixo, largura, baixo);
            g.fillRect(x, y + cima, esquerda, altura - cima - baixo);
            g.fillRect(x + largura - direita, y + cima, direita, altura - cima - baixo);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(cima, esquerda, baixo, direita);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(cima, esquerda, baixo, direita);
            return insets;
        }
    }
}
