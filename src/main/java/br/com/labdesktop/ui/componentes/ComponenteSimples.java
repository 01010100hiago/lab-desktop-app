package br.com.labdesktop.ui.componentes;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Um {@code JComponent} pintado à mão que, ao contrário do {@code JComponent} puro, tem contexto de acessibilidade (o
 * puro devolve {@code null}), então dá para dar nome ao que o leitor de tela lê.
 */
public abstract class ComponenteSimples extends JComponent {

    private static final long serialVersionUID = 1L;

    /** O papel que o leitor de tela anuncia. */
    protected AccessibleRole papel() {
        return AccessibleRole.PANEL;
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return papel();
                }
            };
        }
        return accessibleContext;
    }
}
