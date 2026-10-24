package br.com.labdesktop.seguranca;

/** Uso de um segredo depois de zerado (cofre bloqueado, diálogo fechado). Erro de programação ou corrida benigna. */
public final class SegredoZeradoException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public SegredoZeradoException(String mensagem) {
        super(mensagem);
    }
}
