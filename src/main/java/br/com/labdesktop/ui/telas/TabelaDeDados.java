package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.Bordas;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * Uma tabela SÓ de leitura, de uma linha selecionável por vez, com colunas dadas por funções (a aba Git a usa para commits, arquivos de um commit, alterações e branches). O texto de cada
 * célula é texto (nunca HTML) e a cor vem dos tokens a cada pintura. As larguras são pesos: a coluna que sobra (peso maior) absorve a largura da janela. A dica de uma linha traz o texto inteiro.
 *
 * @param <T> o dado de uma linha
 */
final class TabelaDeDados<T> extends JTable {

    private static final long serialVersionUID = 1L;

    /** Uma coluna: o título, o peso da largura, se o texto vai à direita, e como tirar o texto (e a dica) da linha. */
    record Coluna<T>(String titulo, double peso, boolean direita, Function<T, String> texto, Function<T, String> dica) {

        Coluna(String titulo, double peso, boolean direita, Function<T, String> texto) {
            this(titulo, peso, direita, texto, texto);
        }
    }

    private final class Modelo extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        List<T> linhas = List.of();

        @Override
        public int getRowCount() {
            return linhas.size();
        }

        @Override
        public int getColumnCount() {
            return colunas.size();
        }

        @Override
        public String getColumnName(int c) {
            return colunas.get(c).titulo();
        }

        @Override
        public Object getValueAt(int linha, int coluna) {
            return linhas.get(linha);
        }
    }

    private final transient List<Coluna<T>> colunas;
    private final Modelo modelo = new Modelo();
    private final transient Consumer<T> aoSelecionar;
    private final transient Celula celula = new Celula();
    private boolean mudandoPorCodigo;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    TabelaDeDados(String nomeAcessivel, List<Coluna<T>> colunas, Consumer<T> aoSelecionar) {
        this.colunas = List.copyOf(colunas);
        this.aoSelecionar = aoSelecionar;
        setModel(modelo);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setShowGrid(false);
        setShowHorizontalLines(true);
        setIntercellSpacing(new Dimension(0, 1));
        setFillsViewportHeight(true);
        setAutoResizeMode(AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        setAutoCreateRowSorter(false);
        setRowHeight(28);
        putClientProperty("html.disable", Boolean.TRUE);
        getTableHeader().setReorderingAllowed(false);
        getTableHeader().setDefaultRenderer(new Cabecalho());
        getTableHeader().setPreferredSize(new Dimension(10, 30));
        setDefaultRenderer(Object.class, celula);
        getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !mudandoPorCodigo && aoSelecionar != null) {
                int l = getSelectedRow();
                if (l >= 0 && l < modelo.linhas.size()) {
                    aoSelecionar.accept(modelo.linhas.get(l));
                }
            }
        });
        getAccessibleContext().setAccessibleName(nomeAcessivel);
        ajustarLarguras();
    }

    /** A tabela numa área de rolagem com o cabeçalho das colunas (sem esperar o addNotify, para o cabeçalho aparecer também fora de janela) e a moldura do tema. */
    javax.swing.JScrollPane emRolagem() {
        javax.swing.JScrollPane r = new javax.swing.JScrollPane(this);
        r.setColumnHeaderView(getTableHeader());
        r.setBorder(Bordas.contorno(Tokens.BORDA));
        r.getViewport().setOpaque(true);
        return r;
    }

    /** Troca as linhas mantendo a seleção no mesmo dado, se ele continua na lista (a comparação é por {@code equals}). Não dispara o ouvinte de seleção. */
    void definir(List<T> novas) {
        T selecionado = selecionado();
        mudandoPorCodigo = true;
        try {
            modelo.linhas = List.copyOf(novas);
            modelo.fireTableDataChanged();
            clearSelection();
            if (selecionado != null) {
                int i = modelo.linhas.indexOf(selecionado);
                if (i >= 0) {
                    setRowSelectionInterval(i, i);
                }
            }
        } finally {
            mudandoPorCodigo = false;
        }
        ajustarLarguras();
    }

    List<T> linhas() {
        return new ArrayList<>(modelo.linhas);
    }

    T selecionado() {
        int l = getSelectedRow();
        return l >= 0 && l < modelo.linhas.size() ? modelo.linhas.get(l) : null;
    }

    /** Seleciona a linha {@code i} COM o aviso ao ouvinte (o que um clique faria). */
    void selecionarLinha(int i) {
        if (i >= 0 && i < modelo.linhas.size()) {
            setRowSelectionInterval(i, i);
        }
    }

    /** Seleciona a linha sem avisar ninguém. */
    void marcarLinha(int i) {
        mudandoPorCodigo = true;
        try {
            if (i >= 0 && i < modelo.linhas.size()) {
                setRowSelectionInterval(i, i);
            } else {
                clearSelection();
            }
        } finally {
            mudandoPorCodigo = false;
        }
    }

    /** O texto de uma célula como a tela o mostra (para os testes). */
    String textoDa(int linha, int coluna) {
        return colunas.get(coluna).texto().apply(modelo.linhas.get(linha));
    }

    private void ajustarLarguras() {
        double total = colunas.stream().mapToDouble(Coluna::peso).sum();
        int largura = Math.max(getWidth(), 640);
        for (int i = 0; i < colunas.size() && i < getColumnModel().getColumnCount(); i++) {
            getColumnModel().getColumn(i).setPreferredWidth((int) Math.round(largura * colunas.get(i).peso() / total));
        }
    }

    @Override
    public void doLayout() {
        ajustarLarguras();
        super.doLayout();
    }

    private final class Celula extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("this-escape")
        Celula() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getTableCellRendererComponent(JTable t, Object valor, boolean selecionada, boolean foco, int linha, int coluna) {
            super.getTableCellRendererComponent(t, "", selecionada, false, linha, coluna);
            T dado = (T) valor;
            Coluna<T> c = colunas.get(t.convertColumnIndexToModel(coluna));
            setText(c.texto().apply(dado));
            String dica = c.dica() == null ? null : c.dica().apply(dado);
            setToolTipText(dica == null || dica.isEmpty() ? null : dica);
            setHorizontalAlignment(c.direita() ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            Color cor = selecionada ? t.getSelectionForeground() : Tokens.cor(coluna == 0 || coluna == 1 ? Tokens.TEXTO : Tokens.TEXTO2);
            setForeground(cor);
            return this;
        }
    }

    private final class Cabecalho implements TableCellRenderer {
        private final Rotulo rotulo = new Rotulo().corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f);

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int linha, int coluna) {
            Coluna<T> c = colunas.get(t.convertColumnIndexToModel(coluna));
            rotulo.setText(c.titulo().toUpperCase(java.util.Locale.forLanguageTag("pt-BR")));
            rotulo.setHorizontalAlignment(c.direita() ? SwingConstants.RIGHT : SwingConstants.LEFT);
            rotulo.setBorder(BorderFactory.createCompoundBorder(Bordas.lados(0, 0, 1, 0, Tokens.BORDA), BorderFactory.createEmptyBorder(0, 8, 0, 8)));
            rotulo.setOpaque(true);
            rotulo.setBackground(Tokens.cor(Tokens.BG));
            return rotulo;
        }
    }
}
