package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.View;

/**
 * Parágrafo que quebra linha na largura que o layout der (um {@code JLabel} não quebra). É um {@code JTextArea}
 * apenas de leitura, transparente e sem borda: o usuário pode selecionar e copiar uma mensagem de erro. O texto passa
 * por {@link TextoSeguro#limpar} (é texto de servidor ou de exceção), e a cor vem de um token a cada pintura.
 *
 * <p>A altura preferida é calculada para a largura ATUAL (ou para {@link #larguraPreferida} antes de existir um
 * layout), e, quando a largura muda e a altura necessária muda junto, o componente pede um novo layout sozinho:
 * isso resolve o problema clássico de "precisa de duas passadas" do Swing em texto que quebra.
 */
public final class TextoCorrido extends JTextArea {

    private static final long serialVersionUID = 1L;

    private int larguraPreferida;
    private String tokenDaCor;

    public TextoCorrido(String texto, int larguraPreferida) {
        super();
        this.larguraPreferida = larguraPreferida;
        setEditable(false);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(null);
        setMargin(new Insets(0, 0, 0, 0));
        Font base = UIManager.getFont("Label.font");
        if (base != null) {
            setFont(base);
        }
        putClientProperty("html.disable", Boolean.TRUE);
        setText(texto);
    }

    @Override
    public void setText(String texto) {
        super.setText(texto == null ? "" : TextoSeguro.limpar(texto));
        revalidate();
    }

    public TextoCorrido corDoToken(String token) {
        this.tokenDaCor = token;
        repaint();
        return this;
    }

    public TextoCorrido tamanho(float pontos) {
        setFont(getFont().deriveFont(pontos));
        return this;
    }

    public TextoCorrido negrito() {
        setFont(getFont().deriveFont(Font.BOLD));
        return this;
    }

    public TextoCorrido mono(float pontos) {
        Font m = UIManager.getFont("monospaced.font");
        setFont((m != null ? m : getFont()).deriveFont(pontos));
        return this;
    }

    @Override
    public Color getForeground() {
        // o construtor do JTextArea pergunta a cor (installUI) antes de os campos existirem: sem token, a do LaF
        return Tokens.cor(tokenDaCor != null ? tokenDaCor : Tokens.TEXTO);
    }

    @Override
    public Color getDisabledTextColor() {
        return Tokens.cor(Tokens.TEXTO3);
    }

    private int alturaPara(int largura) {
        Insets i = getInsets();
        View raiz = getUI().getRootView(this);
        raiz.setSize(Math.max(1, largura - i.left - i.right), 0);
        return (int) Math.ceil(raiz.getPreferredSpan(View.Y_AXIS)) + i.top + i.bottom;
    }

    @Override
    public Dimension getPreferredSize() {
        int w = getWidth() > 0 ? getWidth() : larguraPreferida;
        if (w <= 0) {
            return super.getPreferredSize();
        }
        return new Dimension(larguraPreferida > 0 ? Math.min(w, Math.max(larguraPreferida, getWidth())) : w, alturaPara(w));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(60, alturaPara(Math.max(60, getWidth() > 0 ? getWidth() : larguraPreferida)));
    }

    @Override
    public void setBounds(int x, int y, int largura, int altura) {
        int antes = getWidth();
        super.setBounds(x, y, largura, altura);
        if (largura > 0 && largura != antes && alturaPara(largura) != altura) {
            SwingUtilities.invokeLater(this::revalidate);
        }
    }
}
