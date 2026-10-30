package br.com.labdesktop.git;

import br.com.labdesktop.git.ComandoGit.Natureza;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Roda um {@link ComandoGit} pelo executor do ambiente do projeto (o servidor por SSH, que já passa pelo túnel do Session Manager ou da Cloudflare, ou este computador por processo): o
 * mesmo caminho do resto da página, nenhum caminho novo de rede. Cada instância só deixa passar certas {@link Natureza}s, e confere pela FORMA do comando
 * ({@link ComandosDeGit#naturezaPelaForma}), não pelo que o objeto diz de si: o executor da leitura recusa qualquer escrita.
 *
 * <p>Bloqueia (rede ou disco): thread de fundo, nunca a EDT.
 */
public final class ExecutorDeGit {

    /** O que um comando devolveu. */
    public record Saida(int status, byte[] stdout, String stderr, boolean truncado) {

        public String texto() {
            return DecodificadorDeTexto.decodificar(stdout).texto();
        }

        public boolean ok() {
            return status == 0;
        }
    }

    private final ExecutorDeArgumentos executor;
    private final Set<Natureza> permitidas;
    private final String arvoreVazia;

    private ExecutorDeGit(ExecutorDeArgumentos executor, Set<Natureza> permitidas, String arvoreVazia) {
        this.executor = executor;
        this.permitidas = permitidas;
        this.arvoreVazia = arvoreVazia;
    }

    /**
     * O mesmo executor com {@code --attr-source=<árvore vazia>} em TODA leitura de um repositório (Git 2.40 ou mais novo): o {@code .gitattributes} do projeto deixa de valer, então um {@code filter=} dele
     * (que o {@code .git/config} ligaria a um programa) nunca roda no {@code status} nem no {@code diff}. A opção entra logo depois do {@code -C <pasta>}; as posições de texto livre depois dela andam uma casa.
     *
     * @param arvoreVazia o hash da árvore vazia do repositório (40 ou 64 letras hexadecimais)
     */
    public ExecutorDeGit comAtributosNeutros(String arvoreVazia) {
        if (arvoreVazia == null || !ValidadorDeGit.hashInteiro(arvoreVazia)) {
            throw new IllegalArgumentException("Hash da árvore vazia inválido.");
        }
        return new ExecutorDeGit(executor, permitidas, arvoreVazia);
    }

    /** O comando com a opção dos atributos neutros, quando este executor a tem e o comando é uma leitura de repositório com {@code -C}. */
    private ComandoGit neutralizado(ComandoGit c) {
        List<String> a = c.argumentos();
        int i = a.indexOf("-C");
        if (arvoreVazia == null || c.natureza() != Natureza.LEITURA || i < 0 || a.stream().anyMatch(x -> x.startsWith("--attr-source=")) || a.stream().anyMatch(x -> x.startsWith("--git-dir="))
            || a.size() < i + 3 || a.contains("hash-object")) {
            return c;
        }
        List<String> novos = new ArrayList<>(a);
        novos.add(i + 2, "--attr-source=" + arvoreVazia);
        Set<Integer> livres = new HashSet<>();
        for (int p : c.livres()) {
            livres.add(p >= i + 2 ? p + 1 : p);
        }
        return new ComandoGit(c.natureza(), novos, livres, c.ambiente(), c.entrada());
    }

    /** Só leitura: tudo o que a aba Git e a linha "Código" fazem. */
    public static ExecutorDeGit soDeLeitura(ExecutorDeArgumentos executor) {
        return new ExecutorDeGit(executor, EnumSet.of(Natureza.LEITURA), null);
    }

    /** Leitura e a área temporária fora do projeto: a conferência de "Criar repositório". */
    public static ExecutorDeGit paraConferir(ExecutorDeArgumentos executor) {
        return new ExecutorDeGit(executor, EnumSet.of(Natureza.LEITURA, Natureza.TEMPORARIO), null);
    }

    /** Tudo: só o criador de repositório usa, depois do clique. */
    public static ExecutorDeGit completo(ExecutorDeArgumentos executor) {
        return new ExecutorDeGit(executor, EnumSet.allOf(Natureza.class), null);
    }

    /**
     * Roda o comando. Um código de saída diferente de zero NÃO é exceção (o chamador decide: {@code git diff --no-index} sai com 1 quando há diferença).
     *
     * @throws ErroDoGit o programa não existe, o prazo estourou, a conexão caiu ou o comando é de uma natureza que este executor não aceita
     */
    public Saida rodar(ComandoGit original, Duration prazo) throws ErroDoGit, InterruptedException {
        ComandoGit c = neutralizado(original);
        Natureza pelaForma = ComandosDeGit.naturezaPelaForma(c.argumentos());
        if (!permitidas.contains(c.natureza()) || !permitidas.contains(pelaForma) || pelaForma.ordinal() > c.natureza().ordinal()) {
            throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "Este comando escreve no projeto e não é permitido aqui."));
        }
        try {
            ResultadoExec r = executor.executarComTexto(c.argumentos(), c.livres(), c.ambiente(), c.entrada(), prazo);
            return new Saida(r.status(), r.stdoutBytes(), new String(r.stderrBytes(), java.nio.charset.StandardCharsets.UTF_8), r.truncado());
        } catch (ProgramaNaoEncontradoException e) {
            throw new ErroDoGit(ClassificadorDeErrosDoGit.naoInstalado());
        } catch (ComandoDemorouException e) {
            throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.DEMOROU, "O Git demorou mais que o prazo (" + prazo.toSeconds() + " s) e o app parou de esperar."));
        } catch (IllegalArgumentException e) {
            throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO,
                "O app recusou montar este comando por segurança (algum nome tem caractere que não pode ir numa linha de comando de servidor)."));
        } catch (IOException e) {
            throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.SEM_CONEXAO, "Não consegui falar com o servidor para rodar o Git agora. Confira a conexão e tente de novo."));
        }
    }
}
