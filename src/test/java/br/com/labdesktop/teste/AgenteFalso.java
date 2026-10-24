package br.com.labdesktop.teste;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * O agente do painel de mentira: um {@code HttpServer} do JDK em 127.0.0.1 com resposta programável. Reproduz
 * manias do agente real que o cliente HTTP do app precisa respeitar: a
 * destruição do socket diante de qualquer {@code Upgrade} (o que mataria um {@code HttpClient} em h2c).
 */
public final class AgenteFalso implements AutoCloseable {

    /** Uma requisição recebida, com tudo que os testes conferem. */
    public record Pedido(String metodo, String caminhoComQuery, String protocolo, Map<String, List<String>> cabecalhos,
                         String corpo) {

        public String cabecalho(String nome) {
            return cabecalhos.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(nome))
                .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }

        public String caminho() {
            int q = caminhoComQuery.indexOf('?');
            return q < 0 ? caminhoComQuery : caminhoComQuery.substring(0, q);
        }
    }

    /**
     * Resposta programada.
     *
     * @param atrasoMs espera antes de responder (prova de prazo)
     * @param emperrar manda os cabeçalhos prometendo um corpo e NÃO o entrega (prova de prazo do corpo)
     * @param cortar   fecha a conexão sem responder nada
     */
    public record Resposta(int status, String corpo, String tipo, Map<String, String> cabecalhos, long atrasoMs,
                           boolean emperrar, boolean cortar) {

        public static Resposta json(int status, String corpo) {
            return new Resposta(status, corpo, "application/json; charset=utf-8", Map.of(), 0, false, false);
        }

        public static Resposta texto(int status, String corpo) {
            return new Resposta(status, corpo, "text/plain; charset=utf-8", Map.of(), 0, false, false);
        }

        public static Resposta comAtraso(Resposta r, long ms) {
            return new Resposta(r.status, r.corpo, r.tipo, r.cabecalhos, ms, false, false);
        }

        public static Resposta corpoQueEmperra() {
            return new Resposta(200, "{}", "application/json", Map.of(), 0, true, false);
        }

        public static Resposta semResposta() {
            return new Resposta(0, "", "text/plain", Map.of(), 0, false, true);
        }

        public Resposta comCabecalho(String nome, String valor) {
            java.util.Map<String, String> c = new java.util.HashMap<>(cabecalhos);
            c.put(nome, valor);
            return new Resposta(status, corpo, tipo, c, atrasoMs, emperrar, cortar);
        }
    }

    private final HttpServer servidor;
    public final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    public final AtomicInteger upgradesRecebidos = new AtomicInteger();
    private volatile Function<Pedido, Resposta> manipulador = p -> Resposta.json(404, "{\"erro\":\"sem rota\"}");

    public AgenteFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agente-falso");
            t.setDaemon(true);
            return t;
        }));
        servidor.createContext("/", this::atender);
        servidor.start();
    }

    public int porta() {
        return servidor.getAddress().getPort();
    }

    public void responder(Function<Pedido, Resposta> m) {
        this.manipulador = m;
    }

    private void atender(HttpExchange troca) throws IOException {
        if (troca.getRequestHeaders().containsKey("Upgrade")) {
            // Como o upgrade handler do agente real: derruba a conexão sem responder nada.
            upgradesRecebidos.incrementAndGet();
            troca.close();
            return;
        }
        String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Pedido p = new Pedido(troca.getRequestMethod(), troca.getRequestURI().toString(), troca.getProtocol(),
            Map.copyOf(troca.getRequestHeaders()), corpo);
        pedidos.add(p);
        String host = p.cabecalho("Host");
        // Só o próprio computador (a porta não importa: pelo túnel ela é outra).
        String nomeDoHost = host == null ? "" : host.replaceAll(":\\d+$", "");
        if (!List.of("localhost", "127.0.0.1", "[::1]").contains(nomeDoHost)) {
            // Fora disso: 421 em texto puro.
            byte[] msg = "Host não permitido.".getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            troca.sendResponseHeaders(421, msg.length);
            try (OutputStream os = troca.getResponseBody()) {
                os.write(msg);
            }
            return;
        }
        Resposta r = manipulador.apply(p);
        if (r.cortar()) {
            troca.close();
            return;
        }
        if (r.atrasoMs() > 0) {
            try {
                Thread.sleep(r.atrasoMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (r.emperrar()) {
            troca.getResponseHeaders().set("Content-Type", r.tipo());
            troca.sendResponseHeaders(200, 100);
            troca.getResponseBody().write('{');
            troca.getResponseBody().flush();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            troca.close();
            return;
        }
        byte[] bytes = r.corpo().getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", r.tipo());
        troca.getResponseHeaders().set("Cache-Control", "no-store");
        r.cabecalhos().forEach((k, v) -> troca.getResponseHeaders().set(k, v));
        troca.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = troca.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            troca.close();
        }
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
