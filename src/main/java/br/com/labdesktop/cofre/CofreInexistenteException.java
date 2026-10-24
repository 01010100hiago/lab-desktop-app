package br.com.labdesktop.cofre;

/** Não há nenhum arquivo de cofre na pasta: é a primeira execução (a tela oferece "Criar cofre"). */
public final class CofreInexistenteException extends CofreException {

    private static final long serialVersionUID = 1L;

    public CofreInexistenteException() {
        super("Não há cofre nesta pasta.");
    }
}
