package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * O contrato de verdade: cada DTO lê a resposta REAL do agente do lab, gravada por {@code scripts/capturar-fixtures.sh}
 * em {@code src/test/resources/lab-real/}. O mock do front NÃO vale como contrato (CONTRATO-API 0): no mock, {@code mem}
 * de container é texto ("186MiB") e as datas são macros ({@code @+5d3h}); no agente real são número e data ISO. Os testes
 * de {@code ClienteApiTest} com os mocks provam só a TOLERÂNCIA do cliente; este prova a FORMA.
 *
 * <p><b>PENDENTE até a primeira captura</b> (precisa de um servidor real, que ninguém conecta de dentro dos testes): sem os
 * arquivos, cada teste é PULADO e o Gradle o lista como "skipped". Isso mantém o critério
 * "contrato: cada DTO parseia a fixture real" visivelmente PARCIAL em vez de aparentar cumprido.
 */
class ContratoComFixturesReaisTest {

    private static final Path PASTA = Path.of("src", "test", "resources", "lab-real");

    private static String ler(String arquivo) {
        Path p = PASTA.resolve(arquivo);
        assumeTrue(Files.isRegularFile(p),
            "PENDENTE: " + p + " não existe. Publique o painel e rode scripts/capturar-fixtures.sh.");
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode arvore(String json) {
        try {
            return Json.mapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void versaoDoAgenteRealParseiaEFalaAVersaoDoContratoDoApp() {
        VersaoAgente v = FixturesDeApi.de(ler("versao.json"), VersaoAgente.class);

        assertTrue(v.compativel(), "o agente real fala a versão " + v.api() + " do contrato; o app fala " + VersaoAgente.API_SUPORTADA);
        assertNotNull(v.painel());
        assertTrue(java.util.List.of("desligado", "pendente", "ativo").contains(v.totp()), "totp: " + v.totp());
        assertNotNull(v.exigeToken());
    }

    @Test
    void limitesReaisParseiamComNumeroEDataDeVerdade() {
        String json = ler("limites.json");
        LimitesConta l = FixturesDeApi.de(json, LimitesConta.class);

        assertNotNull(l.disponivel());
        assertNotNull(l.freio());
        assertNotNull(l.porProjeto());
        verificarFormaDosCampos(arvore(json), "limites.json");
        if (Boolean.TRUE.equals(l.disponivel())) {
            assertNotNull(l.semana(), "com leitura disponível, a janela da semana vem");
            assertNotNull(l.semana().usadoPct());
            assertNotNull(l.semana().zeraEm(), "zera_em é uma data ISO de verdade, não uma macro do mock");
        }
    }

    @Test
    void listaDeProjetosRealParseiaInclusiveOResumoDoServidor() {
        String json = ler("projetos.json");
        ListaProjetos l = FixturesDeApi.de(json, ListaProjetos.class);

        assertFalse(l.projetos().isEmpty(), "o lab tem projetos");
        for (var p : l.projetos()) {
            assertTrue(ValidadorDeNomes.projetoValido(p.nome()), "nome de projeto fora do padrão do agente: " + p.nome());
            assertNotNull(p.estado(), p.nome());
        }
        assertNotNull(l.servidor(), "o resumo do servidor vem junto com a lista");
        verificarFormaDosCampos(arvore(json), "projetos.json");
    }

    @Test
    void servidorRealParseia() {
        String json = ler("servidor.json");
        InfoServidor s = FixturesDeApi.de(json, InfoServidor.class);

        assertNotNull(s.hostname());
        assertNotNull(s.cpu());
        assertNotNull(s.memoria());
        verificarFormaDosCampos(arvore(json), "servidor.json");
    }

    @Test
    void sessaoRaizRealTemAFormaPropriaDoLab() {
        String json = ler("projetos/lab.json");
        DetalheRaiz r = FixturesDeApi.de(json, DetalheRaiz.class);

        assertEquals("lab", r.nome());
        assertTrue(r.raiz(), "a raiz é marcada com raiz:true");
        assertNotNull(r.limite());
        verificarFormaDosCampos(arvore(json), "projetos/lab.json");
    }

    @Test
    void detalhesReaisDosProjetosComunsParseiamComEComSemSessao() throws IOException {
        Path pasta = PASTA.resolve("projetos");
        assumeTrue(Files.isDirectory(pasta), "PENDENTE: " + pasta + " não existe. Rode scripts/capturar-fixtures.sh.");
        int lidos = 0;
        try (Stream<Path> arquivos = Files.list(pasta)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".json")).sorted().toList()) {
                String nome = arquivo.getFileName().toString().replaceFirst("\\.json$", "");
                if (nome.equals("lab")) {
                    continue; // a raiz tem teste próprio
                }
                String json = Files.readString(arquivo);
                DetalheProjeto d = FixturesDeApi.de(json, DetalheProjeto.class);
                assertEquals(nome, d.nome(), "o arquivo e o nome do projeto conferem");
                assertNotNull(d.sessao(), nome + ": o detalhe comum sempre traz o objeto sessao");
                assertNotNull(d.limite(), nome);
                verificarFormaDosCampos(arvore(json), "projetos/" + arquivo.getFileName());
                lidos++;
            }
        }
        assumeTrue(lidos > 0, "PENDENTE: nenhum detalhe de projeto capturado. Rode scripts/capturar-fixtures.sh.");
    }

    /**
     * A divergência que o mock esconde: {@code mem} escalar é número (ou {@code null}), nunca texto, e todo campo de data é
     * ISO 8601, nunca macro. Percorre a árvore inteira da resposta real.
     */
    private static void verificarFormaDosCampos(JsonNode no, String onde) {
        if (no.isObject()) {
            for (Map.Entry<String, JsonNode> c : no.properties()) {
                String nome = c.getKey();
                JsonNode valor = c.getValue();
                // 'mem' escalar é de container ou projeto (bytes); 'historico.mem' é uma série (array) e não entra aqui.
                if (nome.equals("mem") && valor.isValueNode() && !valor.isNull()) {
                    assertTrue(valor.isNumber(), onde + ": 'mem' deveria ser número de bytes, veio " + valor);
                }
                if (ehCampoDeData(nome) && valor.isTextual()) {
                    String texto = valor.asText();
                    assertFalse(texto.contains("@"), onde + ": '" + nome + "' é macro do mock, não data real: " + texto);
                    try {
                        Instant.parse(texto);
                    } catch (java.time.format.DateTimeParseException e) {
                        throw new AssertionError(onde + ": '" + nome + "' deveria ser data ISO 8601, veio " + texto, e);
                    }
                }
                verificarFormaDosCampos(valor, onde);
            }
        } else if (no.isArray()) {
            no.forEach(f -> verificarFormaDosCampos(f, onde));
        }
    }

    private static boolean ehCampoDeData(String nome) {
        return nome.equals("quando") || nome.equals("desde") || nome.equals("em") || nome.endsWith("_em");
    }
}
