package br.com.labdesktop.terminal;

/**
 * O tamanho do terminal em caracteres. O terminal mede a área DEPOIS do primeiro layout e abre o canal já com esse tamanho (abrir com
 * um padrão faz o tmux redesenhar a tela duas vezes); quando não deu para medir, vale {@link #PADRAO} (120 por 32).
 */
public record TamanhoDeTerminal(int colunas, int linhas) {

    public static final TamanhoDeTerminal PADRAO = new TamanhoDeTerminal(120, 32);
    private static final int MAXIMO = 1000;

    /** Valores fora de 1 a 1000 são trazidos para dentro: o servidor nunca recebe 0 nem um número absurdo. */
    public TamanhoDeTerminal {
        colunas = Math.max(1, Math.min(MAXIMO, colunas));
        linhas = Math.max(1, Math.min(MAXIMO, linhas));
    }
}
