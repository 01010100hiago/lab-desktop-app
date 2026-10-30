package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O caso dos projetos cujo repositório mora em {@code <projeto>/app/.git} e a pasta principal NÃO tem {@code .git} (quatro no lab do usuário), com o {@code git} e o {@code find} de
 * VERDADE: a procura acha o repositório de subpasta pelos DOIS caminhos (o {@code find} fixo por executor e o {@code java.nio} de "Este computador"), ignora o que deve, não segue link, respeita
 * profundidade e limite, lê a subpasta como leria a raiz, lembra a escolha, e o motor RECUSA criar na pasta principal por cima de um repositório que existe, a menos que o pedido traga a confirmação.
 */
class RepositoriosEmSubpastaComGitDeVerdadeTest {

    @TempDir
    Path tmp;

    GitDeTeste g;

    @BeforeEach
    void preparar() {
        assumeTrue(GitDeTeste.temGit(), "sem git neste computador");
        g = new GitDeTeste(tmp);
    }

    /** O leitor da pasta principal pelo {@code find} (regra de linha remota ligada, como no SSH). */
    LeitorDeGit viaFind(Path pasta) {
        return new LeitorDeGitPorExecutor(g.executorComRegraDeSsh(), pasta.toString(), false);
    }

    /** O leitor da pasta principal pelo disco, como em "Este computador". */
    LeitorDeGit viaDisco(Path pasta) {
        return new LeitorDeGitPorExecutor(g.executor(), pasta.toString(), true);
    }

    private static List<String> caminhos(ResumoDoRepositorio r) {
        return r.abaixo().stream().map(RepositorioAbaixo::caminho).toList();
    }

    /** Um repositório com dois commits em {@code <projeto>/<sub>}. */
    private Path repoEm(String projeto, String sub, String autorDoCommit) {
        Path p = g.repositorio(projeto + "/" + sub);
        g.escrever(p, "README.md", "# " + sub + "\n");
        g.commitar(p, "Primeiro de " + sub);
        g.escrever(p, "src/" + autorDoCommit + ".js", "x\n");
        g.commitar(p, "Segundo de " + sub);
        return p;
    }

