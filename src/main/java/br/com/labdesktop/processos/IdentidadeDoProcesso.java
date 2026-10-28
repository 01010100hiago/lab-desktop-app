package br.com.labdesktop.processos;

import java.util.Optional;

/**
 * Quem é um processo de verdade, lido do sistema: o número, o dono (o número do usuário), a HORA EM QUE COMEÇOU e o nome. O número sozinho não basta:
 * o sistema reaproveita números, e um {@code kill} no número de um processo que já saiu mata o próximo que ganhou o mesmo número. Por isso o app lê
 * esta identidade ao abrir a confirmação, mostra ao usuário e, na hora de enviar o sinal, confere de novo (no mesmo comando que envia): se mudou
 * qualquer parte, nada é enviado.
 *
 * <p>A linha canônica ({@link #linha()}) é o que o servidor compara: {@code pid uid dia-da-semana mês dia hora ano nome}, com um espaço entre os
 * pedaços e sem espaço nas pontas.
 *
 * @param uid    o dono: o número do usuário (servidor) ou o nome (este computador)
 * @param inicio a hora de início como o {@code ps -o lstart} a escreve ({@code Tue Oct 28 12:00:00 2026}), sempre com UM espaço entre os pedaços; em Este
 *               computador, o instante de início lido do sistema
 */
public record IdentidadeDoProcesso(int pid, String uid, String inicio, String nome) {

    private static final java.util.regex.Pattern INICIO_DO_PS = java.util.regex.Pattern.compile("[A-Za-z]{3} [A-Za-z]{3} [0-9]{1,2} [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4}");

    public IdentidadeDoProcesso {
        if (uid == null || uid.isBlank() || uid.length() > 64 || semControle(uid)) {
            throw new IllegalArgumentException("Dono do processo fora do formato.");
        }
        if (inicio == null || inicio.isBlank() || inicio.length() > 64 || semControle(inicio)) {
            throw new IllegalArgumentException("Hora de início fora do formato.");
        }
        if (nome == null || nome.isBlank() || nome.length() > 512 || semControle(nome)) {
            throw new IllegalArgumentException("Nome do processo fora do formato.");
        }
    }

    private static boolean semControle(String t) {
        return t.chars().anyMatch(c -> c < 0x20 || c == 0x7F);
    }

    /** A linha que o servidor compara com a que o {@code ps} escreve agora. */
    public String linha() {
        return pid + " " + uid + " " + inicio + " " + nome;
    }

    /**
     * Lê a saída de {@code ps -o pid=,uid=,lstart=,comm= -p <pid>}.
     *
     * @return vazio se o processo não existe (saída vazia) ou se a saída não tem a forma esperada
     */
    public static Optional<IdentidadeDoProcesso> deSaidaDoPs(String saida, int pidEsperado) {
        if (saida == null) {
            return Optional.empty();
        }
        for (String bruta : saida.split("\n")) {
            String linha = normalizar(bruta);
            if (linha.isEmpty()) {
                continue;
            }
            String[] p = linha.split(" ", 8);
            if (p.length < 8) {
                return Optional.empty();
            }
            try {
                int pid = Integer.parseInt(p[0]);
                String inicio = String.join(" ", p[2], p[3], p[4], p[5], p[6]);
                if (pid != pidEsperado || !p[1].matches("[0-9]{1,10}") || !INICIO_DO_PS.matcher(inicio).matches()) {
                    return Optional.empty();
                }
                return Optional.of(new IdentidadeDoProcesso(pid, p[1], inicio, p[7]));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Junta os espaços repetidos em UM e tira os das pontas: o mesmo que o comando do servidor faz com a saída do {@code ps}. */
    static String normalizar(String t) {
        return t.replace("\r", "").replaceAll(" +", " ").strip();
    }

    /** O nome do {@code ps} (até 15 letras, ou o caminho inteiro no Mac) confere com o da lista? Só compara o começo, sem maiúsculas. */
    public static boolean nomeConfere(String nomeDaLista, String nomeLido) {
        String a = ProcessoAlvo.nomeCurto(nomeDaLista).toLowerCase(java.util.Locale.ROOT);
        String b = ProcessoAlvo.nomeCurto(nomeLido).toLowerCase(java.util.Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.startsWith(b) || b.startsWith(a);
    }
}
