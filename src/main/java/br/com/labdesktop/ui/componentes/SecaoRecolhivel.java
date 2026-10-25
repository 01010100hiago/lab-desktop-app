package br.com.labdesktop.ui.componentes;

import java.awt.BorderLayout;
import javax.swing.JComponent;

/**
 * Uma seção que abre e fecha (os "detalhes" do HTML): um botão de texto com a seta e, embaixo, o conteúdo, que começa
 * FECHADO. Serve para guardar o que o caminho normal não precisa (o comando técnico, as opções de quem já tem uma chave)
 * sem apagar da tela de quem precisa. Quem a usa recebe um aviso a cada mudança, para refazer o tamanho da janela.
 */
public final class SecaoRecolhivel extends Painel {

    private static final long serialVersionUID = 1L;

    private final String rotuloFechada;
    private final String rotuloAberta;
    private final Botao alternar;
    /** Quem some e aparece é este envoltório, não o conteúdo: o conteúdo pode ligar a própria visibilidade (a ficha some sem dados e volta com eles). */
    private final Painel gaveta = new Painel(new BorderLayout());
    private final Runnable aoMudar;
    private boolean aberta;

    /**
     * @param rotuloFechada o texto do botão enquanto a seção está fechada ("Ver comando")
     * @param rotuloAberta  o texto do botão enquanto está aberta ("Esconder o comando")
     * @param conteudo      o que aparece ao abrir
     * @param aoMudar       chamado depois de abrir ou fechar
     */
    public SecaoRecolhivel(String rotuloFechada, String rotuloAberta, JComponent conteudo, Runnable aoMudar) {
        super(new BorderLayout(0, 6));
        this.rotuloFechada = rotuloFechada;
        this.rotuloAberta = rotuloAberta;
        this.aoMudar = aoMudar;
        this.alternar = Botoes.fantasma(rotuloFechada, "avancar");
        alternar.setIconTextGap(6);
        alternar.getAccessibleContext().setAccessibleName(rotuloFechada);
        add(Caixa.linha(0, alternar, Caixa.mola()), BorderLayout.NORTH);
        gaveta.add(conteudo, BorderLayout.CENTER);
        gaveta.setVisible(false);
        add(gaveta, BorderLayout.CENTER);
        alternar.addActionListener(e -> definirAberta(!aberta));
    }

    /** Abre ou fecha. Na EDT. */
    public void definirAberta(boolean abrir) {
        if (abrir == aberta) {
            return;
        }
        aberta = abrir;
        gaveta.setVisible(abrir);
        alternar.setText(abrir ? rotuloAberta : rotuloFechada);
        alternar.setIcon(Icones.de(abrir ? "seta-baixo" : "avancar", 16));
        alternar.getAccessibleContext().setAccessibleName(abrir ? rotuloAberta : rotuloFechada);
        revalidate();
        aoMudar.run();
    }

    public boolean aberta() {
        return aberta;
    }

    /** O botão que abre e fecha (para os testes). */
    public Botao botao() {
        return alternar;
    }
}
