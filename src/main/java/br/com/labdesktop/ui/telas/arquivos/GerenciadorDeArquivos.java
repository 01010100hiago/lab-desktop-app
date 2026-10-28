package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.BackupsLocais;
import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.EditorRemoto;
import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.Escopo;
import br.com.labdesktop.arquivos.FilaDeTransferencias;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Decisao;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Escolha;
import br.com.labdesktop.arquivos.ListagemDePasta;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.RaizDoServidor;
import br.com.labdesktop.arquivos.ResumoDeTamanho;
import br.com.labdesktop.arquivos.ServicoDeArquivos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.arquivos.ServicoDeArquivos.ResultadoDoLote;
import br.com.labdesktop.arquivos.VisaoDaPasta;
import br.com.labdesktop.arquivos.VisaoDaPasta.Coluna;
import br.com.labdesktop.arquivos.VisaoDaPasta.Ordem;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.dialogos.DialogoApagarArquivos;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeSalvar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeTransferencia;
import br.com.labdesktop.ui.dialogos.DialogoFecharEditor;
import br.com.labdesktop.ui.dialogos.DialogoNome;
import br.com.labdesktop.ui.telas.PaginaDoServidor.Esmaecivel;
import br.com.labdesktop.ui.telas.PaginaDoServidor.FaixaDeDados;
import br.com.labdesktop.ui.telas.SituacaoDaVisao;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import br.com.labdesktop.util.Fundo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O gerenciador de arquivos do servidor, por SFTP (o mesmo SSH que o app já mantém: sem agente, funciona em qualquer servidor).
 * UM componente para dois modos: <b>servidor inteiro</b> (com o seletor de raiz Projetos, Minha pasta e, em Avançado, "Tudo (/)")
 * e <b>projeto</b> (raiz travada em {@code <raiz dos projetos>/<projeto>}: sair dela é impossível).
 *
 * <h2>Como se navega</h2>
 * Barra de caminho clicável, Voltar, Subir e Atualizar, árvore de pastas com carga preguiçosa à esquerda e tabela à direita.
 * Duplo clique ou Enter abre pasta ou arquivo; Backspace sobe; F2 renomeia; Delete apaga; Cmd+F vai ao filtro. Pastas primeiro,
 * ordem por coluna, filtro de texto na pasta, ocultos escondidos por padrão. Texto vira editor numa aba ao lado.
 *
 * <h2>Regras</h2>
 * Tudo de rede roda fora da EDT ({@link Tarefas}); a tela só desenha o que chega. Cada resposta tem uma geração: um clique novo
 * invalida a leitura anterior que ainda estava em voo. Sem {@code -Plabdesktop.acoes=true} a tela só LÊ e BAIXA e diz isso numa
 * frase. Sem conexão, os últimos dados ficam esmaecidos com a hora e as ações se desabilitam; quando a conexão volta, a pasta
 * é relida sozinha. O que vem do servidor (nomes, conteúdo) é sempre TEXTO.
 */
public final class GerenciadorDeArquivos extends Painel {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(GerenciadorDeArquivos.class);
    /**
     * Quem a página do projeto consulta antes de agir no Esc (que lá volta para a lista): um componente marcado assim trata o Esc
     * sozinho. O editor se marca, para o Esc fechar a busca e NUNCA limpar o texto nem sair da página com alterações no ar.
     */
    public static final String PROPRIEDADE_ESC = "labdesktop.esc-proprio";
    private static final String ABA_NAVEGADOR = "navegador";
    private static final String PREFIXO_DE_EDITOR = "editor:";
    private static final int ESPERA_DO_FILTRO_NO_SERVIDOR_MS = 350;

    /** Em que pé está a área da lista. */
    public enum Situacao {
        /** Ainda não começou (a aba nunca foi vista). */
        INICIANDO,
        CARREGANDO,
        NORMAL,
        VAZIA,
        SEM_CONEXAO,
        SFTP_INDISPONIVEL,
        SEM_PERMISSAO,
        NAO_ENCONTRADA,
        ERRO
    }

    private final transient ContextoDeArquivos ctx;
    private final transient MemoriaDeCaminhos memoria;

    // ---- o que o servidor deu (só a EDT mexe) ----
    private transient ClienteSftp cliente;
    private transient ServicoDeArquivos servico;
    private transient EditorRemoto editorRemoto;
    private transient BackupsLocais backups;
    private transient FilaDeTransferencias fila;
    private transient Escopo escopo;
    private transient String home = "/";
    private RaizDoServidor raizAtual = RaizDoServidor.PROJETOS;
    private String caminhoAtual;
    private transient ListagemDePasta listagem;
    private boolean listagemFiltradaNoServidor;
    private final Deque<String> pilhaDeVoltar = new ArrayDeque<>();
    private Situacao situacao = Situacao.INICIANDO;
    private String erroDaPasta = "";
    private transient EstadoServidor estadoDaConexao;
    private boolean semConexaoVista;
    private transient Instant ultimaLeitura;
    private long geracao;
    private boolean iniciado;
    private boolean encerrado;
    private boolean preparado;
    private final AtomicInteger emVoo = new AtomicInteger();
    private int tentativasDeReconexao;
    private transient Timer reconexao;
    private transient Timer filtroNoServidor;
    private transient Inscricao inscricaoDoEstado;
    private transient Runnable desinscreverFila = () -> { };
    private final List<CompletableFuture<Decisao>> decisoesPendentes = new CopyOnWriteArrayList<>();

    // ---- escolhas do usuário ----
    private String filtro = "";
    private boolean mostrarOcultos;
    private Ordem ordem = Ordem.PADRAO;

    // ---- peças ----
    private final CardLayout cartoesDoCorpo = new CardLayout();
    private final JPanel corpo = new Painel(cartoesDoCorpo);
    private final Abas abas = new Abas(true);
    private final JPanel navegador = new Painel(new BorderLayout(0, 8));
    private final Map<String, SessaoDeEdicao> editores = new LinkedHashMap<>();
    private int proximoEditor = 1;

    private final Botao voltar = Botoes.mini("Voltar", "voltar");
    private final Botao subir = Botoes.mini("Subir uma pasta", "seta-cima");
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    private final BarraDeCaminho barraDeCaminho = new BarraDeCaminho(this::irPara);
    private final Botao seletorDeRaiz = Botoes.mini("Pasta: Projetos", "pasta");
    private final JTextField campoDeFiltro = Campos.busca(24, "Filtrar nesta pasta");
    private final JCheckBox caixaDeOcultos = new JCheckBox("Mostrar ocultos");
    private final Rotulo resumo = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel();
    private final Botao enviar = Botoes.mini("Enviar", "enviar-arquivo");
    private final Botao baixar = Botoes.mini("Baixar", "baixar");
    private final Botao novaPasta = Botoes.mini("Nova pasta", "pasta-mais");
    private final Botao novoArquivo = Botoes.mini("Novo arquivo", "arquivo-mais");
    private final Botao renomear = Botoes.mini("Renomear", "editar");
    private final Botao apagar = Botoes.miniPerigo("Apagar", "lixeira");
    private final Botao maisAcoes = Botoes.mini("Mais", "mais-acoes");
    private final Rotulo somenteLeitura = new Rotulo("Somente leitura nesta versão: dá para navegar, abrir e baixar; enviar, editar, criar, renomear, "
        + "mover e apagar ficam desligados.").corDoToken(Tokens.TEXTO3).tamanho(12.5f).truncavel();
    private final FaixaDeDados faixaDeDados = new FaixaDeDados();
    private final TabelaDeEntradas tabela;
    private final ArvoreDePastas arvore;
    private final CardLayout cartoesDaLista = new CardLayout();
    private final JPanel areaDaLista = new Painel(cartoesDaLista);
    private final Esmaecivel tabelaEsmaecivel = new Esmaecivel();
    private final CartaoDeMensagem cartao = new CartaoDeMensagem();
    private final EsqueletoDaLista esqueleto = new EsqueletoDaLista();
    private final PainelDeTransferencias painelDeTransferencias;
    private final JSplitPane divisor;

    private static final String CARTAO_TABELA = "tabela";
    private static final String CARTAO_MENSAGEM = "mensagem";
    private static final String CARTAO_ESQUELETO = "esqueleto";

