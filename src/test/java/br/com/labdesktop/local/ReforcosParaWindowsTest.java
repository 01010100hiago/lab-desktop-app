package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.projetos.EnvDoProjeto;
import br.com.labdesktop.projetos.GabaritoDoProjeto;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O que o lado local reforça SEM um Windows: só .exe, argumentos validados antes de rodar, fim de linha LF ao escrever, nada do app depende de shell. */
class ReforcosParaWindowsTest {

    // ---- só .exe ----

    @Test
    void noWindowsSoProcuraExeENuncaCmdNemBat() {
        // existem docker.cmd e docker.bat na pasta, mas rodar um .cmd passa os argumentos pelo cmd.exe (& | ^ % viram comando): o app não os aceita
        LocalizadorDeProgramas soScripts = new LocalizadorDeProgramas(SistemaOperacional.WINDOWS, List.of("C:\\Tools"),
            p -> p.getFileName().toString().equals("docker.cmd") || p.getFileName().toString().equals("docker.bat"));
        assertEquals(Optional.empty(), soScripts.localizar("docker"));
        LocalizadorDeProgramas comExe = new LocalizadorDeProgramas(SistemaOperacional.WINDOWS, List.of("C:\\Tools"), p -> p.getFileName().toString().equals("docker.exe"));
        assertTrue(comExe.localizar("docker").isPresent());
        assertEquals("docker.exe", comExe.localizar("docker").orElseThrow().getFileName().toString());
        // nome com barra, ponto e vírgula ou vazio nunca é procurado
        assertEquals(Optional.empty(), comExe.localizar("..\\docker"));
        assertEquals(Optional.empty(), comExe.localizar("C:\\x\\docker"));
        assertEquals(Optional.empty(), comExe.localizar(""));
    }

    // ---- ProcessosLocais no Windows ----

    @Test
    void noWindowsOProcessoSoNasceSeOsArgumentosFazemAIdaEVolta() throws Exception {
        PlataformaLocal windows = new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of());
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        // o localizador de um Windows de mentira nunca acharia o java do Mac: a rota do caminho explícito (como nos testes reais) o resolve
        ProcessosLocais p = new ProcessosLocais(new LocalizadorDeProgramas(SistemaOperacional.WINDOWS, List.of(), x -> false), windows);
        assertThrows(IllegalArgumentException.class, () -> p.executar(List.of(java.toString(), "-version", "--format", "\"{{.x}}\"x"), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> p.executar(List.of(java.toString(), ""), Duration.ofSeconds(5)));
        // o mesmo comando num Mac não tem essa regra (a lista chega inteira ao programa)
        PlataformaLocal mac = new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of());
        ProcessosLocais pm = new ProcessosLocais(new LocalizadorDeProgramas(SistemaOperacional.MAC, List.of(), x -> false), mac);
        assertEquals(0, pm.executar(List.of(java.toString(), "-version"), Duration.ofSeconds(30)).status());
    }

    // ---- LF ao escrever ----

    @Test
    void tudoQueOAppEscreveParaUmProjetoNovoEmLfSemCr() {
        for (Map.Entry<String, String> e : GabaritoDoProjeto.arquivos("meu-app", LocalDate.of(2026, 10, 28)).entrySet()) {
            assertFalse(e.getValue().contains("\r"), e.getKey() + " tem CR");
        }
        assertFalse(GabaritoDoProjeto.composeVazio("meu-app").contains("\r"));
        Map<String, String> novas = new LinkedHashMap<>();
        novas.put("A", "1");
        novas.put("B", "2");
        assertFalse(EnvDoProjeto.acrescentar(null, novas).texto().contains("\r"), ".env novo é só LF");
        assertFalse(EnvDoProjeto.acrescentar("X=1\n", novas).texto().contains("\r"), ".env do usuário em LF continua em LF");
    }

    @Test
    void envDoDonoComCrlfRecebeAsLinhasNovasComCrlfSemTocarNoQueJaEra() {
        Map<String, String> novas = new LinkedHashMap<>();
        novas.put("SENHA", "abc");
        String antes = "X=1\r\nY=2\r\n";
        String depois = EnvDoProjeto.acrescentar(antes, novas).texto();
        assertTrue(depois.startsWith(antes), "o que já havia não mudou");
        assertEquals("X=1\r\nY=2\r\nSENHA=abc\r\n", depois, "nenhum LF solto no meio de um arquivo CRLF");
        // sem quebra final: a primeira linha nova também sai com o fim de linha do arquivo
        assertEquals("X=1\r\nSENHA=abc\r\n", EnvDoProjeto.acrescentar("X=1\r\n", novas).texto());
    }

    // ---- git no Windows ----

    @Test
    void oGitDoWindowsNuncaPedeSenhaNemAbreJanelaDoGerenciadorDeCredenciais() {
        Map<String, String> env = br.com.labdesktop.projetos.ComandosDeProjeto.ambienteDoGit();
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("never", env.get("GCM_INTERACTIVE"), "o Git Credential Manager do Windows abriria uma janela de login e travaria o app");
        assertEquals("https:ssh", env.get("GIT_ALLOW_PROTOCOL"));
    }
}
