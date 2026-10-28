package br.com.labdesktop.processos;

import java.util.regex.Pattern;

/**
 * O comando que o USUÁRIO copia e roda sozinho no Terminal quando o app não tem permissão. NUNCA é executado pelo app (o app não usa {@code sudo}, não
 * digita senha). Como o usuário pode colar horas depois da leitura, o comando é GUARDADO: confere o nome do programa antes de enviar o sinal (se o número
 * foi reaproveitado, o {@code grep} falha e o {@code kill} nem roda), e vem com uma linha para CONFERIR primeiro ({@code ps -o pid,user,lstart,comm}).
 *
 * <p>O nome que entra no comando passa por {@link #NOME}: letras, números, ponto, traço e sublinhado (sem espaço, aspas, {@code $}, {@code ;}, nada que
 * o shell interprete). Um nome fora disso NÃO vira comando: o app diz o motivo e o usuário escolhe o que fazer.
 *
 * @param conferir   a linha que só LÊ, para o usuário ver quem é o processo antes ({@code ""} se não há comando)
 * @param executar   o comando guardado ({@code ""} se não há comando)
 * @param motivoSem  quando não há comando: o porquê, numa frase para a tela ({@code ""} se há)
 */
public record ComandoParaODono(String conferir, String executar, String motivoSem) {

    /** Só estes caracteres entram no comando; precisa começar com letra ou número (nunca vira opção do programa). */
    public static final Pattern NOME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern NOME_WINDOWS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.exe$", Pattern.CASE_INSENSITIVE);

    /** O {@code comm} do Linux tem no máximo 15 letras. */
    static final int COMM_DO_LINUX = 15;

    public ComandoParaODono {
        conferir = conferir == null ? "" : conferir;
        executar = executar == null ? "" : executar;
        motivoSem = motivoSem == null ? "" : motivoSem;
    }

    public static ComandoParaODono nenhum(String motivo) {
        return new ComandoParaODono("", "", motivo);
    }

    public boolean temComando() {
        return !executar.isEmpty();
    }

    /** O texto que a tela mostra e o botão Copiar copia: a conferência e o comando, cada um na sua linha ({@code ""} se não há comando). */
    public String texto() {
        return temComando() ? conferir + "\n" + executar : "";
    }

    /** Um processo: confere o nome e só então envia o sinal. */
    public static ComandoParaODono doProcesso(Integer pid, String nome, Sinal sinal, AmbienteDeProcessos.Tipo tipo) {
        if (!ValidadorDePid.valido(pid)) {
            return nenhum("O número do processo não é um que o app possa usar num comando com segurança.");
        }
        int n = pid;
        String curto = ProcessoAlvo.nomeCurto(nome);
        if (tipo == AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS) {
            if (!NOME_WINDOWS.matcher(curto).matches()) {
                return nenhum("O app não monta o comando porque o nome do programa (" + paraTela(curto) + ") tem caracteres que o terminal poderia interpretar. "
                    + "Confira o programa no Gerenciador de Tarefas e encerre por lá.");
            }
            return new ComandoParaODono("tasklist /FI \"PID eq " + n + "\"",
                "taskkill /PID " + n + " /FI \"PID eq " + n + "\" /FI \"IMAGENAME eq " + curto + "\"" + (sinal == Sinal.KILL ? " /F" : ""), "");
        }
        if (!NOME.matcher(curto).matches()) {
            return nenhum("O app não monta o comando porque o nome do programa (" + paraTela(curto) + ") tem caracteres que o shell poderia interpretar "
                + "(espaço, aspas, $ ou ;). Confira o processo no Terminal e encerre por lá.");
        }
        boolean cortado = curto.length() > COMM_DO_LINUX;
        String comparavel = cortado ? curto.substring(0, COMM_DO_LINUX) : curto;
        String regex = "(^|/)" + comparavel.replace(".", "\\.") + (cortado ? "" : "$");
        return new ComandoParaODono("ps -o pid,user,lstart,comm -p " + n,
            "ps -o comm= -p " + n + " | grep -Eq '" + regex + "' && sudo kill -" + sinal.nome() + " " + n, "");
    }

    /** Um container: confere que o nome é exatamente aquele e só então age. */
    public static ComandoParaODono doContainer(Acao acao, String container) {
        if (container == null || !ComandosDeProcessos.CONTAINER.matcher(container).matches()) {
            return nenhum("O nome do container não é um que o Docker aceite; o app não monta o comando.");
        }
        String verbo = acao == Acao.REINICIAR ? "restart" : "stop";
        return new ComandoParaODono("sudo docker ps -a --filter \"name=^" + container + "$\"",
            "[ \"$(sudo docker inspect -f '{{.Name}}' " + container + ")\" = \"/" + container + "\" ] && sudo docker " + verbo + " " + container, "");
    }

    /** O nome para a frase: sem caractere de controle (nem os que invertem o texto) e curto. */
    private static String paraTela(String nome) {
        String t = nome == null ? "" : nome.strip();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length() && sb.length() < 40; i++) {
            char c = t.charAt(i);
            boolean estranho = c < 0x20 || c == 0x7F || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.CONTROL;
            sb.append(estranho ? '?' : c);
        }
        return sb.toString();
    }
}
