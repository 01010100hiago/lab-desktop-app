package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JToggleButton;

/**
 * As abas de uma tela: rótulos de texto numa fileira, a escolhida em negrito com um traço de acento embaixo, e uma
 * linha fina na largura toda. Um {@code JTabbedPane} puxaria o visual do sistema; aqui é o tema do app. A escolhida
 * nunca é só cor: tem negrito e o traço.
 */
public final class Abas extends Painel {

    /** As abas da página (14 pt, 38 px) ou as sub-abas de dentro de uma página (13 pt, 32 px). */
    private final boolean compacta;

    private static final long serialVersionUID = 1L;

    private final Map<String, JToggleButton> botoes = new LinkedHashMap<>();
    private final ButtonGroup grupo = new ButtonGroup();
    private Consumer<String> ouvinte = id -> { };
    private String selecionada;

    public Abas() {
        this(false);
    }

    /** {@code compacta}: sub-abas, menores, para o que fica dentro de uma página (Segurança). */
    public Abas(boolean compacta) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.compacta = compacta;
        setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
    }

    /** Acrescenta uma aba. A primeira vira a escolhida (sem avisar o ouvinte). */
    public JToggleButton adicionar(String id, String rotulo) {
        JToggleButton b = new JToggleButton() {
            private static final long serialVersionUID = 1L;

            @Override
            public Color getForeground() {
                return Tokens.cor(isSelected() ? Tokens.TEXTO : Tokens.TEXTO2);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(getFont());
                    g2.setColor(getForeground());
                    var fm = g2.getFontMetrics();
                    int x = (getWidth() - fm.stringWidth(getText())) / 2;
                    int y = (getHeight() - 3 - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(getText(), x, y);
                    if (isFocusOwner()) { // foco visível para quem navega pelo teclado
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(Tokens.cor(Tokens.FOCO));
                        g2.setStroke(new java.awt.BasicStroke(2f));
                        g2.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 8, 8, 8);
                    }
                    if (isSelected()) {
                        g2.setColor(Tokens.cor(Tokens.ACENTO));
                        g2.fillRect(compacta ? 6 : 8, getHeight() - 3, getWidth() - (compacta ? 12 : 16), 3);
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
        b.putClientProperty("html.disable", Boolean.TRUE);
        b.setText(rotulo);
        b.setFocusPainted(false);
        b.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                b.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                b.repaint();
            }
        });
        // setas esquerda e direita trocam de aba (o espaço e o enter já ativam o botão focado)
        b.getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0), "proxima-aba");
        b.getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0), "aba-anterior");
        b.getActionMap().put("proxima-aba", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                vizinha(id, 1);
            }
        });
        b.getActionMap().put("aba-anterior", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                vizinha(id, -1);
            }
        });
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 16, 0, 16));
        b.getAccessibleContext().setAccessibleName(rotulo);
        b.addActionListener(e -> selecionar(id, true));
        botoes.put(id, b);
        grupo.add(b);
        add(b);
        if (selecionada == null) {
            selecionada = id;
            b.setSelected(true);
        }
        atualizarFontes();
        return b;
    }

    private void atualizarFontes() {
        botoes.values().forEach(b -> {
            Font base = b.getFont().deriveFont(Font.BOLD, compacta ? 13f : 14f);
            b.setFont(base);
            b.setPreferredSize(new Dimension(b.getFontMetrics(base).stringWidth(b.getText()) + (compacta ? 28 : 40),
                compacta ? 32 : 38));
        });
    }

    /** Move a escolha para a aba ao lado (sem dar a volta) e leva o foco junto. */
    private void vizinha(String id, int passo) {
        java.util.List<String> ids = new java.util.ArrayList<>(botoes.keySet());
        int i = ids.indexOf(id) + passo;
        if (i >= 0 && i < ids.size()) {
            selecionar(ids.get(i), true);
            botoes.get(ids.get(i)).requestFocusInWindow();
        }
    }

    public void aoSelecionar(Consumer<String> novo) {
        this.ouvinte = novo;
    }

    /** Escolhe a aba e avisa o ouvinte (se mudou). */
    public void selecionar(String id) {
        selecionar(id, true);
    }

    private void selecionar(String id, boolean avisar) {
        JToggleButton b = botoes.get(id);
        if (b == null) {
            return;
        }
        b.setSelected(true);
        boolean mudou = !id.equals(selecionada);
        selecionada = id;
        repaint();
        if (mudou && avisar) {
            ouvinte.accept(id);
        }
    }

    public String selecionada() {
        return selecionada;
    }

    /** Troca o texto de uma aba ("Backups (5)") e a largura dela; a escolhida continua escolhida. */
    public void rotulo(String id, String novo) {
        JToggleButton b = botoes.get(id);
        if (b == null || novo.equals(b.getText())) {
            return;
        }
        b.setText(novo);
        b.getAccessibleContext().setAccessibleName(novo);
        atualizarFontes();
        revalidate();
        repaint();
    }

    public JToggleButton botao(String id) {
        return botoes.get(id);
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        g.setColor(Tokens.cor(Tokens.BORDA));
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }
}
