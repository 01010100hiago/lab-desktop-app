package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.PacoteDoAgente;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os comandos do laboratório local: listas de argumentos (nunca texto de shell), a fronteira leitura x escrita e o que o {@code docker run} leva. */
class ComandosLocaisTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {1, 2, 3});
    private final ParametrosDoLaboratorio p = ParametrosDoLaboratorio.padrao(7070, "/Users/ana/LabLocal/projects");

    private List<List<String>> leituras() {
        return List.of(ComandosLocais.versaoDoDocker(), ComandosLocais.versaoDoComposeDoDocker(), ComandosLocais.versaoDoGit(), ComandosLocais.estadoDoWsl(),
            ComandosLocais.virtualizacaoDoWindows(), ComandosLocais.lerRede(p), ComandosLocais.lerContainersNaRede(p), ComandosLocais.lerContainer(p),
            ComandosLocais.lerContainerPeloNome(p.containerAnterior()), ComandosLocais.lerVolume(p), ComandosLocais.lerImagem(p, PACOTE.versao()),
            ComandosLocais.lerLogs(p), ComandosLocais.lerRegistroDoAgente("painel-lab"), ComandosLocais.lerEstadoDoContainer("painel-lab"));
    }

    private List<List<String>> escritas() {
        return List.of(ComandosLocais.criarRede(p), ComandosLocais.removerRede(p), ComandosLocais.construirImagem(p, PACOTE),
            ComandosLocais.subirContainer(p, PACOTE, ComandosRemotos.montagemDosArquivosDaMaquina(), "0"),
            ComandosLocais.lerGidDoSoquete(p, PACOTE), ComandosLocais.acertarDonoDosDados(p, PACOTE),
            ComandosLocais.removerContainer("painel-lab"), ComandosLocais.renomearContainer("painel-lab", "painel-lab-anterior"),
            ComandosLocais.pararContainer("painel-lab"), ComandosLocais.iniciarContainer("painel-lab"), ComandosLocais.removerVolume(p),
            ComandosLocais.removerImagem(p, PACOTE.versao()), ComandosLocais.criarVolume(p));
    }

    @Test
    void nenhumArgumentoLevaAspasPoisNoWindowsElasChegamQuebradasAoDockerExe() {
        List<List<String>> todos = new ArrayList<>(leituras());
        todos.addAll(escritas());
        for (List<String> c : todos) {
            for (String a : c) {
                assertFalse(a.contains("\"") || a.contains("'"), "argumento com aspas: " + a + " em " + c);
            }
        }
    }

    @Test
    void osRotulosSaemPeloFormatoLabelsESaoLidosPeloTexto() {
        assertEquals("1", LeituraDoDocker.rotulo("a=b,br.com.labdesktop.local=1,c=d", ComandosLocais.ROTULO_LOCAL));
        assertEquals("", LeituraDoDocker.rotulo("a=b,xbr.com.labdesktop.local=1", ComandosLocais.ROTULO_LOCAL), "a chave tem de casar inteira");
        assertEquals("", LeituraDoDocker.rotulo("", ComandosLocais.ROTULO_LOCAL));
        List<LeituraDoDocker.Container> l = LeituraDoDocker.containers(
            "painel-lab|org.x=a|b,br.com.labdesktop.agente=0.1.0,br.com.labdesktop.local=1|running|127.0.0.1:7070->7070/tcp\n");
        assertEquals(1, l.size());
        assertEquals("painel-lab", l.get(0).nome());
        assertEquals("0.1.0", l.get(0).rotuloDoAgente());
        assertEquals("1", l.get(0).rotuloLocal());
        assertEquals("running", l.get(0).estado());
        assertTrue(l.get(0).publicaNoLoopback(7070));
        assertFalse(l.get(0).publicaNoLoopback(7071));
        assertEquals(7070, l.get(0).portaNoLoopback());
        assertFalse(LeituraDoDocker.containers("x|a=b|running|0.0.0.0:7070->7070/tcp, :::7070->7070/tcp\n").get(0).publicaNoLoopback(7070),
            "publicada em todas as interfaces não é só no loopback");
        assertTrue(LeituraDoDocker.containers("").isEmpty());
        assertTrue(LeituraDoDocker.redes("edge|\n").get(0).rotuloLocal().isEmpty());
    }

    @Test
    void todaLeituraELeituraEToda_escritaNao() {
        for (List<String> c : leituras()) {
            assertTrue(ComandosLocais.ehLeitura(c), "devia ser leitura: " + c);
        }
        for (List<String> c : escritas()) {
            assertFalse(ComandosLocais.ehLeitura(c), "NÃO pode contar como leitura: " + c);
        }
        for (List<String> c : List.of(List.of("brew", "install", "--cask", "docker-desktop"), List.of("winget", "install", "x"), List.of("open", "-a", "Docker"),
            List.of("docker", "exec", "-i", "painel-lab", "node", "acesso.js", "token-registrar"), List.of("rm", "-rf", "/"))) {
            assertFalse(ComandosLocais.ehLeitura(c), c.toString());
        }
        assertFalse(ComandosLocais.ehLeitura(null));
        assertFalse(ComandosLocais.ehLeitura(List.of()));
    }

    @Test
    void leituraComUmArgumentoAMaisOuForaDaRegraNaoELeitura() {
        for (List<String> c : leituras()) {
            List<String> mais = new ArrayList<>(c);
            mais.add("--force");
            assertFalse(ComandosLocais.ehLeitura(mais), "um argumento a mais: " + mais);
            List<String> concatenado = new ArrayList<>(c);
            concatenado.set(concatenado.size() - 1, concatenado.get(concatenado.size() - 1) + "; rm -rf /");
            assertFalse(ComandosLocais.ehLeitura(concatenado), "texto de shell colado ao último argumento: " + concatenado);
        }
        assertFalse(ComandosLocais.ehLeitura(List.of("docker", "ps", "-a", "--filter", "name=^x$", "--format", "{{.Names}}")), "formato diferente");
        assertFalse(ComandosLocais.ehLeitura(List.of("docker", "inspect", "-f", "{{.State.Status}}", "Nome Com Espaço")));
        assertFalse(ComandosLocais.ehLeitura(List.of("docker", "logs", "--tail", "40", "../x")));
    }

    @Test
    void oDockerRunPublicaSoNoLoopbackNaoUsaRedeDoHostExigeTokenEMontaSoOsTresArquivos() {
        List<String> r = ComandosLocais.subirContainer(p, PACOTE, ComandosRemotos.montagemDosArquivosDaMaquina(), "0");
        assertEquals(List.of("docker", "run", "-d", "--name", "painel-lab", "--restart", "unless-stopped", "-p", "127.0.0.1:7070:7070", "--pid", "host",
            "--user", "10001:10001", "--group-add", "0", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only", "--tmpfs", "/tmp",
            "--label", "br.com.labdesktop.agente=0.1.0-abc1234", "--label", "br.com.labdesktop.local=1",
            "--log-opt", "max-size=10m", "--log-opt", "max-file=3", "-e", "PORT=7070", "-e", "PAINEL_PERFIL=monitor", "-e", "PAINEL_EXIGE_TOKEN=1",
            "-e", "PAINEL_ESCUTA=todas", "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "--mount", "type=bind,source=/etc/hostname,target=/host/etc/hostname,readonly",
            "--mount", "type=bind,source=/etc/os-release,target=/host/etc/os-release,readonly",
            "--mount", "type=bind,source=/etc/passwd,target=/host/etc/passwd,readonly",
            "-v", "painel-lab-dados:/srv/painel/dados", "labdesktop-agente:0.1.0-abc1234"), r);
        assertFalse(r.contains("--privileged") || r.contains("--cap-add"), "nenhuma capacidade de volta: " + r);
        assertEquals(List.of("docker", "run", "--rm", "--network", "none", "-v", "/var/run/docker.sock:/var/run/docker.sock", "labdesktop-agente:0.1.0-abc1234",
            "stat", "-c", "%g", "/var/run/docker.sock"), ComandosLocais.lerGidDoSoquete(p, PACOTE));
        assertEquals(List.of("docker", "run", "--rm", "--network", "none", "--user", "0:0", "-v", "painel-lab-dados:/srv/painel/dados",
            "labdesktop-agente:0.1.0-abc1234", "chown", "-R", "10001:10001", "/srv/painel/dados"), ComandosLocais.acertarDonoDosDados(p, PACOTE));
        assertThrows(IllegalArgumentException.class, () -> ComandosLocais.subirContainer(p, PACOTE, List.of(), "0; rm -rf /"), "o grupo é só número");
        assertFalse(r.contains("/:/host:ro"), "a raiz do disco não entra");
        assertFalse(r.stream().anyMatch(a -> a.contains("LabLocal")), "a pasta dos projetos não entra no container do agente de monitoramento: " + r);
        assertFalse(r.stream().anyMatch(a -> a.startsWith("br.com.labdesktop.pasta")), "sem o rótulo da pasta");
        assertFalse(r.contains("--network"), "o Docker Desktop não tem rede de host de verdade");
        assertTrue(r.contains("127.0.0.1:7070:7070"), "nunca 0.0.0.0");
        assertFalse(r.stream().anyMatch(a -> a.startsWith("0.0.0.0")), r.toString());
    }

    @Test
    void oBuildLevaOPacotePelaEntradaSemCaminhoDoSistema() {
        assertEquals(List.of("docker", "build", "--label", "br.com.labdesktop.local=1", "-f", "Dockerfile.monitor", "-t", "labdesktop-agente:0.1.0-abc1234", "-"), ComandosLocais.construirImagem(p, PACOTE));
    }

    @Test
    void nenhumArgumentoTemControleENenhumaLinhaViraTextoDeShell() {
        List<List<String>> todas = new ArrayList<>(leituras());
        todas.addAll(escritas());
        for (List<String> c : todas) {
            assertFalse(c.isEmpty());
            assertFalse(c.get(0).contains(" "), "o primeiro item é o programa, não uma linha: " + c);
            for (String a : c) {
                assertTrue(a.chars().noneMatch(ch -> ch < 0x20 || ch == 0x7F), "controle em: " + c);
            }
            assertFalse(c.get(0).equals("sh") || c.get(0).equals("bash") || c.get(0).equals("cmd"), "sem shell: " + c);
        }
    }

    @Test
    void nomesPassamPorRegexAntesDeEntrarEmUmComando() {
        for (String ruim : List.of("Painel", "a b", "-x", "a;b", "a$(b)", "", "x".repeat(60))) {
            assertThrows(IllegalArgumentException.class, () -> new ParametrosDoLaboratorio(ruim, 7070, "edge", "img", "/x"), ruim);
            assertThrows(IllegalArgumentException.class, () -> new ParametrosDoLaboratorio("ok", 7070, ruim, "img", "/x"), ruim);
            assertThrows(IllegalArgumentException.class, () -> new ParametrosDoLaboratorio("ok", 7070, "edge", ruim, "/x"), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ComandosLocais.removerContainer("ruim; rm"));
        assertThrows(IllegalArgumentException.class, () -> ComandosLocais.lerEstadoDoContainer("A b"));
        assertThrows(IllegalArgumentException.class, () -> new ParametrosDoLaboratorio("ok", 80, "edge", "img", "/x"), "porta baixa");
        assertThrows(IllegalArgumentException.class, () -> new ParametrosDoLaboratorio("ok", 7070, "edge", "img", " "));
    }
}
