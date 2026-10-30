package br.com.labdesktop.construcao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O cálculo da versão com o git DE VERDADE, num repositório criado no teste (nunca o do projeto): prova que os comandos que o cálculo pede existem e respondem como ele espera,
 * inclusive no clone raso, que é o caso da cópia clonada só com o último commit. Sem git no computador o teste é pulado.
 */
class VersaoDaCompilacaoComGitDeVerdadeTest {

    private static final LocalDate DIA = LocalDate.of(2026, 10, 30);

    @BeforeAll
    static void gitDisponivel() {
        assumeTrue(rodar(Path.of("."), List.of("--version")).isPresent(), "sem git neste computador");
    }

    /** Como o build roda o git: {@code git -C <pasta> ...}, saída padrão aparada, vazio se saiu com erro. */
    private static Optional<String> rodar(Path pasta, List<String> args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", pasta.toAbsolutePath().toString()));
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process p = pb.start();
            p.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? Optional.of(saida) : Optional.empty();
        } catch (IOException | InterruptedException e) {
            return Optional.empty();
        }
    }

    private static VersaoDaCompilacao calcular(Path pasta) {
        return VersaoDaCompilacao.calcular("0.1", args -> rodar(pasta, args), DIA);
    }

    private static void git(Path pasta, String... args) {
        List<String> todos = new ArrayList<>(List.of("-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        todos.addAll(List.of(args));
        assertTrue(rodar(pasta, todos).isPresent(), "git " + String.join(" ", args));
    }

    private static void commitar(Path pasta, String arquivo, String texto) throws IOException {
        Files.writeString(pasta.resolve(arquivo), texto);
        git(pasta, "add", arquivo);
        git(pasta, "commit", "-q", "-m", "c " + texto);
    }

    @Test
    void contaOsCommitsMostraOCommitEMarcaAlteradoDeVerdade(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        VersaoDaCompilacao semCommits = calcular(repo);
        assertEquals("0.1.0", semCommits.versao(), "repositório sem commits cai na base com zero");
        assertEquals("desconhecido", semCommits.commit());

        commitar(repo, "a.txt", "um");
        commitar(repo, "b.txt", "dois");
        String cabeca = rodar(repo, List.of("rev-parse", "HEAD")).orElseThrow();
        VersaoDaCompilacao limpo = calcular(repo);
        assertEquals("0.1.2", limpo.versao());
        assertEquals(cabeca.substring(0, 7), limpo.commit());

        Files.writeString(repo.resolve("a.txt"), "mudou");
        assertEquals(cabeca.substring(0, 7) + "-alterado", calcular(repo).commit(), "arquivo rastreado mudado");
        git(repo, "checkout", "--", "a.txt");
        assertEquals(cabeca.substring(0, 7), calcular(repo).commit());
        Files.writeString(repo.resolve("novo.txt"), "x");
        assertEquals(cabeca.substring(0, 7) + "-alterado", calcular(repo).commit(), "arquivo novo ainda não rastreado");
    }

    @Test
    void cloneRasoCaiNaBaseComZeroEOCompletoContaTudo(@TempDir Path tmp) throws IOException {
        Path origem = tmp.resolve("origem");
        Files.createDirectories(origem);
        git(origem, "init", "-q");
        commitar(origem, "a.txt", "um");
        commitar(origem, "b.txt", "dois");
        commitar(origem, "c.txt", "tres");

        Path completo = tmp.resolve("completo");
        git(tmp, "clone", "-q", origem.toUri().toString(), completo.toString());
        assertEquals("0.1.3", calcular(completo).versao());

        Path raso = tmp.resolve("raso");
        git(tmp, "clone", "-q", "--depth", "1", origem.toUri().toString(), raso.toString());
        assertEquals("1", rodar(raso, List.of("rev-list", "--count", "HEAD")).orElseThrow(), "o clone raso de fato conta 1: a contagem seria falsa");
        VersaoDaCompilacao v = calcular(raso);
        assertEquals("0.1.0", v.versao());
        assertEquals("desconhecido", v.commit());
    }

    @Test
    void pastaForaDeQualquerRepositorioCaiNaBaseComZero(@TempDir Path tmp) {
        // o TempDir fica sob build/tmp/testes, DENTRO do repositório do projeto: um subdiretório novo ali ainda é do repositório do projeto.
        // Para provar "fora de um repositório" aponta o git para uma pasta inexistente (o mesmo erro de "não é um repositório": o git sai com erro).
        VersaoDaCompilacao v = VersaoDaCompilacao.calcular("0.1", args -> rodar(tmp.resolve("nao-existe"), args), DIA);
        assertEquals("0.1.0", v.versao());
        assertEquals("desconhecido", v.commit());
    }
}
