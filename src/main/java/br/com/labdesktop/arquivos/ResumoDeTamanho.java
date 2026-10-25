package br.com.labdesktop.arquivos;

/**
 * Quanto há dentro de uma pasta (para a confirmação de apagar: "Esta pasta tem 143 itens e 12 MB").
 *
 * @param arquivos quantos arquivos (e links e outros itens que não são pasta)
 * @param pastas   quantas pastas, sem contar a própria
 * @param bytes    soma dos tamanhos dos arquivos
 * @param completo {@code false} se a contagem parou no teto (pasta gigante): os números são "pelo menos isto"
 */
public record ResumoDeTamanho(long arquivos, long pastas, long bytes, boolean completo) {

    public long itens() {
        return arquivos + pastas;
    }
}
