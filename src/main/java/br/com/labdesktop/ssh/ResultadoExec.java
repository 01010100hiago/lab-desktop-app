package br.com.labdesktop.ssh;

import java.nio.charset.StandardCharsets;

/**
 * Saída de um comando remoto. NÃO é {@code record}: o stdout da configuração do 2FA carrega o segredo, e
 * o {@code toString} de um record o imprimiria. Aqui o {@code toString} só diz o status e os tamanhos.
 */
public final class ResultadoExec {

    private final int status;
    private final byte[] stdout;
    private final byte[] stderr;
    private final boolean truncado;

    public ResultadoExec(int status, byte[] stdout, byte[] stderr, boolean truncado) {
        this.status = status;
        this.stdout = stdout;
        this.stderr = stderr;
        this.truncado = truncado;
    }

    /** Código de saída do comando; {@code -1} se o servidor não informou. */
    public int status() {
        return status;
    }

    public String stdout() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderr() {
        return new String(stderr, StandardCharsets.UTF_8);
    }

    /** {@code true} se a saída passou do teto e foi cortada. */
    public boolean truncado() {
        return truncado;
    }

    @Override
    public String toString() {
        return "ResultadoExec[status=" + status + ", stdout=" + stdout.length + " bytes, stderr=" + stderr.length
            + " bytes]";
    }
}
