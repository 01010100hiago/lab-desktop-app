package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.plataforma.SistemaOperacional;
import java.awt.event.InputEvent;

/**
 * Apoio a atalhos de teclado: a tecla de comando de cada sistema (Cmd no Mac, Ctrl no Windows e no Linux).
 *
 * <p>Decidida pelo SISTEMA e não pelo {@code Toolkit}: o Toolkit não responde sem tela (os testes de componente soltos rodam sem cabeça) e um
 * teste não consegue trocá-lo. Assim o menu e os testes dizem a mesma coisa nos três sistemas, e um teste prova o Windows num Mac.
 *
 * <p>No Windows e no Linux o Ctrl dos atalhos do app convive com o terminal: com o foco no terminal o JediTerm consome a tecla antes do menu
 * (Ctrl+C continua sendo "interromper"), e copiar e colar lá são Ctrl+Shift+C e Ctrl+Shift+V (o padrão do JediTerm fora do Mac).
 */
public final class Teclas {

    private Teclas() { }

    /** A máscara da tecla de comando de um sistema: {@code META_DOWN_MASK} no Mac, {@code CTRL_DOWN_MASK} nos outros. */
    public static int comando(SistemaOperacional sistema) {
        return sistema == SistemaOperacional.MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /** A de ESTE computador. */
    public static int comando() {
        return comando(SistemaOperacional.detectar());
    }

    /** O nome da tecla para frases e dicas ({@code Cmd} ou {@code Ctrl}). */
    public static String nome(SistemaOperacional sistema) {
        return sistema == SistemaOperacional.MAC ? "Cmd" : "Ctrl";
    }

    public static String nome() {
        return nome(SistemaOperacional.detectar());
    }
}
