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

    private static final long serialVersionUID = 1L;

    private final Map<String, JToggleButton> botoes = new LinkedHashMap<>();
    private final ButtonGroup grupo = new ButtonGroup();
    private Consumer<String> ouvinte = id -> { };
    private String selecionada;

    public Abas() {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
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
                    if (isSelected()) {
                        g2.setColor(Tokens.cor(Tokens.ACENTO));
                        g2.fillRect(8, getHeight() - 3, getWidth() - 16, 3);
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
        b.putClientProperty("html.disable", Boolean.TRUE);
        b.setText(rotulo);
        b.setFocusPainted(false);
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
            Font base = b.getFont().deriveFont(Font.BOLD, 14f);
            b.setFont(base);
            b.setPreferredSize(new Dimension(b.getFontMetrics(base).stringWidth(b.getText()) + 40, 38));
        });
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
