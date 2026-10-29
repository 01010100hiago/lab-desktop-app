package br.com.labdesktop.git;

/**
 * Um arquivo que um commit mexeu (uma linha do {@code --numstat}).
 *
 * @param caminho      o caminho de hoje (o novo, se foi renomeado)
 * @param antigo       o caminho de antes, se foi renomeado; senão {@code null}
 * @param acrescentadas linhas acrescentadas ({@code -1} em arquivo binário)
 * @param tiradas       linhas tiradas ({@code -1} em arquivo binário)
 */
public record ArquivoDoCommit(String caminho, String antigo, int acrescentadas, int tiradas) {

    public boolean binario() {
        return acrescentadas < 0 || tiradas < 0;
    }

    public boolean renomeado() {
        return antigo != null;
    }
}