    private void igualNosDoisCaminhos(Path projeto, List<String> esperado) throws Exception {
        for (LeitorDeGit l : List.of(viaFind(projeto), viaDisco(projeto))) {
            ResumoDoRepositorio r = l.resumo().valorOuNulo();
            assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, r.estado(), String.valueOf(r.falha()));
            assertEquals(esperado, caminhos(r), l.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A procura
    // ---------------------------------------------------------------------------------------------

    @Test
    void raizSemGitComRepositorioEmAppNaoEMaisSemGit() throws Exception {
        g.projeto("loja-web");
        repoEm("loja-web", "app", "a");
        Path projeto = g.raiz.resolve("loja-web");
        assertFalse(Files.exists(projeto.resolve(".git")));
        igualNosDoisCaminhos(projeto, List.of("app"));
        ResumoDoRepositorio r = viaFind(projeto).resumo().valorOuNulo();
        assertNull(r.falha());
        assertFalse(r.emSubpasta(), "o leitor da raiz só LISTA; quem escolhe é o leitor do projeto");
        assertTrue(r.abaixo().get(0).comBarra().equals("app/") && !r.abaixo().get(0).lido(), "com um só, nem lê o resto");
    }

    @Test
    void doisRepositoriosVemComBranchEUltimoCommitDeCadaUm() throws Exception {
        g.projeto("painel-api");
        repoEm("painel-api", "api", "x");
        repoEm("painel-api", "web", "y");
        Path projeto = g.raiz.resolve("painel-api");
        for (LeitorDeGit l : List.of(viaFind(projeto), viaDisco(projeto))) {
            ResumoDoRepositorio r = l.resumo().valorOuNulo();
            assertEquals(List.of("api", "web"), caminhos(r));
            for (RepositorioAbaixo a : r.abaixo()) {
                assertTrue(a.lido());
                assertEquals("main", a.branch());
                assertEquals("Segundo de " + a.caminho(), a.ultimo().assunto());
            }
        }
    }

    @Test
    void dependenciasNaoEntramLinkSimbolicoParaForaNaoSegueEProfundidadeQuatroNaoConta() throws Exception {
        g.projeto("portal-docs");
        Path projeto = g.raiz.resolve("portal-docs");
        repoEm("portal-docs", "app", "a");
        repoEm("portal-docs", "servicos/api", "b"); // .git a 3 níveis: entra
        for (String ignorada : List.of("node_modules/pkg", "vendor/lib", "build/gerado", "dist/sub", ".venv/lib", ".cache/x")) {
            g.git(g.projeto("portal-docs/" + ignorada), "init", "-q", "-b", "main");
        }
        g.git(g.projeto("portal-docs/a/b/c"), "init", "-q", "-b", "main"); // .git a 4 níveis: não entra
        // um repositório FORA do projeto, apontado por um link de pasta e por um link no lugar do .git
        Path fora = g.repositorio("../fora-do-projeto/repo");
        g.escrever(fora, "x.txt", "x");
        g.commitar(fora, "de fora");
        Files.createSymbolicLink(projeto.resolve("link-para-fora"), fora);
        Path comGitLink = g.projeto("portal-docs/com-git-link");
        Files.createSymbolicLink(comGitLink.resolve(".git"), fora.resolve(".git"));
        igualNosDoisCaminhos(projeto, List.of("app", "servicos/api"));
    }

    @Test
    void ateVinteRepositoriosEOResto() throws Exception {
        Path projeto = g.projeto("muitos");
        for (int i = 0; i < 25; i++) {
            g.git(g.projeto(String.format("muitos/r%02d", i)), "init", "-q", "-b", "main");
        }
        for (LeitorDeGit l : List.of(viaFind(projeto), viaDisco(projeto))) {
            ResumoDoRepositorio r = l.resumo().valorOuNulo();
            assertEquals(20, r.abaixo().size());
            assertTrue(r.abaixoCortado());
            assertEquals("r00", r.abaixo().get(0).caminho());
            assertEquals("r19", r.abaixo().get(19).caminho());
        }
    }

    @Test
    void pastaComNomeEstranhoEAchadaELidaSemNadaDentroDoNomeViraComando() throws Exception {
        Path projeto = g.projeto("estranho");
        String nome = "ação d'ávila \"x\" $(touch ARMADILHA) `ls` $HOME;rm";
        repoEm("estranho", nome, "z");
        repoEm("estranho", "normal", "n");
        for (LeitorDeGit l : List.of(viaFind(projeto), viaDisco(projeto))) {
            ResumoDoRepositorio r = l.resumo().valorOuNulo();
            assertEquals(List.of(nome, "normal").stream().sorted().toList(), caminhos(r));
        }
        LeitorDeGitDoProjeto lp = new LeitorDeGitDoProjeto(() -> viaFind(projeto), sub -> subLeitor(projeto, sub), LeitorDeGitDoProjeto.Memoria.NENHUMA);
        lp.resumo();
        assertTrue(lp.escolher(nome));
        ResumoDoRepositorio lido = lp.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, lido.estado(), String.valueOf(lido.falha()));
        assertEquals(nome, lido.subpasta());
        assertEquals("Segundo de " + nome, lido.ultimo().assunto());
        assertFalse(Files.exists(Path.of("ARMADILHA")), "o nome entrou como texto, nunca como comando");
    }

    private LeitorDeGit subLeitor(Path projeto, String sub) {
        return new LeitorDeGitPorExecutor(g.executorComRegraDeSsh(), ValidadorDeGit.juntar(projeto.toString(), sub));
    }

    @Test
    void raizQueEhRepositorioNaoProcuraNadaEPastaVaziaESemGit() throws Exception {
        Path repo = g.repositorio("normal");
        g.escrever(repo, "a.txt", "a");
        g.commitar(repo, "um");
        g.git(g.projeto("normal/dentro"), "init", "-q", "-b", "main");
        ResumoDoRepositorio r = viaFind(repo).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
        assertTrue(r.abaixo().isEmpty());
        assertTrue(g.comandos.stream().noneMatch(c -> c.get(0).equals("find")), "com .git na raiz o app nem procura nas subpastas: " + g.comandos);

        Path vazio = g.projeto("vazio");
        ResumoDoRepositorio v = viaFind(vazio).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.SEM_GIT, v.estado());
        assertEquals(ResumoDoRepositorio.Estado.SEM_GIT, viaDisco(vazio).resumo().valorOuNulo().estado());
    }

