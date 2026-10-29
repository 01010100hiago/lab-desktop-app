package br.com.labdesktop.transporte;

/** O túnel de um transporte sem porta aberta não subiu. A causa, em palavras simples, está em {@link #falha()}. */
public final class FalhaDoTunelException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient FalhaDoTunel falha;

    public FalhaDoTunelException(FalhaDoTunel falha) {
        super(falha.mensagem());
        this.falha = falha;
    }

    public FalhaDoTunel falha() {
        return falha;
    }
}
