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
    private static synchronized EsperaLonga.Acao acompanharEspera() {
        EsperaLonga.Acao acao = ESPERA.comecou(Instant.now());
        if (vigia == null) {
            vigia = new javax.swing.Timer(100, e -> conferirOCursor());
            // uma janela que reaparece (esconder e mostrar de novo, um diálogo novo) nasce sem o cursor de espera: o vigia o põe de volta enquanto a espera dura
            vigia.setInitialDelay(100);
        }
        vigia.start();
        return acao;
    }

    private static synchronized void esperaTerminou(EsperaLonga.Acao acao) {
        ESPERA.terminou(acao);
        conferirOCursor();
        if (!ESPERA.haAcaoRodando() && vigia != null) {
            vigia.stop();
        }
    }

    private static void conferirOCursor() {
        boolean querer = ESPERA.cursorDeEsperaAgora(Instant.now());
        boolean mudou = querer != cursorLigado;
        cursorLigado = querer;
        for (Window w : Window.getWindows()) {
            boolean temEspera = w.isCursorSet() && w.getCursor().getType() == Cursor.WAIT_CURSOR;
            switch (decidir(querer, temEspera, w.isShowing(), mudou)) {
                case POR_ESPERA -> w.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)); // inclusive a janela que apareceu depois de a espera começar
                case TIRAR_ESPERA -> w.setCursor(null); // inclusive a que estava escondida quando a espera acabou: ela não reaparece com o cursor de espera
                case NADA -> { }
            }
        }
    }

    /** O que fazer com o cursor de UMA janela. */
    enum Decisao { POR_ESPERA, TIRAR_ESPERA, NADA }

    /**
     * A regra, pura: com espera, toda janela À VISTA que ainda não tem o cursor de espera o recebe (também a que apareceu depois: o vigia confere a cada 100 ms); sem espera, a que tem o
     * cursor de espera o perde, à vista ou não (quando a espera acabou ou quando a janela reaparece).
     */
    static Decisao decidir(boolean esperaAgora, boolean janelaTemEspera, boolean janelaAVista, boolean esperaMudou) {
        if (esperaAgora && !janelaTemEspera && janelaAVista) {
            return Decisao.POR_ESPERA;
        }
        if (!esperaAgora && janelaTemEspera && (esperaMudou || janelaAVista)) {
            return Decisao.TIRAR_ESPERA;
        }
        return Decisao.NADA;
    }

    /**
     * Roda {@code trabalho} numa thread virtual; depois chama {@code aoConcluir} OU {@code aoFalhar} na EDT.
     * Quem chama é a EDT (um clique), então a interface nunca espera pela rede.
     */
    public static <T> void rodar(Callable<T> trabalho, Consumer<T> aoConcluir, Consumer<Throwable> aoFalhar) {
        EsperaLonga.Acao acao = acompanharEspera();
        Fundo.emFundoComResultado(trabalho).whenComplete((resultado, falha) -> Fundo.naEdt(() -> {
            esperaTerminou(acao);
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
