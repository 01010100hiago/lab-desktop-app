package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.Painel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import javax.swing.JComponent;

/**
 * As colunas da lista de projetos (o {@code grid-template-columns} do {@code .lp-linha} do painel web): estado (com os
 * containers embaixo), projeto, CPU e memória (empilhadas), consumo de 7 dias (no mínimo 156 px, para a barra do limite e o
 * texto de 13), código e endereço (git e URL empilhados), Claude e ações, com o mesmo espaço entre elas (8 px) e o mesmo recuo
 * (12 px). São sete colunas, e não dez, para a soma dos pisos caber na janela de 1100 px (844 px de lista) com o texto de 13 e
 * 14: dez colunas lado a lado passavam de 1100 e a lista rolava para o lado. O cabeçalho e cada linha montam o MESMO conjunto de células
 * com larguras vindas daqui, não do conteúdo, e por isso alinham (o equivalente de "grids separados com as
 * mesmas colunas").
 */
final class ColunasDaLista {

    /** Largura mínima, largura preferida e peso na sobra. {@code min} é o que o {@code minmax} do CSS chama de piso. */
    enum Coluna {
        ESTADO("Estado", 96, 112, 0, false),
        PROJETO("Projeto", 100, 200, 1.2, false),
        RECURSOS("Recursos", 70, 84, 0, true),
        CONSUMO("Consumo 7 dias", 172, 260, 1.2, true),
        CODIGO("Código", 104, 190, 0.8, false),
        CLAUDE("Claude", 108, 150, 0, false),
        ACOES("Ações", 104, 112, 0, false);

        final String rotulo;
        final int minimo;
        final int preferida;
        final double peso;
        final boolean numerica;

        Coluna(String rotulo, int minimo, int preferida, double peso, boolean numerica) {
            this.rotulo = rotulo;
            this.minimo = minimo;
            this.preferida = preferida;
            this.peso = peso;
            this.numerica = numerica;
        }
    }

    /** Espaço entre colunas. */
    static final int ESPACO = 8;
    /** Recuo horizontal das linhas. */
    static final int RECUO = 12;

    private ColunasDaLista() { }

    /** Largura abaixo da qual a lista rola na horizontal (a soma dos pisos, dos espaços e dos recuos). */
    static int larguraMinima() {
        int soma = 2 * RECUO + ESPACO * (Coluna.values().length - 1);
        for (Coluna c : Coluna.values()) {
            soma += c.minimo;
        }
        return soma;
    }

    /** As restrições da célula {@code c} numa linha (cada coluna é uma célula do {@code GridBagLayout}). */
    static GridBagConstraints restricoes(Coluna c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = c.ordinal();
        g.gridy = 0;
        g.weightx = c.peso;
        g.weighty = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, c.ordinal() == 0 ? 0 : ESPACO, 0, 0);
        return g;
    }

    /**
     * Uma célula: o tamanho horizontal é o da coluna (piso e preferido), a altura é a do conteúdo. O conteúdo ocupa a
     * célula inteira.
     */
    static JComponent celula(Coluna c, JComponent conteudo) {
        Painel p = new Painel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(c.preferida, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(c.minimo, super.getMinimumSize().height);
            }
        };
        p.add(conteudo, BorderLayout.CENTER);
        return p;
    }
}
