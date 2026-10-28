package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * O {@code RSyntaxTextArea} do editor, com a paleta neutra do app no lugar da padrão (que usa azul e ciano, cores que cansam a vista
 * do usuário): palavras reservadas na cor de destaque, texto entre aspas em verde discreto, números em marrom fosco, comentários em cinza. As cores
 * saem dos tokens a cada troca de tema ({@link #updateUI}) e a fonte do editor segue o fator de texto do usuário.
 *
 * <p>Ligações de segurança: links dentro do arquivo NÃO são clicáveis (o conteúdo é do servidor e não vira navegação), e o
 * texto é só texto, nunca HTML.
 */
final class AreaDeEdicao extends RSyntaxTextArea {

    private static final long serialVersionUID = 1L;

    /** Quem repinta o resto do editor (a calha dos números de linha) quando a fonte ou o tema mudam. */
    private transient Consumer<AreaDeEdicao> aoMudarAparencia = a -> { };
    /** O {@code updateUI} roda DENTRO do construtor do RSTA, antes de ele ter montado as próprias peças: só depois daqui vale pintar. */
    private boolean pronta;

    @SuppressWarnings("this-escape")
    AreaDeEdicao() {
        super();
        setCodeFoldingEnabled(false);
        setHyperlinksEnabled(false);
        setMarkOccurrences(false);
        setBracketMatchingEnabled(true);
        setAntiAliasingEnabled(true);
        setTabSize(4);
        setTabsEmulated(false);
        setPaintTabLines(false);
        setAutoIndentEnabled(true);
        setHighlightCurrentLine(true);
        setFadeCurrentLineHighlight(false);
        setRoundedSelectionEdges(false);
        setUseSelectedTextColor(false);
        putClientProperty("html.disable", Boolean.TRUE);
        pronta = true;
        aplicarPaleta();
    }

    /**
     * O RSTA pede {@code getGraphics()} uma vez, para medir a fonte, e estoura se vier {@code null}, que é o que um componente fora
     * de janela devolve (a captura de tela dos testes pinta assim). Numa janela de verdade o gráfico existe e este desvio nunca roda.
     */
    @Override
    public Graphics getGraphics() {
        Graphics g = super.getGraphics();
        return g != null ? g : new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
    }

    void aoMudarAparencia(Consumer<AreaDeEdicao> ouvinte) {
        this.aoMudarAparencia = ouvinte;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (!pronta) {
            return;
        }
        aplicarPaleta();
        if (aoMudarAparencia != null) {
            aoMudarAparencia.accept(this);
        }
    }

    @Override
    public void setFont(Font f) {
        super.setFont(f);
        if (aoMudarAparencia != null) {
            aoMudarAparencia.accept(this);
        }
    }

    /** Lê os tokens do tema de agora. */
    void aplicarPaleta() {
        setBackground(Tokens.cor(Tokens.SUPERFICIE));
        setForeground(Tokens.cor(Tokens.TEXTO));
        setCaretColor(Tokens.cor(Tokens.DESTAQUE));
        setSelectionColor(Tokens.cor(Tokens.DESTAQUE_BORDA));
        setCurrentLineHighlightColor(Tokens.cor(Tokens.SUPERFICIE2));
        setMarkAllHighlightColor(Tokens.cor(Tokens.ATENCAO_SUAVE).darker());
        setMatchedBracketBGColor(Tokens.cor(Tokens.DESTAQUE_SUAVE));
        setMatchedBracketBorderColor(Tokens.cor(Tokens.DESTAQUE));
        setHyperlinkForeground(Tokens.cor(Tokens.DESTAQUE));
        setTabLineColor(Tokens.cor(Tokens.BORDA));
        setSyntaxScheme(esquema());
    }

    /** O esquema de cores: o do RSTA com as cores trocadas e as fontes (negrito, itálico) que ele já trazia. */
    private SyntaxScheme esquema() {
        SyntaxScheme s = new SyntaxScheme(getFont(), true);
        Color texto = Tokens.cor(Tokens.TEXTO);
        Color mudo = Tokens.cor(Tokens.TEXTO3);
        Color secundario = Tokens.cor(Tokens.TEXTO2);
        Color destaque = Tokens.cor(Tokens.DESTAQUE);
        Color verde = Tokens.cor(Tokens.OK);
        Color ambar = Tokens.cor(Tokens.SERIE5);
        Color oliva = Tokens.cor(Tokens.SERIE6);
        Color rosa = Tokens.cor(Tokens.SERIE7);
        Color perigo = Tokens.cor(Tokens.PERIGO);
        for (int i = 0; i < s.getStyleCount(); i++) {
            Style st = s.getStyle(i);
            if (st == null) {
                continue;
            }
            st.background = null;
            st.foreground = switch (i) {
                case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
                     TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT,
                     TokenTypes.MARKUP_DTD, TokenTypes.MARKUP_PROCESSING_INSTRUCTION -> mudo;
                case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.DATA_TYPE, TokenTypes.PREPROCESSOR,
                     TokenTypes.ANNOTATION, TokenTypes.MARKUP_TAG_NAME -> destaque;
                case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
                     TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, TokenTypes.MARKUP_CDATA -> verde;
                case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT, TokenTypes.LITERAL_NUMBER_HEXADECIMAL,
                     TokenTypes.LITERAL_BOOLEAN, TokenTypes.MARKUP_ENTITY_REFERENCE -> ambar;
                case TokenTypes.MARKUP_TAG_ATTRIBUTE, TokenTypes.FUNCTION -> oliva;
                case TokenTypes.VARIABLE, TokenTypes.REGEX -> rosa;
                case TokenTypes.OPERATOR, TokenTypes.SEPARATOR, TokenTypes.MARKUP_TAG_DELIMITER,
                     TokenTypes.MARKUP_CDATA_DELIMITER -> secundario;
                case TokenTypes.ERROR_IDENTIFIER, TokenTypes.ERROR_NUMBER_FORMAT, TokenTypes.ERROR_STRING_DOUBLE,
                     TokenTypes.ERROR_CHAR -> perigo;
                default -> texto;
            };
        }
        return s;
    }
}
