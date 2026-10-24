package br.com.labdesktop.cofre;

/**
 * Falha esperada do cofre (senha errada, arquivo corrompido, disco que falha ao gravar). É verificada de
 * propósito: a tela precisa tratar cada caso com uma mensagem própria.
 */
public class CofreException extends Exception {

    private static final long serialVersionUID = 1L;

    public CofreException(String mensagem) {
        super(mensagem);
    }

    public CofreException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
