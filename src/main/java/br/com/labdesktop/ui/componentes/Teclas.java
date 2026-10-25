package br.com.labdesktop.ui.componentes;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.event.InputEvent;

/** Apoio a atalhos de teclado que funciona também sem tela (os testes de componente soltos rodam sem cabeça). */
public final class Teclas {

    private Teclas() { }

    /** A tecla de atalho do sistema (Cmd no Mac, Ctrl nos outros); sem tela, Cmd. */
    public static int comando() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (HeadlessException e) {
            return InputEvent.META_DOWN_MASK;
        }
    }
}