    @Test
    void oFindEFixoEPorListaNuncaRecebeTextoDoDono() throws Exception {
        g.projeto("loja-web");
        repoEm("loja-web", "app", "a");
        Path projeto = g.raiz.resolve("loja-web");
        viaFind(projeto).resumo();
        List<List<String>> finds = g.comandos.stream().filter(c -> c.get(0).equals("find")).toList();
        assertEquals(1, finds.size());
        List<String> esperado = new ArrayList<>(List.of("find", "-H", projeto.toString()));
        esperado.addAll(ComandosDeGit.FIND_DEPOIS_DA_PASTA);
        assertEquals(esperado, finds.get(0));
        assertTrue(finds.get(0).stream().noneMatch(a -> a.equals("-exec") || a.equals("-delete") || a.startsWith("-fprint") || a.equals("-ok")));
    }

    @Test
    void seAProcuraFalhaOAppNaoDizSemGitNemLiberaCriar() throws Exception {
        g.projeto("loja-web");
        repoEm("loja-web", "app", "a");
        Path projeto = g.raiz.resolve("loja-web");
        ExecutorDeArgumentos semFind = new ExecutorDeArgumentos() {
            @Override
            public ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
                if (argumentos.get(0).equals("find")) {
                    throw new IOException("a conexão caiu");
                }
                return g.executor().executar(argumentos, ambiente, stdin, prazo);
            }
        };
        ResumoDoRepositorio r = new LeitorDeGitPorExecutor(semFind, projeto.toString(), false).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado(), "sem conseguir procurar, 'sem Git' seria mentira e levaria a criar por cima");
        assertTrue(r.falha().frase().contains("não consegui procurar repositórios nas subpastas"), r.falha().frase());

        AmbienteDeProjetos pelaConexao = new AmbienteDeProjetos(false, g.raiz.toString(), g.arquivos(), semFind, x -> x);
        ConferenciaDoRepositorio c = new CriadorDeRepositorio(pelaConexao, projeto.toString()).conferirCriacao();
        assertEquals(Situacao.NAO_CONFERI, c.situacao());
        assertFalse(c.liberaACriacao());
        assertTrue(c.falha().frase().contains("procurar repositórios"), c.falha().frase());
    }

    // ---------------------------------------------------------------------------------------------
    // Ler a subpasta como a raiz, lembrar a escolha
    // ---------------------------------------------------------------------------------------------

    /** A memória de mentira: guarda o que foi lembrado. */
    private static final class MemoriaFalsa implements LeitorDeGitDoProjeto.Memoria {
        final AtomicReference<String> valor = new AtomicReference<>();
        final List<String> historico = new ArrayList<>();

        @Override
        public String lembrada() {
            return valor.get();
        }

        @Override
        public void lembrar(String subpasta) {
            historico.add(String.valueOf(subpasta));
            valor.set(subpasta);
        }
    }

    private LeitorDeGitDoProjeto doProjeto(Path projeto, LeitorDeGitDoProjeto.Memoria memoria) {
        return new LeitorDeGitDoProjeto(() -> viaFind(projeto), sub -> subLeitor(projeto, sub), memoria);
    }

    @Test
    void aSubpastaLidaMostraOsCommitsOsBranchesEAsAlteracoesCertosEIgualAoDaRaiz() throws Exception {
        g.projeto("loja-web");
        Path app = repoEm("loja-web", "app", "a");
        g.git(app, "branch", "feature/entrega");
        g.escrever(app, "README.md", "# mudado\n");
        g.escrever(app, "novo.txt", "n\n");
        Path projeto = g.raiz.resolve("loja-web");
        LeitorDeGitDoProjeto lp = doProjeto(projeto, LeitorDeGitDoProjeto.Memoria.NENHUMA);
        ResumoDoRepositorio r = lp.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado(), "com um só, abre direto nele");
        assertEquals("app", r.subpasta());
        assertEquals(List.of("app"), caminhos(r), "e guarda a lista para voltar a ela");
        assertEquals("main", r.branch());
        assertEquals("Segundo de app", r.ultimo().assunto());
        assertEquals(2, r.alteracoes());
        assertEquals("app", lp.escolhida());

        // os mesmos números que o leitor da própria pasta do repositório dá
        LeitorDeGit direto = viaFind(app);
        List<Commit> doProjeto = lp.commits(null, 0, 10).valorOuNulo().commits();
        List<Commit> daPasta = direto.commits(null, 0, 10).valorOuNulo().commits();
        assertEquals(2, doProjeto.size());
        assertEquals(daPasta.stream().map(Commit::hash).toList(), doProjeto.stream().map(Commit::hash).toList());
        assertEquals(List.of("Segundo de app", "Primeiro de app"), doProjeto.stream().map(Commit::assunto).toList());
        assertEquals(List.of("main", "feature/entrega"), lp.branches().valorOuNulo().stream().map(Branch::nome).sorted((x, y) -> x.equals("main") ? -1 : 1).toList());
        StatusDoGit st = lp.status().valorOuNulo();
        assertEquals(List.of("README.md", "novo.txt"), st.pendencias().stream().map(Pendencia::caminho).sorted().toList());
        DetalheDoCommit d = lp.detalhe(doProjeto.get(0).hash()).valorOuNulo();
        assertEquals(List.of("src/a.js"), d.arquivos().stream().map(ArquivoDoCommit::caminho).toList());
        assertEquals(1, lp.diffDoCommit(doProjeto.get(0).hash(), d.arquivos().get(0)).valorOuNulo().acrescentadas());
        assertEquals(1, lp.diffPendente(st.pendencias().stream().filter(p -> p.caminho().equals("README.md")).findFirst().orElseThrow(), false).valorOuNulo().acrescentadas());
    }

    @Test
    void comDoisSemEscolhaVemALista_aEscolhaLeASubpastaCertaEFicaLembrada() throws Exception {
        g.projeto("painel-api");
        repoEm("painel-api", "api", "x");
        repoEm("painel-api", "web", "y");
        Path projeto = g.raiz.resolve("painel-api");
        MemoriaFalsa memoria = new MemoriaFalsa();
        LeitorDeGitDoProjeto lp = doProjeto(projeto, memoria);
        ResumoDoRepositorio lista = lp.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, lista.estado());
        assertNull(lp.escolhida());
        assertFalse(lp.escolher("nao-existe"), "só escolhe o que a lista tem");
        assertFalse(lp.escolher("../fora"));
        assertTrue(memoria.historico.isEmpty());

        assertTrue(lp.escolher("web"));
        assertEquals(List.of("web"), memoria.historico);
        ResumoDoRepositorio r = lp.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
        assertEquals("web", r.subpasta());
        assertEquals("Segundo de web", r.ultimo().assunto());
        assertEquals(List.of("Segundo de web", "Primeiro de web"), lp.commits(null, 0, 10).valorOuNulo().commits().stream().map(Commit::assunto).toList(), "os commits são os de web, não os de api");

        // outra abertura do app: um leitor NOVO com a mesma memória abre direto em web
        LeitorDeGitDoProjeto outro = doProjeto(projeto, memoria);
        assertEquals("web", outro.resumo().valorOuNulo().subpasta());

        // "voltar à lista" esquece a escolha, também a lembrada
        outro.voltarALista();
        assertEquals(List.of("web", "null"), memoria.historico);
        assertNull(memoria.lembrada());
        assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, outro.resumo().valorOuNulo().estado());
        assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, doProjeto(projeto, memoria).resumo().valorOuNulo().estado(), "e a próxima abertura mostra a lista");
    }

    @Test
    void umaEscolhaLembradaQueSumiuDaListaEIgnoradaEComUmSoVoltarALstaNaoReabreNele() throws Exception {
        g.projeto("loja-web");
        repoEm("loja-web", "app", "a");
        Path projeto = g.raiz.resolve("loja-web");
        MemoriaFalsa memoria = new MemoriaFalsa();
        memoria.valor.set("repositorio-que-sumiu");
        LeitorDeGitDoProjeto lp = doProjeto(projeto, memoria);
        assertEquals("app", lp.resumo().valorOuNulo().subpasta(), "a lembrada não está na lista: vale o único que existe");

        lp.voltarALista();
        ResumoDoRepositorio lista = lp.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, lista.estado(), "o usuário pediu a lista: com um só, não reabre sozinho nele");
        assertEquals(List.of("app"), caminhos(lista));
        assertTrue(lp.escolher("app"));
        assertEquals("app", lp.resumo().valorOuNulo().subpasta());
    }

    @Test
    void seAPastaPrincipalVoltaASerRepositorioOLeitorDoProjetoLeDela() throws Exception {
        g.projeto("loja-web");
        repoEm("loja-web", "app", "a");
        Path projeto = g.raiz.resolve("loja-web");
        LeitorDeGitDoProjeto lp = doProjeto(projeto, LeitorDeGitDoProjeto.Memoria.NENHUMA);
        assertEquals("app", lp.resumo().valorOuNulo().subpasta());
        g.git(projeto, "init", "-q", "-b", "main");
        ResumoDoRepositorio r = lp.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
        assertNull(r.subpasta());
        assertNull(lp.escolhida());
        assertTrue(lp.repositoriosAbaixo().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Criar na pasta principal: o motor recusa
    // ---------------------------------------------------------------------------------------------

    private CriadorDeRepositorio criador(Path pasta) {
        return new CriadorDeRepositorio(g.ambiente(), pasta.toString());
    }

    @Test
    void aConferenciaDizOsRepositoriosAbaixoEOMotorRecusaCriarSemAConfirmacao() throws Exception {
        g.projeto("loja-web");
        Path app = repoEm("loja-web", "app", "a");
        g.escrever(g.raiz.resolve("loja-web"), "docker-compose.yml", "services: {}\n");
        Path projeto = g.raiz.resolve("loja-web");
        CriadorDeRepositorio c = criador(projeto);
        ConferenciaDoRepositorio conf = c.conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, conf.situacao(), String.valueOf(conf.falha()));
        assertEquals(List.of("app"), conf.repositoriosAbaixo());
        assertTrue(conf.temRepositoriosAbaixo());

        int antes = g.comandos.size();
        ResultadoDaCriacao r = c.criarRepositorio(new PedidoDeCriacao(conf, "Ana", "ana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertFalse(r.repositorioCriado());
        assertTrue(r.mensagem().contains("Já existe repositório Git dentro deste projeto, em app/"), r.mensagem());
        assertTrue(r.mensagem().endsWith("Nada foi feito."), r.mensagem());
        assertFalse(Files.exists(projeto.resolve(".git")), "nada foi criado na pasta principal");
        assertTrue(g.comandos.subList(antes, g.comandos.size()).stream().noneMatch(cmd -> cmd.contains("init") || cmd.contains("add") || cmd.contains("commit")),
            "nem um comando de escrita rodou: " + g.comandos.subList(antes, g.comandos.size()));
        assertEquals(List.of("Segundo de app", "Primeiro de app"), List.of(g.git(app, "log", "--format=%s").strip().split("\n")), "o histórico de app/ está intacto");

        // uma conferência ADULTERADA sem a lista não engana o motor: ele procura de novo na hora de criar
        ConferenciaDoRepositorio adulterada = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, conf.versaoDoGit(), "Ana", "ana@example.com", List.of("docker-compose.yml"), List.of(), List.of(), null,
            List.of(), true, false, 0);
        assertFalse(adulterada.temRepositoriosAbaixo());
        ResultadoDaCriacao r2 = c.criarRepositorio(new PedidoDeCriacao(adulterada, null, null, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r2.criou());
        assertFalse(Files.exists(projeto.resolve(".git")));
    }

    @Test
    void comAConfirmacaoExplicitaCriaPorEscolhaDoDonoEAppFicaForaDoCommit() throws Exception {
        g.projeto("loja-web");
        Path app = repoEm("loja-web", "app", "a");
        Path projeto = g.raiz.resolve("loja-web");
        g.escrever(projeto, "docker-compose.yml", "services: {}\n");
        CriadorDeRepositorio c = criador(projeto);
        ConferenciaDoRepositorio conf = c.conferirCriacao();
        ResultadoDaCriacao r = c.criarRepositorio(new PedidoDeCriacao(conf, "Ana", "ana@example.com", false, true), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertTrue(Files.isDirectory(projeto.resolve(".git")));
        List<String> rastreados = List.of(g.git(projeto, "ls-files").strip().split("\n"));
        assertEquals(List.of(".gitignore", "docker-compose.yml"), rastreados, "app/ NÃO entra como submódulo nem como arquivos: fica no .gitignore");
        assertTrue(Files.readString(projeto.resolve(".gitignore")).contains("/app/"));
        assertEquals(List.of("Segundo de app", "Primeiro de app"), List.of(g.git(app, "log", "--format=%s").strip().split("\n")), "o histórico de app/ continua intacto");
    }

    @Test
    void semRepositorioAbaixoCriarContinuaComoSempreSemPedirConfirmacao() throws Exception {
        Path p = g.projeto("simples");
        g.escrever(p, "src/app.js", "x\n");
        CriadorDeRepositorio c = criador(p);
        ConferenciaDoRepositorio conf = c.conferirCriacao();
        assertTrue(conf.repositoriosAbaixo().isEmpty());
        ResultadoDaCriacao r = c.criarRepositorio(new PedidoDeCriacao(conf, "Ana", "ana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertNotNull(g.git(p, "rev-parse", "HEAD"));
    }
}
