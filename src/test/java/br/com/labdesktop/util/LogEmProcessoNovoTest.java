package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regressão: o log do app empacotado deixou de ser gravado porque um logger nasceu (na carga de {@code Permissoes}) ANTES de o {@code ConfiguracaoDeLog} definir o
 * arquivo, e o {@code slf4j-simple} lê o destino uma vez só. Precisa de uma JVM nova (nesta o slf4j-simple já foi inicializado), e o filho usa as peças da partida real na ordem em que
 * o pior caso as usaria: pasta privada (carrega {@code Permissoes}) e só então a configuração do log.
 */
class LogEmProcessoNovoTest {

    /** Programa do filho. */
    public static final class Filho {
        public static void main(String[] args) throws Exception {
            CaminhosApp c = CaminhosApp.de(Path.of(args[0]).resolve("dados"), Path.of(args[0]).resolve("logs"));
            Permissoes.criarPastaPrivada(c.pastaDados().resolve("antes")); // o uso que antes criava o logger cedo demais
            ConfiguracaoDeLog.configurar(c);
            org.slf4j.LoggerFactory.getLogger("prova").info("linha-de-prova-do-log");
        }
    }

    private static String classes(Class<?> tipo) throws URISyntaxException {
        return Path.of(tipo.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test
    void umaLinhaDeLogChegaAoArquivoDePastaDeLogsNumProcessoNovo(@TempDir Path tmp) throws Exception {
        String sep = System.getProperty("path.separator");
        String cp = classes(ConfiguracaoDeLog.class) + sep + classes(Filho.class) + sep + classes(org.slf4j.LoggerFactory.class) + sep + classes(Class.forName("org.slf4j.simple.SimpleLogger"));
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        Process filho = new ProcessBuilder(java.toString(), "-cp", cp, Filho.class.getName(), tmp.toString()).redirectErrorStream(true).start();
        String saida = new String(filho.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(filho.waitFor(60, TimeUnit.SECONDS), "o filho não terminou");
        assertEquals(0, filho.exitValue(), saida);
        Path log = tmp.resolve("logs").resolve("lab-desktop.log");
        assertTrue(Files.exists(log), "o arquivo de log não foi criado; saída do filho: " + saida);
        assertTrue(lerOuVazio(log).contains("linha-de-prova-do-log"), "a linha não foi para o arquivo; arquivo: [" + lerOuVazio(log) + "] saída: [" + saida + "]");
        assertTrue(saida.isBlank() || !saida.contains("linha-de-prova-do-log"), "a linha foi para a tela em vez do arquivo: " + saida);
    }

    private static String lerOuVazio(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
