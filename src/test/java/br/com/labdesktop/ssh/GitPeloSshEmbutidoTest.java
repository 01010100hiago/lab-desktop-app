package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.CriadorDeRepositorio;
import br.com.labdesktop.git.DetalheDoCommit;
import br.com.labdesktop.git.FalhaDoGit;
import br.com.labdesktop.git.GitDeTeste;
import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.git.LeitorDeGitPorExecutor;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.teste.ShellDeTeste;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O Git do projeto de ponta a ponta pelo servidor SSH EMBUTIDO (MINA): cada linha de comando chega por um canal {@code exec} de uma conexão de verdade e roda no {@code sh} do computador de teste,
 * com o {@code git} de verdade numa pasta temporária. É o mesmo caminho do servidor por SSH, e por isso o mesmo do túnel do Session Manager e da Cloudflare (o transporte só entrega o canal; o que
 * passa por ele não muda): a leitura, a conferência, a criação com identidade de nome estranho (aspas), a área temporária em {@code /tmp} apagada no fim e o servidor sem git.
 */
class GitPeloSshEmbutidoTest {

    @TempDir
    Path tmp;

    private AmbienteDeProjetos ambiente(CenarioDeConexao c, GitDeTeste g) {
        return new AmbienteDeProjetos(false, g.raiz.toString(), g.arquivos(), ExecutorDeArgumentos.porSsh(c.conexao::executar), caminho -> caminho);
    }

    private ShellDeTeste shell(CenarioDeConexao c, GitDeTeste g) {
        return new ShellDeTeste(g.casa, c.ssh).comAmbiente("GIT_CEILING_DIRECTORIES", tmp.toAbsolutePath().toString());
    }

    private static void conectar(CenarioDeConexao c) {
        c.conexao.iniciar();
        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
    }

    private long areasEmTmp() throws Exception {
        try (Stream<Path> s = Files.list(Path.of("/tmp"))) {
            return s.filter(x -> x.getFileName().toString().startsWith("lab-desktop-git-")).count();
        }
    }

