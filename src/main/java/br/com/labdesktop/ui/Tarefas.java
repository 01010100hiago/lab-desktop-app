package br.com.labdesktop.ui;

import br.com.labdesktop.util.Fundo;
import java.awt.Cursor;
import java.awt.Window;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Rede, disco e KDF fora da EDT (ARQUITETURA 1.3, princípio 2 e 6.6): o trabalho roda numa thread virtual e o
 * resultado (ou o erro) volta à EDT. A tela só desenha o que chega aqui.
 */
public final class Tarefas {

    private static final EsperaLonga ESPERA = new EsperaLonga();
    private static javax.swing.Timer vigia;
    private static boolean cursorLigado;

    private Tarefas() { }

    /**
     * O cursor de espera nas janelas enquanto alguma ação do usuário demora mais de 300 ms (uma que responde logo não faz o cursor piscar). Na EDT. Os botões
     * que têm o próprio cursor (a mãozinha) o mantêm; o resto da janela mostra que o app está trabalhando.
     */
    private static synchronized void acompanharEspera() {
        ESPERA.comecou(Instant.now());
        if (vigia == null) {
            vigia = new javax.swing.Timer(100, e -> conferirOCursor());
        }
        vigia.start();
    }

    private static synchronized void esperaTerminou() {
        ESPERA.terminou();
        conferirOCursor();
        if (!ESPERA.haAcaoRodando() && vigia != null) {
            vigia.stop();
        }
    }

    private static void conferirOCursor() {
        boolean querer = ESPERA.cursorDeEsperaAgora(Instant.now());
        if (querer == cursorLigado) {
            return;
        }
        cursorLigado = querer;
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                w.setCursor(querer ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
            }
        }
    }

    /**
     * Roda {@code trabalho} numa thread virtual; depois chama {@code aoConcluir} OU {@code aoFalhar} na EDT.
     * Quem chama é a EDT (um clique), então a interface nunca espera pela rede.
     */
    public static <T> void rodar(Callable<T> trabalho, Consumer<T> aoConcluir, Consumer<Throwable> aoFalhar) {
        acompanharEspera();
        Fundo.emFundoComResultado(trabalho).whenComplete((resultado, falha) -> Fundo.naEdt(() -> {
            esperaTerminou();
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
