package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import br.com.labdesktop.plataforma.SistemaOperacional;

/** O caminho da pasta dos projetos como o container do agente a enxerga, nos três sistemas (a regra do Windows é texto puro, testada num Mac). */
class MapeamentoDeCaminhosTest {

    @Test
    void noMacENoLinuxOCaminhoEOMesmo() {
        assertEquals("/Users/ana/LabLocal/projects", MapeamentoDeCaminhos.doContainer(SistemaOperacional.MAC, "/Users/ana/LabLocal/projects"));
        assertEquals("/home/ana/LabLocal/projects", MapeamentoDeCaminhos.doContainer(SistemaOperacional.LINUX, "/home/ana/LabLocal/projects"));
        assertEquals("/Users/Ana Maria/Lab Local/projects", MapeamentoDeCaminhos.doContainer(SistemaOperacional.MAC, "/Users/Ana Maria/Lab Local/projects"),
            "espaço e acento valem (o -v vai numa lista de argumentos, sem shell)");
        assertEquals("/Users/José/LabLocal", MapeamentoDeCaminhos.doContainer(SistemaOperacional.MAC, "/Users/José/LabLocal/"));
    }

    @Test
    void noWindowsODiscoViraOCaminhoDaMaquinaVirtualDoDockerDesktop() {
        assertEquals("/run/desktop/mnt/host/c/Users/ana/LabLocal/projects",
            MapeamentoDeCaminhos.doContainer(SistemaOperacional.WINDOWS, "C:\\Users\\ana\\LabLocal\\projects"));
        assertEquals("/run/desktop/mnt/host/d/Dados/Lab", MapeamentoDeCaminhos.doContainer(SistemaOperacional.WINDOWS, "D:/Dados/Lab"));
        assertEquals("/run/desktop/mnt/host/c", MapeamentoDeCaminhos.doContainer(SistemaOperacional.WINDOWS, "C:\\"));
    }

    @Test
    void caminhoQueQuebraria_oVDoDockerEhRecusado() {
        for (String ruim : List.of("/Users/ana/a,b", "/Users/ana/a:b", "/Users/ana/../etc", "/Users/ana/\"x\"", "/Users/ana/a\nb", "relativo/x", "", " ")) {
            assertThrows(IllegalArgumentException.class, () -> MapeamentoDeCaminhos.doContainer(SistemaOperacional.MAC, ruim), "recusa: " + ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> MapeamentoDeCaminhos.doContainer(SistemaOperacional.WINDOWS, "/Users/ana"));
        assertThrows(IllegalArgumentException.class, () -> MapeamentoDeCaminhos.doContainer(SistemaOperacional.WINDOWS, "C:\\Users\\a,b"));
    }
}
