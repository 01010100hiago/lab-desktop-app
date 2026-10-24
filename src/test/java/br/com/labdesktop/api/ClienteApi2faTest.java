package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.modelo.Ativacao2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.AgenteFalso.Pedido;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARQUITETURA 10.1 e CONTRATO-API 4.12: o 401 o pedido de código chama o {@link PedidoDeCodigo2fa}, envia
 * a confirmação do código e repete a chamada original UMA vez (também em POST e PUT); sem laço; um único
 * pedido para chamadas concorrentes.
 */
class ClienteApi2faTest {

    private static final String LIMITES_JSON = "{\"disponivel\":false,\"freio\":{\"pct\":null,\"ativo\":false}}";
    private static final String PROJETOS_JSON = "{\"projetos\":[{\"nome\":\"x\"}]}";
    private static final String REQUERIDO = "{\"erro\":\"Confirme o código do autenticador.\",\"codigo\":\"totp_requerido\"}";
    private static final String ATIVADO = "{\"ok\":true,\"ativado_ate\":\"2026-11-19T12:00:00.000Z\"}";

    private AgenteFalso agente;
    private TokenAgente token;
    private final AtomicBoolean confirmado = new AtomicBoolean();
    private final List<String> codigosRecebidos = new CopyOnWriteArrayList<>();

    /** O pedido de teste: devolve os códigos da fila, registra o aviso de cada pedido e cancela quando acaba. */
    private static final class PedidoFalso implements PedidoDeCodigo2fa {
        final List<String> codigos;
        final List<String> avisos = new CopyOnWriteArrayList<>();
        final List<Integer> aguardes = new CopyOnWriteArrayList<>();
        final AtomicInteger chamadas = new AtomicInteger();

        PedidoFalso(String... codigos) {
            this.codigos = new ArrayList<>(List.of(codigos));
        }

        @Override
        public synchronized Optional<String> pedir(String servidor, String aviso) {
            chamadas.incrementAndGet();
            avisos.add(aviso);
            aguardes.add(0);
            return codigos.isEmpty() ? Optional.empty() : Optional.of(codigos.remove(0));
        }

        @Override
        public synchronized Optional<String> pedir(String servidor, String aviso, int aguardeSegundos) {
            Optional<String> r = pedir(servidor, aviso);
            aguardes.set(aguardes.size() - 1, aguardeSegundos);
            return r;
        }
    }

    @BeforeEach
    void abrir() throws IOException {
        agente = new AgenteFalso();
        token = TokenAgente.gerar();
        confirmado.set(false);
        // O agente de mentira com 2FA ativo: sem confirmação, tudo menos a confirmação do código é 401 pedido de código.
        agente.responder(this::comDoisFatores);
    }

    @AfterEach
    void fechar() {
        agente.close();
    }

    private Resposta comDoisFatores(Pedido p) {
        if ("/api/acesso/2fa".equals(p.caminho())) {
            String corpo = p.corpo();
            String codigo = corpo.replaceAll(".*\"codigo\":\"([^\"]*)\".*", "$1");
            codigosRecebidos.add(codigo);
            if ("123456".equals(codigo)) {
                confirmado.set(true);
                return Resposta.json(200, ATIVADO);
            }
            return Resposta.json(401, "{\"erro\":\"Código incorreto ou já usado.\",\"codigo\":\"totp_incorreto\"}");
        }
        if (!confirmado.get()) {
            return Resposta.json(401, REQUERIDO);
        }
        return switch (p.caminho()) {
            case "/api/limites" -> Resposta.json(200, LIMITES_JSON);
            case "/api/projetos" -> Resposta.json(200, PROJETOS_JSON);
            case "/api/projetos/x/limite" -> Resposta.json(200, "{\"ok\":true,\"limite\":{\"teto_pct\":20}}");
            default -> Resposta.json(200, "{\"ok\":true,\"saida\":\"feito\"}");
        };
    }

    private ClienteApi api(PedidoDeCodigo2fa pedido) {
        return new ClienteApi("lab", agente.porta(), token, pedido);
    }

    private List<String> sequencia() {
        return agente.pedidos.stream().map(p -> p.metodo() + " " + p.caminho()).toList();
    }

