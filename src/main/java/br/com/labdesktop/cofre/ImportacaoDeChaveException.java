package br.com.labdesktop.cofre;

/** Falha ao importar uma chave existente; {@link #motivo()} diz à tela se vale pedir a passphrase de novo. */
public final class ImportacaoDeChaveException extends CofreException {

    private static final long serialVersionUID = 1L;

    /** Por que a importação falhou. */
    public enum Motivo {
        /** A chave tem passphrase e nenhuma foi informada. */
        PEDE_SENHA,
        /** A passphrase informada não abre a chave. */
        SENHA_INCORRETA,
        /** O conteúdo não é uma chave OpenSSH legível. */
        FORMATO_INVALIDO,
        /** É uma chave válida, mas não Ed25519. */
        ALGORITMO_NAO_SUPORTADO,
        /** O arquivo não pôde ser lido ou é grande demais. */
        ARQUIVO_ILEGIVEL
    }

    private final Motivo motivo;

    public ImportacaoDeChaveException(Motivo motivo, String mensagem) {
        super(mensagem);
        this.motivo = motivo;
    }

    public ImportacaoDeChaveException(Motivo motivo, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
