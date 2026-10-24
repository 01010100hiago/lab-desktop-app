package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A caixa de um bloco da Visão geral: superfície com borda fina e cantos de 14 px, um título com ícone (e, à direita,
 * um texto de apoio) e o corpo em coluna. É a mesma linguagem do cartão da lista de projetos: linhas dentro de uma
 * caixa, não cartões soltos coloridos.
 */
class CaixaDoServidor extends Painel {

    private static final long serialVersionUID = 1L;

    private final Rotulo titulo;
    private final JPanel corpo = new Painel();
    private final JPanel direita = new Painel(new BorderLayout());

    CaixaDoServidor(String texto, String icone) {
        super(new BorderLayout(0, 10));
        fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        titulo = new Rotulo(texto).negrito().tamanho(14f);
        if (icone != null) {
            titulo.setIcon(Icones.colorido(icone, 16, Tokens.TEXTO2));
            titulo.setIconTextGap(8);
        }
        corpo.setLayout(new BoxLayout(corpo, BoxLayout.Y_AXIS));
        add(Caixa.linha(10, titulo, Caixa.mola(), direita), BorderLayout.NORTH);
        add(corpo, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName(texto);
        setName("bloco-" + texto);
    }

    /** O texto (ou componente) à direita do título. */
    final void apoio(JComponent c) {
        direita.removeAll();
        direita.add(c, BorderLayout.CENTER);
        direita.revalidate();
        direita.repaint();
    }

    final void trocarTitulo(String texto) {
        titulo.setText(texto);
    }

    /** Esvazia o corpo (antes de refazê-lo). */
    final void limpar() {
        corpo.removeAll();
    }

    /** Acrescenta uma linha ao corpo com {@code antes} px de espaço em cima. */
    final void linha(JComponent c, int antes) {
        if (corpo.getComponentCount() > 0 && antes > 0) {
            corpo.add(Caixa.espacoVertical(antes));
        }
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        corpo.add(c);
    }

    final void terminar() {
        corpo.revalidate();
        corpo.repaint();
        revalidate();
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
