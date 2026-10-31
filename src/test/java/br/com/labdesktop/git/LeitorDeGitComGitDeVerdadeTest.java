package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O {@link LeitorDeGit} contra o {@code git} de verdade, por "Este computador" (processo local): vários commits, repositório vazio, merge, binário, nome com acento, branch, alteração
 * ainda não commitada, pasta sem Git, pasta dentro de outro repositório, dono diferente (o próprio git é enganado por {@code GIT_TEST_ASSUME_DIFFERENT_OWNER}) e a tradução dos erros.
 */
class LeitorDeGitComGitDeVerdadeTest {

    @TempDir
    Path tmp;

    GitDeTeste g;

    @BeforeEach
    void preparar() {
        assumeTrue(GitDeTeste.temGit(), "sem git neste computador");
        g = new GitDeTeste(tmp);
    }

    LeitorDeGit leitor(Path pasta) {
        return new LeitorDeGitPorExecutor(g.executor(), pasta.toString());
    }

    private Path repositorioComHistoria() {
        Path p = g.repositorio("loja-web");
        g.escrever(p, "a.txt", "a\nb\n");
        g.escreverBytes(p, "logo.bin", new byte[] {0, 1, 2, 'b', 'i', 'n', 0});
        g.escrever(p, "ação x.txt", "olá\n");
        g.commitar(p, "Primeiro commit");
        g.git(p, "mv", "a.txt", "c.txt");
        g.escrever(p, "c.txt", "a\nb\nc\n");
        g.escrever(p, "d.txt", "novo");
        g.commitar(p, "Segundo commit\n\nCom um corpo de mensagem\nem duas linhas.");
        g.git(p, "rm", "-q", "d.txt");
        g.escrever(p, "src/app.js", "linha 1\nlinha 2");
        g.commitar(p, "Terceiro: apaga d.txt e cria src/app.js");
        return p;
    }

    @Test
    void resumoDeUmRepositorioComHistoria() throws Exception {
        Path p = repositorioComHistoria();
        ResumoDoRepositorio r = leitor(p).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
        assertEquals("main", r.branch());
        assertEquals(0, r.alteracoes());
        assertNotNull(r.ultimo());
        assertEquals("Terceiro: apaga d.txt e cria src/app.js", r.ultimo().assunto());
        assertEquals("Ana Teste", r.ultimo().autor());
        assertEquals(2, r.ultimo().arquivos());
        assertEquals(7, r.ultimo().curto().length() >= 7 ? 7 : r.ultimo().curto().length());
        assertTrue(ValidadorDeGit.hashValido(r.ultimo().hash()));
    }

    @Test
    void alteracoesNaoCommitadasSaoContadasEListadasComDiff() throws Exception {
        Path p = repositorioComHistoria();
        g.escrever(p, "c.txt", "a\nb\nc\nd\n");           // modificado
        g.escrever(p, "novo.txt", "oi\n");                  // não rastreado
        g.escrever(p, "src/pronto.js", "x\n");              // novo, já preparado
        g.git(p, "add", "src/pronto.js");
        ResultadoDoGit<StatusDoGit> st = leitor(p).status();
        StatusDoGit s = st.valorOuNulo();
        assertEquals("main", s.branch());
        assertEquals(3, s.alteracoes());
        Pendencia mod = s.pendencias().stream().filter(x -> x.caminho().equals("c.txt")).findFirst().orElseThrow();
        assertEquals(Pendencia.Tipo.MODIFICADO, mod.tipo());
        assertTrue(mod.naArvore());
        assertFalse(mod.noIndice());
        Pendencia nao = s.pendencias().stream().filter(x -> x.caminho().equals("novo.txt")).findFirst().orElseThrow();
        assertEquals(Pendencia.Tipo.NAO_RASTREADO, nao.tipo());
        Pendencia pronto = s.pendencias().stream().filter(x -> x.caminho().equals("src/pronto.js")).findFirst().orElseThrow();
        assertEquals(Pendencia.Tipo.ADICIONADO, pronto.tipo());
        assertTrue(pronto.noIndice());

        assertEquals(3, leitor(p).resumo().valorOuNulo().alteracoes());

        DiffDoArquivo dm = leitor(p).diffPendente(mod, false).valorOuNulo();
        assertEquals(1, dm.acrescentadas());
        assertEquals(0, dm.tiradas());
        assertTrue(dm.linhas().stream().anyMatch(l -> l.tipo() == LinhaDeDiff.Tipo.ACRESCENTADA && l.texto().equals("d")));

        DiffDoArquivo dn = leitor(p).diffPendente(nao, false).valorOuNulo();
        assertEquals(1, dn.acrescentadas(), "arquivo não rastreado: o diff é contra o vazio");
        DiffDoArquivo dp = leitor(p).diffPendente(pronto, true).valorOuNulo();
        assertEquals(1, dp.acrescentadas());
    }