    @Test
    void aLeituraPeloCanalExecAcertaOResumoOLogEODiffComNomeComAcentoEEspaco() throws Exception {
        assumeTrue(ShellDeTeste.temShell() && GitDeTeste.temGit());
        GitDeTeste g = new GitDeTeste(tmp);
        Path p = g.repositorio("loja-web");
        g.escrever(p, "ação com espaço.txt", "olá\n");
        g.escrever(p, "it's \"quoted\" $x.txt", "q\n");
        g.commitar(p, "Primeiro commit");
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            shell(c, g);
            conectar(c);
            LeitorDeGit leitor = new LeitorDeGitPorExecutor(ExecutorDeArgumentos.porSsh(c.conexao::executar), p.toString());
            ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
            assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
            assertEquals("main", r.branch());
            assertEquals("Primeiro commit", r.ultimo().assunto());
            String hash = leitor.commits(null, 0, 10).valorOuNulo().commits().get(0).hash();
            DetalheDoCommit d = leitor.detalhe(hash).valorOuNulo();
            assertEquals(2, d.arquivos().size());
            for (var arquivo : d.arquivos()) {
                var diff = leitor.diffDoCommit(hash, arquivo).valorOuNulo();
                assertEquals(1, diff.acrescentadas(), "o diff de '" + arquivo.caminho() + "' chegou pelo canal, entre aspas");
            }
            assertTrue(c.ssh.comandos.stream().anyMatch(l -> l.contains("'ação com espaço.txt'")), c.ssh.comandos.toString());
            assertTrue(c.ssh.comandos.stream().allMatch(l -> l.startsWith("env GIT_OPTIONAL_LOCKS=0") || l.startsWith("git ") || l.startsWith("env ")), "toda linha é um git fixo: " + c.ssh.comandos);
            assertTrue(c.ssh.comandos.stream().noneMatch(l -> l.contains("sudo") || l.contains("--global")));
        }
    }

    @Test
    void criarPeloCanalExecComIdentidadeDeNomeEstranhoEAreaTemporariaApagada() throws Exception {
        assumeTrue(ShellDeTeste.temShell() && GitDeTeste.temGit() && Files.isDirectory(Path.of("/tmp")));
        GitDeTeste g = new GitDeTeste(tmp);
        Path p = g.projeto("loja-web");
        g.escrever(p, ".env", "SENHA=1\n");
        g.escrever(p, "src/app.js", "x\n");
        g.escrever(p, "node_modules/x/i.js", "x\n");
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            shell(c, g);
            conectar(c);
            long antes = areasEmTmp();
            Path armadilha = Path.of("/tmp/lab-desktop-teste-armadilha-" + Long.toHexString(System.nanoTime()));
            String nomeHostil = "Joana D'Ávila $(touch " + armadilha + ")";
            CriadorDeRepositorio criador = new CriadorDeRepositorio(ambiente(c, g), p.toString());
            ConferenciaDoRepositorio conf = criador.conferirCriacao();
            assertEquals(ConferenciaDoRepositorio.Situacao.PODE_CRIAR, conf.situacao(), String.valueOf(conf.falha()));
            assertEquals(List.of("src/app.js"), conf.arquivos());
            assertEquals(antes, areasEmTmp(), "a área temporária de /tmp foi apagada pelo rm -rf de nome exato");
            ResultadoDaCriacao r = criador.criarRepositorio(new PedidoDeCriacao(conf, nomeHostil, "joana@example.com", false),
                OperacoesDeGit.Observador.NENHUM, new Cancelamento());
            assertTrue(r.criou(), r.mensagem());
            assertEquals(nomeHostil, g.git(p, "log", "-1", "--format=%an").strip(), "o nome chegou exato: o texto entrou como UM argumento entre aspas");
            assertFalse(Files.exists(armadilha), "nada dentro do nome virou comando");
            assertEquals(List.of(".gitignore", "src/app.js"), List.of(g.git(p, "ls-files").strip().split("\n")));
            assertTrue(c.ssh.comandos.stream().anyMatch(l -> l.contains("config --local user.name 'Joana D'\\''Ávila")), c.ssh.comandos.toString());
            assertTrue(c.ssh.comandos.stream().anyMatch(l -> l.startsWith("tee /tmp/lab-desktop-git-")));
            assertTrue(c.ssh.comandos.stream().anyMatch(l -> l.startsWith("rm -rf -- /tmp/lab-desktop-git-")));
            assertEquals(antes, areasEmTmp());
            assertTrue(c.ssh.comandos.stream().noneMatch(l -> l.contains("--global") || l.contains("push") || l.contains(" -f ") || l.contains("sudo")));
        }
    }

    @Test
    void servidorSemGitDizGitNaoInstaladoPeloCanalExec() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        GitDeTeste g = new GitDeTeste(tmp);
        Path p = g.projeto("loja-web");
        Path bin = Files.createDirectories(tmp.resolve("bin-sem-git"));
        Files.createSymbolicLink(bin.resolve("env"), Path.of("/usr/bin/env"));
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            new ShellDeTeste(g.casa, c.ssh).comAmbiente("PATH", bin.toString());
            conectar(c);
            LeitorDeGit leitor = new LeitorDeGitPorExecutor(ExecutorDeArgumentos.porSsh(c.conexao::executar), p.toString());
            ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
            assertEquals(ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO, r.estado());
            assertNotNull(r.falha());
            assertEquals(FalhaDoGit.Tipo.GIT_NAO_INSTALADO, r.falha().tipo());
            ConferenciaDoRepositorio conf = new CriadorDeRepositorio(ambiente(c, g), p.toString()).conferirCriacao();
            assertEquals(ConferenciaDoRepositorio.Situacao.GIT_AUSENTE, conf.situacao());
        }
    }

    @Test
    void donoDiferentePeloCanalExecDaOComandoSemExecutalo() throws Exception {
        assumeTrue(ShellDeTeste.temShell() && GitDeTeste.temGit());
        GitDeTeste g = new GitDeTeste(tmp);
        Path p = g.repositorio("loja-web");
        g.escrever(p, "a.txt", "a");
        g.commitar(p, "um");
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            shell(c, g).comAmbiente("GIT_TEST_ASSUME_DIFFERENT_OWNER", "1");
            conectar(c);
            LeitorDeGit leitor = new LeitorDeGitPorExecutor(ExecutorDeArgumentos.porSsh(c.conexao::executar), p.toString());
            ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
            assertEquals(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA, r.estado());
            assertEquals("git config --global --add safe.directory " + p, r.falha().comandoParaCopiar());
            assertTrue(c.ssh.comandos.stream().noneMatch(l -> l.contains("safe.directory")), "o app só MOSTRA o comando: " + c.ssh.comandos);
        }
    }
}
