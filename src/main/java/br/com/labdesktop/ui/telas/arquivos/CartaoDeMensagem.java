package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;

/**
 * O cartão central de um estado sem tabela: pasta vazia, sem permissão, servidor sem SFTP, sem conexão, pasta que não existe.
 * Um ícone, uma frase em negrito, uma explicação que quebra linha e, se fizer sentido, UM botão com o verbo da saída ("Voltar",
 * "Tentar de novo"). O estado sempre vem com ícone e palavras, nunca só cor.
 */
final class CartaoDeMensagem extends Painel {

    private static final long serialVersionUID = 1L;

    private final Rotulo titulo = new Rotulo().negrito().tamanho(15f);
    private final TextoCorrido texto = new TextoCorrido("", 460).corDoToken(br.com.labdesktop.ui.tema.Tokens.TEXTO2);
    private final Botao acao = Botoes.secundario("", null);
    private Runnable aoClicar = () -> { };

    @SuppressWarnings("this-escape")
    CartaoDeMensagem() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        titulo.setIconTextGap(10);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.CENTER;
        add(titulo, g);
        g.gridy = 1;
        g.insets = new Insets(8, 0, 0, 0);
        g.fill = GridBagConstraints.HORIZONTAL;
        add(texto, g);
        g.gridy = 2;
        g.insets = new Insets(14, 0, 0, 0);
        g.fill = GridBagConstraints.NONE;
        add(acao, g);
        acao.addActionListener(e -> aoClicar.run());
        acao.setVisible(false);
        setName("cartao-de-mensagem");
    }

    /**
     * @param icone   nome do ícone
     * @param g       o peso (decide a cor do ícone e do título)
     * @param verbo   o texto do botão ({@code null}: sem botão)
     */
    void mostrar(String icone, Gravidade g, String frase, String explicacao, String verbo, Runnable aoApertar) {
        titulo.setText(frase);
        titulo.corDoToken(g == Gravidade.NEUTRO ? br.com.labdesktop.ui.tema.Tokens.TEXTO : g.tokenDoTexto());
        titulo.setIcon(Icones.colorido(icone, 22, g == Gravidade.NEUTRO ? br.com.labdesktop.ui.tema.Tokens.TEXTO3 : g.tokenDoTexto()));
        texto.setText(explicacao);
        texto.setVisible(!explicacao.isEmpty());
        acao.setVisible(verbo != null);
        if (verbo != null) {
            acao.setText(verbo);
            aoClicar = aoApertar == null ? () -> { } : aoApertar;
        }
        getAccessibleContext().setAccessibleName(frase);
        revalidate();
        repaint();
    }

    String textoDaFrase() {
        return titulo.getText();
    }

    String textoDaExplicacao() {
        return texto.getText();
    }

    Botao botao() {
        return acao;
    }
}
