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

    /** A tecla escrita para o usuário ("Cmd+,", "Ctrl+Shift+N", "F1"): a tecla de comando do sistema se chama Cmd no Mac e Ctrl nos outros. */
    public static String descrever(javax.swing.KeyStroke tecla, SistemaOperacional sistema) {
        int mods = tecla.getModifiers();
        StringBuilder b = new StringBuilder();
        if ((mods & java.awt.event.InputEvent.META_DOWN_MASK) != 0) {
            b.append("Cmd+");
        }
        if ((mods & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) {
            b.append("Ctrl+");
        }
        if ((mods & java.awt.event.InputEvent.ALT_DOWN_MASK) != 0) {
            b.append(sistema == SistemaOperacional.MAC ? "Option+" : "Alt+");
        }
        if ((mods & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0) {
            b.append("Shift+");
        }
        b.append(nomeDaTecla(tecla.getKeyCode()));
        return b.toString();
    }

    private static String nomeDaTecla(int codigo) {
        return switch (codigo) {
            case java.awt.event.KeyEvent.VK_COMMA -> ",";
            case java.awt.event.KeyEvent.VK_PERIOD -> ".";
            case java.awt.event.KeyEvent.VK_SLASH -> "/";
            case java.awt.event.KeyEvent.VK_OPEN_BRACKET -> "[";
            case java.awt.event.KeyEvent.VK_CLOSE_BRACKET -> "]";
            case java.awt.event.KeyEvent.VK_EQUALS -> "=";
            case java.awt.event.KeyEvent.VK_MINUS -> "-";
            case java.awt.event.KeyEvent.VK_PLUS -> "+";
            default -> java.awt.event.KeyEvent.getKeyText(codigo).toUpperCase(java.util.Locale.ROOT);
        };
    }

    public static String descrever(javax.swing.KeyStroke tecla) {
        return descrever(tecla, SistemaOperacional.detectar());
    }
}
