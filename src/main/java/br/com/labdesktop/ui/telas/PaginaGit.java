package br.com.labdesktop.ui.telas;

import br.com.labdesktop.git.ArquivoDoCommit;
import br.com.labdesktop.git.Branch;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.DetalheDoCommit;
import br.com.labdesktop.git.DiffDoArquivo;
import br.com.labdesktop.git.FalhaDoGit;
import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.git.LeitorDeGitDoProjeto;
import br.com.labdesktop.git.PaginaDeCommits;
import br.com.labdesktop.git.Pendencia;
import br.com.labdesktop.git.RepositorioAbaixo;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.SegredosDoProjeto;
import br.com.labdesktop.git.RepositoriosAbaixo;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.git.StatusDoGit;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;

/**
 * A aba "Git" da página do projeto, SÓ LEITURA: os últimos commits (50 por vez, "Carregar mais"), o detalhe de cada um (cabeçalho, arquivos com linhas acrescentadas e tiradas e o diff do arquivo
 * escolhido), os branches e as alterações que ainda não foram commitadas. Lê pelo mesmo caminho do resto da página (SSH, que já passa pelo túnel do Session Manager ou da Cloudflare, ou o
 * processo local) e só quando o usuário olha: ao abrir a aba e no botão Atualizar, nunca de tempos em tempos e nunca com a aba escondida. Não precisa do agente: funciona também com ele fora do ar.
 *
 * <p>Quando a pasta principal do projeto não tem {@code .git} mas uma SUBPASTA tem ({@code app/}, o caso de muitos projetos), a aba NÃO oferece criar: lê o repositório da subpasta (o único, o lembrado
 * ou o que o usuário escolhe numa lista simples) exatamente como leria o da raiz, com o rótulo "Repositório: app/" e "Trocar repositório" para voltar à lista. Criar um repositório na pasta principal
 * vira um link discreto, com aviso e uma confirmação a mais na janela (um repositório dentro de outro esconderia o histórico do de dentro).
 *
 * <p>Sem Git no projeto, a aba oferece "Criar repositório Git"; sem o programa git, o "Instalar o Git…" que já existe; com a pasta de outro dono, a frase e o comando do
 * {@code safe.directory} para copiar (o app nunca o executa). Nada aqui escreve no servidor.
 */
