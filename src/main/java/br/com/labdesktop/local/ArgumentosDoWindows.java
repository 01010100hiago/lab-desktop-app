package br.com.labdesktop.local;

import java.util.ArrayList;
import java.util.List;

/**
 * Como os argumentos de um comando chegam a um programa no WINDOWS (ARQUITETURA 7.7 e 11.4). O Windows não passa uma LISTA de argumentos: passa UMA linha de texto, e cada programa
 * a corta de novo. O Java monta essa linha (aspas só em volta do argumento que tem espaço ou tabulação, nenhuma escapada para uma aspa que já está dentro dele) e o {@code docker.exe} a
 * corta pelas regras do Microsoft C ({@code CommandLineToArgvW}). Quando uma ida e volta não devolve os mesmos argumentos, o programa recebe OUTRA coisa: um argumento com aspas
 * ({@code {{json .Labels}}} entre aspas) chega quebrado, e um argumento VAZIO some. O servidor local já tirou as aspas dos formatos do Docker; este validador é a trava que impede a volta.
 *
 * <p>{@link #linhaDoJava} reproduz a montagem do {@code ProcessImpl} do OpenJDK para executáveis ({@code .exe}), {@link #cortar} reproduz o corte da documentação da Microsoft
 * ("Parsing C command-line arguments"), e {@link #validar} recusa o comando se a ida e volta falhar. Puro: nada roda.
 */
public final class ArgumentosDoWindows {

    private ArgumentosDoWindows() { }

    /** A linha que o Java entrega ao {@code CreateProcess} para os argumentos DEPOIS do primeiro (o programa). */
    public static String linhaDoJava(List<String> argumentos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < argumentos.size(); i++) {
            String a = argumentos.get(i);
            sb.append(' ');
            boolean jaComAspas = a.length() >= 2 && a.charAt(0) == '"' && a.charAt(a.length() - 1) == '"';
            boolean precisa = !jaComAspas && (a.indexOf(' ') >= 0 || a.indexOf('\t') >= 0);
            if (precisa) {
                sb.append('"').append(a);
                if (a.endsWith("\\")) {
                    sb.append('\\'); // o Java dobra a barra final para ela não escapar a aspa de fechar
                }
                sb.append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    /** Corta uma linha em argumentos pelas regras do Microsoft C (sem o argumento 0, o programa). */
    public static List<String> cortar(String linha) {
        List<String> saida = new ArrayList<>();
        int i = 0;
        int n = linha.length();
        while (true) {
            while (i < n && (linha.charAt(i) == ' ' || linha.charAt(i) == '\t')) {
                i++;
            }
            if (i >= n) {
                return saida;
            }
            StringBuilder arg = new StringBuilder();
            boolean entreAspas = false;
            while (i < n && (entreAspas || (linha.charAt(i) != ' ' && linha.charAt(i) != '\t'))) {
                int barras = 0;
                while (i < n && linha.charAt(i) == '\\') {
                    barras++;
                    i++;
                }
                if (i < n && linha.charAt(i) == '"') {
                    arg.append("\\".repeat(barras / 2));
                    if (barras % 2 == 1) {
                        arg.append('"'); // número ímpar de barras: a aspa é literal
                    } else if (entreAspas && i + 1 < n && linha.charAt(i + 1) == '"') {
                        arg.append('"'); // duas aspas dentro de aspas: uma aspa literal
                        i++;
                    } else {
                        entreAspas = !entreAspas;
                    }
                    i++;
                } else {
                    arg.append("\\".repeat(barras)); // barras que não encostam numa aspa valem como são
                    if (i < n && (entreAspas || (linha.charAt(i) != ' ' && linha.charAt(i) != '\t'))) {
                        arg.append(linha.charAt(i));
                        i++;
                    }
                }
            }
            saida.add(arg.toString());
        }
    }

    /**
     * Confere a ida e volta de {@code comando} (o primeiro item é o programa e não entra na conta).
     *
     * @throws IllegalArgumentException o argumento que o programa receberia diferente do que o app mandou (aspas dentro, argumento vazio, caractere de controle)
     */
    public static void validar(List<String> comando) {
        for (int i = 1; i < comando.size(); i++) {
            String a = comando.get(i);
            for (int k = 0; k < a.length(); k++) {
                if (a.charAt(k) < 0x20 && a.charAt(k) != '\t') {
                    throw new IllegalArgumentException("O argumento " + i + " do comando local tem caractere de controle: no Windows ele chegaria quebrado ao programa.");
                }
            }
        }
        List<String> volta = cortar(linhaDoJava(comando));
        List<String> esperados = comando.subList(1, comando.size());
        if (!volta.equals(esperados)) {
            int onde = 0;
            while (onde < Math.min(volta.size(), esperados.size()) && volta.get(onde).equals(esperados.get(onde))) {
                onde++;
            }
            String qual = onde < esperados.size() ? esperados.get(onde) : "(depois do último)";
            throw new IllegalArgumentException("No Windows o argumento " + (onde + 1) + " do comando local (" + visivel(qual) + ") chegaria ao programa diferente do que o app mandou "
                + "(aspas dentro do argumento, ou argumento vazio): o app não roda este comando.");
        }
    }

    private static String visivel(String s) {
        String limpo = s.replace("\"", "\\\"");
        return limpo.length() > 40 ? limpo.substring(0, 40) + "…" : "\"" + limpo + "\"";
    }
}
