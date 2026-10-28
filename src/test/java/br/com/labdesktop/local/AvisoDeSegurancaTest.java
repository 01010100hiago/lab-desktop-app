package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.PacoteDoAgente;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O aviso de segurança diz o que o {@code docker run} dá de fato ao container, em cada sistema. */
class AvisoDeSegurancaTest {

    private static String tudo(SistemaOperacional so) {
        return String.join("\n", AvisoDeSeguranca.paragrafos(so, "/Users/ana/LabLocal/projects"));
    }

    @Test
    void noMacDizSoqueteProcessosRaizSoLeituraPastaComEscritaEOAlcanceEmUsers() {
        String t = tudo(SistemaOperacional.MAC);
        assertTrue(t.contains("soquete do Docker") && t.contains("--pid host"), t);
        assertTrue(t.contains("a raiz /, montada em /host), só para leitura"), t);
        assertTrue(t.contains("/Users/ana/LabLocal/projects") && t.contains("com permissão de escrita"), t);
        assertTrue(t.contains("QUALQUER pasta") && t.contains("tudo o que está em /Users"), t);
        assertTrue(t.contains("SÓ em 127.0.0.1") && t.contains("exige o token"), t);
    }

    @Test
    void noWindowsOAlcanceEODiscoCENaoSeMontaARaiz() {
        String t = tudo(SistemaOperacional.WINDOWS);
        assertTrue(t.contains("disco C: inteiro"), t);
        assertFalse(t.contains("montada em /host"), t);
    }

    @Test
    void noLinuxODockerEquivaleARoot() {
        assertTrue(tudo(SistemaOperacional.LINUX).contains("administrador (root)"));
    }

    @Test
    void oAvisoCombinaComOQueODockerRunMontaDeVerdade() {
        PacoteDoAgente pacote = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {1});
        ParametrosDoLaboratorio p = ParametrosDoLaboratorio.padrao(7070, "/Users/ana/LabLocal/projects");
        List<String> mac = ComandosLocais.subirContainer(p, pacote, new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", java.util.Map.of()).montagemDaMaquina(), "/x");
        assertTrue(mac.contains("/var/run/docker.sock:/var/run/docker.sock") && mac.contains("host") && mac.contains("/:/host:ro"));
        List<String> win = ComandosLocais.subirContainer(p, pacote, new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", java.util.Map.of()).montagemDaMaquina(), "/x");
        assertFalse(win.contains("/:/host:ro"), "no Windows o aviso não promete a raiz montada: " + win);
    }
}
