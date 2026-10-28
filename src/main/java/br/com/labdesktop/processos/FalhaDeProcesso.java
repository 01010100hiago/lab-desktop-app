package br.com.labdesktop.processos;

/** Algo impediu de preparar a ação (o processo mudou, já saiu, é protegido, o servidor não deixa conferir...). Leva o desfecho e a frase. */
public final class FalhaDeProcesso extends Exception {

    private static final long serialVersionUID = 1L;

    private final Desfecho desfecho;
    private final String comandoParaODono;

    public FalhaDeProcesso(Desfecho desfecho, String mensagem) {
        this(desfecho, mensagem, "", null);
    }

    public FalhaDeProcesso(Desfecho desfecho, String mensagem, String comandoParaODono, Throwable causa) {
        super(mensagem, causa);
        this.desfecho = desfecho;
        this.comandoParaODono = comandoParaODono == null ? "" : comandoParaODono;
    }

    public Desfecho desfecho() {
        return desfecho;
    }

    public String comandoParaODono() {
        return comandoParaODono;
    }

    public Resultado comoResultado() {
        return new Resultado(desfecho, getMessage(), comandoParaODono);
    }
}
