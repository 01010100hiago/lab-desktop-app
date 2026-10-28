package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Uma tabela de texto simples: o rótulo das colunas em caixa alta pequena, um traço, e as linhas separadas por traços
 * finos. Cada coluna tem peso na sobra de largura; o texto que não cabe termina em reticências (e vai inteiro na dica).
 * Sem dado, mostra a frase de "vazio". Feita para listas curtas (20 processos, 5 sessões, 10 logins), refeita inteira
 * a cada atualização.
 */
public final class TabelaDeLinhas extends Painel {

    private static final long serialVersionUID = 1L;

    /** Uma coluna: título, peso na sobra, se alinha à direita e se usa a fonte mono. */
    public record Coluna(String titulo, double peso, boolean numerica, boolean mono) { }

    private final List<Coluna> colunas;
    private final Rotulo vazio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12.5f);
    private List<List<String>> linhas = List.of();

    public TabelaDeLinhas(Coluna... colunas) {
        super(new GridBagLayout());
        this.colunas = List.of(colunas);
        definir(List.of(), "");
    }

    /** Troca as linhas. {@code mensagemVazia} aparece no lugar delas quando a lista está vazia. */
    public void definir(List<List<String>> novas, String mensagemVazia) {
        this.linhas = List.copyOf(novas);
        removeAll();
        int y = 0;
        if (linhas.isEmpty()) {
            vazio.setText(mensagemVazia);
            GridBagConstraints g = restricoes(0, y, colunas.size(), 0);
            add(vazio, g);
            revalidate();
            repaint();
            return;
        }
        for (int i = 0; i < colunas.size(); i++) {
            Coluna c = colunas.get(i);
            Rotulo r = new Rotulo(c.titulo().toUpperCase(Locale.forLanguageTag("pt-BR"))).corDoToken(Tokens.TEXTO3)
                .negrito().tamanho(10.5f).truncavel();
            r.setHorizontalAlignment(c.numerica() ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT);
            GridBagConstraints g = restricoes(i, y, 1, c.peso());
            g.insets = new Insets(0, i == 0 ? 0 : 12, 6, 0);
            add(r, g);
        }
        y++;
        for (List<String> linha : linhas) {
            add(new Traco(Tokens.BORDA_SUTIL), restricoes(0, y++, colunas.size(), 0));
            for (int i = 0; i < colunas.size(); i++) {
                Coluna c = colunas.get(i);
                String texto = i < linha.size() && linha.get(i) != null ? linha.get(i) : FormatosDoServidor.SEM_DADO;
                Rotulo r = new Rotulo(texto).truncavel();
                if (c.mono()) {
                    r.mono(11.5f);
                } else {
                    r.tamanho(12.5f);
                }
                r.setToolTipText(texto);
                r.setHorizontalAlignment(c.numerica() ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT);
                GridBagConstraints g = restricoes(i, y, 1, c.peso());
                g.insets = new Insets(5, i == 0 ? 0 : 12, 5, 0);
                add(r, g);
            }
            y++;
        }
        revalidate();
        repaint();
    }

    private static GridBagConstraints restricoes(int x, int y, int largura, double peso) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.gridwidth = largura;
        g.weightx = peso;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        return g;
    }

    /** As linhas atuais (para os testes). */
    List<List<String>> linhas() {
        return new ArrayList<>(linhas);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** O traço fino entre linhas. */
    private static final class Traco extends JComponent {
        private static final long serialVersionUID = 1L;
        private final String token;

        Traco(String token) {
            this.token = token;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Tokens.cor(token));
            g.fillRect(0, 0, getWidth(), 1);
        }
    }
}
