package br.com.labdesktop.cloudflare;

import br.com.labdesktop.Versao;
import br.com.labdesktop.cloudflare.ErroCloudflare.Tipo;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O cliente HTTP da API da Cloudflare (v4), do próprio computador do usuário: o token NUNCA passa pelo servidor. Cliente HTTP do JDK, sem dependência nova.
 *
 * <ul>
 *   <li><b>HTTPS sempre.</b> A única exceção é um endereço de {@code loopback} (os testes, com um servidor falso em {@code HttpServer}): o token nunca
 *       sai em texto puro para outro lugar. O endereço base é configurável ({@link #urlBase()}: a propriedade {@value #PROPRIEDADE_DA_URL}, só para
 *       teste).</li>
 *   <li><b>Prazos:</b> conexão e chamada com tempo-limite; o corpo é lido com teto de 4 MiB.</li>
 *   <li><b>429:</b> espera o {@code Retry-After} (ou 5 s), até um número pequeno de vezes e um teto de espera; só então vira
 *       {@link Tipo#LIMITE_DE_TAXA}. É seguro repetir, porque a Cloudflare recusa o pedido antes de executá-lo. <b>Nenhuma outra falha é repetida
 *       sozinha</b> (nem leitura): 5xx vira {@link Tipo#SERVIDOR_DA_CLOUDFLARE} e, numa escrita, "incerto".</li>
 *   <li><b>Erros em texto:</b> a frase da Cloudflare entra limpa ({@link TextoSeguro}), sem segredo ({@link Redator}) e sem o token, que é cortado do
 *       texto por garantia. O token nunca vai a log, URL, {@code toString} nem mensagem.</li>
 *   <li><b>O token:</b> o cliente guarda uma CÓPIA e a zera em {@link #close()}.</li>
 * </ul>
 */
public final class ClienteCloudflare implements ApiCloudflare {

    public static final String URL_PADRAO = "https://api.cloudflare.com/client/v4";
    /** Só para teste: troca o endereço da API (precisa ser https, ou um endereço de loopback). */
    public static final String PROPRIEDADE_DA_URL = "labdesktop.cloudflare.url";

    private static final Logger LOG = LoggerFactory.getLogger(ClienteCloudflare.class);
    private static final int TETO_DA_RESPOSTA = 4 * 1024 * 1024;
    private static final int PAGINA = 50;
    private static final int MAXIMO_DE_PAGINAS = 20;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** Os códigos da Cloudflare que querem dizer "este registro já existe". */
    private static final List<Integer> CODIGOS_DE_REGISTRO_EXISTENTE = List.of(81053, 81057, 81058);

    /** Quem espera entre uma tentativa e outra do 429 (nos testes, não dorme de verdade). */
    @FunctionalInterface
    public interface Esperador {
        void esperar(Duration duracao) throws InterruptedException;
    }

    /**
     * Os tempos e limites do cliente.
     *
     * @param conexao            prazo para abrir a conexão
     * @param chamada            prazo de cada chamada (a resposta inteira)
     * @param esperasPor429      quantas vezes espera e repete depois de um 429 (0: nunca)
     * @param esperaMaximaPor429 teto de cada espera (um {@code Retry-After} maior que isso é cortado)
     * @param esperaSemCabecalho quanto espera quando o 429 vem sem {@code Retry-After}
     */
    public record Opcoes(Duration conexao, Duration chamada, int esperasPor429, Duration esperaMaximaPor429, Duration esperaSemCabecalho, Esperador esperador) {
        public static Opcoes padrao() {
            return new Opcoes(Duration.ofSeconds(5), Duration.ofSeconds(25), 3, Duration.ofSeconds(30), Duration.ofSeconds(5), d -> Thread.sleep(d));
        }

        /** Para os testes: tempos curtos e sem dormir. */
        public static Opcoes rapida() {
            return new Opcoes(Duration.ofSeconds(2), Duration.ofSeconds(3), 3, Duration.ofSeconds(30), Duration.ofSeconds(5), d -> { });
        }

        public Opcoes comChamada(Duration nova) {
            return new Opcoes(conexao, nova, esperasPor429, esperaMaximaPor429, esperaSemCabecalho, esperador);
        }

        public Opcoes comEsperas(int vezes, Esperador novo) {
            return new Opcoes(conexao, chamada, vezes, esperaMaximaPor429, esperaSemCabecalho, novo);
        }
    }

    private final URI base;
    private final TokenCloudflare token;
    private final Opcoes opcoes;
    private final HttpClient http;
    private volatile java.util.function.IntConsumer aoEsperarOLimite = s -> { };

    /** O endereço da API: o padrão da Cloudflare, ou o da propriedade de teste. */
    public static URI urlBase() {
        String p = System.getProperty(PROPRIEDADE_DA_URL);
        if (p == null || p.isBlank()) {
            return URI.create(URL_PADRAO);
        }
        // a propriedade existe só para os testes (uma Cloudflare falsa na própria máquina): o token iria para QUALQUER host que ela dissesse, então só vale se for loopback
        try {
            URI u = URI.create(p.strip());
            String esq = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (ehLoopback(u) && (esq.equals("http") || esq.equals("https"))) {
                return u;
            }
        } catch (IllegalArgumentException e) {
            // endereço que nem é endereço: cai no padrão
        }
        org.slf4j.LoggerFactory.getLogger(ClienteCloudflare.class).warn("A propriedade {} só vale para um endereço da própria máquina (127.0.0.1, localhost ou ::1); usando a Cloudflare de verdade.",
            PROPRIEDADE_DA_URL);
        return URI.create(URL_PADRAO);
    }

    /** O endereço é da própria máquina (loopback)? */
    static boolean ehLoopback(URI u) {
        String host = u.getHost() == null ? "" : u.getHost();
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
    }

    /**
     * @param base  o endereço da API (https, ou loopback nos testes)
     * @param token o cliente guarda uma CÓPIA (zerada em {@link #close()}); o original continua de quem o entregou
     */
    public ClienteCloudflare(URI base, TokenCloudflare token, Opcoes opcoes) {
        String esquema = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = base.getHost() == null ? "" : base.getHost();
        boolean loopback = ehLoopback(base);
        if (!(esquema.equals("https") || (esquema.equals("http") && loopback))) {
            throw new IllegalArgumentException("O token da Cloudflare só vai por HTTPS.");
        }
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.token = token.copia();
        this.opcoes = opcoes;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(loopback ? ProxySelector.of(null) : ProxySelector.getDefault())
            .connectTimeout(opcoes.conexao())
            .executor(Fundo.ioVirtual())
            .build();
    }

    /** Avisa (na thread que chama) quantos segundos o cliente vai esperar por causa de um 429, para a tela dizer o que está acontecendo. */
    public void aoEsperarOLimite(java.util.function.IntConsumer ouvinte) {
        this.aoEsperarOLimite = ouvinte == null ? s -> { } : ouvinte;
    }

    @Override
    public void close() {
        http.shutdownNow();
        token.zerar();
    }

    @Override
    public String toString() {
        return "ClienteCloudflare[" + base.getHost() + ", token=redigido]";
    }

    // =====================================================================================================
    // Leituras
    // =====================================================================================================

    @Override
    public TokenVerificado verificarToken() throws ErroCloudflare {
        JsonNode r = chamar("GET", "/user/tokens/verify", null, null, "conferir o token", false).path("result");
        String status = r.path("status").asText("");
        if (!status.equals("active")) {
            throw new ErroCloudflare(Tipo.TOKEN_INVALIDO, "O token existe, mas não está ativo na Cloudflare (estado: " + (status.isEmpty() ? "desconhecido" : limpar(status))
                + "). Ative-o ou crie outro e cole de novo.", 200, 0, false, null);
        }
        return new TokenVerificado(r.path("id").asText(""), status, r.path("expires_on").asText(""));
    }

    @Override
    public List<ContaCf> contas() throws ErroCloudflare {
        List<ContaCf> l = new ArrayList<>();
        for (JsonNode n : listar("/accounts", "", "listar as contas")) {
            l.add(new ContaCf(n.path("id").asText(""), limpar(n.path("name").asText(""))));
        }
        return l;
    }

    @Override
    public List<ZonaCf> zonas() throws ErroCloudflare {
        List<ZonaCf> l = new ArrayList<>();
        for (JsonNode n : listar("/zones", "", "listar os domínios")) {
            l.add(new ZonaCf(n.path("id").asText(""), n.path("name").asText("").toLowerCase(java.util.Locale.ROOT), n.path("account").path("id").asText(""),
                limpar(n.path("account").path("name").asText("")), n.path("status").asText("")));
        }
        return l;
    }

    @Override
    public List<TunelCf> tuneis(String contaId) throws ErroCloudflare {
        String c = id(contaId);
        List<TunelCf> l = new ArrayList<>();
        for (JsonNode n : listar("/accounts/" + c + "/cfd_tunnel", "is_deleted=false", "listar os túneis")) {
            boolean removido = !n.path("deleted_at").isMissingNode() && !n.path("deleted_at").isNull();
            if (removido) {
                continue;
            }
            l.add(new TunelCf(n.path("id").asText(""), limpar(n.path("name").asText("")), n.path("status").asText(""), origemDoTunel(n), false));
        }
        return l;
    }

    private static ConfiguracaoCloudflare.Tipo origemDoTunel(JsonNode n) {
        String src = n.path("config_src").asText("");
        if (src.equals("cloudflare") || (src.isEmpty() && n.path("remote_config").asBoolean(false))) {
            return ConfiguracaoCloudflare.Tipo.API;
        }
        return ConfiguracaoCloudflare.Tipo.ARQUIVO; // "local" e os túneis de antes do campo: o arquivo do servidor manda
    }

    @Override
    public ConfigDoTunel configuracaoDoTunel(String contaId, String tunelId) throws ErroCloudflare {
        JsonNode r = chamar("GET", "/accounts/" + id(contaId) + "/cfd_tunnel/" + id(tunelId) + "/configurations", null, null, "ler a configuração do túnel", false)
            .path("result");
        JsonNode config = r.path("config");
        if (config.isMissingNode() || config.isNull() || !config.isObject()) {
            config = Json.mapper().createObjectNode();
        }
        return new ConfigDoTunel(config, r.path("version").asInt(0));
    }

    @Override
    public List<RegistroDnsCf> registrosDnsComONome(String zonaId, String nomeCompleto) throws ErroCloudflare {
        return registros(zonaId, "name=" + cod(nomeCompleto), "listar os registros de DNS");
    }

    @Override
    public List<RegistroDnsCf> registrosDnsComComentarioQueComecaCom(String zonaId, String prefixoDoComentario) throws ErroCloudflare {
        List<RegistroDnsCf> todos = registros(zonaId, "comment.startswith=" + cod(prefixoDoComentario), "listar os registros de DNS");
        List<RegistroDnsCf> certos = new ArrayList<>();
        for (RegistroDnsCf r : todos) {
            if (r.comentario() != null && r.comentario().startsWith(prefixoDoComentario)) { // a Cloudflare filtra; o app confere de novo
                certos.add(r);
            }
        }
        return certos;
    }

    private List<RegistroDnsCf> registros(String zonaId, String filtro, String oQue) throws ErroCloudflare {
        List<RegistroDnsCf> l = new ArrayList<>();
        for (JsonNode n : listar("/zones/" + id(zonaId) + "/dns_records", filtro, oQue)) {
            l.add(registro(n));
        }
        return l;
    }

    private static RegistroDnsCf registro(JsonNode n) {
        return new RegistroDnsCf(n.path("id").asText(""), n.path("name").asText("").toLowerCase(java.util.Locale.ROOT), n.path("type").asText(""),
            n.path("content").asText(""), n.path("proxied").asBoolean(false), n.path("comment").isNull() ? "" : n.path("comment").asText(""));
    }

    // =====================================================================================================
    // Escritas (só depois do clique de confirmação)
    // =====================================================================================================

    @Override
    public void gravarConfiguracaoDoTunel(String contaId, String tunelId, JsonNode config) throws ErroCloudflare {
        ObjectNode corpo = Json.mapper().createObjectNode();
        corpo.set("config", config);
        chamar("PUT", "/accounts/" + id(contaId) + "/cfd_tunnel/" + id(tunelId) + "/configurations", null, corpo.toString(), "gravar a configuração do túnel", true);
    }

    @Override
    public RegistroDnsCf criarCname(String zonaId, String nomeCompleto, String alvo, boolean proxied, String comentario) throws ErroCloudflare {
        ObjectNode corpo = Json.mapper().createObjectNode();
        corpo.put("type", "CNAME");
        corpo.put("name", nomeCompleto);
        corpo.put("content", alvo);
        corpo.put("proxied", proxied);
        corpo.put("ttl", 1);
        if (comentario != null && !comentario.isEmpty()) {
            corpo.put("comment", comentario);
        }
        JsonNode r = chamar("POST", "/zones/" + id(zonaId) + "/dns_records", null, corpo.toString(), "criar o registro de DNS", true).path("result");
        return registro(r);
    }

    @Override
    public void removerDns(String zonaId, String registroId) throws ErroCloudflare {
        chamar("DELETE", "/zones/" + id(zonaId) + "/dns_records/" + id(registroId), null, null, "apagar o registro de DNS", true);
    }

    // =====================================================================================================
    // O pedido
    // =====================================================================================================

    /** Lê todas as páginas de uma lista (até um teto). */
    private List<JsonNode> listar(String caminho, String filtro, String oQue) throws ErroCloudflare {
        List<JsonNode> todos = new ArrayList<>();
        for (int pagina = 1; pagina <= MAXIMO_DE_PAGINAS; pagina++) {
            String query = (filtro.isEmpty() ? "" : filtro + "&") + "per_page=" + PAGINA + "&page=" + pagina;
            JsonNode raiz = chamar("GET", caminho, query, null, oQue, false);
            JsonNode resultado = raiz.path("result");
            if (!resultado.isArray()) {
                throw new ErroCloudflare(Tipo.RESPOSTA_INVALIDA, "A Cloudflare respondeu algo que o app não entende ao " + oQue + ".");
            }
            resultado.forEach(todos::add);
            int total = raiz.path("result_info").path("total_pages").asInt(1);
            if (resultado.isEmpty() || pagina >= total) {
                break;
            }
        }
        return todos;
    }

    private static String id(String valor) throws ErroCloudflare {
        if (valor == null || !ID.matcher(valor).matches()) {
            throw new ErroCloudflare(Tipo.RECUSADO, "Um identificador da Cloudflare tem formato que o app não aceita.");
        }
        return valor;
    }

    private static String cod(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode chamar(String metodo, String caminho, String query, String corpo, String oQue, boolean escrita) throws ErroCloudflare {
        URI uri = URI.create(base + caminho + (query == null || query.isEmpty() ? "" : "?" + query));
        for (int tentativa = 0; ; tentativa++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(opcoes.chamada())
                .header("Authorization", token.cabecalhoAuthorization())
                .header("Accept", "application/json")
                .header("User-Agent", Versao.userAgent());
            if (corpo != null) {
                b.header("Content-Type", "application/json");
            }
            b.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8));
            long inicio = System.nanoTime();
            HttpResponse<InputStream> resposta;
            byte[] bytes;
            try {
                resposta = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                bytes = lerOCorpo(resposta.body());
            } catch (HttpTimeoutException e) {
                throw new ErroCloudflare(Tipo.REDE, "A Cloudflare não respondeu a tempo ao " + oQue + "." + (escrita ? " Confira na Cloudflare se o pedido chegou a ser feito antes de repetir." : ""),
                    0, 0, escrita, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ErroCloudflare(Tipo.CANCELADO, "Cancelado.", 0, 0, escrita, e);
            } catch (IOException e) {
                throw new ErroCloudflare(Tipo.REDE, "Não consegui falar com a Cloudflare ao " + oQue + " (sem internet, ou a conexão caiu)."
                    + (escrita ? " Confira na Cloudflare se o pedido chegou a ser feito antes de repetir." : ""), 0, 0, escrita, e);
            }
            int status = resposta.statusCode();
            LOG.debug("[cloudflare] {} {} -> {} em {} ms", metodo, caminho, status, (System.nanoTime() - inicio) / 1_000_000);
            if (bytes.length > TETO_DA_RESPOSTA) {
                throw new ErroCloudflare(Tipo.RESPOSTA_INVALIDA, "A resposta da Cloudflare é grande demais para o app.", status, 0, escrita, null);
            }
            if (status == 429) {
                if (tentativa >= opcoes.esperasPor429()) {
                    throw new ErroCloudflare(Tipo.LIMITE_DE_TAXA, "A Cloudflare está limitando os pedidos (muitos em pouco tempo). Espere um minuto e tente de novo.", 429, 0, false, null);
                }
                Duration espera = esperaDo429(resposta);
                aoEsperarOLimite.accept((int) Math.max(1, espera.toSeconds()));
                try {
                    opcoes.esperador().esperar(espera);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ErroCloudflare(Tipo.CANCELADO, "Cancelado.", 429, 0, false, e);
                }
                continue; // a Cloudflare recusou ANTES de executar: repetir é seguro, até para escrita
            }
            return interpretar(status, bytes, oQue, escrita);
        }
    }

    /** Lê o corpo com o prazo da chamada (o {@code timeout} do pedido só cobre até os cabeçalhos): um corpo que trava não prende a thread. */
    private byte[] lerOCorpo(InputStream in) throws IOException, InterruptedException {
        java.util.concurrent.CompletableFuture<byte[]> lido = new java.util.concurrent.CompletableFuture<>();
        Thread leitor = Thread.ofVirtual().start(() -> {
            try (InputStream i = in) {
                lido.complete(i.readNBytes(TETO_DA_RESPOSTA + 1));
            } catch (IOException | RuntimeException e) {
                lido.completeExceptionally(e);
            }
        });
        try {
            return lido.get(opcoes.chamada().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            leitor.interrupt();
            try {
                in.close();
            } catch (IOException ignorado) {
                // já falhou por prazo
            }
            throw new HttpTimeoutException("o corpo da resposta não chegou a tempo");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    private Duration esperaDo429(HttpResponse<?> r) {
        Duration espera = opcoes.esperaSemCabecalho();
        String h = r.headers().firstValue("Retry-After").orElse("").strip();
        if (!h.isEmpty()) {
            try {
                espera = Duration.ofSeconds(Math.max(0, Long.parseLong(h)));
            } catch (NumberFormatException e) {
                // data HTTP em vez de segundos: o app não a calcula, fica com a espera padrão
            }
        }
        return espera.compareTo(opcoes.esperaMaximaPor429()) > 0 ? opcoes.esperaMaximaPor429() : espera;
    }

    private JsonNode interpretar(int status, byte[] corpo, String oQue, boolean escrita) throws ErroCloudflare {
        JsonNode raiz = null;
        try {
            raiz = corpo.length == 0 ? null : Json.mapper().readTree(corpo);
        } catch (JsonProcessingException e) {
            // não é JSON: abaixo vira erro de resposta inválida (ou o erro HTTP, se o status já era de erro)
        } catch (IOException e) {
            // idem
        }
        boolean sucesso = raiz != null && raiz.path("success").asBoolean(false);
        if (status >= 200 && status < 300 && sucesso) {
            return raiz;
        }
        int codigo = 0;
        String textoDaCloudflare = "";
        if (raiz != null && raiz.path("errors").isArray() && !raiz.path("errors").isEmpty()) {
            codigo = raiz.path("errors").get(0).path("code").asInt(0);
            List<String> frases = new ArrayList<>();
            for (JsonNode e : raiz.path("errors")) {
                String m = e.path("message").asText("");
                if (!m.isBlank()) {
                    frases.add(m);
                }
            }
            textoDaCloudflare = limpar(String.join("; ", frases));
        }
        String ela = textoDaCloudflare.isEmpty() ? "" : " A Cloudflare disse: " + TextoSeguro.truncar(textoDaCloudflare, 300);
        if (status >= 200 && status < 300) {
            throw new ErroCloudflare(Tipo.RESPOSTA_INVALIDA, "A Cloudflare respondeu algo que o app não entende ao " + oQue + "." + ela, status, codigo, escrita, null);
        }
        if (status == 401 || (status == 400 && codigo == 6003)) {
            throw new ErroCloudflare(Tipo.TOKEN_INVALIDO, "A Cloudflare não aceitou o token (ele pode ter sido apagado, ter expirado ou estar errado). Crie um token novo e cole de novo. "
                + "Se você criou o token em \"Tokens de API da conta\", crie-o em Meu perfil > Tokens de API." + ela, status, codigo, false, null);
        }
        if (status == 403) {
            throw new ErroCloudflare(Tipo.SEM_PERMISSAO, "O token não tem permissão para " + oQue + "." + ela + " Confira as permissões do token (a tela Cloudflare lista as que o app precisa).",
                status, codigo, false, null);
        }
        if (status == 404) {
            throw new ErroCloudflare(Tipo.NAO_ENCONTRADO, "A Cloudflare não achou o que o app pediu ao " + oQue + " (foi apagado, ou o token não o enxerga)." + ela, status, codigo, false, null);
        }
        if (status == 409 || CODIGOS_DE_REGISTRO_EXISTENTE.contains(codigo)) {
            throw new ErroCloudflare(Tipo.JA_EXISTE, "Já existe na Cloudflare um registro com esse nome." + ela, status, codigo, false, null);
        }
        if (status >= 500) {
            throw new ErroCloudflare(Tipo.SERVIDOR_DA_CLOUDFLARE, "A Cloudflare está com problema agora (erro " + status + ") ao " + oQue + "."
                + (escrita ? " O resultado é incerto: confira na Cloudflare antes de repetir." : " Tente de novo daqui a pouco.") + ela, status, codigo, escrita, null);
        }
        throw new ErroCloudflare(Tipo.RECUSADO, "A Cloudflare recusou o pedido ao " + oQue + " (erro " + status + ")." + ela, status, codigo, false, null);
    }

    /** O texto da Cloudflare, limpo (sem controle nem HTML), sem segredo e sem o token. */
    private String limpar(String texto) {
        String t = Redator.redigir(TextoSeguro.limparLinha(texto == null ? "" : texto));
        String valor = null;
        try {
            valor = token.valorParaFiltro();
        } catch (RuntimeException e) {
            return t; // o token já foi zerado
        }
        return valor.isEmpty() ? t : t.replace(valor, Redator.MASCARA);
    }
}
