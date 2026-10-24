package br.com.labdesktop.cofre;

/** O arquivo do cofre não tem a forma esperada (magic, versão, parâmetros do KDF, tamanho ou JSON). */
public final class CofreCorrompidoException extends CofreException {

    private static final long serialVersionUID = 1L;

    public CofreCorrompidoException(String mensagem) {
        super(mensagem);
    }

    public CofreCorrompidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
