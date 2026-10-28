package br.com.labdesktop.ui;

import br.com.labdesktop.Versao;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A janelinha de abertura: o nome do app e "Abrindo...", numa janela simples sem borda, no centro da tela, desde o começo da partida até a primeira janela
 * de verdade aparecer. Só existe quando a partida passa de 1 segundo ({@link br.com.labdesktop.util.MedidaDaPartida}); numa máquina rápida ninguém a vê.
 * É uma janela do Swing comum (nada de {@code -splash} da JVM, que o app empacotado não tem), pintada à mão com cores fixas e neutras: ela nasce ANTES do
 * tema, então não depende dele.
 *
 * <p>Sem tela (modo sem cabeça, autoteste) {@link #mostrar()} não faz nada e {@link #fechar()} também não.
 */
public final class JanelaDeAbertura {

    private static final Color FUNDO = new Color(0x1D2024);
    private static final Color BORDA = new Color(0x34383B);
    private static final Color TEXTO = new Color(0xE6E8EA);
    private static final Color TEXTO_APAGADO = new Color(0x9AA1A8);

    private JWindow janela;
    private Timer quadros;

    private JanelaDeAbertura() { }

    /** Mostra a janelinha (na EDT, sem esperar). Sem tela, devolve uma que não faz nada. */
    public static JanelaDeAbertura mostrar() {
        JanelaDeAbertura j = new JanelaDeAbertura();
        if (GraphicsEnvironment.isHeadless()) {
            return j;
        }
        SwingUtilities.invokeLater(() -> j.abrir());
        return j;
    }

    private void abrir() {
        try {
            janela = new JWindow();
            PainelDeAbertura painel = new PainelDeAbertura();
            janela.setContentPane(painel);
            janela.pack();
            janela.setLocationRelativeTo(null);
            janela.setAlwaysOnTop(true);
            janela.setVisible(true);
            quadros = new Timer(50, e -> painel.repaint());
            quadros.start();
        } catch (RuntimeException e) {
            janela = null; // sem a janelinha o app abre do mesmo jeito
        }
    }

    /** Fecha a janelinha (de qualquer thread; idempotente). */
    public void fechar() {
        SwingUtilities.invokeLater(() -> {
            if (quadros != null) {
                quadros.stop();
                quadros = null;
            }
            if (janela != null) {
                janela.setVisible(false);
                janela.dispose();
                janela = null;
            }
        });
    }

    /** O desenho da janelinha: pintado à mão com cores fixas (não há tema ainda). */
    public static final class PainelDeAbertura extends JPanel {
        private static final long serialVersionUID = 1L;

        public PainelDeAbertura() {
            setOpaque(true);
            setPreferredSize(new Dimension(360, 170));
            getAccessibleContext().setAccessibleName(Versao.NOME + ": abrindo");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(FUNDO);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(BORDA);
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setColor(TEXTO);
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
                var fm = g2.getFontMetrics();
                g2.drawString(Versao.NOME, (getWidth() - fm.stringWidth(Versao.NOME)) / 2, 78);
                g2.setColor(TEXTO_APAGADO);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                String frase = "Abrindo...";
                var fm2 = g2.getFontMetrics();
                g2.drawString(frase, (getWidth() - fm2.stringWidth(frase)) / 2, 108);
                // o arco que gira, embaixo
                int d = 18;
                double angulo = System.nanoTime() / 4_000_000L % 360;
                g2.setColor(TEXTO);
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Arc2D.Double((getWidth() - d) / 2.0, 124, d, d, -angulo, 270, Arc2D.OPEN));
            } finally {
                g2.dispose();
            }
        }
    }
}
