package br.com.labdesktop.teste;

import br.com.labdesktop.cofre.Pin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.ServiceFactory;
import org.apache.sshd.common.Service;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.forward.StaticDecisionForwardingFilter;
import org.apache.sshd.server.forward.TcpForwardingFilter;
import org.apache.sshd.server.session.ServerUserAuthServiceFactory;

/**
 * Um {@code SshServer} embutido do MINA, com chave de host gerada na hora, que registra tudo o que os testes
 * precisam provar: quais chaves foram oferecidas, quantas vezes o cliente chegou a pedir o serviço de
 * autenticação, que linhas de comando rodaram e o que veio por stdin.
 */
public final class ServidorSshDeTeste implements AutoCloseable {

    /** O que um comando remoto de teste faz com a linha de comando e o stdin. */
    @FunctionalInterface
    public interface Manipulador {
        /** Escreve o stdout em {@code saida}; devolve o status de saída. */
        int executar(String linhaDeComando, String stdin, StringBuilder saida);
    }

    public final SshServer sshd;
    public final KeyPair chaveDoHost;
    /** Impressão de cada chave pública que o cliente ofereceu na autenticação. */
    public final List<String> chavesOferecidas = new CopyOnWriteArrayList<>();
    /** Quantas vezes o cliente pediu o serviço {@code ssh-userauth}. Zero = nem tentou autenticar. */
    public final AtomicInteger pedidosDeAutenticacao = new AtomicInteger();
    public final AtomicInteger autenticacoesAceitas = new AtomicInteger();
    public final List<String> comandos = new CopyOnWriteArrayList<>();
    public final List<String> entradas = new CopyOnWriteArrayList<>();
    public final AtomicInteger sessoesAbertas = new AtomicInteger();
    /** Destinos de forward que o cliente pediu (host:porta), quando o filtro restritivo está ligado. */
    public final List<String> destinosPedidos = new CopyOnWriteArrayList<>();

    private volatile List<PublicKey> aceitas;
    private volatile Manipulador manipulador = (c, in, out) -> 0;

    private ServidorSshDeTeste(KeyPair chaveDoHost, List<PublicKey> aceitas) throws IOException {
        this.chaveDoHost = chaveDoHost;
        this.aceitas = aceitas;
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(chaveDoHost));
        sshd.setPublickeyAuthenticator((usuario, chave, sessao) -> {
            chavesOferecidas.add(KeyUtils.getFingerPrint(chave));
            boolean ok = this.aceitas.stream().anyMatch(a -> KeyUtils.compareKeys(chave, a));
            if (ok) {
                autenticacoesAceitas.incrementAndGet();
            }
            return ok;
        });
        // Conta o pedido do serviço de autenticação: a sondagem NÃO pode chegar nem a pedi-lo.
        sshd.setServiceFactories(List.of(new ServiceFactory() {
            @Override
            public String getName() {
                return ServerUserAuthServiceFactory.INSTANCE.getName();
            }

            @Override
            public Service create(Session sessao) throws IOException {
                pedidosDeAutenticacao.incrementAndGet();
                return ServerUserAuthServiceFactory.INSTANCE.create(sessao);
            }
        }, org.apache.sshd.server.session.ServerConnectionServiceFactory.INSTANCE));
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.setCommandFactory((canal, linha) -> {
            comandos.add(linha);
            return new ComandoDeTeste(linha);
        });
        sshd.start();
    }

    public static ServidorSshDeTeste criar(String algoritmoDoHost, PublicKey... aceitas) throws IOException, GeneralSecurityException {
        int bits = switch (algoritmoDoHost) {
            case "ecdsa-sha2-nistp256" -> 256;
            case "ecdsa-sha2-nistp384" -> 384;
            default -> 256;
        };
        return new ServidorSshDeTeste(KeyUtils.generateKeyPair(algoritmoDoHost, bits), List.of(aceitas));
    }

    public static ServidorSshDeTeste ed25519(PublicKey... aceitas) throws IOException, GeneralSecurityException {
        return criar("ssh-ed25519", aceitas);
    }

    public int porta() {
        return sshd.getPort();
    }

    public Pin pin() {
        return Pin.de(chaveDoHost.getPublic());
    }

    public void aceitar(PublicKey... chaves) {
        this.aceitas = List.of(chaves);
    }

    public void comandoRemoto(Manipulador m) {
        this.manipulador = m;
    }

    /** Só aceita encaminhar para 127.0.0.1:{@code porta} (o que o {@code permitopen} do authorized_keys faz). */
    public void permitirEncaminhamentoSomentePara(int porta) {
        sshd.setForwardingFilter(new StaticDecisionForwardingFilter(true) {
            @Override
            public boolean canConnect(TcpForwardingFilter.Type tipo, SshdSocketAddress destino, Session sessao) {
                destinosPedidos.add(destino.getHostName() + ":" + destino.getPort());
                return "127.0.0.1".equals(destino.getHostName()) && destino.getPort() == porta;
            }
        });
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
    }

    private final class ComandoDeTeste implements Command {
        private final String linha;
        private InputStream entrada;
        private OutputStream saida;
        private OutputStream erro;
        private ExitCallback chamada;

        ComandoDeTeste(String linha) {
            this.linha = linha;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.entrada = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.saida = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.erro = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.chamada = callback;
        }

        @Override
        public void start(ChannelSession canal, Environment env) {
            sessoesAbertas.incrementAndGet();
            Thread t = new Thread(() -> {
                try {
                    ByteArrayOutputStream lido = new ByteArrayOutputStream();
                    byte[] b = new byte[1024];
                    int n;
                    while ((n = entrada.read(b)) != -1) {
                        lido.write(b, 0, n);
                    }
                    String stdin = lido.toString(StandardCharsets.UTF_8);
                    entradas.add(stdin);
                    StringBuilder out = new StringBuilder();
                    int status = manipulador.executar(linha, stdin, out);
                    saida.write(out.toString().getBytes(StandardCharsets.UTF_8));
                    saida.flush();
                    chamada.onExit(status);
                } catch (IOException e) {
                    chamada.onExit(255, e.toString());
                }
            }, "comando-de-teste");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void destroy(ChannelSession canal) {
            // nada a limpar
        }
    }
}
