package br.com.labdesktop.processos;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TODOS os comandos para encerrar e reiniciar, num lugar só. Cada um é FIXO: o único texto que varia é o número do processo (um
 * inteiro que passou por {@link ValidadorDePid}), o nome de um container ({@link #CONTAINER}) e o nome de uma unidade do systemd ({@link #UNIDADE}), e
 * os dois nomes passam por regex curta antes de entrar. Nada que o servidor devolva (nome, dono, hora) vai para uma linha de comando: o que é
 * texto do servidor viaja pelo stdin.
 *
 * <p>Só o {@link #enviarSinal} usa o {@code sh -c}, porque confere a identidade e envia o sinal NO MESMO comando (sem espaço para o número ser
 * reaproveitado entre a conferência e o {@code kill}). Nenhum comando que o app EXECUTA usa {@code sudo}: o app nunca eleva privilégio nem digita senha (o comando do usuário, que ele só mostra, é {@link ComandoParaODono}).
 */
public final class ComandosDeProcessos {

    /** Nome de container válido para o Docker. */
    public static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
    /** Unidade do systemd que o app aceita reiniciar: nome simples terminado em {@code .service}. */
    public static final Pattern UNIDADE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.@:-]{0,99}\\.service$");

    /** O que o comando de sinal imprime em cada caso. */
    public static final String ENVIADO = "enviado";
    public static final String MUDOU = "mudou";
    public static final String JA_SAIU = "ja-saiu";
    public static final String SEM_PERMISSAO = "sem-permissao";
    public static final String SEM_CONFERENCIA = "sem-conferencia";

    private ComandosDeProcessos() { }

    // ---- leituras (nada muda) ----

    /** Os argumentos de {@code ps -o pid=,uid=,lstart=,comm= -p <pid>} (com {@code LC_ALL=C} para a data sair sempre igual). */
    public static List<String> lerIdentidade(int pid) {
        return List.of("ps", "-o", "pid=,uid=,lstart=,comm=", "-p", String.valueOf(ValidadorDePid.exigir(pid)));
    }

    /** O ambiente de {@link #lerIdentidade}: a data em inglês, sempre igual. */
    public static Map<String, String> ambienteDaLeitura() {
        return Map.of("LC_ALL", "C");
    }

    /** O grupo de controle do processo (mostra se ele pertence a um serviço do systemd do usuário). */
    public static List<String> lerGrupoDeControle(int pid) {
        return List.of("cat", "/proc/" + ValidadorDePid.exigir(pid) + "/cgroup");
    }

    // ---- escritas (só pelo clique do usuário) ----

    /** O que o comando de sinal escreve antes de cada resposta: o resto da saída (banner, {@code .bashrc}) é ruído e é ignorado. */
    public static final String MARCA = "labdesktop:";

    /**
     * Confere a identidade e envia o sinal, num comando só. Lê UMA linha do stdin (a identidade esperada, {@link IdentidadeDoProcesso#linha()}) e
     * imprime, numa linha própria com a {@value #MARCA}, {@value #ENVIADO}, {@value #MUDOU} (o número agora é de outro processo; nada enviado),
     * {@value #JA_SAIU}, {@value #SEM_PERMISSAO} ou {@value #SEM_CONFERENCIA} (o {@code ps} do servidor não sabe dizer a hora de início, ou o processo é de
     * outro usuário e o servidor o esconde: nada enviado, e sem prova de que saiu).
     */
    public static String enviarSinal(int pid, Sinal sinal) {
        int n = ValidadorDePid.exigir(pid);
        return "sh -c '"
            + "IFS= read -r e; "
            + "a=$(LC_ALL=C ps -o pid=,uid=,lstart=,comm= -p " + n + " 2>/dev/null | tr -s \" \" | sed \"s/^ //; s/ $//\"); "
            + "if [ -z \"$a\" ]; then "
            + "d=$(echo \"$e\" | cut -d\" \" -f2); "
            + "if [ \"$d\" != \"$(id -u)\" ] || [ -e /proc/" + n + " ] || kill -0 " + n + " 2>/dev/null; then echo " + MARCA + SEM_CONFERENCIA + "; else echo " + MARCA + JA_SAIU + "; fi; "
            + "elif [ \"$a\" != \"$e\" ]; then echo " + MARCA + MUDOU + "; "
            + "elif kill -" + sinal.nome() + " " + n + " 2>/dev/null; then echo " + MARCA + ENVIADO + "; "
            + "else echo " + MARCA + SEM_PERMISSAO + "; fi'";
    }

    /** A resposta do comando de sinal: a ÚLTIMA linha com a {@link #MARCA}; vazio se nenhuma (o app não sabe o que houve). */
    public static java.util.Optional<String> respostaDoSinal(String saida) {
        if (saida == null) {
            return java.util.Optional.empty();
        }
        String ultima = null;
        for (String linha : saida.split("\n")) {
            String t = linha.strip();
            if (t.startsWith(MARCA)) {
                ultima = t.substring(MARCA.length());
            }
        }
        return java.util.Optional.ofNullable(ultima);
    }

    /**
     * {@code docker stop <container>}: o Docker pede, espera o tempo que ele mesmo escolhe (10 s, a menos que o container peça outro, e há containers que
     * pedem mais) e, se preciso, mata. O app NÃO passa {@code -t}: não corta a espera do container, só a acompanha.
     */
    public static List<String> pararContainer(String container) {
        return List.of("docker", "stop", exigirContainer(container));
    }

    /** {@code docker restart <container>}, com a espera do próprio container. */
    public static List<String> reiniciarContainer(String container) {
        return List.of("docker", "restart", exigirContainer(container));
    }

    /** {@code systemctl --user restart -- <unidade>} (o {@code --} garante que o nome nunca vira opção); o {@code XDG_RUNTIME_DIR} vem de {@link #ambienteDoServicoDoUsuario}. */
    public static List<String> reiniciarServicoDoUsuario(String unidade) {
        if (unidade == null || !UNIDADE.matcher(unidade).matches()) {
            throw new IllegalArgumentException("Nome de serviço fora do que o app aceita.");
        }
        return List.of("systemctl", "--user", "restart", "--", unidade);
    }

    /** O ambiente que o {@code systemctl --user} precisa por SSH: onde fica o barramento do usuário. */
    public static Map<String, String> ambienteDoServicoDoUsuario(String uid) {
        if (uid == null || !uid.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("Usuário fora do formato.");
        }
        return Map.of("XDG_RUNTIME_DIR", "/run/user/" + uid);
    }

    /**
     * Windows: {@code taskkill /PID <n>}; {@code forcar} acrescenta {@code /F}. SEM {@code /T}: o {@code /T} também encerra os programas que o
     * processo abriu, e o app não faz isso sem o usuário saber (por isso nunca o usa; os filhos podem continuar, e a tela não promete o contrário).
     */
    public static List<String> taskkill(int pid, boolean forcar) {
        int n = ValidadorDePid.exigir(pid);
        return forcar ? List.of("taskkill", "/PID", String.valueOf(n), "/F") : List.of("taskkill", "/PID", String.valueOf(n));
    }

    /** O comando que o USUÁRIO roda sozinho quando o app não tem permissão (nunca é executado pelo app): guardado pelo nome do programa. */
    public static ComandoParaODono paraODono(Integer pid, String nome, Sinal sinal, AmbienteDeProcessos.Tipo tipo) {
        return ComandoParaODono.doProcesso(pid, nome, sinal, tipo);
    }

    /** O comando para o usuário rodar quando é um container e o usuário não alcança o Docker: guardado pelo nome do container. */
    public static ComandoParaODono paraODonoDoContainer(Acao acao, String container) {
        return ComandoParaODono.doContainer(acao, container);
    }

    private static String exigirContainer(String container) {
        if (container == null || !CONTAINER.matcher(container).matches()) {
            throw new IllegalArgumentException("Nome de container fora do que o Docker aceita.");
        }
        return container;
    }

    /** A unidade do usuário a que um processo pertence, lida do {@code /proc/<pid>/cgroup}; vazio se não é de um serviço do usuário. */
    public static java.util.Optional<String> unidadeDoUsuario(String cgroup, String uid) {
        if (cgroup == null || uid == null) {
            return java.util.Optional.empty();
        }
        for (String linha : cgroup.split("\n")) {
            int i = linha.indexOf("user@" + uid + ".service/");
            if (i < 0) {
                continue;
            }
            String resto = linha.substring(i + ("user@" + uid + ".service/").length());
            String ultima = null;
            for (String parte : resto.split("/")) {
                if (parte.endsWith(".service")) {
                    ultima = parte;
                }
            }
            if (ultima != null && UNIDADE.matcher(ultima).matches()) {
                return java.util.Optional.of(ultima);
            }
        }
        return java.util.Optional.empty();
    }
}
