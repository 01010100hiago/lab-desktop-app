package br.com.labdesktop;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Espera por condição com prazo, para testes de integração com threads (sem {@code Thread.sleep} solto). */
public final class Espera {

    private Espera() { }

    /** Falha com {@link AssertionError} se a condição não ficar verdadeira dentro do prazo. */
    public static void ate(Duration prazo, String descricao, BooleanSupplier condicao) {
        long limite = System.nanoTime() + prazo.toNanos();
        while (System.nanoTime() < limite) {
            if (condicao.getAsBoolean()) {
                return;
            }
            dormir(15);
        }
        if (!condicao.getAsBoolean()) {
            throw new AssertionError("Não aconteceu em " + prazo.toMillis() + " ms: " + descricao);
        }
    }

    /** Com contexto na mensagem de erro (por exemplo, o estado atual). */
    public static void ate(Duration prazo, Supplier<String> descricao, BooleanSupplier condicao) {
        long limite = System.nanoTime() + prazo.toNanos();
        while (System.nanoTime() < limite) {
            if (condicao.getAsBoolean()) {
                return;
            }
            dormir(15);
        }
        if (!condicao.getAsBoolean()) {
            throw new AssertionError("Não aconteceu em " + prazo.toMillis() + " ms: " + descricao.get());
        }
    }

    /** Garante que a condição continua falsa durante todo o intervalo (prova de "não acontece"). */
    public static void naoAcontece(Duration intervalo, String descricao, BooleanSupplier condicao) {
        long limite = System.nanoTime() + intervalo.toNanos();
        while (System.nanoTime() < limite) {
            if (condicao.getAsBoolean()) {
                throw new AssertionError("Aconteceu, e não devia: " + descricao);
            }
            dormir(15);
        }
    }

    public static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrompido", e);
        }
    }
}
