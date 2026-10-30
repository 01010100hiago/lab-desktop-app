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
        return filhoComClasspath(System.getProperty("java.class.path"), java.util.List.of(), argumentos);
    }

    private static Process filhoComClasspath(String classpath, java.util.List<String> opcoesDaJvm, String... argumentos) throws Exception {
        Path programa = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(programa.toString(), "-Djava.awt.headless=true", "-Dlabdesktop.autoteste.aceitaDev=true",
            "-cp", classpath, Principal.class.getName()));
        cmd.addAll(1, opcoesDaJvm);
        cmd.addAll(java.util.List.of(argumentos));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    private static String saidaDe(Process p) throws Exception {
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), saida);
        return saida.strip();
    }

    @Test
    void versaoImprimeNomeEVersaoESai() throws Exception {
        assertEquals("Lab Desktop dev", saidaDe(filho("--versao")), "fora do jar (testes, IDE) a versão é dev, sem inventar commit");
    }

    @Test
    void versaoNoRunMostraOCommitEADataQueOBuildPassou() throws Exception {
        assertEquals("Lab Desktop dev (a207ad4-alterado, 2026-10-30)",
            saidaDe(filhoComClasspath(System.getProperty("java.class.path"), java.util.List.of("-Dlabdesktop.build.commit=a207ad4-alterado", "-Dlabdesktop.build.data=2026-10-30"), "--versao")));
    }

    /** O app de verdade: a classe {@code Versao} dentro de um jar com o manifesto que o build grava; o {@code --versao} imprime a versão completa. */
    @Test
    void versaoImprimeAVersaoCompletaQuandoHaManifestoNoJar(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("lab-desktop.jar");
        java.util.jar.Manifest m = new java.util.jar.Manifest();
        m.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Implementation-Version", "0.1.320");
        m.getMainAttributes().putValue("Build-Commit", "a207ad4");
        m.getMainAttributes().putValue("Build-Date", "2026-10-30");
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar), m)) {
            for (String classe : new String[] {"Versao", "Versao$Compilacao", "Versao$Lida"}) {
                out.putNextEntry(new java.util.jar.JarEntry("br/com/labdesktop/" + classe + ".class"));
                try (var in = Versao.class.getResourceAsStream(classe + ".class")) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        // o jar vai NA FRENTE do classpath: a Versao carregada é a dele (com manifesto); o resto do app vem das classes do teste
        String saida = saidaDe(filhoComClasspath(jar + java.io.File.pathSeparator + System.getProperty("java.class.path"), java.util.List.of(), "--versao"));
        assertEquals("Lab Desktop 0.1.320 (a207ad4, 2026-10-30)", saida);
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
        assertTrue(saida.contains("conectou em 127.0.0.1 e autenticou"), "o ssh-mina deve fazer a ida e volta com um servidor local:\n" + saida);
    }
}
