package br.com.labdesktop.ui;

import br.com.labdesktop.ui.tema.Tokens;

/**
 * O "peso" visual de um estado, na paleta do painel (DESIGN.md): acento bronze, verde-musgo (ok), âmbar escuro
 * (atenção) e terracota (perigo). A cor NUNCA é o único sinal: selo, faixa e linha sempre trazem ícone e texto.
 */
public enum Gravidade {
    NEUTRO(Tokens.TEXTO3, Tokens.SUPERFICIE3, Tokens.BORDA),
    ACENTO(Tokens.ACENTO, Tokens.ACENTO_SUAVE, Tokens.ACENTO_BORDA),
    OK(Tokens.OK, Tokens.OK_SUAVE, Tokens.OK),
    ATENCAO(Tokens.ATENCAO, Tokens.ATENCAO_SUAVE, Tokens.ATENCAO),
    PERIGO(Tokens.PERIGO, Tokens.PERIGO_SUAVE, Tokens.PERIGO);

    private final String tokenDoTexto;
    private final String tokenDoFundo;
    private final String tokenDaBorda;

    Gravidade(String tokenDoTexto, String tokenDoFundo, String tokenDaBorda) {
        this.tokenDoTexto = tokenDoTexto;
        this.tokenDoFundo = tokenDoFundo;
        this.tokenDaBorda = tokenDaBorda;
    }

    /** Token da cor do texto e do ícone. */
    public String tokenDoTexto() {
        return tokenDoTexto;
    }

    /** Token do fundo suave (selo, faixa). */
    public String tokenDoFundo() {
        return tokenDoFundo;
    }

    /** Token da borda (faixa). */
    public String tokenDaBorda() {
        return tokenDaBorda;
    }
}
