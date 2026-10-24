package br.com.labdesktop.ui;

import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.Dialogo;
import java.util.ArrayList;
import java.util.List;

/**
 * Um {@link Apresentador} que NÃO abre janela (o Swing sem cabeça não cria {@code JDialog}): guarda o diálogo que
 * seria mostrado, e o teste age sobre o painel dele ({@link Dialogo#raiz()}), como o usuário agiria.
 */
public final class DialogosGravados implements Apresentador {

    public final List<Dialogo> mostrados = new ArrayList<>();

    @Override
    public void mostrar(Dialogo dialogo) {
        mostrados.add(dialogo);
    }

    public Dialogo ultimo() {
        return mostrados.isEmpty() ? null : mostrados.get(mostrados.size() - 1);
    }

    @SuppressWarnings("unchecked")
    public <T extends Dialogo> T ultimoComo(Class<T> tipo) {
        Dialogo d = ultimo();
        if (!tipo.isInstance(d)) {
            throw new AssertionError("o último diálogo é " + (d == null ? "nenhum" : d.getClass().getSimpleName())
                + ", esperava " + tipo.getSimpleName());
        }
        return (T) d;
    }
}
