package br.com.labdesktop.git;

import br.com.labdesktop.git.ExecutorDeGit.Saida;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * O {@link LeitorDeGit} de verdade: roda os comandos de {@link ComandosDeGit} pelo executor do ambiente do projeto (SSH, que já passa pelos túneis do Session Manager e da Cloudflare, ou processo local) e
 * transforma a saída com os interpretadores. Só leitura: o {@link ExecutorDeGit} que ele usa recusa qualquer escrita pela forma do comando.
 */
public final class LeitorDeGitPorExecutor implements LeitorDeGit {

    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final int TETO_DE_ARQUIVOS = 500;

    private final ExecutorDeGit executor;
    private final String pasta;

    /**
     * @param executor o executor do ambiente do projeto
     * @param pastaDoProjeto a pasta do projeto como o SISTEMA a entende (já convertida no computador local)
     */
    public LeitorDeGitPorExecutor(ExecutorDeArgumentos executor, String pastaDoProjeto) {
        this.executor = ExecutorDeGit.soDeLeitura(executor);
        this.pasta = ValidadorDeGit.exigirPasta(pastaDoProjeto);
    }

    /** Como {@link #LeitorDeGitPorExecutor(ExecutorDeArgumentos, String)}, convertendo a pasta do app para a do sistema. */
    public static LeitorDeGitPorExecutor de(ExecutorDeArgumentos executor, UnaryOperator<String> paraOSistema, String pastaNoApp) {
        return new LeitorDeGitPorExecutor(executor, paraOSistema.apply(pastaNoApp));
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public ResultadoDoGit<String> versao() throws InterruptedException {
        try {
            Saida s = executor.rodar(ComandosDeGit.versao(), PRAZO);
            if (s.ok()) {
                return new ResultadoDoGit.Ok<>(br.com.labdesktop.seguranca.TextoSeguro.truncar(br.com.labdesktop.seguranca.TextoSeguro.limparLinha(s.texto()), 60));
            }
            return falhou(s);
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<ResumoDoRepositorio> resumo() throws InterruptedException {
        try {
            Saida repo = executor.rodar(ComandosDeGit.ehRepositorio(pasta), PRAZO);
            if (!repo.ok()) {
                FalhaDoGit f = ClassificadorDeErrosDoGit.classificar(repo.status(), repo.stderr(), pasta);
                return new ResultadoDoGit.Ok<>(switch (f.tipo()) {
                    case NAO_E_REPOSITORIO -> ResumoDoRepositorio.semGit();
                    case GIT_NAO_INSTALADO -> ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO, f);
                    case PROPRIEDADE_DUVIDOSA -> ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA, f);
                    default -> ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, f);
                });
            }
            String prefixo = repo.texto().strip();
            if (!prefixo.isEmpty()) {
                return new ResultadoDoGit.Ok<>(new ResumoDoRepositorio(ResumoDoRepositorio.Estado.DENTRO_DE_OUTRO, null, false, null, 0, null, prefixo));
            }
            Saida st = executor.rodar(ComandosDeGit.status(pasta), PRAZO);
            if (!st.ok()) {
                return new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, ClassificadorDeErrosDoGit.classificar(st.status(), st.stderr(), pasta)));
            }
            StatusDoGit status = InterpretadorDoStatus.interpretar(st.texto());
            Commit ultimo = null;
            if (!status.semCommits()) {
                Saida lg = executor.rodar(ComandosDeGit.ultimoCommit(pasta), PRAZO);
                if (lg.ok()) {
                    List<Commit> l = InterpretadorDoLog.interpretar(lg.texto());
                    ultimo = l.isEmpty() ? null : l.get(0);
                }
            }
            return new ResultadoDoGit.Ok<>(new ResumoDoRepositorio(ResumoDoRepositorio.Estado.COM_GIT, status.branch(), status.cabecaSolta(), ultimo, status.alteracoes(), null, null));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(
                e.falha().tipo() == FalhaDoGit.Tipo.GIT_NAO_INSTALADO ? ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO : ResumoDoRepositorio.Estado.ERRO, e.falha()));
        }
    }

    @Override
    public ResultadoDoGit<PaginaDeCommits> commits(String branch, int pular, int quantos) throws InterruptedException {
        try {
            Saida s = executor.rodar(ComandosDeGit.commits(pasta, branch, pular, quantos + 1), PRAZO);
            if (!s.ok()) {
                FalhaDoGit f = ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta);
                if (f.tipo() == FalhaDoGit.Tipo.SEM_COMMITS) {
                    return new ResultadoDoGit.Ok<>(new PaginaDeCommits(List.of(), false));
                }
                return new ResultadoDoGit.Falhou<>(f);
            }
            List<Commit> todos = InterpretadorDoLog.interpretar(s.texto());
            boolean mais = todos.size() > quantos;
            return new ResultadoDoGit.Ok<>(new PaginaDeCommits(mais ? todos.subList(0, quantos) : todos, mais));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<DetalheDoCommit> detalhe(String hash) throws InterruptedException {
        try {
            Saida c = executor.rodar(ComandosDeGit.cabecalhoDoCommit(pasta, hash), PRAZO);
            if (!c.ok()) {
                return falhou(c);
            }
            InterpretadorDoLog.Cabecalho cab = InterpretadorDoLog.interpretarCabecalho(c.texto());
            if (cab == null) {
                return new ResultadoDoGit.Falhou<>(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "Não consegui ler este commit."));
            }
            Saida a = executor.rodar(ComandosDeGit.arquivosDoCommit(pasta, hash), PRAZO);
            if (!a.ok()) {
                return falhou(a);
            }
            List<ArquivoDoCommit> arquivos = InterpretadorDoNumstat.interpretar(a.texto());
            boolean cortada = a.truncado() || arquivos.size() > TETO_DE_ARQUIVOS;
            if (arquivos.size() > TETO_DE_ARQUIVOS) {
                arquivos = arquivos.subList(0, TETO_DE_ARQUIVOS);
            }
            Commit k = cab.commit();
            Commit comArquivos = new Commit(k.hash(), k.curto(), k.autor(), k.email(), k.quando(), k.pais(), k.assunto(), arquivos.size());
            return new ResultadoDoGit.Ok<>(new DetalheDoCommit(comArquivos, cab.mensagem(), arquivos, cortada));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<DiffDoArquivo> diffDoCommit(String hash, ArquivoDoCommit arquivo) throws InterruptedException {
        if (arquivo.binario()) {
            return new ResultadoDoGit.Ok<>(new DiffDoArquivo(List.of(), true, false, false, arquivo.antigo(), false, 0, 0, false));
        }
        try {
            Saida s = executor.rodar(ComandosDeGit.diffDoCommit(pasta, hash, arquivo.caminho(), arquivo.antigo()), PRAZO);
            if (!s.ok()) {
                return falhou(s);
            }
            return new ResultadoDoGit.Ok<>(InterpretadorDeDiff.interpretar(s.stdout(), s.truncado()));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<List<Branch>> branches() throws InterruptedException {
        try {
            Saida s = executor.rodar(ComandosDeGit.branches(pasta), PRAZO);
            if (!s.ok()) {
                return falhou(s);
            }
            return new ResultadoDoGit.Ok<>(InterpretadorDeBranches.interpretar(s.texto()));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<StatusDoGit> status() throws InterruptedException {
        try {
            Saida s = executor.rodar(ComandosDeGit.status(pasta), PRAZO);
            if (!s.ok()) {
                return falhou(s);
            }
            return new ResultadoDoGit.Ok<>(InterpretadorDoStatus.interpretar(s.texto()));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<DiffDoArquivo> diffPendente(Pendencia p, boolean noIndice) throws InterruptedException {
        try {
            ComandoGit c = p.tipo() == Pendencia.Tipo.NAO_RASTREADO
                ? ComandosDeGit.diffDeNaoRastreado(pasta, p.caminho())
                : ComandosDeGit.diffPendente(pasta, p.caminho(), p.antigo(), noIndice);
            Saida s = executor.rodar(c, PRAZO);
            if (s.status() != 0 && !(p.tipo() == Pendencia.Tipo.NAO_RASTREADO && s.status() == 1)) {
                return falhou(s);
            }
            return new ResultadoDoGit.Ok<>(InterpretadorDeDiff.interpretar(s.stdout(), s.truncado()));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    private <T> ResultadoDoGit<T> falhou(Saida s) {
        return new ResultadoDoGit.Falhou<>(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta));
    }
}