    /**
     * @param ctx tudo o que vem de fora (servidor, modo, ações liberadas, diálogos)
     */
    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public GerenciadorDeArquivos(ContextoDeArquivos ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        this.memoria = ctx.servicos().memoria();
        fundo(Tokens.BG);
        setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        setName(ctx.modoProjeto() ? "arquivos-do-projeto" : "arquivos-do-servidor");

        this.tabela = new TabelaDeEntradas(new OuvinteDaTabela());
        this.arvore = new ArvoreDePastas(new CarregadorDaArvore());
        this.painelDeTransferencias = new PainelDeTransferencias(new PainelDeTransferencias.Acoes() {
            @Override
            public void cancelar(long id) {
                if (fila != null) {
                    fila.cancelar(id);
                }
            }

            @Override
            public void limparConcluidas() {
                if (fila != null) {
                    fila.limparConcluidas();
                }
            }
        });
        this.divisor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        montarNavegador();
        montarAbasECorpo();
        add(painelDeTransferencias, BorderLayout.SOUTH);
        ligarEventos();
        instalarArraste();
        atualizarBotoes();
        getAccessibleContext().setAccessibleName(ctx.modoProjeto() ? "Arquivos do projeto " + ctx.projeto() : "Arquivos do servidor");
        definirSituacao(Situacao.INICIANDO); // até a primeira leitura, o esqueleto da lista (nunca uma tabela em branco)

        if (ctx.observarEstado() != null) {
            inscricaoDoEstado = ctx.observarEstado().apply(this::aoMudarEstadoDaConexao);
        }
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                iniciar();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private void montarNavegador() {
        voltar.setToolTipText("Volta para a pasta anterior");
        subir.setToolTipText("Sobe uma pasta (Backspace)");
        atualizar.setToolTipText("Lê a pasta de novo no servidor");
        seletorDeRaiz.setToolTipText("Escolhe de onde começar a navegar neste servidor");
        barraDeCaminho.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        JPanel linhaDeNavegacao = Caixa.linha(8, voltar, subir, atualizar, barraDeCaminho, ctx.modoProjeto() ? null : seletorDeRaiz);
        seletorDeRaiz.setVisible(!ctx.modoProjeto());

        campoDeFiltro.setMaximumSize(new Dimension(320, 36));
        campoDeFiltro.setPreferredSize(new Dimension(280, 36));
        caixaDeOcultos.setOpaque(false);
        caixaDeOcultos.putClientProperty("html.disable", Boolean.TRUE);
        caixaDeOcultos.setToolTipText("Mostra também os arquivos e as pastas que começam com ponto (.env, .git)");
        JPanel linhaDeFiltro = Caixa.linha(14, campoDeFiltro, caixaDeOcultos, Caixa.mola(), resumo);
        resumo.setMaximumSize(new Dimension(420, 24));

        enviar.setToolTipText("Envia arquivos e pastas do seu computador para esta pasta (ou arraste do gerenciador de arquivos)");
        baixar.setToolTipText("Baixa o que está selecionado para uma pasta do seu computador");
        novaPasta.setToolTipText("Cria uma pasta aqui");
        novoArquivo.setToolTipText("Cria um arquivo vazio aqui e o abre no editor");
        renomear.setToolTipText("Muda o nome do item selecionado (F2)");
        apagar.setToolTipText("Apaga o que está selecionado (Delete)");
        maisAcoes.setToolTipText("Mover para outra pasta e copiar o caminho");
        maisAcoes.setIconTextGap(6);
        JPanel linhaDeAcoes = Caixa.linha(8, enviar, baixar, novaPasta, novoArquivo, renomear, apagar, maisAcoes, Caixa.mola());

        somenteLeitura.setIcon(Icones.colorido("info", 16, Tokens.TEXTO3));
        somenteLeitura.setIconTextGap(8);
        somenteLeitura.setVisible(!ctx.escritaLiberada());

        // o espaço entre as linhas é uma borda de CADA linha (e some com ela): uma linha escondida não deixa vazio
        JPanel faixaComFolga = new Painel(new BorderLayout());
        faixaComFolga.add(faixaDeDados, BorderLayout.CENTER);
        faixaDeDados.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                faixaComFolga.setVisible(true);
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                faixaComFolga.setVisible(false);
            }
        });
        faixaDeDados.setVisible(false);
        faixaComFolga.setVisible(false);
        somenteLeitura.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        JPanel topo = new Painel();
        topo.setLayout(new BoxLayout(topo, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[] {linhaDeNavegacao, linhaDeFiltro, linhaDeAcoes, somenteLeitura, faixaComFolga}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            if (c instanceof JPanel p) {
                p.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            }
            topo.add(c);
        }

        JScrollPane rolagemDaTabela = new JScrollPane(tabela);
        rolagemDaTabela.setBorder(null);
        rolagemDaTabela.setColumnHeaderView(tabela.getTableHeader()); // sem esperar o addNotify: o cabeçalho aparece também fora de janela
        rolagemDaTabela.getViewport().setOpaque(true);
        tabelaEsmaecivel.setLayout(new BorderLayout());
        tabelaEsmaecivel.add(rolagemDaTabela, BorderLayout.CENTER);
        areaDaLista.add(tabelaEsmaecivel, CARTAO_TABELA);
        areaDaLista.add(cartao, CARTAO_MENSAGEM);
        areaDaLista.add(esqueleto, CARTAO_ESQUELETO);
        Painel moldura = new Painel(new BorderLayout());
        moldura.fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(8);
        moldura.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        moldura.add(areaDaLista, BorderLayout.CENTER);

        JScrollPane rolagemDaArvore = new JScrollPane(arvore);
        rolagemDaArvore.setBorder(null);
        rolagemDaArvore.getViewport().setOpaque(true);
        Painel molduraDaArvore = new Painel(new BorderLayout());
        molduraDaArvore.fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(8);
        molduraDaArvore.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        molduraDaArvore.add(rolagemDaArvore, BorderLayout.CENTER);
        molduraDaArvore.setMinimumSize(new Dimension(140, 100));
        moldura.setMinimumSize(new Dimension(300, 100));

        divisor.setLeftComponent(molduraDaArvore);
        divisor.setRightComponent(moldura);
        divisor.setDividerLocation(200);
        divisor.setResizeWeight(0);
        divisor.setContinuousLayout(true);
        divisor.setBorder(null);
        divisor.setOpaque(false);

        navegador.add(topo, BorderLayout.NORTH);
        navegador.add(divisor, BorderLayout.CENTER);
        cartao.mostrar("pasta", Gravidade.NEUTRO, "", "", null, null);
    }

    private void montarAbasECorpo() {
        abas.adicionar(ABA_NAVEGADOR, "Arquivos");
        abas.setVisible(false); // só aparece com um editor aberto
        abas.aoSelecionar(this::mostrarCartao);
        corpo.add(navegador, ABA_NAVEGADOR);
        cartoesDoCorpo.show(corpo, ABA_NAVEGADOR);
        JPanel centro = new Painel(new BorderLayout(0, 8));
        centro.add(abas, BorderLayout.NORTH);
        centro.add(corpo, BorderLayout.CENTER);
        add(centro, BorderLayout.CENTER);
    }

    private void mostrarCartao(String id) {
        cartoesDoCorpo.show(corpo, id);
        corpo.revalidate();
        corpo.repaint();
    }

    private void ligarEventos() {
        voltar.addActionListener(e -> voltar());
        subir.addActionListener(e -> subir());
        atualizar.addActionListener(e -> atualizar());
        enviar.addActionListener(e -> escolherEEnviar());
        baixar.addActionListener(e -> baixarSelecionadas());
        novaPasta.addActionListener(e -> pedirNovaPasta());
        novoArquivo.addActionListener(e -> pedirNovoArquivo());
        renomear.addActionListener(e -> pedirRenomear());
        apagar.addActionListener(e -> pedirApagar());
        maisAcoes.addActionListener(e -> menuDeMais().show(maisAcoes, 0, maisAcoes.getHeight()));
        seletorDeRaiz.addActionListener(e -> menuDeRaiz().show(seletorDeRaiz, 0, seletorDeRaiz.getHeight()));
        caixaDeOcultos.addActionListener(e -> mostrarOcultos(caixaDeOcultos.isSelected()));
        campoDeFiltro.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filtrar(campoDeFiltro.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filtrar(campoDeFiltro.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) { }
        });
        int cmd = br.com.labdesktop.ui.componentes.Teclas.comando();
        navegador.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, cmd), "ir-ao-filtro");
        navegador.getActionMap().put("ir-ao-filtro", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                campoDeFiltro.requestFocusInWindow();
                campoDeFiltro.selectAll();
            }
        });
        navegador.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, cmd | java.awt.event.InputEvent.ALT_DOWN_MASK), "copiar-caminho");
        navegador.getActionMap().put("copiar-caminho", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                copiarCaminho();
            }
        });
        // Esc no filtro limpa o filtro (e devolve o foco à tabela)
        campoDeFiltro.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "limpar-filtro");
        campoDeFiltro.getActionMap().put("limpar-filtro", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (!campoDeFiltro.getText().isEmpty()) {
                    campoDeFiltro.setText("");
                }
                tabela.requestFocusInWindow();
            }
        });
        campoDeFiltro.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "descer-para-a-tabela");
        campoDeFiltro.getActionMap().put("descer-para-a-tabela", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                tabela.requestFocusInWindow();
                if (tabela.getRowCount() > 0 && tabela.getSelectedRow() < 0) {
                    tabela.setRowSelectionInterval(0, 0);
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Início e fim
    // ---------------------------------------------------------------------------------------------

    /** Começa a ler (a aba apareceu pela primeira vez). Idempotente. */
    public void iniciar() {
        if (iniciado || encerrado) {
            return;
        }
        iniciado = true;
        if (ctx.fabricaDeCliente() == null) {
            definirSituacao(Situacao.SEM_CONEXAO);
            return;
        }
        if (estadoDaConexao != null && !conectado()) {
            definirSituacao(Situacao.SEM_CONEXAO);
            return;
        }
        preparar(null, null);
    }

    /** Fecha o canal SFTP, cancela o que corre e solta as inscrições. A tela saiu (o projeto fechou, o servidor foi removido). */
    public void encerrar() {
        if (encerrado) {
            return;
        }
        encerrado = true;
        if (reconexao != null) {
            reconexao.stop();
        }
        if (filtroNoServidor != null) {
            filtroNoServidor.stop();
        }
        if (inscricaoDoEstado != null) {
            inscricaoDoEstado.close();
        }
        for (CompletableFuture<Decisao> f : decisoesPendentes) {
            f.complete(new Decisao(Escolha.CANCELAR, false));
        }
        desinscreverFila.run();
        if (fila != null) {
            fila.close();
        }
        ClienteSftp fechando = cliente;
        if (fechando != null) {
            Fundo.emFundo(fechando::close); // fechar o canal fala com o servidor: nunca na EDT
        }
        for (SessaoDeEdicao s : editores.values()) {
            s.painel.area().setEditable(false);
        }
    }

    public boolean encerrado() {
        return encerrado;
    }

    /** Há arquivo aberto no editor com alterações que não foram salvas (quem fecha a página pergunta ou guarda). */
    public boolean temAlteracoesNaoSalvas() {
        return editores.values().stream().anyMatch(s -> s.painel.modificado());
    }

    /** Quantos arquivos abertos no editor têm alterações não salvas. */
    public int editoresComAlteracoes() {
        return (int) editores.values().stream().filter(s -> s.painel.modificado()).count();
    }

    /** Quantas transferências ainda não terminaram (na fila ou andando). */
    public int transferenciasEmAberto() {
        return fila == null ? 0 : fila.emAberto();
    }

    // ---------------------------------------------------------------------------------------------
    // Escopo e primeira leitura
    // ---------------------------------------------------------------------------------------------

    /** O resultado de preparar o escopo (feito fora da EDT). */
    private record Preparado(Escopo escopo, ServicoDeArquivos servico, String home, RaizDoServidor raiz, ListagemDePasta listagem) { }

    private synchronized ClienteSftp clienteGarantido() throws ErroSftp {
        if (ctx.fabricaDeCliente() == null) {
            throw ErroSftp.semConexao(null);
        }
        if (cliente == null) {
            cliente = ctx.fabricaDeCliente().criar();
        }
        return cliente;
    }

    /**
     * Monta o escopo (raiz do modo, home, raiz dos projetos) e lê a primeira pasta.
     *
     * @param raizPedida {@code null} usa a lembrada (ou a padrão); só vale no modo servidor
     * @param inicio     a pasta onde começar, ou {@code null} para a lembrada
     */
    private void preparar(RaizDoServidor raizPedida, String inicio) {
        long g = ++geracao;
        definirSituacao(Situacao.CARREGANDO);
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> prepararEmFundo(raizPedida, inicio), p -> {
            emVoo.decrementAndGet();
            if (g != geracao || encerrado) {
                return;
            }
            aplicarPreparado(p);
        }, erro -> {
            emVoo.decrementAndGet();
            if (g == geracao && !encerrado) {
                tratarErro(erro, null);
            }
        });
    }

    private Preparado prepararEmFundo(RaizDoServidor raizPedida, String inicio) throws ErroSftp {
        ClienteSftp c = clienteGarantido();
        String casa = CaminhoRemoto.normalizar(c.caminhoCanonico("."));
        String raizDosProjetos = memoria.raizDosProjetos(ctx.servidorId());
        Escopo e;
        RaizDoServidor raiz = RaizDoServidor.PROJETOS;
        String lembrado;
        if (ctx.modoProjeto()) {
            e = Escopo.projeto(raizDosProjetos, ctx.projeto(), casa);
            lembrado = inicio != null ? inicio : memoria.ultimoCaminhoDoProjeto(ctx.servidorId(), ctx.projeto()).orElse(null);
        } else {
            raiz = raizPedida;
            boolean primeiraVez = memoria.ultimoCaminhoDoServidor(ctx.servidorId()).isEmpty();
            if (raiz == null) {
                raiz = primeiraVez && !c.existe(raizDosProjetos) ? RaizDoServidor.MINHA_PASTA : memoria.raizEscolhida(ctx.servidorId());
            }
            e = escopoDe(raiz, casa, raizDosProjetos);
            lembrado = inicio != null ? inicio
                : raizPedida != null ? null : memoria.ultimoCaminhoDoServidor(ctx.servidorId()).orElse(null);
        }
        e = e.comBlindados(ctx.blindados()); // em Este computador: o cofre e os dados do app nunca se apagam nem se movem, em escopo nenhum
        ServicoDeArquivos s = new ServicoDeArquivos(c, e);
        String comeco = lembrado != null && e.contem(lembrado) ? lembrado : e.raiz();
        ListagemDePasta l;
        try {
            l = s.listar(comeco, ListagemDePasta.LIMITE_PADRAO, n -> true);
        } catch (ErroSftp falha) {
            if (comeco.equals(e.raiz()) || !(falha.tipo() == ErroSftp.Tipo.NAO_EXISTE || falha.tipo() == ErroSftp.Tipo.SEM_PERMISSAO
                || falha.tipo() == ErroSftp.Tipo.FORA_DA_RAIZ)) {
                throw falha;
            }
            l = s.listar(e.raiz(), ListagemDePasta.LIMITE_PADRAO, n -> true); // o último caminho sumiu: começa na raiz
        }
        return new Preparado(e, s, casa, raiz, l);
    }

    private Escopo escopoDe(RaizDoServidor raiz, String casa, String raizDosProjetos) {
        return switch (raiz) {
            case PROJETOS -> Escopo.servidor(RaizDoServidor.PROJETOS.rotulo(), raizDosProjetos, raizDosProjetos, casa);
            case MINHA_PASTA -> Escopo.servidor(RaizDoServidor.MINHA_PASTA.rotulo(), casa, raizDosProjetos, casa);
            case TUDO -> Escopo.servidor(RaizDoServidor.TUDO.rotulo(), "/", raizDosProjetos, casa);
        };
    }

    private void aplicarPreparado(Preparado p) {
        this.escopo = p.escopo();
        this.servico = p.servico();
        this.home = p.home();
        this.raizAtual = p.raiz();
        this.backups = new BackupsLocais(ctx.servicos().pastaDeBackups(), ctx.servidorNome());
        this.editorRemoto = new EditorRemoto(servico, backups, ctx.executorDeSudo());
        this.preparado = true;
        garantirFila();
        pilhaDeVoltar.clear();
        arvore.mostrarOcultos(mostrarOcultos);
        arvore.definirRaiz(escopo.raiz(), escopo.nome());
        seletorDeRaiz.setText("Pasta: " + p.raiz().rotulo());
        aplicarListagem(p.listagem(), false);
    }

    private FilaDeTransferencias garantirFila() {
        if (fila == null) {
            fila = new FilaDeTransferencias(() -> servico, this::decidirConflitoDeTransferencia, Fundo::naEdt, 2);
            desinscreverFila = fila.observar(retratos -> {
                painelDeTransferencias.definir(retratos);
                aoMudarAsTransferencias(retratos);
            });
        }
        return fila;
    }

    private final Map<Long, Boolean> transferenciasJaNotadas = new LinkedHashMap<>();

    /** Quando uma transferência termina e a pasta de destino é a que está na tela, lê a pasta de novo (os arquivos novos aparecem). */
    private void aoMudarAsTransferencias(List<FilaDeTransferencias.Retrato> retratos) {
        boolean algumaTerminou = false;
        for (FilaDeTransferencias.Retrato r : retratos) {
            if (r.estado().terminou() && transferenciasJaNotadas.putIfAbsent(r.id(), Boolean.TRUE) == null) {
                algumaTerminou = true;
            }
        }
        if (algumaTerminou && preparado && situacao != Situacao.CARREGANDO) {
            recarregarSemMudarDePasta();
        }
    }

    private boolean conectado() {
        return estadoDaConexao == null || estadoDaConexao.conexao().permiteExec();
    }

    // ---------------------------------------------------------------------------------------------
    // Estado da conexão
    // ---------------------------------------------------------------------------------------------

    /** O estado da conexão com o servidor mudou (na EDT). Público para os testes alimentarem a tela com dados fixos. */
    public void aoMudarEstadoDaConexao(EstadoServidor novo) {
        if (encerrado) {
            return;
        }
        boolean estavaConectado = conectado();
        this.estadoDaConexao = novo;
        boolean agora = conectado();
        if (!agora) {
            semConexaoVista = true;
            if (situacao != Situacao.SFTP_INDISPONIVEL) {
                definirSituacao(Situacao.SEM_CONEXAO);
            }
        } else if (!estavaConectado || semConexaoVista) {
            semConexaoVista = false;
            tentativasDeReconexao = 0;
            if (iniciado) {
                if (preparado) {
                    atualizar();
                } else {
                    preparar(null, null);
                }
            }
        }
        atualizarBotoes();
    }

    private void agendarNovaTentativa() {
        if (encerrado || tentativasDeReconexao >= 5) {
            return;
        }
        tentativasDeReconexao++;
        if (reconexao != null) {
            reconexao.stop();
        }
        reconexao = new Timer(1500, e -> {
            if (encerrado || !conectado()) {
                return;
            }
            if (preparado) {
                atualizar();
            } else {
                preparar(null, null);
            }
        });
        reconexao.setRepeats(false);
        reconexao.start();
    }

    // ---------------------------------------------------------------------------------------------
    // Navegação
    // ---------------------------------------------------------------------------------------------

    /** Abre uma pasta (um clique nas migalhas, na árvore, ou um duplo clique numa pasta). */
    public void irPara(String caminho) {
        abrirPasta(caminho, true, filtroEfetivoNoServidor());
    }

    /** Sobe uma pasta (sem passar da raiz do modo). */
    public void subir() {
        if (!preparado || caminhoAtual == null || caminhoAtual.equals(escopo.raiz())) {
            return;
        }
        irPara(CaminhoRemoto.pai(caminhoAtual));
    }

    /** Volta à pasta anterior. */
    public void voltar() {
        if (pilhaDeVoltar.isEmpty()) {
            return;
        }
        abrirPasta(pilhaDeVoltar.pop(), false, filtroEfetivoNoServidor());
    }

    /** Lê a pasta de novo no servidor, esquecendo o que já tinha conferido dos links. */
    public void atualizar() {
        if (!preparado) {
            if (iniciado) {
                preparar(null, null);
            }
            return;
        }
        servico.esquecerConferencias();
        arvore.recarregar();
        abrirPasta(caminhoAtual == null ? escopo.raiz() : caminhoAtual, false, filtroEfetivoNoServidor());
    }

    private void recarregarSemMudarDePasta() {
        if (caminhoAtual != null && preparado) {
            abrirPasta(caminhoAtual, false, filtroEfetivoNoServidor());
        }
    }

    private String filtroEfetivoNoServidor() {
        return listagem != null && listagem.truncada() && !filtro.isBlank() ? filtro.strip() : null;
    }

    private void abrirPasta(String caminho, boolean empilhar, String filtroDoServidor) {
        if (!preparado || encerrado) {
            return;
        }
        long g = ++geracao;
        String anterior = caminhoAtual;
        boolean mudouDePasta = anterior == null || !anterior.equals(caminho);
        if (empilhar && anterior != null && mudouDePasta) {
            pilhaDeVoltar.push(anterior); // antes de ler: se a pasta nova não abrir, "Voltar" leva de volta de onde o usuário veio
        }
        if (mudouDePasta) {
            definirSituacao(Situacao.CARREGANDO);
        }
        emVoo.incrementAndGet();
        ServicoDeArquivos s = servico;
        Tarefas.rodar(() -> s.listar(caminho, ListagemDePasta.LIMITE_PADRAO,
            filtroDoServidor == null ? n -> true : n -> VisaoDaPasta.casaComFiltro(n, filtroDoServidor)), l -> {
                emVoo.decrementAndGet();
                if (g != geracao || encerrado) {
                    return;
                }
                listagemFiltradaNoServidor = filtroDoServidor != null;
                aplicarListagem(l, true);
            }, erro -> {
                emVoo.decrementAndGet();
                if (g == geracao && !encerrado) {
                    tratarErro(erro, caminho);
                }
            });
    }

    private void aplicarListagem(ListagemDePasta l, boolean revelarNaArvore) {
        this.listagem = l;
        this.caminhoAtual = l.caminho();
        this.ultimaLeitura = ctx.relogio().get();
        this.erroDaPasta = "";
        tentativasDeReconexao = 0;
        barraDeCaminho.definir(escopo, caminhoAtual);
        if (ctx.modoProjeto()) {
            memoria.lembrarDoProjeto(ctx.servidorId(), ctx.projeto(), caminhoAtual);
        } else {
            memoria.lembrarDoServidor(ctx.servidorId(), raizAtual, caminhoAtual);
        }
        arvore.revelar(caminhoAtual);
        redesenharLista();
    }

    /** Refaz a tabela e o estado a partir da listagem de agora, do filtro, dos ocultos e da ordem. */
    private void redesenharLista() {
        if (listagem == null) {
            return;
        }
        List<EntradaRemota> visiveis = VisaoDaPasta.montar(listagem.entradas(), listagemFiltradaNoServidor ? "" : filtro, mostrarOcultos, ordem);
        tabela.definirOrdem(ordem);
        tabela.definir(visiveis);
        tabela.relogio(ctx.relogio().get(), ctx.fuso());
        if (visiveis.isEmpty()) {
            mostrarVazia();
        } else {
            definirSituacao(Situacao.NORMAL);
        }
        resumo.setText(textoDoResumo(visiveis));
        resumo.setToolTipText(resumo.getText());
        atualizarBotoes();
    }

    private void mostrarVazia() {
        boolean comFiltro = !filtro.isBlank();
        boolean soOcultos = !mostrarOcultos && listagem.entradas().stream().anyMatch(EntradaRemota::oculto);
        String frase = comFiltro ? "Nada encontrado com este filtro." : "Esta pasta está vazia.";
        String explicacao = comFiltro ? (listagem.truncada() ? "Procurei na pasta inteira, não só nos itens à vista." : "")
            : soOcultos ? "Só há itens ocultos aqui (os que começam com ponto). Ligue \"Mostrar ocultos\" para vê-los." : "";
        cartao.mostrar("pasta", Gravidade.NEUTRO, frase, explicacao, null, null);
        definirSituacao(Situacao.VAZIA);
    }

    private String textoDoResumo(List<EntradaRemota> visiveis) {
        if (listagem == null) {
            return "";
        }
        long pastas = visiveis.stream().filter(EntradaRemota::ehPasta).count();
        long arquivos = visiveis.size() - pastas;
        String base = pastas + (pastas == 1 ? " pasta" : " pastas") + ", " + arquivos + (arquivos == 1 ? " arquivo" : " arquivos");
        if (listagem.truncada()) {
            String total = listagem.totalExato() ? Formato.fmtNum(listagem.total()) : "mais de " + Formato.fmtNum(listagem.total());
            return listagemFiltradaNoServidor
                ? "Mostrando " + Formato.fmtNum(listagem.entradas().size()) + " de " + total + " itens com este filtro"
                : "Mostrando " + Formato.fmtNum(listagem.entradas().size()) + " de " + total + " itens: use o filtro";
        }
        if (listagemFiltradaNoServidor) {
            return base + " com este filtro";
        }
        return base;
    }

    // ---------------------------------------------------------------------------------------------
    // Situações
    // ---------------------------------------------------------------------------------------------

    private void definirSituacao(Situacao nova) {
        this.situacao = nova;
        boolean comDados = listagem != null;
        faixaDeDados.setVisible(false);
        tabelaEsmaecivel.esmaecer(false);
        if (nova == Situacao.SEM_PERMISSAO || nova == Situacao.NAO_ENCONTRADA || nova == Situacao.ERRO) {
            resumo.setText(""); // a contagem de "N pastas, N arquivos" era da pasta de antes
        }
        switch (nova) {
            case INICIANDO, CARREGANDO -> cartoesDaLista.show(areaDaLista, CARTAO_ESQUELETO);
            case NORMAL -> cartoesDaLista.show(areaDaLista, CARTAO_TABELA);
            case VAZIA -> cartoesDaLista.show(areaDaLista, CARTAO_MENSAGEM);
            case SEM_CONEXAO -> {
                SituacaoDaVisao v = SituacaoDaVisao.de(estadoDaConexao, comDados, ultimaLeitura, ctx.relogio().get(), ctx.fuso());
                String titulo = comDados ? v.titulo() : "Sem conexão com o servidor";
                String texto = v.temAviso() ? v.texto() : "O app está tentando conectar de novo.";
                if (comDados && tabela.getRowCount() > 0) {
                    faixaDeDados.mostrar(v.temAviso() ? v : SituacaoDaVisao.de(estadoDaConexao == null ? semConexaoPadrao() : estadoDaConexao, true,
                        ultimaLeitura, ctx.relogio().get(), ctx.fuso()));
                    tabelaEsmaecivel.esmaecer(true);
                    cartoesDaLista.show(areaDaLista, CARTAO_TABELA);
                } else {
                    cartao.mostrar("rede", Gravidade.ATENCAO, titulo, texto + " Assim que a conexão voltar, os arquivos aparecem aqui.", null, null);
                    cartoesDaLista.show(areaDaLista, CARTAO_MENSAGEM);
                }
            }
            case SFTP_INDISPONIVEL -> {
                cartao.mostrar("alerta", Gravidade.ATENCAO, "Este servidor não aceita transferência de arquivos",
                    "O servidor está conectado, mas o serviço de arquivos dele (SFTP) está desligado ou não liberado para este usuário. "
                        + "Quem administra o servidor pode ligá-lo (a linha \"Subsystem sftp\" do sshd_config). Enquanto isso, o resto do app funciona.",
                    "Tentar de novo", this::atualizarDoZero);
                cartoesDaLista.show(areaDaLista, CARTAO_MENSAGEM);
            }
            case SEM_PERMISSAO -> {
                cartao.mostrar("alerta", Gravidade.ATENCAO, "Sem permissão para abrir esta pasta",
                    "O usuário que o app usa neste servidor não tem permissão de leitura nela.",
                    pilhaDeVoltar.isEmpty() ? "Subir uma pasta" : "Voltar", () -> {
                        if (!pilhaDeVoltar.isEmpty()) {
                            voltar();
                        } else {
                            subir();
                        }
                    });
                cartoesDaLista.show(areaDaLista, CARTAO_MENSAGEM);
            }
            case NAO_ENCONTRADA -> {
                // a raiz do projeto sumiu (ou nunca existiu naquela pasta de projetos): a saída é apontar onde os projetos ficam
                boolean raizDoProjeto = ctx.modoProjeto() && (!preparado || caminhoAtual == null || caminhoAtual.equals(escopo.raiz()));
                boolean podeSubir = preparado && caminhoAtual != null && !caminhoAtual.equals(escopo.raiz());
                cartao.mostrar("pasta", Gravidade.ATENCAO, raizDoProjeto ? "A pasta deste projeto não foi encontrada" : "Esta pasta não existe (mais)",
                    raizDoProjeto ? "Procurei em " + memoria.raizDosProjetos(ctx.servidorId()) + "/" + TextoSeguro.limparLinha(ctx.projeto())
                        + ". Se os projetos deste servidor ficam em outra pasta, diga qual."
                        : erroDaPasta.isEmpty() ? "Ela pode ter sido apagada ou renomeada." : erroDaPasta,
                    raizDoProjeto ? "Mudar a pasta dos projetos…" : podeSubir ? "Subir uma pasta" : "Tentar de novo",
                    raizDoProjeto ? this::pedirRaizDosProjetos : podeSubir ? this::subir : this::atualizarDoZero);
                cartoesDaLista.show(areaDaLista, CARTAO_MENSAGEM);
            }
            case ERRO -> {
                cartao.mostrar("alerta", Gravidade.PERIGO, "Não consegui abrir a pasta", erroDaPasta, "Tentar de novo", this::atualizarDoZero);
                cartoesDaLista.show(areaDaLista, CARTAO_MENSAGEM);
            }
        }
        atualizarBotoes();
    }

    private EstadoServidor semConexaoPadrao() {
        return new EstadoServidor(ctx.servidorId(), ctx.servidorNome(), EstadoConexao.RECONECTANDO, null, 0, null, null, 0,
            br.com.labdesktop.cofre.Estado2fa.DESLIGADO, null, null, null);
    }

    private void atualizarDoZero() {
        if (preparado) {
            atualizar();
        } else {
            preparar(null, null);
        }
    }

    private void tratarErro(Throwable erro, String doCaminho) {
        ErroSftp e = ErroSftp.de(erro, "abrir esta pasta");
        if (!(erro instanceof ErroSftp) && e.tipo() == ErroSftp.Tipo.OUTRO) {
            LOG.warn("Erro inesperado nos arquivos: {}", erro.toString());
        }
        if (doCaminho != null) {
            this.caminhoAtual = e.tipo() == ErroSftp.Tipo.FORA_DA_RAIZ ? caminhoAtual : doCaminho;
        }
        erroDaPasta = e.mensagemSimples();
        switch (e.tipo()) {
            case SEM_CONEXAO -> {
                semConexaoVista = true;
                definirSituacao(Situacao.SEM_CONEXAO);
                agendarNovaTentativa();
            }
            case SFTP_INDISPONIVEL -> definirSituacao(Situacao.SFTP_INDISPONIVEL);
            case SEM_PERMISSAO -> {
                if (escopo != null) {
                    barraDeCaminho.definir(escopo, caminhoAtual);
                    arvore.revelar(caminhoAtual);
                }
                definirSituacao(Situacao.SEM_PERMISSAO);
            }
            case NAO_EXISTE -> {
                if (escopo != null) {
                    barraDeCaminho.definir(escopo, caminhoAtual);
                }
                definirSituacao(Situacao.NAO_ENCONTRADA);
            }
            case FORA_DA_RAIZ, NOME_INVALIDO, PROTEGIDO -> {
                ctx.avisos().atencao(e.mensagemSimples());
                definirSituacao(listagem == null ? Situacao.ERRO : (tabela.getRowCount() == 0 ? Situacao.VAZIA : Situacao.NORMAL));
                if (listagem != null) {
                    this.caminhoAtual = listagem.caminho();
                }
            }
            default -> definirSituacao(Situacao.ERRO);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Filtro, ocultos, ordem
    // ---------------------------------------------------------------------------------------------

    /** Troca o filtro de texto (a cada tecla). Numa pasta com mais itens que o limite, o servidor refaz a busca na pasta toda. */
    public void filtrar(String novo) {
        String f = novo == null ? "" : novo;
        if (f.equals(filtro)) {
            return;
        }
        filtro = f;
        if (!campoDeFiltro.getText().equals(f)) {
            campoDeFiltro.setText(f);
        }
        redesenharLista();
        if (listagem != null && (listagem.truncada() || listagemFiltradaNoServidor)) {
            if (filtroNoServidor != null) {
                filtroNoServidor.stop();
            }
            filtroNoServidor = new Timer(ESPERA_DO_FILTRO_NO_SERVIDOR_MS, e -> {
                if (preparado && caminhoAtual != null) {
                    String doServidor = filtro.isBlank() ? null : filtro.strip();
                    listagemFiltradaNoServidor = doServidor != null;
                    abrirPasta(caminhoAtual, false, doServidor);
                }
            });
            filtroNoServidor.setRepeats(false);
            filtroNoServidor.start();
        }
    }

    /** Liga ou desliga os arquivos ocultos (os que começam com ponto). */
    public void mostrarOcultos(boolean sim) {
        mostrarOcultos = sim;
        if (caixaDeOcultos.isSelected() != sim) {
            caixaDeOcultos.setSelected(sim);
        }
        arvore.mostrarOcultos(sim);
        if (preparado) {
            arvore.recarregar();
        }
        redesenharLista();
    }

    /** Clicar no cabeçalho de uma coluna (ou o menu "Ordenar por"). */
    public void ordenarPor(Coluna coluna) {
        ordem = ordem.clicar(coluna);
        redesenharLista();
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir
    // ---------------------------------------------------------------------------------------------

    /** Duplo clique ou Enter: pasta abre a pasta; arquivo, no editor (ou, se não é texto, oferece baixar). */
    public void abrirEntrada(EntradaRemota e) {
        if (e.ehPasta()) {
            irPara(e.caminho());
        } else if (e.tipo() == EntradaRemota.Tipo.LINK_QUEBRADO) {
            ctx.avisos().atencao("Este link não leva a lugar nenhum" + (e.destinoDoLink() == null ? "." : " (ele aponta para " + TextoSeguro.limparLinha(e.destinoDoLink()) + ")."));
        } else if (e.ehArquivo()) {
            abrirNoEditor(e);
        } else {
            ctx.avisos().atencao("Este item é especial (não é arquivo nem pasta) e não abre aqui.");
        }
    }

    private void abrirNoEditor(EntradaRemota e) {
        SessaoDeEdicao ja = editores.get(e.caminho());
        if (ja != null) {
            abas.selecionar(ja.id);
            return;
        }
        if (!preparado) {
            return;
        }
        EditorRemoto er = editorRemoto;
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> er.abrir(e.caminho()), aberto -> {
            emVoo.decrementAndGet();
            if (!encerrado) {
                novaAbaDeEditor(e, aberto);
            }
        }, erro -> {
            emVoo.decrementAndGet();
            ErroSftp f = ErroSftp.de(erro, "abrir este arquivo");
            if (f.tipo() == ErroSftp.Tipo.NAO_E_TEXTO) {
                oferecerBaixar(e, f.mensagemSimples());
            } else {
                ctx.avisos().erro(f.mensagemSimples());
                if (f.tipo() == ErroSftp.Tipo.NAO_EXISTE) {
                    recarregarSemMudarDePasta();
                }
            }
        });
    }

    private void oferecerBaixar(EntradaRemota e, String frase) {
        ctx.apresentador().mostrar(new DialogoConfirmar("Este arquivo não abre aqui", "baixar", frase, "Baixar", false, ok -> {
            if (ok) {
                baixar(List.of(e));
            }
        }));
    }

    private void novaAbaDeEditor(EntradaRemota entrada, br.com.labdesktop.arquivos.EditorRemoto.Aberto aberto) {
        String id = PREFIXO_DE_EDITOR + proximoEditor++;
        SessaoDeEdicao s = new SessaoDeEdicao(id, entrada, aberto);
        editores.put(entrada.caminho(), s);
        abas.adicionar(id, TextoSeguro.limparLinha(aberto.nome()));
        abas.setVisible(true);
        corpo.add(s.painel, id);
        abas.selecionar(id);
        s.atualizarPodeDesfazer();
        revalidate();
        s.painel.area().requestFocusInWindow();
    }

    /** Uma aba de editor e o que ela sabe de si. */
    private final class SessaoDeEdicao implements EditorDeTexto.Acoes {
        final String id;
        final EntradaRemota entrada;
        final EditorDeTexto painel;
        private boolean salvando;
        private boolean fecharDepoisDeSalvar;

        SessaoDeEdicao(String id, EntradaRemota entrada, br.com.labdesktop.arquivos.EditorRemoto.Aberto aberto) {
            this.id = id;
            this.entrada = entrada;
            this.painel = new EditorDeTexto(aberto, !ctx.escritaLiberada(), this);
        }

        /** Liga "Desfazer a última edição" se há cópia local anterior. Procurar a cópia é disco: roda fora da EDT. */
        void atualizarPodeDesfazer() {
            EditorRemoto er = editorRemoto;
            if (er == null) {
                painel.definirPodeDesfazer(false);
                return;
            }
            var aberto = painel.aberto();
            Tarefas.rodar(() -> er.ultimaVersaoGuardada(aberto).isPresent(), painel::definirPodeDesfazer, erro -> painel.definirPodeDesfazer(false));
        }

        @Override
        public void salvar() {
            salvarEditor(this, false, false);
        }

        @Override
        public void recarregar() {
            recarregarEditor(this);
        }

        @Override
        public void desfazerUltimaEdicao() {
            desfazerEdicao(this);
        }

        @Override
        public void fechar() {
            fecharEditor(this, null);
        }

        @Override
        public void modificadoMudou(boolean modificado) {
            abas.rotulo(id, TextoSeguro.limparLinha(painel.aberto().nome()) + (modificado ? " *" : ""));
        }
    }

    private void salvarEditor(SessaoDeEdicao s, boolean sobrescrever, boolean comSudo) {
        if (!ctx.escritaLiberada() || s.salvando) {
            return;
        }
        s.salvando = true;
        s.painel.limparAviso();
        String texto = s.painel.texto();
        var base = s.painel.aberto();
        EditorRemoto er = editorRemoto;
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> comSudo ? er.salvarComSudo(base, texto, sobrescrever) : er.salvar(base, texto, sobrescrever), novo -> {
            emVoo.decrementAndGet();
            s.salvando = false;
            s.painel.marcarComoSalvo(novo, texto);
            s.atualizarPodeDesfazer();
            ctx.avisos().ok("Salvei " + TextoSeguro.limparLinha(novo.nome()) + ".");
            recarregarSemMudarDePasta();
            if (s.fecharDepoisDeSalvar) {
                s.fecharDepoisDeSalvar = false;
                removerEditor(s);
            }
        }, erro -> {
            emVoo.decrementAndGet();
            s.salvando = false;
            s.fecharDepoisDeSalvar = false;
            tratarErroDeSalvar(s, ErroSftp.de(erro, "gravar este arquivo"), texto, comSudo);
        });
    }

    private void tratarErroDeSalvar(SessaoDeEdicao s, ErroSftp e, String texto, boolean eraSudo) {
        String nome = s.painel.aberto().nome();
        switch (e.tipo()) {
            case ARQUIVO_MUDOU -> ctx.apresentador().mostrar(new DialogoConflitoDeSalvar(nome, e.mensagemSimples().contains("apagado"), r -> {
                switch (r) {
                    case RECARREGAR -> recarregarEditor(s);
                    case SOBRESCREVER -> salvarEditor(s, true, eraSudo);
                    case CANCELAR -> { }
                }
            }));
            case SEM_PERMISSAO, OUTRO_DONO -> {
                if (eraSudo || ctx.executorDeSudo() == null) {
                    s.painel.mostrarAviso(e.mensagemSimples(), Gravidade.PERIGO);
                    ctx.avisos().erro(e.mensagemSimples());
                    return;
                }
                String dono = s.painel.aberto().dono();
                String quem = dono == null || dono.isBlank() ? "Você não tem permissão para gravar neste arquivo."
                    : "Este arquivo é de \"" + TextoSeguro.limparLinha(dono) + "\" e o usuário que o app usa neste servidor não pode gravar nele.";
                ctx.apresentador().mostrar(new DialogoConfirmar("Salvar com sudo?", "chave", quem
                    + " Posso tentar salvar com sudo: o servidor precisa deixar este usuário usar sudo sem digitar senha. "
                    + "Antes, o conteúdo que está no servidor vira uma cópia no seu computador.", "Salvar com sudo", true, true, ok -> {
                        if (ok) {
                            salvarEditor(s, false, true);
                        }
                    }));
            }
            default -> {
                s.painel.mostrarAviso(e.mensagemSimples(), Gravidade.PERIGO);
                ctx.avisos().erro(e.mensagemSimples());
            }
        }
    }

    private void recarregarEditor(SessaoDeEdicao s) {
        EditorRemoto er = editorRemoto;
        String caminho = s.entrada.caminho();
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> er.abrir(caminho), aberto -> {
            emVoo.decrementAndGet();
            s.painel.definirConteudo(aberto, aberto.texto());
            s.painel.limparAviso();
            s.atualizarPodeDesfazer();
        }, erro -> {
            emVoo.decrementAndGet();
            ctx.avisos().erro(ErroSftp.de(erro, "abrir este arquivo").mensagemSimples());
        });
    }

    private void desfazerEdicao(SessaoDeEdicao s) {
        if (!ctx.escritaLiberada()) {
            return;
        }
        var base = s.painel.aberto();
        EditorRemoto er = editorRemoto;
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> er.desfazerUltimaEdicao(base), novo -> {
            emVoo.decrementAndGet();
            s.painel.definirConteudo(novo, novo.texto());
            s.atualizarPodeDesfazer();
            ctx.avisos().ok("Voltei " + TextoSeguro.limparLinha(novo.nome()) + " à versão anterior. O que estava lá virou uma cópia no seu computador.");
        }, erro -> {
            emVoo.decrementAndGet();
            ErroSftp f = ErroSftp.de(erro, "gravar este arquivo");
            if (f.tipo() == ErroSftp.Tipo.SEM_PERMISSAO || f.tipo() == ErroSftp.Tipo.OUTRO_DONO) {
                tratarErroDeSalvar(s, f, s.painel.texto(), false);
            } else {
                ctx.avisos().erro(f.mensagemSimples());
            }
        });
    }

    /** Fecha o editor; com alterações, pergunta (Salvar, Descartar, Cancelar). */
    private void fecharEditor(SessaoDeEdicao s, Runnable depois) {
        if (!s.painel.modificado()) {
            removerEditor(s);
            if (depois != null) {
                depois.run();
            }
            return;
        }
        ctx.apresentador().mostrar(new DialogoFecharEditor(s.painel.aberto().nome(), ctx.escritaLiberada(), r -> {
            switch (r) {
                case SALVAR -> {
                    s.fecharDepoisDeSalvar = true;
                    salvarEditor(s, false, false);
                }
                case DESCARTAR -> removerEditor(s);
                case CANCELAR -> { }
            }
        }));
    }

    private void removerEditor(SessaoDeEdicao s) {
        editores.remove(s.entrada.caminho());
        corpo.remove(s.painel);
        abas.remover(s.id, editores.isEmpty() ? ABA_NAVEGADOR : editores.values().iterator().next().id);
        abas.setVisible(!editores.isEmpty());
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------------------------------------
    // Criar, renomear, mover, apagar, copiar caminho
    // ---------------------------------------------------------------------------------------------

    private void pedirNovaPasta() {
        if (!podeEscrever()) {
            return;
        }
        ctx.apresentador().mostrar(new DialogoNome("Nova pasta", "pasta-mais", "Nome da pasta nova", "Nome da pasta", "", "Criar pasta",
            GerenciadorDeArquivos::validarNome, this::criarPasta));
    }

    private void pedirNovoArquivo() {
        if (!podeEscrever()) {
            return;
        }
        ctx.apresentador().mostrar(new DialogoNome("Novo arquivo", "arquivo-mais", "Nome do arquivo novo (vazio, para você escrever depois)",
            "Nome do arquivo", "", "Criar arquivo", GerenciadorDeArquivos::validarNome, this::criarArquivo));
    }

    private static String validarNome(String texto) {
        try {
            CaminhoRemoto.validarNome(texto);
            return null;
        } catch (ErroSftp e) {
            return e.mensagemSimples();
        }
    }

    /** Cria uma pasta na pasta atual (o que o diálogo "Nova pasta" faz ao confirmar). */
    public void criarPasta(String nome) {
        if (!podeEscrever()) {
            return;
        }
        String pasta = caminhoAtual;
        ServicoDeArquivos s = servico;
        executarEscrita(() -> s.criarPasta(pasta, nome), criado -> {
            ctx.avisos().ok("Criei a pasta " + TextoSeguro.limparLinha(nome) + ".");
            recarregarEDestacar(criado);
        });
    }

    /** Cria um arquivo vazio na pasta atual e o abre no editor. */
    public void criarArquivo(String nome) {
        if (!podeEscrever()) {
            return;
        }
        String pasta = caminhoAtual;
        ServicoDeArquivos s = servico;
        executarEscrita(() -> s.criarArquivoVazio(pasta, nome), criado -> {
            ctx.avisos().ok("Criei o arquivo " + TextoSeguro.limparLinha(nome) + ".");
            recarregarEDestacar(criado);
            EntradaRemota nova = new EntradaRemota(CaminhoRemoto.nome(criado), criado, EntradaRemota.Tipo.ARQUIVO, false, 0, ctx.relogio().get(), 0644, "", "", null);
            abrirNoEditor(nova);
        });
    }

    private void pedirRenomear() {
        List<EntradaRemota> sel = tabela.selecionadas();
        if (!podeEscrever() || sel.size() != 1) {
            return;
        }
        EntradaRemota e = sel.get(0);
        String nome = e.nome();
        int ponto = nome.lastIndexOf('.');
        ctx.apresentador().mostrar(new DialogoNome("Renomear", "editar", "Novo nome para " + TextoSeguro.limparLinha(nome), "Novo nome", nome,
            ponto > 0 && !e.ehPasta() ? ponto : -1, "Renomear", GerenciadorDeArquivos::validarNome, novo -> renomear(e, novo)));
    }

    /** Muda o nome de um item (o que o diálogo "Renomear" faz ao confirmar). */
    public void renomear(EntradaRemota e, String novoNome) {
        if (!podeEscrever()) {
            return;
        }
        if (temEditorAbertoSob(e.caminho())) {
            ctx.avisos().atencao("Feche o editor de " + TextoSeguro.limparLinha(e.nome()) + " antes de renomear.");
            return;
        }
        ServicoDeArquivos s = servico;
        executarEscrita(() -> s.renomear(e, novoNome), novo -> {
            ctx.avisos().ok("Renomeei para " + TextoSeguro.limparLinha(CaminhoRemoto.nome(novo)) + ".");
            recarregarEDestacar(novo);
        });
    }

    private void pedirMover() {
        List<EntradaRemota> sel = tabela.selecionadas();
        if (!podeEscrever() || sel.isEmpty()) {
            return;
        }
        String oQue = sel.size() == 1 ? sel.get(0).nome() : sel.size() + " itens";
        ctx.apresentador().mostrar(new DialogoEscolherPasta(oQue, caminhoAtual, escopo.raiz(), escopo.nome(), new CarregadorDaArvore(),
            destino -> mover(sel, destino)));
    }

    /** Move itens para uma pasta (o "Mover para…" e o arrastar dentro do servidor). */
    public void mover(List<EntradaRemota> entradas, String destino) {
        if (!podeEscrever() || entradas.isEmpty()) {
            return;
        }
        for (EntradaRemota e : entradas) {
            if (temEditorAbertoSob(e.caminho())) {
                ctx.avisos().atencao("Feche o editor de " + TextoSeguro.limparLinha(e.nome()) + " antes de mover.");
                return;
            }
        }
        ServicoDeArquivos s = servico;
        executarEscrita(() -> s.mover(List.copyOf(entradas), destino), (ResultadoDoLote r) -> {
            if (r.tudoCerto()) {
                ctx.avisos().ok(r.feitos() == 0 ? "Nada a mover: já está nessa pasta." : r.feitos() == 1 ? "Movi 1 item." : "Movi " + r.feitos() + " itens.");
            } else {
                ResultadoDoLote.Falha f = r.falhas().get(0);
                ctx.avisos().atencao((r.feitos() > 0 ? "Movi " + r.feitos() + ", mas " : "Não movi: ") + TextoSeguro.limparLinha(f.nome()) + ": " + f.mensagem()
                    + (r.falhas().size() > 1 ? " (e mais " + (r.falhas().size() - 1) + " com problema)" : ""));
            }
            arvore.recarregar();
            recarregarSemMudarDePasta();
        });
    }

    private void pedirApagar() {
        List<EntradaRemota> sel = tabela.selecionadas();
        if (!podeEscrever() || sel.isEmpty()) {
            return;
        }
        for (EntradaRemota e : sel) {
            if (escopo.protegido(e.caminho())) {
                ctx.avisos().atencao("\"" + TextoSeguro.limparLinha(e.nome()) + "\" sustenta o gerenciador (é a raiz, a pasta dos projetos ou a pasta pessoal): não deixo apagar.");
                return;
            }
            if (temEditorAbertoSob(e.caminho())) {
                ctx.avisos().atencao("Feche o editor de " + TextoSeguro.limparLinha(e.nome()) + " antes de apagar.");
                return;
            }
        }
        ServicoDeArquivos s = servico;
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> somarParaApagar(s, sel), resumo -> {
            emVoo.decrementAndGet();
            List<DialogoApagarArquivos.Item> itens = new ArrayList<>();
            for (EntradaRemota e : sel) {
                itens.add(new DialogoApagarArquivos.Item(e.nome(), e.ehPasta(), e.link()));
            }
            ctx.apresentador().mostrar(new DialogoApagarArquivos(itens, resumo, ok -> {
                if (ok) {
                    apagar(sel);
                }
            }));
        }, erro -> {
            emVoo.decrementAndGet();
            tratarErroDeEscrita(erro);
        });
    }

    private static ResumoDeTamanho somarParaApagar(ServicoDeArquivos s, List<EntradaRemota> sel) throws ErroSftp {
        long arquivos = 0;
        long pastas = 0;
        long bytes = 0;
        boolean completo = true;
        for (EntradaRemota e : sel) {
            ResumoDeTamanho r = s.resumirParaApagar(e, Cancelamento.NUNCA);
            arquivos += r.arquivos();
            pastas += r.pastas();
            bytes += r.bytes();
            completo &= r.completo();
        }
        return new ResumoDeTamanho(arquivos, pastas, bytes, completo);
    }

    /** Apaga os itens (depois da confirmação). */
    public void apagar(List<EntradaRemota> itens) {
        if (!podeEscrever()) {
            return;
        }
        ServicoDeArquivos s = servico;
        executarEscrita(() -> {
            int feitos = 0;
            List<String> falhas = new ArrayList<>();
            for (EntradaRemota e : itens) {
                try {
                    s.apagar(e);
                    feitos++;
                } catch (ErroSftp f) {
                    if (f.tipo() == ErroSftp.Tipo.SEM_CONEXAO) {
                        throw f;
                    }
                    falhas.add(TextoSeguro.limparLinha(e.nome()) + ": " + f.mensagemSimples());
                }
            }
            return new ResultadoDoLote(feitos, falhas.stream().map(t -> new ResultadoDoLote.Falha("", t)).toList());
        }, (ResultadoDoLote r) -> {
            if (r.tudoCerto()) {
                ctx.avisos().ok(r.feitos() == 1 ? "Apaguei " + TextoSeguro.limparLinha(itens.get(0).nome()) + "." : "Apaguei " + r.feitos() + " itens.");
            } else {
                ctx.avisos().atencao((r.feitos() > 0 ? "Apaguei " + r.feitos() + ", mas " : "Não apaguei: ") + r.falhas().get(0).mensagem());
            }
            arvore.recarregar();
            recarregarSemMudarDePasta();
        });
    }

    /** Copia o caminho do que está selecionado (ou o da pasta atual) para a área de transferência. */
    public void copiarCaminho() {
        List<EntradaRemota> sel = tabela.selecionadas();
        String texto = sel.isEmpty() ? (caminhoAtual == null ? "" : caminhoAtual)
            : String.join("\n", sel.stream().map(EntradaRemota::caminho).toList());
        if (texto.isEmpty()) {
            return;
        }
        if (ctx.copiador().copiar(texto)) {
            ctx.avisos().ok(sel.size() > 1 ? "Copiei os caminhos." : "Copiei o caminho.");
        } else {
            ctx.avisos().erro("Não consegui usar a área de transferência.");
        }
    }

    private boolean temEditorAbertoSob(String caminho) {
        for (String aberto : editores.keySet()) {
            if (CaminhoRemoto.dentroDe(caminho, aberto)) {
                return true;
            }
        }
        return false;
    }

    private boolean podeEscrever() {
        if (!ctx.escritaLiberada()) {
            ctx.avisos().atencao("Esta tela está somente leitura nesta versão: não dá para criar, renomear, mover, enviar nem apagar.");
            return false;
        }
        return preparado && conectado() && caminhoAtual != null;
    }

    @FunctionalInterface
    private interface Escrita<T> {
        T executar() throws ErroSftp;
    }

    private <T> void executarEscrita(Escrita<T> trabalho, Consumer<T> aoConcluir) {
        emVoo.incrementAndGet();
        Tarefas.rodar(trabalho::executar, r -> {
            emVoo.decrementAndGet();
            if (!encerrado) {
                aoConcluir.accept(r);
            }
        }, erro -> {
            emVoo.decrementAndGet();
            tratarErroDeEscrita(erro);
        });
    }

    private void tratarErroDeEscrita(Throwable erro) {
        ErroSftp e = ErroSftp.de(erro, "fazer isso aqui");
        if (e.tipo() == ErroSftp.Tipo.SEM_CONEXAO) {
            ctx.avisos().atencao(e.mensagemSimples());
            agendarNovaTentativa();
        } else {
            ctx.avisos().erro(e.mensagemSimples());
        }
    }

    private void recarregarEDestacar(String caminho) {
        long g = ++geracao;
        String pasta = caminhoAtual;
        ServicoDeArquivos s = servico;
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> s.listar(pasta, ListagemDePasta.LIMITE_PADRAO, n -> true), l -> {
            emVoo.decrementAndGet();
            if (g != geracao || encerrado) {
                return;
            }
            listagemFiltradaNoServidor = false;
            aplicarListagem(l, true);
            tabela.selecionarCaminho(caminho);
        }, erro -> {
            emVoo.decrementAndGet();
            tratarErro(erro, pasta);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Enviar e baixar
    // ---------------------------------------------------------------------------------------------

    private void escolherEEnviar() {
        if (!podeEscrever()) {
            return;
        }
        List<Path> escolhidos = ctx.escolhedor().escolherParaEnviar();
        if (!escolhidos.isEmpty()) {
            enviarArquivosLocais(escolhidos, caminhoAtual);
        }
    }

    /** Envia arquivos e pastas do Mac para uma pasta do servidor (o botão Enviar e o arrastar do Finder). */
    public void enviarArquivosLocais(List<Path> locais, String pastaDestino) {
        if (!podeEscrever() || locais.isEmpty()) {
            return;
        }
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> FilaDeTransferencias.somarLocal(locais), total -> {
            emVoo.decrementAndGet();
            confirmarSeGrande(total, "enviar", () -> {
                garantirFila().enviar(locais, pastaDestino);
                painelDeTransferencias.definirAberto(true);
            });
        }, erro -> {
            emVoo.decrementAndGet();
            ctx.avisos().erro("Não consegui ler o que você escolheu neste computador.");
        });
    }

    private void confirmarSeGrande(long bytes, String verbo, Runnable seguir) {
        if (bytes <= FilaDeTransferencias.LIMITE_DE_AVISO) {
            seguir.run();
            return;
        }
        ctx.apresentador().mostrar(new DialogoConfirmar("Transferência grande", "alerta", "Isto soma " + Formato.fmtBytes(bytes)
            + ". Pode demorar bastante e, se a conexão cair no meio, o app não retoma de onde parou: começa do zero. Continuar?",
            Character.toUpperCase(verbo.charAt(0)) + verbo.substring(1), false, true, ok -> {
                if (ok) {
                    seguir.run();
                }
            }));
    }

    private void baixarSelecionadas() {
        List<EntradaRemota> sel = tabela.selecionadas();
        if (sel.isEmpty() || !preparado || !conectado()) {
            return;
        }
        baixar(sel);
    }

    /** Baixa itens (pastas inclusive) para uma pasta escolhida do Mac (a padrão é Downloads). */
    public void baixar(List<EntradaRemota> itens) {
        if (!preparado || itens.isEmpty()) {
            return;
        }
        Optional<Path> destino = ctx.escolhedor().escolherPastaParaBaixar(ServicosDeArquivos.pastaDeDownloadsPadrao());
        if (destino.isEmpty()) {
            return;
        }
        baixarPara(itens, destino.get());
    }

    /** Baixa itens para uma pasta já escolhida, avisando antes se somam mais de 500 MB. */
    public void baixarPara(List<EntradaRemota> itens, Path pasta) {
        ServicoDeArquivos s = servico;
        emVoo.incrementAndGet();
        Tarefas.rodar(() -> {
            long total = 0;
            for (EntradaRemota e : itens) {
                total += s.somarTamanho(e, Cancelamento.NUNCA);
            }
            return total;
        }, total -> {
            emVoo.decrementAndGet();
            confirmarSeGrande(total, "baixar", () -> {
                garantirFila().baixar(itens, pasta);
                painelDeTransferencias.definirAberto(true);
            });
        }, erro -> {
            emVoo.decrementAndGet();
            tratarErroDeEscrita(erro);
        });
    }

    /** O que a fila pergunta quando o destino já existe: abre o diálogo na EDT e espera a resposta (a thread de transferência bloqueia). */
    private Decisao decidirConflitoDeTransferencia(FilaDeTransferencias.Conflito c) {
        CompletableFuture<Decisao> f = new CompletableFuture<>();
        decisoesPendentes.add(f);
        try {
            if (encerrado) {
                return new Decisao(Escolha.CANCELAR, false);
            }
            Fundo.naEdt(() -> ctx.apresentador().mostrar(new DialogoConflitoDeTransferencia(c, ctx.fuso(), f::complete)));
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Decisao(Escolha.CANCELAR, false);
        } catch (ExecutionException e) {
            return new Decisao(Escolha.CANCELAR, false);
        } finally {
            decisoesPendentes.remove(f);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Raiz do servidor
    // ---------------------------------------------------------------------------------------------

    /** Escolhe a raiz do modo servidor. "Tudo (/)" pede uma confirmação antes. */
    public void selecionarRaiz(RaizDoServidor raiz) {
        if (ctx.modoProjeto() || !iniciado) {
            return;
        }
        if (raiz == RaizDoServidor.TUDO) {
            ctx.apresentador().mostrar(new DialogoConfirmar("Abrir o servidor inteiro?", "alerta",
                "Em \"Tudo (/)\" você navega por TODO o servidor, não só pelos projetos: arquivos do sistema, de outros usuários e de "
                    + "outros programas. Apagar ou mudar o que não deve pode quebrar o servidor. O app nunca apaga a raiz, a pasta dos "
                    + "projetos nem a sua pasta pessoal, mas o resto fica por sua conta.", "Abrir o servidor inteiro", true, true, ok -> {
                        if (ok) {
                            preparar(RaizDoServidor.TUDO, null);
                        }
                    }));
            return;
        }
        preparar(raiz, null);
    }

    private JPopupMenu menuDeRaiz() {
        JPopupMenu m = new JPopupMenu();
        String projetos = memoria.raizDosProjetos(ctx.servidorId());
        m.add(item(RaizDoServidor.PROJETOS.rotulo() + " (" + projetos + ")", () -> selecionarRaiz(RaizDoServidor.PROJETOS)));
        m.add(item(RaizDoServidor.MINHA_PASTA.rotulo() + " (" + home + ")", () -> selecionarRaiz(RaizDoServidor.MINHA_PASTA)));
        m.addSeparator();
        JMenu avancado = new JMenu("Avançado");
        avancado.putClientProperty("html.disable", Boolean.TRUE);
        avancado.add(item("Tudo (/): o servidor inteiro…", () -> selecionarRaiz(RaizDoServidor.TUDO)));
        m.add(avancado);
        m.addSeparator();
        m.add(item("Mudar a pasta dos projetos…", this::pedirRaizDosProjetos));
        return m;
    }

    /** Pede a pasta onde ficam os projetos neste servidor (o padrão é /srv/lab/projects). */
    public void pedirRaizDosProjetos() {
        ctx.apresentador().mostrar(new DialogoNome("Pasta dos projetos", "pasta", "Em que pasta do servidor ficam os projetos? "
            + (ctx.modoProjeto() ? "A pasta de cada projeto é essa pasta mais o nome dele." : ""), "/srv/lab/projects",
            memoria.raizDosProjetos(ctx.servidorId()), "Usar esta pasta", texto -> {
                try {
                    String n = CaminhoRemoto.normalizar(texto.strip());
                    return n.equals("/") ? "A pasta dos projetos não pode ser a raiz (/) do servidor." : null;
                } catch (ErroSftp e) {
                    return e.mensagemSimples();
                }
            }, texto -> {
                try {
                    memoria.definirRaizDosProjetos(ctx.servidorId(), texto);
                } catch (ErroSftp e) {
                    ctx.avisos().erro(e.mensagemSimples());
                    return;
                }
                preparar(ctx.modoProjeto() ? null : RaizDoServidor.PROJETOS, null);
            }));
    }

    // ---------------------------------------------------------------------------------------------
    // Menus
    // ---------------------------------------------------------------------------------------------

    private static JMenuItem item(String texto, Runnable acao) {
        JMenuItem i = new JMenuItem(texto);
        i.putClientProperty("html.disable", Boolean.TRUE);
        i.addActionListener(e -> acao.run());
        return i;
    }

    private JPopupMenu menuDeMais() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem mover = item("Mover para…", this::pedirMover);
        mover.setEnabled(ctx.escritaLiberada() && !tabela.selecionadas().isEmpty() && acoesLiberadasAgora());
        m.add(mover);
        JMenuItem copiar = item("Copiar caminho", this::copiarCaminho);
        copiar.setEnabled(acoesLiberadasAgora());
        m.add(copiar);
        return m;
    }

    private JPopupMenu menuDeContexto() {
        JPopupMenu m = new JPopupMenu();
        List<EntradaRemota> sel = tabela.selecionadas();
        boolean ativas = acoesLiberadasAgora();
        if (!sel.isEmpty()) {
            JMenuItem abrir = item(sel.size() == 1 && sel.get(0).ehPasta() ? "Abrir a pasta" : "Abrir", () -> abrirEntrada(sel.get(0)));
            abrir.setEnabled(ativas && sel.size() == 1);
            m.add(abrir);
            JMenuItem bx = item("Baixar…", this::baixarSelecionadas);
            bx.setEnabled(ativas);
            m.add(bx);
            m.addSeparator();
            JMenuItem rn = item("Renomear", this::pedirRenomear);
            rn.setEnabled(ativas && ctx.escritaLiberada() && sel.size() == 1);
            m.add(rn);
            JMenuItem mv = item("Mover para…", this::pedirMover);
            mv.setEnabled(ativas && ctx.escritaLiberada());
            m.add(mv);
            m.add(item("Copiar caminho", this::copiarCaminho));
            m.addSeparator();
            JMenuItem ap = item("Apagar", this::pedirApagar);
            ap.setEnabled(ativas && ctx.escritaLiberada());
            m.add(ap);
        } else {
            JMenuItem np = item("Nova pasta", this::pedirNovaPasta);
            np.setEnabled(ativas && ctx.escritaLiberada());
            m.add(np);
            JMenuItem na = item("Novo arquivo", this::pedirNovoArquivo);
            na.setEnabled(ativas && ctx.escritaLiberada());
            m.add(na);
            JMenuItem en = item("Enviar para esta pasta…", this::escolherEEnviar);
            en.setEnabled(ativas && ctx.escritaLiberada());
            m.add(en);
            m.addSeparator();
            m.add(item("Copiar o caminho desta pasta", this::copiarCaminho));
            m.add(item("Atualizar", this::atualizar));
        }
        m.addSeparator();
        JMenu ordenar = new JMenu("Ordenar por");
        ordenar.putClientProperty("html.disable", Boolean.TRUE);
        for (Coluna c : new Coluna[] {Coluna.NOME, Coluna.TIPO, Coluna.TAMANHO, Coluna.MODIFICADO}) {
            String rotulo = switch (c) {
                case NOME -> "Nome";
                case TIPO -> "Tipo";
                case TAMANHO -> "Tamanho";
                case MODIFICADO -> "Data de modificação";
                default -> c.name();
            };
            ordenar.add(item(rotulo + (ordem.coluna() == c ? (ordem.crescente() ? " (crescente)" : " (decrescente)") : ""), () -> ordenarPor(c)));
        }
        m.add(ordenar);
        return m;
    }

    private boolean acoesLiberadasAgora() {
        return preparado && conectado() && (situacao == Situacao.NORMAL || situacao == Situacao.VAZIA);
    }

    // ---------------------------------------------------------------------------------------------
    // Botões
    // ---------------------------------------------------------------------------------------------

    private void atualizarBotoes() {
        boolean pronto = acoesLiberadasAgora();
        boolean escreve = pronto && ctx.escritaLiberada();
        List<EntradaRemota> sel = tabela == null ? List.of() : tabela.selecionadas();
        voltar.setEnabled(preparado && conectado() && !pilhaDeVoltar.isEmpty() && situacao != Situacao.CARREGANDO);
        subir.setEnabled(preparado && conectado() && caminhoAtual != null && !caminhoAtual.equals(escopo.raiz()) && situacao != Situacao.CARREGANDO);
        atualizar.setEnabled(iniciado && conectado());
        seletorDeRaiz.setEnabled(iniciado && preparado && conectado());
        enviar.setVisible(ctx.escritaLiberada());
        novaPasta.setVisible(ctx.escritaLiberada());
        novoArquivo.setVisible(ctx.escritaLiberada());
        renomear.setVisible(ctx.escritaLiberada());
        apagar.setVisible(ctx.escritaLiberada());
        enviar.setEnabled(escreve);
        novaPasta.setEnabled(escreve);
        novoArquivo.setEnabled(escreve);
        renomear.setEnabled(escreve && sel.size() == 1);
        apagar.setEnabled(escreve && !sel.isEmpty());
        baixar.setEnabled(pronto && !sel.isEmpty());
        maisAcoes.setEnabled(pronto);
        campoDeFiltro.setEnabled(preparado);
        caixaDeOcultos.setEnabled(preparado);
    }

    // ---------------------------------------------------------------------------------------------
    // Arrastar e soltar
    // ---------------------------------------------------------------------------------------------

    private void instalarArraste() {
        ManipuladorDeArraste h = new ManipuladorDeArraste(new ManipuladorDeArraste.Alvo() {
            @Override
            public boolean escritaLiberada() {
                return ctx.escritaLiberada() && acoesLiberadasAgora();
            }

            @Override
            public String pastaDeSoltura(TransferHandler.TransferSupport s) {
                return pastaDeSolturaDe(s);
            }

            @Override
            public List<EntradaRemota> selecionadasParaArrastar() {
                return tabela.selecionadas();
            }

            @Override
            public void soltarArquivosLocais(List<Path> arquivos, String pasta) {
                enviarArquivosLocais(arquivos, pasta);
            }

            @Override
            public void soltarEntradas(List<EntradaRemota> entradas, String pasta) {
                mover(entradas, pasta);
            }
        });
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            tabela.setDragEnabled(true); // sem tela (os testes), o Swing nem deixa ligar; o manipulador é chamado direto
        }
        tabela.setTransferHandler(h);
        arvore.setTransferHandler(h);
        // soltar no espaço vazio embaixo das linhas também vale (vai para a pasta atual)
        if (tabela.getParent() instanceof javax.swing.JViewport v) {
            v.setTransferHandler(h);
        }
        tabela.setFillsViewportHeight(true);
    }

    /** A pasta de destino de uma soltura: a linha de pasta sob o ponteiro, o nó da árvore, ou a pasta atual no espaço vazio. */
    private String pastaDeSolturaDe(TransferHandler.TransferSupport s) {
        Component c = s.getComponent();
        if (c == arvore) {
            javax.swing.JTree.DropLocation dl = s.isDrop() ? (javax.swing.JTree.DropLocation) s.getDropLocation() : null;
            if (dl != null && dl.getPath() != null && dl.getPath().getLastPathComponent() instanceof javax.swing.tree.DefaultMutableTreeNode no
                && no.getUserObject() instanceof ArvoreDePastas.NoDePasta p) {
                return p.caminho();
            }
            return null;
        }
        if (s.isDrop()) {
            javax.swing.JTable.DropLocation dl = c instanceof javax.swing.JTable ? (javax.swing.JTable.DropLocation) s.getDropLocation() : null;
            if (dl != null && dl.getRow() >= 0) {
                EntradaRemota e = tabela.entradaNaLinha(dl.getRow());
                if (e != null && e.ehPasta() && !tabela.selecionadas().contains(e)) {
                    return e.caminho();
                }
            }
        }
        return caminhoAtual;
    }

    /** Soltar arquivos do Mac sobre uma pasta (os testes simulam o arrastar do Finder por aqui). */
    public void soltarArquivosLocais(List<Path> arquivos, String pasta) {
        enviarArquivosLocais(arquivos, pasta == null ? caminhoAtual : pasta);
    }

    // ---------------------------------------------------------------------------------------------
    // Peças internas
    // ---------------------------------------------------------------------------------------------

    private final class OuvinteDaTabela implements TabelaDeEntradas.Ouvinte {
        @Override
        public void abrir(EntradaRemota entrada) {
            if (acoesLiberadasAgora()) {
                abrirEntrada(entrada);
            }
        }

        @Override
        public void subir() {
            GerenciadorDeArquivos.this.subir();
        }

        @Override
        public void renomear() {
            if (ctx.escritaLiberada()) {
                pedirRenomear();
            }
        }

        @Override
        public void apagar() {
            if (ctx.escritaLiberada()) {
                pedirApagar();
            }
        }

        @Override
        public void menu(Component origem, int x, int y) {
            menuDeContexto().show(origem, x, y);
        }

        @Override
        public void ordenarPor(Coluna coluna) {
            GerenciadorDeArquivos.this.ordenarPor(coluna);
        }

        @Override
        public void selecaoMudou() {
            atualizarBotoes();
        }
    }

    /** As subpastas de uma pasta, lidas fora da EDT, para a árvore e para o "Mover para…". */
    private final class CarregadorDaArvore implements ArvoreDePastas.Carregador {
        @Override
        public void carregar(String caminho, Consumer<List<EntradaRemota>> aoChegar, Consumer<ErroSftp> aoFalhar) {
            ServicoDeArquivos s = servico;
            if (s == null) {
                aoFalhar.accept(ErroSftp.semConexao(null));
                return;
            }
            Tarefas.rodar(() -> s.listar(caminho, ListagemDePasta.LIMITE_PADRAO, n -> true).entradas(), aoChegar,
                erro -> aoFalhar.accept(ErroSftp.de(erro, "abrir esta pasta")));
        }

        @Override
        public void escolheuPasta(String caminho) {
            irPara(caminho);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public Situacao situacao() {
        return situacao;
    }

    public String caminhoAtual() {
        return caminhoAtual;
    }

    public Escopo escopo() {
        return escopo;
    }

    public ListagemDePasta listagem() {
        return listagem;
    }

    public RaizDoServidor raizAtual() {
        return raizAtual;
    }

    /** Quantas tarefas de rede ainda não voltaram (os testes esperam isto chegar a zero). */
    public int emVoo() {
        return emVoo.get();
    }

    public boolean preparado() {
        return preparado;
    }

    public FilaDeTransferencias fila() {
        return fila;
    }

    TabelaDeEntradas tabela() {
        return tabela;
    }

    ArvoreDePastas arvore() {
        return arvore;
    }

    BarraDeCaminho barraDeCaminho() {
        return barraDeCaminho;
    }

    PainelDeTransferencias painelDeTransferencias() {
        return painelDeTransferencias;
    }

    CartaoDeMensagem cartao() {
        return cartao;
    }

    FaixaDeDados faixaDeDados() {
        return faixaDeDados;
    }

    Esmaecivel tabelaEsmaecivel() {
        return tabelaEsmaecivel;
    }

    JTextField campoDeFiltro() {
        return campoDeFiltro;
    }

    JCheckBox caixaDeOcultos() {
        return caixaDeOcultos;
    }

    Rotulo rotuloDoResumo() {
        return resumo;
    }

    Rotulo avisoDeSomenteLeitura() {
        return somenteLeitura;
    }

    Abas abas() {
        return abas;
    }

    /** A área de texto do editor aberto para este arquivo, ou {@code null} (quem testa o Esc da página do projeto precisa dela). */
    public javax.swing.text.JTextComponent areaDoEditor(String caminho) {
        SessaoDeEdicao s = editores.get(caminho);
        return s == null ? null : s.painel.area();
    }

    Map<String, EditorDeTexto> editoresAbertos() {
        Map<String, EditorDeTexto> m = new LinkedHashMap<>();
        editores.forEach((k, v) -> m.put(k, v.painel));
        return m;
    }

    /** O botão com este nome ("voltar", "subir", "atualizar", "enviar", "baixar", "nova-pasta", "novo-arquivo", "renomear", "apagar", "mais", "raiz"). */
    Botao botao(String nome) {
        return switch (nome) {
            case "voltar" -> voltar;
            case "subir" -> subir;
            case "atualizar" -> atualizar;
            case "enviar" -> enviar;
            case "baixar" -> baixar;
            case "nova-pasta" -> novaPasta;
            case "novo-arquivo" -> novoArquivo;
            case "renomear" -> renomear;
            case "apagar" -> apagar;
            case "mais" -> maisAcoes;
            case "raiz" -> seletorDeRaiz;
            default -> throw new IllegalArgumentException(nome);
        };
    }

    JPopupMenu menuDeContextoParaTeste() {
        return menuDeContexto();
    }

    JPopupMenu menuDeRaizParaTeste() {
        return menuDeRaiz();
    }

    ManipuladorDeArraste manipuladorParaTeste() {
        return (ManipuladorDeArraste) tabela.getTransferHandler();
    }

    public Optional<EntradaRemota> entrada(String nome) {
        return listagem == null ? Optional.empty() : listagem.entradas().stream().filter(e -> e.nome().equals(nome)).findFirst();
    }

    void selecionarNaTabela(String... nomes) {
        tabela.clearSelection();
        for (String n : nomes) {
            for (int i = 0; i < tabela.itens().size(); i++) {
                if (tabela.itens().get(i).nome().equals(n)) {
                    tabela.addRowSelectionInterval(i, i);
                }
            }
        }
    }

    /** Qual cartão da área da lista está à vista: {@code "tabela"}, {@code "mensagem"} ou {@code "esqueleto"} (para os testes). */
    String cartaoDaListaVisivel() {
        if (esqueleto.isVisible()) {
            return CARTAO_ESQUELETO;
        }
        return cartao.isVisible() ? CARTAO_MENSAGEM : CARTAO_TABELA;
    }

    /** Para os testes de layout: o divisor entre a árvore e a tabela. */
    JSplitPane divisor() {
        return divisor;
    }
}
