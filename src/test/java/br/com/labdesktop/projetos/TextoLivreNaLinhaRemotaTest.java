package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.ssh.ResultadoExec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O texto livre numa linha de comando remota (o nome de quem assina um commit, um arquivo com espaço ou acento): só vai entre aspas simples, só nas posições que o programa marcou, e chega ao
 * programa como UM argumento exato, sem que nada nele vire comando. Provado com o {@code sh} de verdade.
 */
class TextoLivreNaLinhaRemotaTest {

    @TempDir
    Path tmp;

    private static final List<String> HOSTIS = List.of("$(touch PWNED)", "`touch PWNED`", "; touch PWNED", "&& touch PWNED", "| touch PWNED", "a'b\"c", "*", "-n", "~", "\\", "\\'", "'; touch PWNED; '",
        "${HOME}", "$HOME", "a b  c", "ação x.txt", "  espaços  ", "# comentário", "!histórico", "a\\nb", "(subshell)", "<arquivo", ">arquivo", "'''", "\"\"");

    @Test
    void asparSoEntreAspasSimplesEComAAspaSimplesEscapada() {
        assertEquals("'Ana Silva'", ExecutorDeArgumentos.aspar("Ana Silva"));
        assertEquals("'Joana D'\\''Ávila'", ExecutorDeArgumentos.aspar("Joana D'Ávila"));
        assertEquals("'a;b'", ExecutorDeArgumentos.aspar("a;b"));
        assertEquals("''\\'''\\'''\\'''", ExecutorDeArgumentos.aspar("'''"));
    }

    @Test
    void asparRecusaControleVazioEGrande() {
        for (String ruim : new String[] {"", "a\nb", "a\tb", "a\0b", "a\u001bb", "a\u007fb", "a\u0085b"}) {
            assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.aspar(ruim), ruim.replace("\n", "\\n"));
        }
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.aspar(null));
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.aspar("x".repeat(ExecutorDeArgumentos.MAXIMO_DO_TEXTO_LIVRE + 1)));
        assertEquals("'" + "x".repeat(ExecutorDeArgumentos.MAXIMO_DO_TEXTO_LIVRE) + "'", ExecutorDeArgumentos.aspar("x".repeat(ExecutorDeArgumentos.MAXIMO_DO_TEXTO_LIVRE)));
    }

    @Test
    void soAsPosicoesLivresSaemAspadasAsOutrasContinuamPresasAARegra() {
        assertEquals("git -C /srv/lab/projects/loja-web config --local user.name 'Ana Silva'",
            ExecutorDeArgumentos.linhaRemota(List.of("git", "-C", "/srv/lab/projects/loja-web", "config", "--local", "user.name", "Ana Silva"), Set.of(6), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of("git", "-C", "/srv/x y", "status"), Set.of(), Map.of()),
            "uma posição que NÃO é livre continua recusada se tem espaço");
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of("git", "-C", "/srv/x y", "status"), Set.of(3), Map.of()),
            "marcar OUTRA posição como livre não libera esta");
        assertEquals("env LC_ALL=C git show -- 'ação x.txt'", ExecutorDeArgumentos.linhaRemota(List.of("git", "show", "--", "ação x.txt"), Set.of(3), Map.of("LC_ALL", "C")));
    }

    @Test
    void oExecutorPorSshMandaALinhaComAspasSoNoTextoLivre() throws Exception {
        AtomicReference<String> linha = new AtomicReference<>();
        ExecutorDeArgumentos ex = ExecutorDeArgumentos.porSsh((comando, stdin, prazo) -> {
            linha.set(comando);
            return new ResultadoExec(0, new byte[0], new byte[0], false);
        });
        ex.executarComTexto(List.of("git", "config", "--local", "user.name", "Ana D'Ávila"), Set.of(4), Map.of(), new byte[0], Duration.ofSeconds(5));
        assertEquals("git config --local user.name 'Ana D'\\''Ávila'", linha.get());
        // sem marcar a posição como livre, o texto com espaço nunca sai
        assertThrows(IllegalArgumentException.class, () -> ex.executar(List.of("git", "config", "--local", "user.name", "Ana Silva"), Duration.ofSeconds(5)));
    }

    @Test
    void textoHostilChegaAoProgramaComoUmArgumentoExatoENadaViraComando() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        for (String hostil : HOSTIS) {
            String linha = "printf '%s' " + ExecutorDeArgumentos.aspar(hostil);
            Process p = new ProcessBuilder("/bin/sh", "-c", linha).directory(tmp.toFile()).start();
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(20, TimeUnit.SECONDS));
            assertEquals(hostil, saida, "chegou exato: " + hostil);
        }
        assertFalse(Files.exists(tmp.resolve("PWNED")), "nenhum comando escondido no texto rodou");
        try (var l = Files.list(tmp)) {
            assertEquals(0, l.count(), "e nenhum redirecionamento criou arquivo");
        }
    }

    @Test
    void oPadraoDeUmExecutorQueNaoSabeAsparRecusaOQueNaoESeguro() {
        ExecutorDeArgumentos semAspas = (argumentos, ambiente, stdin, prazo) -> {
            ExecutorDeArgumentos.linhaRemota(argumentos, ambiente);
            return new ResultadoExec(0, new byte[0], new byte[0], false);
        };
        assertThrows(IllegalArgumentException.class, () -> semAspas.executarComTexto(List.of("git", "x", "a b"), Set.of(2), Map.of(), new byte[0], Duration.ofSeconds(1)));
    }
}
