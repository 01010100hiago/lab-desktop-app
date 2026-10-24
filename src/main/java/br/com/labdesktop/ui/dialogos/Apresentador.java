package br.com.labdesktop.ui.dialogos;

/**
 * Quem mostra um {@link Dialogo} (modal, sobre a janela principal). Existe para as telas e os fluxos não conhecerem
 * a janela e para os testes capturarem o diálogo e agirem sobre o painel dele.
 */
@FunctionalInterface
public interface Apresentador {

    /** Mostra o diálogo e só volta quando ele fechar (modal). Na EDT. */
    void mostrar(Dialogo dialogo);
}
