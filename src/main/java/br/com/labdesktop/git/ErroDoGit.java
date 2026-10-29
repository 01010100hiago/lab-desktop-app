package br.com.labdesktop.git;

/** Um comando do git que não chegou a rodar ou não terminou (programa ausente, prazo, conexão): leva a falha já explicada. */
public final class ErroDoGit extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient FalhaDoGit falha;

    public ErroDoGit(FalhaDoGit falha) {
        super(falha.frase());
        this.falha = falha;
    }

    public FalhaDoGit falha() {
        return falha;
    }
}
