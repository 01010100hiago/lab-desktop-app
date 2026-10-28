package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cloudflare.ErroCloudflare.Tipo;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.teste.CloudflareFalsa.Permissao;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O cliente da API da Cloudflare contra a Cloudflare FALSA: token, listas, DNS, túnel, 429, 5xx, prazo e o segredo fora de tudo. */
class ClienteCloudflareTest {

    private CloudflareFalsa cf;
    private TokenCloudflare token;
    private final List<Duration> esperas = new ArrayList<>();

    @BeforeEach
    void subir() throws Exception {
        cf = new CloudflareFalsa();
        token = TokenCloudflare.deChars(CloudflareFalsa.TOKEN_CERTO.toCharArray());
    }

    @AfterEach
    void descer() {
        cf.close();
        token.zerar();
    }

    private ClienteCloudflare cliente() {
        return new ClienteCloudflare(cf.base(), token, ClienteCloudflare.Opcoes.rapida().comEsperas(3, esperas::add));
    }

    private static ErroCloudflare erro(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ErroCloudflare.class, e);
    }

    // ---------------------------------------------------------------------------------------------
    // token
    // ---------------------------------------------------------------------------------------------

    @Test
    void tokenValidoEConferidoENuncaViajaForaDoCabecalhoAuthorization() throws Exception {
        try (ClienteCloudflare c = cliente()) {
            TokenVerificado v = c.verificarToken();
            assertEquals("active", v.status());
            c.contas();
            c.zonas();
        }
        assertEquals(3, cf.pedidos.size());
        for (CloudflareFalsa.Pedido p : cf.pedidos) {
            assertEquals("Bearer " + CloudflareFalsa.TOKEN_CERTO, p.autorizacao());
            assertFalse(p.caminho().contains(CloudflareFalsa.TOKEN_CERTO), "nunca na URL");
            assertFalse(p.query().contains(CloudflareFalsa.TOKEN_CERTO));
            assertFalse(p.corpo().contains(CloudflareFalsa.TOKEN_CERTO), "nunca no corpo");
        }
        assertEquals("/user/tokens/verify", cf.pedidos.get(0).caminho());
    }

    @Test
    void tokenErradoViraTokenInvalidoComAFraseDeComoCriarOutro() throws Exception {
        cf.tokenAceito = "OutroTokenQualquerDeTeste0123456789abcd";
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(c::verificarToken);
            assertEquals(Tipo.TOKEN_INVALIDO, e.tipo());
            assertEquals(401, e.status());
            assertTrue(e.mensagem().contains("Crie um token novo"), e.mensagem());
            assertTrue(e.mensagem().contains("Meu perfil"), "o aviso do token de conta: " + e.mensagem());
            assertFalse(e.mensagem().contains(CloudflareFalsa.TOKEN_CERTO));
            assertFalse(e.incerto());
        }
    }

    @Test
    void tokenDesativadoOuExpiradoNaoPassa() throws Exception {
        cf.estadoDoToken = "expired";
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(c::verificarToken);
            assertEquals(Tipo.TOKEN_INVALIDO, e.tipo());
            assertTrue(e.mensagem().contains("não está ativo"), e.mensagem());
            assertTrue(e.mensagem().contains("expired"));
        }
    }

    @Test
    void semPermissaoDiaOQueFaltaEOTextoDaCloudflareSemHtml() throws Exception {
        cf.semPermissao(Permissao.ZONAS);
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(c::zonas);
            assertEquals(Tipo.SEM_PERMISSAO, e.tipo());
            assertEquals(403, e.status());
            assertEquals(10000, e.codigoDaCloudflare());
            assertTrue(e.mensagem().contains("listar os domínios"), e.mensagem());
            assertTrue(e.mensagem().contains("A Cloudflare disse: Authentication error"), e.mensagem());
        }
    }

    @Test
    void oTextoDaCloudflareEntraComoTextoSemHtmlSemControleESemOToken() throws Exception {
        cf.falhar("GET /accounts", 400, "{\"success\":false,\"errors\":[{\"code\":1,\"message\":\"<html><b>ruim</b>\\u0007 token " + CloudflareFalsa.TOKEN_CERTO + " cfut_AbCdEfGhIjKlMnOpQrStUv\"}]}", 1);
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(c::contas);
            assertEquals(Tipo.RECUSADO, e.tipo());
            assertFalse(e.mensagem().contains(CloudflareFalsa.TOKEN_CERTO), e.mensagem());
            assertFalse(e.mensagem().contains("cfut_AbCdEfGhIjKlMnOpQrStUv"), e.mensagem());
            assertFalse(e.mensagem().contains("\u0007"));
            assertFalse(e.mensagem().contains("disse: <html"), "o <html do Swing é neutralizado (o resto é texto, nunca interpretado): " + e.mensagem());
            assertTrue(e.mensagem().contains("***"));
        }
    }

    @Test
    void oClienteSoFalaPorHttpsOuComLoopbackEOToStringNaoMostraOToken() {
        assertThrows(IllegalArgumentException.class, () -> new ClienteCloudflare(URI.create("http://api.cloudflare.com/client/v4"), token, ClienteCloudflare.Opcoes.rapida()));
        assertThrows(IllegalArgumentException.class, () -> new ClienteCloudflare(URI.create("ftp://api.cloudflare.com/client/v4"), token, ClienteCloudflare.Opcoes.rapida()));
        try (ClienteCloudflare https = new ClienteCloudflare(URI.create(ClienteCloudflare.URL_PADRAO), token, ClienteCloudflare.Opcoes.rapida())) {
            assertFalse(https.toString().contains(CloudflareFalsa.TOKEN_CERTO));
            assertEquals("ClienteCloudflare[api.cloudflare.com, token=redigido]", https.toString());
        }
        assertEquals(ClienteCloudflare.URL_PADRAO, ClienteCloudflare.urlBase().toString());
    }

    @Test
    void fecharZeraACopiaDoTokenEDepoisNadaSai() throws Exception {
        ClienteCloudflare c = cliente();
        c.verificarToken();
        int antes = cf.pedidos.size();
        c.close();
        assertThrows(SegredoZeradoException.class, c::verificarToken);
        assertEquals(antes, cf.pedidos.size());
        assertFalse(token.estaZerado(), "o original é de quem o entregou (o cofre): o cliente só zera a cópia dele");
    }

    // ---------------------------------------------------------------------------------------------
    // listas
    // ---------------------------------------------------------------------------------------------

    @Test
    void contasZonasETuneisVoltamComOQueATelaMostra() throws Exception {
        try (ClienteCloudflare c = cliente()) {
            assertEquals(List.of(new ContaCf(CloudflareFalsa.CONTA, "Conta de Teste")), c.contas());
            ZonaCf z = c.zonas().get(0);
            assertEquals("example.com", z.nome());
            assertEquals(CloudflareFalsa.CONTA, z.contaId());
            assertTrue(z.ativa());
            List<TunelCf> t = c.tuneis(CloudflareFalsa.CONTA);
            assertEquals(2, t.size());
            assertEquals(ConfiguracaoCloudflare.Tipo.ARQUIVO, t.get(0).origem(), "config_src local: o arquivo do servidor manda");
            assertEquals(ConfiguracaoCloudflare.Tipo.API, t.get(1).origem(), "config_src cloudflare: a API manda");
            assertEquals("no ar", t.get(0).estadoEmTexto());
        }
        assertTrue(cf.pedidos.stream().anyMatch(p -> p.caminho().endsWith("/cfd_tunnel") && p.query().contains("is_deleted=false")));
    }

    @Test
    void tunelSemConfigSrcEOsApagadosSaoTratadosComoDeveSer() throws Exception {
        cf.tuneis.get(0).apagado = true;
        cf.tuneis.add(cf.novoTunel("22222222-2222-2222-2222-222222222222", "antigo", null));
        try (ClienteCloudflare c = cliente()) {
            List<TunelCf> t = c.tuneis(CloudflareFalsa.CONTA);
            assertEquals(List.of(CloudflareFalsa.TUNEL_API, "22222222-2222-2222-2222-222222222222"), t.stream().map(TunelCf::id).toList(), "o apagado não entra");
            assertEquals(ConfiguracaoCloudflare.Tipo.ARQUIVO, t.get(1).origem(), "sem config_src: o arquivo manda");
        }
    }

    @Test
    void asListasPaginadasVoltamInteiras() throws Exception {
        cf.paginaMaxima = 1;
        cf.zonas.add(new CloudflareFalsa.Zona("c".repeat(32), "segundo.example.com", CloudflareFalsa.CONTA, "Conta de Teste", "active"));
        cf.zonas.add(new CloudflareFalsa.Zona("d".repeat(32), "terceiro.example.com", CloudflareFalsa.CONTA, "Conta de Teste", "pending"));
        try (ClienteCloudflare c = cliente()) {
            List<ZonaCf> z = c.zonas();
            assertEquals(List.of("example.com", "segundo.example.com", "terceiro.example.com"), z.stream().map(ZonaCf::nome).toList());
            assertFalse(z.get(2).ativa());
        }
        assertEquals(3, cf.pedidos.stream().filter(p -> p.caminho().equals("/zones")).count(), "uma chamada por página");
    }

    @Test
    void semTotalPagesNemTotalCountOClienteSegueEnquantoAPaginaVierCheia() throws Exception {
        cf.paginaMaxima = 5;
        cf.semTotais = true;
        for (int i = 0; i < 13; i++) {
            cf.semear("lab-" + i + ".example.com", "CNAME", "x.cfargotunnel.com", "lab-desktop: demo");
        }
        try (ClienteCloudflare c = cliente()) {
            assertEquals(13, c.registrosDnsComComentarioQueComecaCom(CloudflareFalsa.ZONA, "lab-desktop:").size(), "as 3 páginas (5 + 5 + 3), sem total para guiar");
        }
        assertEquals(3, cf.pedidos.stream().filter(p -> p.caminho().endsWith("/dns_records")).count());
    }

    @Test
    void listaMaiorQueOTetoDePaginasDaErroEmVezDeCortarEmSilencio() throws Exception {
        cf.paginaMaxima = 1;
        cf.semTotais = true;
        for (int i = 0; i < 25; i++) {
            cf.semear("lab-" + i + ".example.com", "CNAME", "x.cfargotunnel.com", "lab-desktop: demo");
        }
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(() -> c.registrosDnsComComentarioQueComecaCom(CloudflareFalsa.ZONA, "lab-desktop:"));
            assertEquals(Tipo.RESPOSTA_INVALIDA, e.tipo());
            assertTrue(e.mensagem().contains("mais de 20 páginas"), e.mensagem());
        }
    }

    @Test
    void identificadorComFormatoEstranhoNaoChegaARede() throws Exception {
        try (ClienteCloudflare c = cliente()) {
            assertEquals(Tipo.RECUSADO, erro(() -> c.tuneis("../../etc")).tipo());
            assertEquals(Tipo.RECUSADO, erro(() -> c.removerDns(CloudflareFalsa.ZONA, "x/../y")).tipo());
        }
        assertEquals(List.of(), cf.pedidos);
    }

    // ---------------------------------------------------------------------------------------------
    // DNS
    // ---------------------------------------------------------------------------------------------

    @Test
    void criarListarEApagarUmCnameComComentario() throws Exception {
        try (ClienteCloudflare c = cliente()) {
            RegistroDnsCf r = c.criarCname(CloudflareFalsa.ZONA, "lab-demo.example.com", CloudflareFalsa.TUNEL_ARQUIVO + ".cfargotunnel.com", true, "lab-desktop: demo");
            assertEquals("lab-demo.example.com", r.nome());
            assertEquals("CNAME", r.tipo());
            assertTrue(r.proxied());
            assertEquals("lab-desktop: demo", r.comentario());
            JsonNode corpo = Json.mapper().readTree(cf.escritas().get(0).corpo());
            assertEquals("CNAME", corpo.path("type").asText());
            assertEquals(CloudflareFalsa.TUNEL_ARQUIVO + ".cfargotunnel.com", corpo.path("content").asText(), "o alvo é o UUID, nunca o nome do túnel");
            assertEquals(1, corpo.path("ttl").asInt());
            assertEquals("lab-desktop: demo", corpo.path("comment").asText());
            assertEquals(1, c.registrosDnsComONome(CloudflareFalsa.ZONA, "lab-demo.example.com").size());
            cf.semear("outro.example.com", "CNAME", "x.cfargotunnel.com", "do usuário, sem marca");
            assertEquals(List.of("lab-demo.example.com"), c.registrosDnsComComentarioQueComecaCom(CloudflareFalsa.ZONA, "lab-desktop:").stream().map(RegistroDnsCf::nome).toList(),
                "só os que levam a marca do app");
            c.removerDns(CloudflareFalsa.ZONA, r.id());
            assertEquals(List.of(), c.registrosDnsComONome(CloudflareFalsa.ZONA, "lab-demo.example.com"));
            assertEquals(Tipo.NAO_ENCONTRADO, erro(() -> c.removerDns(CloudflareFalsa.ZONA, r.id())).tipo(), "apagar de novo: não achou");
        }
    }

    @Test
    void nomeQueJaExisteViraJaExisteComAFrase() throws Exception {
        cf.semear("lab-demo.example.com", "CNAME", "x.cfargotunnel.com", "do usuário");
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(() -> c.criarCname(CloudflareFalsa.ZONA, "lab-demo.example.com", "y.cfargotunnel.com", true, "lab-desktop: demo"));
            assertEquals(Tipo.JA_EXISTE, e.tipo());
            assertEquals(81053, e.codigoDaCloudflare());
            assertTrue(e.mensagem().contains("Já existe"), e.mensagem());
            assertFalse(e.incerto(), "a Cloudflare recusou: nada foi criado");
        }
        assertEquals("do usuário", cf.registro("lab-demo.example.com").comentario, "o que era do usuário não foi tocado");
    }

    @Test
    void zonaQueNaoExisteViraNaoEncontrado() throws Exception {
        try (ClienteCloudflare c = cliente()) {
            assertEquals(Tipo.NAO_ENCONTRADO, erro(() -> c.registrosDnsComONome("e".repeat(32), "x.example.com")).tipo());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // túnel gerido pela API
    // ---------------------------------------------------------------------------------------------

    @Test
    void lerEGravarAConfiguracaoDoTunelDevolveOQueNaoEDoApp() throws Exception {
        CloudflareFalsa.Tunel t = cf.tunel(CloudflareFalsa.TUNEL_API);
        ObjectNode original = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"service\":\"http_status:404\"}],\"warp-routing\":{\"enabled\":true}}");
        t.configuracao = original;
        try (ClienteCloudflare c = cliente()) {
            ConfigDoTunel lida = c.configuracaoDoTunel(CloudflareFalsa.CONTA, CloudflareFalsa.TUNEL_API);
            assertEquals(3, lida.versao());
            ObjectNode nova = lida.config().deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) nova.get("ingress")).insert(0, Json.mapper().createObjectNode().put("hostname", "lab-demo.example.com").put("service", "http://demo-web:3000"));
            c.gravarConfiguracaoDoTunel(CloudflareFalsa.CONTA, CloudflareFalsa.TUNEL_API, nova);
        }
        assertTrue(t.configuracao.path("warp-routing").path("enabled").asBoolean(), "o que não é do app volta igual");
        assertEquals("lab-demo.example.com", t.configuracao.path("ingress").get(0).path("hostname").asText());
        assertEquals(4, t.versao);
        JsonNode corpo = Json.mapper().readTree(cf.escritas().get(0).corpo());
        assertTrue(corpo.has("config"), "o PUT leva {\"config\": ...}");
    }

    @Test
    void tunelSemConfiguracaoVoltaComObjetoVazio() throws Exception {
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = null;
        try (ClienteCloudflare c = cliente()) {
            ConfigDoTunel lida = c.configuracaoDoTunel(CloudflareFalsa.CONTA, CloudflareFalsa.TUNEL_API);
            assertTrue(lida.config().isObject());
            assertTrue(lida.config().isEmpty());
        }
    }

    @Test
    void tunelQueNaoExisteViraNaoEncontradoESemPermissaoDeEscritaNaoGrava() throws Exception {
        try (ClienteCloudflare c = cliente()) {
            assertEquals(Tipo.NAO_ENCONTRADO, erro(() -> c.configuracaoDoTunel(CloudflareFalsa.CONTA, "naoexiste")).tipo());
            cf.semPermissao(Permissao.TUNEL_ESCREVER);
            ObjectNode cfg = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"service\":\"http_status:404\"}]}");
            ErroCloudflare e = erro(() -> c.gravarConfiguracaoDoTunel(CloudflareFalsa.CONTA, CloudflareFalsa.TUNEL_API, cfg));
            assertEquals(Tipo.SEM_PERMISSAO, e.tipo());
            assertTrue(e.mensagem().contains("gravar a configuração do túnel"));
            assertEquals(3, cf.tunel(CloudflareFalsa.TUNEL_API).versao, "nada foi gravado");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 429, 5xx e prazo
    // ---------------------------------------------------------------------------------------------

    @Test
    void limiteDeTaxaEsperaORetryAfterERepete() throws Exception {
        cf.limitarTaxa("GET /accounts", 2, "7");
        try (ClienteCloudflare c = cliente()) {
            assertEquals(1, c.contas().size());
        }
        assertEquals(List.of(Duration.ofSeconds(7), Duration.ofSeconds(7)), esperas);
        assertEquals(3, cf.pedidos.stream().filter(p -> p.caminho().equals("/accounts")).count());
    }

    @Test
    void semRetryAfterEsperaOPadraoEUmRetryAfterGrandeECortado() throws Exception {
        cf.limitarTaxa("GET /accounts", 1, null).limitarTaxa("GET /zones", 1, "9999");
        try (ClienteCloudflare c = cliente()) {
            c.contas();
            c.zonas();
        }
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)), esperas, "padrão de 5 s; e o teto de 30 s");
    }

    @Test
    void depoisDeMuitasEsperasDesisteComUmaFrase() throws Exception {
        cf.limitarTaxa("GET /accounts", 99, "1");
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(c::contas);
            assertEquals(Tipo.LIMITE_DE_TAXA, e.tipo());
            assertTrue(e.mensagem().contains("limitando os pedidos"), e.mensagem());
        }
        assertEquals(3, esperas.size());
        assertEquals(4, cf.pedidos.size(), "a primeira e três repetições");
    }

    @Test
    void umaEscritaLimitadaPorTaxaEhRepetidaPoisACloudflareRecusouAntesDeExecutar() throws Exception {
        cf.limitarTaxa("POST /zones/.*/dns_records", 1, "1");
        try (ClienteCloudflare c = cliente()) {
            c.criarCname(CloudflareFalsa.ZONA, "lab-x.example.com", "y.cfargotunnel.com", true, "lab-desktop: x");
        }
        assertEquals(1, cf.registros.size(), "criado UMA vez só");
    }

    @Test
    void erro5xxEClaroENaoERepetidoSozinhoNemNaLeitura() throws Exception {
        cf.falhar("GET /zones", 502, "<html><body>Bad gateway</body></html>", 5);
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(c::zonas);
            assertEquals(Tipo.SERVIDOR_DA_CLOUDFLARE, e.tipo());
            assertEquals(502, e.status());
            assertTrue(e.mensagem().contains("erro 502") && e.mensagem().contains("Tente de novo"), e.mensagem());
            assertFalse(e.mensagem().contains("<html>"), "nunca o HTML da resposta");
            assertFalse(e.incerto(), "leitura: nada mudou");
        }
        assertEquals(1, cf.pedidos.size(), "uma chamada só: 5xx não se repete às cegas");
    }

    @Test
    void erro5xxNumaEscritaEIncertoENaoERepetido() throws Exception {
        cf.falhar("POST /zones/.*/dns_records", 503, erro503(), 5);
        try (ClienteCloudflare c = cliente()) {
            ErroCloudflare e = erro(() -> c.criarCname(CloudflareFalsa.ZONA, "lab-x.example.com", "y.cfargotunnel.com", true, "lab-desktop: x"));
            assertEquals(Tipo.SERVIDOR_DA_CLOUDFLARE, e.tipo());
            assertTrue(e.incerto(), "pode ter sido criado: quem chama confere antes de repetir");
            assertTrue(e.mensagem().contains("incerto"), e.mensagem());
        }
        assertEquals(1, cf.escritas().size());
    }

    private static String erro503() {
        return "{\"success\":false,\"errors\":[{\"code\":9999,\"message\":\"Service unavailable\"}]}";
    }

    @Test
    void semRespostaATempoViraErroDeRedeEMarcaIncertezaNaEscrita() throws Exception {
        cf.demorar("GET /accounts", 1500, 1).demorar("POST /zones/.*/dns_records", 1500, 1);
        try (ClienteCloudflare c = new ClienteCloudflare(cf.base(), token, ClienteCloudflare.Opcoes.rapida().comChamada(Duration.ofMillis(300)))) {
            ErroCloudflare leitura = erro(c::contas);
            assertEquals(Tipo.REDE, leitura.tipo());
            assertTrue(leitura.mensagem().contains("não respondeu a tempo"), leitura.mensagem());
            assertFalse(leitura.incerto());
            ErroCloudflare escrita = erro(() -> c.criarCname(CloudflareFalsa.ZONA, "lab-x.example.com", "y.cfargotunnel.com", true, "lab-desktop: x"));
            assertEquals(Tipo.REDE, escrita.tipo());
            assertTrue(escrita.incerto());
            assertTrue(escrita.mensagem().contains("Confira na Cloudflare"), escrita.mensagem());
        }
    }

    @Test
    void semConexaoNenhumaFraseTecnica() throws Exception {
        URI morta = cf.base();
        cf.close();
        try (ClienteCloudflare c = new ClienteCloudflare(morta, token, ClienteCloudflare.Opcoes.rapida())) {
            ErroCloudflare e = erro(c::verificarToken);
            assertEquals(Tipo.REDE, e.tipo());
            assertTrue(e.mensagem().contains("sem internet"), e.mensagem());
            assertFalse(e.mensagem().contains("Connection refused"));
        }
        cf = new CloudflareFalsa(); // para o descer() fechar algo
    }

    @Test
    void respostaQueNaoEJsonNemSucessoViraRespostaInvalida() throws Exception {
        cf.falhar("GET /accounts", 200, "isto não é json", 1);
        cf.falhar("GET /zones", 200, "{\"success\":true,\"result\":{\"nao\":\"lista\"}}", 1);
        try (ClienteCloudflare c = cliente()) {
            assertEquals(Tipo.RESPOSTA_INVALIDA, erro(c::contas).tipo());
            assertEquals(Tipo.RESPOSTA_INVALIDA, erro(c::zonas).tipo());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // o endereço da API e os redirecionamentos: o token nunca vai para outro host
    // ---------------------------------------------------------------------------------------------

    @Test
    void aPropriedadeDeTesteSoTrocaOEnderecoParaLoopback() {
        String antes = System.getProperty(ClienteCloudflare.PROPRIEDADE_DA_URL);
        try {
            System.setProperty(ClienteCloudflare.PROPRIEDADE_DA_URL, "http://127.0.0.1:9999/client/v4");
            assertEquals("127.0.0.1", ClienteCloudflare.urlBase().getHost());
            System.setProperty(ClienteCloudflare.PROPRIEDADE_DA_URL, "http://localhost:9999/client/v4");
            assertEquals("localhost", ClienteCloudflare.urlBase().getHost());
            for (String ruim : List.of("https://api.malvado.example/client/v4", "http://192.0.2.9:80/x", "https://127.0.0.1.malvado.example/", "ftp://localhost/", "https://evil.example/@127.0.0.1", "não é endereço")) {
                System.setProperty(ClienteCloudflare.PROPRIEDADE_DA_URL, ruim);
                assertEquals("api.cloudflare.com", ClienteCloudflare.urlBase().getHost(), "a propriedade com " + ruim + " é ignorada: o token não vai para lá");
            }
            System.clearProperty(ClienteCloudflare.PROPRIEDADE_DA_URL);
            assertEquals("api.cloudflare.com", ClienteCloudflare.urlBase().getHost());
        } finally {
            if (antes == null) {
                System.clearProperty(ClienteCloudflare.PROPRIEDADE_DA_URL);
            } else {
                System.setProperty(ClienteCloudflare.PROPRIEDADE_DA_URL, antes);
            }
        }
    }

    @Test
    void umRedirecionamentoNuncaLevaOTokenParaOutroHost() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> recebidoNoSegundo = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger pedidosNoSegundo = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer segundo = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        segundo.createContext("/", x -> {
            pedidosNoSegundo.incrementAndGet();
            recebidoNoSegundo.set(x.getRequestHeaders().getFirst("Authorization"));
            byte[] corpo = "{\"success\":true,\"result\":{\"status\":\"active\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, corpo.length);
            x.getResponseBody().write(corpo);
            x.close();
        });
        segundo.start();
        com.sun.net.httpserver.HttpServer primeiro = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        primeiro.createContext("/", x -> {
            x.getResponseHeaders().add("Location", "http://127.0.0.1:" + segundo.getAddress().getPort() + "/roubado");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        primeiro.start();
        try (ClienteCloudflare c = new ClienteCloudflare(URI.create("http://127.0.0.1:" + primeiro.getAddress().getPort() + "/client/v4"), token, ClienteCloudflare.Opcoes.rapida())) {
            assertThrows(ErroCloudflare.class, c::verificarToken);
        } finally {
            primeiro.stop(0);
            segundo.stop(0);
        }
        assertEquals(0, pedidosNoSegundo.get(), "o cliente não segue redirecionamento: o segundo host nem foi procurado");
        assertEquals(null, recebidoNoSegundo.get(), "e o cabeçalho Authorization não chegou lá");
    }
}
