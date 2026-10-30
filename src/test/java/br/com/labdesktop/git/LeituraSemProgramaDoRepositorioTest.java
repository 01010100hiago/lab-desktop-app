package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Um repositório MALICIOSO não faz o app rodar um programa dele. O {@code .gitattributes} manda usar um filtro ({@code * filter=x}) e o {@code .git/config} liga esse filtro a um programa que deixa um arquivo-marcador:
 * depois de CADA leitura do app o marcador NÃO existe. O controle prova que o teste pode falhar: o próprio git, sem as proteções, cria o marcador. Dois caminhos: o Git 2.40 ou mais novo (atributos neutros em toda
 * leitura) e o 2.28 a 2.39 (confere a configuração antes de ler e se recusa a ler o que executa).
 */
class LeituraSemProgramaDoRepositorioTest {

    @TempDir
    Path tmp;

    GitDeTeste g;
    Path repo;
    Path marcador;

    @BeforeEach
    void preparar() throws Exception {
        assumeTrue(GitDeTeste.temGit(), "sem git neste computador");
        g = new GitDeTeste(tmp);
        repo = g.repositorio("loja-web");
        g.escrever(repo, "a.txt", "um\ndois\n");
        g.escrever(repo, ".gitattributes", "* filter=x\n");
        g.commitar(repo, "Primeiro commit");
        marcador = tmp.resolve("MARCADOR-DO-PROGRAMA");
        Path programa = tmp.resolve("limpar.sh");
        Files.writeString(programa, "#!/bin/sh\necho executou >> '" + marcador + "'\ncat\n");
        programa.toFile().setExecutable(true);
        g.git(repo, "config", "--local", "filter.x.clean", programa.toString());
        // alterado e novo, para o status e o diff terem o que comparar
        g.escrever(repo, "a.txt", "um\ndois\ntres\n");
        g.escrever(repo, "novo.txt", "oi\n");
    }

    private static int versaoDoGit(GitDeTeste g, Path repo) {
        int[] v = ClassificadorDeErrosDoGit.versao(g.git(repo, "--version"));
        return v[0] * 100 + v[1];
    }

    private void lerTudo(LeitorDeGit leitor) throws Exception {
        leitor.resumo();
        StatusDoGit st = leitor.status().valorOuNulo();
        if (st != null) {
            for (Pendencia p : st.pendencias()) {
                leitor.diffPendente(p, false);
                leitor.diffPendente(p, true);
            }
        }
        List<Commit> commits = leitor.commits(null, 0, 10).valorOuNulo().commits();
        DetalheDoCommit d = leitor.detalhe(commits.get(0).hash()).valorOuNulo();
        leitor.diffDoCommit(commits.get(0).hash(), d.arquivos().get(0));
        leitor.branches();
    }

    @Test
    void oControleOGitSemAsProtecoesRodaOProgramaDoRepositorio() {
        g.bruto(repo, "diff");
        g.bruto(repo, "status");
        assertTrue(Files.exists(marcador), "sem as proteções do app o git roda o filtro do .git/config: o teste pode falhar");
    }

