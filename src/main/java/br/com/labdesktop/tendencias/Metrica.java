package br.com.labdesktop.tendencias;

import java.util.function.Function;

/**
 * Os seis gráficos das Tendências, na ordem da grade 3 x 2 e com os nomes EXATOS da tela: CPU, Memória, Disco, Escrita no disco, Download e Upload.
 */
public enum Metrica {

    CPU("CPU", true, UnidadesDeMedida::porcentagem),
    MEMORIA("Memória", false, UnidadesDeMedida::porcentagem),
    DISCO("Disco", false, UnidadesDeMedida::porcentagem),
    ESCRITA_NO_DISCO("Escrita no disco", true, UnidadesDeMedida::bytesPorSegundo),
    DOWNLOAD("Download", false, UnidadesDeMedida::bitsPorSegundo),
    UPLOAD("Upload", false, UnidadesDeMedida::bitsPorSegundo);

    private final String nome;
    private final boolean temMaximo;
    private final Function<Double, String> formato;

    Metrica(String nome, boolean temMaximo, Function<Double, String> formato) {
        this.nome = nome;
        this.temMaximo = temMaximo;
        this.formato = formato;
    }

    /** O nome na tela ("Escrita no disco"). */
    public String nome() {
        return nome;
    }

    /** O agente guarda também o MAIOR valor de cada célula (CPU e escrita): o pico de verdade, que a média esconde. */
    public boolean temMaximo() {
        return temMaximo;
    }

    /** O valor em texto, com a unidade desta métrica. */
    public String formatar(Double valor) {
        return formato.apply(valor);
    }

    /** É uma fração de 0 a 1 mostrada em porcentagem? */
    public boolean ehPorcentagem() {
        return this == CPU || this == MEMORIA || this == DISCO;
    }
}
