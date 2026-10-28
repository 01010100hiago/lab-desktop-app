package br.com.labdesktop.registro;

/** Quão grave é uma linha do registro. Sempre mostrada em PALAVRA e ícone, nunca só pela cor. É uma estimativa feita pelo texto da linha (veja {@link ClassificadorDeSeveridade}). */
public enum Severidade {
    INFO("Info"),
    AVISO("Aviso"),
    ERRO("Erro");

    private final String palavra;

    Severidade(String palavra) {
        this.palavra = palavra;
    }

    /** A palavra na tela: Info, Aviso ou Erro. */
    public String palavra() {
        return palavra;
    }

    /** Esta severidade é pelo menos tão grave quanto {@code minima}? */
    public boolean alemDe(Severidade minima) {
        return ordinal() >= minima.ordinal();
    }
}