    @Test
    void comGit240OuMaisONovoAppNuncaRodaOProgramaEmNenhumaLeitura() throws Exception {
        assumeTrue(versaoDoGit(g, repo) >= 240, "este git não tem --attr-source");
        LeitorDeGit leitor = new LeitorDeGitPorExecutor(g.executor(), repo.toString());
        assertFalse(Files.exists(marcador));
        ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado(), "ler funciona normalmente: " + r.falha());
        assertEquals(2, r.alteracoes());
        assertFalse(Files.exists(marcador), "o resumo (que roda o status) não executou o programa");
        lerTudo(leitor);
        assertFalse(Files.exists(marcador), "nem o status, nem o diff do que não foi commitado, nem o de um arquivo novo, nem log, show e branches");
        // o status e o diff continuam úteis: mostram a mudança de verdade
        Pendencia mod = leitor.status().valorOuNulo().pendencias().stream().filter(p -> p.caminho().equals("a.txt")).findFirst().orElseThrow();
        DiffDoArquivo diff = leitor.diffPendente(mod, false).valorOuNulo();
        assertEquals(1, diff.acrescentadas());
        assertTrue(g.comandos.stream().anyMatch(c -> c.stream().anyMatch(x -> x.startsWith("--attr-source="))), "a opção foi mesmo posta: " + g.comandos);
    }

    /** Um executor que diz ser um Git 2.39 (sem {@code --attr-source}) e deixa o resto para o git de verdade. */
    private ExecutorDeArgumentos gitAntigo() {
        ExecutorDeArgumentos real = g.executor();
        return new ExecutorDeArgumentos() {
            @Override
            public ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
                if (argumentos.equals(List.of("git", "--version"))) {
                    return new ResultadoExec(0, "git version 2.39.0\n".getBytes(StandardCharsets.UTF_8), new byte[0], false);
                }
                assertTrue(argumentos.stream().noneMatch(x -> x.startsWith("--attr-source=")), "um git antigo não recebe a opção que ele não conhece: " + argumentos);
                return real.executar(argumentos, ambiente, stdin, prazo);
            }
        };
    }

    @Test
    void comGitDe228A239OAppConfereAConfiguracaoERecusaLerOQueExecutaPrograma() throws Exception {
        LeitorDeGit leitor = new LeitorDeGitPorExecutor(gitAntigo(), repo.toString());
        ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado());
        assertEquals(FalhaDoGit.Tipo.CONFIGURACAO_QUE_EXECUTA, r.falha().tipo());
        assertTrue(r.falha().frase().contains("filter.x.clean"), r.falha().frase());
        assertTrue(r.falha().frase().contains("não lê o Git dele por segurança"));
        ResultadoDoGit<StatusDoGit> st = leitor.status();
        assertFalse(st.ok());
        assertEquals(FalhaDoGit.Tipo.CONFIGURACAO_QUE_EXECUTA, st.falhaOuNula().tipo());
        Pendencia qualquer = new Pendencia("a.txt", null, Pendencia.Tipo.MODIFICADO, false, true);
        assertFalse(leitor.diffPendente(qualquer, false).ok());
        assertFalse(Files.exists(marcador), "o programa NÃO rodou: nem status nem diff foram executados");
        // o histórico não passa por filtro e continua legível
        assertEquals(1, leitor.commits(null, 0, 10).valorOuNulo().commits().size());
        assertTrue(g.comandos.stream().noneMatch(c -> c.contains("status") || c.contains("diff")), "nem um status nem um diff saiu: " + g.comandos);
    }

    @Test
    void comGitAntigoUmRepositorioLimpoLeNormalmente() throws Exception {
        g.git(repo, "config", "--local", "--unset", "filter.x.clean");
        LeitorDeGit leitor = new LeitorDeGitPorExecutor(gitAntigo(), repo.toString());
        ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado(), String.valueOf(r.falha()));
        assertEquals(2, r.alteracoes());
        Pendencia mod = leitor.status().valorOuNulo().pendencias().stream().filter(p -> p.caminho().equals("a.txt")).findFirst().orElseThrow();
        assertEquals(1, leitor.diffPendente(mod, false).valorOuNulo().acrescentadas());
    }

    @Test
    void cadaChaveQueExecutaProgramaEPegaNaConferencia() throws Exception {
        g.git(repo, "config", "--local", "--unset", "filter.x.clean");
        for (String[] chave : new String[][] {{"core.fsmonitor", "/bin/true"}, {"core.hooksPath", "/tmp"}, {"core.sshCommand", "ssh -v"}, {"core.pager", "cat"},
            {"diff.x.textconv", "cat"}, {"diff.x.command", "echo"}, {"filter.y.smudge", "cat"}, {"include.path", "../outro"}}) {
            g.git(repo, "config", "--local", chave[0], chave[1]);
            ResumoDoRepositorio r = new LeitorDeGitPorExecutor(gitAntigo(), repo.toString()).resumo().valorOuNulo();
            assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado(), chave[0]);
            g.git(repo, "config", "--local", "--unset", chave[0]);
        }
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, new LeitorDeGitPorExecutor(gitAntigo(), repo.toString()).resumo().valorOuNulo().estado());
    }
}
