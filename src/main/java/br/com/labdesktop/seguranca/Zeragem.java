package br.com.labdesktop.seguranca;

import java.util.Arrays;

/** Zeragem de segredos em memória. */
public final class Zeragem {

    private Zeragem() { }

    /** Zera o array (aceita {@code null}). */
    public static void zerar(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Zera o array (aceita {@code null}). Para o {@code char[]} do {@code JPasswordField}. */
    public static void zerar(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }

    /** Zera todos os segredos (aceita {@code null} no array e nos elementos). */
    public static void zerar(Segredo... segredos) {
        if (segredos == null) {
            return;
        }
        for (Segredo s : segredos) {
            if (s != null) {
                s.zerar();
            }
        }
    }

    /** {@code true} se todo byte é zero (e {@code true} para array vazio ou {@code null}). */
    public static boolean todoZero(byte[] bytes) {
        if (bytes == null) {
            return true;
        }
        // Percorre tudo, sem parada antecipada: o tempo não depende de onde está o primeiro byte não zero.
        int acumulado = 0;
        for (byte b : bytes) {
            acumulado |= b;
        }
        return acumulado == 0;
    }
}
