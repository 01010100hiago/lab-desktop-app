package br.com.labdesktop.ssh;

import java.io.IOException;

/** O comando remoto não terminou no prazo (a conexão está de pé: o comando é que demorou). Não é "a conexão caiu". */
public final class ComandoDemorouException extends IOException {

    private static final long serialVersionUID = 1L;

    public ComandoDemorouException(long segundos) {
        super("O comando remoto não terminou em " + segundos + " s.");
    }
}
