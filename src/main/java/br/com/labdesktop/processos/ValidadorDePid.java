package br.com.labdesktop.processos;

/**
 * O número de um processo só entra numa linha de comando depois de passar por aqui: um INTEIRO entre 2 e o maior número que o Linux de 64 bits dá
 * ({@code pid_max} = 4194304). O 1 (o primeiro processo do sistema) e o 0 nunca valem; negativo nunca vale (um {@code kill -1} mata tudo o que o
 * usuário tem).
 */
public final class ValidadorDePid {

    /** O maior número de processo do Linux de 64 bits. */
    public static final int MAIOR_PID = 4_194_304;

    private ValidadorDePid() { }

    /** {@code true} se o número pode entrar numa linha de comando. */
    public static boolean valido(Integer pid) {
        return pid != null && pid >= 2 && pid <= MAIOR_PID;
    }

    /** O número, ou {@link IllegalArgumentException} se ele não pode entrar numa linha de comando. */
    public static int exigir(Integer pid) {
        if (!valido(pid)) {
            throw new IllegalArgumentException("Número de processo fora do que o app aceita.");
        }
        return pid;
    }
}
