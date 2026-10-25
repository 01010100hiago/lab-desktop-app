package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Uma faixa de aviso DENTRO de uma aba do projeto ("A sessão do Claude está desatualizada", "Sessão pausada pelo limite"):
 * fundo suave da gravidade, ícone, título em negrito, uma frase e, à direita, os botões que resolvem. Estado nunca é só cor:
 * sempre tem ícone e texto. Some inteira (e o espaço dela) quando não há o que avisar.
 */
final class AlertaDaPagina extends Painel {

    private static final long serialVersionUID = 1L;

    private final Rotulo titulo = new Rotulo().negrito().tamanho(13f);
    private final Rotulo texto = new Rotulo().tamanho(12.5f).corDoToken(Tokens.TEXTO2).truncavel();
    private final JPanel botoes = new Painel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));

    AlertaDaPagina() {
        super(new BorderLayout(12, 0));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 14, 6, 12));
        // uma linha só (título e frase lado a lado): o aviso não rouba duas linhas da página; a frase inteira vai na dica
        JPanel meio = new Painel(new BorderLayout(10, 0));
        meio.add(titulo, BorderLayout.WEST);
        meio.add(texto, BorderLayout.CENTER);
        add(meio, BorderLayout.CENTER);
        add(botoes, BorderLayout.EAST);
        setVisible(false);
    }

    /** Mostra o aviso com os botões dados (zero ou mais). */
    void mostrar(Gravidade g, String icone, String tituloDoAviso, String frase, Botao... acoes) {
        fundo(g.tokenDoFundo()).borda(g == Gravidade.NEUTRO ? Tokens.BORDA : g.tokenDaBorda()).raio(10);
        titulo.setText(tituloDoAviso);
        titulo.corDoToken(g.tokenDoTexto());
        titulo.setIcon(Icones.colorido(icone, 16, g.tokenDoTexto()));
        titulo.setIconTextGap(8);
        texto.setText(frase);
        texto.setToolTipText(frase);
        botoes.removeAll();
        for (JComponent b : acoes) {
            botoes.add(b);
        }
        setVisible(true);
        getAccessibleContext().setAccessibleName(tituloDoAviso);
        revalidate();
        repaint();
    }

    void esconder() {
        setVisible(false);
    }

    String textoDoTitulo() {
        return titulo.getText();
    }

    String textoDaFrase() {
        return texto.getText();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
