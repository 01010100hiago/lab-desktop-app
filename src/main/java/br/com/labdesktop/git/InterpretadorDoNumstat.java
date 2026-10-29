package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a saída de {@code git show --numstat -z}: cada arquivo é {@code acrescentadas TAB tiradas TAB caminho NUL}; num arquivo renomeado o caminho vem vazio e os dois nomes ({@code antigo NUL novo NUL})
 * vêm em seguida; em arquivo binário os números são {@code -}. Com {@code -z} o nome não leva aspas nem escape, então acento, espaço e até quebra de linha chegam exatos.
 */
public final class InterpretadorDoNumstat {

    private static final Pattern LINHA = Pattern.compile("^(-|\\d+)\t(-|\\d+)\t(.*)$", Pattern.DOTALL);

    private InterpretadorDoNumstat() { }

    public static List<ArquivoDoCommit> interpretar(String saida) {
        List<ArquivoDoCommit> r = new ArrayList<>();
        if (saida == null || saida.isEmpty()) {
            return r;
        }
        String[] t = saida.split("\0", -1);
        for (int i = 0; i < t.length; i++) {
            if (t[i].isEmpty()) {
                continue;
            }
            Matcher m = LINHA.matcher(t[i]);
            if (!m.matches()) {
                continue;
            }
            int mais = m.group(1).equals("-") ? -1 : Integer.parseInt(m.group(1));
            int menos = m.group(2).equals("-") ? -1 : Integer.parseInt(m.group(2));
            String caminho = m.group(3);
            String antigo = null;
            if (caminho.isEmpty()) {
                if (i + 2 >= t.length || t[i + 1].isEmpty() || t[i + 2].isEmpty()) {
                    break; // renomeado cortado no meio: ignora
                }
                antigo = t[i + 1];
                caminho = t[i + 2];
                i += 2;
            }
            r.add(new ArquivoDoCommit(TextoSeguro.limpar(caminho), antigo == null ? null : TextoSeguro.limpar(antigo), mais, menos));
        }
        return r;
    }
}