final class PaginaGit extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    static final int COMMITS_POR_VEZ = 50;
    private static final int TETO_DO_CACHE = 64;
    private static final String CARTAO_MENSAGEM = "mensagem";
    private static final String CARTAO_CONTEUDO = "conteudo";
    private static final String SUB_COMMITS = "commits";
    private static final String SUB_ALTERACOES = "alteracoes";
    private static final String SUB_BRANCHES = "branches";
    private static final DateTimeFormatter DATA_COMPLETA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"));

    /** Tudo o que uma leitura completa traz, de uma vez. */
    private record Tudo(ResumoDoRepositorio resumo, ResultadoDoGit<PaginaDeCommits> commits, ResultadoDoGit<List<Branch>> branches, ResultadoDoGit<StatusDoGit> status) { }

    // ---- a barra de cima ----
    private final Abas sub = new Abas(true);
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    private final Rotulo situacao = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
    private final Rotulo filtroDeBranch = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Botao voltarAoAtual = Botoes.mini("Voltar ao branch atual", "voltar");
    private final Rotulo repositorioEmUso = new Rotulo().negrito().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Botao trocarRepositorio = Botoes.mini("Trocar repositório", "lista");
    private JPanel faixaRepositorio;

    // ---- o cartão de mensagem (carregando, sem Git, erro...) ----
    private final Rotulo mensagemTitulo = new Rotulo().negrito().tamanho(14f);
    private final TextoCorrido mensagemTexto = new TextoCorrido("", 640).corDoToken(Tokens.TEXTO2).tamanho(13f);
    private final Rotulo comando = new Rotulo().mono(12f).corDoToken(Tokens.TEXTO).truncavel();
    private final JPanel linhaDoComando = new Painel(new BorderLayout(8, 0));
    private final Botao copiarComando;
    private final Botao criar = Botoes.miniPrimario("Criar repositório Git", "mais");
    private final Botao instalar = Botoes.mini("Instalar o Git…", "baixar");
    private final Botao tentarDeNovo = Botoes.mini("Tentar de novo", "atualizar");
    private final Botao listaDeRepositorios = Botoes.mini("Ver a lista de repositórios", "lista");
    /** O seletor de repositório de subpasta: uma linha por repositório e o link discreto de criar na pasta principal. */
    private final TabelaDeDados<RepositorioAbaixo> tabelaDeRepositorios;
    private final Botao criarMesmoAssim = Botoes.link("Criar um repositório na pasta principal mesmo assim");
    private final JPanel seletor = new Painel(new BorderLayout(0, 6));

    // ---- commits ----
    private final TabelaDeDados<Commit> tabelaDeCommits;
    private final Botao carregarMais = Botoes.mini("Carregar mais", "seta-baixo");
    private final Rotulo contagemDeCommits = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Rotulo semCommits = new Rotulo("Este repositório ainda não tem commits.").corDoToken(Tokens.TEXTO2).tamanho(13f);
    private final Rotulo hashCompleto = new Rotulo().mono(12f).truncavel();
    private final Rotulo autorEData = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final TextoCorrido mensagemDoCommit = new TextoCorrido("", 500).tamanho(13f);
    private final Botao copiarHash;
    private final TabelaDeDados<ArquivoDoCommit> tabelaDeArquivos;
    private final Rotulo semArquivos = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
    private final VistaDeDiff diffDoCommit;
    private final JPanel detalhe = new Painel(new BorderLayout(0, 8));

    // ---- alterações ----
    private final TabelaDeDados<Pendencia> tabelaDePendencias;
    private final VistaDeDiff diffPendente;
    private final Rotulo semAlteracoes = new Rotulo("Nenhuma alteração: tudo o que está na pasta já foi salvo no git.").corDoToken(Tokens.TEXTO2).tamanho(13f);
    private final JToggleButton verArvore = Botoes.filtro("Ainda não preparado", "O que mudou na pasta e ainda não foi para a área de preparo (git add)");
    private final JToggleButton verIndice = Botoes.filtro("Preparado", "O que já está na área de preparo, pronto para o próximo commit");

    // ---- branches ----
    private final TabelaDeDados<Branch> tabelaDeBranches;

    private final CardLayout cartoes = new CardLayout();
    private final JPanel raiz = new Painel(cartoes);
    private final CardLayout cartoesDasSub = new CardLayout();
    private final JPanel corpoDasSub = new Painel(cartoesDasSub);

    // ---- estado ----
    private final AtomicInteger geracao = new AtomicInteger();
    private final AtomicInteger geracaoDoDetalhe = new AtomicInteger();
    private final AtomicInteger geracaoDoDiff = new AtomicInteger();
    private final Map<String, DetalheDoCommit> cacheDeDetalhes = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DetalheDoCommit> e) {
            return size() > TETO_DO_CACHE;
        }
    };
    private final Map<String, DiffDoArquivo> cacheDeDiffs = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DiffDoArquivo> e) {
            return size() > TETO_DO_CACHE;
        }
    };
    /** Os arquivos com cara de segredo que o usuário mandou mostrar mesmo assim (até o próximo Atualizar). */
    private final java.util.Set<String> liberados = new java.util.HashSet<>();
    /** O hash da cabeça lido na primeira página: as páginas seguintes partem DELE, então um commit novo no meio não repete nem pula commits. */
    private String ancora;
    private final List<Commit> commits = new ArrayList<>();
    private boolean temMais;
    private boolean carregando;
    private boolean carregandoMais;
    private boolean carregouAlgumaVez;
    private String branchEscolhido;
    private DetalheDoCommit detalheAtual;
    private ResumoDoRepositorio resumo;
    private StatusDoGit statusAtual;
    private List<Branch> branchesAtuais = List.of();
    private String ultimoErroDeLeitura;
    private boolean modoMensagem = true;

    PaginaGit(ContextoDoProjeto ctx) {
        super(ctx);
        this.copiarComando = BotaoCopiar.criar("Copiar comando", () -> comando.getText(), ctx.copiador, ctx.avisos);
        this.copiarHash = BotaoCopiar.criar("Copiar hash", () -> detalheAtual == null ? "" : detalheAtual.commit().hash(), ctx.copiador, ctx.avisos);
        this.diffDoCommit = new VistaDeDiff(ctx.copiador, ctx.avisos);
        this.diffPendente = new VistaDeDiff(ctx.copiador, ctx.avisos);
        this.tabelaDeCommits = new TabelaDeDados<>("Commits do projeto", List.of(
            new TabelaDeDados.Coluna<Commit>("Commit", 1.0, false, Commit::curto, c -> c.hash()),
            new TabelaDeDados.Coluna<Commit>("Mensagem", 4.0, false, c -> (c.ehMerge() ? "(merge) " : "") + (c.assunto().isEmpty() ? "(sem mensagem)" : c.assunto()), c -> c.assunto()),
            new TabelaDeDados.Coluna<Commit>("Autor", 1.4, false, Commit::autor, c -> c.autor() + (c.email().isEmpty() ? "" : " <" + c.email() + ">")),
            new TabelaDeDados.Coluna<Commit>("Quando", 1.1, false, c -> FormatosDoProjeto.haQuanto(c.quando(), ctx.agora()), c -> dataCompleta(c)),
            new TabelaDeDados.Coluna<Commit>("Arquivos", 1.0, true, c -> c.arquivos() < 0 ? "—" : String.valueOf(c.arquivos()))),
            this::aoEscolherOCommit);
        this.tabelaDeArquivos = new TabelaDeDados<>("Arquivos do commit", List.of(
            new TabelaDeDados.Coluna<ArquivoDoCommit>("Arquivo", 4.0, false, a -> (a.renomeado() ? a.antigo() + " → " : "") + a.caminho(), a -> a.caminho()),
            new TabelaDeDados.Coluna<ArquivoDoCommit>("+", 0.5, true, a -> a.binario() ? "bin" : "+" + a.acrescentadas()),
            new TabelaDeDados.Coluna<ArquivoDoCommit>("−", 0.5, true, a -> a.binario() ? "bin" : "-" + a.tiradas())),
            this::aoEscolherOArquivo);
        this.tabelaDePendencias = new TabelaDeDados<>("Alterações ainda não commitadas", List.of(
            new TabelaDeDados.Coluna<Pendencia>("Arquivo", 4.0, false, p -> (p.antigo() != null ? p.antigo() + " → " : "") + p.caminho(), p -> p.caminho()),
            new TabelaDeDados.Coluna<Pendencia>("O que mudou", 1.2, false, p -> p.tipo().rotulo()),
            new TabelaDeDados.Coluna<Pendencia>("Situação", 1.8, false, PaginaGit::situacaoDaPendencia)),
            this::aoEscolherAPendencia);
        this.tabelaDeRepositorios = new TabelaDeDados<>("Repositórios nas subpastas do projeto", List.of(
            new TabelaDeDados.Coluna<RepositorioAbaixo>("Repositório", 2.0, false, RepositorioAbaixo::comBarra),
            new TabelaDeDados.Coluna<RepositorioAbaixo>("Branch", 1.4, false, r -> r.lido() ? (r.branch() == null ? "commit solto" : r.branch()) : "—"),
            new TabelaDeDados.Coluna<RepositorioAbaixo>("Último commit", 4.0, false, r -> ultimoDaLista(r), r -> ultimoDaLista(r))),
            this::aoEscolherORepositorio);
        this.tabelaDeBranches = new TabelaDeDados<>("Branches", List.of(
            new TabelaDeDados.Coluna<Branch>("Branch", 3.0, false, Branch::nome),
            new TabelaDeDados.Coluna<Branch>("Situação", 1.2, false, b -> b.atual() ? "atual" : ""),
            new TabelaDeDados.Coluna<Branch>("Commit", 1.0, false, Branch::curto)),
            this::aoEscolherOBranch);
        iniciar();
    }

    @Override
    boolean preencheAAltura() {
        return true;
    }

    /** O Git é lido por SSH ou processo, não pelo agente: a aba existe e funciona sem o detalhe do projeto. */
    @Override
    boolean independeDoDetalhe() {
        return true;
    }

    @Override
    void aoAparecer() {
        recarregar();
    }

    @Override
    void aoSumir() {
        geracao.incrementAndGet();
        geracaoDoDetalhe.incrementAndGet();
        geracaoDoDiff.incrementAndGet();
        carregando = false;
        carregandoMais = false;
    }

    @Override
    void atualizarConteudo() {
        boolean acoes = ctx.acoesLiberadas();
        criar.setEnabled(acoes);
        criar.setToolTipText(acoes ? "Confere a pasta (só leitura) e mostra o que entraria no primeiro commit, antes de criar qualquer coisa" : TextosDeAcao.TRAVA);
        boolean protegido = ctx.estado != null && ctx.estado.protegido();
        instalar.setEnabled(!protegido);
        instalar.setToolTipText(protegido ? "O servidor é protegido: o app não instala nada nele." : "Abre a confirmação de instalar o Git, com os comandos exatos (nada roda antes do clique)");
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    @Override
    JComponent montarConteudo() {
        setName("pagina-git");
        sub.adicionar(SUB_COMMITS, "Commits");
        sub.adicionar(SUB_ALTERACOES, "Alterações");
        sub.adicionar(SUB_BRANCHES, "Branches");
        sub.aoSelecionar(id -> cartoesDasSub.show(corpoDasSub, id));
        atualizar.setName("atualizar-git");
        atualizar.setToolTipText("Lê o Git do projeto de novo (só leitura)");
        atualizar.addActionListener(e -> recarregar());
        voltarAoAtual.addActionListener(e -> {
            branchEscolhido = null;
            recarregar();
        });
        voltarAoAtual.setVisible(false);
        repositorioEmUso.setName("repositorio-em-uso");
        trocarRepositorio.setName("trocar-repositorio");
        trocarRepositorio.setToolTipText("Volta à lista de repositórios das subpastas do projeto");
        trocarRepositorio.addActionListener(e -> voltarAListaDeRepositorios());
        repositorioEmUso.setVisible(false);
        trocarRepositorio.setVisible(false);
        listaDeRepositorios.setName("lista-de-repositorios");
        listaDeRepositorios.addActionListener(e -> voltarAListaDeRepositorios());
        criarMesmoAssim.setName("criar-mesmo-assim");
        criarMesmoAssim.setToolTipText("Abre a conferência com o aviso: um repositório na pasta principal esconderia o histórico do que já existe numa subpasta");
        criarMesmoAssim.addActionListener(e -> ctx.criarRepositorio.accept(ctx.recarregarGit, () -> ctx.navegar.accept(TelaProjeto.Aba.GIT)));
        carregarMais.setName("carregar-mais-commits");
        carregarMais.addActionListener(e -> carregarMaisCommits());
        carregarMais.setVisible(false);
        criar.setName("criar-repositorio-git-da-aba");
        criar.addActionListener(e -> ctx.criarRepositorio.accept(ctx.recarregarGit, () -> ctx.navegar.accept(TelaProjeto.Aba.GIT)));
        instalar.setName("instalar-git-da-aba");
        instalar.addActionListener(e -> ctx.instalarGit.accept(ctx.recarregarGit));
        tentarDeNovo.setName("tentar-git-da-aba");
        tentarDeNovo.addActionListener(e -> recarregar());
        comando.putClientProperty("html.disable", Boolean.TRUE);
        comando.setName("comando-safe-directory-da-aba");
        verArvore.addActionListener(e -> aoTrocarAVistaDaPendencia(false));
        verIndice.addActionListener(e -> aoTrocarAVistaDaPendencia(true));
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(verArvore);
        g.add(verIndice);
        verArvore.setSelected(true);

        // a barra de cima
        JPanel barra = new Painel(new BorderLayout(12, 0));
        barra.add(sub, BorderLayout.WEST);
        barra.add(Caixa.linha(10, filtroDeBranch, voltarAoAtual), BorderLayout.CENTER);
        barra.add(Caixa.linha(10, situacao, atualizar), BorderLayout.EAST);

        // o cartão de mensagem
        linhaDoComando.add(comando, BorderLayout.CENTER);
        linhaDoComando.add(copiarComando, BorderLayout.EAST);
        linhaDoComando.setVisible(false);
        JPanel mensagem = new Painel();
        mensagem.setLayout(new BoxLayout(mensagem, BoxLayout.Y_AXIS));
        mensagem.setBorder(BorderFactory.createEmptyBorder(24, 8, 8, 8));
        for (JComponent c : new JComponent[] {mensagemTitulo, mensagemTexto, linhaDoComando}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
        }
        mensagem.add(mensagemTitulo);
        mensagem.add(Caixa.espacoVertical(6));
        mensagem.add(mensagemTexto);
        mensagem.add(Caixa.espacoVertical(8));
        mensagem.add(linhaDoComando);
        mensagem.add(Caixa.espacoVertical(12));
        JScrollPane rolagemDeRepositorios = tabelaDeRepositorios.emRolagem();
        seletor.add(rolagemDeRepositorios, BorderLayout.CENTER);
        seletor.add(new AEsquerda(criarMesmoAssim), BorderLayout.SOUTH);
        seletor.setAlignmentX(LEFT_ALIGNMENT);
        seletor.setVisible(false);
        mensagem.add(seletor);
        mensagem.add(Caixa.espacoVertical(8));
        JPanel botoes = new AEsquerda(Caixa.linha(8, criar, instalar, tentarDeNovo, listaDeRepositorios));
        botoes.setAlignmentX(LEFT_ALIGNMENT);
        mensagem.add(botoes);
        criar.setVisible(false);
        instalar.setVisible(false);
        tentarDeNovo.setVisible(false);
        listaDeRepositorios.setVisible(false);
        JPanel mensagemAEsquerda = new Painel(new BorderLayout());
        mensagemAEsquerda.add(mensagem, BorderLayout.NORTH);

        // commits: a lista em cima, o detalhe embaixo
        JScrollPane rolagemDeCommits = tabelaDeCommits.emRolagem();
        rolagemDeCommits.setPreferredSize(new Dimension(300, 170)); // a página nunca rola: o que passa da janela rola POR DENTRO de cada tabela
        JPanel rodapeDaLista = new Painel(new BorderLayout(10, 0));
        rodapeDaLista.add(Caixa.linha(10, carregarMais, contagemDeCommits), BorderLayout.WEST);
        JPanel lista = new Painel(new BorderLayout(0, 6));
        lista.add(rolagemDeCommits, BorderLayout.CENTER);
        lista.add(rodapeDaLista, BorderLayout.SOUTH);
        semCommits.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 8));
        semCommits.setVisible(false);
        lista.add(semCommits, BorderLayout.NORTH);

        JScrollPane rolagemDeArquivos = tabelaDeArquivos.emRolagem();
        rolagemDeArquivos.setMinimumSize(new Dimension(160, 80));
        rolagemDeArquivos.setPreferredSize(new Dimension(220, 120));
        JPanel arquivos = new Painel(new BorderLayout(0, 4));
        arquivos.add(rolagemDeArquivos, BorderLayout.CENTER);
        arquivos.add(semArquivos, BorderLayout.SOUTH);
        semArquivos.setVisible(false);
        JSplitPane arquivosEDiff = dividir(JSplitPane.HORIZONTAL_SPLIT, arquivos, diffDoCommit, 0.30);

        mensagemDoCommit.setName("mensagem-do-commit");
        JScrollPane rolagemDaMensagem = new JScrollPane(mensagemDoCommit);
        rolagemDaMensagem.setBorder(BorderFactory.createEmptyBorder());
        rolagemDaMensagem.setOpaque(false);
        rolagemDaMensagem.getViewport().setOpaque(false);
        rolagemDaMensagem.setPreferredSize(new Dimension(300, 52));
        rolagemDaMensagem.setMinimumSize(new Dimension(100, 36));
        JPanel cabecalho = new Painel();
        cabecalho.setLayout(new BoxLayout(cabecalho, BoxLayout.Y_AXIS));
        JPanel linhaDoHash = new Painel(new BorderLayout(10, 0));
        linhaDoHash.add(hashCompleto, BorderLayout.CENTER);
        linhaDoHash.add(copiarHash, BorderLayout.EAST);
        for (JComponent c : new JComponent[] {linhaDoHash, autorEData, rolagemDaMensagem}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            cabecalho.add(c);
        }
        copiarHash.setName("copiar-hash");
        copiarHash.setEnabled(false);
        detalhe.add(cabecalho, BorderLayout.NORTH);
        detalhe.add(arquivosEDiff, BorderLayout.CENTER);
        JPanel detalheComTopo = new Painel(new BorderLayout());
        detalheComTopo.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        detalheComTopo.add(detalhe, BorderLayout.CENTER);
        JSplitPane commitsEDetalhe = dividir(JSplitPane.VERTICAL_SPLIT, lista, detalheComTopo, 0.36);
        limparODetalhe("Escolha um commit na lista para ver o que ele mudou.");

        // alterações
        JScrollPane rolagemDePendencias = tabelaDePendencias.emRolagem();
        rolagemDePendencias.setPreferredSize(new Dimension(300, 150));
        JPanel vistas = Caixa.linha(6, verArvore, verIndice, Caixa.mola());
        JPanel baixoDasPendencias = new Painel(new BorderLayout(0, 6));
        baixoDasPendencias.add(vistas, BorderLayout.NORTH);
        baixoDasPendencias.add(diffPendente, BorderLayout.CENTER);
        vistas.setVisible(false);
        semAlteracoes.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 8));
        semAlteracoes.setVisible(false);
        JPanel cimaDasPendencias = new Painel(new BorderLayout());
        cimaDasPendencias.add(semAlteracoes, BorderLayout.NORTH);
        cimaDasPendencias.add(rolagemDePendencias, BorderLayout.CENTER);
        JSplitPane pendenciasEDiff = dividir(JSplitPane.VERTICAL_SPLIT, cimaDasPendencias, baixoDasPendencias, 0.36);
        diffPendente.vazio("Escolha uma alteração para ver o que mudou.");

        // branches
        JScrollPane rolagemDeBranches = tabelaDeBranches.emRolagem();
        rolagemDeBranches.setPreferredSize(new Dimension(300, 200));
        Rotulo dicaDosBranches = new Rotulo("Clique num branch para ver os commits dele na aba Commits.").corDoToken(Tokens.TEXTO3).tamanho(12f);
        JPanel branches = new Painel(new BorderLayout(0, 6));
        branches.add(rolagemDeBranches, BorderLayout.CENTER);
        branches.add(dicaDosBranches, BorderLayout.SOUTH);

        corpoDasSub.add(commitsEDetalhe, SUB_COMMITS);
        corpoDasSub.add(pendenciasEDiff, SUB_ALTERACOES);
        corpoDasSub.add(branches, SUB_BRANCHES);

        // quando o Git lido é o de uma subpasta, uma faixa de uma linha diz qual (e leva de volta à lista) ANTES da barra: na barra não sobra largura
        JPanel faixaDoRepositorio = new Painel(new BorderLayout(10, 0));
        faixaDoRepositorio.add(Caixa.linha(10, repositorioEmUso, trocarRepositorio, Caixa.mola()), BorderLayout.CENTER);
        faixaDoRepositorio.setVisible(false);
        faixaRepositorio = faixaDoRepositorio;
        JPanel cimaDoConteudo = new Painel(new BorderLayout(0, 8));
        cimaDoConteudo.add(faixaDoRepositorio, BorderLayout.NORTH);
        cimaDoConteudo.add(barra, BorderLayout.CENTER);
        JPanel conteudo = new Painel(new BorderLayout(0, 10));
        conteudo.add(cimaDoConteudo, BorderLayout.NORTH);
        conteudo.add(corpoDasSub, BorderLayout.CENTER);
        raiz.add(mensagemAEsquerda, CARTAO_MENSAGEM);
        raiz.add(conteudo, CARTAO_CONTEUDO);
        mostrarMensagem("Lendo o Git do projeto…", "", null, false, false, false);
        return raiz;
    }

    private static JSplitPane dividir(int direcao, JComponent a, JComponent b, double peso) {
        JSplitPane s = new JSplitPane(direcao, a, b);
        s.setContinuousLayout(true);
        s.setResizeWeight(peso);
        s.setDividerSize(8);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.setOpaque(false);
        return s;
    }

    // ---------------------------------------------------------------------------------------------
    // Ler (só quando o usuário olha)
    // ---------------------------------------------------------------------------------------------

    /** Lê tudo de novo: ao abrir a aba, no Atualizar e depois de criar o repositório. Em segundo plano; nunca pelo relógio. */
    void recarregar() {
        LeitorDeGit leitor = ctx.leitorDeGit.get();
        if (leitor == null) {
            mostrarMensagem("Sem conexão com o projeto", "Esta página ainda não tem como ler o Git deste projeto.", null, false, false, true);
            return;
        }
        if (ctx.estado != null && !ctx.estado.local() && !ctx.estado.conexao().permiteExec()) {
            mostrarMensagem("Sem conexão com o servidor", "O Git é lido pela mesma conexão do Terminal, e ela não está de pé agora. Quando voltar, use Atualizar.", null, false, false, true);
            return;
        }
        int minha = geracao.incrementAndGet();
        carregando = true;
        cacheDeDetalhes.clear();
        cacheDeDiffs.clear();
        liberados.clear();
        String branch = branchEscolhido;
        if (!carregouAlgumaVez) {
            mostrarMensagem("Lendo o Git do projeto…", "Só leitura: nada é escrito no servidor.", null, false, false, false);
        }
        situacao.setText("Lendo…");
        atualizar.setEnabled(false);
        Tarefas.rodar(() -> lerTudo(leitor, branch), (Tudo t) -> {
            if (minha != geracao.get()) {
                return;
            }
            carregando = false;
            atualizar.setEnabled(true);
            aplicar(t);
        }, falha -> {
            if (minha != geracao.get()) {
                return;
            }
            carregando = false;
            atualizar.setEnabled(true);
            ultimoErroDeLeitura = MensagensDeErro.de(falha);
            mostrarMensagem("Não consegui ler o Git", ultimoErroDeLeitura, null, false, false, true);
        });
    }

    private static Tudo lerTudo(LeitorDeGit leitor, String branch) throws InterruptedException {
        ResultadoDoGit<ResumoDoRepositorio> r = leitor.resumo();
        ResumoDoRepositorio resumo = r.ok() ? r.valorOuNulo() : ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, r.falhaOuNula());
        if (resumo.estado() != ResumoDoRepositorio.Estado.COM_GIT) {
            return new Tudo(resumo, null, null, null);
        }
        ResultadoDoGit<PaginaDeCommits> commits = leitor.commits(branch, 0, COMMITS_POR_VEZ);
        ResultadoDoGit<List<Branch>> branches = leitor.branches();
        ResultadoDoGit<StatusDoGit> status = leitor.status();
        return new Tudo(resumo, commits, branches, status);
    }

    private void aplicar(Tudo t) {
        resumo = t.resumo();
        carregouAlgumaVez = true;
        switch (resumo.estado()) {
            case SEM_GIT -> mostrarMensagem(ctx.servidor != null && ctx.servidor.ehLocal() ? "Esta pasta, neste computador, não tem Git" : "Esta pasta, no servidor, não tem Git",
                (ctx.servidor != null && ctx.servidor.ehLocal()
                    ? "Se o repositório está em outro lugar ou no GitHub, ele só aparece aqui depois de ser clonado ou copiado para cá. "
                    : "Se o repositório está no seu computador ou no GitHub, ele não aparece aqui até ser clonado ou enviado para o servidor. ")
                    + "O app também pode criar um repositório novo nesta pasta: confere antes (só leitura) e mostra o que entraria no primeiro commit; segredos como o .env nunca entram.",
                null, true, false, false);
            case REPOSITORIOS_ABAIXO -> mostrarRepositoriosAbaixo(resumo);
            case GIT_NAO_INSTALADO -> mostrarMensagem("O Git não está instalado", ctx.estado != null && ctx.estado.protegido()
                ? "O servidor é protegido: o app não instala nada nele. Instale o Git você mesmo (por exemplo no Terminal) e use Atualizar."
                : "Sem o programa git o app não consegue ler nem criar o repositório. \"Instalar o Git…\" mostra os comandos exatos antes de rodar qualquer coisa.",
                null, false, true, true);
            case PROPRIEDADE_DUVIDOSA -> mostrarMensagem("O Git recusou a pasta", resumo.falha().frase(), resumo.falha().comandoParaCopiar(), false, false, true);
            case DENTRO_DE_OUTRO -> mostrarMensagem("Esta pasta está dentro de outro repositório",
                "O Git que vale aqui é o da pasta de cima, e o app não cria um repositório dentro de outro. Abra o Git do projeto de cima.", null, false, false, true);
            case ERRO -> mostrarMensagem("Não consegui ler o Git", resumo.falha() == null ? "Erro desconhecido." : resumo.falha().frase(), null, false, false, true);
            case COM_GIT -> aplicarComGit(t);
        }
        atualizarORepositorioEmUso();
    }

    /** O rótulo "Repositório: app/" e o "Trocar repositório" (barra de cima, e o botão do cartão de mensagem), só quando o Git lido é o de uma subpasta. */
    private void atualizarORepositorioEmUso() {
        boolean emSubpasta = resumo != null && resumo.emSubpasta();
        repositorioEmUso.setText(emSubpasta ? "Repositório: " + resumo.subpasta() + "/" : "");
        repositorioEmUso.setToolTipText(emSubpasta ? "O projeto não tem Git na pasta principal: o app lê o repositório da subpasta " + resumo.subpasta() + "/" : null);
        boolean barra = emSubpasta && !modoMensagem;
        repositorioEmUso.setVisible(barra);
        trocarRepositorio.setVisible(barra);
        if (faixaRepositorio != null) {
            faixaRepositorio.setVisible(barra);
        }
        listaDeRepositorios.setVisible(emSubpasta && modoMensagem);
    }

    /**
     * A pasta principal não tem {@code .git} e há repositório em subpasta, sem escolha ainda: mostra a lista (uma linha por repositório, com o branch e o último commit quando já lidos). Clicar numa
     * linha abre aquele repositório nesta aba. Criar na pasta principal fica num link discreto, com o aviso na janela.
     */
    private void mostrarRepositoriosAbaixo(ResumoDoRepositorio r) {
        List<RepositorioAbaixo> lista = r.abaixo();
        mostrarMensagem("Este projeto não tem Git na pasta principal",
            (lista.size() == 1 ? "Achei um repositório numa subpasta. " : "Achei repositórios em subpastas. ") + "Escolha um para ver os commits, os branches e as alterações dele, como se fosse o projeto. O app lembra a sua escolha."
                + (r.abaixoCortado() ? " Há mais repositórios do que a lista mostra (" + RepositoriosAbaixo.LIMITE + ")." : ""),
            null, false, false, false);
        tabelaDeRepositorios.definir(lista);
        tabelaDeRepositorios.clearSelection();
        JScrollPane rolagem = (JScrollPane) tabelaDeRepositorios.getParent().getParent();
        rolagem.setPreferredSize(new Dimension(300, 36 + 28 * Math.min(lista.size(), 6)));
        seletor.setVisible(true);
        criarMesmoAssim.setEnabled(ctx.acoesLiberadas());
        revalidate();
        repaint();
    }

    private static String ultimoDaLista(RepositorioAbaixo r) {
        if (!r.lido()) {
            return "ainda não lido";
        }
        if (r.ultimo() == null) {
            return "sem commits";
        }
        return r.ultimo().curto() + " " + (r.ultimo().assunto().isEmpty() ? "(sem mensagem)" : r.ultimo().assunto());
    }

    /** O usuário clicou num repositório da lista: o leitor do projeto passa a ler aquela subpasta (e a lembra), e a aba lê de novo. */
    private void aoEscolherORepositorio(RepositorioAbaixo r) {
        if (ctx.leitorDeGit.get() instanceof LeitorDeGitDoProjeto p && p.escolher(r.caminho())) {
            branchEscolhido = null;
            recarregar();
        }
    }

    /** "Trocar repositório": volta à lista (esquece a escolha). */
    private void voltarAListaDeRepositorios() {
        if (ctx.leitorDeGit.get() instanceof LeitorDeGitDoProjeto p) {
            p.voltarALista();
            branchEscolhido = null;
            recarregar();
        }
    }

    private void aplicarComGit(Tudo t) {
        if (!t.commits().ok()) {
            mostrarMensagem("Não consegui ler os commits", t.commits().falhaOuNula().frase(), null, false, false, true);
            return;
        }
        commits.clear();
        commits.addAll(t.commits().valorOuNulo().commits());
        ancora = commits.isEmpty() ? null : commits.get(0).hash();
        temMais = t.commits().valorOuNulo().temMais();
        branchesAtuais = t.branches().ok() ? t.branches().valorOuNulo() : List.of();
        statusAtual = t.status().ok() ? t.status().valorOuNulo() : null;
        tabelaDeCommits.definir(commits);
        tabelaDeBranches.definir(branchesAtuais);
        List<Pendencia> pendencias = statusAtual == null ? List.of() : statusAtual.pendencias();
        tabelaDePendencias.definir(pendencias);
        semCommits.setVisible(commits.isEmpty() && branchEscolhido == null);
        semAlteracoes.setVisible(pendencias.isEmpty());
        carregarMais.setVisible(temMais);
        carregarMais.setEnabled(true);
        atualizarContagens();
        voltarAoAtual.setVisible(branchEscolhido != null);
        filtroDeBranch.setText(branchEscolhido == null ? "" : "Commits do branch " + branchEscolhido);
        modoMensagem = false;
        cartoes.show(raiz, CARTAO_CONTEUDO);
        if (commits.isEmpty()) {
            limparODetalhe(branchEscolhido == null ? "Quando houver o primeiro commit, ele aparece aqui." : "Este branch não tem commits.");
        } else {
            Commit anterior = detalheAtual == null ? null : detalheAtual.commit();
            int i = anterior == null ? -1 : indiceDoHash(anterior.hash());
            if (i >= 0) {
                tabelaDeCommits.marcarLinha(i);
            } else {
                limparODetalhe("Escolha um commit na lista para ver o que ele mudou.");
            }
        }
        if (pendencias.isEmpty()) {
            diffPendente.vazio("Nenhuma alteração para mostrar.");
        }
        revalidate();
        repaint();
    }

    private int indiceDoHash(String hash) {
        for (int i = 0; i < commits.size(); i++) {
            if (commits.get(i).hash().equals(hash)) {
                return i;
            }
        }
        return -1;
    }

    private void atualizarContagens() {
        int pend = statusAtual == null ? 0 : statusAtual.alteracoes();
        sub.rotulo(SUB_COMMITS, "Commits");
        sub.rotulo(SUB_ALTERACOES, pend == 0 ? "Alterações" : "Alterações (" + pend + ")");
        sub.rotulo(SUB_BRANCHES, branchesAtuais.isEmpty() ? "Branches" : "Branches (" + branchesAtuais.size() + ")");
        contagemDeCommits.setText(commits.isEmpty() ? "" : commits.size() + (commits.size() == 1 ? " commit carregado" : " commits carregados") + (temMais ? " (há mais antigos)" : ""));
        situacao.setText("Lido às " + br.com.labdesktop.util.Formato.fmtHora(ctx.agora(), ctx.fuso));
    }

    private void carregarMaisCommits() {
        LeitorDeGit leitor = ctx.leitorDeGit.get();
        if (leitor == null || carregandoMais || !temMais) {
            return;
        }
        int minha = geracao.get();
        int pular = commits.size();
        String branch = ancora != null ? ancora : branchEscolhido; // a partir da cabeça da primeira página, nunca da cabeça de agora
        carregandoMais = true;
        carregarMais.setEnabled(false);
        Tarefas.rodar(() -> leitor.commits(branch, pular, COMMITS_POR_VEZ), (ResultadoDoGit<PaginaDeCommits> r) -> {
            if (minha != geracao.get()) {
                return;
            }
            carregandoMais = false;
            carregarMais.setEnabled(true);
            if (!r.ok()) {
                contagemDeCommits.setText("Não consegui carregar mais: " + r.falhaOuNula().frase());
                return;
            }
            commits.addAll(r.valorOuNulo().commits());
            temMais = r.valorOuNulo().temMais();
            tabelaDeCommits.definir(commits);
            carregarMais.setVisible(temMais);
            atualizarContagens();
        }, falha -> {
            if (minha != geracao.get()) {
                return;
            }
            carregandoMais = false;
            carregarMais.setEnabled(true);
            contagemDeCommits.setText("Não consegui carregar mais: " + MensagensDeErro.de(falha));
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Escolher um commit, um arquivo, uma alteração, um branch
    // ---------------------------------------------------------------------------------------------

    private void aoEscolherOCommit(Commit c) {
        LeitorDeGit leitor = ctx.leitorDeGit.get();
        if (leitor == null) {
            return;
        }
        int minha = geracaoDoDetalhe.incrementAndGet();
        geracaoDoDiff.incrementAndGet();
        DetalheDoCommit pronto = cacheDeDetalhes.get(c.hash());
        if (pronto != null) {
            mostrarDetalhe(pronto);
            return;
        }
        hashCompleto.setText(c.hash());
        autorEData.setText("Lendo o commit…");
        mensagemDoCommit.setText("");
        copiarHash.setEnabled(false);
        tabelaDeArquivos.definir(List.of());
        semArquivos.setVisible(false);
        diffDoCommit.carregando(c.curto());
        Tarefas.rodar(() -> leitor.detalhe(c.hash()), (ResultadoDoGit<DetalheDoCommit> r) -> {
            if (minha != geracaoDoDetalhe.get()) {
                return;
            }
            if (!r.ok()) {
                limparODetalhe("Não consegui ler este commit: " + r.falhaOuNula().frase());
                return;
            }
            cacheDeDetalhes.put(c.hash(), r.valorOuNulo());
            mostrarDetalhe(r.valorOuNulo());
        }, falha -> {
            if (minha == geracaoDoDetalhe.get()) {
                limparODetalhe("Não consegui ler este commit: " + MensagensDeErro.de(falha));
            }
        });
    }

    private void mostrarDetalhe(DetalheDoCommit d) {
        detalheAtual = d;
        Commit c = d.commit();
        hashCompleto.setText(c.hash());
        hashCompleto.setToolTipText(c.hash());
        autorEData.setText(c.autor() + (c.email().isEmpty() ? "" : " <" + c.email() + ">") + " · " + dataCompleta(c) + " (" + FormatosDoProjeto.haQuanto(c.quando(), ctx.agora()) + ")"
            + (c.ehMerge() ? " · merge" : ""));
        mensagemDoCommit.setText(d.mensagem().isEmpty() ? "(sem mensagem)" : d.mensagem());
        copiarHash.setEnabled(true);
        tabelaDeArquivos.definir(d.arquivos());
        semArquivos.setText(d.arquivos().isEmpty() ? "Este commit não mudou nenhum arquivo (um merge sem diferenças, por exemplo)."
            : d.listaCortada() ? "Lista cortada: o commit mexeu em mais arquivos do que o app lista (" + d.arquivos().size() + " mostrados)."
            : d.arquivos().size() + (d.arquivos().size() == 1 ? " arquivo" : " arquivos") + ": +" + d.totalAcrescentadas() + " acrescentadas, -" + d.totalTiradas() + " tiradas.");
        semArquivos.setVisible(true);
        if (d.arquivos().isEmpty()) {
            diffDoCommit.vazio("Nada para mostrar: o commit não tem arquivos com mudança.");
        } else {
            diffDoCommit.vazio("Escolha um arquivo para ver o que mudou nele.");
            tabelaDeArquivos.selecionarLinha(0);
        }
    }

    private void limparODetalhe(String frase) {
        detalheAtual = null;
        hashCompleto.setText("");
        autorEData.setText("");
        mensagemDoCommit.setText("");
        copiarHash.setEnabled(false);
        tabelaDeArquivos.definir(List.of());
        semArquivos.setVisible(false);
        diffDoCommit.vazio(frase);
    }

    private void aoEscolherOArquivo(ArquivoDoCommit a) {
        LeitorDeGit leitor = ctx.leitorDeGit.get();
        DetalheDoCommit d = detalheAtual;
        if (leitor == null || d == null) {
            return;
        }
        int minha = geracaoDoDiff.incrementAndGet();
        if (a.binario()) { // o git já disse que é binário: não há o que ler
            diffDoCommit.mostrar(a.caminho(), new DiffDoArquivo(List.of(), true, false, false, a.antigo(), false, 0, 0, false));
            return;
        }
        String chave = d.commit().hash() + "\0" + a.caminho() + "\0" + a.antigo();
        DiffDoArquivo pronto = cacheDeDiffs.get(chave);
        if (pronto != null) {
            diffDoCommit.mostrar(a.caminho(), pronto);
            return;
        }
        if (SegredosDoProjeto.pareceSegredo(a.caminho()) && !liberados.contains(chave)) {
            // o conteúdo de um arquivo com cara de segredo nem é lido até o usuário pedir (um .env commitado, por exemplo)
            diffDoCommit.protegido(a.caminho(), () -> {
                liberados.add(chave);
                lerODiffDoCommit(leitor, d, a, chave, geracaoDoDiff.incrementAndGet());
            });
            return;
        }
        lerODiffDoCommit(leitor, d, a, chave, minha);
    }

    private void lerODiffDoCommit(LeitorDeGit leitor, DetalheDoCommit d, ArquivoDoCommit a, String chave, int minha) {
        diffDoCommit.carregando(a.caminho());
        Tarefas.rodar(() -> leitor.diffDoCommit(d.commit().hash(), a), (ResultadoDoGit<DiffDoArquivo> r) -> {
            if (minha != geracaoDoDiff.get()) {
                return;
            }
            if (!r.ok()) {
                diffDoCommit.erro(a.caminho(), r.falhaOuNula().frase());
                return;
            }
            cacheDeDiffs.put(chave, r.valorOuNulo());
            diffDoCommit.mostrar(a.caminho(), r.valorOuNulo());
        }, falha -> {
            if (minha == geracaoDoDiff.get()) {
                diffDoCommit.erro(a.caminho(), MensagensDeErro.de(falha));
            }
        });
    }

    private void aoEscolherAPendencia(Pendencia p) {
        JPanel vistas = (JPanel) verArvore.getParent();
        boolean ambas = p.noIndice() && p.naArvore() && p.tipo() != Pendencia.Tipo.NAO_RASTREADO;
        vistas.setVisible(ambas);
        boolean noIndice = ambas ? verIndice.isSelected() : p.noIndice() && !p.naArvore();
        if (!ambas) {
            (noIndice ? verIndice : verArvore).setSelected(true);
        }
        carregarDiffDaPendencia(p, noIndice);
    }

    private void aoTrocarAVistaDaPendencia(boolean indice) {
        Pendencia p = tabelaDePendencias.selecionado();
        if (p != null) {
            carregarDiffDaPendencia(p, indice);
        }
    }

    private void carregarDiffDaPendencia(Pendencia p, boolean noIndice) {
        LeitorDeGit leitor = ctx.leitorDeGit.get();
        if (leitor == null) {
            return;
        }
        String chave = "pendente\0" + p.caminho() + "\0" + noIndice + "\0" + p.tipo();
        int minha = geracaoDoDiff.incrementAndGet();
        DiffDoArquivo pronto = cacheDeDiffs.get(chave);
        if (pronto != null) {
            diffPendente.mostrar(p.caminho(), pronto);
            return;
        }
        if (SegredosDoProjeto.pareceSegredo(p.caminho()) && !liberados.contains(chave)) {
            // um .env que ainda não é do repositório (nem está no .gitignore) não tem o conteúdo lido nem mostrado até o usuário pedir
            diffPendente.protegido(p.caminho(), () -> {
                liberados.add(chave);
                lerODiffPendente(leitor, p, noIndice, chave, geracaoDoDiff.incrementAndGet());
            });
            return;
        }
        lerODiffPendente(leitor, p, noIndice, chave, minha);
    }

    private void lerODiffPendente(LeitorDeGit leitor, Pendencia p, boolean noIndice, String chave, int minha) {
        diffPendente.carregando(p.caminho());
        Tarefas.rodar(() -> leitor.diffPendente(p, noIndice), (ResultadoDoGit<DiffDoArquivo> r) -> {
            if (minha != geracaoDoDiff.get()) {
                return;
            }
            if (!r.ok()) {
                diffPendente.erro(p.caminho(), r.falhaOuNula().frase());
                return;
            }
            cacheDeDiffs.put(chave, r.valorOuNulo());
            diffPendente.mostrar(p.caminho(), r.valorOuNulo());
        }, falha -> {
            if (minha == geracaoDoDiff.get()) {
                diffPendente.erro(p.caminho(), MensagensDeErro.de(falha));
            }
        });
    }

    private void aoEscolherOBranch(Branch b) {
        if (b.atual() && branchEscolhido == null) {
            sub.selecionar(SUB_COMMITS);
            cartoesDasSub.show(corpoDasSub, SUB_COMMITS);
            return;
        }
        branchEscolhido = b.atual() ? null : b.nome();
        sub.selecionar(SUB_COMMITS);
        cartoesDasSub.show(corpoDasSub, SUB_COMMITS);
        recarregar();
    }

    // ---------------------------------------------------------------------------------------------
    // O cartão de mensagem
    // ---------------------------------------------------------------------------------------------

    private void mostrarMensagem(String titulo, String texto, String comandoParaCopiar, boolean comCriar, boolean comInstalar, boolean comTentar) {
        seletor.setVisible(false);
        mensagemTitulo.setText(titulo);
        mensagemTexto.setText(texto);
        mensagemTexto.setVisible(!texto.isEmpty());
        comando.setText(comandoParaCopiar == null ? "" : comandoParaCopiar);
        linhaDoComando.setVisible(comandoParaCopiar != null);
        criar.setVisible(comCriar);
        instalar.setVisible(comInstalar);
        tentarDeNovo.setVisible(comTentar);
        atualizarConteudo();
        situacao.setText("");
        modoMensagem = true;
        atualizarORepositorioEmUsoNaMensagem();
        cartoes.show(raiz, CARTAO_MENSAGEM);
        revalidate();
        repaint();
    }

    /** Na mensagem, o botão da lista só vale se o que está à vista veio de uma subpasta (a leitura que "Lendo…" e os erros de conexão mostram não traz resumo novo). */
    private void atualizarORepositorioEmUsoNaMensagem() {
        listaDeRepositorios.setVisible(false);
        repositorioEmUso.setVisible(false);
        trocarRepositorio.setVisible(false);
        if (faixaRepositorio != null) {
            faixaRepositorio.setVisible(false);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Textos
    // ---------------------------------------------------------------------------------------------

    private String dataCompleta(Commit c) {
        return DATA_COMPLETA.format(c.quando().atZone(ctx.fuso == null ? ZoneId.systemDefault() : ctx.fuso));
    }

    private static String situacaoDaPendencia(Pendencia p) {
        if (p.tipo() == Pendencia.Tipo.NAO_RASTREADO) {
            return "arquivo novo, ainda não preparado";
        }
        if (p.tipo() == Pendencia.Tipo.CONFLITO) {
            return "em conflito";
        }
        if (p.noIndice() && p.naArvore()) {
            return "preparado e alterado depois";
        }
        return p.noIndice() ? "preparado para o commit" : "ainda não preparado";
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    TabelaDeDados<Commit> tabelaDeCommits() {
        return tabelaDeCommits;
    }

    TabelaDeDados<ArquivoDoCommit> tabelaDeArquivos() {
        return tabelaDeArquivos;
    }

    TabelaDeDados<Pendencia> tabelaDePendencias() {
        return tabelaDePendencias;
    }

    TabelaDeDados<Branch> tabelaDeBranches() {
        return tabelaDeBranches;
    }

    VistaDeDiff diffDoCommit() {
        return diffDoCommit;
    }

    VistaDeDiff diffPendente() {
        return diffPendente;
    }

    Abas subAbas() {
        return sub;
    }

    Botao botaoAtualizar() {
        return atualizar;
    }

    Botao botaoCarregarMais() {
        return carregarMais;
    }

    Botao botaoCriar() {
        return criar;
    }

    Botao botaoInstalar() {
        return instalar;
    }

    Botao botaoTentarDeNovo() {
        return tentarDeNovo;
    }

    Botao botaoCopiarHash() {
        return copiarHash;
    }

    Botao botaoCopiarComando() {
        return copiarComando;
    }

    Botao botaoVoltarAoAtual() {
        return voltarAoAtual;
    }

    TabelaDeDados<RepositorioAbaixo> tabelaDeRepositorios() {
        return tabelaDeRepositorios;
    }

    Botao botaoTrocarRepositorio() {
        return trocarRepositorio;
    }

    Botao botaoListaDeRepositorios() {
        return listaDeRepositorios;
    }

    Botao botaoCriarMesmoAssim() {
        return criarMesmoAssim;
    }

    boolean mostraOSeletor() {
        return seletor.isVisible() && modoMensagem;
    }

    String textoDoRepositorioEmUso() {
        return repositorioEmUso.isVisible() ? repositorioEmUso.getText() : "";
    }

    boolean mostraMensagem() {
        return modoMensagem;
    }

    String tituloDaMensagem() {
        return mensagemTitulo.getText();
    }

    String textoDaMensagem() {
        return mensagemTexto.getText();
    }

    String comandoDaMensagem() {
        return linhaDoComando.isVisible() ? comando.getText() : "";
    }

    String hashDoDetalhe() {
        return hashCompleto.getText();
    }

    String autorEDataDoDetalhe() {
        return autorEData.getText();
    }

    String mensagemDoDetalhe() {
        return mensagemDoCommit.getText();
    }

    String textoDosArquivos() {
        return semArquivos.isVisible() ? semArquivos.getText() : "";
    }

    String textoDaContagemDeCommits() {
        return contagemDeCommits.getText();
    }

    boolean semCommitsVisivel() {
        return semCommits.isVisible();
    }

    boolean semAlteracoesVisivel() {
        return semAlteracoes.isVisible();
    }

    int commitsCarregados() {
        return commits.size();
    }

    String branchEscolhido() {
        return branchEscolhido;
    }

    boolean carregando() {
        return carregando;
    }

    String textoDaSituacao() {
        return situacao.getText();
    }

    JToggleButton botaoVerIndice() {
        return verIndice;
    }

    JToggleButton botaoVerArvore() {
        return verArvore;
    }
}