    @Test
    void listaOsCommitsComAutorDataAssuntoENumeroDeArquivos() throws Exception {
        Path p = repositorioComHistoria();
        PaginaDeCommits pg = leitor(p).commits(null, 0, 50).valorOuNulo();
        assertEquals(3, pg.commits().size());
        assertFalse(pg.temMais());
        assertEquals("Terceiro: apaga d.txt e cria src/app.js", pg.commits().get(0).assunto());
        assertEquals("Segundo commit", pg.commits().get(1).assunto(), "só a primeira linha da mensagem");
        assertEquals("Primeiro commit", pg.commits().get(2).assunto());
        assertEquals(3, pg.commits().get(2).arquivos());
        assertEquals(1, pg.commits().get(0).pais().size());
        assertEquals(0, pg.commits().get(2).pais().size(), "o primeiro commit não tem pai");
        assertTrue(pg.commits().get(0).quando().isAfter(java.time.Instant.now().minusSeconds(600)));
    }

    @Test
    void carregarMaisPagina() throws Exception {
        Path p = g.repositorio("app-exemplo");
        for (int i = 1; i <= 7; i++) {
            g.escrever(p, "f" + i + ".txt", "x" + i);
            g.commitar(p, "commit " + i);
        }
        PaginaDeCommits a = leitor(p).commits(null, 0, 3).valorOuNulo();
        assertEquals(List.of("commit 7", "commit 6", "commit 5"), a.commits().stream().map(Commit::assunto).toList());
        assertTrue(a.temMais());
        PaginaDeCommits b = leitor(p).commits(null, 3, 3).valorOuNulo();
        assertEquals(List.of("commit 4", "commit 3", "commit 2"), b.commits().stream().map(Commit::assunto).toList());
        assertTrue(b.temMais());
        PaginaDeCommits c = leitor(p).commits(null, 6, 3).valorOuNulo();
        assertEquals(List.of("commit 1"), c.commits().stream().map(Commit::assunto).toList());
        assertFalse(c.temMais());
    }

