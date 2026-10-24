package br.com.labdesktop.api.modelo;

/** O estado exibido por projeto (regra do front, CONTRATO-API 3.7). */
public enum EstadoProjeto {
    /** Sem arquivo compose. */
    SEM_COMPOSE("sem compose"),
    /** Tem compose mas nunca subiu um container. */
    NUNCA_SUBIU("nunca subiu"),
    /** Todos os containers rodando. */
    NO_AR("no ar"),
    /** Alguns rodando. */
    PARCIAL("parcial"),
    /** Containers existem, nenhum rodando. */
    PARADO("parado");

    private final String rotulo;

    EstadoProjeto(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
