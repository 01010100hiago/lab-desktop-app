package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cloudflare.ComposeComEdge.Situacao;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O que "Publicar na internet" escreve no compose: container_name único e a rede edge, só por acréscimo, e o que o app nunca escreve (valores conhecidos). */
class ComposeComEdgeTest {

    /** O que o "Adicionar container" deixa para a aplicação Node. */
    private static final String DO_APP = "name: \"demo\"\n"
        + "# Esqueleto: acrescente os serviços abaixo.\n"
        + "services:\n"
        + "  app:\n"
        + "    image: node:22-alpine\n"
        + "    working_dir: /app\n"
        + "    ports:\n"
        + "      - \"127.0.0.1:3000:3000\"\n"
        + "    networks:\n"
        + "      - default\n"
        + "    restart: unless-stopped\n"
        + "  postgres:\n"
        + "    image: postgres:17-alpine\n"
        + "    volumes:\n"
        + "      - postgres_dados:/var/lib/postgresql/data\n"
        + "    networks:\n"
        + "      - default\n"
        + "    restart: unless-stopped\n"
        + "\n"
        + "volumes:\n"
        + "  postgres_dados:\n";

    @Test
    void servicoDoAppGanhaContainerNameAEdgeEORedeExternaNoFimValorConhecido() {
        ComposeComEdge.Proposta p = ComposeComEdge.propor(DO_APP, "app", "demo-app");
        assertEquals(Situacao.EDITAVEL, p.situacao());
        assertEquals("demo-app", p.containerName());
        assertEquals("name: \"demo\"\n"
            + "# Esqueleto: acrescente os serviços abaixo.\n"
            + "services:\n"
            + "  app:\n"
            + "    container_name: demo-app\n"
            + "    image: node:22-alpine\n"
            + "    working_dir: /app\n"
            + "    ports:\n"
            + "      - \"127.0.0.1:3000:3000\"\n"
            + "    networks:\n"
            + "      - default\n"
            + "      - edge\n"
            + "    restart: unless-stopped\n"
            + "  postgres:\n"
            + "    image: postgres:17-alpine\n"
            + "    volumes:\n"
            + "      - postgres_dados:/var/lib/postgresql/data\n"
            + "    networks:\n"
            + "      - default\n"
            + "    restart: unless-stopped\n"
            + "\n"
            + "volumes:\n"
            + "  postgres_dados:\n"
            + "\n"
            + "networks:\n"
            + "  edge:\n"
            + "    external: true\n", p.novoTexto());
        assertTrue(p.motivos().isEmpty());
        assertTrue(p.blocoParaCopiar().contains("container_name: demo-app"));
    }

    @Test
    void oSegundoPassoDeixaTudoProntoEOQueNaoEDoServicoNaoMuda() {
        String uma = ComposeComEdge.propor(DO_APP, "app", "demo-app").novoTexto();
        ComposeComEdge.Proposta de_novo = ComposeComEdge.propor(uma, "app", "outro-nome-qualquer");
        assertEquals(Situacao.PRONTO, de_novo.situacao(), "já tem container_name e edge: nada a fazer");
        assertEquals("demo-app", de_novo.containerName(), "vale o nome que já estava, não o pedido");
        assertEquals(uma, de_novo.novoTexto());
        // o outro serviço continua intacto e entra depois com a rede já declarada: só ele ganha linhas
        ComposeComEdge.Proposta pg = ComposeComEdge.propor(uma, "postgres", "demo-postgres");
        assertEquals(Situacao.EDITAVEL, pg.situacao());
        assertEquals(uma.replace("  postgres:\n", "  postgres:\n    container_name: demo-postgres\n").replace("    volumes:\n      - postgres_dados:/var/lib/postgresql/data\n    networks:\n      - default\n",
            "    volumes:\n      - postgres_dados:/var/lib/postgresql/data\n    networks:\n      - default\n      - edge\n"), pg.novoTexto(), "só as linhas do serviço; a rede de cima já existia");
    }

    @Test
    void servicoSemNetworksGanhaDefaultEEdgeEUmaRedeDeCimaExistenteRecebeAEdgeDentroDela() {
        String t = "networks:\n  interna:\n    driver: bridge\n"
            + "services:\n  web:\n    image: nginx\n    restart: always\n";
        ComposeComEdge.Proposta p = ComposeComEdge.propor(t, "web", "demo-web");
        assertEquals(Situacao.EDITAVEL, p.situacao());
        assertEquals("networks:\n  interna:\n    driver: bridge\n  edge:\n    external: true\n"
            + "services:\n  web:\n    container_name: demo-web\n    networks:\n      - default\n      - edge\n    image: nginx\n    restart: always\n", p.novoTexto(),
            "a rede de cima vem ANTES dos serviços no arquivo: as duas edições não se atrapalham");
    }

    @Test
    void redesEmFormaDeMapaGanhamUmaEntradaEdgeNoFimDoBloco() {
        String t = "services:\n  web:\n    image: nginx\n    networks:\n      interna:\n        aliases:\n          - site\n    restart: always\n";
        ComposeComEdge.Proposta p = ComposeComEdge.propor(t, "web", "demo-web");
        assertEquals(Situacao.EDITAVEL, p.situacao());
        assertEquals("services:\n  web:\n    container_name: demo-web\n    image: nginx\n    networks:\n      interna:\n        aliases:\n          - site\n      edge:\n    restart: always\n"
            + "\nnetworks:\n  edge:\n    external: true\n", p.novoTexto());
    }

