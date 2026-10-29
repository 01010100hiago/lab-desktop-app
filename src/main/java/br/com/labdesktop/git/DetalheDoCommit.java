package br.com.labdesktop.git;

import java.util.List;

/**
 * O cabeçalho e a lista de arquivos de um commit.
 *
 * @param commit     os dados do commit (o número de arquivos vale a lista)
 * @param mensagem   a mensagem inteira
 * @param arquivos   os arquivos que mudaram (até o teto)
 * @param listaCortada há mais arquivos do que os listados (o teto da saída)
 */
public record DetalheDoCommit(Commit commit, String mensagem, List<ArquivoDoCommit> arquivos, boolean listaCortada) {

    public DetalheDoCommit {
        arquivos = List.copyOf(arquivos);
        mensagem = mensagem == null ? "" : mensagem;
    }

    public int totalAcrescentadas() {
        return arquivos.stream().mapToInt(a -> Math.max(0, a.acrescentadas())).sum();
    }

    public int totalTiradas() {
        return arquivos.stream().mapToInt(a -> Math.max(0, a.tiradas())).sum();
    }
}
