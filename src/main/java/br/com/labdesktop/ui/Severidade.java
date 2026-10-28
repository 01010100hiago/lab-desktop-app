package br.com.labdesktop.ui;

import br.com.labdesktop.ui.tema.Tokens;

/**
 * Cor de preenchimento de uma barra (ARQUITETURA 8.2): normal (destaque), alta (âmbar, acima de 75 %) e crítica
 * (terracota, acima de 90 %). A barra sempre vem com o número ao lado: severidade nunca é só cor.
 */
public enum Severidade {
    NORMAL(Tokens.DESTAQUE),
    ALTA(Tokens.ATENCAO),
    CRITICA(Tokens.PERIGO);

    /** Limiar da severidade alta nas barras de uso (CPU, memória, disco): estritamente acima de 75 %. */
    public static final double LIMIAR_ALTA = 0.75;
    /** Limiar da severidade crítica nas barras de uso: estritamente acima de 90 %. */
    public static final double LIMIAR_CRITICA = 0.90;

    private final String tokenDaCor;

    Severidade(String tokenDaCor) {
        this.tokenDaCor = tokenDaCor;
    }

    public String tokenDaCor() {
        return tokenDaCor;
    }

    /** O equivalente de {@code UI.classeBarra(frac)}: {@code frac > 0,75} alto, {@code > 0,9} crítico. */
    public static Severidade doUso(double fracao) {
        return fracao > LIMIAR_CRITICA ? CRITICA : fracao > LIMIAR_ALTA ? ALTA : NORMAL;
    }

    /**
     * A barra de CRÉDITO do plano (5 h e semana): a mesma régua da faixa de aviso, que acende A PARTIR de 75 % e
     * vira perigo A PARTIR de 90 % (ARQUITETURA 8.2). Assim a barra e a faixa nunca discordam no 75,0 exato.
     */
    public static Severidade doCredito(double fracao) {
        return fracao >= 0.90 ? CRITICA : fracao >= 0.75 ? ALTA : NORMAL;
    }
}