    @Test
    void totpRequeridoPedeOCodigoConfirmaERepeteAChamadaUmaVez() throws Exception {
        PedidoFalso pedido = new PedidoFalso("123456");
        try (ClienteApi api = api(pedido)) {
            assertNotNull(api.limites());
        }
        assertEquals(1, pedido.chamadas.get());
        assertNull(pedido.avisos.get(0), "primeiro pedido sem aviso");
        assertEquals(List.of("GET /api/limites", "POST /api/acesso/2fa", "GET /api/limites"), sequencia());
        // O POST do 2FA leva a mesma identificação e o código normalizado no corpo.
        Pedido post = agente.pedidos.get(1);
        assertEquals(token.cabecalhoAuthorization(), post.cabecalho("Authorization"));
        assertEquals("{\"codigo\":\"123456\"}", post.corpo());
        assertEquals("application/json", post.cabecalho("Content-Type"));
    }

    @Test
    void repeteTambemPostEPutComOMesmoCorpo() throws Exception {
        PedidoFalso pedido = new PedidoFalso("123456");
        try (ClienteApi api = api(pedido)) {
            assertEquals("feito", api.iniciar("x").saida());
        }
        assertEquals(List.of("POST /api/projetos/x/iniciar", "POST /api/acesso/2fa", "POST /api/projetos/x/iniciar"),
            sequencia());
        assertEquals(agente.pedidos.get(0).corpo(), agente.pedidos.get(2).corpo());

        confirmado.set(false);
        agente.pedidos.clear();
        PedidoFalso outro = new PedidoFalso("123456");
        try (ClienteApi api = api(outro)) {
            assertEquals(20, api.definirLimite("x", 20).limite().tetoPct());
        }
        assertEquals(List.of("PUT /api/projetos/x/limite", "POST /api/acesso/2fa", "PUT /api/projetos/x/limite"), sequencia());
        assertEquals("{\"teto_pct\":20}", agente.pedidos.get(2).corpo());
    }

    @Test
    void aceitaOEspacoDoMeioDoAutenticador() throws Exception {
        PedidoFalso pedido = new PedidoFalso("123 456");
        try (ClienteApi api = api(pedido)) {
            api.limites();
        }
        assertEquals(List.of("123456"), codigosRecebidos);
    }

    @Test
    void segundaRespostaTotpRequeridoViraErroComumSemLaco() {
        // Agente "quebrado": continua exigindo o código mesmo depois de aceitar o POST.
        agente.responder(p -> "/api/acesso/2fa".equals(p.caminho()) ? Resposta.json(200, ATIVADO) : Resposta.json(401, REQUERIDO));
        PedidoFalso pedido = new PedidoFalso("123456", "123456");
        try (ClienteApi api = api(pedido)) {
            NaoAutorizado e = assertThrows(NaoAutorizado.class, api::limites);
            assertTrue(e.totpRequerido());
        }
        assertEquals(1, pedido.chamadas.get(), "um pedido só; a repetição não pergunta de novo");
        assertEquals(List.of("GET /api/limites", "POST /api/acesso/2fa", "GET /api/limites"), sequencia());
    }

    @Test
    void cancelarFalhaComNaoAutorizadoTotpRequeridoSemRepetirNada() {
        PedidoFalso pedido = new PedidoFalso(); // sem códigos: o usuário cancela
        try (ClienteApi api = api(pedido)) {
            NaoAutorizado e = assertThrows(NaoAutorizado.class, api::projetos);
            assertTrue(e.totpRequerido());
            assertEquals("totp_requerido", e.codigo());
            assertFalse(e.tokenRecusado());
        }
        assertEquals(1, pedido.chamadas.get());
        assertEquals(List.of("GET /api/projetos"), sequencia(), "nada de POST nem repetição");
    }

    @Test
    void codigoIncorretoReabreOPedidoComAviso() throws Exception {
        PedidoFalso pedido = new PedidoFalso("000000", "123456");
        try (ClienteApi api = api(pedido)) {
            assertNotNull(api.limites());
        }
        assertEquals(2, pedido.chamadas.get());
        assertNull(pedido.avisos.get(0));
        assertTrue(pedido.avisos.get(1).startsWith("Código incorreto ou já usado."), pedido.avisos.get(1));
        assertEquals(List.of("000000", "123456"), codigosRecebidos);
        assertEquals(List.of("GET /api/limites", "POST /api/acesso/2fa", "POST /api/acesso/2fa", "GET /api/limites"),
            sequencia());
    }