    @Test
    void networksVazioEContainerNameQueJaExisteSaoRespeitados() {
        String t = "services:\n  web:\n    container_name: \"meu-site\"\n    image: nginx\n    networks:\n    restart: always\n";
        ComposeComEdge.Proposta p = ComposeComEdge.propor(t, "web", "demo-web");
        assertEquals("meu-site", p.containerName(), "o container_name do usuário vale");
        assertEquals("services:\n  web:\n    container_name: \"meu-site\"\n    image: nginx\n    networks:\n      - default\n      - edge\n    restart: always\n"
            + "\nnetworks:\n  edge:\n    external: true\n", p.novoTexto());
    }

    @Test
    void comentariosESemQuebraDeLinhaNoFimContinuamComoEstavam() {
        String t = "# meu compose\nservices:\n  # o site\n  web:   # principal\n    image: nginx  # leve\n    # sem rede ainda\n";
        ComposeComEdge.Proposta p = ComposeComEdge.propor(t, "web", "demo-web");
        assertEquals("# meu compose\nservices:\n  # o site\n  web:   # principal\n    container_name: demo-web\n    networks:\n      - default\n      - edge\n    image: nginx  # leve\n"
            + "    # sem rede ainda\n\nnetworks:\n  edge:\n    external: true\n", p.novoTexto());
        String semEol = "services:\n  web:\n    image: nginx";
        assertFalse(ComposeComEdge.propor(semEol, "web", "demo-web").novoTexto().endsWith("\n"), "o arquivo sem quebra de linha no fim continua sem");
    }

    @Test
    void oQueOAppNaoEscreveViraManualComOMotivoEOBlocoParaCopiar() {
        String base = "services:\n  web:\n    image: nginx\n";
        record Caso(String texto, String servico, String trecho) { }
        List<Caso> casos = List.of(
            new Caso(base.replace("\n", "\r\n"), "web", "CRLF"),
            new Caso("services:\n\tweb:\n\t\timage: nginx\n", "web", "tabulação"),
            new Caso("services:\n  web:\n    image: nginx\n    networks: [default, interna]\n", "web", "uma linha só"),
            new Caso("services:\n  web:\n    image: nginx\n    network_mode: host\n", "web", "network_mode"),
            new Caso("services: {web: {image: nginx}}\n", "web", "services"),
            new Caso("services:\n  web:\n    image: nginx\nnetworks: {}\n", "web", "networks do arquivo"),
            new Caso("services:\n  web:\n    image: nginx\nnetworks:\n  edge:\n    driver: bridge\n", "web", "não é externa"),
            new Caso("services:\n  web:\n    image: nginx\n  api:\n    container_name: demo-web\n    image: x\n", "web", "único"),
            new Caso(base, "naoexiste", "Não achei o serviço"),
            new Caso("services:\n  web:\n    image: nginx\n    container_name: MeuSite;rm\n", "web", "container_name"),
            new Caso("---\nservices:\n  web:\n    image: nginx\n", "web", "documento"),
            new Caso("version: '3'\n", "web", "services"));
        for (Caso c : casos) {
            ComposeComEdge.Proposta p = ComposeComEdge.propor(c.texto(), c.servico(), "demo-web");
            assertEquals(Situacao.MANUAL, p.situacao(), c.trecho());
            assertNull(p.novoTexto(), "o app nunca escreve: " + c.trecho());
            assertTrue(p.motivos().stream().anyMatch(m -> m.contains(c.trecho())), c.trecho() + " -> " + p.motivos());
            if (c.trecho().equals("network_mode")) {
                assertEquals("", p.blocoParaCopiar(), "com network_mode o bloco contradiria o bloqueio: não há bloco, só o motivo");
            } else {
                assertTrue(p.blocoParaCopiar().contains("external: true"), c.trecho());
            }
        }
    }

    @Test
    void listaOsServicosComContainerNameEdgeEPortaSugerida() {
        String t = "services:\n  web:\n    image: nginx\n    container_name: site\n    ports:\n      - \"127.0.0.1:8080:80\"\n    networks:\n      - default\n      - edge\n"
            + "  api:\n    image: x\n    expose:\n      - 3000\n  worker:\n    image: y\n    ports:\n      - 9000\n  db:\n    image: postgres\n    ports:\n      - 127.0.0.1:5440:5432/tcp\n";
        List<ComposeComEdge.Servico> l = ComposeComEdge.servicos(t);
        assertEquals(List.of("web", "api", "worker", "db"), l.stream().map(ComposeComEdge.Servico::nome).toList());
        assertEquals(new ComposeComEdge.Servico("web", "site", true, 80), l.get(0));
        assertEquals(new ComposeComEdge.Servico("api", null, false, 3000), l.get(1));
        assertEquals(9000, l.get(2).portaSugerida());
        assertEquals(5432, l.get(3).portaSugerida());
        assertEquals(List.of(), ComposeComEdge.servicos("services: {}\n"));
        assertEquals(List.of(), ComposeComEdge.servicos(null));
    }

    @Test
    void nomeDeContainerQueNaoServeERecusadoAntesDeMexerEmQualquerCoisa() {
        for (String ruim : List.of("Demo", "-v", "a b", "demo;ls", "", "a".repeat(60))) {
            assertThrows(IllegalArgumentException.class, () -> ComposeComEdge.propor(DO_APP, "app", ruim), ruim);
        }
    }
}
