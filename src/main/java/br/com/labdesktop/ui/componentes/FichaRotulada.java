package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * Uma FICHA: dados soltos em grade, cada um com o rótulo (cinza, 13) numa linha e o valor (claro, 14) na de baixo. Existe no
 * lugar das linhas corridas de dados juntos com " · " ("main@a1b2c3d · tamanho · mexido há 3 d"), que quebravam, apertavam e
 * ninguém entendia. Só mostra o que existe: o dado ausente simplesmente não entra (nada de "ainda não medido"). As colunas
 * dividem a largura por igual; o texto que não cabe termina em reticências, com tudo na dica.
 */
public final class FichaRotulada extends Painel {

    private static final long serialVersionUID = 1L;

    /**
     * Um dado da ficha: o rótulo e o componente do valor (um {@link Rotulo}, ou links, ou uma pequena lista).
     *
     * @param linhaInteira o dado ocupa uma linha só dele, na largura toda (o "Código", que leva o último commit e botões)
     */
    public record Item(String rotulo, JComponent valor, boolean linhaInteira) {

        public Item(String rotulo, JComponent valor) {
            this(rotulo, valor, false);
        }

        /** Um valor em texto. */
        public static Item de(String rotulo, String valor) {
            Rotulo v = new Rotulo(valor).tamanho(13f).truncavel();
            v.setToolTipText(valor);
            return new Item(rotulo, v);
        }
    }

    private final int colunas;
    private final int espaco;
    private List<Item> itens = List.of();

    /**
     * @param colunas quantas colunas de dados (2 na ficha do projeto, 4 na da máquina)
     * @param espaco  px entre as colunas
     */
    public FichaRotulada(int colunas, int espaco) {
        super(new GridBagLayout());
        this.colunas = Math.max(1, colunas);
        this.espaco = espaco;
        setVisible(false);
    }

    /** Troca os dados. Sem nenhum, a ficha some inteira. */
    public void definir(List<Item> novos) {
        this.itens = List.copyOf(novos);
        removeAll();
        int i = 0;
        // os dados de linha inteira (o Código) vão para a primeira linha; os outros correm em colunas logo abaixo (a ordem de {@link #pares} não muda)
        List<Item> ordem = new ArrayList<>();
        itens.stream().filter(Item::linhaInteira).forEach(ordem::add);
        itens.stream().filter(x -> !x.linhaInteira()).forEach(ordem::add);
        for (Item item : ordem) {
            if (item.linhaInteira() && i % colunas != 0) {
                i += colunas - i % colunas; // começa uma linha nova
            }
            Rotulo rotulo = new Rotulo(item.rotulo()).tamanho(12f).corDoToken(Tokens.TEXTO3).truncavel();
            Painel celula = new Painel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            celula.add(rotulo, c);
            c.gridy = 1;
            c.insets = new Insets(0, 0, 0, 0);
            celula.add(item.valor(), c);
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = i % colunas;
            g.gridy = i / colunas;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.anchor = GridBagConstraints.NORTHWEST;
            g.insets = new Insets(i / colunas == 0 ? 0 : 6, 0, 0, item.linhaInteira() || i % colunas == colunas - 1 ? 0 : espaco);
            if (item.linhaInteira()) {
                g.gridwidth = colunas;
            }
            add(celula, g);
            i += item.linhaInteira() ? colunas : 1;
        }
        setVisible(!itens.isEmpty());
        revalidate();
        repaint();
    }

    /** Os pares (rótulo, valor em texto) na ordem da tela, para os testes. */
    public List<List<String>> pares() {
        List<List<String>> l = new ArrayList<>();
        for (Item item : itens) {
            l.add(List.of(item.rotulo(), textoDe(item.valor())));
        }
        return l;
    }

    private static String textoDe(JComponent c) {
        StringBuilder sb = new StringBuilder();
        juntar(c, sb);
        return sb.toString().strip();
    }

    private static void juntar(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof javax.swing.JLabel l && l.getText() != null && !l.getText().isEmpty()) {
            sb.append(l.getText()).append(' ');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null && !b.getText().isEmpty()) {
            sb.append(b.getText()).append(' ');
        } else if (c instanceof TextoCorrido t && !t.getText().isEmpty()) {
            sb.append(t.getText()).append(' '); // o texto que quebra em linhas (a linha "Código")
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                juntar(f, sb);
            }
        }
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
