package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.PacoteDoAgente;
import java.util.List;
import org.junit.jupiter.api.Test;
import br.com.labdesktop.plataforma.SistemaOperacional;

/** O aviso de segurança diz o que o {@code docker run} dá de fato ao container, em cada sistema. */
class AvisoDeSegurancaTest {

    private static String tudo(SistemaOperacional so) {
        return String.join("\n", AvisoDeSeguranca.paragrafos(so, "/Users/ana/LabLocal/projects"));
    }

    @Test
    void noMacDizSoqueteProcessosTresArquivosENaoDiscoNemPastaEOAlcanceEmUsers() {
        String t = tudo(SistemaOperacional.MAC);
        assertTrue(t.contains("soquete do Docker") && t.contains("--pid host"), t);
        assertTrue(t.contains("três arquivos do computador, só para leitura") && t.contains("/etc/hostname") && t.contains("/etc/passwd"), t);
        assertTrue(t.contains("NÃO recebe o disco, nem a pasta dos projetos (/Users/ana/LabLocal/projects)"), t);
        assertFalse(t.contains("com permissão de escrita") || t.contains("montada em /host"), "não promete o que não monta: " + t);
        assertTrue(t.contains("QUALQUER pasta") && t.contains("tudo o que está em /Users"), t);
        assertTrue(t.contains("SÓ em 127.0.0.1") && t.contains("exige o token"), t);
    }

    @Test
    void noWindowsOAlcanceEODiscoCENenhumArquivoEMontado() {
        String t = tudo(SistemaOperacional.WINDOWS);
        assertTrue(t.contains("disco C: inteiro"), t);
        assertTrue(t.contains("nenhum arquivo do computador"), t);
        assertFalse(t.contains("/etc/hostname"), t);
    }

    @Test
    void noLinuxODockerEquivaleARoot() {
        assertTrue(tudo(SistemaOperacional.LINUX).contains("administrador (root)"));
    }

    @Test
    void oAvisoCombinaComOQueODockerRunMontaDeVerdade() {
        PacoteDoAgente pacote = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {1});
        ParametrosDoLaboratorio p = ParametrosDoLaboratorio.padrao(7070, "/Users/ana/LabLocal/projects");
        List<String> mac = ComandosLocais.subirContainer(p, pacote, new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", java.util.Map.of()).montagemDaMaquina(), "0");
        assertTrue(mac.contains("/var/run/docker.sock:/var/run/docker.sock") && mac.contains("host")
            && mac.contains("type=bind,source=/etc/passwd,target=/host/etc/passwd,readonly"));
        assertEquals(3, mac.stream().filter("--mount"::equals).count(), "só os três arquivos do aviso");
        assertFalse(mac.contains("/:/host:ro"));
        List<String> win = ComandosLocais.subirContainer(p, pacote, new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", java.util.Map.of()).montagemDaMaquina(), "0");
        assertFalse(win.contains("--mount"), "no Windows o aviso não promete arquivo nenhum: " + win);
    }
}
