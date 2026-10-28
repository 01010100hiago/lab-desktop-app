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
import java.time.Duration;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A janelinha de abertura: o nome do app e "Abrindo...", numa janela simples sem borda, no centro da tela, desde o começo da partida até a primeira janela
 * de verdade aparecer. Só existe quando a partida passa de 1 segundo ({@link br.com.labdesktop.util.MedidaDaPartida}); numa máquina rápida ninguém a vê.
 * É uma janela do Swing comum (nada de {@code -splash} da JVM, que o app empacotado não tem), pintada à mão: ela nasce ANTES do tema, então usa as cores do tema
 * que vai valer ({@link Cores#claro()} e {@link Cores#escuro()}, as mesmas das superfícies do claro e do escuro) em vez de um escuro fixo que faria um clarão no tema claro.
 *
 * <p>Nunca fica presa: fecha sozinha depois de {@link #PRAZO_DE_SEGURANCA} e quem pode matar a partida (a segunda cópia do app, um erro antes da primeira janela, o
 * fim do app) a fecha antes ({@link #fecharAAtual()}). Não fica por cima das outras janelas (um aviso do sistema ou do app tem de aparecer na frente dela).
 * Sem tela (modo sem cabeça, autoteste) {@link #mostrar} não faz nada e {@link #fechar()} também não.
 */
public final class JanelaDeAbertura {

    /** Quanto a janelinha pode ficar na tela se ninguém a fechar. */
    public static final Duration PRAZO_DE_SEGURANCA = Duration.ofSeconds(20);

    /** As cores da janelinha: as do tema (superfície, borda, texto e texto secundário) que o usuário vai ver na janela de verdade. */
    public record Cores(Color fundo, Color borda, Color texto, Color textoApagado) {

        /** Os valores de {@code Lab.superficie}, {@code Lab.borda}, {@code Lab.texto} e {@code Lab.texto2} do tema claro (um teste os confere com o arquivo do tema). */
        public static Cores claro() {
            return new Cores(new Color(0xFFFFFF), new Color(0xD5D9DD), new Color(0x1B1F23), new Color(0x585E64));
        }

        /** O mesmo para o tema escuro. */
        public static Cores escuro() {
            return new Cores(new Color(0x1D2024), new Color(0x34383B), new Color(0xE6E8EA), new Color(0x9AA1A8));
        }

        public static Cores de(boolean escuro) {
            return escuro ? escuro() : claro();
        }
    }

    /** A janela de verdade (ou a de mentira dos testes). */
    public interface Apresentacao {
        void abrir(Cores cores);

        void fechar();
    }

    /** O relógio do prazo de segurança (o dos testes anda sem esperar). */
    public interface Prazo {
        void depois(Duration espera, Runnable acao);
    }

    private static JanelaDeAbertura atual;

    private final Apresentacao janela;
    private boolean fechada;

    private JanelaDeAbertura(Apresentacao janela) {
        this.janela = janela;
    }

    /** Mostra a janelinha (na EDT, sem esperar) com as cores do tema. Sem tela, devolve uma que não faz nada. */
    public static JanelaDeAbertura mostrar(boolean temaEscuro) {
        if (GraphicsEnvironment.isHeadless()) {
            return new JanelaDeAbertura(new Apresentacao() {
                @Override
                public void abrir(Cores cores) { }

                @Override
                public void fechar() { }
            });
        }
        return mostrar(Cores.de(temaEscuro), new ApresentacaoSwing(), (espera, acao) -> SwingUtilities.invokeLater(() -> {
            Timer t = new Timer((int) espera.toMillis(), e -> acao.run());
            t.setRepeats(false);
            t.start();
        }));
    }

    /** Com a janela e o relógio dados (os testes provam o prazo e os caminhos de falha sem tela). */
    public static JanelaDeAbertura mostrar(Cores cores, Apresentacao apresentacao, Prazo prazo) {
        JanelaDeAbertura j = new JanelaDeAbertura(apresentacao);
        synchronized (JanelaDeAbertura.class) {
            atual = j;
        }
        apresentacao.abrir(cores);
        prazo.depois(PRAZO_DE_SEGURANCA, j::fechar);
        return j;
    }

    /** Fecha a janelinha (de qualquer thread; idempotente). */
    public void fechar() {
        synchronized (JanelaDeAbertura.class) {
            if (fechada) {
                return;
            }
            fechada = true;
            if (atual == this) {
                atual = null;
            }
        }
        janela.fechar();
    }

    /** Fecha a janelinha que estiver aberta, se houver: é o que vem antes de um aviso fatal, de {@code System.exit} e do fim do app. */
    public static void fecharAAtual() {
        JanelaDeAbertura j;
        synchronized (JanelaDeAbertura.class) {
            j = atual;
        }
        if (j != null) {
            j.fechar();
        }
    }

    /** A janelinha está à vista (para os testes)? */
    public boolean aberta() {
        synchronized (JanelaDeAbertura.class) {
            return !fechada;
        }
    }

    /** A janela Swing de verdade. */
    private static final class ApresentacaoSwing implements Apresentacao {
        private JWindow janela;
        private Timer quadros;

        @Override
        public void abrir(Cores cores) {
            SwingUtilities.invokeLater(() -> {
                try {
                    janela = new JWindow();
                    PainelDeAbertura painel = new PainelDeAbertura(cores);
                    janela.setContentPane(painel);
                    janela.pack();
                    janela.setLocationRelativeTo(null);
                    janela.setVisible(true); // sem setAlwaysOnTop: o aviso de "já está aberto" e os outros diálogos têm de aparecer na frente
                    quadros = new Timer(50, e -> painel.repaint());
                    quadros.start();
                } catch (RuntimeException e) {
                    janela = null; // sem a janelinha o app abre do mesmo jeito
                }
            });
        }

        @Override
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
    }

    /** O desenho da janelinha: pintado à mão com as cores do tema (não há tema instalado ainda). */
    public static final class PainelDeAbertura extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient Cores cores;

        public PainelDeAbertura(Cores cores) {
            this.cores = cores;
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
                g2.setColor(cores.fundo());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(cores.borda());
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g2.setColor(cores.texto());
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
                var fm = g2.getFontMetrics();
                g2.drawString(Versao.NOME, (getWidth() - fm.stringWidth(Versao.NOME)) / 2, 78);
                g2.setColor(cores.textoApagado());
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                String frase = "Abrindo...";
                var fm2 = g2.getFontMetrics();
                g2.drawString(frase, (getWidth() - fm2.stringWidth(frase)) / 2, 108);
                // o arco que gira, embaixo
                int d = 18;
                double angulo = System.nanoTime() / 4_000_000L % 360;
                g2.setColor(cores.texto());
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Arc2D.Double((getWidth() - d) / 2.0, 124, d, d, -angulo, 270, Arc2D.OPEN));
            } finally {
                g2.dispose();
            }
        }
    }
}
