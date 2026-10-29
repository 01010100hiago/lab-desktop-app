package br.com.labdesktop.ssh;

import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ProxyTcp;
import br.com.labdesktop.cofre.Pin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Monta o mundo inteiro de um teste de {@link ConexaoSsh}: servidor SSH embutido, agente falso, cofre falso,
 * proxy opcional (para buraco negro) e uma sonda programável. Fecha tudo no {@code close()}.
 */
final class CenarioDeConexao implements AutoCloseable {

    static final String TOKEN = "Bearer lab_" + "A".repeat(43);

    final ServidorSshDeTeste ssh;
    final AgenteFalso agente;
    final CofreFalso cofre;
    final ProxyTcp proxy;
    final ConexaoSsh conexao;
    final List<InfoConexao> historico = new CopyOnWriteArrayList<>();
    final AtomicInteger chamadasDaSonda = new AtomicInteger();
    final AtomicReference<SondaDoAgente.Resultado> respostaDaSonda = new AtomicReference<>(SondaDoAgente.Resultado.OK);
    /** Se não nulo, a sonda faz um GET de verdade pelo túnel e usa o resultado dele. */
    private volatile boolean sondaPorHttp;

    private CenarioDeConexao(ServidorSshDeTeste ssh, AgenteFalso agente, CofreFalso cofre, ProxyTcp proxy, ConfigSsh cfg,
                             PoliticaDeBackoff backoff, br.com.labdesktop.transporte.AbridorDeDestino abridor) {
        this.ssh = ssh;
        this.agente = agente;
        this.cofre = cofre;
        this.proxy = proxy;
        SondaDoAgente sonda = tunel -> {
            chamadasDaSonda.incrementAndGet();
            return sondaPorHttp ? sondarPorHttp(tunel) : respostaDaSonda.get();
        };
        this.conexao = abridor != null
            ? new ConexaoSsh(CofreFalso.ID, cofre, cfg, sonda, br.com.labdesktop.util.Agendador.doFundo(),
                backoff != null ? backoff : new PoliticaDeBackoff(cfg.escalaBackoff(), Math::random), abridor)
            : backoff == null
            ? new ConexaoSsh(CofreFalso.ID, cofre, cfg, sonda)
            : new ConexaoSsh(CofreFalso.ID, cofre, cfg, sonda, br.com.labdesktop.util.Agendador.doFundo(), backoff);
        this.conexao.observar(historico::add);
        agente.responder(p -> {
            if ("/api/versao".equals(p.caminho())) {
                String auth = p.cabecalho("Authorization");
                if (TOKEN.equals(auth)) {
                    return AgenteFalso.Resposta.json(200, "{\"api\":1,\"painel\":\"v22.0.0\",\"exigeToken\":false,\"cliente\":\"mac-de-teste\",\"totp\":\"desligado\"}");
                }
                return AgenteFalso.Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}");
            }
            return AgenteFalso.Resposta.json(404, "{\"erro\":\"sem rota\"}");
        });
    }

    /** Servidor SSH ed25519 que aceita a chave do cofre; a conexão passa direto (sem proxy). */
    static CenarioDeConexao novo() throws IOException, GeneralSecurityException {
        return novo(false, ConfigSsh.rapida(), true, null);
    }

    static CenarioDeConexao comProxy() throws IOException, GeneralSecurityException {
        return novo(true, ConfigSsh.rapida(), true, null);
    }

    static CenarioDeConexao novo(boolean comProxy, ConfigSsh cfg, boolean comPin, PoliticaDeBackoff backoff)
        throws IOException, GeneralSecurityException {
        ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519();
        AgenteFalso agente = new AgenteFalso();
        ProxyTcp proxy = comProxy ? new ProxyTcp(ssh.porta()) : null;
        int portaDoCliente = comProxy ? proxy.porta() : ssh.porta();
        List<Pin> pins = comPin ? List.of(ssh.pin()) : List.of();
        CofreFalso cofre = new CofreFalso("127.0.0.1", portaDoCliente, pins).comPortaDoAgente(agente.porta());
        ssh.aceitar(cofre.par().getPublic());
        return new CenarioDeConexao(ssh, agente, cofre, proxy, cfg, backoff, null);
    }

    /**
     * O servidor conecta SEM PORTA ABERTA: o cofre tem o transporte dado (e o pin do servidor SSH embutido), o endereço guardado é um nome que nunca resolve, e o
     * {@code abridor} é quem sobe o túnel (com ferramentas FALSAS: nunca a aws nem o cloudflared de verdade).
     */
    static CenarioDeConexao pelaTunel(br.com.labdesktop.cofre.Transporte transporte, br.com.labdesktop.transporte.AbridorDeDestino abridor, ConfigSsh cfg,
                                      PoliticaDeBackoff backoff) throws IOException, GeneralSecurityException {
        ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519();
        AgenteFalso agente = new AgenteFalso();
        CofreFalso cofre = new CofreFalso("127.0.0.1", 1, List.of(ssh.pin())).comPortaDoAgente(agente.porta()).comTransporte(transporte);
        ssh.aceitar(cofre.par().getPublic());
        return new CenarioDeConexao(ssh, agente, cofre, null, cfg, backoff, abridor);
    }

    CenarioDeConexao sondaPorHttp() {
        sondaPorHttp = true;
        return this;
    }

    InfoConexao info() {
        return conexao.info();
    }

    EstadoConexao estado() {
        return conexao.info().estado();
    }

    boolean jaPassouPor(EstadoConexao e) {
        return historico.stream().anyMatch(i -> i.estado() == e);
    }

    /** GET {@code /api/versao} pelo túnel, com ou sem o cabeçalho; devolve o status HTTP. */
    int getPeloTunel(String caminho, boolean comAutorizacao) throws IOException, InterruptedException {
        int porta = conexao.tunel().orElseThrow().portaLocal();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho)).GET()
                .timeout(Duration.ofSeconds(5));
            if (comAutorizacao) {
                b.header("Authorization", TOKEN);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } finally {
            http.close();
        }
    }

    private static SondaDoAgente.Resultado sondarPorHttp(TunelSsh tunel) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tunel.portaLocal() + "/api/versao"))
                .header("Authorization", TOKEN).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            return switch (r.statusCode()) {
                case 200 -> SondaDoAgente.Resultado.OK;
                case 401 -> SondaDoAgente.Resultado.TOKEN_RECUSADO;
                default -> SondaDoAgente.Resultado.AGENTE_FORA;
            };
        } catch (IOException e) {
            return SondaDoAgente.Resultado.AGENTE_FORA;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SondaDoAgente.Resultado.AGENTE_FORA;
        } finally {
            http.close();
        }
    }

    <T> T com(Function<CenarioDeConexao, T> f) {
        return f.apply(this);
    }

    @Override
    public void close() {
        conexao.fechar().join();
        try {
            if (proxy != null) {
                proxy.close();
            }
            agente.close();
            ssh.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
