package br.com.labdesktop.terminal;

import java.io.IOException;

/**
 * O transporte recusou abrir o terminal de um jeito que repetir não resolve (por exemplo, o limite de sessões do servidor). Quem
 * recebe NÃO entra no laço de reanexação: mostra {@link #getMessage()} e oferece "Reconectar".
 */
public final class TerminalRecusadoException extends IOException {

    private static final long serialVersionUID = 1L;

    public TerminalRecusadoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
