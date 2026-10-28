package br.com.labdesktop.processos;

/** Os dois sinais que o app manda: o pedido educado e o à força. Nenhum outro. */
public enum Sinal {
    /** Pede ao programa para fechar (ele pode salvar o que tem). */
    TERM("TERM", "SIGTERM"),
    /** Mata na hora; o programa não tem chance de salvar nada. */
    KILL("KILL", "SIGKILL");

    private final String nome;
    private final String rotulo;

    Sinal(String nome, String rotulo) {
        this.nome = nome;
        this.rotulo = rotulo;
    }

    /** O nome que o {@code kill} entende ({@code TERM}). */
    public String nome() {
        return nome;
    }

    /** O nome que o usuário lê ({@code SIGTERM}). */
    public String rotulo() {
        return rotulo;
    }
}
