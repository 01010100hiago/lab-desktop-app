package br.com.labdesktop.instalador;

/** Em que pé está um passo: a tela mostra ícone e palavra (nunca só cor). */
public enum EstadoDoPasso {
    PENDENTE("pendente"),
    EM_ANDAMENTO("em andamento"),
    FEITO("feito"),
    /** O passo conferiu e viu que já estava feito: não repetiu nada. */
    JA_ESTAVA_FEITO("já estava feito"),
    FALHOU("falhou"),
    /** Foi feito e depois desfeito (falha ou cancelamento): o instalador tirou o que tinha criado. */
    DESFEITO("desfeito");

    private final String palavra;

    EstadoDoPasso(String palavra) {
        this.palavra = palavra;
    }

    public String palavra() {
        return palavra;
    }
}
