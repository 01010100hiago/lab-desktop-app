package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.servidor.VisaoProjetos;
import br.com.labdesktop.ui.AcoesDaInterface;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.FaixaAviso;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Navegador;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Carregando;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.ColunasDaLista.Coluna;
import br.com.labdesktop.ui.telas.ListagemDeProjetos.Filtro;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.Timer;

/**
 * A lista de projetos de um servidor (ARQUITETURA 8.3, tradução de {@code index.html}): LINHAS, não cartões, com
 * estado, projeto, containers, CPU, memória, consumo de 7 dias com o limite, git, URL pública, Claude e ações; busca e
 * os filtros Todos, No ar, Com Claude e Pausados; a barra de crédito do plano e a faixa de aviso (conexão, crédito,
 * sessões pausadas) no topo.
 *
 * <p>A tela só desenha snapshots imutáveis: assina o tópico {@code PROJETOS} ao ficar visível
 * ({@code HierarchyListener} com {@code showing}) e solta ao esconder, então uma tela esquecida não custa
 * {@code docker exec} no servidor. As linhas são atualizadas POR NOME, no lugar: foco, menu aberto e o código de
 * login digitado sobrevivem ao polling de 10 s. Toda ação roda fora da EDT ({@link FluxosDeProjeto}).
 */
public final class TelaProjetos extends Painel {

    private static final long serialVersionUID = 1L;

    private static final String CARTAO_ESQUELETO = "esqueleto";
    private static final String CARTAO_ERRO = "erro";
    private static final String CARTAO_VAZIO = "vazio";
    private static final String CARTAO_NADA = "nada";
    private static final String CARTAO_LISTA = "lista";

    private final ServidorLab servidor;
    private final FluxosDeProjeto fluxos;
    private final Navegador navegador;
    private final AcoesDaInterface acoesUi;

    private final FaixaAviso faixa;
    private final PainelDeCredito credito = new PainelDeCredito();
    /** Os números do topo (7 projetos, 4 no ar...) em itens SEPARADOS, não numa frase com " · " que quebra. */
    private final JPanel resumo = Caixa.fluxo(java.awt.FlowLayout.LEFT, 18);
    private String resumoEmTexto = "";
    private final JTextField busca = Campos.busca(16, "Buscar por nome…");
    private final Map<Filtro, JToggleButton> botoesDeFiltro = new EnumMap<>(Filtro.class);
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    /** "Novo projeto": só com as ações liberadas e um servidor que sabe criar projeto (o agente do lab ou este computador). */
    private final Botao novoProjeto = Botoes.miniPrimario("Novo projeto", "pasta-mais");
    /** O convite grande da lista vazia: "Criar o primeiro projeto" (as mesmas condições do botão). */
    private final Botao convite = Botoes.primario("Criar o primeiro projeto", "pasta-mais");
    private final Rotulo textoDoVazio = new Rotulo().corDoToken(Tokens.TEXTO2);
    private Runnable aoPedirNovoProjeto = this::pedirNovoProjeto;
    private AcoesDaInterface.DepoisDeCriar depoisDeCriar;
    private final CardLayout cartoes = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private final JPanel linhasPanel = new Painel();
    /** O "Carregando projetos..." de antes da primeira lista: na hora, com prazo e "Tentar de novo" (nunca fica preso). */
    private final Carregando carregando = new Carregando("projetos");
    private CabecalhoDeColunas cabecalhoDeColunas;
    private final Rotulo mensagemDeErro = new Rotulo().corDoToken(Tokens.TEXTO2);
    private final Timer relogioDaTela;
    private final Timer fimDoOcupado = new Timer(10_000, e -> atualizar.definirOcupado(false));
    private JScrollPane rolagemDaLista;
    private JPanel topo;

