package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Onde estão as ferramentas em cada sistema, com um sistema de arquivos de mentira: o Windows e o Linux provados num Mac. */
class DescobertaDeFerramentasTest {

    private static DescobertaDeFerramentas de(SistemaOperacional so, String casa, Map<String, String> variaveis, String... existentes) {
        Set<String> existe = Set.of(existentes);
        return new DescobertaDeFerramentas(AmbienteDoSistema.de(so, casa, "ana", variaveis), existe::contains);
    }

    // ----- Mac -----

    @Test
    void macAchaNasPastasConhecidasMesmoComOPathMinimoDeUmAppAbertoPeloFinder() {
        var d = de(SistemaOperacional.MAC, "/Users/ana", Map.of("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"),
            "/opt/homebrew/bin/aws", "/opt/homebrew/bin/cloudflared", "/usr/local/sessionmanagerplugin/bin/session-manager-plugin");
        assertEquals(Optional.of("/opt/homebrew/bin/aws"), d.localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("/opt/homebrew/bin/cloudflared"), d.localizar(FerramentaExterna.CLOUDFLARED));
        assertEquals(Optional.of("/usr/local/sessionmanagerplugin/bin/session-manager-plugin"), d.localizar(FerramentaExterna.PLUGIN_SSM));
    }

    @Test
    void macAchaNoUsrLocalBinENoDiretorioDoInstaladorDaAws() {
        assertEquals(Optional.of("/usr/local/bin/aws"), de(SistemaOperacional.MAC, "/Users/ana", Map.of(), "/usr/local/bin/aws").localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("/usr/local/aws-cli/aws"), de(SistemaOperacional.MAC, "/Users/ana", Map.of(), "/usr/local/aws-cli/aws").localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("/usr/local/bin/cloudflared"), de(SistemaOperacional.MAC, "/Users/ana", Map.of(), "/usr/local/bin/cloudflared").localizar(FerramentaExterna.CLOUDFLARED));
    }

    @Test
    void oPathVemAntesDasPastasConhecidas() {
        var d = de(SistemaOperacional.MAC, "/Users/ana", Map.of("PATH", "/meu/bin:/usr/bin"), "/opt/homebrew/bin/aws", "/meu/bin/aws");
        assertEquals(Optional.of("/meu/bin/aws"), d.localizar(FerramentaExterna.AWS));
        assertEquals(List.of("/meu/bin", "/usr/bin"), d.pastasDoPath());
    }

    @Test
    void naoAchandoDevolveVazio() {
        assertEquals(Optional.empty(), de(SistemaOperacional.MAC, "/Users/ana", Map.of("PATH", "/usr/bin")).localizar(FerramentaExterna.AWS));
        assertEquals(Optional.empty(), de(SistemaOperacional.MAC, "/Users/ana", Map.of()).localizar(FerramentaExterna.PLUGIN_SSM));
        // o plugin NÃO é achado na pasta de outro programa que não seja a dele
        assertEquals(Optional.empty(), de(SistemaOperacional.MAC, "/Users/ana", Map.of(), "/usr/local/aws-cli/session-manager-plugin").localizar(FerramentaExterna.PLUGIN_SSM));
    }

    // ----- Windows -----

    @Test
    void windowsAchaNasPastasConhecidasComExe() {
        var d = de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of(),
            "C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe", "C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe",
            "C:\\Program Files (x86)\\cloudflared\\cloudflared.exe");
        assertEquals(Optional.of("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe"), d.localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe"), d.localizar(FerramentaExterna.PLUGIN_SSM));
        assertEquals(Optional.of("C:\\Program Files (x86)\\cloudflared\\cloudflared.exe"), d.localizar(FerramentaExterna.CLOUDFLARED));
    }

