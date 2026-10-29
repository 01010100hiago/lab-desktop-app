package br.com.labdesktop.ui.telas;

import br.com.labdesktop.git.DiffDoArquivo;
import br.com.labdesktop.git.LinhaDeDiff;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;

/**
 * O diff de UM arquivo, só leitura, em fonte monoespaçada: cada linha leva o SÍMBOLO na frente ({@code +} acrescentada, {@code -} tirada, espaço sem mudança, {@code @} trecho) e um fundo de tom
 * neutro (grafite mais claro ou mais escuro), então a diferença nunca depende só da cor. O cabeçalho diz o arquivo, quantas linhas entraram e saíram e tem o Copiar; avisos: "diff cortado" (limite
 * de linhas ou de tamanho), "arquivo binário", "sem mudança de conteúdo" e "texto fora de UTF-8". É um {@code JList} com altura de linha fixa (só pinta o que aparece), então 2000 linhas não pesam.
 * O texto nunca é HTML.
 */
final class VistaDeDiff extends Painel {

    private static final long serialVersionUID = 1L;
    private static final String CARTAO_DIFF = "diff";
    private static final String CARTAO_MENSAGEM = "mensagem";

    private final Rotulo arquivo = new Rotulo().mono(12f).truncavel();
    private final Rotulo contagem = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f);
    private final Rotulo aviso = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Rotulo mensagem = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(13f).aoCentro();
    private final DefaultListModel<LinhaDeDiff> linhas = new DefaultListModel<>();
    private final JList<LinhaDeDiff> lista = new JList<>(linhas);
    private final CardLayout cartoes = new CardLayout();
    private final JPanel corpo = new Painel(cartoes);
    private final Botao copiar;
    private String textoParaCopiar = "";
    private DiffDoArquivo atual;

    @SuppressWarnings("this-escape")
    VistaDeDiff(CopiadorDeTexto copiador, Notificacoes avisos) {
        super(new BorderLayout(0, 6));
        setName("vista-do-diff");
        this.copiar = BotaoCopiar.criar("Copiar diff", () -> textoParaCopiar, copiador, avisos);
        copiar.setName("copiar-diff");
        copiar.setEnabled(false);
        lista.setCellRenderer(new Renderizador());
        lista.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lista.setFixedCellHeight(Math.max(18, getFontMetrics(Fontes.fonte(Fontes.MONO, Font.PLAIN, 12f)).getHeight() + 4));
        lista.putClientProperty("html.disable", Boolean.TRUE);
        lista.setBackground(Tokens.cor(Tokens.LOGS_BG));
        lista.getAccessibleContext().setAccessibleName("Linhas do diff");
        JScrollPane rolagem = new JScrollPane(lista);
        rolagem.setPreferredSize(new Dimension(400, 130)); // quem usa decide a altura; 2000 linhas rolam por dentro
        rolagem.setBorder(br.com.labdesktop.ui.componentes.Bordas.contorno(Tokens.LOGS_BORDA));
        rolagem.getViewport().setBackground(Tokens.cor(Tokens.LOGS_BG));
        mensagem.setBorder(BorderFactory.createEmptyBorder(24, 16, 24, 16));
        JPanel quadro = new Painel(new BorderLayout());
        quadro.add(mensagem, BorderLayout.CENTER);
        corpo.add(rolagem, CARTAO_DIFF);
        corpo.add(quadro, CARTAO_MENSAGEM);

        JPanel cima = new Painel(new BorderLayout(10, 0));
        cima.add(Caixa.linha(10, arquivo, contagem), BorderLayout.CENTER);
        cima.add(copiar, BorderLayout.EAST);
        JPanel topo = new Painel();
        topo.setLayout(new javax.swing.BoxLayout(topo, javax.swing.BoxLayout.Y_AXIS));
        cima.setAlignmentX(LEFT_ALIGNMENT);
        aviso.setAlignmentX(LEFT_ALIGNMENT);
        topo.add(cima);
        topo.add(aviso);
        add(topo, BorderLayout.NORTH);
        add(corpo, BorderLayout.CENTER);
        aviso.setVisible(false);
        vazio("Escolha um arquivo para ver o que mudou nele.");
    }

    /** Antes de escolher: a frase de apoio. */
    void vazio(String frase) {
        mostrarMensagem("", "", frase);
    }

    void carregando(String caminho) {
        mostrarMensagem(caminho, "", "Lendo o diff de " + caminho + "…");
    }

    void erro(String caminho, String frase) {
        mostrarMensagem(caminho, "", "Não consegui ler o diff: " + frase);
    }

    private void mostrarMensagem(String caminho, String conta, String frase) {
        atual = null;
        linhas.clear();
        arquivo.setText(caminho);
        arquivo.setToolTipText(caminho);
        contagem.setText(conta);
        aviso.setVisible(false);
        mensagem.setText(frase);
        textoParaCopiar = "";
        copiar.setEnabled(false);
        cartoes.show(corpo, CARTAO_MENSAGEM);
    }

    /** O diff de um arquivo. */
    void mostrar(String caminho, DiffDoArquivo d) {
        if (d.binario()) {
            mostrarMensagem(caminho, "", "arquivo binário: o app não mostra o conteúdo.");
            atual = d;
            return;
        }
        if (d.vazio() && d.linhas().stream().noneMatch(l -> l.tipo() != LinhaDeDiff.Tipo.INFORMACAO)) {
            mostrarMensagem(caminho, "", "Sem mudança de conteúdo neste arquivo" + (d.linhas().isEmpty() ? "." : " (" + d.linhas().get(0).texto() + ")."));
            atual = d;
            return;
        }
        atual = d;
        linhas.clear();
        linhas.addAll(d.linhas());
        arquivo.setText(caminho);
        arquivo.setToolTipText(caminho);
        contagem.setText("+" + d.acrescentadas() + " acrescentadas  -" + d.tiradas() + " tiradas");
        StringBuilder av = new StringBuilder();
        if (d.cortado()) {
            av.append("diff cortado: o app mostra só o começo (até ").append(br.com.labdesktop.git.InterpretadorDeDiff.LINHAS_POR_ARQUIVO).append(" linhas ou o que cabe na leitura). ");
        }
        if (d.outraCodificacao()) {
            av.append("O texto não está em UTF-8: foi lido como Latin-1. ");
        }
        aviso.setText(av.toString().strip());
        aviso.setVisible(av.length() > 0);
        textoParaCopiar = d.comoTexto();
        copiar.setEnabled(true);
        lista.ensureIndexIsVisible(0);
        cartoes.show(corpo, CARTAO_DIFF);
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------------------------------------

    /** A linha do diff: símbolo, um espaço e o texto, em mono, com o fundo do seu tipo. */
    private static final class Renderizador extends JLabel implements ListCellRenderer<LinhaDeDiff> {
        private static final long serialVersionUID = 1L;
        private Color fundo = Color.WHITE;

        @SuppressWarnings("this-escape")
        Renderizador() {
            setOpaque(false);
            putClientProperty("html.disable", Boolean.TRUE);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            Fontes.aplicar(this, Fontes.MONO, Font.PLAIN, 12f);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends LinhaDeDiff> l, LinhaDeDiff v, int i, boolean sel, boolean foco) {
            String simbolo = v.simbolo().equals("i") ? "#" : v.simbolo();
            setText(simbolo + " " + v.texto());
            setToolTipText(null);
            getAccessibleContext().setAccessibleName(v.palavra() + ": " + v.texto());
            Color base = Tokens.cor(Tokens.LOGS_BG);
            Color texto = Tokens.cor(Tokens.LOGS_TEXTO);
            switch (v.tipo()) {
                case ACRESCENTADA -> fundo = misturar(base, texto, 0.10);
                case TIRADA -> fundo = misturar(base, texto, 0.22);
                case TRECHO -> fundo = misturar(base, texto, 0.05);
                default -> fundo = base;
            }
            Color frente = v.tipo() == LinhaDeDiff.Tipo.CONTEXTO ? Tokens.cor(Tokens.LOGS_TEXTO2) : texto;
            setForeground(frente);
            Font f = Fontes.fonte(Fontes.MONO, Font.PLAIN, 12f);
            setFont(f.deriveFont(v.tipo() == LinhaDeDiff.Tipo.ACRESCENTADA || v.tipo() == LinhaDeDiff.Tipo.TIRADA ? Font.BOLD : v.tipo() == LinhaDeDiff.Tipo.TRECHO ? Font.ITALIC : Font.PLAIN));
            return this;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            g.setColor(fundo);
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
        }

        /** {@code a} com {@code t} (0 a 1) de {@code b}, opaca. */
        private static Color misturar(Color a, Color b, double t) {
            int r = (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t);
            int g = (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t);
            int bl = (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t);
            return new Color(r, g, bl);
        }
    }

    // ---- para os testes ----

    int quantasLinhas() {
        return linhas.size();
    }

    LinhaDeDiff linha(int i) {
        return linhas.get(i);
    }

    String textoDaMensagem() {
        return mensagem.getText();
    }

    boolean mostraDiff() {
        return linhas.size() > 0 && atual != null && !atual.binario();
    }

    String textoDoAviso() {
        return aviso.isVisible() ? aviso.getText() : "";
    }

    String textoDaContagem() {
        return contagem.getText();
    }

    String textoParaCopiar() {
        return textoParaCopiar;
    }

    Botao botaoCopiar() {
        return copiar;
    }

    JList<LinhaDeDiff> lista() {
        return lista;
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(200, 120);
    }
}
