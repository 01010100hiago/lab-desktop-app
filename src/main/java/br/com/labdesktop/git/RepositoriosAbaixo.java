package br.com.labdesktop.git;

import br.com.labdesktop.git.ExecutorDeGit.Saida;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

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
    /**
     * @param caminhos  os repositórios achados, com nome que o app mostra com segurança
     * @param cortado   havia mais que {@link #LIMITE}
     * @param ilegiveis quantos repositórios existem em subpastas cujo NOME o app não mostra com segurança (controle, caractere bidi ou de formato do Unicode, barra invertida...): ficam fora da
     *                  lista, mas existem, então contam para "há repositório abaixo" e nunca viram "sem Git"
     */
    public record Achados(List<String> caminhos, boolean cortado, int ilegiveis) {

        public Achados {
            caminhos = List.copyOf(caminhos);
        }

        public Achados(List<String> caminhos, boolean cortado) {
            this(caminhos, cortado, 0);
        }

        public static final Achados NENHUM = new Achados(List.of(), false, 0);

        /** Nada achado, nem repositório de nome ilegível. */
        public boolean vazio() {
            return caminhos.isEmpty() && ilegiveis == 0;
        }

        /** Só há repositórios de nome ilegível: não há o que listar, mas há o que impedir. */
        public boolean soIlegiveis() {
            return caminhos.isEmpty() && ilegiveis > 0;
        }

        /** O que os avisos mostram: os caminhos e, no lugar de cada nome ilegível, "pasta com nome ilegível (N)". */
        public List<String> paraAvisos() {
            List<String> l = new ArrayList<>(caminhos);
            for (int i = 1; i <= ilegiveis; i++) {
                l.add(TEXTO_DO_ILEGIVEL + " (" + i + ")");
            }
            return l;
        }
    }

    /** O que se mostra no lugar do nome de uma pasta que o app não mostra com segurança. */
    public static final String TEXTO_DO_ILEGIVEL = "pasta com nome ilegível";

    /** Quem procura: bloqueia (rede ou disco), então só roda fora da EDT. */
    @FunctionalInterface
    public interface Buscador {

        /**
         * @param raiz a pasta do projeto como o SISTEMA a entende
         * @throws ErroDoGit a busca não deu certo (conexão, prazo, comando ausente): quem chama NÃO pode concluir "não há repositório"
         */
        Achados buscar(String raiz) throws ErroDoGit, InterruptedException;
    }

    /** Uma linha de erro do {@code find} que só diz que uma pasta não pôde ser lida (volume de outro usuário, arquivo no lugar de pasta). */
    private static final Pattern ERRO_DE_LEITURA_DO_FIND = Pattern.compile("^find: .*: (Permission denied|Not a directory)$");

    /**
     * Num servidor por SSH: o {@code find} fixo, pelo executor do ambiente do projeto (só leitura). Sai com 0, ou com 1 SÓ quando TODAS as linhas do erro dizem que uma pasta não pôde ser lida
     * ("Permission denied", "Not a directory"): o resto da lista vale. Qualquer outro caso (predicado que o {@code find} do servidor não conhece, BusyBox, erro de uso, código 127 de {@code find}
     * ausente) é ERRO DE BUSCA, nunca "nenhum repositório": uma saída vazia de um {@code find} que não rodou liberaria criar por cima de um {@code app/.git} que existe.
     */
    public static Buscador porFind(ExecutorDeGit executor) {
        return raiz -> {
            Saida s = executor.rodar(ComandosDeGit.repositoriosAbaixo(raiz), PRAZO);
            if (s.status() == 127) {
                throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "o comando find não existe neste servidor."));
            }
            if (s.status() != 0 && !(s.status() == 1 && soErrosDeLeitura(s.stderr()))) {
                String detalhe = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(s.stderr())), 300);
                throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "o find respondeu com erro (código " + s.status() + ")" + (detalhe.isEmpty() ? "." : ": " + detalhe)));
            }
            return interpretar(raiz, s.texto());
        };
    }

    /** O erro do {@code find} é só "não consegui ler uma pasta"? Cada linha é conferida; erro vazio NÃO vale (um {@code find} que sai com 1 sem dizer nada não é o caso conhecido). */
    static boolean soErrosDeLeitura(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return false;
        }
        for (String linha : stderr.split("\\R")) {
            if (!linha.isBlank() && !ERRO_DE_LEITURA_DO_FIND.matcher(linha.strip()).matches()) {
                return false;
            }
        }
        return true;
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
        Set<String> ilegiveis = new HashSet<>();
        for (String t : saida.split("\0")) {
            if (!t.startsWith(base)) {
                continue;
            }
            String resto = t.substring(base.length());
            if (!resto.endsWith("/.git")) {
                continue;
            }
            String rel = resto.substring(0, resto.length() - "/.git".length());
            if (!estruturaAceita(rel)) {
                continue;
            }
            if (nomeLegivel(rel)) {
                achados.add(rel);
            } else {
                ilegiveis.add(rel); // existe, mas o nome não se mostra com segurança: não some em silêncio
            }
        }
        return cortar(achados, ilegiveis.size());
    }

    /** O caminho (relativo à raiz, sem barra no fim) é uma subpasta que a busca aceita e que o app mostra com segurança? */
    public static boolean subpastaAceita(String rel) {
        return estruturaAceita(rel) && nomeLegivel(rel);
    }

    /** O lugar certo: no máximo {@value #PROFUNDIDADE} níveis do {@code .git}, nenhuma pasta ignorada, nada de {@code .} nem {@code ..} nem segmento vazio. */
    static boolean estruturaAceita(String rel) {
        if (rel == null || rel.isEmpty()) {
            return false;
        }
        String[] partes = rel.split("/", -1);
        if (partes.length > PROFUNDIDADE - 1) {
            return false;
        }
        for (String p : partes) {
            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.equals(".git") || IGNORADAS.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * O nome passa na regra de caminho do app (sem controle, sem barra invertida) E não tem caractere de formato nem separador do Unicode: um U+202E (inverte o texto que vem depois), U+200E/F,
     * U+202A-202E, U+2066-2069, U+200B..., U+2028/2029 fariam a pasta aparecer como outra coisa na lista e nos avisos.
     */
    public static boolean nomeLegivel(String rel) {
        if (rel == null || !ValidadorDeGit.caminhoRelativoValido(rel)) {
            return false;
        }
        for (int i = 0; i < rel.length(); ) {
            int c = rel.codePointAt(i);
            int tipo = Character.getType(c);
            if (tipo == Character.FORMAT || tipo == Character.LINE_SEPARATOR || tipo == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            i += Character.charCount(c);
        }
        return true;
    }

    private static Achados cortar(TreeSet<String> achados, int ilegiveis) {
        List<String> todos = new ArrayList<>(achados);
        boolean cortado = todos.size() > LIMITE;
        return new Achados(cortado ? todos.subList(0, LIMITE) : todos, cortado, ilegiveis);
    }

    /**
     * A raiz sem barra no fim, na forma que o {@code find} imprime. A barra invertida só vira barra numa raiz de WINDOWS ({@code C:\...}): numa raiz Linux ela é um caractere do nome, e trocá-la
     * faria nenhum achado começar pela raiz (e o projeto virar "sem Git").
     */
    private static String semBarraNoFim(String raiz) {
        boolean windows = raiz.length() >= 3 && Character.isLetter(raiz.charAt(0)) && raiz.charAt(1) == ':' && (raiz.charAt(2) == '\\' || raiz.charAt(2) == '/');
        String r = raiz;
        while (r.length() > 1 && (r.endsWith("/") || windows && r.endsWith("\\"))) {
            r = r.substring(0, r.length() - 1);
        }
        return windows ? r.replace('\\', '/') : r;
    }

    // ---------------------------------------------------------------------------------------------
    // O disco deste computador
    // ---------------------------------------------------------------------------------------------

    static Achados buscarNoDisco(Path raiz) {
        TreeSet<String> achados = new TreeSet<>();
        Set<String> ilegiveis = new HashSet<>();
        if (Files.isDirectory(raiz)) {
            descer(raiz, raiz, 0, achados, ilegiveis);
        }
        return cortar(achados, ilegiveis.size());
    }

    /** {@code dir} está a {@code nivel} pastas da raiz. O {@code .git} dele fica a {@code nivel + 1}: só vale se couber nos {@value #PROFUNDIDADE} níveis. */
    private static void descer(Path raiz, Path dir, int nivel, TreeSet<String> achados, Set<String> ilegiveis) {
        if (achados.size() >= TETO_DA_BUSCA) {
            return;
        }
        if (nivel >= 1) {
            Path g = dir.resolve(".git");
            if (!Files.isSymbolicLink(g) && Files.exists(g, LinkOption.NOFOLLOW_LINKS)) {
                // os nomes das pastas juntados com "/": nada de depender do separador do sistema (no Windows ele é a barra invertida; no Linux a barra invertida é um caractere do nome)
                StringBuilder junto = new StringBuilder();
                for (Path parte : raiz.relativize(dir)) {
                    junto.append(junto.length() > 0 ? "/" : "").append(parte);
                }
                String rel = junto.toString();
                if (estruturaAceita(rel)) {
                    if (nomeLegivel(rel)) {
                        achados.add(rel);
                    } else {
                        ilegiveis.add(rel);
                    }
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
            descer(raiz, f, nivel + 1, achados, ilegiveis);
        }
    }
}
