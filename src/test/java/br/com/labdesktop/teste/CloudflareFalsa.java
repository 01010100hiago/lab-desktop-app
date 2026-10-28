package br.com.labdesktop.teste;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A API da Cloudflare de MENTIRA para os testes: um {@code HttpServer} do JDK em 127.0.0.1 que guarda contas, zonas, túneis, configurações de túnel e
 * registros de DNS de verdade (na memória), confere o token (o certo, ou o inválido) e as permissões do token, pagina as listas, e pode ser
 * roteirizado para falhar (429 com {@code Retry-After}, 5xx, atraso, corte). NUNCA fala com a Cloudflare real.
 *
 * <p>Guarda cada pedido que recebeu ({@link Pedido}), com o cabeçalho {@code Authorization} à parte, para os testes provarem que o token só viaja
 * nesse cabeçalho (nunca na URL nem no corpo).
 */
public final class CloudflareFalsa implements AutoCloseable {

    public static final String CONTA = "a".repeat(32);
    public static final String ZONA = "b".repeat(32);
    public static final String TUNEL_ARQUIVO = "11111111-2222-3333-4444-555555555555";
    public static final String TUNEL_API = "99999999-8888-7777-6666-555555555555";
    public static final String TOKEN_CERTO = "TokenCertoDeTeste0123456789abcdefghijklmn";

    /** O que o token pode fazer. */
    public enum Permissao { CONTAS, ZONAS, DNS_LER, DNS_ESCREVER, TUNEL_LER, TUNEL_ESCREVER }

    public record Pedido(String metodo, String caminho, String query, String corpo, String autorizacao) { }

    /** Uma falha programada: as próximas {@code vezes} chamadas que casam com {@code metodo caminho} respondem assim. */
    private static final class Falha {
        final Pattern alvo;
        final int status;
        final String corpo;
        final Map<String, String> cabecalhos;
        final AtomicInteger restantes;
        final long atrasoMs;
        /** Faz o que o pedido manda (cria o registro, grava a configuração) e SÓ DEPOIS devolve a falha: a resposta se perdeu no caminho. */
        volatile boolean executarAntes;

        Falha(String alvo, int status, String corpo, Map<String, String> cabecalhos, int vezes, long atrasoMs) {
            this.alvo = Pattern.compile(alvo);
            this.status = status;
            this.corpo = corpo;
            this.cabecalhos = cabecalhos;
            this.restantes = new AtomicInteger(vezes);
            this.atrasoMs = atrasoMs;
        }
    }

    public record Conta(String id, String nome) { }

    public record Zona(String id, String nome, String contaId, String contaNome, String status) { }

    public static final class Tunel {
        public final String id;
        public final String nome;
        public final String configSrc;
        public volatile String status = "healthy";
        public volatile ObjectNode configuracao;
        public volatile int versao;
        public volatile boolean apagado;

        Tunel(String id, String nome, String configSrc) {
            this.id = id;
            this.nome = nome;
            this.configSrc = configSrc;
        }
    }

    public static final class Registro {
        public final String id;
        public volatile String nome;
        public volatile String tipo;
        public volatile String conteudo;
        public volatile boolean proxied;
        public volatile String comentario;

        Registro(String id, String nome, String tipo, String conteudo, boolean proxied, String comentario) {
            this.id = id;
            this.nome = nome;
            this.tipo = tipo;
            this.conteudo = conteudo;
            this.proxied = proxied;
            this.comentario = comentario;
        }
    }

    private final HttpServer servidor;
    public final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    public final List<Conta> contas = new CopyOnWriteArrayList<>();
    public final List<Zona> zonas = new CopyOnWriteArrayList<>();
    public final List<Tunel> tuneis = new CopyOnWriteArrayList<>();
    public final List<Registro> registros = new CopyOnWriteArrayList<>();
    private final List<Falha> falhas = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequencia = new AtomicInteger(1000);
    public volatile String tokenAceito = TOKEN_CERTO;
    public volatile String estadoDoToken = "active";
    public volatile Set<Permissao> permissoes = Set.of(Permissao.values());
    /** Quantos itens por página no máximo (para provar a paginação): 0 = o que o cliente pedir. */
    public volatile int paginaMaxima;

