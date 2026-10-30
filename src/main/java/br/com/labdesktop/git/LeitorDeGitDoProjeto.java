package br.com.labdesktop.git;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * O Git de UM projeto como a página o vê, mesmo quando o repositório não está na pasta principal. Embrulha o leitor da pasta principal ({@code raiz}) e um jeito de montar o leitor de uma
 * subpasta ({@code deSubpasta}): quando a pasta principal não tem {@code .git} mas há repositório em {@code app/}, todas as leituras (commits, branches, alterações, diff) passam a ir para
 * essa subpasta, exatamente como na raiz.
 *
 * <p>Quem decide qual subpasta ler: a escolha desta sessão; senão a que o app lembra para este projeto neste servidor ({@link Memoria}); senão, se há UM só repositório, ele (com um rótulo claro e
 * um jeito de voltar à lista); senão ninguém, e {@link #resumo()} devolve {@link ResumoDoRepositorio.Estado#REPOSITORIOS_ABAIXO} para a tela oferecer a lista. Só leitura. Bloqueia (rede ou
 * processo): thread de fundo, nunca a EDT. O estado (escolha e lista) é guardado para a Visão geral e a aba Git mostrarem a mesma resposta.
 */
public final class LeitorDeGitDoProjeto implements LeitorDeGit {

    /** Onde a escolha fica guardada entre uma abertura e outra do app (por projeto e servidor); {@code null} em {@link #lembrar} esquece. */
    public interface Memoria {
        String lembrada();

        void lembrar(String subpasta);

        Memoria NENHUMA = new Memoria() {
            @Override
            public String lembrada() {
                return null;
            }

            @Override
            public void lembrar(String subpasta) { }
        };
    }

    private final Supplier<LeitorDeGit> raiz;
    private final Function<String, LeitorDeGit> deSubpasta;
    private final Memoria memoria;

    private volatile String escolhida;
    private volatile List<RepositorioAbaixo> abaixo = List.of();
    private volatile boolean abaixoCortado;
    /** O usuário pediu para ver a lista nesta sessão: com um só repositório, não reabre sozinho nele. */
    private volatile boolean listaPedida;

    /**
     * @param raiz       monta o leitor da pasta principal do projeto (só MONTA, nada roda ao montar; chamado a cada leitura)
     * @param deSubpasta monta o leitor de uma subpasta (recebe o caminho relativo validado); só MONTA
     * @param memoria    onde a escolha fica guardada
     */
    public LeitorDeGitDoProjeto(Supplier<LeitorDeGit> raiz, Function<String, LeitorDeGit> deSubpasta, Memoria memoria) {
        this.raiz = raiz;
        this.deSubpasta = deSubpasta;
        this.memoria = memoria == null ? Memoria.NENHUMA : memoria;
    }

    /** O leitor que vale agora: o da subpasta escolhida, ou o da pasta principal. */
    private LeitorDeGit emUso() {
        String sub = escolhida;
        return sub == null ? raiz.get() : deSubpasta.apply(sub);
    }

    // ---------------------------------------------------------------------------------------------
    // A escolha
    // ---------------------------------------------------------------------------------------------

    /** A subpasta lida agora ({@code app}), ou {@code null} quando é a pasta principal. */
    public String escolhida() {
        return escolhida;
    }

    /** Os repositórios de subpasta que a última leitura achou. */
    public List<RepositorioAbaixo> repositoriosAbaixo() {
        return abaixo;
    }

    /** Escolhe o repositório de uma subpasta da lista (e o lembra). A tela lê de novo depois. Caminho que não está na lista é ignorado. */
    public boolean escolher(String caminho) {
        if (caminho == null || abaixo.stream().noneMatch(a -> a.caminho().equals(caminho))) {
            return false;
        }
        escolhida = caminho;
        listaPedida = false;
        memoria.lembrar(caminho);
        return true;
    }

    /** Volta à lista: esquece a escolha (também a lembrada) e, com um só repositório, não reabre sozinho nele até a próxima abertura do app. */
    public void voltarALista() {
        escolhida = null;
        listaPedida = true;
        memoria.lembrar(null);
    }

    // ---------------------------------------------------------------------------------------------
    // Ler
    // ---------------------------------------------------------------------------------------------

    @Override
    public ResultadoDoGit<String> versao() throws InterruptedException {
        return raiz.get().versao();
    }

    @Override
    public ResultadoDoGit<ResumoDoRepositorio> resumo() throws InterruptedException {
        ResultadoDoGit<ResumoDoRepositorio> r = raiz.get().resumo();
        ResumoDoRepositorio base = r.valorOuNulo();
        if (base == null || base.estado() != ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO) {
            escolhida = null; // a pasta principal é (ou voltou a ser) o que vale
            abaixo = List.of();
            abaixoCortado = false;
            return r;
        }
        abaixo = base.abaixo();
        abaixoCortado = base.abaixoCortado();
        String alvo = decidir(base.abaixo());
        if (alvo == null) {
            escolhida = null;
            return r;
        }
        escolhida = alvo;
        ResultadoDoGit<ResumoDoRepositorio> lido = deSubpasta.apply(alvo).resumo();
        ResumoDoRepositorio rs = lido.ok() ? lido.valorOuNulo() : ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, lido.falhaOuNula());
        return new ResultadoDoGit.Ok<>(rs.naSubpasta(alvo, base.abaixo(), base.abaixoCortado()));
    }

    /** Qual subpasta ler: a desta sessão, a lembrada, o único repositório; {@code null} quando o usuário precisa escolher. */
    private String decidir(List<RepositorioAbaixo> lista) {
        String atual = escolhida;
        if (atual != null && existe(lista, atual)) {
            return atual;
        }
        if (listaPedida) {
            return null;
        }
        String lembrada = memoria.lembrada();
        if (lembrada != null && existe(lista, lembrada)) {
            return lembrada;
        }
        return lista.size() == 1 ? lista.get(0).caminho() : null;
    }

    private static boolean existe(List<RepositorioAbaixo> lista, String caminho) {
        return lista.stream().anyMatch(a -> a.caminho().equals(caminho));
    }

    @Override
    public ResultadoDoGit<PaginaDeCommits> commits(String branch, int pular, int quantos) throws InterruptedException {
        return emUso().commits(branch, pular, quantos);
    }

    @Override
    public ResultadoDoGit<DetalheDoCommit> detalhe(String hash) throws InterruptedException {
        return emUso().detalhe(hash);
    }

    @Override
    public ResultadoDoGit<DiffDoArquivo> diffDoCommit(String hash, ArquivoDoCommit arquivo) throws InterruptedException {
        return emUso().diffDoCommit(hash, arquivo);
    }

    @Override
    public ResultadoDoGit<List<Branch>> branches() throws InterruptedException {
        return emUso().branches();
    }

    @Override
    public ResultadoDoGit<StatusDoGit> status() throws InterruptedException {
        return emUso().status();
    }

    @Override
    public ResultadoDoGit<DiffDoArquivo> diffPendente(Pendencia pendencia, boolean noIndice) throws InterruptedException {
        return emUso().diffPendente(pendencia, noIndice);
    }

    /** Houve mais repositórios que o limite da lista. */
    public boolean listaCortada() {
        return abaixoCortado;
    }
}
