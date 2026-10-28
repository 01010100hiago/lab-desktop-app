package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Os comandos do servidor (listas FIXAS, só com o que passou pela validação) e a leitura do {@code docker inspect}. */
class ComandosEMontagensTest {

    @Test
    void osComandosSaoListasFixasEPassamPelaRegraDoSsh() {
        List<List<String>> todos = List.of(ComandosCloudflared.inspecionar(false, "cloudflared"), ComandosCloudflared.reiniciar(false, "cloudflared"),
            ComandosCloudflared.validarRegras(false, "cloudflared", "/etc/cloudflared/config.yml"), ComandosCloudflared.inspecionar(true, "cloudflared"),
            ComandosCloudflared.reiniciar(true, "cloudflared"), ComandosCloudflared.validarRegras(true, "cloudflared", "/etc/cloudflared/config.yml"));
        assertEquals(List.of("docker", "inspect", "cloudflared"), todos.get(0));
        assertEquals(List.of("docker", "restart", "cloudflared"), todos.get(1));
        assertEquals(List.of("docker", "exec", "cloudflared", "cloudflared", "tunnel", "--config", "/etc/cloudflared/config.yml", "ingress", "validate"), todos.get(2));
        assertEquals(List.of("sudo", "-n", "docker", "restart", "cloudflared"), todos.get(4));
        for (List<String> c : todos) {
            assertEquals(String.join(" ", c), ExecutorDeArgumentos.linhaRemota(c, java.util.Map.of()), "cada argumento passa pela regra de caracteres seguros: " + c);
        }
        assertTrue(ComandosCloudflared.ehLeitura(todos.get(0)));
        assertTrue(ComandosCloudflared.ehLeitura(todos.get(2)));
        assertFalse(ComandosCloudflared.ehLeitura(todos.get(1)), "reiniciar é escrita");
        assertFalse(ComandosCloudflared.ehLeitura(todos.get(4)));
    }

    @Test
    void textoHostilNaoViraComando() {
        for (String ruim : List.of("-v /:/host", "cloudflared; rm -rf /", "cloud flared", "$(id)", "", "a".repeat(60), "-x", "../x", "Cloud`id`")) {
            assertThrows(IllegalArgumentException.class, () -> ComandosCloudflared.reiniciar(false, ruim), ruim);
            assertThrows(IllegalArgumentException.class, () -> ComandosCloudflared.inspecionar(true, ruim), ruim);
            assertThrows(IllegalArgumentException.class, () -> ComandosCloudflared.validarRegras(false, ruim, "/etc/x.yml"), ruim);
        }
        for (String ruim : List.of("etc/x.yml", "/etc/../x.yml", "/etc/x yml", "/etc/x;ls", "", "/etc//x.yml", "/etc/$(id)")) {
            assertThrows(IllegalArgumentException.class, () -> ComandosCloudflared.validarRegras(false, "cloudflared", ruim), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ComandosCloudflared.reiniciar(false, null));
    }

    private static String inspect(boolean rodando, String montagens) {
        return "[{\"State\":{\"Running\":" + rodando + "},\"Config\":{\"Image\":\"cloudflare/cloudflared:2026.1.0\"},\"Mounts\":[" + montagens + "]}]";
    }

    @Test
    void oArquivoMontadoDiretoDaOCaminhoDeDentro() {
        MontagensDoContainer m = MontagensDoContainer.ler(inspect(true, "{\"Type\":\"bind\",\"Source\":\"/srv/lab/infra/cloudflared/config.yml\",\"Destination\":\"/etc/cloudflared/config.yml\"}"),
            "/srv/lab/infra/cloudflared/config.yml");
        assertTrue(m.existe());
        assertTrue(m.rodando());
        assertEquals("cloudflare/cloudflared:2026.1.0", m.imagem());
        assertEquals(Optional.of("/etc/cloudflared/config.yml"), m.caminhoDoConfigNoContainer());
    }

    @Test
    void umaPastaMontadaQueContemOArquivoDaOCaminhoRelativo() {
        MontagensDoContainer m = MontagensDoContainer.ler(inspect(true, "{\"Source\":\"/srv/lab/infra/cloudflared\",\"Destination\":\"/etc/cloudflared\"},"
            + "{\"Source\":\"/srv/lab\",\"Destination\":\"/lab\"}"), "/srv/lab/infra/cloudflared/config.yml");
        assertEquals(Optional.of("/etc/cloudflared/config.yml"), m.caminhoDoConfigNoContainer(), "vale a montagem mais específica");
    }

    @Test
    void semMontagemQueLeveAoArquivoOCaminhoNaoApareceEParecidoNaoCasa() {
        assertEquals(Optional.empty(), MontagensDoContainer.ler(inspect(true, "{\"Source\":\"/srv/lab/infra/outra\",\"Destination\":\"/x\"}"), "/srv/lab/infra/cloudflared/config.yml")
            .caminhoDoConfigNoContainer());
        assertEquals(Optional.empty(), MontagensDoContainer.ler(inspect(true, "{\"Source\":\"/srv/lab/infra/cloudflared-velho\",\"Destination\":\"/x\"}"), "/srv/lab/infra/cloudflared/config.yml")
            .caminhoDoConfigNoContainer(), "/cloudflared-velho não é pasta de /cloudflared");
        assertEquals(Optional.empty(), MontagensDoContainer.ler(inspect(true, ""), "/a/b.yml").caminhoDoConfigNoContainer());
    }

    @Test
    void containerParadoEJsonQueNaoEDeContainer() {
        assertFalse(MontagensDoContainer.ler(inspect(false, ""), "/a.yml").rodando());
        assertTrue(MontagensDoContainer.ler(inspect(false, ""), "/a.yml").existe());
        for (String lixo : List.of("", "[]", "isto não é json", "{}", "[{\"outra\":1}]", "null", "[1,2]")) {
            assertEquals(MontagensDoContainer.NAO_EXISTE, MontagensDoContainer.ler(lixo, "/a.yml"), lixo);
        }
        assertEquals(MontagensDoContainer.NAO_EXISTE, MontagensDoContainer.ler(null, "/a.yml"));
    }

    @Test
    void destinoComCaractereRuimNaoViraCaminhoDeComando() {
        MontagensDoContainer m = MontagensDoContainer.ler(inspect(true, "{\"Source\":\"/a/b.yml\",\"Destination\":\"/x y; ls/b.yml\"}"), "/a/b.yml");
        assertEquals(Optional.empty(), m.caminhoDoConfigNoContainer(), "um destino que não passa na validação nunca é usado");
    }
}