    public CloudflareFalsa() throws IOException {
        contas.add(new Conta(CONTA, "Conta de Teste"));
        zonas.add(new Zona(ZONA, "example.com", CONTA, "Conta de Teste", "active"));
        Tunel arquivo = new Tunel(TUNEL_ARQUIVO, "lab", "local");
        Tunel api = new Tunel(TUNEL_API, "projetos-na-nuvem", "cloudflare");
        api.configuracao = (ObjectNode) Json.mapper().createObjectNode().set("ingress", Json.mapper().createArrayNode()
            .add(Json.mapper().createObjectNode().put("service", "http_status:404")));
        api.versao = 3;
        tuneis.add(arquivo);
        tuneis.add(api);
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cloudflare-falsa");
            t.setDaemon(true);
            return t;
        }));
        servidor.createContext("/", this::atender);
        servidor.start();
    }

    public URI base() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/client/v4");
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    // ---------------------------------------------------------------------------------------------
    // roteiro
    // ---------------------------------------------------------------------------------------------

    /** As próximas {@code vezes} chamadas cujo "METODO caminho" casa com a regex respondem com este status e corpo. */
    public CloudflareFalsa falhar(String metodoECaminho, int status, String corpo, int vezes) {
        falhas.add(new Falha(metodoECaminho, status, corpo, Map.of(), vezes, 0));
        return this;
    }

    public CloudflareFalsa limitarTaxa(String metodoECaminho, int vezes, String retryAfter) {
        Map<String, String> c = retryAfter == null ? Map.of() : Map.of("Retry-After", retryAfter);
        falhas.add(new Falha(metodoECaminho, 429, erro(971, "Rate limited"), c, vezes, 0));
        return this;
    }

    /** As próximas {@code vezes} chamadas fazem o que pedem e depois respondem 504: o pedido chegou, a resposta se perdeu (resultado incerto para quem pediu). */
    public CloudflareFalsa executarEPerderAResposta(String metodoECaminho, int vezes) {
        Falha f = new Falha(metodoECaminho, 504, erro(9999, "Gateway timeout"), Map.of(), vezes, 0);
        f.executarAntes = true;
        falhas.add(f);
        return this;
    }

    public CloudflareFalsa demorar(String metodoECaminho, long ms, int vezes) {
        falhas.add(new Falha(metodoECaminho, -1, "", Map.of(), vezes, ms));
        return this;
    }

    public CloudflareFalsa semPermissao(Permissao... tirar) {
        Set<Permissao> p = new java.util.HashSet<>(permissoes);
        p.removeAll(List.of(tirar));
        permissoes = Set.copyOf(p);
        return this;
    }

    public Registro registro(String nome) {
        return registros.stream().filter(r -> r.nome.equals(nome)).findFirst().orElse(null);
    }

    public Registro semear(String nome, String tipo, String conteudo, String comentario) {
        Registro r = new Registro("r" + sequencia.incrementAndGet(), nome, tipo, conteudo, true, comentario);
        registros.add(r);
        return r;
    }

    public Tunel novoTunel(String id, String nome, String configSrc) {
        return new Tunel(id, nome, configSrc);
    }

    public Tunel tunel(String id) {
        return tuneis.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow();
    }

    public List<Pedido> escritas() {
        return pedidos.stream().filter(p -> !p.metodo().equals("GET")).toList();
    }

    // ---------------------------------------------------------------------------------------------
    // o servidor
    // ---------------------------------------------------------------------------------------------

    private static String erro(int codigo, String mensagem) {
        return "{\"success\":false,\"errors\":[{\"code\":" + codigo + ",\"message\":" + Json.mapper().valueToTree(mensagem) + "}],\"messages\":[],\"result\":null}";
    }

    private void responder(HttpExchange x, int status, String corpo, Map<String, String> cabecalhos) throws IOException {
        byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        cabecalhos.forEach((k, v) -> x.getResponseHeaders().set(k, v));
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            x.getResponseBody().write(b);
        }
        x.close();
    }

    private String ok(JsonNode resultado, Integer total, int pagina, int porPagina) {
        ObjectNode r = Json.mapper().createObjectNode();
        r.put("success", true);
        r.set("errors", Json.mapper().createArrayNode());
        r.set("messages", Json.mapper().createArrayNode());
        r.set("result", resultado);
        if (total != null) {
            ObjectNode info = r.putObject("result_info");
            info.put("page", pagina);
            info.put("per_page", porPagina);
            info.put("total_count", total);
            info.put("total_pages", Math.max(1, (int) Math.ceil(total / (double) porPagina)));
        }
        return r.toString();
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) {
            return m;
        }
        for (String par : q.split("&")) {
            int i = par.indexOf('=');
            m.put(URLDecoder.decode(i < 0 ? par : par.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private String pagina(List<? extends JsonNode> todos, Map<String, String> q) {
        int por = Integer.parseInt(q.getOrDefault("per_page", "20"));
        if (paginaMaxima > 0) {
            por = Math.min(por, paginaMaxima);
        }
        int pag = Integer.parseInt(q.getOrDefault("page", "1"));
        ArrayNode a = Json.mapper().createArrayNode();
        for (int i = (pag - 1) * por; i < Math.min(todos.size(), pag * por); i++) {
            a.add(todos.get(i));
        }
        return ok(a, todos.size(), pag, por);
    }

    private void atender(HttpExchange x) throws IOException {
        String metodo = x.getRequestMethod();
        String caminhoCompleto = x.getRequestURI().getRawPath();
        String caminho = caminhoCompleto.replaceFirst("^/client/v4", "");
        String query = x.getRequestURI().getRawQuery();
        String corpo = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = x.getRequestHeaders().getFirst("Authorization");
        pedidos.add(new Pedido(metodo, caminho, query == null ? "" : query, corpo, auth));
        try {
            for (Falha f : falhas) {
                if (f.restantes.get() > 0 && f.alvo.matcher(metodo + " " + caminho).matches() && f.restantes.getAndDecrement() > 0) {
                    if (f.atrasoMs > 0) {
                        Thread.sleep(f.atrasoMs);
                        if (f.status < 0) {
                            break; // só atrasou: segue o caminho normal depois
                        }
                    }
                    if (f.executarAntes) {
                        if (auth != null && auth.equals("Bearer " + tokenAceito)) {
                            rotearSemResponder(metodo, caminho, query(query), corpo);
                        }
                    }
                    responder(x, f.status, f.corpo, f.cabecalhos);
                    return;
                }
            }
            if (auth == null || !auth.equals("Bearer " + tokenAceito)) {
                responder(x, 401, erro(1000, "Invalid API Token"), Map.of());
                return;
            }
            rotear(x, metodo, caminho, query(query), corpo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            x.close();
        } catch (RuntimeException e) {
            responder(x, 500, erro(1, "falha da Cloudflare falsa: " + e), Map.of());
        }
    }

    /** O efeito de um pedido de escrita, sem responder (para "executou e a resposta se perdeu"). */
    private void rotearSemResponder(String metodo, String caminho, Map<String, String> q, String corpo) throws IOException {
        Matcher m;
        if (metodo.equals("POST") && (m = Pattern.compile("/zones/([^/]+)/dns_records").matcher(caminho)).matches()) {
            JsonNode c = Json.mapper().readTree(corpo);
            registros.add(new Registro("r" + sequencia.incrementAndGet(), c.path("name").asText("").toLowerCase(), c.path("type").asText(), c.path("content").asText(),
                c.path("proxied").asBoolean(false), c.path("comment").isMissingNode() ? "" : c.path("comment").asText()));
        } else if (metodo.equals("DELETE") && (m = Pattern.compile("/zones/([^/]+)/dns_records/([^/]+)").matcher(caminho)).matches()) {
            String id = m.group(2);
            registros.removeIf(r -> r.id.equals(id));
        } else if (metodo.equals("PUT") && (m = Pattern.compile("/accounts/([^/]+)/cfd_tunnel/([^/]+)/configurations").matcher(caminho)).matches()) {
            String id = m.group(2);
            tuneis.stream().filter(t -> t.id.equals(id)).findFirst().ifPresent(t -> {
                try {
                    t.configuracao = (ObjectNode) Json.mapper().readTree(corpo).path("config");
                    t.versao++;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private boolean pode(HttpExchange x, Permissao p) throws IOException {
        if (permissoes.contains(p)) {
            return true;
        }
        responder(x, 403, erro(10000, "Authentication error"), Map.of());
        return false;
    }

    private void rotear(HttpExchange x, String metodo, String caminho, Map<String, String> q, String corpo) throws IOException {
        Matcher m;
        if (caminho.equals("/user/tokens/verify") && metodo.equals("GET")) {
            ObjectNode r = Json.mapper().createObjectNode().put("id", "tok123").put("status", estadoDoToken);
            responder(x, 200, ok(r, null, 1, 1), Map.of());
        } else if (caminho.equals("/accounts") && metodo.equals("GET")) {
            if (!pode(x, Permissao.CONTAS)) {
                return;
            }
            List<ObjectNode> l = new ArrayList<>();
            contas.forEach(c -> l.add(Json.mapper().createObjectNode().put("id", c.id()).put("name", c.nome()).put("type", "standard")));
            responder(x, 200, pagina(l, q), Map.of());
        } else if (caminho.equals("/zones") && metodo.equals("GET")) {
            if (!pode(x, Permissao.ZONAS)) {
                return;
            }
            List<ObjectNode> l = new ArrayList<>();
            for (Zona z : zonas) {
                ObjectNode n = Json.mapper().createObjectNode().put("id", z.id()).put("name", z.nome()).put("status", z.status());
                n.putObject("account").put("id", z.contaId()).put("name", z.contaNome());
                l.add(n);
            }
            responder(x, 200, pagina(l, q), Map.of());
        } else if ((m = Pattern.compile("/accounts/([^/]+)/cfd_tunnel").matcher(caminho)).matches() && metodo.equals("GET")) {
            if (!pode(x, Permissao.TUNEL_LER)) {
                return;
            }
            List<ObjectNode> l = new ArrayList<>();
            for (Tunel t : tuneis) {
                ObjectNode n = Json.mapper().createObjectNode().put("id", t.id).put("name", t.nome).put("status", t.status);
                if (t.configSrc != null) {
                    n.put("config_src", t.configSrc);
                }
                if (t.apagado) {
                    n.put("deleted_at", "2026-01-01T00:00:00Z");
                    if ("false".equals(q.get("is_deleted"))) {
                        continue;
                    }
                }
                l.add(n);
            }
            responder(x, 200, pagina(l, q), Map.of());
        } else if ((m = Pattern.compile("/accounts/([^/]+)/cfd_tunnel/([^/]+)/configurations").matcher(caminho)).matches()) {
            String idDoTunel = m.group(2);
            Tunel t = tuneis.stream().filter(u -> u.id.equals(idDoTunel)).findFirst().orElse(null);
            if (t == null) {
                responder(x, 404, erro(1003, "Tunnel not found"), Map.of());
            } else if (metodo.equals("GET")) {
                if (!pode(x, Permissao.TUNEL_LER)) {
                    return;
                }
                ObjectNode r = Json.mapper().createObjectNode().put("tunnel_id", t.id).put("version", t.versao).put("source", t.configSrc);
                r.set("config", t.configuracao);
                responder(x, 200, ok(r, null, 1, 1), Map.of());
            } else if (metodo.equals("PUT")) {
                if (!pode(x, Permissao.TUNEL_ESCREVER)) {
                    return;
                }
                JsonNode c = Json.mapper().readTree(corpo).path("config");
                if (!c.isObject() || !c.path("ingress").isArray()) {
                    responder(x, 400, erro(1002, "Invalid config"), Map.of());
                    return;
                }
                t.configuracao = (ObjectNode) c;
                t.versao++;
                responder(x, 200, ok(Json.mapper().createObjectNode().put("tunnel_id", t.id).put("version", t.versao), null, 1, 1), Map.of());
            } else {
                responder(x, 405, erro(1, "método"), Map.of());
            }
        } else if ((m = Pattern.compile("/zones/([^/]+)/dns_records").matcher(caminho)).matches()) {
            String idDaZona = m.group(1);
            if (zonas.stream().noneMatch(z -> z.id().equals(idDaZona))) {
                responder(x, 404, erro(7003, "Could not route to /zones/" + idDaZona + "/dns_records"), Map.of());
            } else if (metodo.equals("GET")) {
                if (!pode(x, Permissao.DNS_LER)) {
                    return;
                }
                List<ObjectNode> l = new ArrayList<>();
                for (Registro r : registros) {
                    if (q.containsKey("name") && !q.get("name").equalsIgnoreCase(r.nome)) {
                        continue;
                    }
                    if (q.containsKey("comment.startswith") && (r.comentario == null || !r.comentario.startsWith(q.get("comment.startswith")))) {
                        continue;
                    }
                    l.add(Json.mapper().createObjectNode().put("id", r.id).put("name", r.nome).put("type", r.tipo).put("content", r.conteudo).put("proxied", r.proxied)
                        .put("comment", r.comentario));
                }
                responder(x, 200, pagina(l, q), Map.of());
            } else if (metodo.equals("POST")) {
                if (!pode(x, Permissao.DNS_ESCREVER)) {
                    return;
                }
                JsonNode c = Json.mapper().readTree(corpo);
                String nome = c.path("name").asText("").toLowerCase();
                if (registros.stream().anyMatch(r -> r.nome.equals(nome) && (r.tipo.equals("CNAME") || r.tipo.equals("A") || r.tipo.equals("AAAA")))) {
                    responder(x, 400, erro(81053, "An A, AAAA or CNAME record with that host already exists."), Map.of());
                    return;
                }
                Registro r = new Registro("r" + sequencia.incrementAndGet(), nome, c.path("type").asText(), c.path("content").asText(), c.path("proxied").asBoolean(false),
                    c.path("comment").isMissingNode() ? "" : c.path("comment").asText());
                registros.add(r);
                responder(x, 200, ok(Json.mapper().createObjectNode().put("id", r.id).put("name", r.nome).put("type", r.tipo).put("content", r.conteudo)
                    .put("proxied", r.proxied).put("comment", r.comentario), null, 1, 1), Map.of());
            } else {
                responder(x, 405, erro(1, "método"), Map.of());
            }
        } else if ((m = Pattern.compile("/zones/([^/]+)/dns_records/([^/]+)").matcher(caminho)).matches() && metodo.equals("DELETE")) {
            if (!pode(x, Permissao.DNS_ESCREVER)) {
                return;
            }
            String idDoRegistro = m.group(2);
            boolean tirou = registros.removeIf(r -> r.id.equals(idDoRegistro));
            responder(x, tirou ? 200 : 404, tirou ? ok(Json.mapper().createObjectNode().put("id", idDoRegistro), null, 1, 1) : erro(81044, "Record does not exist."), Map.of());
        } else {
            responder(x, 404, erro(7000, "No route for that URI"), Map.of());
        }
    }
}
