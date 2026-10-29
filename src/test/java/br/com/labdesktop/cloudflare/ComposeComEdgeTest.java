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
            new Caso("services:\n  web:\n    image: nginx\n    networks: {default: {}}\n", "web", "uma linha só"),
            new Caso("services:\n  web:\n    image: nginx\n    networks: []\n", "web", "uma linha só"),
            new Caso("services:\n  web:\n    image: nginx\n    networks: [default,\n      interna]\n", "web", "uma linha só"),
            new Caso("services:\n  web:\n    image: nginx\n    networks: [default, ${REDE}]\n", "web", "uma linha só"),
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

    /** O compose do projeto de exemplo do lab: container_name com comentário, redes em uma linha e edge já declarada. */
    private static final String WHOAMI = "# Projeto de exemplo\n"
        + "name: whoami\n"
        + "services:\n"
        + "  whoami:\n"
        + "    image: traefik/whoami\n"
        + "    container_name: whoami        # nome único: é assim que o cloudflared o encontra\n"
        + "    restart: unless-stopped\n"
        + "    networks: [default, edge]\n"
        + "\n"
        + "networks:\n"
        + "  edge:\n"
        + "    external: true\n";

    @Test
    void redesEmUmaLinhaComEdgeJaEstaProntoENadaSeEscreve() {
        ComposeComEdge.Proposta p = ComposeComEdge.propor(WHOAMI, "whoami", "demo-whoami");
        assertEquals(Situacao.PRONTO, p.situacao(), p.motivos().toString());
        assertEquals("whoami", p.containerName());
        assertEquals(WHOAMI, p.novoTexto());
        assertTrue(ComposeComEdge.servicos(WHOAMI).get(0).naRedeEdge(), "a lista de serviços também vê a edge");
        for (String forma : List.of("[default, edge]", "[ \"default\" , 'edge' ]", "[edge]", "[default, edge]   # as redes")) {
            String t = WHOAMI.replace("[default, edge]", forma);
            assertEquals(Situacao.PRONTO, ComposeComEdge.propor(t, "whoami", "demo-whoami").situacao(), forma);
        }
    }

    @Test
    void redesEmUmaLinhaSemEdgeGanhamEdgeDentroDosColchetesSemMexerNoResto() {
        String t = "services:\n  web:\n    image: nginx\n    container_name: site\n    networks: [default, interna]  # redes\n    restart: always\n"
            + "\nnetworks:\n  edge:\n    external: true\n";
        ComposeComEdge.Proposta p = ComposeComEdge.propor(t, "web", "demo-web");
        assertEquals(Situacao.EDITAVEL, p.situacao(), p.motivos().toString());
        assertEquals(t.replace("[default, interna]  # redes", "[default, interna, edge]  # redes"), p.novoTexto());
        assertEquals(Situacao.EDITAVEL, ComposeComEdge.propor("services:\n  web:\n    image: nginx\n    networks: [default]\n", "web", "demo-web").situacao());
        String semContainer = "services:\n  web:\n    image: nginx\n    networks: [default]\n";
        assertEquals("services:\n  web:\n    container_name: demo-web\n    image: nginx\n    networks: [default, edge]\n\nnetworks:\n  edge:\n    external: true\n",
            ComposeComEdge.propor(semContainer, "web", "demo-web").novoTexto());
    }

    @Test
    void oBlocoParaCopiarDizOQueFazerQuandoAsRedesEstaoEmUmaLinhaQueOAppNaoEdita() {
        ComposeComEdge.Proposta p = ComposeComEdge.propor("services:\n  web:\n    image: nginx\n    networks: {default: {}}\n", "web", "demo-web");
        assertEquals(Situacao.MANUAL, p.situacao());
        assertTrue(p.blocoParaCopiar().contains("escrito em uma linha, acrescente edge ao que já está lá"), p.blocoParaCopiar());
        assertFalse(p.blocoParaCopiar().contains("acrescente a linha"), "não manda acrescentar uma linha de lista a quem escreveu em uma linha");
        assertFalse(p.blocoParaCopiar().contains("networks: ["), "o exemplo não inventa a rede default de quem não a usava");
    }

    @Test
    void listaEmUmaLinhaComVirgulaFinalOuComentarioColadoEntendeCorretamente() {
        String base = "services:\n  web:\n    image: nginx\n    container_name: site\n    networks: FORMA\n\nnetworks:\n  edge:\n    external: true\n";
        assertEquals(Situacao.PRONTO, ComposeComEdge.propor(base.replace("FORMA", "[default, edge,]"), "web", "demo-web").situacao(), "vírgula final é YAML válido");
        ComposeComEdge.Proposta p = ComposeComEdge.propor(base.replace("FORMA", "[default,]"), "web", "demo-web");
        assertEquals(Situacao.EDITAVEL, p.situacao());
        assertEquals(base.replace("FORMA", "[default, edge]"), p.novoTexto(), "sem vírgula dupla");
        for (String forma : List.of("[default]#c", "[default,edge]#c", "[\"a]b\", edge]", "[default] x")) {
            assertEquals(Situacao.MANUAL, ComposeComEdge.propor(base.replace("FORMA", forma), "web", "demo-web").situacao(), forma);
        }
        ComposeComEdge.Proposta comentario = ComposeComEdge.propor(base.replace("FORMA", "[default]  # veja [x]"), "web", "demo-web");
        assertEquals(base.replace("FORMA", "[default, edge]  # veja [x]"), comentario.novoTexto(), "o ] do comentário não conta");
    }

    @Test
    void listaEmUmaLinhaSemEdgeComARedeDeCimaAntesDosServicosEOAlvoNoMeio() {
        String t = "networks:\n  interna:\n  edge:\n    external: true\nservices:\n  db:\n    image: pg\n  web:\n    image: nginx\n    networks: [interna]\n  api:\n    image: x\n";
        ComposeComEdge.Proposta p = ComposeComEdge.propor(t, "web", "demo-web");
        assertEquals(Situacao.EDITAVEL, p.situacao(), p.motivos().toString());
        assertEquals("networks:\n  interna:\n  edge:\n    external: true\nservices:\n  db:\n    image: pg\n  web:\n    container_name: demo-web\n    image: nginx\n"
            + "    networks: [interna, edge]\n  api:\n    image: x\n", p.novoTexto());
        String semRede = "networks:\n  interna:\nservices:\n  db:\n    image: pg\n  web:\n    image: nginx\n    networks: [interna]\n";
        assertEquals("networks:\n  interna:\n  edge:\n    external: true\nservices:\n  db:\n    image: pg\n  web:\n    container_name: demo-web\n    image: nginx\n"
            + "    networks: [interna, edge]\n", ComposeComEdge.propor(semRede, "web", "demo-web").novoTexto());
        assertFalse(ComposeComEdge.servicos("services:\n  web:\n    image: nginx\n    networks: [interna]\n").get(0).naRedeEdge());
    }
}
