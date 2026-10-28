package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import javax.swing.JMenuItem;

/** Um item de menu de contexto cuja letra é de um token (o vermelho de "Remover"): a cor é lida na pintura, então um menu guardado acompanha a troca de tema. */
public final class ItemDeMenuPerigo extends JMenuItem {

    private static final long serialVersionUID = 1L;
    private final String tokenDaCor;

    public ItemDeMenuPerigo(String texto, String tokenDaCor) {
        super(texto);
        this.tokenDaCor = tokenDaCor;
    }

    @Override
    public Color getForeground() {
        // o construtor do JMenuItem já pergunta a cor (updateUI) antes de o campo existir
        return tokenDaCor == null ? super.getForeground() : Tokens.cor(tokenDaCor);
    }
}
