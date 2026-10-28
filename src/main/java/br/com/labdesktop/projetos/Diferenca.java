package br.com.labdesktop.projetos;

import java.util.ArrayList;
import java.util.List;

/**
 * A diferença entre dois textos, linha a linha, no formato que o usuário lê antes de gravar: linhas iguais com dois espaços na frente, as que saem
 * com {@code "- "} e as que entram com {@code "+ "}, só com algumas linhas de contexto em volta de cada mudança. Para arquivos pequenos (um compose);
 * acima de {@link #LIMITE_DE_LINHAS} devolve só a contagem.
 */
public final class Diferenca {

    /** Acima disto não se calcula a diferença (a conta é quadrática). */
    public static final int LIMITE_DE_LINHAS = 1500;

    private Diferenca() { }

    /** O texto da diferença, com {@code contexto} linhas iguais antes e depois de cada mudança; {@code ""} se os textos são iguais. */
    public static String legivel(String antes, String depois, int contexto) {
        List<String> a = linhas(antes);
        List<String> b = linhas(depois);
        if (a.size() > LIMITE_DE_LINHAS || b.size() > LIMITE_DE_LINHAS) {
            return "(arquivo grande demais para mostrar a diferença: " + a.size() + " linhas antes, " + b.size() + " depois)";
        }
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> tudo = new ArrayList<>();
        List<Boolean> mudou = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                tudo.add("  " + a.get(i));
                mudou.add(false);
                i++;
                j++;
            } else if (i < n && (j >= m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                tudo.add("- " + a.get(i)); // o que sai vem antes do que entra no lugar
                mudou.add(true);
                i++;
            } else {
                tudo.add("+ " + b.get(j));
                mudou.add(true);
                j++;
            }
        }
        if (mudou.stream().noneMatch(x -> x)) {
            return "";
        }
        boolean[] mostrar = new boolean[tudo.size()];
        for (int k = 0; k < tudo.size(); k++) {
            if (mudou.get(k)) {
                for (int c = Math.max(0, k - contexto); c <= Math.min(tudo.size() - 1, k + contexto); c++) {
                    mostrar[c] = true;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean pulou = false;
        for (int k = 0; k < tudo.size(); k++) {
            if (mostrar[k]) {
                if (pulou && sb.length() > 0) {
                    sb.append("  ...\n");
                }
                pulou = false;
                sb.append(tudo.get(k)).append('\n');
            } else {
                pulou = true;
            }
        }
        return sb.toString();
    }

    private static List<String> linhas(String texto) {
        if (texto == null || texto.isEmpty()) {
            return List.of();
        }
        List<String> l = new ArrayList<>(List.of(texto.split("\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) {
            l.remove(l.size() - 1);
        }
        return l;
    }
}
