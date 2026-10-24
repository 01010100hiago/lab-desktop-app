package br.com.labdesktop.ssh;

/** A sondagem do host não conseguiu obter a chave (host fora do ar, porta errada, não é SSH, prazo). */
public class SondagemException extends Exception {

    private static final long serialVersionUID = 1L;

    public SondagemException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
