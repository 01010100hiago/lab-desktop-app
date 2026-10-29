package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ClienteSftpLocal;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.local.LocalizadorDeProgramas;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ProcessosLocais;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * O {@code git} DE VERDADE numa pasta temporária, com a configuração do git isolada (um HOME de mentira, sem configuração do sistema): nada do que o git do usuário tem configurado entra no teste,
 * e nada do teste vai para o git dele. Todos os comandos passam pelo {@link ProcessosLocais} do app (o mesmo caminho de "Este computador"), anotados em {@link #comandos}.
 */
public final class GitDeTeste {

    public final Path casa;
    public final Path raiz;
    public final List<List<String>> comandos = new CopyOnWriteArrayList<>();
    private final Map<String, String> extra = new HashMap<>();
    private final ProcessosLocais processos;

    public GitDeTeste(Path tmp) {
        this.casa = tmp.resolve("casa");
        this.raiz = casa.resolve("LabLocal").resolve("projects");
        try {
            Files.createDirectories(raiz);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.detectar(), System.getProperty("user.home"), Map.of());
        this.processos = new ProcessosLocais(LocalizadorDeProgramas.doSistema(plataforma), plataforma);
        extra.put("HOME", casa.toString());
        extra.put("XDG_CONFIG_HOME", casa.resolve(".config").toString());
        extra.put("GIT_CONFIG_NOSYSTEM", "1");
        extra.put("GIT_CONFIG_GLOBAL", casa.resolve(".gitconfig").toString());
        // os testes moram em build/tmp, que fica DENTRO do repositório do app: o git não pode subir até lá (senão toda pasta de teste "estaria dentro de outro repositório")
        extra.put("GIT_CEILING_DIRECTORIES", tmp.toAbsolutePath().toString());
    }

    /** Há um git de verdade neste computador? */
    public static boolean temGit() {
        try {
            PlataformaLocal p = new PlataformaLocal(SistemaOperacional.detectar(), System.getProperty("user.home"), Map.of());
            return new ProcessosLocais(LocalizadorDeProgramas.doSistema(p), p).executar(List.of("git", "--version"), Duration.ofSeconds(20)).status() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Variável de ambiente a mais em TODO comando (por exemplo {@code GIT_TEST_ASSUME_DIFFERENT_OWNER=1}, que faz o git achar que a pasta é de outro dono). */
    public GitDeTeste comAmbiente(String nome, String valor) {
        extra.put(nome, valor);
        return this;
    }

    /** O executor do app com o ambiente isolado. */
    public ExecutorDeArgumentos executor() {
        return new ExecutorDeArgumentos() {
            @Override
            public ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
                comandos.add(List.copyOf(argumentos));
                Map<String, String> todos = new HashMap<>(extra);
                todos.putAll(ambiente);
                return processos.executar(argumentos, todos, stdin, prazo);
            }
        };
    }

    /** O mesmo executor, mas que SÓ aceita o que passa pela regra de linha remota (como o SSH): prova que nada depende de um shell. */
    public ExecutorDeArgumentos executorComRegraDeSsh() {
        ExecutorDeArgumentos base = executor();
        return new ExecutorDeArgumentos() {
            @Override
            public ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
                ExecutorDeArgumentos.linhaRemota(argumentos, ambiente); // lança se algum argumento não é seguro
                return base.executar(argumentos, ambiente, stdin, prazo);
            }

            @Override
            public ResultadoExec executarComTexto(List<String> argumentos, java.util.Set<Integer> livres, Map<String, String> ambiente, byte[] stdin, Duration prazo)
                throws IOException, InterruptedException {
                ExecutorDeArgumentos.linhaRemota(argumentos, livres, ambiente);
                return base.executar(argumentos, ambiente, stdin, prazo);
            }
        };
    }

    public FabricaDeClienteSftp arquivos() {
        return () -> {
            ClienteSftp c = new ClienteSftpLocal(new CaminhosLocais(false), casa.toString());
            return c;
        };
    }

    /** O ambiente de projetos "Este computador" desta pasta, com o executor isolado. */
    public AmbienteDeProjetos ambiente() {
        return new AmbienteDeProjetos(true, raiz.toString(), arquivos(), executor(), c -> c);
    }

    public AmbienteDeProjetos ambiente(ExecutorDeArgumentos executor) {
        return new AmbienteDeProjetos(true, raiz.toString(), arquivos(), executor, c -> c);
    }

    // ---------------------------------------------------------------------------------------------

    /** Roda o git na pasta e exige sucesso; devolve o que ele escreveu. */
    public String git(Path pasta, String... args) {
        ResultadoExec r = bruto(pasta, args);
        assertEquals(0, r.status(), "git " + String.join(" ", args) + ": " + r.stderr());
        return r.stdout();
    }

    public ResultadoExec bruto(Path pasta, String... args) {
        List<String> l = new ArrayList<>();
        l.add("git");
        l.add("-C");
        l.add(pasta.toString());
        l.addAll(List.of(args));
        try {
            Map<String, String> env = new HashMap<>(extra);
            return processos.executar(l, env, new byte[0], Duration.ofSeconds(60));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Uma pasta de projeto nova (sem git). */
    public Path projeto(String nome) {
        try {
            return Files.createDirectories(raiz.resolve(nome));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Um repositório novo com identidade local de teste. */
    public Path repositorio(String nome) {
        Path p = projeto(nome);
        git(p, "init", "-q", "-b", "main");
        git(p, "config", "--local", "user.name", "Ana Teste");
        git(p, "config", "--local", "user.email", "ana@example.com");
        return p;
    }

    public void escrever(Path pasta, String relativo, String conteudo) {
        escreverBytes(pasta, relativo, conteudo.getBytes(StandardCharsets.UTF_8));
    }

    public void escreverBytes(Path pasta, String relativo, byte[] bytes) {
        try {
            Path f = pasta.resolve(relativo);
            Files.createDirectories(f.getParent());
            Files.write(f, bytes);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** {@code git add -A} e commit com a mensagem; devolve o hash inteiro. */
    public String commitar(Path pasta, String mensagem) {
        git(pasta, "add", "-A");
        git(pasta, "commit", "-q", "-m", mensagem);
        return git(pasta, "rev-parse", "HEAD").strip();
    }

    /** O app como "caminho do app" desta pasta: o mesmo texto no computador local. */
    public String pastaNoApp(Path pasta) {
        return pasta.toString();
    }
}
