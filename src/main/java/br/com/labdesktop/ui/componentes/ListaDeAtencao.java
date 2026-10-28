package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * "Precisa de atenção": no máximo {@value #MAXIMO} itens, do mais grave ao menos grave (em empate, a ordem dada), cada
 * um numa linha com ícone, a palavra do estado (CRÍTICO, ATENÇÃO), uma frase curta e o nome do lugar para onde leva
 * ("Segurança ›"). A linha inteira é clicável e também responde ao teclado (Tab para chegar, Enter ou espaço para abrir),
 * com o foco desenhado. Sem itens, diz com tom tranquilo que nada precisa de atenção. É uma lista de linhas dentro de
 * UMA caixa, não uma pilha de cartões.
 */
public final class ListaDeAtencao extends Painel {

    private static final long serialVersionUID = 1L;
    /** O teto de itens. */
    public static final int MAXIMO = 5;
    /** A frase de quando não há nada. */
    public static final String TEXTO_SEM_ITENS = "Nada precisa de atenção agora";

    /** Um item. {@code destino} é o nome do lugar para onde {@code aoAbrir} leva. */
    public record Item(Gravidade gravidade, String texto, String destino, Runnable aoAbrir) { }

    private final List<Linha> linhas = new ArrayList<>();
    private List<Item> itens = List.of();

    public ListaDeAtencao() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        getAccessibleContext().setAccessibleName("Precisa de atenção");
        definir(List.of());
    }

    /** Do mais grave ao menos grave, estável, até o máximo. */
    public static List<Item> ordenar(List<Item> itens) {
        List<Item> copia = new ArrayList<>(itens);
        copia.sort((a, b) -> Integer.compare(peso(b.gravidade()), peso(a.gravidade())));
        return List.copyOf(copia.subList(0, Math.min(MAXIMO, copia.size())));
    }

    private static int peso(Gravidade g) {
        return switch (g) {
            case PERIGO -> 3;
            case ATENCAO -> 2;
            case DESTAQUE -> 1;
            case NEUTRO, OK -> 0;
        };
    }

    /** Troca os itens (ordena e limita). */
    public void definir(List<Item> novos) {
        this.itens = ordenar(novos);
        removeAll();
        linhas.clear();
        if (itens.isEmpty()) {
            Rotulo r = new Rotulo(TEXTO_SEM_ITENS).tamanho(13f);
            r.setIcon(Icones.colorido("ok-circulo", 18, Tokens.OK));
            r.setIconTextGap(8);
            r.setAlignmentX(LEFT_ALIGNMENT);
            r.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 8, 0));
            add(r);
        } else {
            for (Item i : itens) {
                Linha l = new Linha(i);
                l.setAlignmentX(LEFT_ALIGNMENT);
                linhas.add(l);
                add(l);
            }
        }
        revalidate();
        repaint();
    }

    /** Os itens na ordem da tela. */
    public List<Item> itens() {
        return itens;
    }

    /** Os textos das linhas ("CRÍTICO: frase > Destino"), para os testes. */
    public List<String> textos() {
        return linhas.stream().map(Linha::textoCompleto).toList();
    }

    /** Aciona a linha {@code i} como o clique faria. */
    public void abrir(int i) {
        linhas.get(i).abrir();
    }

    public Linha linha(int i) {
        return linhas.get(i);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Uma linha clicável. */
    public static final class Linha extends Painel {
        private static final long serialVersionUID = 1L;
        private final Item item;
        private final Rotulo estado;
        private final Rotulo frase;
        private final Rotulo destino;
        private boolean sobMouse;

        @SuppressWarnings("this-escape")
        Linha(Item item) {
            super(new BorderLayout(0, 3));
            this.item = item;
            Gravidade g = item.gravidade();
            boolean critico = g == Gravidade.PERIGO;
            String palavra = critico ? "CRÍTICO" : "ATENÇÃO";
            estado = new Rotulo(palavra).corDoToken(g.tokenDoTexto()).negrito().tamanho(10.5f);
            estado.setIcon(Icones.colorido(critico ? "erro" : "alerta", 16, g.tokenDoTexto()));
            estado.setIconTextGap(6);
            frase = new Rotulo(item.texto()).tamanho(12.5f).truncavel();
            frase.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 22, 0, 0)); // alinhada com a palavra, depois do ícone
            frase.setToolTipText(item.texto());
            destino = new Rotulo(item.destino() + " ›").corDoToken(Tokens.DESTAQUE).negrito().tamanho(12f);
            JPanel linhaDeCima = new Painel(new BorderLayout(8, 0));
            linhaDeCima.add(estado, BorderLayout.CENTER);
            linhaDeCima.add(destino, BorderLayout.EAST);
            add(linhaDeCima, BorderLayout.NORTH);
            add(frase, BorderLayout.CENTER);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(7, 8, 7, 8));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            getAccessibleContext().setAccessibleName(textoCompleto());
            getAccessibleContext().setAccessibleDescription("Abre a aba " + item.destino());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    sobMouse = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    sobMouse = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        abrir();
                    }
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "abrir");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "abrir");
            getActionMap().put("abrir", new AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    abrir();
                }
            });
        }

        void abrir() {
            if (item.aoAbrir() != null) {
                item.aoAbrir().run();
            }
        }

        public String textoCompleto() {
            return estado.getText() + ": " + item.texto() + " > " + item.destino();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (sobMouse || isFocusOwner()) {
                    g2.setColor(Tokens.cor(Tokens.SUPERFICIE3));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                }
                if (isFocusOwner()) {
                    g2.setColor(Tokens.cor(Tokens.FOCO));
                    g2.setStroke(new java.awt.BasicStroke(2f));
                    g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }
}
