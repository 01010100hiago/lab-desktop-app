package br.com.labdesktop.terminal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * O limite de terminais abertos por servidor: 8 (o OpenSSH padrão aceita {@code MaxSessions=10} canais de sessão, e o app ainda
 * precisa de alguns para SFTP e comandos). Vale para o servidor inteiro: as abas da aba "Terminal" e os terminais das sessões do
 * Claude dos projetos dividem o mesmo contador.
 */
public final class LimiteDeAbas {

    public static final int MAXIMO = 8;

    private final AtomicInteger emUso = new AtomicInteger();

    /** Reserva uma vaga. {@code false} (sem reservar nada) se as {@value #MAXIMO} já estão em uso. */
    public boolean reservar() {
        while (true) {
            int n = emUso.get();
            if (n >= MAXIMO) {
                return false;
            }
            if (emUso.compareAndSet(n, n + 1)) {
                return true;
            }
        }
    }

    /** Devolve uma vaga (ao fechar a aba). Nunca fica abaixo de zero. */
    public void liberar() {
        emUso.updateAndGet(n -> Math.max(0, n - 1));
    }

    public int emUso() {
        return emUso.get();
    }

    /** O que a tela diz quando o app mesmo recusa a nona aba. */
    public static String mensagemDoLimite() {
        return "Este servidor já tem " + MAXIMO + " terminais abertos, o máximo. Feche um terminal para abrir outro.";
    }
}