    @Test
    void detalheDoCommitComRenomeadoBinarioEAcento() throws Exception {
        Path p = repositorioComHistoria();
        List<Commit> cs = leitor(p).commits(null, 0, 10).valorOuNulo().commits();
        String primeiro = cs.get(2).hash();
        DetalheDoCommit d1 = leitor(p).detalhe(primeiro).valorOuNulo();
        assertEquals(3, d1.arquivos().size());
        ArquivoDoCommit bin = d1.arquivos().stream().filter(a -> a.caminho().equals("logo.bin")).findFirst().orElseThrow();
        assertTrue(bin.binario());
        ArquivoDoCommit acento = d1.arquivos().stream().filter(a -> a.caminho().equals("ação x.txt")).findFirst().orElseThrow();
        assertEquals(1, acento.acrescentadas());
        DiffDoArquivo dbin = leitor(p).diffDoCommit(primeiro, bin).valorOuNulo();
        assertTrue(dbin.binario());
        assertTrue(dbin.linhas().isEmpty());
        DiffDoArquivo dac = leitor(p).diffDoCommit(primeiro, acento).valorOuNulo();
        assertTrue(dac.linhas().stream().anyMatch(l -> l.tipo() == LinhaDeDiff.Tipo.ACRESCENTADA && l.texto().equals("olá")), "nome com acento e espaço: o diff sai exato");

        String segundo = cs.get(1).hash();
        DetalheDoCommit d2 = leitor(p).detalhe(segundo).valorOuNulo();
        assertEquals("Segundo commit\n\nCom um corpo de mensagem\nem duas linhas.", d2.mensagem());
        assertEquals("Segundo commit", d2.commit().assunto());
        ArquivoDoCommit ren = d2.arquivos().stream().filter(ArquivoDoCommit::renomeado).findFirst().orElseThrow();
        assertEquals("a.txt", ren.antigo());
        assertEquals("c.txt", ren.caminho());
        DiffDoArquivo dren = leitor(p).diffDoCommit(segundo, ren).valorOuNulo();
        assertEquals("a.txt", dren.antigo());
        assertEquals(1, dren.acrescentadas());
        assertTrue(dren.linhas().stream().anyMatch(l -> l.tipo() == LinhaDeDiff.Tipo.INFORMACAO && l.texto().contains("renomeado de a.txt")));

        String terceiro = cs.get(0).hash();
        DetalheDoCommit d3 = leitor(p).detalhe(terceiro).valorOuNulo();
        ArquivoDoCommit app = d3.arquivos().stream().filter(a -> a.caminho().equals("src/app.js")).findFirst().orElseThrow();
        DiffDoArquivo dapp = leitor(p).diffDoCommit(terceiro, app).valorOuNulo();
        assertTrue(dapp.semFimDeLinha(), "o arquivo termina sem quebra de linha");
        assertTrue(dapp.linhas().stream().anyMatch(l -> l.tipo() == LinhaDeDiff.Tipo.AVISO));
        ArquivoDoCommit apagado = d3.arquivos().stream().filter(a -> a.caminho().equals("d.txt")).findFirst().orElseThrow();
        DiffDoArquivo dap = leitor(p).diffDoCommit(terceiro, apagado).valorOuNulo();
        assertEquals(1, dap.tiradas());
    }

    @Test
    void repositorioVazioNaoTemCommitsEODizSemQuebrar() throws Exception {
        Path p = g.repositorio("loja-web");
        ResumoDoRepositorio r = leitor(p).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
        assertTrue(r.semCommits());
        assertNull(r.ultimo());
        assertEquals("main", r.branch());
        PaginaDeCommits pg = leitor(p).commits(null, 0, 50).valorOuNulo();
        assertTrue(pg.commits().isEmpty());
        assertFalse(pg.temMais());
        g.escrever(p, "x.txt", "x");
        assertEquals(1, leitor(p).resumo().valorOuNulo().alteracoes(), "arquivo novo num repositório vazio conta como alteração");
    }

    @Test
    void mergeEBranches() throws Exception {
        Path p = g.repositorio("loja-web");
        g.escrever(p, "base.txt", "base\n");
        g.commitar(p, "base");
        g.git(p, "checkout", "-q", "-b", "feature/entrega");
        g.escrever(p, "feature.txt", "f\n");
        g.commitar(p, "na feature");
        g.git(p, "checkout", "-q", "main");
        g.escrever(p, "main.txt", "m\n");
        g.commitar(p, "na main");
        g.git(p, "merge", "-q", "--no-ff", "feature/entrega", "-m", "Mescla a feature");

        List<Branch> bs = leitor(p).branches().valorOuNulo();
        assertEquals(2, bs.size());
        Branch atual = bs.stream().filter(Branch::atual).findFirst().orElseThrow();
        assertEquals("main", atual.nome());
        assertTrue(bs.stream().anyMatch(b -> b.nome().equals("feature/entrega") && !b.atual()));

        Commit merge = leitor(p).commits(null, 0, 10).valorOuNulo().commits().get(0);
        assertEquals("Mescla a feature", merge.assunto());
        assertTrue(merge.ehMerge());
        DetalheDoCommit d = leitor(p).detalhe(merge.hash()).valorOuNulo();
        assertEquals(List.of("feature.txt"), d.arquivos().stream().map(ArquivoDoCommit::caminho).toList(), "o merge mostra o que entrou, contra o primeiro pai");
        assertEquals(1, leitor(p).diffDoCommit(merge.hash(), d.arquivos().get(0)).valorOuNulo().acrescentadas());

        PaginaDeCommits daFeature = leitor(p).commits("feature/entrega", 0, 10).valorOuNulo();
        assertEquals(List.of("na feature", "base"), daFeature.commits().stream().map(Commit::assunto).toList());
    }