    @Test
    void codigoForaDoFormatoNemVaiAoAgente() throws Exception {
        PedidoFalso pedido = new PedidoFalso("12ab", "12345", "123456");
        try (ClienteApi api = api(pedido)) {
            api.limites();
        }
        assertEquals(3, pedido.chamadas.get());
        assertEquals("Informe o código de 6 dígitos.", pedido.avisos.get(1));
        assertEquals(List.of("123456"), codigosRecebidos, "só o código bem formado foi enviado");
    }

    @Test
    void bloqueioDoAgenteMostraAguardeNSegundos() throws Exception {
        AtomicInteger tentativas = new AtomicInteger();
        agente.responder(p -> {
            if ("/api/acesso/2fa".equals(p.caminho())) {
                if (tentativas.incrementAndGet() == 1) {
                    return Resposta.json(429, "{\"erro\":\"Muitas tentativas. Aguarde 60 s.\",\"codigo\":\"totp_bloqueado\",\"aguarde_s\":7}")
                        .comCabecalho("Retry-After", "7");
                }
                confirmado.set(true);
                return Resposta.json(200, ATIVADO);
            }
            return confirmado.get() ? Resposta.json(200, LIMITES_JSON) : Resposta.json(401, REQUERIDO);
        });
        PedidoFalso pedido = new PedidoFalso("111111", "123456");
        try (ClienteApi api = api(pedido)) {
            api.limites();
        }
        assertEquals(2, pedido.chamadas.get());
        assertEquals(List.of(0, 7), pedido.aguardes, "o segundo pedido avisa os 7 s do bloqueio");
        assertTrue(pedido.avisos.get(1).contains("Aguarde 7 s"), pedido.avisos.get(1));
    }

    @Test
    void doisFatoresDesativadoNoMeioRepeteSemCodigo() throws Exception {
        AtomicInteger limites = new AtomicInteger();
        agente.responder(p -> {
            if ("/api/acesso/2fa".equals(p.caminho())) {
                return Resposta.json(409, "{\"erro\":\"O 2FA não está ativo neste servidor.\",\"codigo\":\"totp_nao_configurado\"}");
            }
            return limites.incrementAndGet() == 1 ? Resposta.json(401, REQUERIDO) : Resposta.json(200, LIMITES_JSON);
        });
        PedidoFalso pedido = new PedidoFalso("123456");
        try (ClienteApi api = api(pedido)) {
            assertNotNull(api.limites());
        }
        assertEquals(List.of("GET /api/limites", "POST /api/acesso/2fa", "GET /api/limites"), sequencia());
    }

    @Test
    void tokenRevogadoNoMeioDoPedidoViraTokenRecusado() {
        agente.responder(p -> "/api/acesso/2fa".equals(p.caminho())
            ? Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}") : Resposta.json(401, REQUERIDO));
        PedidoFalso pedido = new PedidoFalso("123456");
        try (ClienteApi api = api(pedido)) {
            NaoAutorizado e = assertThrows(NaoAutorizado.class, api::limites);
            assertTrue(e.tokenRecusado());
        }
    }

    @Test
    void erroDeRedeNoPostDoCodigoNaoEhReenviado() {
        agente.responder(p -> "/api/acesso/2fa".equals(p.caminho()) ? Resposta.semResposta() : Resposta.json(401, REQUERIDO));
        PedidoFalso pedido = new PedidoFalso("123456", "123456");
        try (ClienteApi api = api(pedido)) {
            assertThrows(ErroRede.class, api::limites);
        }
        Espera.dormir(300);
        assertEquals(1, pedido.chamadas.get());
        assertEquals(List.of("GET /api/limites", "POST /api/acesso/2fa"), sequencia(),
            "o resultado de uma falha de rede é incerto: o código pode ter sido consumido");
    }

