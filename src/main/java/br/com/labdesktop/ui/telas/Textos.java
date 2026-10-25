package br.com.labdesktop.ui.telas;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JLabel;

/** Junta o texto de todos os rótulos de uma árvore de componentes (os testes conferem o conteúdo dos blocos por aqui). */
final class Textos {

    private Textos() { }

    static String de(Component raiz) {
        StringBuilder sb = new StringBuilder();
        juntar(raiz, sb);
        return sb.toString();
    }

    private static void juntar(Component c, StringBuilder sb) {
        if (c instanceof JLabel l && l.getText() != null && !l.getText().isEmpty()) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.text.JTextComponent t && t.getText() != null && !t.getText().isEmpty()) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo s && !s.texto().isEmpty()) {
            sb.append(s.texto()).append('\n');
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                juntar(f, sb);
            }
        }
    }
}
