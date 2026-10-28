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

    /** Uma linha da saída do manipulador que começa com isto vai para o STDERR do comando (sem o prefixo). */
    public static final String PREFIXO_STDERR = "\u0001";

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
    /** Cada senha que chegou ao autenticador do servidor (aceita ou não): vazio = o servidor NUNCA viu uma senha. */
    public final List<String> senhasVistas = new CopyOnWriteArrayList<>();

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

    /** Passa a aceitar login por SENHA deste usuário (e a registrar toda senha que chegar). Sem esta chamada o servidor só aceita chave. */
    public void aceitarSenha(String usuario, String senha) {
        ofereceSenha(true);
        sshd.setPasswordAuthenticator((u, s, sessao) -> {
            senhasVistas.add(s);
            return u.equals(usuario) && s.equals(senha);
        });
    }

    /** Aceita senha mas LARGA a conexão assim que ela chega (a conta bloqueada, o limite de tentativas do servidor). */
    public void largarAoReceberSenha() {
        ofereceSenha(true);
        sshd.setPasswordAuthenticator((u, s, sessao) -> {
            senhasVistas.add(s);
            sessao.close(true);
            return false;
        });
    }

    /** Pede para trocar a senha (a senha venceu). */
    public void pedirTrocaDeSenha() {
        ofereceSenha(true);
        sshd.setPasswordAuthenticator((u, s, sessao) -> {
            senhasVistas.add(s);
            throw new org.apache.sshd.server.auth.password.PasswordChangeRequiredException("venceu", "Troque a senha", "pt-BR");
        });
    }

    /** Volta a aceitar só chave (sem login por senha). */
    public void desligarSenha() {
        ofereceSenha(false);
        sshd.setPasswordAuthenticator(null);
    }

    /** A lista de métodos que o servidor anuncia: o padrão do MINA a monta ao iniciar, então a senha entra (ou sai) dela aqui. */
    private void ofereceSenha(boolean sim) {
        List<org.apache.sshd.server.auth.UserAuthFactory> fatores = new java.util.ArrayList<>();
        if (sim) {
            fatores.add(org.apache.sshd.server.auth.password.UserAuthPasswordFactory.INSTANCE);
        }
        fatores.add(org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory.INSTANCE);
        sshd.setUserAuthFactories(fatores);
    }

    public void comandoRemoto(Manipulador m) {
        this.manipulador = m;
    }

    /**
     * Liga o subsistema SFTP do MINA sobre uma pasta (o {@code VirtualFileSystemFactory} monta um {@code RootedFileSystem}: o
     * cliente vê {@code /} como a pasta dada e não escapa dela, nem por {@code ..} nem por link simbólico). Sem esta chamada o
     * servidor não aceita SFTP, que é justamente o caso "este servidor não aceita transferência de arquivos".
     */
    public ServidorSshDeTeste comSftp(java.nio.file.Path raiz) {
        sshd.setSubsystemFactories(List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(raiz));
        return this;
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
                    StringBuilder so = new StringBuilder();
                    StringBuilder se = new StringBuilder();
                    for (String l : out.toString().split("(?<=\n)")) {
                        if (l.startsWith(PREFIXO_STDERR)) {
                            se.append(l.substring(PREFIXO_STDERR.length()));
                        } else {
                            so.append(l);
                        }
                    }
                    saida.write(so.toString().getBytes(StandardCharsets.UTF_8));
                    saida.flush();
                    if (se.length() > 0 && erro != null) {
                        erro.write(se.toString().getBytes(StandardCharsets.UTF_8));
                        erro.flush();
                    }
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
