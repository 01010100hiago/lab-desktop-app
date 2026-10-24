package br.com.labdesktop.ui;

import br.com.labdesktop.util.Fundo;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Rede, disco e KDF fora da EDT (ARQUITETURA 1.3, princípio 2 e 6.6): o trabalho roda numa thread virtual e o
 * resultado (ou o erro) volta à EDT. A tela só desenha o que chega aqui.
 */
public final class Tarefas {

    private Tarefas() { }

    /**
     * Roda {@code trabalho} numa thread virtual; depois chama {@code aoConcluir} OU {@code aoFalhar} na EDT.
     * Quem chama é a EDT (um clique), então a interface nunca espera pela rede.
     */
    public static <T> void rodar(Callable<T> trabalho, Consumer<T> aoConcluir, Consumer<Throwable> aoFalhar) {
        Fundo.emFundoComResultado(trabalho).whenComplete((resultado, falha) -> Fundo.naEdt(() -> {
            if (falha == null) {
                aoConcluir.accept(resultado);
            } else {
                aoFalhar.accept(falha instanceof CompletionException && falha.getCause() != null ? falha.getCause() : falha);
            }
        }));
    }

    /** Variante sem resultado. */
    public static void rodar(ComTrabalho trabalho, Runnable aoConcluir, Consumer<Throwable> aoFalhar) {
        rodar(() -> {
            trabalho.executar();
            return Boolean.TRUE;
        }, ok -> aoConcluir.run(), aoFalhar);
    }

    /** Um trabalho que pode lançar. */
    @FunctionalInterface
    public interface ComTrabalho {
        void executar() throws Exception;
    }
}
