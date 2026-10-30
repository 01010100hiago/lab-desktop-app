package br.com.labdesktop.git;

import br.com.labdesktop.git.ExecutorDeGit.Saida;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Procura repositórios Git em SUBPASTAS de um projeto cuja pasta principal não tem {@code .git} (o repositório mora em {@code <projeto>/app/.git}, por exemplo). É SÓ LEITURA, e nunca executa
 * nada de dentro do que acha: num servidor por SSH é UM comando fixo de {@code find} (sem texto do usuário; a pasta já passou por {@link ValidadorDeGit#exigirPasta}); neste computador é
 * {@code java.nio}, sem seguir link e sem programa externo (também porque o Windows não tem {@code find}).
 *
 * <p>As regras valem igual nos dois caminhos: até 3 níveis (o {@code .git} fica a no máximo 3 pastas da raiz: {@code app/.git}, {@code servicos/api/.git}), nunca dentro de {@code node_modules},
 * {@code vendor}, {@code .cache}, {@code .venv}, {@code dist} e {@code build}, nunca através de link simbólico (nem o próprio {@code .git} sendo um link), no máximo {@value #LIMITE} achados, e
 * cada caminho achado passa por {@link ValidadorDeGit#caminhoRelativoValido} e é conferido como subpasta de verdade (sem {@code ..}, sem controle, dentro da raiz). A saída do {@code find} é
 * tratada como TEXTO.
 */
public final class RepositoriosAbaixo {

    /** Quantos repositórios a lista guarda (o resto vira "e outros"). */
    public static final int LIMITE = 20;
    /** Quantos níveis abaixo da raiz o {@code .git} pode estar. */
    public static final int PROFUNDIDADE = 3;
    /** As pastas onde NÃO se procura (dependências e saídas de build costumam ter clones dentro). */
    public static final List<String> IGNORADAS = List.of("node_modules", "vendor", ".cache", ".venv", "dist", "build");

    private static final Duration PRAZO = Duration.ofSeconds(30);
    /** Quantos achados o caminho local junta antes de parar (a lista final corta em {@link #LIMITE}). */
    private static final int TETO_DA_BUSCA = 200;

    private RepositoriosAbaixo() { }

    /** O que a busca achou. */
    public record Achados(List<String> caminhos, boolean cortado) {

        public Achados {
            caminhos = List.copyOf(caminhos);
        }

        public static final Achados NENHUM = new Achados(List.of(), false);

        public boolean vazio() {
            return caminhos.isEmpty();
        }
    }

    /** Quem procura: bloqueia (rede ou disco), então só roda fora da EDT. */
    @FunctionalInterface
    public interface Buscador {

        /**
         * @param raiz a pasta do projeto como o SISTEMA a entende
         * @throws ErroDoGit a busca não deu certo (conexão, prazo, comando ausente): quem chama NÃO pode concluir "não há repositório"
         */
        Achados buscar(String raiz) throws ErroDoGit, InterruptedException;
    }

    /** Num servidor por SSH: o {@code find} fixo, pelo executor do ambiente do projeto (só leitura). */
    public static Buscador porFind(ExecutorDeGit executor) {
        return raiz -> {
            Saida s = executor.rodar(ComandosDeGit.repositoriosAbaixo(raiz), PRAZO);
            // find sai com 1 quando alguma subpasta não pôde ser lida (volume de outro usuário, por exemplo) e mesmo assim lista o resto; qualquer outro código é falha
            if (s.status() != 0 && s.status() != 1) {
                throw new ErroDoGit(ClassificadorDeErrosDoGit.classificar(s.status(), s.stderr(), raiz));
            }
            return interpretar(raiz, s.texto());
        };
    }

    /** Neste computador: {@code java.nio}, sem seguir link e sem rodar programa. */
    public static Buscador local() {
        return raiz -> buscarNoDisco(Path.of(raiz));
    }

    // ---------------------------------------------------------------------------------------------
    // O texto do find
    // ---------------------------------------------------------------------------------------------

    /**
     * Transforma a saída do {@code find -print0} (caminhos terminados em NUL) nos repositórios achados. Só vale o que é {@code <raiz>/<subpasta>/.git} de verdade: o que não começa pela raiz, tem
     * {@code ..}, controle, mais de {@value #PROFUNDIDADE} níveis ou uma pasta ignorada é descartado em silêncio.
     */
    public static Achados interpretar(String raiz, String saida) {
        String base = semBarraNoFim(raiz) + "/";
        TreeSet<String> achados = new TreeSet<>();
        for (String t : saida.split("\0")) {
            if (!t.startsWith(base)) {
                continue;
            }
            String resto = t.substring(base.length());
            if (!resto.endsWith("/.git")) {
                continue;
            }
            String rel = resto.substring(0, resto.length() - "/.git".length());
            if (subpastaAceita(rel)) {
                achados.add(rel);
            }
        }
        return cortar(achados);
    }

    /** O caminho (relativo à raiz, sem barra no fim) é uma subpasta que a busca aceita? */
    public static boolean subpastaAceita(String rel) {
        if (rel == null || !ValidadorDeGit.caminhoRelativoValido(rel)) {
            return false;
        }
        String[] partes = rel.split("/");
        if (partes.length > PROFUNDIDADE - 1) {
            return false;
        }
        for (String p : partes) {
            if (IGNORADAS.contains(p) || p.equals(".git")) {
                return false;
            }
        }
        return true;
    }

    private static Achados cortar(TreeSet<String> achados) {
        List<String> todos = new ArrayList<>(achados);
        boolean cortado = todos.size() > LIMITE;
        return new Achados(cortado ? todos.subList(0, LIMITE) : todos, cortado);
    }

    private static String semBarraNoFim(String raiz) {
        String r = raiz;
        while (r.length() > 1 && (r.endsWith("/") || r.endsWith("\\"))) {
            r = r.substring(0, r.length() - 1);
        }
        return r.replace('\\', '/');
    }

    // ---------------------------------------------------------------------------------------------
    // O disco deste computador
    // ---------------------------------------------------------------------------------------------

    static Achados buscarNoDisco(Path raiz) {
        TreeSet<String> achados = new TreeSet<>();
        if (Files.isDirectory(raiz)) {
            descer(raiz, raiz, 0, achados);
        }
        return cortar(achados);
    }

    /** {@code dir} está a {@code nivel} pastas da raiz. O {@code .git} dele fica a {@code nivel + 1}: só vale se couber nos {@value #PROFUNDIDADE} níveis. */
    private static void descer(Path raiz, Path dir, int nivel, TreeSet<String> achados) {
        if (achados.size() >= TETO_DA_BUSCA) {
            return;
        }
        if (nivel >= 1) {
            Path g = dir.resolve(".git");
            if (!Files.isSymbolicLink(g) && Files.exists(g, LinkOption.NOFOLLOW_LINKS)) {
                String rel = raiz.relativize(dir).toString().replace('\\', '/');
                if (subpastaAceita(rel)) {
                    achados.add(rel);
                }
            }
        }
        if (nivel + 1 >= PROFUNDIDADE) {
            return; // os filhos teriam o .git a mais de 3 níveis
        }
        List<Path> filhos = new ArrayList<>();
        try (DirectoryStream<Path> fluxo = Files.newDirectoryStream(dir)) {
            for (Path f : fluxo) {
                String nome = f.getFileName().toString();
                if (IGNORADAS.contains(nome) || nome.equals(".git") || Files.isSymbolicLink(f) || !Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                filhos.add(f);
            }
        } catch (IOException | RuntimeException e) {
            return; // pasta sem permissão ou que sumiu: segue sem ela (o find faz o mesmo)
        }
        filhos.sort(null);
        for (Path f : filhos) {
            descer(raiz, f, nivel + 1, achados);
        }
    }
}
