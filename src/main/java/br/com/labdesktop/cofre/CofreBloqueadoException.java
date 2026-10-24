package br.com.labdesktop.cofre;

/**
 * Uso do cofre depois de bloqueado. Não verificada: depois de bloquear, todo código que ainda segura uma
 * referência (uma conexão que está caindo, um timer) vai esbarrar nisto, e não deve ser obrigado a tratar.
 */
public final class CofreBloqueadoException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public CofreBloqueadoException() {
        super("O cofre está bloqueado.");
    }
}
