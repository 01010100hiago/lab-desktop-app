package br.com.labdesktop.git;

/**
 * Uma alteração que ainda não foi commitada (uma linha do {@code status --porcelain=v2}).
 *
 * @param caminho   o caminho de hoje
 * @param antigo    de onde veio, se foi renomeado ou copiado; senão {@code null}
 * @param tipo      o que mudou
 * @param noIndice  a mudança já foi para a área de preparo ({@code git add})
 * @param naArvore  há mudança ainda fora da área de preparo (ou o arquivo é novo e não rastreado)
 */
public record Pendencia(String caminho, String antigo, Tipo tipo, boolean noIndice, boolean naArvore) {

    /** O que aconteceu com o arquivo, em palavras. */
    public enum Tipo {
        MODIFICADO("modificado"),
        ADICIONADO("novo"),
        APAGADO("apagado"),
        RENOMEADO("renomeado"),
        COPIADO("copiado"),
        TIPO_MUDOU("tipo mudou"),
        CONFLITO("em conflito"),
        NAO_RASTREADO("não rastreado");

        private final String rotulo;

        Tipo(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }
}
