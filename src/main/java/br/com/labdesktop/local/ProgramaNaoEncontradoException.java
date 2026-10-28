package br.com.labdesktop.local;

import java.io.IOException;

/** O programa pedido não existe neste computador (não está no PATH nem nas pastas conhecidas): é o "Docker não instalado". */
public final class ProgramaNaoEncontradoException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String programa;

    public ProgramaNaoEncontradoException(String programa) {
        super("O programa " + programa + " não foi encontrado neste computador.");
        this.programa = programa;
    }

    public String programa() {
        return programa;
    }
}
