package br.com.labdesktop.local;

import br.com.labdesktop.api.modelo.Container;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.GitInfo;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Os projetos do laboratório LOCAL ("Este computador"): as pastas da pasta dos projetos e os containers do Docker que o compose ligou a elas. O
 * agente local é do perfil monitor e não conhece projetos, então quem lê a pasta e o Docker é o app, com as MESMAS regras do agente do lab: só
 * pasta com nome válido, os arquivos {@code docker-compose*.yml} da raiz (e {@code <subpasta>/docker-compose.yml}), o {@code name:} do compose, e container
 * só conta se a pasta de trabalho dele fica dentro da pasta do projeto.
 *
 * <p>A leitura roda sozinha (a tela consulta a cada 10 s), por isso usa só o executor que impõe a fronteira "só leitura"
 * ({@link ExecutorSoDeLeitura}); Iniciar, Parar e Reiniciar usam o executor de escrita e só nascem do botão do usuário. Tudo bloqueia: thread de fundo.
 */
public final class ProjetosLocais {

    /** O projeto não existe (mais) na pasta. */
    public static final class ProjetoNaoExisteException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProjetoNaoExisteException(String nome) {
            super("O projeto " + nome + " não existe na pasta dos projetos.");
        }
    }

    private static final Pattern COMPOSE_NA_RAIZ = Pattern.compile("^docker-compose.*\\.ya?ml$");
    private static final Pattern NOME_NO_COMPOSE = Pattern.compile("(?m)^name:[ \\t]*[\"']?([a-z0-9][a-z0-9_-]*)[\"']?[ \\t]*(#.*)?$");
    private static final Pattern ROTULO_DO_PROJETO = Pattern.compile("(?:^|,)com\\.docker\\.compose\\.project=([^,]*)");
    private static final Pattern ROTULO_DA_PASTA = Pattern.compile("(?:^|,)com\\.docker\\.compose\\.project\\.working_dir=([^,]*)");
    private static final Duration PRAZO_DA_LEITURA = Duration.ofSeconds(20);
    private static final Duration PRAZO_DA_ACAO = Duration.ofMinutes(12);
    private static final int TETO_DO_TEXTO = 6000;

    private final Supplier<Path> pasta;
    private final ExecutorDeProcesso leitura;
    private final ExecutorDeProcesso escrita;

    /**
     * @param pasta    a pasta dos projetos locais (caminho do sistema), lida a cada consulta (o usuário pode trocá-la)
     * @param executor o executor de processos do computador; a leitura passa pelo que só deixa passar leitura
     */
    public ProjetosLocais(Supplier<Path> pasta, ExecutorDeProcesso executor) {
        this.pasta = pasta;
        this.leitura = new ExecutorSoDeLeitura(executor);
        this.escrita = executor;
    }

    private record Compose(String arquivo, String projetoDoCompose) { }

    private record ContainerLido(String nome, String estado, String status, String portas, String imagem, String projetoDoCompose, String pastaDeTrabalho) { }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    /** A lista de projetos (a pasta vazia, ou que ainda não existe, é uma lista vazia). */
    public ListaProjetos lista() throws IOException {
        Path raiz = pasta.get();
        List<String> nomes = nomesDasPastas(raiz);
        List<ContainerLido> todos = nomes.isEmpty() ? List.of() : containersDoDocker();
        List<Projeto> projetos = new ArrayList<>();
        for (String nome : nomes) {
            Path p = raiz.resolve(nome);
            List<Compose> composes = composesDe(nome, p);
            List<Container> containers = containersDe(nome, p, composes, todos);
            projetos.add(new Projeto(nome, descricaoDe(p), composes.stream().map(Compose::arquivo).toList(), containers,
                containers.stream().anyMatch(Container::rodando), List.of(), null, mexidoEm(p), false, null, null, null, null, true, 0, null, null,
                gitDe(p)));
        }
        return new ListaProjetos(projetos, null);
    }

    /** O detalhe de um projeto. */
    public DetalheProjeto detalhe(String nome) throws IOException {
        if (!ValidadorDeNomes.projetoValido(nome)) {
            throw new ProjetoNaoExisteException(nome);
        }
        Path raiz = pasta.get();
        Path p = raiz.resolve(nome);
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProjetoNaoExisteException(nome);
        }
        List<Compose> composes = composesDe(nome, p);
        List<Container> containers = containersDe(nome, p, composes, containersDoDocker());
        List<DetalheProjeto.Arquivo> arquivos = new ArrayList<>();
        for (String n : nomesOrdenados(p)) {
            Path filho = p.resolve(n);
            arquivos.add(new DetalheProjeto.Arquivo(n, Files.isDirectory(filho, LinkOption.NOFOLLOW_LINKS) ? "pasta" : "arquivo", null));
        }
        return new DetalheProjeto(nome, descricaoDe(p), texto(p.resolve("README.md")), texto(p.resolve("STATUS.md")),
            composes.stream().map(c -> new DetalheProjeto.Compose(c.arquivo(), c.projetoDoCompose())).toList(), containers, List.of(), null, gitDe(p),
            backupsDe(p), null, mexidoEm(p), true, List.of(), arquivos, null);
    }

    /** Quantos backups o detalhe lista no máximo (o do agente também corta). */
    private static final int MAXIMO_DE_BACKUPS = 200;

    /** Os arquivos da pasta {@code backups/} do projeto, do mais recente ao mais antigo: só arquivo comum (nunca link nem pasta), com o tamanho e a hora de alteração. */
    static List<DetalheProjeto.Backup> backupsDe(Path projeto) {
        Path pastaDeBackups = projeto.resolve("backups");
        if (!Files.isDirectory(pastaDeBackups, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<DetalheProjeto.Backup> l = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(pastaDeBackups)) {
            for (Path f : s) {
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    l.add(new DetalheProjeto.Backup(f.getFileName().toString(), Files.size(f), Files.getLastModifiedTime(f).toInstant()));
                } catch (IOException e) {
                    // um arquivo que sumiu no meio da leitura não entra
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        l.sort(Comparator.comparing(DetalheProjeto.Backup::quando, Comparator.nullsLast(Comparator.reverseOrder())));
        return l.size() > MAXIMO_DE_BACKUPS ? List.copyOf(l.subList(0, MAXIMO_DE_BACKUPS)) : List.copyOf(l);
    }

    private static List<String> nomesDasPastas(Path raiz) throws IOException {
        if (!Files.isDirectory(raiz)) {
            return List.of();
        }
        List<String> nomes = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(raiz)) {
            for (Path f : s) {
                String n = f.getFileName().toString();
                if (ValidadorDeNomes.projetoValido(n) && Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) {
                    nomes.add(n);
                }
            }
        }
        nomes.sort(Comparator.naturalOrder());
        return nomes;
    }

    private static List<String> nomesOrdenados(Path pasta) throws IOException {
        List<String> nomes = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(pasta)) {
            for (Path f : s) {
                nomes.add(f.getFileName().toString());
            }
        }
        nomes.sort(String.CASE_INSENSITIVE_ORDER);
        return nomes.size() > 200 ? nomes.subList(0, 200) : nomes;
    }

    private static List<Compose> composesDe(String nome, Path pasta) {
        List<String> achados = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(pasta)) {
            for (Path f : s) {
                String n = f.getFileName().toString();
                if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && COMPOSE_NA_RAIZ.matcher(n).matches()) {
                    achados.add(n);
                } else if (Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS) && !n.startsWith(".") && !n.equals("referencia") && !n.equals("node_modules")
                    && Files.isRegularFile(f.resolve("docker-compose.yml"), LinkOption.NOFOLLOW_LINKS)) {
                    achados.add(n + "/docker-compose.yml");
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        achados.sort(Comparator.comparing((String x) -> x.contains("/")).thenComparing(Comparator.naturalOrder()));
        List<Compose> r = new ArrayList<>();
        for (String arquivo : achados) {
            Matcher m = NOME_NO_COMPOSE.matcher(textoSemErro(pasta.resolve(arquivo), 60_000));
            String padrao = arquivo.contains("/") ? arquivo.substring(0, arquivo.indexOf('/')).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "") : nome;
            String nomeDoCompose = m.find() ? m.group(1) : (padrao.isEmpty() ? nome : padrao);
            if (!NomeDoComposeRecusado.recusa(nomeDoCompose, nome, pasta.getParent())) {
                r.add(new Compose(arquivo, nomeDoCompose));
            }
        }
        return r;
    }

    /** O {@code name:} de um compose não pode ser o de outro projeto nem o da infraestrutura (a regra do agente do lab). */
    private static final class NomeDoComposeRecusado {
        /** Os mesmos nomes que o agente reserva (a infraestrutura e a sessão raiz). */
        private static final java.util.Set<String> RESERVADOS = java.util.Set.of("infra", "painel", "lab");

        static boolean recusa(String nomeDoCompose, String projeto, Path raiz) {
            if (RESERVADOS.contains(nomeDoCompose) || nomeDoCompose.length() > 63) { // acima de 63 o Docker não aceita o nome do projeto do compose
                return true;
            }
            return !nomeDoCompose.equals(projeto) && raiz != null && Files.exists(raiz.resolve(nomeDoCompose));
        }
    }

    private List<ContainerLido> containersDoDocker() {
        try {
            ResultadoExec r = leitura.executar(ComandosLocais.lerContainersDosProjetos(), PRAZO_DA_LEITURA);
            if (r.status() != 0) {
                return List.of(); // Docker parado ou sem permissão: a faixa do servidor já diz; aqui só não há container para mostrar
            }
            List<ContainerLido> l = new ArrayList<>();
            for (String linha : r.stdout().split("\n")) {
                String[] p = linha.split("\\|", 6);
                if (p.length < 6 || p[0].isBlank()) {
                    continue;
                }
                Matcher mp = ROTULO_DO_PROJETO.matcher(p[5]);
                Matcher md = ROTULO_DA_PASTA.matcher(p[5]);
                l.add(new ContainerLido(p[0].strip(), p[1].strip(), p[2].strip(), p[3].strip(), p[4].strip(), mp.find() ? mp.group(1) : "",
                    md.find() ? md.group(1) : ""));
            }
            return l;
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static List<Container> containersDe(String nome, Path pasta, List<Compose> composes, List<ContainerLido> todos) {
        List<String> aceitos = composes.stream().map(Compose::projetoDoCompose).distinct().toList();
        String base = semBarraFinal(pasta.toString());
        List<Container> r = new ArrayList<>();
        for (ContainerLido c : todos) {
            String trabalho = semBarraFinal(c.pastaDeTrabalho());
            boolean dentro = trabalho.equals(base) || trabalho.startsWith(base + "/") || trabalho.startsWith(base + "\\");
            if (aceitos.contains(c.projetoDoCompose()) && dentro) {
                r.add(new Container(c.nome(), c.estado(), c.status(), c.portas(), c.imagem(), null, null, null, null, null, null, null));
            }
        }
        return r;
    }

    private static String semBarraFinal(String t) {
        String s = t == null ? "" : t;
        while (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String descricaoDe(Path pasta) {
        for (String linha : textoSemErro(pasta.resolve("README.md"), 20_000).split("\n")) {
            String l = linha.strip();
            if (l.isEmpty() || l.charAt(0) == '#' || l.charAt(0) == '!' || l.charAt(0) == '|' || l.startsWith("```")) {
                continue;
            }
            String t = l.replaceAll("[*_`]", "");
            return t.length() <= 170 ? t : t.substring(0, 170).replaceAll("\\s+\\S*$", "") + "…";
        }
        return "";
    }

    private static Long mexidoEm(Path pasta) {
        long maior = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(pasta)) {
            for (Path f : s) {
                try {
                    maior = Math.max(maior, Files.getLastModifiedTime(f, LinkOption.NOFOLLOW_LINKS).toMillis());
                } catch (IOException e) {
                    // some entre uma leitura e outra: ignora
                }
            }
        } catch (IOException e) {
            return null;
        }
        return maior == 0 ? null : maior;
    }

    /** A branch, lida de {@code .git/HEAD} (sem rodar o git): o app não executa nada de repositório sozinho. */
    private static GitInfo gitDe(Path pasta) {
        Path cabeca = pasta.resolve(".git").resolve("HEAD");
        String t = textoSemErro(cabeca, 512).strip();
        if (t.isEmpty()) {
            return null;
        }
        String ref = "ref: refs/heads/";
        return new GitInfo(t.startsWith(ref) ? t.substring(ref.length()) : "(sem branch)", null, null, null);
    }

    private static String texto(Path arquivo) throws IOException {
        try {
            return lerAte(arquivo, TETO_DO_TEXTO);
        } catch (NoSuchFileException e) {
            return "";
        }
    }

    private static String textoSemErro(Path arquivo, int teto) {
        try {
            return lerAte(arquivo, teto);
        } catch (IOException e) {
            return "";
        }
    }

    private static String lerAte(Path arquivo, int teto) throws IOException {
        if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(arquivo.toString());
        }
        try (var in = Files.newInputStream(arquivo)) {
            return new String(in.readNBytes(teto), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ações (só pelo botão do usuário)
    // ---------------------------------------------------------------------------------------------

    /** {@code docker compose up -d} em cada compose do projeto. Devolve a saída; falha com o texto do Docker. */
    public String iniciar(String nome) throws IOException, InterruptedException {
        return comporCadaArquivo(nome, ComandosLocais.AcaoDeProjeto.INICIAR);
    }

    /** {@code docker compose down} (SEM {@code -v}: nenhum volume some) em cada compose. */
    public String parar(String nome) throws IOException, InterruptedException {
        return comporCadaArquivo(nome, ComandosLocais.AcaoDeProjeto.PARAR);
    }

    public String reiniciar(String nome) throws IOException, InterruptedException {
        return comporCadaArquivo(nome, ComandosLocais.AcaoDeProjeto.REINICIAR);
    }

    private String comporCadaArquivo(String nome, ComandosLocais.AcaoDeProjeto acao) throws IOException, InterruptedException {
        if (!ValidadorDeNomes.projetoValido(nome)) {
            throw new ProjetoNaoExisteException(nome);
        }
        Path p = pasta.get().resolve(nome);
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProjetoNaoExisteException(nome);
        }
        List<Compose> composes = composesDe(nome, p);
        if (composes.isEmpty()) {
            throw new IOException("Projeto sem docker-compose.yml.");
        }
        StringBuilder saida = new StringBuilder();
        for (Compose c : composes) {
            ResultadoExec r = escrita.executar(ComandosLocais.comporProjeto(c.projetoDoCompose(), p.resolve(c.arquivo()).toString(), acao),
                PRAZO_DA_ACAO);
            // o Docker escreve o progresso e o erro no stderr: com falha vale o stderr; com sucesso, o que saiu no stdout (ou o progresso)
            String texto = (r.status() != 0 ? (r.stderr().isBlank() ? r.stdout() : r.stderr()) : (r.stdout().isBlank() ? r.stderr() : r.stdout())).strip();
            saida.append('[').append(c.arquivo()).append("] ").append(texto).append('\n');
            if (r.status() != 0) {
                throw new IOException(saida.toString().strip());
            }
        }
        return saida.toString().strip();
    }

    /** {@code docker restart} de UM container do projeto (só se ele é mesmo do projeto). */
    public void reiniciarContainer(String nome, String container) throws IOException, InterruptedException {
        DetalheProjeto d = detalhe(nome);
        if (d.containers().stream().noneMatch(c -> c.nome().equals(container))) {
            throw new IOException("O container " + container + " não é deste projeto.");
        }
        ResultadoExec r = escrita.executar(ComandosLocais.reiniciarContainerDoProjeto(container), Duration.ofMinutes(3));
        if (r.status() != 0) {
            throw new IOException((r.stderr().isBlank() ? r.stdout() : r.stderr()).strip());
        }
    }
}