    @Test
    void erroSemCodigoContinuaTokenRecusadoENaoPerguntaNada() {
        agente.responder(p -> Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}"));
        PedidoFalso pedido = new PedidoFalso("123456");
        try (ClienteApi api = api(pedido)) {
            NaoAutorizado e = assertThrows(NaoAutorizado.class, api::versao);
            assertTrue(e.tokenRecusado());
        }
        assertEquals(0, pedido.chamadas.get());
        assertEquals(1, agente.pedidos.size());
    }

    @Test
    void chamadasConcorrentesCompartilhamUmUnicoPedido() throws Exception {
        int fios = 6;
        CountDownLatch liberarOCodigo = new CountDownLatch(1);
        AtomicInteger pedidos = new AtomicInteger();
        AtomicInteger recusados = new AtomicInteger();
        PedidoDeCodigo2fa pedido = (servidor, aviso) -> {
            pedidos.incrementAndGet();
            try {
                liberarOCodigo.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of("123456");
        };
        agente.responder(p -> {
            Resposta r = comDoisFatores(p);
            if (r.status() == 401 && r.corpo().contains("totp_requerido")) {
                recusados.incrementAndGet();
            }
            return r;
        });
        ExecutorService pool = Executors.newFixedThreadPool(fios);
        try (ClienteApi api = api(pedido)) {
            List<Future<Object>> resultados = new ArrayList<>();
            for (int i = 0; i < fios; i++) {
                int n = i;
                resultados.add(pool.submit(() -> n % 2 == 0 ? api.limites() : api.projetos()));
            }
            // Só libera o código quando TODAS já levaram 401: assim todas esperam o mesmo pedido.
            Espera.ate(Duration.ofSeconds(20), () -> "401s=" + recusados.get(), () -> recusados.get() == fios);
            liberarOCodigo.countDown();
            for (Future<Object> f : resultados) {
                assertNotNull(f.get(20, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, pedidos.get(), "um único diálogo");
        assertEquals(1, codigosRecebidos.size(), "um único POST /api/acesso/2fa");
        // E cada uma das seis repetiu a chamada original.
        long get200 = agente.pedidos.stream().filter(p -> !"/api/acesso/2fa".equals(p.caminho())).count();
        assertEquals(fios * 2L, get200);
    }

    @Test
    void chamadaQueTomou401DepoisDeOutraConfirmarNaoPerguntaDeNovo() throws Exception {
        PedidoFalso pedido = new PedidoFalso("123456");
        try (ClienteApi api = api(pedido)) {
            api.limites(); // confirma
            assertEquals(1, pedido.chamadas.get());
            // Agora o agente "esquece" (a confirmação venceu): a PRÓXIMA chamada pergunta de novo, uma vez.
            confirmado.set(false);
            pedido.codigos.add("123456");
            api.projetos();
            assertEquals(2, pedido.chamadas.get());
        }
    }

    @Test
    void confirmar2faDiretoUmaTentativaSemDialogo() throws Exception {
        PedidoFalso pedido = new PedidoFalso();
        try (ClienteApi api = api(pedido)) {
            Ativacao2fa a = api.confirmar2fa("123 456");
            assertTrue(a.ok());
            assertEquals(Instant.parse("2026-11-19T12:00:00.000Z"), a.ativadoAte());

            NaoAutorizado e = assertThrows(NaoAutorizado.class, () -> api.confirmar2fa("000000"));
            assertEquals("totp_incorreto", e.codigo());
            assertThrows(IllegalArgumentException.class, () -> api.confirmar2fa("12"));
        }
        assertEquals(0, pedido.chamadas.get());
    }

    @Test
    void oCodigoNuncaApareceNasMensagensDeErro() {
        agente.responder(p -> "/api/acesso/2fa".equals(p.caminho())
            ? Resposta.json(500, "{\"erro\":\"Não consegui ler ou gravar o estado do 2FA no servidor.\"}")
            : Resposta.json(401, REQUERIDO));
        PedidoFalso pedido = new PedidoFalso("654321");
        try (ClienteApi api = api(pedido)) {
            ErroApi e = assertThrows(ErroApi.class, api::limites);
            assertFalse(e.getMessage().contains("654321"));
            assertEquals(500, e.status());
        }
    }
}
