package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@code --versao} e {@code --autoteste} de verdade, num processo à parte com as classes do teste (o autoteste instala o tema e abre um PTY: não deve poluir a JVM dos outros testes).
 * É a mesma prova que o CI faz dentro do instalador, só que aqui com o runtime completo do Java.
 */
class AutoTesteEmProcessoTest {

    private static Process filho(String... argumentos) throws Exception {
        Path programa = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(programa.toString(), "-Djava.awt.headless=true", "-Dlabdesktop.autoteste.aceitaDev=true",
            "-cp", System.getProperty("java.class.path"), Principal.class.getName()));
        cmd.addAll(java.util.List.of(argumentos));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    @Test
    void versaoImprimeNomeEVersaoESai() throws Exception {
        Process p = filho("--versao");
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), saida);
        assertTrue(saida.strip().startsWith("Lab Desktop "), saida);
    }

    @Test
    void autotesteConfereAsBibliotecasSemJanelaESaiZero() throws Exception {
        Process p = filho("--autoteste");
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), saida);
        assertEquals(0, p.exitValue(), saida);
        for (String esperado : new String[] {"OK versao", "OK locale-pt-BR", "OK modulos-do-java", "OK chave-ed25519", "OK cofre-argon2-aes", "OK trava-de-instancia", "OK ssh-mina", "OK tema-flatlaf", "AUTOTESTE OK"}) {
            assertTrue(saida.contains(esperado), "faltou '" + esperado + "' em:\n" + saida);
        }
    }
}
