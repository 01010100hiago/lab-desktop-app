package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ajuda.AtalhoDoMenu;
import br.com.labdesktop.ajuda.CategoriaDeAjuda;
import br.com.labdesktop.ajuda.Destino;
import br.com.labdesktop.ajuda.GuiaDeAjuda;
import br.com.labdesktop.ajuda.HistoricoDeAjuda;
import br.com.labdesktop.ajuda.TopicoDeAjuda;
import br.com.labdesktop.ui.componentes.AreaDaPagina;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * A Ajuda dentro do app (Ajustes, Ajuda; F1 no Windows e no Linux, Cmd+? no Mac): um guia curto, em categorias por tarefa do usuário, com uma árvore recolhível à esquerda
 * (só a categoria do tópico aberto fica aberta; setas movem e Enter abre), busca no topo que filtra e destaca o trecho, Voltar e Avançar entre os tópicos já lidos, a lista
 * rápida "Onde fica...?" e o texto do tópico à direita, cada tópico numa tela. Os links ABREM telas; nada aqui escreve.
 *
 * <p>SEM TRANSBORDO: a árvore e o texto rolam cada um por dentro do seu painel (só na vertical), o painel todo nunca rola e, em largura pequena (menos de
 * {@link #LARGURA_DO_MODO_ESTREITO} px), a árvore dá lugar a um seletor no topo (categoria e tópico).
 */
final class SecaoAjuda extends Painel {

    private static final long serialVersionUID = 1L;

    /** Abaixo desta largura do painel a árvore vira o seletor de categoria e tópico. */
    static final int LARGURA_DO_MODO_ESTREITO = 760;
    private static final int LARGURA_DA_ARVORE = 270;
    static final String ID_DO_INDICE = "onde-fica";
    private static final String ROTULO_DO_INDICE = "Onde fica...? (lista rápida)";

    private enum Tipo { INDICE, CATEGORIA, TOPICO, CONTINUAR }

    /** O que cada nó da árvore guarda; o texto da árvore é o rótulo. */
    private record No(Tipo tipo, String id, String rotulo) {
        @Override
        public String toString() {
            return rotulo;
        }
    }

    /** Um item do seletor do modo estreito (o rótulo é o que a lista suspensa mostra). */
    private record Item(String id, String rotulo) {
        @Override
        public String toString() {
            return rotulo;
        }
    }

    private final transient ControleDeAjustes controle;
    private final transient GuiaDeAjuda guia = GuiaDeAjuda.doJar();
    private final transient HistoricoDeAjuda historico = new HistoricoDeAjuda();
    private final DefaultMutableTreeNode raiz = new DefaultMutableTreeNode("Ajuda");
    private final DefaultTreeModel modelo = new DefaultTreeModel(raiz);
    private final JTree arvore;
    private final JScrollPane rolagemDaArvore;
    private final JTextField busca = Campos.busca(18, "Buscar na ajuda…");
    private final Botao voltar = Botoes.icone("voltar", "Voltar ao tópico anterior");
    private final Botao avancar = Botoes.icone("avancar", "Avançar para o tópico seguinte");
    private final JComboBox<Item> seletorDeCategoria = new JComboBox<>();
    private final JComboBox<Item> seletorDeTopico = new JComboBox<>();
    private final JComponent painelDoSeletor;
    private final Painel corpo = new Painel(new BorderLayout());
    private final Painel areaDoTexto = new Painel(new BorderLayout());
    private final transient VistaDoTopico.Fora fora;
    private boolean modoEstreito;
    private boolean modoDefinido;
    private boolean atualizando;
    private String consulta = "";
    private String topicoAberto;
    private transient VistaDoTopico.Montada vistaAtual;

    SecaoAjuda(ControleDeAjustes controle) {
        super(new BorderLayout());
        this.controle = controle;
        this.fora = new VistaDoTopico.Fora() {
            @Override
            public List<AtalhoDoMenu> atalhos() {
                return controle.atalhosDoMenu();
            }

            @Override
            public void irPara(Destino destino) {
                if (!controle.irPara(destino)) {
                    controle.avisos().info("Adicione um servidor primeiro para abrir essa tela.");
                }
            }

            @Override
            public void abrirTopico(String id) {
                abrir(id);
            }

            @Override
            public String rotuloDoTopico(String id) {
                TopicoDeAjuda t = guia.topico(id);
                return t == null ? id : t.rotulo();
            }

            @Override
            public String textoParaCopiar(TopicoDeAjuda topico) {
                return GuiaDeAjuda.textoParaCopiar(topico, controle.atalhosDoMenu());
            }

            @Override
            public CopiadorDeTexto copiador() {
                return controle.copiador();
            }

            @Override
            public Notificacoes avisos() {
                return controle.avisos();
            }
        };

        arvore = new JTree(modelo) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                super.updateUI();
                // a altura da linha acompanha o tamanho do texto que o usuário escolheu
                setRowHeight(Math.max(28, getFontMetrics(getFont()).getHeight() + 12));
            }
        };
        arvore.setRootVisible(false);
        arvore.setShowsRootHandles(true);
        arvore.setToggleClickCount(1);
        arvore.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        arvore.getAccessibleContext().setAccessibleName("Tópicos da Ajuda");
        arvore.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath caminho = arvore.getPathForLocation(e.getX(), e.getY());
                if (caminho != null) {
                    abrirNo(caminho);
                }
            }
        });
        arvore.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "abrir-topico");
        arvore.getActionMap().put("abrir-topico", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                TreePath caminho = arvore.getSelectionPath();
                if (caminho != null) {
                    abrirNo(caminho);
                }
            }
        });
        rolagemDaArvore = new JScrollPane(arvore, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rolagemDaArvore.setBorder(br.com.labdesktop.ui.componentes.Bordas.lados(0, 0, 0, 1, Tokens.BORDA));
        rolagemDaArvore.setPreferredSize(new Dimension(LARGURA_DA_ARVORE, 100));
        rolagemDaArvore.setMinimumSize(new Dimension(LARGURA_DA_ARVORE, 60));

        seletorDeCategoria.getAccessibleContext().setAccessibleName("Categoria da Ajuda");
        seletorDeTopico.getAccessibleContext().setAccessibleName("Tópico da Ajuda");
        painelDoSeletor = Caixa.linha(8, seletorDeCategoria, seletorDeTopico);
        painelDoSeletor.setBorder(new EmptyBorder(8, 12, 8, 12));
        seletorDeCategoria.addActionListener(e -> {
            if (!atualizando && seletorDeCategoria.getSelectedItem() instanceof Item c) {
                aoEscolherACategoria(c);
            }
        });
        seletorDeTopico.addActionListener(e -> {
            if (!atualizando && seletorDeTopico.getSelectedItem() instanceof Item t) {
                abrir(t.id());
            }
        });

        voltar.addActionListener(e -> {
            String id = historico.voltar();
            mostrar(id, false);
        });
        avancar.addActionListener(e -> {
            String id = historico.avancar();
            mostrar(id, false);
        });
        busca.getDocument().addDocumentListener(new br.com.labdesktop.ui.dialogos.SimplesOuvinteDeDocumento(() -> buscar(busca.getText())));
        busca.getAccessibleContext().setAccessibleName("Buscar na Ajuda");

        JComponent topo = Caixa.linha(8, voltar, avancar, busca);
        topo.setBorder(new EmptyBorder(10, 12, 10, 12));
        add(topo, BorderLayout.NORTH);
        add(corpo, BorderLayout.CENTER);
        corpo.add(areaDoTexto, BorderLayout.CENTER);
        reconstruir();
        getAccessibleContext().setAccessibleName("Ajuda");
    }

    /**
     * A troca de tema atualiza só o que está NA janela. A árvore e o seletor se revezam (um sai da janela quando o outro entra), então o que está fora
     * perderia a troca e voltaria com as cores de antes (os seletores brancos no escuro): aqui eles também são atualizados.
     */
    @Override
    public void updateUI() {
        super.updateUI();
        if (painelDoSeletor != null && painelDoSeletor.getParent() == null) {
            javax.swing.SwingUtilities.updateComponentTreeUI(painelDoSeletor);
        }
        if (rolagemDaArvore != null && rolagemDaArvore.getParent() == null) {
            javax.swing.SwingUtilities.updateComponentTreeUI(rolagemDaArvore);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Modo (árvore ou seletor)
    // ---------------------------------------------------------------------------------------------

    @Override
    public void doLayout() {
        boolean estreito = getWidth() > 0 && getWidth() < LARGURA_DO_MODO_ESTREITO;
        if (!modoDefinido || estreito != modoEstreito) {
            modoDefinido = true;
            modoEstreito = estreito;
            corpo.remove(rolagemDaArvore);
            corpo.remove(painelDoSeletor);
            corpo.add(estreito ? painelDoSeletor : rolagemDaArvore, estreito ? BorderLayout.NORTH : BorderLayout.WEST);
            corpo.revalidate();
        }
        super.doLayout();
    }

    // ---------------------------------------------------------------------------------------------
    // Árvore e seletor
    // ---------------------------------------------------------------------------------------------

    /** Refaz a árvore (e o seletor) com o que a busca deixa: categorias só com tópicos achados, todas abertas quando há busca; sem busca, só a do tópico aberto. */
    private void reconstruir() {
        atualizando = true;
        try {
            raiz.removeAllChildren();
            List<TopicoDeAjuda> achados = guia.filtrar(consulta);
            boolean comBusca = !consulta.isBlank();
            if (!comBusca || !indiceAchado().isEmpty()) {
                raiz.add(new DefaultMutableTreeNode(new No(Tipo.INDICE, ID_DO_INDICE, ROTULO_DO_INDICE)));
            }
            String maisRecente = historico.maisRecenteQueNaoEOAtual();
            boolean primeira = true;
            for (CategoriaDeAjuda c : guia.categorias()) {
                List<TopicoDeAjuda> dela = c.ids().stream().map(guia::topico).filter(t -> t != null && achados.contains(t)).toList();
                if (dela.isEmpty() && !(primeira && maisRecente != null && !comBusca)) {
                    primeira = false;
                    continue;
                }
                DefaultMutableTreeNode noDaCategoria = new DefaultMutableTreeNode(new No(Tipo.CATEGORIA, c.nome(), c.nome()));
                if (primeira && maisRecente != null && !comBusca && guia.topico(maisRecente) != null) {
                    noDaCategoria.add(new DefaultMutableTreeNode(new No(Tipo.CONTINUAR, maisRecente, "Continuar: " + guia.topico(maisRecente).rotulo())));
                }
                for (TopicoDeAjuda t : dela) {
                    noDaCategoria.add(new DefaultMutableTreeNode(new No(Tipo.TOPICO, t.id(), t.rotulo())));
                }
                raiz.add(noDaCategoria);
                primeira = false;
            }
            modelo.reload();
            if (comBusca) {
                for (int i = 0; i < raiz.getChildCount(); i++) {
                    arvore.expandPath(new TreePath(((DefaultMutableTreeNode) raiz.getChildAt(i)).getPath()));
                }
            } else {
                expandirSomenteACategoriaDoAberto();
            }
            marcarNaArvore();
            refazerSeletores(achados);
        } finally {
            atualizando = false;
        }
        avancar.setEnabled(historico.podeAvancar());
        voltar.setEnabled(historico.podeVoltar());
    }

    private List<TopicoDeAjuda.OndeFica> indiceAchado() {
        if (consulta.isBlank()) {
            return guia.ondeFica();
        }
        return guia.ondeFica().stream().filter(o -> !GuiaDeAjuda.trechos(o.oQue() + " " + o.caminho(), consulta).isEmpty()
            && todasAsPalavras(o.oQue() + " " + o.caminho())).toList();
    }

    private boolean todasAsPalavras(String texto) {
        for (String palavra : consulta.strip().split("\\s+")) {
            if (GuiaDeAjuda.trechos(texto, palavra).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void expandirSomenteACategoriaDoAberto() {
        String categoriaAberta = categoriaDe(topicoAberto);
        for (int i = 0; i < raiz.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) raiz.getChildAt(i);
            TreePath p = new TreePath(n.getPath());
            if (n.getUserObject() instanceof No no && no.tipo() == Tipo.CATEGORIA) {
                if (no.id().equals(categoriaAberta)) {
                    arvore.expandPath(p);
                } else {
                    arvore.collapsePath(p);
                }
            }
        }
    }

    private String categoriaDe(String idDoTopico) {
        if (idDoTopico == null) {
            return null;
        }
        for (CategoriaDeAjuda c : guia.categorias()) {
            if (c.ids().contains(idDoTopico)) {
                return c.nome();
            }
        }
        return null;
    }

    private void marcarNaArvore() {
        if (topicoAberto == null) {
            arvore.clearSelection();
            return;
        }
        for (int i = 0; i < raiz.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) raiz.getChildAt(i);
            if (n.getUserObject() instanceof No no && no.tipo() == Tipo.INDICE && no.id().equals(topicoAberto)) {
                arvore.setSelectionPath(new TreePath(n.getPath()));
                return;
            }
            for (int j = 0; j < n.getChildCount(); j++) {
                DefaultMutableTreeNode f = (DefaultMutableTreeNode) n.getChildAt(j);
                if (f.getUserObject() instanceof No no && no.tipo() == Tipo.TOPICO && no.id().equals(topicoAberto)) {
                    arvore.setSelectionPath(new TreePath(f.getPath()));
                    arvore.scrollPathToVisible(new TreePath(f.getPath()));
                    return;
                }
            }
        }
        arvore.clearSelection();
    }

    private void refazerSeletores(List<TopicoDeAjuda> achados) {
        seletorDeCategoria.removeAllItems();
        seletorDeCategoria.addItem(new Item(ID_DO_INDICE, ROTULO_DO_INDICE));
        for (CategoriaDeAjuda c : guia.categorias()) {
            if (c.ids().stream().map(guia::topico).anyMatch(t -> t != null && achados.contains(t))) {
                seletorDeCategoria.addItem(new Item(c.nome(), c.nome()));
            }
        }
        String categoria = topicoAberto == null || topicoAberto.equals(ID_DO_INDICE) ? null : categoriaDe(topicoAberto);
        selecionar(seletorDeCategoria, categoria == null ? ID_DO_INDICE : categoria);
        refazerSeletorDeTopicos(achados);
    }

    private void refazerSeletorDeTopicos(List<TopicoDeAjuda> achados) {
        seletorDeTopico.removeAllItems();
        Item categoria = (Item) seletorDeCategoria.getSelectedItem();
        if (categoria == null || categoria.id().equals(ID_DO_INDICE)) {
            seletorDeTopico.addItem(new Item(ID_DO_INDICE, "Todas as linhas")); // a lista rápida é uma tela só: o seletor de tópico não fica apagado nem vazio
            return;
        }
        for (CategoriaDeAjuda c : guia.categorias()) {
            if (c.nome().equals(categoria.id())) {
                for (String id : c.ids()) {
                    TopicoDeAjuda t = guia.topico(id);
                    if (t != null && achados.contains(t)) {
                        seletorDeTopico.addItem(new Item(t.id(), t.rotulo()));
                    }
                }
            }
        }
        selecionar(seletorDeTopico, topicoAberto);
    }

    private static void selecionar(JComboBox<Item> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).id().equals(id)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void aoEscolherACategoria(Item categoria) {
        if (categoria.id().equals(ID_DO_INDICE)) {
            abrir(ID_DO_INDICE);
            return;
        }
        atualizando = true;
        try {
            refazerSeletorDeTopicos(guia.filtrar(consulta));
        } finally {
            atualizando = false;
        }
        if (seletorDeTopico.getItemCount() > 0) {
            abrir(seletorDeTopico.getItemAt(0).id());
        }
    }

    private void abrirNo(TreePath caminho) {
        Object o = ((DefaultMutableTreeNode) caminho.getLastPathComponent()).getUserObject();
        if (o instanceof No no) {
            switch (no.tipo()) {
                case INDICE, TOPICO, CONTINUAR -> abrir(no.id());
                case CATEGORIA -> {
                    if (arvore.isExpanded(caminho)) {
                        arvore.collapsePath(caminho);
                    } else {
                        arvore.expandPath(caminho);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir um tópico e buscar
    // ---------------------------------------------------------------------------------------------

    /** Abre um tópico (ou a lista rápida), registra no histórico e mostra. */
    void abrir(String id) {
        if (id == null || (!id.equals(ID_DO_INDICE) && guia.topico(id) == null)) {
            return;
        }
        historico.abrir(id);
        mostrar(id, true);
    }

    private void mostrar(String id, boolean ehNovo) {
        if (id == null) {
            return;
        }
        topicoAberto = id;
        areaDoTexto.removeAll();
        VistaDoTopico.Montada montada = id.equals(ID_DO_INDICE)
            ? VistaDoTopico.indice(indiceAchado(), fora, consulta)
            : VistaDoTopico.topico(guia.topico(id), fora, consulta);
        vistaAtual = montada;
        if (montada.detalhes() != null && montada.destaquesNosDetalhes() > 0 && montada.destaques() == montada.destaquesNosDetalhes()) {
            montada.detalhes().definirAberta(true); // a busca só achou dentro de "Mais detalhes": abre para mostrar o trecho
        }
        areaDoTexto.add(new AreaDaPagina(montada.componente(), false), BorderLayout.CENTER);
        reconstruir();
        areaDoTexto.revalidate();
        areaDoTexto.repaint();
    }

    /** A busca: filtra os tópicos e destaca o trecho no que está aberto. */
    void buscar(String texto) {
        String nova = texto == null ? "" : texto.strip();
        if (nova.equals(consulta)) {
            return;
        }
        consulta = nova;
        if (topicoAberto != null) {
            mostrar(topicoAberto, false); // refaz a vista com os destaques novos (e a árvore)
        } else {
            reconstruir();
        }
    }

    /** Ao aparecer a seção: se nada foi aberto ainda, abre o primeiro tópico. */
    void aoMostrar() {
        if (topicoAberto == null && !guia.topicos().isEmpty()) {
            abrir(guia.topicos().get(0).id());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    GuiaDeAjuda guia() {
        return guia;
    }

    HistoricoDeAjuda historico() {
        return historico;
    }

    JTree arvore() {
        return arvore;
    }

    JScrollPane rolagemDaArvore() {
        return rolagemDaArvore;
    }

    JTextField campoDeBusca() {
        return busca;
    }

    Botao botaoVoltar() {
        return voltar;
    }

    Botao botaoAvancar() {
        return avancar;
    }

    JComboBox<?> seletorDeCategoria() {
        return seletorDeCategoria;
    }

    JComboBox<?> seletorDeTopico() {
        return seletorDeTopico;
    }

    boolean modoEstreito() {
        return modoEstreito;
    }

    String topicoAberto() {
        return topicoAberto;
    }

    VistaDoTopico.Montada vistaAtual() {
        return vistaAtual;
    }

    String consultaAtual() {
        return consulta;
    }

    /** Os rótulos das categorias e dos tópicos como a árvore os mostra agora, em linhas ("Categoria" e "  Tópico"), com {@code [+]} nas abertas. */
    List<String> linhasDaArvore() {
        List<String> linhas = new ArrayList<>();
        for (int i = 0; i < raiz.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) raiz.getChildAt(i);
            TreePath p = new TreePath(n.getPath());
            if (n.getUserObject() instanceof No no && no.tipo() == Tipo.CATEGORIA) {
                linhas.add((arvore.isExpanded(p) ? "[-] " : "[+] ") + no.rotulo());
                if (arvore.isExpanded(p)) {
                    for (int j = 0; j < n.getChildCount(); j++) {
                        linhas.add("    " + ((No) ((DefaultMutableTreeNode) n.getChildAt(j)).getUserObject()).rotulo());
                    }
                }
            } else if (n.getUserObject() instanceof No no) {
                linhas.add(no.rotulo());
            }
        }
        return linhas;
    }

    /** Clica no nó de um tópico da árvore (para os testes agirem como o usuário). */
    void clicarNoTopicoDaArvore(String id) {
        for (int i = 0; i < raiz.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) raiz.getChildAt(i);
            for (int j = 0; j < n.getChildCount(); j++) {
                DefaultMutableTreeNode f = (DefaultMutableTreeNode) n.getChildAt(j);
                if (f.getUserObject() instanceof No no && no.id().equals(id) && no.tipo() == Tipo.TOPICO) {
                    TreePath caminho = new TreePath(f.getPath());
                    arvore.expandPath(new TreePath(n.getPath()));
                    arvore.setSelectionPath(caminho);
                    abrirNo(caminho);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("sem nó para " + id);
    }

    /** Aperta Enter na árvore (para os testes). */
    void apertarEnterNaArvore() {
        arvore.getActionMap().get("abrir-topico").actionPerformed(null);
    }

    /** Rotulo de estado para ajudar a depurar testes. */
    @Override
    public String toString() {
        return "SecaoAjuda[" + topicoAberto + ", busca=" + consulta + (modoEstreito ? ", estreito" : "") + "]";
    }
}
