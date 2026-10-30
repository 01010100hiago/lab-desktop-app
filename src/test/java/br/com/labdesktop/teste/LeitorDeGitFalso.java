package br.com.labdesktop.teste;

import br.com.labdesktop.git.ArquivoDoCommit;
import br.com.labdesktop.git.Branch;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.DetalheDoCommit;
import br.com.labdesktop.git.DiffDoArquivo;
import br.com.labdesktop.git.FalhaDoGit;
import br.com.labdesktop.git.InterpretadorDeDiff;
import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.git.PaginaDeCommits;
import br.com.labdesktop.git.Pendencia;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.git.StatusDoGit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Um {@link LeitorDeGit} de mentira para os testes de tela: devolve o que o teste pôs (nada de git, rede ou processo) e ANOTA cada chamada, então o teste prova também o que a tela NÃO leu
 * (nada de leitura com a aba escondida, nada de leitura de tempos em tempos).
 */
public final class LeitorDeGitFalso implements LeitorDeGit {

    public static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");

    /** Cada chamada, na ordem: {@code resumo}, {@code commits:branch:pular:quantos}, {@code detalhe:hash}, {@code diff:hash:caminho}, {@code branches}, {@code status}, {@code pendente:caminho:indice}. */
    public final List<String> chamadas = new CopyOnWriteArrayList<>();

    public volatile ResultadoDoGit<ResumoDoRepositorio> resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
    public volatile List<Commit> commits = List.of();
    public volatile Map<String, List<Commit>> commitsPorBranch = new HashMap<>();
    public volatile List<Branch> branches = List.of();
    public volatile StatusDoGit status = new StatusDoGit("main", false, null, null, -1, -1, List.of());
    public final Map<String, DetalheDoCommit> detalhes = new HashMap<>();
    public volatile Function<String, ResultadoDoGit<DiffDoArquivo>> diffs = chave -> new ResultadoDoGit.Ok<>(diff("+linha nova"));
    public volatile FalhaDoGit falhaDeCommits;
    public volatile FalhaDoGit falhaDoDetalhe;
    /** Se não for nulo, as leituras de commits esperam por isto (para provar o "carregando"). */
    public volatile java.util.concurrent.CountDownLatch segurar;

    public int quantas(String prefixo) {
        return (int) chamadas.stream().filter(c -> c.startsWith(prefixo)).count();
    }

    // ---------------------------------------------------------------------------------------------

    public static Commit commit(int n) {
        String hash = String.format("%040x", 0x1000000L + n);
        return new Commit(hash, hash.substring(0, 7), n % 2 == 0 ? "Ana Silva" : "Beto Souza", "autor" + n + "@example.com", AGORA.minusSeconds(3600L * n), List.of(String.format("%040x", 0x1000000L + n - 1)),
            "Commit número " + n + ": ajusta o carrinho da loja", n % 3 + 1);
    }

    public static List<Commit> commits(int quantos) {
        List<Commit> l = new ArrayList<>();
        for (int i = quantos; i >= 1; i--) {
            l.add(commit(i));
        }
        return l;
    }

    /** Um diff de um arquivo a partir de linhas com o símbolo na frente ({@code +}, {@code -}, espaço). */
    public static DiffDoArquivo diff(String... linhas) {
        StringBuilder sb = new StringBuilder("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,3 +1,3 @@\n");
        for (String l : linhas) {
            sb.append(l).append('\n');
        }
        return InterpretadorDeDiff.interpretar(sb.toString(), false);
    }

    public static DetalheDoCommit detalhe(Commit c, String mensagem, ArquivoDoCommit... arquivos) {
        return new DetalheDoCommit(c, mensagem, List.of(arquivos), false);
    }

    public static ResumoDoRepositorio comGit(String branch, Commit ultimo, int alteracoes) {
        return new ResumoDoRepositorio(ResumoDoRepositorio.Estado.COM_GIT, branch, false, ultimo, alteracoes, null, null);
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public ResultadoDoGit<String> versao() {
        chamadas.add("versao");
        return new ResultadoDoGit.Ok<>("git version 2.43.0");
    }

    @Override
    public ResultadoDoGit<ResumoDoRepositorio> resumo() throws InterruptedException {
        chamadas.add("resumo");
        java.util.concurrent.CountDownLatch s = segurar;
        if (s != null) {
            s.await();
        }
        return resumo;
    }

    @Override
    public ResultadoDoGit<PaginaDeCommits> commits(String branch, int pular, int quantos) throws InterruptedException {
        chamadas.add("commits:" + branch + ":" + pular + ":" + quantos);
        java.util.concurrent.CountDownLatch s = segurar;
        if (s != null) {
            s.await();
        }
        if (falhaDeCommits != null) {
            return new ResultadoDoGit.Falhou<>(falhaDeCommits);
        }
        List<Commit> todos = branch == null ? commits : commitsPorBranch.getOrDefault(branch, List.of());
        if (branch != null && br.com.labdesktop.git.ValidadorDeGit.hashInteiro(branch)) { // a âncora: a lista A PARTIR daquele commit, como o git faz
            List<Commit> base = commits;
            int i = -1;
            for (int k = 0; k < base.size() && i < 0; k++) {
                if (base.get(k).hash().equals(branch)) {
                    i = k;
                }
            }
            todos = i < 0 ? List.of() : base.subList(i, base.size());
        }
        int de = Math.min(pular, todos.size());
        int ate = Math.min(pular + quantos, todos.size());
        return new ResultadoDoGit.Ok<>(new PaginaDeCommits(todos.subList(de, ate), todos.size() > ate));
    }

    @Override
    public ResultadoDoGit<DetalheDoCommit> detalhe(String hash) {
        chamadas.add("detalhe:" + hash);
        if (falhaDoDetalhe != null) {
            return new ResultadoDoGit.Falhou<>(falhaDoDetalhe);
        }
        DetalheDoCommit d = detalhes.get(hash);
        return d == null ? new ResultadoDoGit.Falhou<>(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "commit desconhecido")) : new ResultadoDoGit.Ok<>(d);
    }

    @Override
    public ResultadoDoGit<DiffDoArquivo> diffDoCommit(String hash, ArquivoDoCommit arquivo) {
        String chave = hash + ":" + arquivo.caminho();
        chamadas.add("diff:" + chave);
        return diffs.apply(chave);
    }

    @Override
    public ResultadoDoGit<List<Branch>> branches() {
        chamadas.add("branches");
        return new ResultadoDoGit.Ok<>(branches);
    }

    @Override
    public ResultadoDoGit<StatusDoGit> status() {
        chamadas.add("status");
        return new ResultadoDoGit.Ok<>(status);
    }

    @Override
    public ResultadoDoGit<DiffDoArquivo> diffPendente(Pendencia pendencia, boolean noIndice) {
        String chave = pendencia.caminho() + ":" + noIndice;
        chamadas.add("pendente:" + chave);
        return diffs.apply("pendente:" + chave);
    }
}
