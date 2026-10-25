package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Uma tabela de texto que NUNCA cresce sem limite: mostra uma página de {@code porPagina} linhas, com "Anterior",
 * "Próxima" e "mostrando 1-15 de N" embaixo. Filtro opcional (um {@link Predicate} que a tela troca; mudar o filtro volta à
 * página 1) e ordenação opcional (clicar no cabeçalho de uma coluna que tem comparador; o cabeçalho é um botão, então
 * funciona pelo teclado). Sem itens, mostra a frase de "vazio". Reutilizável: quem usa só diz como tirar o texto de cada
 * coluna do item.
 *
 * <p>Atualizar os itens (o polling) mantém a página em que o usuário estava, se ela ainda existe. Linhas "em destaque" saem em
 * negrito e na cor de atenção (e quem usa deve escrever o motivo em alguma coluna: destaque nunca é só cor).
 *
 * @param <T> o item de uma linha
 */
public final class TabelaPaginada<T> extends Painel {

    private static final long serialVersionUID = 1L;

    /**
     * Uma coluna.
     *
     * @param ordem comparador da coluna, ou {@code null} se ela não ordena (a primeira ordenação de uma coluna numérica é
     *              do maior para o menor; as outras, de A a Z)
     */
    public record Coluna<T>(String titulo, double peso, boolean numerica, boolean mono, Function<T, String> texto,
                            Comparator<T> ordem) {

        public static <T> Coluna<T> texto(String titulo, double peso, Function<T, String> texto) {
            return new Coluna<>(titulo, peso, false, false, texto, null);
        }
    }

