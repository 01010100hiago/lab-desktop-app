package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Font;
import javax.swing.JTextArea;

/**
 * Uma área de texto SOMENTE LEITURA, monoespaçada, com as cores de "console" do tema (os tokens {@code Lab.logs*}): é onde o
 * app mostra o que veio de dentro do servidor e é longo (logs de container, a tela do Claude). O usuário pode selecionar e copiar;
 * nunca é HTML. As cores são lidas a cada pintura, então a troca de tema repinta sozinha. Quem usa a põe num {@code JScrollPane}
 * (a rolagem é por dentro, a altura não cresce com o texto).
 */
public final class AreaMonoespacada extends JTextArea {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public AreaMonoespacada(String nomeAcessivel, boolean quebrarLinhas) {
        setEditable(false);
        setLineWrap(quebrarLinhas);
        setWrapStyleWord(false);
        br.com.labdesktop.ui.tema.Fontes.aplicar(this, br.com.labdesktop.ui.tema.Fontes.MONO, Font.PLAIN, 12f);
        putClientProperty("html.disable", Boolean.TRUE);
        // A rolagem é de quem usa (seguir o fim ou ficar onde o usuário parou): trocar o texto não pode mexer nela sozinho.
        ((javax.swing.text.DefaultCaret) getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        getAccessibleContext().setAccessibleName(nomeAcessivel);
    }

    /**
     * O texto, trocando o que a fonte não sabe desenhar: a tela do Claude usa símbolos de terminal ({@code ⎿}, {@code ✻}) que
     * a JetBrains Mono não tem e que viram um quadradinho vazio. Os conhecidos ganham um parecido; o resto vira um ponto médio.
     */
    @Override
    public void setText(String texto) {
        super.setText(adaptar(texto));
    }

    private String adaptar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        Font f = getFont();
        StringBuilder sb = new StringBuilder(texto.length());
        texto.codePoints().forEach(cp -> {
            if (cp < 0x80 || cp == '\n' || cp == '\t' || f.canDisplay(cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(substituto(f, cp));
            }
        });
        return sb.toString();
    }

    private static char substituto(Font f, int cp) {
        char proposto = switch (cp) {
            case 0x23BF -> '└';
            case 0x273B, 0x2736, 0x2733, 0x2722, 0x273D, 0x273A, 0x2731, 0x2732 -> '*';
            case 0x23FA -> '●';
            default -> '·';
        };
        return f.canDisplay(proposto) ? proposto : '*';
    }

    @Override
    public Color getBackground() {
        return Tokens.cor(Tokens.LOGS_BG);
    }

    @Override
    public Color getForeground() {
        return Tokens.cor(Tokens.LOGS_TEXTO);
    }

    @Override
    public Color getSelectionColor() {
        return Tokens.cor(Tokens.DESTAQUE_SUAVE);
    }

    @Override
    public Color getSelectedTextColor() {
        return Tokens.cor(Tokens.LOGS_TEXTO);
    }

    @Override
    public Color getCaretColor() {
        return Tokens.cor(Tokens.LOGS_TEXTO);
    }

    /**
     * O {@code JScrollPane} que a segura, com a moldura e o fundo de console, rolagem horizontal quando a linha não quebra.
     */
    public static javax.swing.JScrollPane emRolagem(AreaMonoespacada area) {
        javax.swing.JScrollPane r = new javax.swing.JScrollPane();
        r.setViewport(new javax.swing.JViewport() {
            private static final long serialVersionUID = 1L;

            /** O fundo de console é lido do token na pintura: um fundo guardado ficava com a cor do tema de antes da troca. */
            @Override
            public Color getBackground() {
                return Tokens.cor(Tokens.LOGS_BG);
            }
        });
        r.setViewportView(area);
        r.setBorder(new javax.swing.border.AbstractBorder() {
            private static final long serialVersionUID = 1L;

            @Override
            public void paintBorder(java.awt.Component c, java.awt.Graphics g, int x, int y, int w, int h) {
                g.setColor(Tokens.cor(Tokens.LOGS_BORDA));
                g.drawRect(x, y, w - 1, h - 1);
            }

            @Override
            public java.awt.Insets getBorderInsets(java.awt.Component c) {
                return new java.awt.Insets(1, 1, 1, 1);
            }
        });
        r.getViewport().setOpaque(true);
        r.getVerticalScrollBar().setUnitIncrement(24);
        return r;
    }
}
