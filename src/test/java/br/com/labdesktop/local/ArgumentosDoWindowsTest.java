package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.PacoteDoAgente;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Os argumentos dos comandos locais no WINDOWS: o corte da linha segue a tabela da Microsoft ("Parsing C command-line arguments"), a montagem é a do OpenJDK para {@code .exe}, e TODO
 * comando do app faz a ida e volta sem mudar (nada de aspas dentro de argumento, nenhum argumento vazio). Provado num Mac, sem rodar nada.
 */
class ArgumentosDoWindowsTest {

    // ---- o corte: os exemplos da documentação da Microsoft ----

    @Test
    void corteSegueATabelaDaDocumentacaoDaMicrosoft() {
        assertEquals(List.of("a b c", "d", "e"), ArgumentosDoWindows.cortar("\"a b c\" d e"));
        assertEquals(List.of("ab\"c", "\\", "d"), ArgumentosDoWindows.cortar("\"ab\\\"c\" \"\\\\\" d"));
        assertEquals(List.of("a\\\\\\b", "de fg", "h"), ArgumentosDoWindows.cortar("a\\\\\\b d\"e f\"g h"));
        assertEquals(List.of("a\\\"b", "c", "d"), ArgumentosDoWindows.cortar("a\\\\\\\"b c d"));
        assertEquals(List.of("a\\\\b c", "d", "e"), ArgumentosDoWindows.cortar("a\\\\\\\\\"b c\" d e"));
        assertEquals(List.of("ab\" c d"), ArgumentosDoWindows.cortar("a\"b\"\" c d"));
        assertEquals(List.of(), ArgumentosDoWindows.cortar("   "));
        assertEquals(List.of("a", "b"), ArgumentosDoWindows.cortar("a\tb"));
    }

    @Test
    void aMontagemDoJavaPoeAspasSoEmVoltaDoQueTemEspacoEDobraABarraFinal() {
        assertEquals(" version --format {{.Server.Version}}", ArgumentosDoWindows.linhaDoJava(List.of("docker", "version", "--format", "{{.Server.Version}}")));
        assertEquals(" -v \"C:\\Users\\João Silva\\x:/d\"", ArgumentosDoWindows.linhaDoJava(List.of("docker", "-v", "C:\\Users\\João Silva\\x:/d")));
        assertEquals(" \"C:\\pasta com espaco\\\\\"", ArgumentosDoWindows.linhaDoJava(List.of("p", "C:\\pasta com espaco\\")), "a barra final é dobrada para não escapar a aspa de fechar");
        assertEquals(" \"ja entre aspas\"", ArgumentosDoWindows.linhaDoJava(List.of("p", "\"ja entre aspas\"")));
    }

    // ---- a ida e volta ----

    @Test
    void argumentosComuns_comEspacoAcentoEBarraFinal_fazemAIdaEVolta() {
        for (List<String> c : List.of(
            List.of("docker", "ps", "-a", "--filter", "name=^painel-lab$", "--format", "{{.Names}}|{{.Labels}}|{{.State}}"),
            List.of("docker", "run", "-v", "C:\\Users\\João Silva\\LabLocal\\projects:/run/desktop/mnt/host/c/Users/João Silva/LabLocal/projects"),
            List.of("docker", "compose", "-p", "meu-app", "-f", "C:\\Users\\Ana Paula\\LabLocal\\projects\\meu app\\docker-compose.yml", "up", "-d"),
            List.of("docker", "x", "C:\\pasta com espaco\\"),
            List.of("git", "clone", "--", "https://github.com/ana/repo.git", "C:\\Users\\ana\\projetos\\repo"),
            List.of("wsl", "--status"),
            List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled"))) {
            assertDoesNotThrow(() -> ArgumentosDoWindows.validar(c), c.toString());
        }
    }

    @Test
    void aspasDentroDoArgumentoEArgumentoVazioSaoRecusadosOuViramOutraCoisa() {
        // o formato com aspas (o antigo): chegaria quebrado ao docker.exe
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoWindows.validar(List.of("docker", "ps", "--format", "\"{{.Names}}\"|x")));
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoWindows.validar(List.of("docker", "ps", "--format", "{{json .Labels}} \"x\"")));
        // vazio: o Java o escreve como nada, e o programa nunca o vê
        IllegalArgumentException vazio = assertThrows(IllegalArgumentException.class, () -> ArgumentosDoWindows.validar(List.of("docker", "run", "", "x")));
        assertTrue(vazio.getMessage().contains("argumento 2"), vazio.getMessage());
        // caractere de controle (quebra de linha) nunca passa
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoWindows.validar(List.of("docker", "run", "a\nb")));
        assertThrows(IllegalArgumentException.class, () -> ArgumentosDoWindows.validar(List.of("docker", "run", "a\rb")));
    }

    @Test
    void todoComandoDoLaboratorioLocalFazAIdaEVoltaNoWindowsComUmaPastaComEspacoEAcento() {
        String pasta = "C:\\Users\\João Silva\\LabLocal\\projects";
        ParametrosDoLaboratorio p = ParametrosDoLaboratorio.padrao(7070, pasta);
        PacoteDoAgente pacote = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {1, 2, 3});
        String noContainer = MapeamentoDeCaminhos.doContainer(br.com.labdesktop.plataforma.SistemaOperacional.WINDOWS, pasta);
        List<List<String>> todos = new ArrayList<>(List.of(
            ComandosLocais.versaoDoDocker(), ComandosLocais.versaoDoComposeDoDocker(), ComandosLocais.versaoDoGit(), ComandosLocais.estadoDoWsl(), ComandosLocais.virtualizacaoDoWindows(),
            ComandosLocais.lerRede(p), ComandosLocais.lerContainersNaRede(p), ComandosLocais.lerContainer(p), ComandosLocais.lerVolume(p), ComandosLocais.lerImagensDoApp(p),
            ComandosLocais.lerImagem(p, "0.1.0-abc1234"), ComandosLocais.lerLogs(p), ComandosLocais.lerRegistroDoAgente(p.container()), ComandosLocais.lerEstadoDoContainer(p.container()),
            ComandosLocais.lerContainersDosProjetos(), ComandosLocais.criarRede(p), ComandosLocais.removerRede(p), ComandosLocais.construirImagem(p, pacote),
            ComandosLocais.subirContainer(p, pacote, List.of(), noContainer), ComandosLocais.removerContainer(p.container()), ComandosLocais.renomearContainer("a", "b"),
            ComandosLocais.pararContainer("a"), ComandosLocais.iniciarContainer("a"), ComandosLocais.criarVolume(p), ComandosLocais.removerVolume(p),
            ComandosLocais.removerImagem(p, "0.1.0-abc1234"), ComandosLocais.reiniciarContainerDoProjeto("meu-app-web-1")));
        for (ComandosLocais.AcaoDeProjeto acao : ComandosLocais.AcaoDeProjeto.values()) {
            todos.add(ComandosLocais.comporProjeto("meu-app", pasta + "\\meu app\\docker-compose.yml", acao));
        }
        for (List<String> c : todos) {
            assertDoesNotThrow(() -> ArgumentosDoWindows.validar(c), "ida e volta no Windows: " + c);
        }
        assertTrue(todos.size() > 25);
    }
}