    private final Map<String, LinhaProjetoVisual> linhas = new LinkedHashMap<>();
    private List<LinhaProjetoVisual> exibidas = List.of();
    private List<Projeto> projetos = List.of();
    private List<LinhaDeProjeto> modelos = List.of();
    private final Map<String, Limite> limites = new HashMap<>();
    private final Set<String> retomando = new HashSet<>();
    private DetalheRaiz raiz;
    private Filtro filtro = Filtro.TODOS;
    /** O servidor tem Claude, segundo o agente (desconhecido vale sim): sem Claude somem a coluna, os filtros e as frases dele. */
    private boolean comClaude = true;
    private String termo = "";
    private boolean temDados;
    private String cartaoAtual = CARTAO_ESQUELETO;
    private EstadoServidor ultimoEstado;
    private Inscricao inscricaoDoTopico;
    private Inscricao inscricaoDoEstado;
    private Inscricao inscricaoDeOcupacao;
    private java.util.function.Consumer<String> aoAbrirProjeto = nome -> { };

    public TelaProjetos(ServidorLab servidor, FluxosDeProjeto fluxos, Navegador navegador, AcoesDaInterface acoesUi) {
        super(new BorderLayout());
        this.servidor = servidor;
        this.fluxos = fluxos;
        this.navegador = navegador;
        this.acoesUi = acoesUi;
        if (acoesUi != null) {
            acoesUi.usarTrava(fluxos::liberadas); // Publicar e Remover conferem a trava também por dentro (defesa em profundidade)
        }
        this.faixa = new FaixaAviso(new FaixaAviso.Ouvinte() {
            @Override
            public void aoAcionar(Acao acao) {
                acoesUi.executar(servidor, acao);
            }

            @Override
            public void aoRetomar(String projeto) {
                fluxos.retomar(projeto, limiteDe(projeto));
            }
        }, java.time.Instant::now, fluxos.liberadas());
        this.relogioDaTela = new Timer(30_000, e -> reavaliarComOTempo());
        fimDoOcupado.setRepeats(false);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 20, 16, 20));

        montar();
        ligarEventos();
        mostrarCartao(CARTAO_ESQUELETO);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    assinar();
                } else {
                    soltar();
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private void montar() {
        ButtonGroup grupo = new ButtonGroup();
        List<Component> botoesDoGrupo = new ArrayList<>();
        for (Filtro f : Filtro.values()) {
            JToggleButton b = Botoes.filtro(f.rotulo(), f.dica());
            b.setSelected(f == filtro);
            grupo.add(b);
            botoesDeFiltro.put(f, b);
            botoesDoGrupo.add(b);
        }
        busca.setPreferredSize(new Dimension(240, 32));
        busca.setMaximumSize(new Dimension(240, 32));

        // duas linhas: o título com os números e o Atualizar; embaixo a busca e os filtros. Numa linha só, com o texto de 14,
        // a busca encolhia para um ponto e o último filtro saía da janela de 1100.
        JComponent cabecalho = Caixa.coluna(8,
            Caixa.linha(14, Rotulo.titulo("Projetos"), resumo, Caixa.mola(), novoProjeto, atualizar),
            Caixa.linha(8, busca, Caixa.linha(6, botoesDoGrupo.toArray(Component[]::new)), Caixa.mola()));

        topo = Caixa.coluna(12, faixa, credito, cabecalho);
        faixa.setAlignmentX(LEFT_ALIGNMENT);
        credito.setAlignmentX(LEFT_ALIGNMENT);
        add(topo, BorderLayout.NORTH);

        linhasPanel.setLayout(new BoxLayout(linhasPanel, BoxLayout.Y_AXIS));
        carregando.aoTentarDeNovo(() -> {
            if (acoesUi != null) {
                acoesUi.atualizar(servidor);
            }
        });
        corpo.add(carregando, CARTAO_ESQUELETO);
        corpo.add(cartaoDeErro(), CARTAO_ERRO);
        corpo.add(cartaoVazio(), CARTAO_VAZIO);
        corpo.add(vazio("busca", "Nada encontrado", "Ajuste a busca ou o filtro rápido."), CARTAO_NADA);
        corpo.add(linhasPanel, CARTAO_LISTA);

        JPanel cartao = new Painel(new BorderLayout()).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        cabecalhoDeColunas = new CabecalhoDeColunas(fluxos.liberadas());
        cartao.add(cabecalhoDeColunas, BorderLayout.NORTH);
        cartao.add(corpo, BorderLayout.CENTER);

        ListaRolavel vista = new ListaRolavel(new BorderLayout());
        vista.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 0, 4, 0));
        vista.add(cartao, BorderLayout.NORTH);
        JScrollPane rolagem = rolagemDaLista = new JScrollPane(vista, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rolagem.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        rolagem.setOpaque(false);
        rolagem.getViewport().setOpaque(false);
        rolagem.getVerticalScrollBar().setUnitIncrement(18);
        // O topo (faixas, crédito, busca) fica fora da rolagem: quando a barra vertical aparece, ele recua o mesmo tanto,
        // para a borda direita do topo e a do cartão da lista continuarem alinhadas.
        javax.swing.JScrollBar barra = rolagem.getVerticalScrollBar();
        barra.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                ajustarMargemDoTopo(barra);
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                ajustarMargemDoTopo(barra);
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                ajustarMargemDoTopo(barra);
            }
        });
        add(rolagem, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName("Projetos");
    }

    private void ajustarMargemDoTopo(javax.swing.JScrollBar barra) {
        int folga = barra.isVisible() ? barra.getWidth() : 0;
        topo.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, folga));
        topo.revalidate();
    }

    private JComponent cartaoDeErro() {
        Botao tentar = Botoes.secundario("Tentar de novo", "atualizar");
        tentar.addActionListener(e -> acoesUi.atualizar(servidor));
        Rotulo titulo = Rotulo.titulo("Não consegui carregar").tamanho(15f);
        titulo.setIcon(Icones.colorido("erro", 20, Tokens.PERIGO));
        titulo.setIconTextGap(8);
        JPanel c = Caixa.coluna(8, titulo, mensagemDeErro, tentar);
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder(28, 28, 28, 28));
        return c;
    }

    private static JComponent vazio(String icone, String titulo, String texto) {
        Rotulo t = Rotulo.titulo(titulo).tamanho(15f);
        t.setIcon(Icones.colorido(icone, 20, Tokens.TEXTO3));
        t.setIconTextGap(8);
        JPanel c = Caixa.coluna(6, t, Rotulo.secundario(texto));
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder(28, 28, 28, 28));
        return c;
    }

    /** A lista vazia: o texto de sempre e, quando dá para criar, o convite grande "Criar o primeiro projeto". */
    private JComponent cartaoVazio() {
        Rotulo t = Rotulo.titulo("Nenhum projeto ainda").tamanho(15f);
        t.setIcon(Icones.colorido("pasta", 20, Tokens.TEXTO3));
        t.setIconTextGap(8);
        convite.setVisible(false);
        convite.addActionListener(e -> aoPedirNovoProjeto.run());
        JPanel c = Caixa.coluna(8, t, textoDoVazio, convite);
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder(28, 28, 28, 28));
        atualizarOTextoDoVazio();
        return c;
    }

    private void atualizarOTextoDoVazio() {
        boolean local = ultimoEstado != null && ultimoEstado.local();
        boolean pode = podeCriarProjeto();
        // onde os projetos moram: no computador local, a pasta do laboratório (nunca o /srv/lab/projects de um servidor); no servidor, a do agente
        String onde = !local ? "em /srv/lab/projects"
            : acoesUi != null && servidor != null ? "em " + acoesUi.pastaDosProjetosLocais(servidor) : "na pasta do laboratório deste computador";
        textoDoVazio.setText(pode ? "Crie um projeto novo ou importe um que já existe (do GitHub ou de uma pasta). Eles ficam " + onde + "."
            : "Os projetos aparecem aqui quando houver pastas " + onde + ".");
        convite.setVisible(pode);
    }

    /** O botão e o convite só existem com as ações liberadas e um servidor que cria projeto (o agente do lab, ou este computador). */
    private boolean podeCriarProjeto() {
        return fluxos.liberadas() && ultimoEstado != null && (ultimoEstado.local() || ultimoEstado.agenteCriaProjetos());
    }

    private void atualizarONovoProjeto() {
        novoProjeto.setVisible(podeCriarProjeto());
        atualizarOTextoDoVazio();
    }

    private void pedirNovoProjeto() {
        if (acoesUi == null || servidor == null || !podeCriarProjeto()) {
            return;
        }
        acoesUi.novoProjeto(servidor, depoisDeCriar != null ? depoisDeCriar : new AcoesDaInterface.DepoisDeCriar() {
            @Override
            public void abrirNoTerminal(String projeto) {
                aoAbrirProjeto.accept(projeto);
            }

            @Override
            public void abrirNoClaude(String projeto) {
                aoAbrirProjeto.accept(projeto);
            }
        });
    }

    /** O que fazer depois de criar ou importar: a {@link TelaServidor} abre a página do projeto na aba Terminal. */
    public void aoCriarProjeto(AcoesDaInterface.DepoisDeCriar depois) {
        this.depoisDeCriar = depois;
    }

    /** "Adicionar container" da página de um projeto: abre o diálogo (o app só escreve depois do clique de gravar). */
    void adicionarContainerDe(String projeto) {
        if (acoesUi == null || servidor == null || !podeCriarProjeto()) {
            return;
        }
        acoesUi.adicionarContainer(servidor, projeto, () -> fluxos.iniciar(projeto));
    }

    /** As ações de interface (diálogos) da janela, ou {@code null} nos testes de tela. */
    AcoesDaInterface acoes() {
        return acoesUi;
    }

    /** "Publicar na internet" da página de um projeto: abre o diálogo (o app só escreve depois do clique de confirmar). Só com as ações liberadas. */
    void publicarDe(String projeto, Runnable depois) {
        if (acoesUi == null || servidor == null || !fluxos.liberadas()) {
            return;
        }
        acoesUi.publicarNaInternet(servidor, projeto, depois);
    }

    /** "Adicionar domínio" a um projeto: abre o assistente (que leva ao "Conectar a Cloudflare" primeiro, se faltar). Só com as ações liberadas. */
    void adicionarDominioDe(String projeto, Runnable depois) {
        if (acoesUi == null || servidor == null || !fluxos.liberadas()) {
            return;
        }
        acoesUi.adicionarDominio(servidor, projeto, depois);
    }

    /** "Criar repositório Git" na página de um projeto: abre a janela de conferência (só lê). Só com as ações liberadas; criar escreve e fica atrás da trava. */
    void criarRepositorioDe(String projeto, Runnable depois, Runnable verCommits) {
        if (acoesUi == null || servidor == null || !fluxos.liberadas()) {
            return;
        }
        acoesUi.criarRepositorioGit(servidor, projeto, depois, verCommits);
    }

    /** "Instalar o Git…" a partir da página de um projeto: o fluxo que já existe (confirmação com os comandos exatos). */
    void instalarGitDe(Runnable depois) {
        if (acoesUi == null || servidor == null) {
            return;
        }
        acoesUi.instalarGit(servidor, depois);
    }

    /** O leitor do Git de um projeto (só leitura, pelo mesmo caminho da página), ou {@code null} se não há servidor ou a pasta não vale. */
    br.com.labdesktop.git.LeitorDeGit leitorDeGitDe(String pastaDoProjetoNoApp) {
        if (servidor == null) {
            return null;
        }
        try {
            return servidor.leitorDeGit(pastaDoProjetoNoApp);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "Adicionar domínio" a partir do servidor (aba Cloudflare): pergunta o projeto e segue para o assistente. Só com as ações liberadas. */
    void adicionarDominioEscolhendoOProjeto(Runnable depois) {
        if (acoesUi == null || servidor == null || !fluxos.liberadas()) {
            return;
        }
        acoesUi.adicionarDominioEscolhendoOProjeto(servidor, nomesDosProjetos(), depois);
    }

    /** Os nomes dos projetos que a lista mostra agora. */
    public List<String> nomesDosProjetos() {
        List<String> nomes = new ArrayList<>();
        modelos.forEach(m -> nomes.add(m.nome()));
        return nomes;
    }

    /** "Conectar a Cloudflare": o diálogo do token (só lê da Cloudflare e grava no cofre local; não depende da trava das ações). */
    void conectarCloudflareDe(Runnable depois) {
        if (acoesUi == null || servidor == null) {
            return;
        }
        acoesUi.conectarCloudflare(servidor, depois);
    }

    /** "Remover" de um endereço publicado: abre o diálogo que pede para digitar o endereço. Só com as ações liberadas. */
    void removerEnderecoDe(String hostname, Runnable depois) {
        if (acoesUi == null || servidor == null || !fluxos.liberadas()) {
            return;
        }
        acoesUi.removerEnderecoDaInternet(servidor, hostname, depois);
    }

    /** Encerrar ou Reiniciar um processo (a aba Processos de um projeto): abre a confirmação. Só com as ações liberadas (a trava vale também dentro das ações da janela). */
    void abrirProcessoDe(br.com.labdesktop.processos.ProcessoAlvo alvo, String origem, br.com.labdesktop.processos.Acao acao, Runnable depois) {
        if (acoesUi == null || servidor == null || !fluxos.liberadas()) {
            return;
        }
        acoesUi.encerrarOuReiniciarProcesso(servidor, alvo, origem, acao, depois);
    }

    /** Abre no navegador um endereço público da zona conectada. */
    void abrirEnderecoDe(String hostname) {
        if (acoesUi == null || servidor == null) {
            return;
        }
        acoesUi.abrirEnderecoPublico(servidor, hostname);
    }

    /** A página do projeto pode oferecer "Adicionar container" (as ações liberadas e um servidor que sabe escrever em projeto)? */
    boolean podeAdicionarContainer() {
        return podeCriarProjeto();
    }

    private void ligarEventos() {
        novoProjeto.setVisible(false);
        novoProjeto.setToolTipText("Cria um projeto do zero ou importa um que já existe");
        novoProjeto.addActionListener(e -> aoPedirNovoProjeto.run());
        busca.getDocument().addDocumentListener(new br.com.labdesktop.ui.dialogos.SimplesOuvinteDeDocumento(() -> {
            termo = busca.getText();
            redesenhar();
        }));
        for (Map.Entry<Filtro, JToggleButton> par : botoesDeFiltro.entrySet()) {
            par.getValue().addActionListener(e -> {
                filtro = par.getKey();
                redesenhar();
            });
        }
        atualizar.addActionListener(e -> {
            atualizar.definirOcupado(true); // o clique mostra que o app está perguntando ao servidor; solta quando a leitura chega (ou em 10 s)
            fimDoOcupado.restart();
            acoesUi.atualizar(servidor);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Assinatura (só consulta o servidor enquanto a tela está visível)
    // ---------------------------------------------------------------------------------------------

    /** Passa a receber os projetos (e a consultar o servidor). Chamado ao ficar visível; idempotente. */
    public void assinar() {
        if (inscricaoDoTopico != null) {
            return;
        }
        if (!temDados) {
            carregando.iniciar(); // a espera conta desde que a lista está à vista, e o "Carregando projetos..." já está na tela
        }
        inscricaoDoTopico = servidor.assinar(servidor.topicoDeProjetos(), new OuvinteDeTopico<VisaoProjetos>() {
            @Override
            public void aoAtualizar(VisaoProjetos v) {
                aoReceberProjetos(v);
            }

            @Override
            public void aoFalhar(ErroApi erro) {
                aoFalharConsulta(erro);
            }

            @Override
            public void aoConfirmar() {
                soltarOAtualizar(); // a leitura foi feita e nada mudou
            }
        });
        inscricaoDoEstado = servidor.observarEstado(this::aoMudarEstado);
        inscricaoDeOcupacao = fluxos.observar(this::aoMudarOcupacao);
        relogioDaTela.start();
    }

    /** Para de consultar. Chamado ao esconder; idempotente. */
    public void soltar() {
        for (Inscricao i : new Inscricao[] {inscricaoDoTopico, inscricaoDoEstado, inscricaoDeOcupacao}) {
            if (i != null) {
                i.close();
            }
        }
        inscricaoDoTopico = null;
        inscricaoDoEstado = null;
        inscricaoDeOcupacao = null;
        relogioDaTela.stop();
        soltarOAtualizar();
        faixa.parar();
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshots
    // ---------------------------------------------------------------------------------------------

    /** Recebe a lista (na EDT). Público para os testes alimentarem a tela com dados fixos. */
    public void aoReceberProjetos(VisaoProjetos visao) {
        this.temDados = true;
        carregando.chegou();
        soltarOAtualizar();
        this.raiz = visao.raiz();
        this.projetos = visao.lista() == null ? List.of() : visao.lista().projetos();
        limites.clear();
        List<LinhaDeProjeto> novos = new ArrayList<>(projetos.size());
        for (Projeto p : projetos) {
            novos.add(LinhaDeProjeto.de(p));
            if (p.limite() != null) {
                limites.put(p.nome(), p.limite());
            }
        }
        if (raiz != null && raiz.limite() != null) {
            limites.put("lab", raiz.limite());
        }
        this.modelos = novos;
        refazerOResumo();
        redesenhar();
    }

    private void refazerOResumo() {
        resumo.removeAll();
        for (String parte : ListagemDeProjetos.partesDoResumo(projetos, comClaude)) {
            resumo.add(new Rotulo(parte).corDoToken(Tokens.TEXTO2).tamanho(12.5f));
        }
        resumoEmTexto = String.join(", ", ListagemDeProjetos.partesDoResumo(projetos, comClaude));
        resumo.revalidate();
        resumo.repaint();
    }

    /** A leitura que o clique em "Atualizar" pediu chegou (ou falhou): o botão volta ao normal. */
    private void soltarOAtualizar() {
        fimDoOcupado.stop();
        atualizar.definirOcupado(false);
    }

    private void aoFalharConsulta(ErroApi erro) {
        soltarOAtualizar();
        if (temDados || erro instanceof ErroApi.ErroRede) {
            return; // a faixa de conexão explica; a tela mantém a última leitura (nunca fica vazia)
        }
        mensagemDeErro.setText(MensagensDeErro.daApi(erro));
        mostrarCartao(CARTAO_ERRO);
    }

    /** Recebe o estado do servidor (na EDT): alimenta a faixa de aviso e a barra de crédito. */
    public void aoMudarEstado(EstadoServidor e) {
        this.ultimoEstado = e;
        boolean temClaude = !Boolean.FALSE.equals(e.temClaude());
        if (temClaude != comClaude) {
            comClaude = temClaude;
            aplicarOClaude();
        }
        faixa.atualizar(e);
        credito.atualizar(e.limites(), Instant.now(), ZoneId.systemDefault());
        atualizarONovoProjeto();
    }

    /** Mostra ou esconde tudo do Claude na lista: a coluna, os filtros "Com Claude" e "Pausados", as frases do resumo. */
    private void aplicarOClaude() {
        credito.setVisible(comClaude); // "Plano do Claude": sem Claude não há plano a mostrar
        cabecalhoDeColunas.mostrarClaude(comClaude);
        botoesDeFiltro.get(Filtro.COM_CLAUDE).setVisible(comClaude);
        botoesDeFiltro.get(Filtro.PAUSADOS).setVisible(comClaude);
        if (!comClaude && (filtro == Filtro.COM_CLAUDE || filtro == Filtro.PAUSADOS)) {
            filtro = Filtro.TODOS;
            botoesDeFiltro.get(Filtro.TODOS).setSelected(true);
        }
        linhas.values().forEach(l -> l.mostrarClaude(comClaude));
        refazerOResumo();
        redesenhar();
    }

    /** A lista mostra a coluna do Claude? (os testes) */
    public boolean mostraOClaude() {
        return comClaude;
    }

    private void reavaliarComOTempo() {
        if (ultimoEstado != null) {
            faixa.atualizar(ultimoEstado);
            credito.atualizar(ultimoEstado.limites(), Instant.now(), ZoneId.systemDefault());
        }
    }

    private void aoMudarOcupacao(String projeto) {
        LinhaProjetoVisual l = linhas.get(projeto);
        if (l != null) {
            l.aplicarOcupacao();
        }
        if (fluxos.ocupado(projeto, FluxosDeProjeto.RETOMAR)) {
            retomando.add(projeto);
        } else {
            retomando.remove(projeto);
        }
        faixa.definirOcupados(retomando);
    }

    private Limite limiteDe(String projeto) {
        return limites.get(projeto);
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir a página de um projeto
    // ---------------------------------------------------------------------------------------------

    /** Quem recebe o nome do projeto quando o usuário abre a linha dele (a {@link TelaServidor} abre a {@link TelaProjeto}). */
    public void aoAbrirProjeto(java.util.function.Consumer<String> novo) {
        this.aoAbrirProjeto = novo == null ? nome -> { } : novo;
    }

    private void abrirProjeto(String nome) {
        aoAbrirProjeto.accept(nome);
    }

    /**
     * O endereço de login do Claude que a ÚLTIMA lista viu para o projeto, ou {@code null}. Só a lista traz o login (o detalhe do
     * projeto não); a página do projeto o pede aqui para a aba Terminal (modo Claude) mostrar "Abrir página de login".
     */
    String urlDeLoginDe(String projeto) {
        return projetos.stream().filter(p -> p.nome().equals(projeto) && p.temLoginPendente()).map(p -> p.login().url())
            .findFirst().orElse(null);
    }

    FluxosDeProjeto fluxos() {
        return fluxos;
    }

    Navegador navegador() {
        return navegador;
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    private void redesenhar() {
        if (!temDados) {
            return;
        }
        Set<String> nomes = new HashSet<>();
        modelos.forEach(m -> nomes.add(m.nome()));
        linhas.keySet().removeIf(n -> !nomes.contains(n));

        List<LinhaDeProjeto> visiveis = ListagemDeProjetos.visiveis(modelos, filtro, termo);
        List<LinhaProjetoVisual> ordem = new ArrayList<>(visiveis.size());
        for (LinhaDeProjeto m : visiveis) {
            LinhaProjetoVisual l = linhas.computeIfAbsent(m.nome(),
                n -> {
                    LinhaProjetoVisual nova = new LinhaProjetoVisual(fluxos, navegador, fluxos::enviarCodigoDeLogin, this::abrirProjeto);
                    if (acoesUi != null && !servidor.ehLocal()) {
                        nova.aoAdicionarDominio(projeto -> adicionarDominioDe(projeto, null));
                    }
                    return nova;
                });
            l.mostrarClaude(comClaude);
            l.atualizar(m, limiteDe(m.nome()));
            ordem.add(l);
        }
        if (!ordem.equals(exibidas)) {
            linhasPanel.removeAll();
            for (int i = 0; i < ordem.size(); i++) {
                if (i > 0) {
                    linhasPanel.add(new Separador());
                }
                ordem.get(i).setAlignmentX(LEFT_ALIGNMENT);
                linhasPanel.add(ordem.get(i));
            }
            exibidas = ordem;
            linhasPanel.revalidate();
            linhasPanel.repaint();
        }
        mostrarCartao(modelos.isEmpty() ? CARTAO_VAZIO : ordem.isEmpty() ? CARTAO_NADA : CARTAO_LISTA);
    }

    private void mostrarCartao(String nome) {
        cartaoAtual = nome;
        cartoes.show(corpo, nome);
        corpo.revalidate();
        corpo.repaint();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    /** A rolagem da lista (para o teste conferir que não há rolagem para o lado). */
    JScrollPane rolagemDaLista() {
        return rolagemDaLista;
    }

    public FaixaAviso faixa() {
        return faixa;
    }

    PainelDeCredito credito() {
        return credito;
    }

    /** O "carregando" desta tela (para os testes conferirem a frase e o estado). */
    Carregando indicadorDeCarga() {
        return carregando;
    }

    public String cartaoAtual() {
        return cartaoAtual;
    }

    public String textoDoResumo() {
        return resumoEmTexto;
    }

    public Botao botaoNovoProjeto() {
        return novoProjeto;
    }

    public Botao botaoDoConvite() {
        return convite;
    }

    public String textoDoVazio() {
        return textoDoVazio.getText();
    }

    /** Troca o que "Novo projeto" faz (os testes contam os cliques sem abrir o diálogo). */
    public void aoPedirNovoProjeto(Runnable acao) {
        this.aoPedirNovoProjeto = acao == null ? this::pedirNovoProjeto : acao;
    }

    public JTextField campoDeBusca() {
        return busca;
    }

    public JToggleButton botaoDeFiltro(Filtro f) {
        return botoesDeFiltro.get(f);
    }

    public Botao botaoAtualizar() {
        return atualizar;
    }

    /** Os nomes dos projetos exibidos, na ordem da tela. */
    public List<String> nomesExibidos() {
        return exibidas.stream().map(LinhaProjetoVisual::projeto).toList();
    }

    /** O x de cada coluna do cabeçalho (o teste confere que as linhas começam as colunas no mesmo lugar). */
    List<Integer> xsDasColunasDoCabecalho() {
        return java.util.Arrays.stream(cabecalhoDeColunas.getComponents()).map(Component::getX).toList();
    }

    LinhaProjetoVisual linha(String projeto) {
        return linhas.get(projeto);
    }

    boolean assinada() {
        return inscricaoDoTopico != null;
    }

    public String textoDoErro() {
        return mensagemDeErro.getText();
    }

    // ---------------------------------------------------------------------------------------------
    // Peças internas
    // ---------------------------------------------------------------------------------------------

    /** A linha de rótulos das colunas (o {@code .lp-cabecalho}): mesmas colunas e recuo das linhas. */
    private static final class CabecalhoDeColunas extends Painel {
        private static final long serialVersionUID = 1L;

        private Component celulaDoClaude;

        void mostrarClaude(boolean sim) {
            if (celulaDoClaude != null) {
                celulaDoClaude.setVisible(sim);
                revalidate();
                repaint();
            }
        }

        CabecalhoDeColunas(boolean acoesLiberadas) {
            super(new GridBagLayout());
            fundo(Tokens.SUPERFICIE2);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(0, ColunasDaLista.RECUO, 0, ColunasDaLista.RECUO));
            for (Coluna c : Coluna.values()) {
                // Somente leitura: a coluna segue no lugar, para o alinhamento, mas sem o título "Ações" sobre nada.
                String titulo = c == Coluna.ACOES && !acoesLiberadas ? "" : c.rotulo;
                Rotulo r = new Rotulo(titulo.toUpperCase(Locale.forLanguageTag("pt-BR")))
                    .corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f).truncavel();
                r.setHorizontalAlignment(c.numerica ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (c == Coluna.RECURSOS) {
                    r.setToolTipText("Em cima, a CPU; embaixo, a memória (dos containers que rodam)");
                }
                if (c == Coluna.CODIGO) {
                    r.setToolTipText("O branch do git e o endereço público do projeto");
                }
                if (c == Coluna.CONSUMO) {
                    r.setToolTipText("US$ equivalente de API dos últimos 7 dias. Embaixo: a fatia estimada da semana do plano "
                        + "contra o limite do projeto.");
                }
                Component celula = ColunasDaLista.celula(c, r);
                if (c == Coluna.CLAUDE) {
                    celulaDoClaude = celula;
                }
                add(celula, ColunasDaLista.restricoes(c));
            }
            getAccessibleContext().setAccessibleName("Colunas da lista de projetos");
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 40);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Tokens.cor(Tokens.BORDA));
            g.fillRect(0, getHeight() - 1, getWidth(), 1);
        }
    }

    /** O traço fino entre duas linhas. */
    private static final class Separador extends JComponent {
        private static final long serialVersionUID = 1L;

        /**
         * Alinhado à esquerda como as linhas: o {@code BoxLayout} mistura os alinhamentos dos filhos, e um separador
         * centralizado empurrava as linhas (alinhadas à esquerda) para a direita, cortando as primeiras colunas.
         */
        @Override
        public float getAlignmentX() {
            return LEFT_ALIGNMENT;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 1);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Tokens.cor(Tokens.BORDA_SUTIL));
            g.fillRect(0, 0, getWidth(), 1);
        }
    }

    /** A vista da rolagem: acompanha a largura da janela, mas não encolhe abaixo do piso das colunas (rola na horizontal). */
    private static final class ListaRolavel extends Painel implements Scrollable {
        private static final long serialVersionUID = 1L;

        ListaRolavel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(d.width, ColunasDaLista.larguraMinima()), d.height);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle area, int orientacao, int direcao) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle area, int orientacao, int direcao) {
            return orientacao == SwingConstants.VERTICAL ? Math.max(40, area.height - 40) : Math.max(40, area.width - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport v && v.getWidth() >= ColunasDaLista.larguraMinima();
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
