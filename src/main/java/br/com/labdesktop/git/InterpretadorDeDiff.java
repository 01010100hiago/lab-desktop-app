package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê o diff unificado de UM arquivo ({@code git show} ou {@code git diff} com {@code --no-color}). Uma máquina de estados simples: antes do primeiro {@code @@} as linhas são do cabeçalho do git
 * ({@code diff --git}, {@code index}, {@code ---}, {@code +++}, {@code rename from}); dentro de um trecho, a primeira letra da linha manda ({@code +}, {@code -}, espaço, {@code \}), de modo que uma linha
 * tirada que começa com {@code -- } nunca é confundida com cabeçalho.
 *
 * <p>Limites: no máximo {@link #LINHAS_POR_ARQUIVO} linhas guardadas (o resto vira "diff cortado"); arquivo binário ({@code Binary files ... differ}, {@code GIT binary patch}) não tem linhas;
 * texto que não é UTF-8 é lido como Latin-1 e avisado (ver {@link DecodificadorDeTexto}); os controles do texto são limpos para a tela.
 */
public final class InterpretadorDeDiff {

    /** O teto de linhas guardadas de um arquivo. */
    public static final int LINHAS_POR_ARQUIVO = 2000;

    /** Uma linha maior que isto é cortada (um arquivo minificado numa linha só não trava a tela). */
    static final int TETO_DA_LINHA = 2000;

    private InterpretadorDeDiff() { }

    /**
     * @param bytes      a saída do git
     * @param saidaCortada o executor cortou a saída (passou do teto de bytes)
     */
    public static DiffDoArquivo interpretar(byte[] bytes, boolean saidaCortada) {
        DecodificadorDeTexto.Texto t = DecodificadorDeTexto.decodificar(bytes);
        return interpretar(t.texto(), saidaCortada, t.outraCodificacao());
    }

    public static DiffDoArquivo interpretar(String texto, boolean saidaCortada) {
        return interpretar(texto, saidaCortada, false);
    }

    static DiffDoArquivo interpretar(String texto, boolean saidaCortada, boolean outraCodificacao) {
        List<LinhaDeDiff> linhas = new ArrayList<>();
        boolean binario = false;
        boolean cortado = saidaCortada;
        boolean semFim = false;
        boolean emTrecho = false;
        String antigo = null;
        int mais = 0;
        int menos = 0;
        boolean algumTrecho = false;
        String[] brutas = texto == null ? new String[0] : texto.split("\n", -1);
        int n = brutas.length;
        if (n > 0 && brutas[n - 1].isEmpty()) {
            n--; // o último \n do texto não é uma linha
        }
        for (int i = 0; i < n; i++) {
            String l = brutas[i];
            if (l.endsWith("\r") && !emTrecho) {
                l = l.substring(0, l.length() - 1);
            }
            if (!emTrecho) {
                if (l.startsWith("@@")) {
                    emTrecho = true;
                    algumTrecho = true;
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.TRECHO, limpar(trecho(l))));
                } else if (l.startsWith("Binary files ") || l.startsWith("GIT binary patch")) {
                    binario = true;
                } else if (l.startsWith("rename from ")) {
                    antigo = TextoSeguro.limpar(l.substring("rename from ".length()));
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "renomeado de " + limpar(antigo)));
                } else if (l.startsWith("rename to ")) {
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "renomeado para " + limpar(l.substring("rename to ".length()))));
                } else if (l.startsWith("new file mode")) {
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "arquivo novo"));
                } else if (l.startsWith("deleted file mode")) {
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "arquivo apagado"));
                } else if (l.startsWith("old mode ")) {
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "permissão de " + limpar(l.substring("old mode ".length()))));
                } else if (l.startsWith("new mode ")) {
                    linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "para " + limpar(l.substring("new mode ".length()))));
                }
                // diff --git, index, similarity, --- e +++: o caminho já está no cabeçalho da tela
                continue;
            }
            if (l.startsWith("diff --git ")) { // outro arquivo na mesma saída (não esperado: o app pede um por vez): o primeiro vale
                break;
            }
            if (linhas.size() >= LINHAS_POR_ARQUIVO) {
                cortado = true;
                break;
            }
            if (l.startsWith("@@")) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.TRECHO, limpar(trecho(l))));
            } else if (l.startsWith("+")) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.ACRESCENTADA, limpar(l.substring(1))));
                mais++;
            } else if (l.startsWith("-")) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.TIRADA, limpar(l.substring(1))));
                menos++;
            } else if (l.startsWith("\\")) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.AVISO, "sem fim de linha no final do arquivo"));
                semFim = true;
            } else if (l.startsWith(" ")) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.CONTEXTO, limpar(l.substring(1))));
            } else if (l.isEmpty()) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.CONTEXTO, "")); // linha de contexto vazia sem o espaço (alguns git cortam o espaço do fim)
            } else {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.CONTEXTO, limpar(l)));
            }
        }
        boolean vazio = !binario && !algumTrecho;
        return new DiffDoArquivo(linhas, binario, cortado, vazio, antigo, semFim, mais, menos, outraCodificacao);
    }

    /** {@code @@ -1,3 +1,4 @@ função()} sem os {@code @@} da frente (a tela põe o símbolo). */
    private static String trecho(String l) {
        return l.length() > 2 ? l.substring(2).strip() : "";
    }

    private static String limpar(String s) {
        String c = TextoSeguro.limpar(s.replace("\r", "")).replace("\t", "    ");
        return c.length() > TETO_DA_LINHA ? c.substring(0, TETO_DA_LINHA) + "…" : c;
    }
}
