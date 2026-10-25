package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.VisaoDaPasta;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * A árvore de pastas à esquerda, com CARGA PREGUIÇOSA: a pasta só é lida do servidor quando o usuário a abre (a seta ou o clique), e
 * só entram as PASTAS (links não: um link para pasta poderia fazer a árvore girar em círculo ou sair da raiz do modo). Cada pasta
 * ainda não lida mostra "Carregando…" até a resposta chegar; pasta sem permissão mostra a frase simples no lugar dos filhos.
 *
 * <p>Quem lê do servidor é o gerenciador ({@link Carregador}); a árvore só desenha o resultado. Escolher uma pasta com o mouse ou
 * o teclado avisa o gerenciador; escolher uma pasta POR PROGRAMA ({@link #revelar}) não avisa, para não dar uma volta.
 */
final class ArvoreDePastas extends JTree {

    private static final long serialVersionUID = 1L;

    /** O que a árvore pede ao gerenciador. */
    interface Carregador {
        /** Lê as subpastas de uma pasta (fora da EDT) e entrega o resultado na EDT. */
        void carregar(String caminho, Consumer<List<EntradaRemota>> aoChegar, Consumer<ErroSftp> aoFalhar);

        /** O usuário escolheu esta pasta. */
        void escolheuPasta(String caminho);
    }

    /** O dado de um nó. */
    record NoDePasta(String caminho, String rotulo) {
        @Override
        public String toString() {
            return rotulo;
        }
    }

    /** O dado do nó provisório ("Carregando…") e o de aviso (pasta que não abriu). */
    private record Aviso(String texto, boolean carregando) {
        @Override
        public String toString() {
            return texto;
        }
    }

    private final Carregador carregador;
    private final DefaultTreeModel modelo = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final Map<String, DefaultMutableTreeNode> nos = new HashMap<>();
    private final Set<String> carregados = new HashSet<>();
    /** Pastas que estavam abertas quando a árvore foi relida e que voltam abertas. */
    private final Set<String> reabrir = new HashSet<>();
    private final Map<String, List<Runnable>> emCarga = new HashMap<>();
    private boolean programatico;
    private boolean mostrarOcultos;
    private long geracao;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    ArvoreDePastas(Carregador carregador) {
        this.carregador = carregador;
        setModel(modelo);
        setRootVisible(true);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setDropMode(DropMode.ON);
        setToggleClickCount(2);
        putClientProperty("html.disable", Boolean.TRUE);
        setCellRenderer(new Celula());
        ajustarAltura();
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                Object o = e.getPath().getLastPathComponent();
                if (o instanceof DefaultMutableTreeNode no && no.getUserObject() instanceof NoDePasta p) {
                    garantirCarregada(p.caminho(), () -> { });
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) { }
        });
        addTreeSelectionListener(e -> {
            if (programatico || e.getNewLeadSelectionPath() == null) {
                return;
            }
            Object o = e.getNewLeadSelectionPath().getLastPathComponent();
            if (o instanceof DefaultMutableTreeNode no && no.getUserObject() instanceof NoDePasta p) {
                carregador.escolheuPasta(p.caminho());
            }
        });
        getAccessibleContext().setAccessibleName("Pastas do servidor");
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    /** Recomeça a árvore a partir de uma raiz (trocou o modo ou a raiz do servidor). */
    void definirRaiz(String caminho, String rotulo) {
        geracao++;
        nos.clear();
        carregados.clear();
        emCarga.clear();
        DefaultMutableTreeNode raiz = novoNo(caminho, rotulo);
        modelo.setRoot(raiz);
        nos.put(caminho, raiz);
        garantirCarregada(caminho, () -> expandirNo(raiz));
    }

    void mostrarOcultos(boolean sim) {
        this.mostrarOcultos = sim;
    }

    private DefaultMutableTreeNode novoNo(String caminho, String rotulo) {
        DefaultMutableTreeNode no = new DefaultMutableTreeNode(new NoDePasta(caminho, rotulo));
        no.add(new DefaultMutableTreeNode(new Aviso("Carregando…", true)));
        return no;
    }

    private void expandirNo(DefaultMutableTreeNode no) {
        boolean antes = programatico;
        programatico = true;
        try {
            expandPath(new TreePath(no.getPath()));
        } finally {
            programatico = antes;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Carga preguiçosa
    // ---------------------------------------------------------------------------------------------

    private void garantirCarregada(String caminho, Runnable depois) {
        if (carregados.contains(caminho)) {
            depois.run();
            return;
        }
        List<Runnable> fila = emCarga.get(caminho);
        if (fila != null) {
            fila.add(depois);
            return;
        }
        fila = new ArrayList<>();
        fila.add(depois);
        emCarga.put(caminho, fila);
        long minha = geracao;
        carregador.carregar(caminho, filhos -> {
            if (minha != geracao) {
                return; // a árvore foi refeita enquanto isso
            }
            preencher(caminho, filhos);
            terminar(caminho);
        }, erro -> {
            if (minha != geracao) {
                return;
            }
            DefaultMutableTreeNode no = nos.get(caminho);
            if (no != null) {
                no.removeAllChildren();
                no.add(new DefaultMutableTreeNode(new Aviso(erro.mensagemSimples(), false)));
                modelo.nodeStructureChanged(no);
            }
            carregados.add(caminho); // não insiste sozinha: o usuário fecha e abre a pasta para tentar de novo
            terminar(caminho);
        });
    }

    private void terminar(String caminho) {
        List<Runnable> fila = emCarga.remove(caminho);
        if (fila != null) {
            fila.forEach(Runnable::run);
        }
    }

    private void preencher(String caminho, List<EntradaRemota> entradas) {
        DefaultMutableTreeNode no = nos.get(caminho);
        if (no == null) {
            return;
        }
        // filhos antigos saem do índice (os que continuarem existindo voltam abaixo)
        for (int i = 0; i < no.getChildCount(); i++) {
            if (((DefaultMutableTreeNode) no.getChildAt(i)).getUserObject() instanceof NoDePasta p) {
                removerDoIndice(p.caminho());
            }
        }
        no.removeAllChildren();
        List<EntradaRemota> pastas = new ArrayList<>();
        for (EntradaRemota e : entradas) {
            if (e.ehPasta() && !e.link() && (mostrarOcultos || !e.oculto())) {
                pastas.add(e);
            }
        }
        pastas.sort((a, b) -> VisaoDaPasta.compararNomes(a.nome(), b.nome()));
        for (EntradaRemota e : pastas) {
            DefaultMutableTreeNode filho = novoNo(e.caminho(), TextoSeguro.limparLinha(e.nome()));
            no.add(filho);
            nos.put(e.caminho(), filho);
        }
        carregados.add(caminho);
        modelo.nodeStructureChanged(no);
        // numa releitura, as pastas que estavam abertas voltam abertas (e, abrindo, leem os próprios filhos)
        for (EntradaRemota e : pastas) {
            if (reabrir.remove(e.caminho())) {
                DefaultMutableTreeNode filho = nos.get(e.caminho());
                garantirCarregada(e.caminho(), () -> expandirNo(filho));
            }
        }
    }

    private void removerDoIndice(String caminho) {
        DefaultMutableTreeNode no = nos.remove(caminho);
        carregados.remove(caminho);
        if (no != null) {
            for (int i = 0; i < no.getChildCount(); i++) {
                if (((DefaultMutableTreeNode) no.getChildAt(i)).getUserObject() instanceof NoDePasta p) {
                    removerDoIndice(p.caminho());
                }
            }
        }
    }

    /** Lê de novo as pastas abertas (o botão Atualizar, ou mudou a chave de mostrar ocultos). */
    void recarregar() {
        DefaultMutableTreeNode raiz = (DefaultMutableTreeNode) modelo.getRoot();
        if (!(raiz.getUserObject() instanceof NoDePasta r)) {
            return;
        }
        reabrir.clear();
        for (Enumeration<TreePath> e = getExpandedDescendants(new TreePath(raiz.getPath())); e != null && e.hasMoreElements();) {
            Object o = e.nextElement().getLastPathComponent();
            if (o instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof NoDePasta p) {
                reabrir.add(p.caminho());
            }
        }
        carregados.remove(r.caminho());
        garantirCarregada(r.caminho(), () -> expandirNo(raiz));
    }

    // ---------------------------------------------------------------------------------------------
    // Seleção por programa
    // ---------------------------------------------------------------------------------------------

    /**
     * Abre os ancestrais (lendo o que ainda não foi lido) e seleciona a pasta, sem avisar o gerenciador. Se a pasta não está na
     * árvore (oculta, ou nova demais), seleciona a mais funda que está.
     */
    void revelar(String caminho) {
        long minha = geracao;
        avancarAte(caminho, minha);
    }

    private void avancarAte(String alvo, long minha) {
        if (minha != geracao) {
            return;
        }
        String melhor = null;
        for (String c = alvo;; c = CaminhoRemoto.pai(c)) {
            if (nos.containsKey(c)) {
                melhor = c;
                break;
            }
            if (c.equals("/")) {
                break;
            }
        }
        if (melhor == null) {
            return;
        }
        DefaultMutableTreeNode no = nos.get(melhor);
        if (melhor.equals(alvo) || carregados.contains(melhor)) {
            selecionarNo(no);
            return;
        }
        garantirCarregada(melhor, () -> {
            expandirNo(no);
            avancarAte(alvo, minha);
        });
    }

    private void selecionarNo(DefaultMutableTreeNode no) {
        boolean antes = programatico;
        programatico = true;
        try {
            TreePath p = new TreePath(no.getPath());
            setSelectionPath(p);
            scrollPathToVisible(p);
            if (no.getParent() != null) {
                expandPath(new TreePath(((DefaultMutableTreeNode) no.getParent()).getPath()));
            }
        } finally {
            programatico = antes;
        }
    }

    /** O caminho da pasta selecionada, ou {@code null}. */
    String caminhoSelecionado() {
        TreePath p = getSelectionPath();
        if (p != null && p.getLastPathComponent() instanceof DefaultMutableTreeNode no && no.getUserObject() instanceof NoDePasta n) {
            return n.caminho();
        }
        return null;
    }

    /** Os caminhos de todos os nós de pasta que a árvore tem agora (para os testes provarem a carga preguiçosa). */
    Set<String> caminhosCarregados() {
        return Set.copyOf(carregados);
    }

    boolean temNo(String caminho) {
        return nos.containsKey(caminho);
    }

    /** A pasta sob o ponto (para soltar arquivos sobre ela). */
    String pastaNoPonto(java.awt.Point p) {
        TreePath t = getClosestPathForLocation(p.x, p.y);
        if (t != null && getPathBounds(t) != null && getPathBounds(t).contains(p)
            && t.getLastPathComponent() instanceof DefaultMutableTreeNode no && no.getUserObject() instanceof NoDePasta n) {
            return n.caminho();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Aparência
    // ---------------------------------------------------------------------------------------------

    private void ajustarAltura() {
        setRowHeight(Math.max(28, getFontMetrics(getFont()).getHeight() + 10));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (modelo != null) {
            setCellRenderer(new Celula());
            ajustarAltura();
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(240, 200);
    }

    private static final class Celula extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("this-escape")
        Celula() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree arvore, Object valor, boolean sel, boolean aberto, boolean folha, int linha, boolean foco) {
            super.getTreeCellRendererComponent(arvore, valor, sel, aberto, folha, linha, false);
            Object dado = valor instanceof DefaultMutableTreeNode no ? no.getUserObject() : valor;
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
            if (dado instanceof NoDePasta p) {
                setText(p.rotulo());
                setIcon(Icones.colorido("pasta", 16, sel ? Tokens.TEXTO : Tokens.ACENTO));
                setIconTextGap(6);
            } else if (dado instanceof Aviso a) {
                setText(a.texto());
                setIcon(a.carregando() ? null : Icones.colorido("alerta", 14, Tokens.ATENCAO));
                setForeground(Tokens.cor(Tokens.TEXTO3));
            }
            return this;
        }
    }
}
