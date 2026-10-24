package br.com.labdesktop.ui;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;

/**
 * O vigia de "Swing fora da EDT" dos testes (ARQUITETURA 6.6): um {@code RepaintManager} que registra toda
 * invalidação ou repintura pedida de uma thread que não é a EDT. Componente tocado de thread de fundo é o bug que
 * deixa a janela esquisita só de vez em quando; aqui ele vira falha do teste ({@link VerificaEdt}).
 */
public final class VerificadorDeEdt extends RepaintManager {

    private static final List<String> VIOLACOES = new CopyOnWriteArrayList<>();

    private VerificadorDeEdt() { }

    /** Instala o vigia (uma vez por JVM). */
    public static void instalar() {
        if (!(RepaintManager.currentManager(null) instanceof VerificadorDeEdt)) {
            RepaintManager.setCurrentManager(new VerificadorDeEdt());
        }
    }

    public static List<String> violacoes() {
        return List.copyOf(VIOLACOES);
    }

    public static void limpar() {
        VIOLACOES.clear();
    }

    private static void conferir(String operacao, JComponent c) {
        if (!SwingUtilities.isEventDispatchThread()) {
            String pilha = Arrays.stream(Thread.currentThread().getStackTrace()).skip(3).limit(12)
                .filter(f -> f.getClassName().startsWith("br.com.labdesktop"))
                .map(f -> "    at " + f).collect(Collectors.joining("\n"));
            VIOLACOES.add(operacao + " em " + c.getClass().getSimpleName() + " na thread " + Thread.currentThread().getName()
                + "\n" + pilha);
        }
    }

    @Override
    public synchronized void addInvalidComponent(JComponent componente) {
        conferir("revalidate", componente);
        super.addInvalidComponent(componente);
    }

    @Override
    public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
        conferir("repaint", c);
        super.addDirtyRegion(c, x, y, w, h);
    }
}
