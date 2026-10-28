package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
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
    private boolean negrito;
    private String familia = Fontes.TEXTO;
    private float nominal = Fontes.NOMINAL_PADRAO;

    public TextoCorrido(String texto, int larguraPreferida) {
        super();
        this.larguraPreferida = larguraPreferida;
        setEditable(false);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(javax.swing.BorderFactory.createEmptyBorder());
        setMargin(new Insets(0, 0, 0, 0));
        refazerAFonte();
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

    /** O tamanho NOMINAL; os pontos de verdade saem de {@link Fontes}. */
    public TextoCorrido tamanho(float pontos) {
        this.nominal = pontos;
        refazerAFonte();
        return this;
    }

    public TextoCorrido negrito() {
        this.negrito = true;
        refazerAFonte();
        return this;
    }

    public TextoCorrido mono(float pontos) {
        this.familia = Fontes.MONO;
        this.nominal = pontos;
        refazerAFonte();
        return this;
    }

    private void refazerAFonte() {
        Fontes.aplicar(this, familia, negrito ? Font.BOLD : Font.PLAIN, nominal);
    }

    @Override
    public Color getForeground() {
        // o construtor do JTextArea pergunta a cor (installUI) antes de os campos existirem: sem token, a do LaF
        return Tokens.cor(tokenDaCor != null ? tokenDaCor : Tokens.TEXTO);
    }

    @Override
    public Color getDisabledTextColor() {
        return Tokens.cor(Tokens.TEXTO2); // o apagado TEXTO3 dava 4,44:1 sobre o fundo suave do aviso de atenção no escuro (minimo 4,5)
    }

    /** A altura que o texto pede numa largura dada (os testes comparam com a que o layout deu: texto CORTADO é altura dada menor que esta). */
    public int alturaParaLargura(int largura) {
        return alturaPara(largura);
    }

    /**
     * Quanto a conta de altura tira da largura: o texto que cabe EXATAMENTE (281 px de texto em 281 px de caixa) cabe na conta e QUEBRA na pintura (a conta é de antes de
     * pintar, a quebra é de quando se pinta), e a última palavra caía fora da caixa e sumia ("Editar… ou Remover…" da lista "Onde fica" da Ajuda). Medindo com 2 px a menos o
     * texto no limite ganha uma linha de folga; o que não está no limite não muda.
     */
    private static final int FOLGA_DA_QUEBRA = 2;

    private int alturaPara(int largura) {
        Insets i = getInsets();
        View raiz = getUI().getRootView(this);
        raiz.setSize(Math.max(1, largura - i.left - i.right - FOLGA_DA_QUEBRA), 0);
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
