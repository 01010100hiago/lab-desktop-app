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

    /** Quantos repositórios de subpasta têm o branch e o último commit lidos para a lista (o resto aparece só com o nome). */
    private static final int LEITURAS_LEVES = 6;

    private final ExecutorDeGit executor;
    private final String pasta;
    private final RepositoriosAbaixo.Buscador buscador;
    /** O executor com os atributos neutros (Git 2.40 ou mais novo), quando já se sabe; {@code null} num git mais antigo. */
    private volatile ExecutorDeGit neutro;
    private volatile boolean versaoLida;

    /**
     * @param executor o executor do ambiente do projeto
     * @param pastaDoProjeto a pasta do projeto como o SISTEMA a entende (já convertida no computador local)
     */
    public LeitorDeGitPorExecutor(ExecutorDeArgumentos executor, String pastaDoProjeto) {
        this(executor, pastaDoProjeto, false);
    }

    /**
     * @param local {@code true} em "Este computador": a procura de repositórios em subpastas é feita pelo disco ({@code java.nio}, sem {@code find} externo e sem seguir link); senão é o
     *              {@code find} fixo pelo executor (SSH)
     */
    public LeitorDeGitPorExecutor(ExecutorDeArgumentos executor, String pastaDoProjeto, boolean local) {
        this.executor = ExecutorDeGit.soDeLeitura(executor);
        this.pasta = ValidadorDeGit.exigirPasta(pastaDoProjeto);
        this.buscador = local ? RepositoriosAbaixo.local() : RepositoriosAbaixo.porFind(this.executor);
    }

    /** Como {@link #LeitorDeGitPorExecutor(ExecutorDeArgumentos, String)}, convertendo a pasta do app para a do sistema. */
    public static LeitorDeGitPorExecutor de(ExecutorDeArgumentos executor, UnaryOperator<String> paraOSistema, String pastaNoApp) {
        return new LeitorDeGitPorExecutor(executor, paraOSistema.apply(pastaNoApp));
    }

    /** Como {@link #de(ExecutorDeArgumentos, UnaryOperator, String)}, dizendo se é "Este computador" (a procura em subpastas é pelo disco). */
    public static LeitorDeGitPorExecutor de(ExecutorDeArgumentos executor, UnaryOperator<String> paraOSistema, String pastaNoApp, boolean local) {
        return new LeitorDeGitPorExecutor(executor, paraOSistema.apply(pastaNoApp), local);
    }

    // ---------------------------------------------------------------------------------------------
    // Um repositório não faz o app rodar um programa dele
    // ---------------------------------------------------------------------------------------------

    /**
     * Descobre UMA vez (por leitor) se o git tem {@code --attr-source} (2.40 ou mais novo): se tem, guarda o executor que a põe em toda leitura, com o hash da árvore vazia DESTE repositório (SHA-1 ou
     * SHA-256); se não tem (2.28 a 2.39), {@link #paraLerOConteudo} confere a configuração antes de cada leitura que poderia rodar um filtro.
     */
    private synchronized void descobrirOsAtributos() throws InterruptedException {
        if (versaoLida) {
            return;
        }
        try {
            Saida v = executor.rodar(ComandosDeGit.versao(), PRAZO);
            if (!v.ok()) {
                return; // sem versão legível: trata como antigo (confere a configuração) e tenta de novo na próxima
            }
            int[] n = ClassificadorDeErrosDoGit.versao(v.texto());
            if (n.length >= 2 && (n[0] > 2 || n[0] == 2 && n[1] >= 40)) {
                Saida h = executor.rodar(ComandosDeGit.hashDaArvoreVazia(pasta), PRAZO);
                String hash = h.ok() ? h.texto().strip() : "";
                neutro = ValidadorDeGit.hashInteiro(hash) ? executor.comAtributosNeutros(hash) : null;
            }
            versaoLida = true;
        } catch (ErroDoGit e) {
            // git ausente ou conexão fora: a leitura de verdade diz o erro; aqui só não decide nada
        }
    }

    /** O executor para ler o que passa pelo filtro do git (status e diff do que não foi commitado): com os atributos neutros, ou, num git antigo, depois de conferir que a configuração não executa nada. */
    private ExecutorDeGit paraLerOConteudo() throws ErroDoGit, InterruptedException {
        descobrirOsAtributos();
        ExecutorDeGit n = neutro;
        if (n != null) {
            return n;
        }
        Saida c = executor.rodar(ComandosDeGit.configuracaoQueExecuta(pasta), PRAZO);
        if (c.status() == 0 && !c.texto().isBlank()) {
            List<String> chaves = java.util.Arrays.stream(c.texto().split("\n")).map(String::strip).filter(x -> !x.isEmpty()).limit(5)
                .map(x -> br.com.labdesktop.seguranca.TextoSeguro.truncar(br.com.labdesktop.seguranca.TextoSeguro.limparLinha(x), 60)).toList();
            throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.CONFIGURACAO_QUE_EXECUTA, "Este repositório tem configuração que executa programas (" + String.join(", ", chaves)
                + "); o app não lê o Git dele por segurança. Confira o arquivo .git/config da pasta, no Terminal."));
        }
        if (c.status() > 1) {
            throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(c.status(), c.stderr(), pasta));
        }
        return executor;
    }

    /** O executor para as leituras que nunca passam por um filtro (log, show, branches): o neutro se já se sabe, senão o comum. */
    private ExecutorDeGit paraOHistorico() throws InterruptedException {
        descobrirOsAtributos();
        ExecutorDeGit n = neutro;
        return n != null ? n : executor;
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
                    case NAO_E_REPOSITORIO -> procurarEmSubpastas();
                    case GIT_NAO_INSTALADO -> ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO, f);
                    case PROPRIEDADE_DUVIDOSA -> ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA, f);
                    default -> ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, f);
                });
            }
            String prefixo = repo.texto().strip();
            if (!prefixo.isEmpty()) {
                return new ResultadoDoGit.Ok<>(new ResumoDoRepositorio(ResumoDoRepositorio.Estado.DENTRO_DE_OUTRO, null, false, null, 0, null, prefixo));
            }
            Saida st = paraLerOConteudo().rodar(ComandosDeGit.status(pasta), PRAZO);
            if (!st.ok()) {
                return new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, ClassificadorDeErrosDoGit.classificar(st.status(), st.stderr(), pasta)));
            }
            StatusDoGit status = InterpretadorDoStatus.interpretar(st.texto());
            Commit ultimo = null;
            if (!status.semCommits()) {
                String nonce = InterpretadorDoLog.novoNonce();
                Saida lg = paraOHistorico().rodar(ComandosDeGit.ultimoCommit(pasta, nonce), PRAZO);
                if (lg.ok()) {
                    List<Commit> l = InterpretadorDoLog.interpretar(lg.texto(), nonce);
                    ultimo = l.isEmpty() ? null : l.get(0);
                }
            }
            return new ResultadoDoGit.Ok<>(new ResumoDoRepositorio(ResumoDoRepositorio.Estado.COM_GIT, status.branch(), status.cabecaSolta(), ultimo, status.alteracoes(), null, null));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(
                e.falha().tipo() == FalhaDoGit.Tipo.GIT_NAO_INSTALADO ? ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO : ResumoDoRepositorio.Estado.ERRO, e.falha()));
        }
    }

    /**
     * A pasta principal não é repositório: antes de dizer "sem Git" procura repositórios em SUBPASTAS (o código de muitos projetos mora em {@code <projeto>/app/.git}). Se a procura falha (prazo,
     * conexão), NÃO diz "sem Git": diz que não conseguiu procurar, porque oferecer criar um repositório por cima de um que existe é justamente o erro que isto evita.
     */
    private ResumoDoRepositorio procurarEmSubpastas() throws InterruptedException {
        RepositoriosAbaixo.Achados achados;
        try {
            achados = buscador.buscar(pasta);
        } catch (ErroDoGit e) {
            FalhaDoGit f = e.falha();
            return ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, new FalhaDoGit(f.tipo(), "Esta pasta não tem Git, mas não consegui procurar repositórios nas subpastas: " + f.frase(), f.comandoParaCopiar()));
        }
        if (achados.vazio()) {
            return ResumoDoRepositorio.semGit();
        }
        List<RepositorioAbaixo> lista = new java.util.ArrayList<>();
        int n = 0;
        for (String caminho : achados.caminhos()) {
            lista.add(n++ < LEITURAS_LEVES && achados.caminhos().size() > 1 ? leituraLeve(caminho) : RepositorioAbaixo.semLeitura(caminho));
        }
        return ResumoDoRepositorio.repositoriosAbaixo(lista, achados.cortado());
    }

    /** O branch e o último commit de um repositório de subpasta, para a lista de escolha. Qualquer falha (dono diferente, repositório vazio, prazo) só deixa o item sem esses dados. */
    private RepositorioAbaixo leituraLeve(String caminho) throws InterruptedException {
        String sub = ValidadorDeGit.juntar(pasta, caminho);
        if (!ValidadorDeGit.pastaValida(sub)) {
            return RepositorioAbaixo.semLeitura(caminho);
        }
        try {
            String branch = null;
            Saida r = executor.rodar(ComandosDeGit.ramoAtual(sub), PRAZO);
            if (r.ok()) {
                String b = br.com.labdesktop.seguranca.TextoSeguro.limparLinha(r.texto());
                branch = b.isEmpty() ? null : br.com.labdesktop.seguranca.TextoSeguro.truncar(b, 80);
            }
            Commit ultimo = null;
            String nonce = InterpretadorDoLog.novoNonce();
            Saida lg = executor.rodar(ComandosDeGit.ultimoCommit(sub, nonce), PRAZO);
            if (lg.ok()) {
                List<Commit> l = InterpretadorDoLog.interpretar(lg.texto(), nonce);
                ultimo = l.isEmpty() ? null : l.get(0);
            }
            return new RepositorioAbaixo(caminho, branch, ultimo, true);
        } catch (ErroDoGit e) {
            return RepositorioAbaixo.semLeitura(caminho);
        }
    }

    @Override
    public ResultadoDoGit<PaginaDeCommits> commits(String branch, int pular, int quantos) throws InterruptedException {
        try {
            String nonce = InterpretadorDoLog.novoNonce();
            Saida s = paraOHistorico().rodar(ComandosDeGit.commits(pasta, branch, pular, quantos + 1, nonce), PRAZO);
            if (!s.ok()) {
                FalhaDoGit f = ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta);
                if (f.tipo() == FalhaDoGit.Tipo.SEM_COMMITS) {
                    return new ResultadoDoGit.Ok<>(new PaginaDeCommits(List.of(), false));
                }
                return new ResultadoDoGit.Falhou<>(f);
            }
            List<Commit> todos = InterpretadorDoLog.interpretar(s.texto(), nonce); // nenhum commit é descartado: "há mais" vale sobre o que o git devolveu
            boolean mais = todos.size() > quantos;
            return new ResultadoDoGit.Ok<>(new PaginaDeCommits(mais ? todos.subList(0, quantos) : todos, mais));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    @Override
    public ResultadoDoGit<DetalheDoCommit> detalhe(String hash) throws InterruptedException {
        try {
            Saida c = paraOHistorico().rodar(ComandosDeGit.cabecalhoDoCommit(pasta, hash), PRAZO);
            if (!c.ok()) {
                return falhou(c);
            }
            InterpretadorDoLog.Cabecalho cab = InterpretadorDoLog.interpretarCabecalho(c.texto());
            if (cab == null) {
                return new ResultadoDoGit.Falhou<>(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "Não consegui ler este commit."));
            }
            Saida a = paraOHistorico().rodar(ComandosDeGit.arquivosDoCommit(pasta, hash), PRAZO);
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
            Saida s = paraOHistorico().rodar(ComandosDeGit.diffDoCommit(pasta, hash, arquivo.caminho(), arquivo.antigo()), PRAZO);
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
            Saida s = paraOHistorico().rodar(ComandosDeGit.branches(pasta), PRAZO);
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
            Saida s = paraLerOConteudo().rodar(ComandosDeGit.status(pasta), PRAZO);
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
            ExecutorDeGit seguro = paraLerOConteudo();
            if (p.tipo() == Pendencia.Tipo.NAO_RASTREADO && p.caminho().endsWith("/")) {
                return new ResultadoDoGit.Ok<>(pastaNova(seguro, p.caminho()));
            }
            ComandoGit c = p.tipo() == Pendencia.Tipo.NAO_RASTREADO
                ? ComandosDeGit.diffDeNaoRastreado(pasta, p.caminho())
                : ComandosDeGit.diffPendente(pasta, p.caminho(), p.antigo(), noIndice);
            Saida s = seguro.rodar(c, PRAZO);
            if (s.status() != 0 && !(p.tipo() == Pendencia.Tipo.NAO_RASTREADO && s.status() == 1)) {
                return falhou(s);
            }
            return new ResultadoDoGit.Ok<>(InterpretadorDeDiff.interpretar(s.stdout(), s.truncado()));
        } catch (ErroDoGit e) {
            return new ResultadoDoGit.Falhou<>(e.falha());
        }
    }

    /** O que mostrar de uma PASTA nova (o {@code status} lista {@code dir/}): "pasta nova: N arquivos" e a lista (até 500, sem seguir link); um repositório de dentro é dito como tal. */
    private DiffDoArquivo pastaNova(ExecutorDeGit seguro, String caminho) throws ErroDoGit, InterruptedException {
        Saida s = seguro.rodar(ComandosDeGit.arquivosDeUmaPastaNova(pasta, caminho), PRAZO);
        if (!s.ok()) {
            throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta));
        }
        List<String> itens = java.util.Arrays.stream(s.texto().split("\0")).filter(x -> !x.isEmpty()).toList();
        List<LinhaDeDiff> linhas = new java.util.ArrayList<>();
        boolean cortado = s.truncado() || itens.size() > TETO_DE_ARQUIVOS;
        if (itens.size() == 1 && itens.get(0).endsWith("/")) {
            linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "pasta nova com um repositório Git dentro: o app não olha lá dentro"));
        } else {
            linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, "pasta nova: " + itens.size() + (itens.size() == 1 ? " arquivo" : " arquivos") + (cortado ? " ou mais" : "")));
            for (String a : itens.stream().limit(TETO_DE_ARQUIVOS).toList()) {
                linhas.add(new LinhaDeDiff(LinhaDeDiff.Tipo.INFORMACAO, br.com.labdesktop.seguranca.TextoSeguro.limparLinha(a)));
            }
        }
        return new DiffDoArquivo(linhas, false, cortado, false, null, false, 0, 0, false);
    }

    private <T> ResultadoDoGit<T> falhou(Saida s) {
        return new ResultadoDoGit.Falhou<>(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), pasta));
    }
}
