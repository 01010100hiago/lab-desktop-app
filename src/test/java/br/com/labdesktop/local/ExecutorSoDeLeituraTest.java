package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ssh.ResultadoExec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A fronteira "só leitura" é imposta em código de produção pelo executor da conferência e do diagnóstico de fundo. */
class ExecutorSoDeLeituraTest {

    @Test
    void deixaPassarLeiturasERecusaQualquerEscritaSemChegarAoProcesso() throws Exception {
        List<List<String>> rodou = new ArrayList<>();
        ExecutorDeProcesso por = (a, in, prazo) -> {
            rodou.add(a);
            return new ResultadoExec(0, new byte[0], new byte[0], false);
        };
        ExecutorSoDeLeitura e = new ExecutorSoDeLeitura(por);
        ParametrosDoLaboratorio p = ParametrosDoLaboratorio.padrao(7070, "/Users/ana/LabLocal/projects");

        e.executar(ComandosLocais.versaoDoDocker(), Duration.ofSeconds(1));
        e.executar(ComandosLocais.lerContainer(p), Duration.ofSeconds(1));
        e.executar(ComandosLocais.versaoDoGit(), Duration.ofSeconds(1));
        assertEquals(3, rodou.size());

        for (List<String> escrita : List.of(ComandosLocais.criarRede(p), ComandosLocais.removerContainer("painel-lab"), ComandosLocais.criarVolume(p),
            List.of("brew", "install", "--cask", "docker-desktop"), List.of("xcode-select", "--install"), List.of("open", "-a", "Docker"),
            List.of("docker", "run", "-d", "alpine"), List.of("git", "clone", "x"))) {
            assertThrows(IllegalStateException.class, () -> e.executar(escrita, Duration.ofSeconds(1)), escrita.toString());
        }
        assertEquals(3, rodou.size(), "nenhuma escrita chegou ao processo");
        assertTrue(rodou.stream().allMatch(ComandosLocais::ehLeitura));
    }
}
