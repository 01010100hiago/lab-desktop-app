package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import br.com.labdesktop.plataforma.SistemaOperacional;

/** O que muda de um sistema para outro, como texto puro: os três sistemas se testam num Mac. */
class PlataformaLocalTest {

    private final PlataformaLocal mac = new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of());
    private final PlataformaLocal windows = new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana",
        Map.of("ProgramFiles", "C:\\Program Files", "SystemRoot", "C:\\Windows", "LOCALAPPDATA", "C:\\Users\\ana\\AppData\\Local"));
    private final PlataformaLocal linux = new PlataformaLocal(SistemaOperacional.LINUX, "/home/ana", Map.of());

    @Test
    void sistemaPeloNomeDaJvm() {
        assertEquals(SistemaOperacional.MAC, SistemaOperacional.de("Mac OS X"));
        assertEquals(SistemaOperacional.WINDOWS, SistemaOperacional.de("Windows 11"));
        assertEquals(SistemaOperacional.LINUX, SistemaOperacional.de("Linux"));
        assertEquals(SistemaOperacional.LINUX, SistemaOperacional.de("FreeBSD"));
        assertEquals(SistemaOperacional.LINUX, SistemaOperacional.de(null));
    }

    @Test
    void aPastaDoLaboratorioFicaNaPastaDoUsuarioSemCaminhoDeMacFixo() {
        assertEquals("/Users/ana/LabLocal/projects", mac.pastaDeProjetosPadrao());
        assertEquals("C:\\Users\\ana\\LabLocal\\projects", windows.pastaDeProjetosPadrao());
        assertEquals("/home/ana/LabLocal", linux.pastaDoLaboratorio());
        for (PlataformaLocal p : List.of(mac, windows, linux)) {
            assertFalse(p.pastaDeProjetosPadrao().contains("Library"), "nada de ~/Library fixo");
        }
    }

    @Test
    void asPastasDeProgramasTemOQueOAppAbertoPeloFinderNaoTemNoPath() {
        assertTrue(mac.diretoriosDeProgramas().containsAll(List.of("/usr/local/bin", "/opt/homebrew/bin", "/Applications/Docker.app/Contents/Resources/bin")));
        assertTrue(windows.diretoriosDeProgramas().contains("C:\\Program Files\\Docker\\Docker\\resources\\bin"));
        assertEquals(":", mac.separadorDoPath());
        assertEquals(";", windows.separadorDoPath());
        assertEquals(List.of("/usr/bin", "/bin"), mac.pastasDoPath("/usr/bin::/bin"));
    }

    @Test
    void abrirODockerNuncaInstalaENaoUsaOsascript() {
        assertEquals(List.of("open", "-a", "Docker"), mac.comandoParaAbrirODocker(true, false));
        assertEquals(List.of("colima", "start"), mac.comandoParaAbrirODocker(false, true));
        assertEquals(List.of(), mac.comandoParaAbrirODocker(false, false));
        assertEquals(List.of("C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe"), windows.comandoParaAbrirODocker(true, false));
        assertEquals(List.of(), linux.comandoParaAbrirODocker(true, true), "no Linux ligar o serviço pede administrador: só orientação");
        assertTrue(linux.orientacaoParaAbrirODocker().contains("systemctl"));
    }

    @Test
    void oDockerRunMontaSoOsTresArquivosDaMaquinaNoMacENoLinuxENadaNoWindows() {
        List<String> tres = List.of("--mount", "type=bind,source=/etc/hostname,target=/host/etc/hostname,readonly",
            "--mount", "type=bind,source=/etc/os-release,target=/host/etc/os-release,readonly",
            "--mount", "type=bind,source=/etc/passwd,target=/host/etc/passwd,readonly");
        assertEquals(tres, mac.montagemDaMaquina());
        assertEquals(tres, linux.montagemDaMaquina());
        assertEquals(List.of(), windows.montagemDaMaquina());
        assertFalse(mac.montagemDaMaquina().contains("/:/host:ro"), "a raiz do disco nunca mais entra no container");
        assertTrue(windows.exigeVirtualizacao());
        assertFalse(mac.exigeVirtualizacao());
    }

    @Test
    void oLocalizadorAchaNoPathENasPastasConhecidas() {
        // comparados como Path: o Windows escreve esses caminhos com barra invertida, e o que vale é o mesmo caminho nos dois
        java.util.Set<java.nio.file.Path> existentes = java.util.Set.of(java.nio.file.Path.of("/usr/local/bin/docker"), java.nio.file.Path.of("/opt/homebrew/bin/brew"));
        LocalizadorDeProgramas l = new LocalizadorDeProgramas(SistemaOperacional.MAC, List.of("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"),
            existentes::contains);
        assertEquals(java.nio.file.Path.of("/usr/local/bin/docker"), l.localizar("docker").orElseThrow());
        assertEquals(java.nio.file.Path.of("/opt/homebrew/bin/brew"), l.localizar("brew").orElseThrow());
        assertTrue(l.localizar("git").isEmpty());
        assertTrue(l.localizar("../docker").isEmpty(), "só nome de programa, nunca caminho");
        assertTrue(l.localizar("/usr/local/bin/docker").isEmpty());
        assertTrue(l.localizar("").isEmpty());
    }
}
