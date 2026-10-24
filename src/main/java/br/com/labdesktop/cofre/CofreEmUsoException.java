package br.com.labdesktop.cofre;

/** Outra instância do app já usa esta pasta de dados ({@link TravaDeInstancia}). */
public final class CofreEmUsoException extends CofreException {

    private static final long serialVersionUID = 1L;

    public CofreEmUsoException() {
        super("O Lab Desktop já está aberto (outra instância usa esta pasta).");
    }
}
