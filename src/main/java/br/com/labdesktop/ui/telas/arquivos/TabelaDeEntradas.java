package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.arquivos.PermissoesEmTexto;
import br.com.labdesktop.arquivos.VisaoDaPasta.Coluna;
import br.com.labdesktop.arquivos.VisaoDaPasta.Ordem;
import br.com.labdesktop.arquivos.VisaoDaPasta.TiposDeArquivo;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * A tabela de itens da pasta (nome, tipo, tamanho, modificado, permissões e dono). Uma {@code JTable} de verdade porque uma pasta
 * pode ter 2000 itens: só as linhas à vista viram componentes. Texto do servidor sempre passa por {@link TextoSeguro} e nunca é
 * HTML. A lista é atualizada POR DIFERENÇA ({@link #definir}): a seleção, a linha com o foco e a posição da rolagem ficam onde o
 * usuário as deixou, quando o item continua na pasta.
 *
 * <p>Teclado: Enter abre, Backspace sobe uma pasta, F2 renomeia, Delete apaga. Duplo clique abre. Botão direito pede o menu.
 */
final class TabelaDeEntradas extends JTable {

    private static final long serialVersionUID = 1L;
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    /** Neste ano: dia, mês e hora ("24/10 18:05"); de outro ano: a data inteira ("24/10/2025"). A dica traz a data completa. */
    private static final DateTimeFormatter DATA_DESTE_ANO = DateTimeFormatter.ofPattern("dd/MM HH:mm", PT_BR);
    private static final DateTimeFormatter DATA_DE_OUTRO_ANO = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR);
    private static final DateTimeFormatter DATA_COMPLETA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", PT_BR);

    /** Quem age sobre a tabela (o gerenciador). */
    interface Ouvinte {
        void abrir(EntradaRemota entrada);

        void subir();

        void renomear();

        void apagar();

        void menu(Component origem, int x, int y);

        void ordenarPor(Coluna coluna);

        void selecaoMudou();
    }

    static final int COLUNA_NOME = 0;
    private static final String[] TITULOS = {"Nome", "Tipo", "Tamanho", "Modificado", "Permissões", "Dono"};
    private static final Coluna[] COLUNAS = Coluna.values();

    /** O modelo: a lista atual, imutável. Só a EDT troca. */
    private final class Modelo extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        List<EntradaRemota> linhas = List.of();

        @Override
        public int getRowCount() {
            return linhas.size();
        }

        @Override
        public int getColumnCount() {
            return TITULOS.length;
        }

        @Override
        public String getColumnName(int c) {
            return TITULOS[c];
        }

        @Override
        public Object getValueAt(int linha, int coluna) {
            return linhas.get(linha);
        }
    }

    private final Modelo modelo = new Modelo();
    private final Ouvinte ouvinte;
    private Ordem ordem = Ordem.PADRAO;
    private Instant agora = Instant.now();
    private ZoneId fuso = ZoneId.systemDefault();
    private final CelulaDeEntrada celula = new CelulaDeEntrada();
    private final CabecalhoDeColuna cabecalho = new CabecalhoDeColuna();

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    TabelaDeEntradas(Ouvinte ouvinte) {
        this.ouvinte = ouvinte;
        setModel(modelo);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setShowGrid(false);
        setShowHorizontalLines(true);
        setIntercellSpacing(new Dimension(0, 1));
        setFillsViewportHeight(true);
        setAutoResizeMode(AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        setAutoCreateRowSorter(false);
        setDropMode(DropMode.ON);
        putClientProperty("html.disable", Boolean.TRUE);
        getTableHeader().setReorderingAllowed(false);
        getTableHeader().setDefaultRenderer(cabecalho);
        getTableHeader().setPreferredSize(new Dimension(10, 34));
        getTableHeader().setToolTipText("Clique para ordenar");
        getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int c = getTableHeader().columnAtPoint(e.getPoint());
                if (c >= 0 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    ouvinte.ordenarPor(COLUNAS[convertColumnIndexToModel(c)]);
                }
            }
        });
        setDefaultRenderer(Object.class, celula);
        ajustarColunas();
        ajustarAlturaDaLinha();
        instalarTeclas();
        instalarMouse();
        getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ouvinte.selecaoMudou();
            }
        });
        getAccessibleContext().setAccessibleName("Arquivos e pastas");
    }

    // ---------------------------------------------------------------------------------------------
    // Dados
    // ---------------------------------------------------------------------------------------------

    /**
     * Troca a lista por diferença. Igual à de agora: nada acontece (nem repinta). Mesmos itens na mesma ordem: só as linhas que
     * mudaram são repintadas. Outra lista: troca tudo, mas devolve a seleção e o foco aos itens que continuam existindo.
     */
    void definir(List<EntradaRemota> nova) {
        List<EntradaRemota> atual = modelo.linhas;
        if (atual.equals(nova)) {
            return;
        }
        if (mesmosCaminhosNaMesmaOrdem(atual, nova)) {
            modelo.linhas = nova;
            for (int i = 0; i < nova.size(); i++) {
                if (!nova.get(i).equals(atual.get(i))) {
                    modelo.fireTableRowsUpdated(i, i);
                }
            }
            return;
        }
        Set<String> selecionados = new HashSet<>();
        for (EntradaRemota e : selecionadas()) {
            selecionados.add(e.caminho());
        }
        int lead = getSelectionModel().getLeadSelectionIndex();
        String caminhoDoFoco = lead >= 0 && lead < atual.size() ? atual.get(lead).caminho() : null;
        modelo.linhas = nova;
        modelo.fireTableDataChanged();
        getSelectionModel().setValueIsAdjusting(true);
        try {
            clearSelection();
            int novoLead = -1;
            for (int i = 0; i < nova.size(); i++) {
                String c = nova.get(i).caminho();
                if (selecionados.contains(c)) {
                    addRowSelectionInterval(i, i);
                }
                if (c.equals(caminhoDoFoco)) {
                    novoLead = i;
                }
            }
            if (novoLead >= 0) {
                getSelectionModel().setLeadSelectionIndex(novoLead);
                getColumnModel().getSelectionModel().setLeadSelectionIndex(0);
            }
        } finally {
            getSelectionModel().setValueIsAdjusting(false);
        }
    }

    private static boolean mesmosCaminhosNaMesmaOrdem(List<EntradaRemota> a, List<EntradaRemota> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).caminho().equals(b.get(i).caminho())) {
                return false;
            }
        }
        return true;
    }

    List<EntradaRemota> itens() {
        return modelo.linhas;
    }

    List<EntradaRemota> selecionadas() {
        List<EntradaRemota> l = new ArrayList<>();
        for (int linha : getSelectedRows()) {
            if (linha >= 0 && linha < modelo.linhas.size()) {
                l.add(modelo.linhas.get(linha));
            }
        }
        return l;
    }

    EntradaRemota entradaNaLinha(int linha) {
        return linha >= 0 && linha < modelo.linhas.size() ? modelo.linhas.get(linha) : null;
    }

    /** Seleciona o item com este caminho (e rola até ele); {@code false} se não está na lista. */
    boolean selecionarCaminho(String caminho) {
        for (int i = 0; i < modelo.linhas.size(); i++) {
            if (modelo.linhas.get(i).caminho().equals(caminho)) {
                setRowSelectionInterval(i, i);
                scrollRectToVisible(getCellRect(i, 0, true));
                return true;
            }
        }
        return false;
    }

    void definirOrdem(Ordem nova) {
        this.ordem = nova;
        getTableHeader().repaint();
    }

    Ordem ordem() {
        return ordem;
    }

    /** O relógio e o fuso das datas (os testes os fixam). */
    void relogio(Instant instante, ZoneId novoFuso) {
        this.agora = instante;
        this.fuso = novoFuso;
        repaint();
    }

    // ---------------------------------------------------------------------------------------------
    // Aparência
    // ---------------------------------------------------------------------------------------------

    private void ajustarColunas() {
        // somam 650: cabem na tabela de uma janela de 1100 (a página ao lado da barra de servidores, menos a árvore)
        int[] largura = {160, 104, 84, 100, 104, 92};
        int[] minima = {120, 60, 60, 84, 70, 60};
        for (int i = 0; i < largura.length; i++) {
            TableColumn c = getColumnModel().getColumn(i);
            c.setPreferredWidth(largura[i]);
            c.setMinWidth(minima[i]);
        }
    }

    private void ajustarAlturaDaLinha() {
        int h = getFontMetrics(getFont()).getHeight() + 14;
        setRowHeight(Math.max(32, h));
    }

    /** Refaz a altura da linha e das cores quando a fonte ou o tema mudam (o fator de texto do usuário, claro/escuro). */
    @Override
    public void updateUI() {
        super.updateUI();
        if (modelo != null && celula != null) {
            ajustarAlturaDaLinha();
            getTableHeader().setDefaultRenderer(cabecalho);
        }
    }

    @Override
    public void setFont(Font f) {
        super.setFont(f);
        if (modelo != null) {
            ajustarAlturaDaLinha();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Teclado e mouse
    // ---------------------------------------------------------------------------------------------

    private void instalarTeclas() {
        int cmd = br.com.labdesktop.ui.componentes.Teclas.comando();
        liga(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "abrir-entrada", () -> {
            List<EntradaRemota> s = selecionadas();
            if (!s.isEmpty()) {
                ouvinte.abrir(s.get(0));
            }
        });
        liga(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "subir-pasta", ouvinte::subir);
        liga(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "renomear-entrada", ouvinte::renomear);
        liga(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "apagar-entrada", ouvinte::apagar);
        liga(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, cmd), "apagar-entrada-cmd", ouvinte::apagar);
    }

    private void liga(KeyStroke tecla, String nome, Runnable acao) {
        getInputMap(WHEN_FOCUSED).put(tecla, nome);
        getActionMap().put(nome, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                acao.run();
            }
        });
    }

    private void instalarMouse() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    EntradaRemota entrada = entradaNaLinha(rowAtPoint(e.getPoint()));
                    if (entrada != null) {
                        ouvinte.abrir(entrada);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                talvezMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                talvezMenu(e);
            }

            private void talvezMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int linha = rowAtPoint(e.getPoint());
                if (linha >= 0 && !isRowSelected(linha)) {
                    setRowSelectionInterval(linha, linha);
                } else if (linha < 0) {
                    clearSelection();
                }
                requestFocusInWindow();
                ouvinte.menu(TabelaDeEntradas.this, e.getX(), e.getY());
            }
        });
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int linha = rowAtPoint(e.getPoint());
        int coluna = columnAtPoint(e.getPoint());
        EntradaRemota entrada = entradaNaLinha(linha);
        if (entrada == null || coluna < 0) {
            return null;
        }
        return switch (convertColumnIndexToModel(coluna)) {
            case 0 -> entrada.link() && entrada.destinoDoLink() != null
                ? TextoSeguro.limparLinha(entrada.nome()) + " (link para " + TextoSeguro.limparLinha(entrada.destinoDoLink()) + ")"
                : TextoSeguro.limparLinha(entrada.nome());
            case 3 -> entrada.modificadoEm() == null ? null : DATA_COMPLETA.format(entrada.modificadoEm().atZone(fuso)) + " ("
                + Formato.fmtHa(entrada.modificadoEm(), agora) + ")";
            case 4 -> entrada.permissoes() < 0 ? null : PermissoesEmTexto.explicacao(entrada.permissoes()) + " ("
                + PermissoesEmTexto.tecnico(entrada.permissoes()) + ", " + PermissoesEmTexto.octal(entrada.permissoes()) + ")";
            case 5 -> entrada.grupo().isEmpty() ? null : "Grupo: " + TextoSeguro.limparLinha(entrada.grupo());
            default -> null;
        };
    }

    /** O texto de cada coluna, para a tela e para os testes. */
    String textoDaCelula(EntradaRemota e, int coluna) {
        return switch (coluna) {
            case 0 -> TextoSeguro.limparLinha(e.nome());
            case 1 -> TiposDeArquivo.rotulo(e);
            case 2 -> e.ehPasta() || e.tamanho() < 0 ? "" : Formato.fmtBytes(e.tamanho());
            case 3 -> e.modificadoEm() == null ? "" : (e.modificadoEm().atZone(fuso).getYear() == agora.atZone(fuso).getYear()
                ? DATA_DESTE_ANO : DATA_DE_OUTRO_ANO).format(e.modificadoEm().atZone(fuso));
            case 4 -> PermissoesEmTexto.simples(e.permissoes());
            case 5 -> TextoSeguro.limparLinha(e.dono());
            default -> "";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Células
    // ---------------------------------------------------------------------------------------------

    /**
     * Os ícones das linhas são sempre os mesmos poucos: guardá-los poupa criar um por célula a cada pintura. Seguro entre temas,
     * porque o ícone lê a cor do token na hora de pintar ({@link Icones#colorido}).
     */
    private static final java.util.Map<String, javax.swing.Icon> ICONES = new java.util.concurrent.ConcurrentHashMap<>();

    private static javax.swing.Icon iconeDe(String nome, String token) {
        return ICONES.computeIfAbsent(nome + "|" + token, k -> Icones.colorido(nome, 16, token));
    }

    private final class CelulaDeEntrada extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("this-escape")
        CelulaDeEntrada() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object valor, boolean selecionada, boolean foco, int linha, int coluna) {
            super.getTableCellRendererComponent(t, "", selecionada, false, linha, coluna);
            EntradaRemota e = (EntradaRemota) valor;
            int modeloDaColuna = t.convertColumnIndexToModel(coluna);
            setText(textoDaCelula(e, modeloDaColuna));
            setHorizontalAlignment(modeloDaColuna == 2 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setIcon(null);
            Color cor = selecionada ? t.getSelectionForeground() : Tokens.cor(modeloDaColuna == 0 ? Tokens.TEXTO : Tokens.TEXTO2);
            if (!selecionada && (e.oculto() || e.tipo() == EntradaRemota.Tipo.LINK_QUEBRADO)) {
                cor = Tokens.cor(Tokens.TEXTO3);
            }
            setForeground(cor);
            if (modeloDaColuna == 0) {
                String icone = e.tipo() == EntradaRemota.Tipo.LINK_QUEBRADO ? "alerta" : e.ehPasta() ? "pasta" : e.link() ? "link" : "arquivo";
                String token = selecionada ? Tokens.TEXTO : e.ehPasta() ? Tokens.DESTAQUE : Tokens.TEXTO3;
                setIcon(iconeDe(icone, e.tipo() == EntradaRemota.Tipo.LINK_QUEBRADO ? Tokens.ATENCAO : token));
                setIconTextGap(8);
            }
            return this;
        }
    }

    private final class CabecalhoDeColuna implements TableCellRenderer {
        private final Rotulo rotulo = new Rotulo().corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f);

        CabecalhoDeColuna() {
            rotulo.setBorder(BorderFactory.createCompoundBorder(
                br.com.labdesktop.ui.componentes.Bordas.lados(0, 0, 1, 0, Tokens.BORDA),
                BorderFactory.createEmptyBorder(0, 8, 0, 8)));
            rotulo.setOpaque(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int linha, int coluna) {
            int m = t.convertColumnIndexToModel(coluna);
            boolean ativa = ordem.coluna() == COLUNAS[m];
            rotulo.setText(TITULOS[m].toUpperCase(PT_BR));
            rotulo.corDoToken(ativa ? Tokens.TEXTO : Tokens.TEXTO3);
            rotulo.setHorizontalAlignment(m == 2 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            rotulo.setHorizontalTextPosition(SwingConstants.LEADING);
            rotulo.setIconTextGap(4);
            rotulo.setIcon(ativa ? Icones.colorido(ordem.crescente() ? "seta-cima" : "seta-baixo", 12, Tokens.TEXTO) : null);
            rotulo.setBorder(BorderFactory.createCompoundBorder(
                br.com.labdesktop.ui.componentes.Bordas.lados(0, 0, 1, 0, Tokens.BORDA),
                BorderFactory.createEmptyBorder(0, 8, 0, 8)));
            rotulo.setOpaque(true);
            rotulo.setBackground(Tokens.cor(Tokens.BG));
            return rotulo;
        }
    }
}
