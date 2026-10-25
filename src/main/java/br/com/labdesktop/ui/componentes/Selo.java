package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * O selo do painel ({@code .selo}): pílula de 22 px com ponto (ou ícone) e texto em caixa alta, 11 pt semibold com
 * espaçamento de letras. Estado nunca só pela cor: o selo SEMPRE tem texto (e o ícone, quando é um estado que o
 * ponto não distingue, como "pausada").
 */
public final class Selo extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final int ALTURA_MINIMA = 24;
    private static final int PADDING = 9;
    private static final int TAMANHO_DO_PONTO = 6;
    private static final int TAMANHO_DO_ICONE = 12;
    private static final int ESPACO = 6;

    private String texto;
    private List<String> linhas = List.of();
    private int larguraDeQuebra;
    private Gravidade gravidade;
    private String nomeDoIcone;
    private Icon icone;

    /** Selo com um ponto antes do texto. */
    public Selo(String texto, Gravidade gravidade) {
        this(texto, gravidade, null);
    }

    /** Selo com um ícone do painel no lugar do ponto ({@code nomeDoIcone == null}: ponto). */
    public Selo(String texto, Gravidade gravidade, String nomeDoIcone) {
        setOpaque(false);
        configurar(texto, gravidade, nomeDoIcone);
    }

    /** Troca o conteúdo (a lista reaproveita o componente quando o retrato da linha muda). */
    public void atualizar(String novoTexto, Gravidade novaGravidade, String novoIcone) {
        configurar(novoTexto, novaGravidade, novoIcone);
        revalidate();
        repaint();
    }

    private void configurar(String novoTexto, Gravidade novaGravidade, String novoIcone) {
        this.texto = novoTexto == null ? "" : TextoSeguro.limparLinha(novoTexto).toUpperCase(PT_BR);
        this.gravidade = novaGravidade;
        this.nomeDoIcone = novoIcone;
        this.linhas = quebrar(larguraDeQuebra);
        this.icone = novoIcone == null ? null : Icones.colorido(novoIcone, TAMANHO_DO_ICONE, novaGravidade.tokenDoTexto());
        getAccessibleContext().setAccessibleName(novoTexto);
    }

    public String texto() {
        return texto;
    }

    public Gravidade gravidade() {
        return gravidade;
    }

    /** A fonte do texto do selo (o teste das fontes mínimas confere que nunca passa de 12 para baixo). */
    public Font fonteDoTexto() {
        return fonteDoSelo();
    }

    private Font fonteDoSelo() {
        // 11 é o nominal; Fontes o leva ao mínimo de 12 e aplica o fator do usuário
        return Fontes.fonte(Fontes.TEXTO, Font.BOLD, 11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.04f));
    }

    /** A altura de uma linha só: a pílula acompanha a fonte (com o texto em 130 % a de 24 px não caberia). */
    private int alturaDeUmaLinha() {
        return Math.max(ALTURA_MINIMA, getFontMetrics(fonteDoSelo()).getHeight() + 8);
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.LABEL;
                }
            };
        }
        return accessibleContext;
    }

    private int largurasDaMarca() {
        return PADDING + (icone != null ? TAMANHO_DO_ICONE : TAMANHO_DO_PONTO) + ESPACO;
    }

    /**
     * Quebra o texto em linhas que caibam em {@code larguraTotal} (0 = sem limite: uma linha só). O selo "pausada pelo
     * limite" numa coluna estreita cai em duas linhas em vez de vazar para a coluna ao lado ({@code .selo-pausada} do
     * painel web).
     */
    private List<String> quebrar(int larguraTotal) {
        if (texto == null || texto.isEmpty()) {
            return List.of("");
        }
        FontMetrics fm = getFontMetrics(fonteDoSelo());
        int disponivel = larguraTotal - largurasDaMarca() - PADDING;
        if (larguraTotal <= 0 || fm.stringWidth(texto) <= disponivel) {
            return List.of(texto);
        }
        List<String> saida = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        for (String palavra : texto.split(" ")) {
            String tentativa = atual.length() == 0 ? palavra : atual + " " + palavra;
            if (atual.length() > 0 && fm.stringWidth(tentativa) > disponivel) {
                saida.add(atual.toString());
                atual = new StringBuilder(palavra);
            } else {
                atual = new StringBuilder(tentativa);
            }
        }
        if (atual.length() > 0) {
            saida.add(atual.toString());
        }
        return saida;
    }

    @Override
    public void setBounds(int x, int y, int largura, int altura) {
        super.setBounds(x, y, largura, altura);
        if (largura > 0 && largura != larguraDeQuebra) {
            larguraDeQuebra = largura;
            List<String> novas = quebrar(largura);
            if (!novas.equals(linhas)) {
                linhas = novas;
                SwingUtilities.invokeLater(this::revalidate); // a altura mudou: o layout pergunta de novo
            }
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(fonteDoSelo());
        int maisLarga = 0;
        for (String l : linhas) {
            maisLarga = Math.max(maisLarga, fm.stringWidth(l));
        }
        int altura = linhas.size() <= 1 ? alturaDeUmaLinha() : 8 + linhas.size() * (fm.getHeight());
        return new Dimension(largurasDaMarca() + maisLarga + PADDING, altura);
    }

    @Override
    public Dimension getMinimumSize() {
        // pode encolher até a palavra mais larga: o resto cai em linhas
        FontMetrics fm = getFontMetrics(fonteDoSelo());
        int palavra = 0;
        for (String p : texto.split(" ")) {
            palavra = Math.max(palavra, fm.stringWidth(p));
        }
        return new Dimension(largurasDaMarca() + palavra + PADDING, alturaDeUmaLinha());
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arco = linhas.size() <= 1 ? h : 16;
            g2.setColor(Tokens.cor(gravidade.tokenDoFundo()));
            g2.fillRoundRect(0, 0, w, h, arco, arco);
            g2.setColor(Tokens.cor(gravidade.tokenDoTexto()));
            int x = PADDING;
            if (icone != null) {
                icone.paintIcon(this, g2, x, (h - TAMANHO_DO_ICONE) / 2);
                x += TAMANHO_DO_ICONE + ESPACO;
            } else {
                g2.fillOval(x, (h - TAMANHO_DO_PONTO) / 2, TAMANHO_DO_PONTO, TAMANHO_DO_PONTO);
                x += TAMANHO_DO_PONTO + ESPACO;
            }
            g2.setFont(fonteDoSelo());
            FontMetrics fm = g2.getFontMetrics();
            int altoDoTexto = linhas.size() * fm.getHeight();
            int y = (h - altoDoTexto) / 2 + fm.getAscent();
            for (String linha : linhas) {
                g2.drawString(linha, x, y);
                y += fm.getHeight();
            }
        } finally {
            g2.dispose();
        }
    }
}