    private final int porPagina;
    private final List<Coluna<T>> colunas;
    private final Rotulo vazio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12.5f);
    private final JPanel grade = new Painel(new GridBagLayout());
    private final JPanel rodape = new Painel(new BorderLayout());
    private final Rotulo resumo = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f);
    private final Rotulo posicao = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f);
    private final Botao anterior = Botoes.mini("Anterior", "voltar");
    private final Botao proxima = Botoes.mini("Próxima", "avancar");
    private final java.util.Map<Integer, JButton> cabecalhos = new java.util.HashMap<>();

    private List<T> todos = List.of();
    private List<T> visiveis = List.of();
    private Predicate<T> filtro = t -> true;
    private Predicate<T> destaque = t -> false;
    private String mensagemVazia = "";
    private String mensagemFiltroVazio = "Nada encontrado com este filtro.";
    private String nota = "";
    private int pagina = 1;
    private int colunaOrdenada = -1;
    private boolean crescente = true;
    private List<List<String>> linhasDaPagina = List.of();

    public TabelaPaginada(int porPagina, List<Coluna<T>> colunas) {
        super(new BorderLayout(0, 8));
        if (porPagina < 1) {
            throw new IllegalArgumentException("porPagina");
        }
        this.porPagina = porPagina;
        this.colunas = List.copyOf(colunas);
        anterior.addActionListener(e -> irParaPagina(pagina - 1));
        proxima.addActionListener(e -> irParaPagina(pagina + 1));
        anterior.getAccessibleContext().setAccessibleName("Página anterior");
        proxima.getAccessibleContext().setAccessibleName("Próxima página");
        proxima.setHorizontalTextPosition(SwingConstants.LEADING);
        JPanel botoes = Caixa.linha(8, anterior, posicao, proxima);
        rodape.add(Caixa.linha(0, resumo, Caixa.mola()), BorderLayout.CENTER);
        rodape.add(botoes, BorderLayout.EAST);
        add(grade, BorderLayout.CENTER);
        add(rodape, BorderLayout.SOUTH);
        desenhar();
    }

    // ---------------------------------------------------------------------------------------------
    // Dados, filtro, ordem
    // ---------------------------------------------------------------------------------------------

    /** Troca os itens (mantém a página, se ainda existir) e a frase de "vazio" de quando não há nenhum. */
    public void definir(List<T> itens, String mensagemQuandoVazio) {
        this.todos = List.copyOf(itens);
        this.mensagemVazia = mensagemQuandoVazio == null ? "" : mensagemQuandoVazio;
        recalcular(false);
    }

    /**
     * Troca os itens E o filtro de uma vez, mantendo a página (se ainda existir): é o que o polling usa, para a tabela não
     * voltar à página 1 a cada atualização. {@link #filtrar} é o do clique do usuário, que volta à página 1.
     */
    public void atualizar(List<T> itens, Predicate<T> novoFiltro, String mensagemQuandoVazio) {
        this.filtro = novoFiltro == null ? t -> true : novoFiltro;
        definir(itens, mensagemQuandoVazio);
    }

    /** Troca o filtro e VOLTA À PÁGINA 1. {@code null} tira o filtro. */
    public void filtrar(Predicate<T> novo) {
        this.filtro = novo == null ? t -> true : novo;
        recalcular(true);
    }

    /** As linhas que casam saem em negrito e na cor de atenção. */
    public void destacar(Predicate<T> quais) {
        this.destaque = quais == null ? t -> false : quais;
        desenhar();
    }

    /** Um aviso no rodapé ("mostrando os 500 mais recentes"). Vazio, some. */
    public void nota(String texto) {
        this.nota = texto == null ? "" : texto;
        desenhar();
    }

    public void mensagemDoFiltroVazio(String texto) {
        this.mensagemFiltroVazio = texto;
    }

    /** Ordena pela coluna (a mesma coluna de novo inverte); {@code coluna} sem comparador é ignorada. */
    public void ordenarPor(int coluna) {
        if (coluna < 0 || coluna >= colunas.size() || colunas.get(coluna).ordem() == null) {
            return;
        }
        if (coluna == colunaOrdenada) {
            crescente = !crescente;
        } else {
            colunaOrdenada = coluna;
            crescente = !colunas.get(coluna).numerica();
        }
        recalcular(true);
    }

    private void recalcular(boolean voltarAoInicio) {
        List<T> v = new ArrayList<>();
        for (T t : todos) {
            if (filtro.test(t)) {
                v.add(t);
            }
        }
        if (colunaOrdenada >= 0) {
            Comparator<T> c = colunas.get(colunaOrdenada).ordem();
            v.sort(crescente ? c : c.reversed());
        }
        this.visiveis = v;
        this.pagina = voltarAoInicio ? 1 : Math.max(1, Math.min(pagina, totalDePaginas()));
        desenhar();
    }

    // ---------------------------------------------------------------------------------------------
    // Paginação
    // ---------------------------------------------------------------------------------------------

    /** Vai para a página (de 1 a {@link #totalDePaginas()}); fora disso, limita. */
    public void irParaPagina(int destino) {
        int nova = Math.max(1, Math.min(destino, totalDePaginas()));
        if (nova != pagina) {
            pagina = nova;
            desenhar();
        }
    }

    public int pagina() {
        return pagina;
    }

    public int totalDePaginas() {
        return Math.max(1, (visiveis.size() + porPagina - 1) / porPagina);
    }

    /** Quantos itens passam pelo filtro. */
    public int totalFiltrado() {
        return visiveis.size();
    }

    /** Quantos itens a tabela tem, sem filtro. */
    public int total() {
        return todos.size();
    }

    public int porPagina() {
        return porPagina;
    }

    /** "mostrando 1-15 de 42", ou vazio sem itens. */
    public String textoDoResumo() {
        if (visiveis.isEmpty()) {
            return "";
        }
        int de = (pagina - 1) * porPagina + 1;
        int ate = Math.min(visiveis.size(), pagina * porPagina);
        return "mostrando " + de + "-" + ate + " de " + visiveis.size();
    }

    /** O texto das linhas da página atual, na ordem da tela. */
    public List<List<String>> linhasDaPagina() {
        return linhasDaPagina;
    }

    public int colunaOrdenada() {
        return colunaOrdenada;
    }

    public boolean crescente() {
        return crescente;
    }

    public Botao botaoAnterior() {
        return anterior;
    }

    public Botao botaoProxima() {
        return proxima;
    }

    /** O botão do cabeçalho da coluna (só existe nas colunas que ordenam; senão {@code null}). */
    public JButton botaoDoCabecalho(int coluna) {
        return cabecalhos.get(coluna);
    }

    public boolean rodapeVisivel() {
        return rodape.isVisible();
    }

    public String textoDaNota() {
        return nota;
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    private void desenhar() {
        grade.removeAll();
        cabecalhos.clear();
        List<T> pag = visiveis.subList(Math.min((pagina - 1) * porPagina, visiveis.size()), Math.min(pagina * porPagina, visiveis.size()));
        List<List<String>> textos = new ArrayList<>();
        int y = 0;
        if (pag.isEmpty()) {
            vazio.setText(todos.isEmpty() ? mensagemVazia : mensagemFiltroVazio);
            GridBagConstraints g = restricoes(0, y, colunas.size(), 0);
            grade.add(vazio, g);
        } else {
            for (int i = 0; i < colunas.size(); i++) {
                Coluna<T> c = colunas.get(i);
                JComponent cab = c.ordem() == null ? cabecalhoSimples(c) : cabecalhoOrdenavel(c, i);
                GridBagConstraints g = restricoes(i, y, 1, c.peso());
                g.insets = new Insets(0, i == 0 ? 0 : 12, 6, 0);
                grade.add(cab, g);
            }
            y++;
            for (T item : pag) {
                grade.add(new Traco(), restricoes(0, y++, colunas.size(), 0));
                boolean marca = destaque.test(item);
                List<String> linha = new ArrayList<>();
                for (int i = 0; i < colunas.size(); i++) {
                    Coluna<T> c = colunas.get(i);
                    String texto = c.texto().apply(item);
                    if (texto == null || texto.isEmpty()) {
                        texto = "—";
                    }
                    linha.add(texto);
                    Rotulo r = new Rotulo(texto).truncavel();
                    if (c.mono()) {
                        r.mono(11.5f);
                    } else {
                        r.tamanho(12.5f);
                    }
                    if (marca) {
                        r.negrito().corDoToken(Tokens.ATENCAO);
                    }
                    r.setToolTipText(texto);
                    r.setHorizontalAlignment(c.numerica() ? SwingConstants.RIGHT : SwingConstants.LEFT);
                    GridBagConstraints g = restricoes(i, y, 1, c.peso());
                    g.insets = new Insets(3, i == 0 ? 0 : 12, 3, 0);
                    grade.add(r, g);
                }
                textos.add(List.copyOf(linha));
                y++;
            }
        }
        this.linhasDaPagina = List.copyOf(textos);

        int paginas = totalDePaginas();
        resumo.setText((textoDoResumo() + (nota.isEmpty() ? "" : (visiveis.isEmpty() ? "" : " · ") + nota)));
        posicao.setText("Página " + pagina + " de " + paginas);
        anterior.setEnabled(pagina > 1);
        proxima.setEnabled(pagina < paginas);
        anterior.setVisible(paginas > 1);
        proxima.setVisible(paginas > 1);
        posicao.setVisible(paginas > 1);
        rodape.setVisible(paginas > 1 || !nota.isEmpty());
        grade.revalidate();
        grade.repaint();
        revalidate();
        repaint();
    }

    private static Rotulo cabecalhoSimples(Coluna<?> c) {
        Rotulo r = new Rotulo(c.titulo().toUpperCase(Locale.forLanguageTag("pt-BR"))).corDoToken(Tokens.TEXTO3).negrito()
            .tamanho(10.5f).truncavel();
        r.setHorizontalAlignment(c.numerica() ? SwingConstants.RIGHT : SwingConstants.LEFT);
        return r;
    }

    private JButton cabecalhoOrdenavel(Coluna<T> c, int indice) {
        boolean ativa = indice == colunaOrdenada;
        JButton b = new JButton(c.titulo().toUpperCase(Locale.forLanguageTag("pt-BR"))) {
            private static final long serialVersionUID = 1L;

            @Override
            public Color getForeground() {
                return Tokens.cor(ativa ? Tokens.TEXTO : Tokens.TEXTO3);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, super.getMinimumSize().height);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(Tokens.cor(Tokens.FOCO));
                        g2.setStroke(new java.awt.BasicStroke(2f));
                        g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
                    } finally {
                        g2.dispose();
                    }
                }
            }
        };
        b.putClientProperty("html.disable", Boolean.TRUE);
        b.setFont(b.getFont().deriveFont(java.awt.Font.BOLD, 10.5f));
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 2, 2));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.setHorizontalAlignment(c.numerica() ? SwingConstants.RIGHT : SwingConstants.LEFT);
        b.setHorizontalTextPosition(SwingConstants.LEADING);
        b.setIconTextGap(4);
        if (ativa) {
            b.setIcon(Icones.colorido(crescente ? "seta-cima" : "seta-baixo", 12, Tokens.TEXTO));
        }
        b.setToolTipText("Ordenar por " + c.titulo().toLowerCase(Locale.forLanguageTag("pt-BR"))
            + (ativa ? (crescente ? " (do menor para o maior)" : " (do maior para o menor)") : ""));
        b.getAccessibleContext().setAccessibleName("Ordenar por " + c.titulo()
            + (ativa ? (crescente ? ", crescente" : ", decrescente") : ""));
        b.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                b.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                b.repaint();
            }
        });
        b.addActionListener(e -> ordenarPor(indice));
        cabecalhos.put(indice, b);
        return b;
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

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** O traço fino entre linhas. */
    private static final class Traco extends JComponent {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Tokens.cor(Tokens.BORDA_SUTIL));
            g.fillRect(0, 0, getWidth(), 1);
        }
    }
}
