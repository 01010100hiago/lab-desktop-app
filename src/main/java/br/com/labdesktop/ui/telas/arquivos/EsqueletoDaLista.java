package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.ui.componentes.ComponenteSimples;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Barras cinzas no formato da tabela de arquivos, enquanto a pasta carrega (nunca uma tela em branco). */
final class EsqueletoDaLista extends ComponenteSimples {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("this-escape")
    EsqueletoDaLista() {
        getAccessibleContext().setAccessibleName("Carregando a pasta");
        setName("esqueleto-da-lista");
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, 260);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tokens.cor(Tokens.ESQUELETO_A));
            int w = getWidth();
            int[] larguras = {42, 30, 54, 36, 48, 26, 40, 34, 52, 28};
            int linhas = Math.min(larguras.length, Math.max(3, (getHeight() - 24) / 34));
            for (int i = 0; i < linhas; i++) {
                int y = 14 + i * 34;
                g2.fillRoundRect(14, y, 16, 16, 5, 5);
                g2.fillRoundRect(42, y + 3, Math.max(60, (w - 420) * larguras[i] / 100 + 120), 10, 5, 5);
                g2.fillRoundRect(Math.max(260, w - 380), y + 3, 70, 10, 5, 5);
                g2.fillRoundRect(Math.max(350, w - 270), y + 3, 100, 10, 5, 5);
            }
        } finally {
            g2.dispose();
        }
    }
}
