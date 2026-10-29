package br.com.labdesktop.git;

import java.util.List;

/**
 * O diff de UM arquivo, já em linhas classificadas.
 *
 * @param linhas      as linhas (até o teto de linhas)
 * @param binario     o git diz que o arquivo é binário (não há linhas para mostrar)
 * @param cortado     passou do limite de linhas ou da saída: o resto não está aqui ("diff cortado")
 * @param vazio       nenhuma mudança de conteúdo (arquivo novo e vazio, ou só mudou o modo)
 * @param antigo      o caminho de antes, se foi renomeado; senão {@code null}
 * @param semFimDeLinha algum lado termina sem quebra de linha
 * @param acrescentadas quantas linhas {@code +} há nas linhas guardadas
 * @param tiradas       quantas linhas {@code -} há nas linhas guardadas
 * @param outraCodificacao o texto não era UTF-8 válido e foi lido como Latin-1
 */
public record DiffDoArquivo(List<LinhaDeDiff> linhas, boolean binario, boolean cortado, boolean vazio, String antigo, boolean semFimDeLinha,
                            int acrescentadas, int tiradas, boolean outraCodificacao) {

    public DiffDoArquivo {
        linhas = List.copyOf(linhas);
    }

    /** O diff como um texto para copiar: cada linha com o seu símbolo. */
    public String comoTexto() {
        StringBuilder sb = new StringBuilder();
        for (LinhaDeDiff l : linhas) {
            if (l.tipo() == LinhaDeDiff.Tipo.TRECHO) {
                sb.append("@@ ").append(l.texto()).append('\n');
            } else if (l.tipo() == LinhaDeDiff.Tipo.INFORMACAO) {
                sb.append("# ").append(l.texto()).append('\n');
            } else if (l.tipo() == LinhaDeDiff.Tipo.AVISO) {
                sb.append("\\ ").append(l.texto()).append('\n');
            } else {
                sb.append(l.simbolo()).append(l.texto()).append('\n');
            }
        }
        if (cortado) {
            sb.append("# diff cortado\n");
        }
        return sb.toString();
    }
}
