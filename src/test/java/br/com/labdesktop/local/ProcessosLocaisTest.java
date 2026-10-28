package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Um teste REAL de processo local, só com programas inofensivos do próprio JDK: o {@code java} que roda esta suíte (e arquivos {@code .java} de
 * uma linha que ele executa direto). Prova argumentos em lista, stdin, status, teto de saída, prazo, interrupção que MATA o processo e programa
 * ausente.
 */
class ProcessosLocaisTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir
    Path tmp;

    private final PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.detectar(), System.getProperty("user.home"), Map.of());
    private final ProcessosLocais processos = new ProcessosLocais(LocalizadorDeProgramas.doSistema(plataforma), plataforma);

    private Path programa(String nome, String corpo) throws Exception {
        Path f = tmp.resolve(nome + ".java");
        Files.writeString(f, "public class " + nome + " { public static void main(String[] a) throws Exception { " + corpo + " } }");
        return f;
    }

    @Test
    void rodaOJdkComArgumentosEmListaEDevolveStatusESaida() throws Exception {
        ResultadoExec x = processos.executar(List.of(JAVA, "-version"), Duration.ofSeconds(30));
        assertEquals(0, x.status());
        assertTrue(x.stderr().contains("version"), x.stderr()); // o java imprime a versão no stderr
    }

    @Test
    void argumentoComEspacoAspasEDolarChegaIntactoSemShell() throws Exception {
        Path f = programa("Ecoa", "for (String s : a) System.out.println(\"[\" + s + \"]\");");
        String estranho = "a b \"c\" $HOME ; rm -rf / `x` *";
        ResultadoExec x = processos.executar(List.of(JAVA, f.toString(), estranho, "dois"), Duration.ofSeconds(60));
        assertEquals(0, x.status(), x.stderr());
        assertEquals("[" + estranho + "]\n[dois]\n", x.stdout());
    }

    @Test
    void stdinChegaAoProgramaEOStatusDeSaidaVolta() throws Exception {
        Path f = programa("Eco", "byte[] b = System.in.readAllBytes(); System.out.write(b); System.out.flush(); System.exit(b.length == 5 ? 7 : 0);");
        ResultadoExec x = processos.executar(List.of(JAVA, f.toString()), "olá!!".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60));
        assertEquals(7 - (7 - x.status()), x.status());
        assertEquals("olá!!", x.stdout());
    }

    @Test
    void stdinGrandeNaoTravaMesmoComOProgramaEscrevendoEnquantoLe() throws Exception {
        Path f = programa("Soma", "long n = 0; byte[] b = new byte[8192]; int r; while ((r = System.in.read(b)) > 0) { n += r; System.out.println(\"lido \" + n + \" \" + \"x\".repeat(200)); } "
            + "System.exit(0);");
        ResultadoExec x = processos.executar(List.of(JAVA, f.toString()), new byte[3_000_000], Duration.ofSeconds(60));
        assertEquals(0, x.status());
        assertTrue(x.truncado(), "a saída passou do teto de 64 KB e foi cortada, sem estourar a memória");
        assertTrue(x.stdout().length() <= CapturaDeSaida.TETO);
    }

    @Test
    void programaQueNaoExisteEUmaExcecaoPropriaENaoUmaFalhaGenerica() {
        ProgramaNaoEncontradoException e = assertThrows(ProgramaNaoEncontradoException.class,
            () -> processos.executar(List.of("programa-que-nao-existe-xyz", "--versao"), Duration.ofSeconds(5)));
        assertEquals("programa-que-nao-existe-xyz", e.programa());
        assertThrows(ProgramaNaoEncontradoException.class, () -> processos.executar(List.of(tmp.resolve("nao-existe").toString()), Duration.ofSeconds(5)));
    }

    @Test
    void oPrimeiroItemNuncaEUmaLinhaDeComandoENemCaminhoRelativoENemControle() {
        for (List<String> ruim : List.of(List.<String>of(), List.of("docker version"), List.of("docker", "a\0b"))) {
            assertThrows(RuntimeException.class, () -> processos.executar(ruim, Duration.ofSeconds(1)), ruim.toString());
        }
        for (List<String> relativo : List.of(List.of("../docker"), List.of("rel/docker"))) {
            assertThrows(ProgramaNaoEncontradoException.class, () -> processos.executar(relativo, Duration.ofSeconds(1)), "caminho relativo nunca é procurado");
        }
    }

    @Test
    void prazoEstouradoMataOProcessoELancaOErroDePrazo() throws Exception {
        Path f = programa("Dorme", "System.out.println(ProcessHandle.current().pid()); System.out.flush(); Thread.sleep(60_000);");
        long inicio = System.nanoTime();
        assertThrows(ComandoDemorouException.class, () -> processos.executar(List.of(JAVA, f.toString()), Duration.ofSeconds(4)));
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toSeconds() < 20);
    }

    @Test
    void interromperAThreadMataOProcessoDeVerdade() throws Exception {
        Path pidArquivo = tmp.resolve("pid.txt");
        Path f = programa("Preso", "java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), Long.toString(ProcessHandle.current().pid())); "
            + "Thread.sleep(120_000);");
        AtomicReference<Throwable> erro = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                processos.executar(List.of(JAVA, f.toString(), pidArquivo.toString()), Duration.ofMinutes(5));
            } catch (Throwable e) {
                erro.set(e);
            }
        });
        long limite = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!Files.exists(pidArquivo) && System.nanoTime() < limite) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(pidArquivo), "o processo começou");
        Thread.sleep(200);
        long pid = Long.parseLong(Files.readString(pidArquivo).strip());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "está rodando (e bloqueado de verdade)");

        t.interrupt();
        t.join(10_000);
        assertTrue(erro.get() instanceof InterruptedException, "vira InterruptedException: " + erro.get());
        long espera = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < espera) {
            Thread.sleep(50);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "o Cancelar matou o processo, ele não ficou rodando sozinho");
    }
}