    @Test
    void windowsAchaOCloudflaredEmProgramFilesSemOX86() {
        var d = de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of(), "C:\\Program Files\\cloudflared\\cloudflared.exe");
        assertEquals(Optional.of("C:\\Program Files\\cloudflared\\cloudflared.exe"), d.localizar(FerramentaExterna.CLOUDFLARED));
    }

    @Test
    void windowsUsaAsVariaveisDoSistemaEOPathComPontoEVirgulaEAspas() {
        var d = de(SistemaOperacional.WINDOWS, "D:\\Pessoas\\ana", Map.of("ProgramFiles", "D:\\Programas", "Path", "\"E:\\Ferramentas\";C:\\Windows"),
            "D:\\Programas\\Amazon\\AWSCLIV2\\aws.exe", "E:\\Ferramentas\\cloudflared.exe");
        assertEquals(Optional.of("D:\\Programas\\Amazon\\AWSCLIV2\\aws.exe"), d.localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("E:\\Ferramentas\\cloudflared.exe"), d.localizar(FerramentaExterna.CLOUDFLARED));
        assertEquals(List.of("E:\\Ferramentas", "C:\\Windows"), d.pastasDoPath());
    }

    @Test
    void windowsSoAceitaExeNuncaCmdNemBatNemSemExtensao() {
        var d = de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of("PATH", "C:\\bin"),
            "C:\\bin\\aws.cmd", "C:\\bin\\aws.bat", "C:\\bin\\aws", "C:\\bin\\cloudflared.cmd");
        assertEquals(Optional.empty(), d.localizar(FerramentaExterna.AWS), "um .cmd ou .bat passaria por um interpretador de comandos");
        assertEquals(Optional.empty(), d.localizar(FerramentaExterna.CLOUDFLARED));
        assertEquals("aws.exe", d.nomeDoArquivo(FerramentaExterna.AWS));
        assertEquals("session-manager-plugin.exe", d.nomeDoArquivo(FerramentaExterna.PLUGIN_SSM));
    }

    // ----- Linux -----

    @Test
    void linuxAchaNasPastasConhecidasInclusiveALocalDoUsuarioESnap() {
        assertEquals(Optional.of("/usr/local/bin/aws"), de(SistemaOperacional.LINUX, "/home/ana", Map.of(), "/usr/local/bin/aws").localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("/usr/bin/cloudflared"), de(SistemaOperacional.LINUX, "/home/ana", Map.of(), "/usr/bin/cloudflared").localizar(FerramentaExterna.CLOUDFLARED));
        assertEquals(Optional.of("/home/ana/.local/bin/aws"), de(SistemaOperacional.LINUX, "/home/ana", Map.of(), "/home/ana/.local/bin/aws").localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("/snap/bin/aws"), de(SistemaOperacional.LINUX, "/home/ana", Map.of(), "/snap/bin/aws").localizar(FerramentaExterna.AWS));
        assertEquals(Optional.of("/usr/local/sessionmanagerplugin/bin/session-manager-plugin"),
            de(SistemaOperacional.LINUX, "/home/ana", Map.of(), "/usr/local/sessionmanagerplugin/bin/session-manager-plugin").localizar(FerramentaExterna.PLUGIN_SSM));
    }

    @Test
    void linuxNaoUsaExeEOPathUsaDoisPontos() {
        var d = de(SistemaOperacional.LINUX, "/home/ana", Map.of("PATH", "/opt/x/bin:/usr/bin"), "/opt/x/bin/cloudflared", "/opt/x/bin/cloudflared.exe");
        assertEquals(Optional.of("/opt/x/bin/cloudflared"), d.localizar(FerramentaExterna.CLOUDFLARED));
        assertEquals("cloudflared", d.nomeDoArquivo(FerramentaExterna.CLOUDFLARED));
        assertEquals(List.of("/opt/x/bin", "/usr/bin"), d.pastasDoPath());
    }

    @Test
    void entradasRelativasDoPathSaoIgnoradasNosTresSistemas() {
        var mac = de(SistemaOperacional.MAC, "/Users/ana", Map.of("PATH", ".:bin::/usr/local/bin:../x:~/bin"), "./aws", "bin/aws", "/usr/local/bin/aws");
        assertEquals(List.of("/usr/local/bin"), mac.pastasDoPath(), "só a absoluta");
        assertEquals(Optional.of("/usr/local/bin/aws"), mac.localizar(FerramentaExterna.AWS));
        var semAbsoluta = de(SistemaOperacional.LINUX, "/home/ana", Map.of("PATH", ".:bin"), "./cloudflared", "bin/cloudflared");
        assertEquals(List.of(), semAbsoluta.pastasDoPath());
        assertEquals(Optional.empty(), semAbsoluta.localizar(FerramentaExterna.CLOUDFLARED), "um cloudflared na pasta de trabalho não conta");
        var win = de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of("Path", ".;bin;C:\\Ferramentas;\\\\servidor\\pasta;D:relativo"), "C:\\Ferramentas\\aws.exe", "bin\\aws.exe");
        assertEquals(List.of("C:\\Ferramentas", "\\\\servidor\\pasta"), win.pastasDoPath());
        assertEquals(Optional.of("C:\\Ferramentas\\aws.exe"), win.localizar(FerramentaExterna.AWS));
    }

    @Test
    void asPastasDeBuscaNaoRepetemEComecamPeloPath() {
        var d = de(SistemaOperacional.MAC, "/Users/ana", Map.of("PATH", "/usr/local/bin:/meu/bin"));
        List<String> pastas = d.pastasDeBusca(FerramentaExterna.AWS);
        assertEquals("/usr/local/bin", pastas.get(0));
        assertEquals(pastas.size(), Set.copyOf(pastas).size(), "sem repetição: " + pastas);
        assertTrue(pastas.contains("/opt/homebrew/bin"));
    }

    @Test
    void pastaDeCortaNoUltimoSeparadorDeQualquerSistema() {
        assertEquals("/usr/local/sessionmanagerplugin/bin", DescobertaDeFerramentas.pastaDe("/usr/local/sessionmanagerplugin/bin/session-manager-plugin"));
        assertEquals("C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin", DescobertaDeFerramentas.pastaDe("C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe"));
        assertEquals("", DescobertaDeFerramentas.pastaDe("aws"));
    }
}
