package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.CaminhosApp;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TravaDeInstanciaTest {

    @Test
    void segundaAquisicaoNaMesmaJvmFalhaEDepoisDeFecharFunciona(@TempDir Path tmp) throws Exception {
        CaminhosApp c = Fixtures.caminhos(tmp);
        TravaDeInstancia primeira = TravaDeInstancia.adquirir(c);
        assertThrows(CofreEmUsoException.class, () -> TravaDeInstancia.adquirir(c));
        primeira.close();
        primeira.close(); // idempotente
        try (TravaDeInstancia terceira = TravaDeInstancia.adquirir(c)) {
            assertNotNull(terceira);
        }
    }

    @Test
    @SuppressWarnings("try") // o recurso só precisa existir (segurar a trava) durante o bloco
    void criaOArquivoDeTravaPrivadoNaPastaPrivada(@TempDir Path tmp) throws Exception {
        CaminhosApp c = Fixtures.caminhos(tmp);
        try (TravaDeInstancia ignorada = TravaDeInstancia.adquirir(c)) {
            assertTrue(Files.exists(c.arquivoTrava()));
            // no Windows quem protege é a ACL (ProtecaoAcl): a conferência por sistema está em SistemaDoTeste.conferirPrivado
            br.com.labdesktop.teste.SistemaDoTeste.conferirPrivado("rw-------", br.com.labdesktop.util.Permissoes.comoTexto(c.arquivoTrava()));
            br.com.labdesktop.teste.SistemaDoTeste.conferirPrivado("rwx------", br.com.labdesktop.util.Permissoes.comoTexto(c.pastaDados()));
        }
    }

    /** Programa do processo filho: segura a trava até a entrada padrão fechar. */
    public static final class Filho {
        @SuppressWarnings("try")
        public static void main(String[] args) throws Exception {
            CaminhosApp c = CaminhosApp.de(Path.of(args[0]), Path.of(args[0]).resolveSibling("logs-filho"));
            try (TravaDeInstancia t = TravaDeInstancia.adquirir(c)) {
                System.out.println("TRAVADO");
                System.out.flush();
                //noinspection ResultOfMethodCallIgnored
                System.in.read(); // espera o pai fechar o stdin
            } catch (CofreEmUsoException e) {
                System.out.println("EM_USO");
            }
        }
    }

    private static String caminhoDoFilho() throws Exception {
        String sep = System.getProperty("path.separator");
        return classes(TravaDeInstancia.class) + sep + classes(Filho.class) + sep + classes(org.slf4j.LoggerFactory.class) + sep + classes(Class.forName("org.slf4j.simple.SimpleLogger"));
    }

    @Test
    void outroProcessoNaoConsegueAMesmaTrava(@TempDir Path tmp) throws Exception {
        CaminhosApp c = Fixtures.caminhos(tmp);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        // O filho só precisa das classes do app e do teste e do slf4j (a proteção de arquivos registra avisos); nenhuma outra biblioteca de terceiros.
        String cp = caminhoDoFilho();
        Process filho = new ProcessBuilder(java.toString(), "-cp", cp, Filho.class.getName(), c.pastaDados().toString())
            .redirectErrorStream(true).start();
        try {
            BufferedReader saida = new BufferedReader(new InputStreamReader(filho.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("TRAVADO", saida.readLine());
            // O filho segura: a trava é do sistema operacional e vale entre processos.
            assertThrows(CofreEmUsoException.class, () -> TravaDeInstancia.adquirir(c));
            filho.getOutputStream().close(); // o filho sai e solta a trava
            assertTrue(filho.waitFor(20, TimeUnit.SECONDS));
            try (TravaDeInstancia depois = TravaDeInstancia.adquirir(c)) {
                assertNotNull(depois);
            }
        } finally {
            filho.destroyForcibly();
        }
    }

    @Test
    void travaSoltaQuandoOProcessoMorreSemFechar(@TempDir Path tmp) throws Exception {
        CaminhosApp c = Fixtures.caminhos(tmp);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        String cp = caminhoDoFilho();
        Process filho = new ProcessBuilder(java.toString(), "-cp", cp, Filho.class.getName(), c.pastaDados().toString())
            .redirectErrorStream(true).start();
        BufferedReader saida = new BufferedReader(new InputStreamReader(filho.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("TRAVADO", saida.readLine());
        filho.destroyForcibly(); // kill -9: sem finally, sem hook
        assertTrue(filho.waitFor(20, TimeUnit.SECONDS));
        // Sem arquivo de PID para ficar velho: o lock morre com o processo.
        try (TravaDeInstancia depois = TravaDeInstancia.adquirir(c)) {
            assertNotNull(depois);
        }
    }

    private static String classes(Class<?> tipo) throws Exception {
        return Path.of(tipo.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
