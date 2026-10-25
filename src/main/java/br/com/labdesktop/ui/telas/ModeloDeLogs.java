package br.com.labdesktop.ui.telas;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * O texto dos logs de um container, preparado para a tela (puro, sem Swing). O agente pode mandar até 8 MiB e o Docker
 * mistura sequências de cor de terminal e controles no meio do texto; a tela nunca renderiza tudo e nunca renderiza HTML:
 * aqui o texto vira uma lista de linhas LIMPAS, só as últimas N, com cada linha cortada num teto de caracteres. Depois o filtro
 * (busca incremental) escolhe as linhas e conta as ocorrências.
 */
public final class ModeloDeLogs {

    /** O maior número de linhas que a tela desenha, mesmo que o usuário peça mais (o agente já limita a 2000). */
    public static final int TETO_DE_LINHAS = 2000;
    /** O maior tamanho de UMA linha na tela: uma linha de 300 KB trava o desenho do componente de texto. */
    public static final int TETO_DE_CARACTERES_POR_LINHA = 2000;

    /** Sequências de escape do terminal ({@code ESC [ 32 m}) que o Docker repassa; sem tratar, sobrariam "[32m" no texto. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");

    /**
     * Os logs prontos para mostrar.
     *
     * @param linhas           as últimas linhas, já limpas
     * @param linhasDoOriginal quantas linhas o texto recebido tinha (para dizer "mostrando as últimas N")
     * @param cortado          havia mais linhas do que as mostradas
     */
    public record Preparado(List<String> linhas, int linhasDoOriginal, boolean cortado) {
        public boolean vazio() {
            return linhas.isEmpty();
        }
    }

    /**
     * O resultado do filtro.
     *
     * @param texto        as linhas que casam, uma por linha
     * @param ocorrencias  quantas vezes o termo aparece (sem diferenciar maiúscula de minúscula)
     * @param linhasQueCasam quantas linhas contêm o termo
     */
    public record Filtrado(String texto, int ocorrencias, int linhasQueCasam) { }

    private ModeloDeLogs() { }

    /**
     * @param bruto       o corpo da resposta do agente (pode ter 300 KB ou mais)
     * @param maxLinhas   as últimas quantas linhas mostrar (limitado a {@link #TETO_DE_LINHAS})
     */
    public static Preparado preparar(String bruto, int maxLinhas) {
        if (bruto == null || bruto.isBlank()) {
            return new Preparado(List.of(), 0, false);
        }
        int limite = Math.max(1, Math.min(maxLinhas, TETO_DE_LINHAS));
        String semCor = ANSI.matcher(bruto).replaceAll("");
        String[] todas = semCor.split("\\R", -1);
        int total = todas.length;
        if (total > 0 && todas[total - 1].isEmpty()) {
            total--; // o texto termina com quebra de linha: a última "linha" é só o fim
        }
        int inicio = Math.max(0, total - limite);
        List<String> linhas = new ArrayList<>(total - inicio);
        for (int i = inicio; i < total; i++) {
            String l = TextoSeguro.limpar(todas[i]);
            linhas.add(l.length() > TETO_DE_CARACTERES_POR_LINHA
                ? l.substring(0, TETO_DE_CARACTERES_POR_LINHA - 1) + "…" : l);
        }
        return new Preparado(List.copyOf(linhas), total, inicio > 0);
    }

    /** Só as linhas que contêm {@code termo} (sem diferenciar maiúscula de minúscula) e a contagem de ocorrências. Termo vazio: tudo. */
    public static Filtrado filtrar(List<String> linhas, String termo) {
        String t = termo == null ? "" : termo.strip();
        if (t.isEmpty()) {
            return new Filtrado(String.join("\n", linhas), 0, linhas.size());
        }
        String procurado = t.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int ocorrencias = 0;
        int casam = 0;
        for (String l : linhas) {
            String minuscula = l.toLowerCase(Locale.ROOT);
            int n = contar(minuscula, procurado);
            if (n > 0) {
                ocorrencias += n;
                casam++;
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(l);
            }
        }
        return new Filtrado(sb.toString(), ocorrencias, casam);
    }

    private static int contar(String texto, String procurado) {
        int n = 0;
        int i = 0;
        while ((i = texto.indexOf(procurado, i)) >= 0) {
            n++;
            i += procurado.length();
        }
        return n;
    }
}
