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
import java.util.Map;
import java.util.function.Consumer;
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
    private Map<String, String> destinos = Map.of();
    private Consumer<String> aoEscolher = chave -> { };

    FaixaDeKpis() {
        super(new GridLayout(1, ModeloDeKpis.MAXIMO));
        fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        setName("faixa-de-kpis");
        getAccessibleContext().setAccessibleName("Indicadores do servidor");
        atualizar(ModeloDeKpis.de(null, null));
    }

    /** O nome que o leitor de tela fala para a faixa (a página do projeto tem os indicadores do projeto, não do servidor). */
    void nomeAcessivel(String nome) {
        getAccessibleContext().setAccessibleName(nome);
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
                c.pai = this;
                cartoes.add(c);
                add(c);
            }
            setLayout(new GridLayout(1, kpis.size()));
        }
        for (int i = 0; i < kpis.size(); i++) {
            cartoes.get(i).mostrar(kpis.get(i));
            cartoes.get(i).atualizarDestino();
        }
        revalidate();
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * Torna os indicadores clicáveis: {@code destinos} diz, por chave (cpu, disco...), o nome da aba para onde o clique leva
     * (a Saúde não leva a lugar nenhum); {@code aoEscolher} recebe a chave. Funciona também pelo teclado (Tab, Enter, espaço).
     */
    void ligar(Map<String, String> destinos, Consumer<String> aoEscolher) {
        this.destinos = Map.copyOf(destinos);
        this.aoEscolher = aoEscolher;
        cartoes.forEach(Cartao::atualizarDestino);
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
        private final AEsquerda caixaDoSelo = new AEsquerda(selo);
        private Kpi atual;
        private FaixaDeKpis pai;
        private boolean sobMouse;

        @SuppressWarnings("this-escape")
        Cartao() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 8));
            valor.mono(18f).negrito();
            for (Component c : new Component[] {rotulo, valor, legenda}) {
                ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
            }
            add(rotulo);
            add(javax.swing.Box.createRigidArea(new Dimension(0, 4)));
            add(valor);
            add(legenda);
            add(javax.swing.Box.createRigidArea(new Dimension(0, 8)));
            caixaDoSelo.setAlignmentX(LEFT_ALIGNMENT);
            add(caixaDoSelo);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    sobMouse = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    sobMouse = false;
                    repaint();
                }

                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
                        clicar();
                    }
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "abrir");
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, 0), "abrir");
            getActionMap().put("abrir", new javax.swing.AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    clicar();
                }
            });
        }

        String destino() {
            return pai == null || atual == null ? null : pai.destinos.get(atual.chave());
        }

        /** O mesmo que o clique do mouse: abre a aba do indicador (a Saúde não abre nada). */
        void clicar() {
            if (destino() != null) {
                pai.aoEscolher.accept(atual.chave());
            }
        }

        void atualizarDestino() {
            boolean clicavel = destino() != null;
            setFocusable(clicavel);
            setCursor(java.awt.Cursor.getPredefinedCursor(clicavel ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
            if (atual != null) {
                String dica = atual.dica() + (clicavel ? " Clique para abrir a aba " + destino() + "." : "");
                setToolTipText(dica);
                getAccessibleContext().setAccessibleDescription(dica);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (destino() != null && (sobMouse || isFocusOwner())) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Tokens.cor(Tokens.SUPERFICIE3));
                    g2.fillRoundRect(4, 4, getWidth() - 8, getHeight() - 8, 10, 10);
                    if (isFocusOwner()) {
                        g2.setColor(Tokens.cor(Tokens.FOCO));
                        g2.setStroke(new java.awt.BasicStroke(2f));
                        g2.drawRoundRect(4, 4, getWidth() - 9, getHeight() - 9, 10, 10);
                    }
                } finally {
                    g2.dispose();
                }
            }
            super.paintComponent(g);
        }

        void mostrar(Kpi k) {
            this.atual = k;
            rotulo.setText(k.rotulo());
            rotulo.setIcon(Icones.colorido(k.icone(), 14, Tokens.TEXTO3));
            rotulo.setIconTextGap(6);
            valor.setText(k.valor());
            legenda.setText(k.legenda());
            String palavra = k.seloTexto() != null && !k.semSelo() ? k.seloTexto() : ApresentacaoDeSaude.texto(k.nivel());
            selo.atualizar(palavra, ApresentacaoDeSaude.gravidade(k.nivel()), ApresentacaoDeSaude.icone(k.nivel()));
            caixaDoSelo.setVisible(!k.semSelo());
            valor.setToolTipText(k.dica());
            legenda.setToolTipText(k.dica());
            getAccessibleContext().setAccessibleName(k.rotulo() + ": " + k.valor() + (k.semSelo() ? "" : ", " + palavra));
            getAccessibleContext().setAccessibleDescription(k.dica());
            setName("kpi-" + k.chave());
            atualizarDestino();
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
