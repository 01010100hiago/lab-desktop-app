package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * Os avisos flutuantes do painel ({@code UI.toast}): um cartão no canto inferior direito da janela, com ícone, texto,
 * um botão de ação opcional e o fechar; some sozinho (5 s, 8 s se for erro). Fica na camada de pop-ups da janela,
 * então nunca atrapalha o layout nem rouba o foco. Sempre usado na EDT.
 */
public final class Toasts implements Notificacoes {

    private static final int LARGURA = 380;
    private static final int MARGEM = 20;
    private static final int ESPACO = 10;
    private static final int MAXIMO_NA_TELA = 4;
    private static final int SOMBRA = 6;

    private final JLayeredPane camada;
    private final List<Cartao> cartoes = new ArrayList<>();

    private Toasts(JLayeredPane camada) {
        this.camada = camada;
        camada.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                reposicionar();
            }
        });
    }

    /** Liga os avisos à janela (o {@code rootPane} do {@code JFrame}). */
    public static Toasts instalar(JRootPane raiz) {
        return new Toasts(raiz.getLayeredPane());
    }

    @Override
    public void ok(String texto) {
        mostrar(texto, Gravidade.OK, "ok-circulo", null, null, 5000);
    }

    @Override
    public void info(String texto) {
        mostrar(texto, Gravidade.ACENTO, "info", null, null, 5000);
    }

    @Override
    public void atencao(String texto) {
        mostrar(texto, Gravidade.ATENCAO, "alerta", null, null, 7000);
    }

    @Override
    public void erro(String texto) {
        mostrar(texto, Gravidade.PERIGO, "erro", null, null, 8000);
    }

    @Override
    public void comAcao(String texto, String rotuloDaAcao, Runnable acao) {
        mostrar(texto, Gravidade.ATENCAO, "alerta", rotuloDaAcao, acao, 9000);
    }

    /** Quantos avisos estão na tela agora (para os testes). */
    public int naTela() {
        return cartoes.size();
    }

    /** O texto do aviso mais recente, ou {@code null} (para os testes). */
    public String ultimoTexto() {
        return cartoes.isEmpty() ? null : cartoes.get(cartoes.size() - 1).texto;
    }

    private void mostrar(String texto, Gravidade gravidade, String icone, String rotuloDaAcao, Runnable acao, int duracaoMs) {
        while (cartoes.size() >= MAXIMO_NA_TELA) {
            remover(cartoes.get(0));
        }
        Cartao c = new Cartao(texto, gravidade, icone, rotuloDaAcao, acao);
        cartoes.add(c);
        camada.add(c, JLayeredPane.POPUP_LAYER);
        Timer t = new Timer(duracaoMs, e -> remover(c));
        t.setRepeats(false);
        c.temporizador = t;
        t.start();
        reposicionar();
    }

    private void remover(Cartao c) {
        if (c.temporizador != null) {
            c.temporizador.stop();
        }
        if (cartoes.remove(c)) {
            camada.remove(c);
            camada.repaint();
            reposicionar();
        }
    }

    private void reposicionar() {
        int y = camada.getHeight() - MARGEM;
        for (int i = cartoes.size() - 1; i >= 0; i--) {
            Cartao c = cartoes.get(i);
            Dimension d = c.getPreferredSize();
            y -= d.height;
            c.setBounds(camada.getWidth() - d.width - MARGEM, y, d.width, d.height);
            y -= ESPACO;
        }
        camada.repaint();
    }

    /** Um aviso: cartão arredondado com sombra suave. */
    private final class Cartao extends JPanel {
        private static final long serialVersionUID = 1L;

        final String texto;
        Timer temporizador;

        Cartao(String texto, Gravidade gravidade, String icone, String rotuloDaAcao, Runnable acao) {
            super(new BorderLayout(10, 0));
            this.texto = texto;
            setOpaque(false);
            setBorder(new EmptyBorder(12 + SOMBRA / 2, 14 + SOMBRA / 2, 12 + SOMBRA / 2, 10 + SOMBRA / 2));
            Rotulo marca = new Rotulo().icone(Icones.colorido(icone, 18, gravidade.tokenDoTexto()));
            marca.setVerticalAlignment(javax.swing.SwingConstants.TOP);
            add(marca, BorderLayout.WEST);

            TextoCorrido corpo = new TextoCorrido(texto, LARGURA - 120);
            JPanel centro = new Painel(new BorderLayout(0, 8));
            centro.add(corpo, BorderLayout.CENTER);
            if (rotuloDaAcao != null && acao != null) {
                Botao b = Botoes.mini(rotuloDaAcao, null);
                b.addActionListener(e -> {
                    remover(this);
                    acao.run();
                });
                JPanel linhaDoBotao = new Painel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
                linhaDoBotao.add(b);
                centro.add(linhaDoBotao, BorderLayout.SOUTH);
            }
            add(centro, BorderLayout.CENTER);

            Botao fechar = Botoes.iconeFantasma("fechar", "Fechar o aviso");
            JPanel lado = new Painel(new BorderLayout());
            lado.add(fechar, BorderLayout.NORTH);
            fechar.addActionListener(e -> remover(this));
            add(lado, BorderLayout.EAST);
            getAccessibleContext().setAccessibleName(texto);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(LARGURA, d.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth() - SOMBRA;
                int h = getHeight() - SOMBRA;
                for (int i = SOMBRA; i > 0; i--) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.035f));
                    g2.setColor(java.awt.Color.BLACK);
                    g2.fillRoundRect(SOMBRA / 2 - i / 2, SOMBRA / 2 - i / 2 + 2, w + i, h + i, 16 + i, 16 + i);
                }
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(Tokens.cor(Tokens.SUPERFICIE));
                g2.fillRoundRect(SOMBRA / 2, SOMBRA / 2, w, h, 14, 14);
                g2.setColor(Tokens.cor(Tokens.BORDA_FORTE));
                g2.drawRoundRect(SOMBRA / 2, SOMBRA / 2, w - 1, h - 1, 14, 14);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
