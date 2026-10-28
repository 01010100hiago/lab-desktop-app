package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** "Está no ar?" contra um servidor falso em loopback: o app respondeu, ou o PORQUÊ de não ter respondido, com prazo e sem prender. */
class TestadorDeUrlTest {

    private HttpServer servidor;
    private volatile int status = 200;
    private volatile boolean corpoVazio;
    private volatile String servidorHeader = "";
    private final AtomicInteger pedidos = new AtomicInteger();
    private final List<Duration> esperas = new ArrayList<>();

    @BeforeEach
    void subir() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.createContext("/", x -> {
            pedidos.incrementAndGet();
            byte[] b = corpoVazio ? new byte[0] : "ola".getBytes();
            if (!servidorHeader.isEmpty()) {
                x.getResponseHeaders().set("Server", servidorHeader);
            }
            x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                x.getResponseBody().write(b);
            }
            x.close();
        });
        servidor.start();
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    private TestadorDeUrl.Https testador(String host) {
        return new TestadorDeUrl.Https(h -> URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/"), Duration.ofSeconds(6), Duration.ofSeconds(2), Duration.ofSeconds(2), esperas::add);
    }

    @Test
    void qualquerRespostaQueNaoEErroDoTunelEstaNoAr() {
        for (int s : new int[] {200, 301, 401, 403, 404}) {
            status = s;
            corpoVazio = false;
            TestadorDeUrl.Resultado r = testador("x").testar("lab-demo.example.com", Cancelamento.NUNCA);
            assertEquals(TestadorDeUrl.Estado.NO_AR, r.estado(), "status " + s);
            assertEquals(s, r.status());
            assertTrue(r.frase().startsWith("No ar"), r.frase());
        }
    }

    @Test
    void erroDoTunelOuDoContainerDiaOMotivo() {
        status = 502;
        TestadorDeUrl.Resultado r = testador("x").testar("lab-demo.example.com", Cancelamento.NUNCA);
        assertEquals(TestadorDeUrl.Estado.FORA_DO_AR, r.estado());
        assertTrue(r.frase().contains("o seu container não respondeu"), r.frase());
        assertTrue(r.frase().contains("rede edge"), r.frase());
        assertTrue(pedidos.get() >= 2, "tenta de novo até o prazo: " + pedidos.get());
        assertTrue(esperas.size() >= 1);
        status = 530;
        assertTrue(testador("x").testar("h", Cancelamento.NUNCA).frase().contains("não achou o túnel conectado"));
        status = 500;
        assertTrue(testador("x").testar("h", Cancelamento.NUNCA).frase().contains("erro 500"));
    }

    @Test
    void quatrocentosEQuatroSemConteudoDaCloudflareFicaEmDuvidaEOuveSoUmaVez() {
        status = 404;
        corpoVazio = true;
        servidorHeader = "cloudflare";
        pedidos.set(0);
        TestadorDeUrl.Resultado r = testador("x").testar("lab-demo.example.com", Cancelamento.NUNCA);
        assertEquals(TestadorDeUrl.Estado.EM_DUVIDA, r.estado());
        assertTrue(r.frase().contains("regra final do túnel"), r.frase());
        assertEquals(1, pedidos.get(), "dúvida não repete");
        assertEquals(false, r.noAr(), "em dúvida não se diz 'no ar'");
    }

    @Test
    void aFraseDeCadaFalhaDeConexao() {
        assertTrue(TestadorDeUrl.Https.resultadoDe(new java.net.UnknownHostException("lab-x.example.com")).frase().contains("DNS novo pode levar alguns minutos"));
        assertTrue(TestadorDeUrl.Https.resultadoDe(new java.io.IOException(new java.net.UnknownHostException("x"))).frase().contains("DNS novo pode levar alguns minutos"));
        assertTrue(TestadorDeUrl.Https.resultadoDe(new java.net.ConnectException("nodename nor servname provided")).frase().contains("DNS novo"));
        assertTrue(TestadorDeUrl.Https.resultadoDe(new javax.net.ssl.SSLHandshakeException("alert")).frase().contains("certificado do endereço ainda não está pronto"));
        assertTrue(TestadorDeUrl.Https.resultadoDe(new java.net.http.HttpTimeoutException("x")).frase().contains("não respondeu a tempo"));
        assertTrue(TestadorDeUrl.Https.resultadoDe(new java.net.ConnectException("Connection refused")).frase().contains("Não consegui abrir o endereço"));
        for (java.io.IOException e : List.of(new java.net.UnknownHostException("x"), new java.net.ConnectException("Connection refused"), new javax.net.ssl.SSLException("x"))) {
            assertEquals(TestadorDeUrl.Estado.FORA_DO_AR, TestadorDeUrl.Https.resultadoDe(e).estado());
        }
    }

    @Test
    void conexaoRecusadaTemFraseEOPrazoTotalAcaba() {
        TestadorDeUrl.Https t = new TestadorDeUrl.Https(h -> URI.create("http://127.0.0.1:1/"), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2), esperas::add);
        TestadorDeUrl.Resultado r = t.testar("x", Cancelamento.NUNCA);
        assertEquals(TestadorDeUrl.Estado.FORA_DO_AR, r.estado());
        assertTrue(r.frase().contains("Não consegui abrir o endereço"), r.frase());
        assertTrue(esperas.size() >= 1, "tentou de novo antes de desistir");
    }

    @Test
    void cancelarPara() {
        status = 502;
        Cancelamento c = new Cancelamento();
        c.cancelar();
        TestadorDeUrl.Resultado r = testador("x").testar("h", c);
        assertEquals(TestadorDeUrl.Estado.CANCELADO, r.estado());
        assertEquals(0, pedidos.get());
    }

    @Test
    void servidorQueNaoRespondeATempoTemFrase() throws Exception {
        servidor.removeContext("/");
        servidor.createContext("/", x -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.close();
        });
        TestadorDeUrl.Https t = new TestadorDeUrl.Https(h -> URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/"), Duration.ofMillis(900), Duration.ofMillis(100),
            Duration.ofMillis(300), esperas::add);
        TestadorDeUrl.Resultado r = t.testar("x", Cancelamento.NUNCA);
        assertEquals(TestadorDeUrl.Estado.FORA_DO_AR, r.estado());
        assertTrue(r.frase().contains("não respondeu a tempo"), r.frase());
    }
}
