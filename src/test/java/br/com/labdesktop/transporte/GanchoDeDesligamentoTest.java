package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.SaidaDaJvmComTunel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * O gancho de desligamento da JVM: uma JVM de verdade abre um túnel e SAI sem fechá-lo; o programa do túnel (processo filho dela) tem de sumir. E o limite honesto: se a JVM
 * morre sem rodar nenhum gancho ({@code halt}, como um {@code kill -9}), o processo fica; o que cobre isso é o prazo curto da ferramenta e ninguém depender dele (ver ARQUITETURA 7.14).
 */
class GanchoDeDesligamentoTest {

    private record Resultado(long pidDaFerramenta, int codigoDaJvm) { }

    private static Resultado rodar(String modo) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SaidaDaJvmComTunel.class.getName(), modo)
            .redirectErrorStream(true).start();
        long pid = -1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = r.readLine()) != null) {
                if (linha.startsWith("PID ")) {
                    pid = Long.parseLong(linha.substring(4).strip());
                }
            }
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "a JVM filha terminou");
        return new Resultado(pid, p.exitValue());
    }

    private static boolean vivo(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    void saidaNormalDaJvmEncerraOProgramaDoTunelPeloGancho() throws Exception {
        Resultado r = rodar("sair");
        assertEquals(0, r.codigoDaJvm());
        assertTrue(r.pidDaFerramenta() > 0, "a JVM filha abriu o túnel");
        for (int i = 0; i < 100 && vivo(r.pidDaFerramenta()); i++) {
            Thread.sleep(50);
        }
        assertFalse(vivo(r.pidDaFerramenta()), "o gancho encerrou o programa do túnel: nenhum processo órfão");
    }

    @Test
    void semNenhumGanchoOProcessoFicaEIssoEOLimiteConhecido() throws Exception {
        Resultado r = rodar("morrer-sem-gancho");
        assertTrue(r.pidDaFerramenta() > 0);
        try {
            // a ferramenta falsa NÃO morre sozinha: prova o limite (kill -9 do app não dá chance ao gancho) e depois limpa
            Thread.sleep(300);
            assertTrue(vivo(r.pidDaFerramenta()), "sem gancho, o processo da ferramenta sobrevive (limite documentado)");
        } finally {
            ProcessHandle.of(r.pidDaFerramenta()).ifPresent(h -> {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
                h.destroyForcibly();
            });
        }
    }
}