    @Test
    void cabecaSoltaEBranchComAlteracao() throws Exception {
        Path p = g.repositorio("loja-web");
        g.escrever(p, "a.txt", "a");
        String h = g.commitar(p, "um");
        g.escrever(p, "a.txt", "a2");
        g.commitar(p, "dois");
        g.git(p, "checkout", "-q", "--detach", h);
        ResumoDoRepositorio r = leitor(p).resumo().valorOuNulo();
        assertTrue(r.cabecaSolta());
        assertNull(r.branch());
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
    }

    @Test
    void textoQueNaoEUtf8NaoQuebra() throws Exception {
        Path p = g.repositorio("loja-web");
        g.escreverBytes(p, "velho.txt", "café maçã\n".getBytes(StandardCharsets.ISO_8859_1)); // Latin-1
        g.commitar(p, "arquivo em Latin-1");
        Commit c = leitor(p).commits(null, 0, 5).valorOuNulo().commits().get(0);
        DetalheDoCommit d = leitor(p).detalhe(c.hash()).valorOuNulo();
        DiffDoArquivo diff = leitor(p).diffDoCommit(c.hash(), d.arquivos().get(0)).valorOuNulo();
        assertTrue(diff.outraCodificacao());
        assertTrue(diff.linhas().stream().anyMatch(l -> l.texto().equals("café maçã")), diff.linhas().toString());
    }

