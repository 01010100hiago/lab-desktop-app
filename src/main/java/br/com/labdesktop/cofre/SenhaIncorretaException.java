package br.com.labdesktop.cofre;

/**
 * A autenticação do AES-GCM falhou em todos os arquivos do cofre. O GCM não distingue senha errada de arquivo
 * adulterado (os dois quebram a tag), então a mensagem cobre os dois casos.
 */
public final class SenhaIncorretaException extends CofreException {

    private static final long serialVersionUID = 1L;

    public SenhaIncorretaException() {
        super("Senha incorreta (ou o arquivo do cofre foi alterado).");
    }
}
