package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.telas.ModeloDeKpis.Kpi;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A primeira linha da Visão geral: até seis indicadores lado a lado numa única caixa, separados por traços finos.
 * Cada um tem o nome (com ícone), o valor grande em fonte mono, uma legenda de uma linha e o selo do estado em texto e
 * ícone. A linha cabe em uma só altura em qualquer largura da janela: legenda comprida vira reticências (e vai inteira
 * na dica).
 */
final class FaixaDeKpis extends Painel {

    private static final long serialVersionUID = 1L;

    private final List<Cartao> cartoes = new ArrayList<>();

    FaixaDeKpis() {
        super(new GridLayout(1, ModeloDeKpis.MAXIMO));
        fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        setName("faixa-de-kpis");
        getAccessibleContext().setAccessibleName("Indicadores do servidor");
        atualizar(ModeloDeKpis.de(null, null));
    }

    void atualizar(List<Kpi> kpis) {
        if (kpis.size() > ModeloDeKpis.MAXIMO) {
            throw new IllegalArgumentException("No máximo " + ModeloDeKpis.MAXIMO + " indicadores na primeira linha.");
        }
        if (cartoes.size() != kpis.size()) {
            removeAll();
            cartoes.clear();
            for (int i = 0; i < kpis.size(); i++) {
                Cartao c = new Cartao();
                cartoes.add(c);
                add(c);
            }
            setLayout(new GridLayout(1, kpis.size()));
        }
        for (int i = 0; i < kpis.size(); i++) {
            cartoes.get(i).mostrar(kpis.get(i));
        }
        revalidate();
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Os indicadores na tela, da esquerda para a direita (para os testes). */
    List<Cartao> cartoes() {
        return List.copyOf(cartoes);
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        g.setColor(Tokens.cor(Tokens.BORDA_SUTIL));
        for (int i = 1; i < cartoes.size(); i++) {
            int x = cartoes.get(i).getX();
            g.fillRect(x, 14, 1, getHeight() - 28);
        }
    }

    /** Um indicador. */
    static final class Cartao extends JPanel {
        private static final long serialVersionUID = 1L;
        private final Rotulo rotulo = new Rotulo().corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f).truncavel();
        private final Rotulo valor = new Rotulo().tamanho(18f).negrito().truncavel();
        private final Rotulo legenda = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(11.5f).truncavel();
        private final Selo selo = new Selo("", br.com.labdesktop.ui.Gravidade.NEUTRO, "info");
        private Kpi atual;

        @SuppressWarnings("this-escape")
        Cartao() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 12));
            valor.mono(18f).negrito();
            for (Component c : new Component[] {rotulo, valor, legenda}) {
                ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
            }
            add(rotulo);
            add(javax.swing.Box.createRigidArea(new Dimension(0, 4)));
            add(valor);
            add(legenda);
            add(javax.swing.Box.createRigidArea(new Dimension(0, 8)));
            AEsquerda s = new AEsquerda(selo);
            s.setAlignmentX(LEFT_ALIGNMENT);
            add(s);
        }

        void mostrar(Kpi k) {
            this.atual = k;
            rotulo.setText(k.rotulo());
            rotulo.setIcon(Icones.colorido(k.icone(), 14, Tokens.TEXTO3));
            rotulo.setIconTextGap(6);
            valor.setText(k.valor());
            legenda.setText(k.legenda());
            selo.atualizar(ApresentacaoDeSaude.texto(k.nivel()), ApresentacaoDeSaude.gravidade(k.nivel()),
                ApresentacaoDeSaude.icone(k.nivel()));
            setToolTipText(k.dica());
            valor.setToolTipText(k.dica());
            legenda.setToolTipText(k.dica());
            getAccessibleContext().setAccessibleName(k.rotulo() + ": " + k.valor() + ", " + ApresentacaoDeSaude.texto(k.nivel()));
            getAccessibleContext().setAccessibleDescription(k.dica());
            setName("kpi-" + k.chave());
        }

        Kpi kpi() {
            return atual;
        }

        String textoDoValor() {
            return valor.getText();
        }

        String textoDaLegenda() {
            return legenda.getText();
        }

        String textoDoSelo() {
            return selo.texto();
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }
    }
}
