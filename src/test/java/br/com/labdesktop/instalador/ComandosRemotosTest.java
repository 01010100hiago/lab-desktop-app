package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os comandos fixos do instalador: a fronteira entre leitura e escrita, e que nada que o usuário ou o servidor digitem entra na linha. */
class ComandosRemotosTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {1, 2, 3});
    private final ParametrosDaInstalacao p = ParametrosDaInstalacao.padrao(7000);

    private List<String> leituras(String docker) {
        List<String> l = new ArrayList<>(List.of(ComandosRemotos.lerOsRelease(), ComandosRemotos.lerArquitetura(), ComandosRemotos.lerPermissoes(),
            ComandosRemotos.lerDocker(), ComandosRemotos.lerPorta(7000), ComandosRemotos.lerDisco(), ComandosRemotos.lerInternet(),
            ComandosRemotos.lerSeAPastaExiste(p), ComandosRemotos.lerDonoDaPasta(p), ComandosRemotos.lerVersaoExtraida(p), ComandosRemotos.lerSha256DoPacote(p, PACOTE),
            ComandosRemotos.lerSituacaoDoAgente("painel-lab")));
        l.add(ComandosRemotos.lerContainer(docker, p));
        l.add(ComandosRemotos.lerVolume(docker, p));
        l.add(ComandosRemotos.lerImagem(docker, p.imagem(PACOTE.versao())));
        l.add(ComandosRemotos.lerLogsDoContainer(docker, p));
        return l;
    }

    private List<String> escritas(String docker) {
        List<String> e = new ArrayList<>(ComandosRemotos.instalarODocker(true, "debian"));
        e.addAll(ComandosRemotos.instalarODocker(false, "ubuntu"));
        e.addAll(List.of(ComandosRemotos.criarPasta(p), ComandosRemotos.extrairPacote(p, PACOTE), ComandosRemotos.construirImagem(docker, p, PACOTE),
            ComandosRemotos.removerContainer(docker, p), ComandosRemotos.lerGidDoSoquete(docker, p.imagem(PACOTE.versao())),
            ComandosRemotos.acertarDonoDosDados(docker, p, PACOTE), ComandosRemotos.subirContainer(docker, p, PACOTE, "999"), ComandosRemotos.removerVolume(docker, p),
            ComandosRemotos.removerImagem(docker, p.imagem(PACOTE.versao())), ComandosRemotos.removerPasta(p)));
        return e;
    }

    @Test
    void todaLeituraELeituraEToda_escritaNaoE() {
        for (String docker : List.of("docker", "sudo -n docker")) {
            for (String c : leituras(docker)) {
                assertTrue(ComandosRemotos.ehLeitura(c), "devia ser leitura: " + c);
            }
            for (String c : escritas(docker)) {
                assertFalse(ComandosRemotos.ehLeitura(c), "NÃO pode contar como leitura: " + c);
            }
        }
        assertFalse(ComandosRemotos.ehLeitura(null));
        assertFalse(ComandosRemotos.ehLeitura("docker exec -i painel-lab node acesso.js token-registrar"));
        assertFalse(ComandosRemotos.ehLeitura("docker run -d --name x"));
        assertFalse(ComandosRemotos.ehLeitura("rm -rf /"));
    }

    /** A fronteira é a linha INTEIRA, não um prefixo: uma leitura seguida de qualquer outra coisa não é leitura. */
    @Test
    void leituraComQualquerCoisaDepoisNaoEMaisLeitura() {
        for (String docker : List.of("docker", "sudo -n docker")) {
            for (String c : leituras(docker)) {
                for (String rabo : List.of("; rm -rf /", " && touch /tmp/x", " | sh", "\nrm x", " > /etc/passwd", " ", "\t")) {
                    assertFalse(ComandosRemotos.ehLeitura(c + rabo), "NÃO é leitura: " + c + rabo);
                }
                assertFalse(ComandosRemotos.ehLeitura("echo oi; " + c));
            }
        }
        assertFalse(ComandosRemotos.ehLeitura("cat /etc/os-release; id"));
        assertFalse(ComandosRemotos.ehLeitura("cat \"$HOME/lab-agente/src/VERSAO\"; rm -rf \"$HOME\""));
        assertFalse(ComandosRemotos.ehLeitura("docker ps -a --filter name=x --format '{{.Names}}' && docker rm -f x"));
        assertFalse(ComandosRemotos.ehLeitura("docker logs --tail 40 ../../x"));
        assertFalse(ComandosRemotos.ehLeitura("docker volume ls -q --filter name=Maiuscula"));
    }

    @Test
    void nenhumaLinhaTemCaractereDeControleNemQuebraDeLinha() {
        for (String docker : List.of("docker", "sudo -n docker")) {
            for (String c : leituras(docker)) {
                semControle(c);
            }
            for (String c : escritas(docker)) {
                semControle(c);
            }
        }
    }

    private static void semControle(String c) {
        for (char ch : c.toCharArray()) {
            assertFalse(ch < 0x20 || ch == 0x7F, "caractere de controle em: " + c);
        }
    }

    @Test
    void aEscritaDoDockerSoAceitaOsDoisComandosFixosDoDocker() {
        for (String ruim : new String[] {"docker; rm -rf /", "docker --host tcp://x", "sudo docker", "sudo -n docker run", "", " docker"}) {
            assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.removerContainer(ruim, p), ruim);
            assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.lerContainer(ruim, p), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.removerContainer(null, p));
    }

    @Test
    void nomesDeContainerPastaEImagemPassamPorRegexAntesDeEntrarNaLinha() {
        for (String ruim : new String[] {"a;b", "a b", "A", "-x", "a$(id)", "a`id`", "a|b", "a&b", "", "a".repeat(42), "../x", "a\nb"}) {
            assertThrows(IllegalArgumentException.class, () -> new ParametrosDaInstalacao(ruim, 7000, "lab-agente"), "container " + ruim);
            assertThrows(IllegalArgumentException.class, () -> new ParametrosDaInstalacao("painel-lab", 7000, ruim), "pasta " + ruim);
            assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.lerSituacaoDoAgente(ruim), "diagnóstico " + ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> new ParametrosDaInstalacao("painel-lab", 0, "lab-agente"));
        assertThrows(IllegalArgumentException.class, () -> new ParametrosDaInstalacao("painel-lab", 65536, "lab-agente"));
        assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.lerPorta(0));
        assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.removerImagem("docker", "x; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> ComandosRemotos.lerImagem("docker", "sem-tag"));
        assertEquals("painel-teste-dados", new ParametrosDaInstalacao("painel-teste", 7100, "lab-agente").volume());
    }

    @Test
    void aVersaoDoPacoteQueViraTagDeImagemTambemEValidada() {
        for (String ruim : new String[] {"1.0 beta", "1.0;x", "", "-1", "a".repeat(42), "a:b", "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> PacoteDoAgente.de(ruim, "abc1234", new byte[] {1}), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> PacoteDoAgente.de("1.0", "nao-hex", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> PacoteDoAgente.de("1.0", "abc1234", new byte[0]));
    }

    @Test
    void osComandosDoContainerCitamOsNomesValidadosENadaMais() {
        assertEquals("docker ps -a --filter name=painel-lab --format '{{.Names}}|{{.Label \"br.com.labdesktop.agente\"}}|{{.State}}'",
            ComandosRemotos.lerContainer("docker", p));
        assertEquals("ss -H -ltn 'sport = :7000'", ComandosRemotos.lerPorta(7000));
        assertEquals("df -Pk \"$HOME\" /var/lib/docker", ComandosRemotos.lerDisco());
        assertEquals("cat \"$HOME/lab-agente/src/VERSAO\"", ComandosRemotos.lerVersaoExtraida(p));
        assertEquals("lab-agente/agente-0.1.0-abc1234.tar.gz", ComandosRemotos.caminhoDoPacote(p, PACOTE));
        assertEquals("sh -c '[ -n \"$HOME\" ] && { [ -f \"$HOME/lab-agente/.lab-desktop\" ] || [ -f \"$HOME/lab-agente/src/VERSAO\" ]; } && rm -rf \"$HOME/lab-agente\"'",
            ComandosRemotos.removerPasta(p));
        String build = ComandosRemotos.construirImagem("docker", p, PACOTE);
        assertTrue(build.contains("docker build -f Dockerfile.monitor -t labdesktop-agente:0.1.0-abc1234 \"$HOME/lab-agente/src\""), build);
        assertTrue(build.contains("tail -n 40") && build.endsWith("exit $r'"), "guarda o fim da saída e devolve o status do build: " + build);
    }

    @Test
    void asLinhasComLogicaNaoTemAspasSimplesPorDentroDoShC() {
        List<String> todas = new ArrayList<>(leituras("docker"));
        todas.addAll(escritas("docker"));
        for (String c : todas) {
            if (c.startsWith("sh -c '")) {
                String corpo = c.substring("sh -c '".length(), c.length() - 1);
                assertFalse(corpo.contains("'"), "aspa simples quebraria o sh -c no fish e no csh: " + c);
                assertTrue(c.endsWith("'"), c);
            }
        }
    }
}
