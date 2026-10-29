package br.com.labdesktop.git;

/**
 * Uma linha do diff de um arquivo.
 *
 * @param tipo  o papel da linha
 * @param texto o texto sem o símbolo da frente ({@code +}, {@code -} ou espaço)
 */
public record LinhaDeDiff(Tipo tipo, String texto) {

    /** O que a linha é. */
    public enum Tipo {
        /** Informação do arquivo (renomeado de, modo, "arquivo novo"). */
        INFORMACAO,
        /** O cabeçalho de um trecho ({@code @@ -1,3 +1,4 @@}). */
        TRECHO,
        CONTEXTO,
        ACRESCENTADA,
        TIRADA,
        /** "Sem fim de linha no final do arquivo". */
        AVISO
    }

    /** O símbolo que vai na frente da linha na tela: a diferença NÃO depende só da cor. */
    public String simbolo() {
        return switch (tipo) {
            case ACRESCENTADA -> "+";
            case TIRADA -> "-";
            case CONTEXTO -> " ";
            case TRECHO -> "@";
            case AVISO -> "!";
            case INFORMACAO -> "i";
        };
    }

    /** A palavra que descreve a linha (leitor de tela e dica). */
    public String palavra() {
        return switch (tipo) {
            case ACRESCENTADA -> "acrescentada";
            case TIRADA -> "tirada";
            case CONTEXTO -> "sem mudança";
            case TRECHO -> "trecho";
            case AVISO -> "aviso";
            case INFORMACAO -> "informação";
        };
    }
}
