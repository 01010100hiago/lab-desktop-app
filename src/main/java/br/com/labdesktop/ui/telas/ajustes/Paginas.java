package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.border.EmptyBorder;

/** As peças que as seções da tela Ajustes repetem: a coluna da seção, o título de grupo e o texto de ajuda que quebra linha. */
final class Paginas {

    /** A largura do texto corrido nas seções (a coluna não passa disto, para a linha continuar legível em janela larga). */
    static final int LARGURA_DO_TEXTO = 640;

    private Paginas() { }

    /** A coluna de uma seção: margem em volta e os filhos empilhados com o espaço dado. */
    static JComponent coluna(int espaco, Component... filhos) {
        JComponent c = Caixa.coluna(espaco, filhos);
        c.setBorder(new EmptyBorder(20, 24, 24, 24));
        return c;
    }

    /** Título de um grupo dentro da seção. */
    static Rotulo grupo(String texto) {
        return Rotulo.titulo(texto).tamanho(15f);
    }

    /** Texto de ajuda: pequeno, discreto e quebrando linha. */
    static TextoCorrido ajuda(String texto) {
        return new TextoCorrido(texto, LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2).tamanho(13f);
    }

    /** Um bloco com fundo de superfície e borda, onde as opções ficam juntas. */
    static Painel cartao(Component... filhos) {
        Painel p = new Painel(new java.awt.BorderLayout());
        p.fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(12);
        JComponent miolo = Caixa.coluna(8, filhos);
        miolo.setBorder(new EmptyBorder(14, 16, 14, 16));
        p.add(miolo, java.awt.BorderLayout.CENTER);
        return p;
    }
}