    @Test
    void pastaSemGitDizSemGitEPastaDentroDeOutroRepositorioDizDentroDeOutro() throws Exception {
        Path sem = g.projeto("app-exemplo");
        g.escrever(sem, "a.txt", "a");
        assertEquals(ResumoDoRepositorio.Estado.SEM_GIT, leitor(sem).resumo().valorOuNulo().estado());

        Path pai = g.repositorio("pai");
        Path filho = pai.resolve("subprojeto");
        Files.createDirectories(filho);
        ResumoDoRepositorio r = leitor(filho).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.DENTRO_DE_OUTRO, r.estado());
        assertEquals("subprojeto/", r.pastaPai());
    }

    @Test
    void donoDiferenteViraAFraseEOComandoExatoSemExecutarNada() throws Exception {
        Path p = repositorioComHistoria();
        g.comAmbiente("GIT_TEST_ASSUME_DIFFERENT_OWNER", "1");
        ResumoDoRepositorio r = leitor(p).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA, r.estado());
        assertEquals(FalhaDoGit.Tipo.PROPRIEDADE_DUVIDOSA, r.falha().tipo());
        assertEquals("git config --global --add safe.directory " + p, r.falha().comandoParaCopiar());
        assertTrue(r.falha().frase().contains("outro usuário"));
        assertTrue(g.comandos.stream().noneMatch(c -> c.contains("--global")), "o app nunca roda o safe.directory: " + g.comandos);
        assertTrue(g.comandos.stream().noneMatch(c -> c.contains("safe.directory")));
    }

    @Test
    void pastaQueNaoExisteEOutrosErrosViramFrase() throws Exception {
        Path nada = tmp.resolve("nao-existe");
        ResumoDoRepositorio r = leitor(nada).resumo().valorOuNulo();
        assertTrue(r.estado() == ResumoDoRepositorio.Estado.ERRO || r.estado() == ResumoDoRepositorio.Estado.SEM_GIT, r.estado().toString());
        ResultadoDoGit<DetalheDoCommit> inexistente = leitor(repositorioComHistoria()).detalhe("0000000");
        assertFalse(inexistente.ok());
        assertNotNull(inexistente.falhaOuNula().frase());
    }

    @Test
    void aLeituraNuncaEscreveNoProjeto() throws Exception {
        Path p = repositorioComHistoria();
        g.escrever(p, "c.txt", "mudou\n");
        String antes = g.git(p, "status", "--porcelain");
        long mexido = Files.getLastModifiedTime(p.resolve(".git/index")).toMillis();
        LeitorDeGit l = leitor(p);
        l.resumo();
        l.status();
        l.commits(null, 0, 10);
        l.branches();
        assertEquals(mexido, Files.getLastModifiedTime(p.resolve(".git/index")).toMillis(), "GIT_OPTIONAL_LOCKS=0: ler não atualiza o índice");
        assertEquals(antes, g.git(p, "status", "--porcelain"));
        assertTrue(g.comandos.stream().allMatch(c -> ComandosDeGit.naturezaPelaForma(c) == ComandoGit.Natureza.LEITURA), g.comandos.toString());
    }

    @Test
    void tudoQueOLeitorMandaPassaPelaRegraDeLinhaRemota() throws Exception {
        Path p = repositorioComHistoria();
        LeitorDeGit l = new LeitorDeGitPorExecutor(g.executorComRegraDeSsh(), p.toString());
        assertTrue(l.resumo().ok());
        Commit c = l.commits(null, 0, 5).valorOuNulo().commits().get(0);
        DetalheDoCommit d = l.detalhe(c.hash()).valorOuNulo();
        assertTrue(l.diffDoCommit(c.hash(), d.arquivos().get(0)).ok());
        assertTrue(l.branches().ok());
        assertTrue(l.status().ok());
    }

    // ---------------------------------------------------------------------------------------------
    // Casos de borda da leitura
    // ---------------------------------------------------------------------------------------------

    @Test
    void commitComOsSeparadoresAntigosNoAssuntoENoAutorAparecePorInteiroNaListaEPaginaCerto() throws Exception {
        Path p = g.repositorio("loja-web");
        for (int i = 1; i <= 4; i++) {
            g.escrever(p, "f" + i + ".txt", "x" + i);
            g.git(p, "add", "-A");
            String assunto = i % 2 == 0 ? "assunto\u001edois\u001ftres " + i : "normal " + i;
            // assunto e autor com os separadores antigos, mas fora dos ARGUMENTOS (o Windows não os deixa ir por lá): mensagem pela entrada e autor pelo ambiente
            g.commitarComMensagemPorEntrada(p, Map.of("GIT_AUTHOR_NAME", "Ana\u001fSilva\u001e", "GIT_COMMITTER_NAME", "Ana\u001fSilva\u001e"), assunto, "commit", "-q");
        }
        PaginaDeCommits a = leitor(p).commits(null, 0, 2).valorOuNulo();
        assertEquals(2, a.commits().size(), "os dois commits da página aparecem, os de assunto estranho também");
        assertTrue(a.temMais(), "há mais: calculado sobre o que o git devolveu, não sobre uma lista que perdeu commits");
        assertEquals("assuntodoistres 4", a.commits().get(0).assunto());
        assertEquals("AnaSilva", a.commits().get(0).autor());
        assertEquals("normal 3", a.commits().get(1).assunto());
        PaginaDeCommits b = leitor(p).commits(null, 2, 2).valorOuNulo();
        assertEquals(List.of("assuntodoistres 2", "normal 1"), b.commits().stream().map(Commit::assunto).toList());
        assertFalse(b.temMais());
        assertEquals("assuntodoistres 4", leitor(p).resumo().valorOuNulo().ultimo().assunto());
        DetalheDoCommit d = leitor(p).detalhe(a.commits().get(0).hash()).valorOuNulo();
        assertEquals("assuntodoistres 4", d.commit().assunto(), "o cabeçalho também aguenta o U+001E e o U+001F");
    }

    @Test
    void autorQueSeChamaComoUmaMarcaFixaNaoPartePaginaNemSomeCommit() throws Exception {
        Path p = g.repositorio("loja-web");
        for (String nome : List.of("LABREC", "x", "@")) {
            g.escrever(p, nome + ".txt", nome);
            g.git(p, "add", "-A");
            g.git(p, "-c", "user.name=" + nome, "-c", "user.email=a@example.com", "commit", "-q", "-m", "do " + nome);
        }
        assertEquals(List.of("do @", "do x", "do LABREC"), leitor(p).commits(null, 0, 10).valorOuNulo().commits().stream().map(Commit::assunto).toList());
    }

    @Test
    void asPaginasSeguintesPartemDoHashDaCabecaDaPrimeiraPaginaEUmCommitNovoNaoRepeteNemPula() throws Exception {
        Path p = g.repositorio("loja-web");
        for (int i = 1; i <= 5; i++) {
            g.escrever(p, "f" + i + ".txt", "x" + i);
            g.commitar(p, "commit " + i);
        }
        PaginaDeCommits primeira = leitor(p).commits(null, 0, 2).valorOuNulo();
        assertEquals(List.of("commit 5", "commit 4"), primeira.commits().stream().map(Commit::assunto).toList());
        String ancora = primeira.commits().get(0).hash();
        g.escrever(p, "f6.txt", "x6");
        g.commitar(p, "commit 6, que entrou enquanto o usuário olhava");
        assertEquals(List.of("commit 4", "commit 3"), leitor(p).commits(null, 2, 2).valorOuNulo().commits().stream().map(Commit::assunto).toList(),
            "sem âncora o --skip repete o commit 4: é o erro que a âncora evita");
        PaginaDeCommits segunda = leitor(p).commits(ancora, 2, 2).valorOuNulo();
        assertEquals(List.of("commit 3", "commit 2"), segunda.commits().stream().map(Commit::assunto).toList(), "a página 2 parte da cabeça que o usuário viu: nem repete nem pula");
        assertTrue(segunda.temMais());
        assertEquals(List.of("commit 1"), leitor(p).commits(ancora, 4, 2).valorOuNulo().commits().stream().map(Commit::assunto).toList());
    }

    @Test
    void repositorioSha256ApareceComSeusCommitsEOStatusEODiffFuncionam() throws Exception {
        Path p = g.projeto("loja-web");
        assumeTrue(g.bruto(p, "init", "-q", "-b", "main", "--object-format=sha256").status() == 0, "este git não faz repositório SHA-256");
        g.git(p, "config", "--local", "user.name", "Ana Teste");
        g.git(p, "config", "--local", "user.email", "ana@example.com");
        g.escrever(p, "a.txt", "a\n");
        g.commitar(p, "primeiro no sha256");
        g.escrever(p, "a.txt", "a\nb\n");
        String h64 = g.git(p, "rev-parse", "HEAD").strip();
        assertEquals(64, h64.length());
        g.commitar(p, "segundo no sha256");
        ResumoDoRepositorio r = leitor(p).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
        assertFalse(r.semCommits(), "o repositório tem commits: nunca diz que não tem");
        assertEquals("segundo no sha256", r.ultimo().assunto());
        assertEquals(64, r.ultimo().hash().length());
        PaginaDeCommits pg = leitor(p).commits(null, 0, 10).valorOuNulo();
        assertEquals(2, pg.commits().size());
        DetalheDoCommit d = leitor(p).detalhe(pg.commits().get(0).hash()).valorOuNulo();
        assertEquals(1, leitor(p).diffDoCommit(pg.commits().get(0).hash(), d.arquivos().get(0)).valorOuNulo().acrescentadas());
        g.escrever(p, "a.txt", "a\nb\nc\n"); // alteração ainda não commitada: o status e o diff usam a árvore vazia DESTE repositório
        Pendencia mod = leitor(p).status().valorOuNulo().pendencias().get(0);
        assertEquals(1, leitor(p).diffPendente(mod, false).valorOuNulo().acrescentadas());
        assertEquals(64, leitor(p).commits(pg.commits().get(0).hash(), 0, 5).valorOuNulo().commits().get(0).hash().length(), "a âncora de 64 letras vale");
    }

    @Test
    void pastaNovaNoStatusMostraQuantosArquivosENaoDaErro() throws Exception {
        Path p = repositorioComHistoria();
        g.escrever(p, "novo/a.txt", "a");
        g.escrever(p, "novo/sub/b.txt", "b");
        g.escrever(p, "novo/ação com espaço.txt", "c");
        Path fora = Files.createDirectories(tmp.resolve("fora"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(fora.resolve("x" + i), "x");
        }
        Files.createSymbolicLink(p.resolve("novo/atalho"), fora); // um link para uma pasta com 5 arquivos: não se segue
        StatusDoGit st = leitor(p).status().valorOuNulo();
        Pendencia pasta = st.pendencias().stream().filter(x -> x.caminho().equals("novo/")).findFirst().orElseThrow();
        ResultadoDoGit<DiffDoArquivo> r = leitor(p).diffPendente(pasta, false);
        assertTrue(r.ok(), String.valueOf(r.falhaOuNula()));
        DiffDoArquivo d = r.valorOuNulo();
        assertEquals("pasta nova: 4 arquivos", d.linhas().get(0).texto(), "três arquivos de verdade mais o link, sem seguir o link: " + d.linhas());
        assertTrue(d.linhas().stream().anyMatch(l -> l.texto().equals("novo/ação com espaço.txt")));
        assertTrue(d.linhas().stream().noneMatch(l -> l.texto().contains("x0")), "o que está do outro lado do link não entra");
        assertFalse(d.binario());
    }

    @Test
    void pastaNovaQueEUmRepositorioDeDentroDizIssoEnaoOlhaLaDentro() throws Exception {
        Path p = repositorioComHistoria();
        Path clone = Files.createDirectories(p.resolve("clone-externo"));
        g.git(clone, "init", "-q", "-b", "main");
        g.escrever(clone, "lib.js", "x");
        StatusDoGit st = leitor(p).status().valorOuNulo();
        Pendencia pasta = st.pendencias().stream().filter(x -> x.caminho().equals("clone-externo/")).findFirst().orElseThrow();
        DiffDoArquivo d = leitor(p).diffPendente(pasta, false).valorOuNulo();
        assertTrue(d.linhas().get(0).texto().startsWith("pasta nova com um repositório Git dentro"), d.linhas().toString());
        assertEquals(1, d.linhas().size());
    }

    @Test
    void branchesComAcentoESimbolosAparecemEDaoCommitsInclusivePelaRegraDeLinhaRemota() throws Exception {
        Path p = g.repositorio("loja-web");
        g.escrever(p, "a.txt", "a");
        g.commitar(p, "base");
        for (String nome : List.of("ação", "fix#12", "a+b", "user@host", "feat/versão")) {
            g.git(p, "branch", nome);
        }
        List<Branch> bs = leitor(p).branches().valorOuNulo();
        assertEquals(Set.of("main", "ação", "fix#12", "a+b", "user@host", "feat/versão"), bs.stream().map(Branch::nome).collect(java.util.stream.Collectors.toSet()), "nenhum branch válido é omitido");
        LeitorDeGit pelaRegra = new LeitorDeGitPorExecutor(g.executorComRegraDeSsh(), p.toString());
        for (String nome : List.of("ação", "fix#12", "a+b", "user@host", "feat/versão")) {
            assertEquals(1, pelaRegra.commits(nome, 0, 5).valorOuNulo().commits().size(), "o branch " + nome + " vai entre aspas como texto livre");
        }
    }
}
