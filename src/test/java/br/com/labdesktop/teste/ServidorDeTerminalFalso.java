package br.com.labdesktop.teste;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.server.Signal;

/**
 * O lado servidor de um terminal de mentira para os testes do terminal: aceita o canal exec (a linha do {@code docker exec ...}) e
 * o shell, com PTY, e executa um {@link Roteiro} (o que escrever, quando sair). Registra a linha de comando, o que chegou pelo
 * teclado, o tipo de terminal, o tamanho inicial e cada {@code window-change} (que o MINA entrega como SIGWINCH).
 */
public final class ServidorDeTerminalFalso {

    /** Uma sessão em andamento do ponto de vista do servidor. */
    public static final class Sessao {
        public final String linha;
        public final InputStream teclado;
        public final OutputStream tela;
        public final Environment ambiente;
        private final ExitCallback aoSair;
        private volatile boolean saiu;

        Sessao(String linha, InputStream teclado, OutputStream tela, Environment ambiente, ExitCallback aoSair) {
            this.linha = linha;
            this.teclado = teclado;
            this.tela = tela;
            this.ambiente = ambiente;
            this.aoSair = aoSair;
        }

        /** Escreve na tela do cliente (e descarrega). */
        public void escrever(String texto) throws IOException {
            escrever(texto.getBytes(StandardCharsets.UTF_8));
        }

        public void escrever(byte[] bytes) throws IOException {
            tela.write(bytes);
            tela.flush();
        }

        /** Termina a sessão com o status dado: o cliente vê o canal fechar. */
        public void sair(int status) {
            saiu = true;
            aoSair.onExit(status);
        }

        public boolean saiu() {
            return saiu;
        }
    }

    /** O que o servidor de mentira faz numa sessão. */
    @FunctionalInterface
    public interface Roteiro {
        void rodar(Sessao s) throws Exception;
    }

    /** Linhas de comando exec recebidas (a do {@code docker exec ...}). */
    public final List<String> comandos = new CopyOnWriteArrayList<>();
    public final AtomicInteger shellsAbertos = new AtomicInteger();
    public final AtomicInteger canaisAbertos = new AtomicInteger();
    /** Canais que o servidor viu fechar (o cliente fechou, ou a conexão caiu). */
    public final AtomicInteger canaisEncerrados = new AtomicInteger();
    /** Tudo o que chegou pelo teclado (todas as sessões juntas, em ordem de chegada). */
    private final ByteArrayOutputStream recebido = new ByteArrayOutputStream();
    /** Cada {@code window-change}, como "colunasxlinhas". */
    public final List<String> janelas = new CopyOnWriteArrayList<>();
    public final List<String> tiposDeTerminal = new CopyOnWriteArrayList<>();
    public final List<String> tamanhosIniciais = new CopyOnWriteArrayList<>();
    public final List<Sessao> sessoes = new CopyOnWriteArrayList<>();

    private volatile Roteiro roteiro = ServidorDeTerminalFalso::roteiroDeEco;
    private final ServidorSshDeTeste ssh;

    public ServidorDeTerminalFalso(ServidorSshDeTeste ssh) {
        this.ssh = ssh;
        ssh.sshd.addChannelListener(new org.apache.sshd.common.channel.ChannelListener() {
            @Override
            public void channelClosed(org.apache.sshd.common.channel.Channel canal, Throwable motivo) {
                if (canal instanceof ChannelSession) {
                    canaisEncerrados.incrementAndGet();
                }
            }
        });
        ssh.sshd.setCommandFactory((canal, linha) -> {
            comandos.add(linha);
            return new ComandoPty(linha, false);
        });
        ssh.sshd.setShellFactory(new ShellFactory() {
            @Override
            public Command createShell(ChannelSession canal) {
                return new ComandoPty("", true);
            }
        });
    }

    /**
     * Do canal {@code n}+1 em diante o servidor recusa abrir sessão com {@code SSH_OPEN_ADMINISTRATIVELY_PROHIBITED}, como o OpenSSH
     * faz quando o {@code MaxSessions} acaba.
     */
    public void limitarSessoesA(int n) {
        AtomicInteger contados = new AtomicInteger();
        List<ChannelFactory> fabricas = new ArrayList<>();
        for (ChannelFactory f : ssh.sshd.getChannelFactories()) {
            if (f instanceof ChannelSessionFactory) {
                fabricas.add(new ChannelFactory() {
                    @Override
                    public String getName() {
                        return f.getName();
                    }

                    @Override
                    public Channel createChannel(Session sessao) throws IOException {
                        if (contados.incrementAndGet() <= n) {
                            return f.createChannel(sessao);
                        }
                        return new ChannelSession() {
                            @Override
                            protected OpenFuture doInit(Buffer buffer) {
                                OpenFuture futuro = new DefaultOpenFuture(this, this);
                                futuro.setException(new SshChannelOpenException(getChannelId(), SshConstants.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED,
                                    "administratively prohibited (teste)"));
                                return futuro;
                            }
                        };
                    }
                });
            } else {
                fabricas.add(f);
            }
        }
        ssh.sshd.setChannelFactories(fabricas);
    }

    public void roteiro(Roteiro r) {
        this.roteiro = r;
    }

    /** Lê o teclado e devolve cada pedaço escrito (o eco do terminal). */
    public static void roteiroDeEco(Sessao s) throws IOException {
        byte[] b = new byte[512];
        int n;
        while ((n = s.teclado.read(b)) != -1) {
            s.tela.write(b, 0, n);
            s.tela.flush();
        }
    }

    /** Fica parado sem ler o teclado até o canal fechar: a janela do SSH enche e a escrita do cliente trava. */
    public static void roteiroQueNaoLe(Sessao s) throws Exception {
        while (!s.saiu()) {
            Thread.sleep(50);
        }
    }

    /** Escreve o texto e sai com o status. */
    public static Roteiro imprimirESair(String texto, int status) {
        return s -> {
            s.escrever(texto);
            s.sair(status);
        };
    }

    public String recebidoComoTexto() {
        synchronized (recebido) {
            return recebido.toString(StandardCharsets.UTF_8);
        }
    }

    public int bytesRecebidos() {
        synchronized (recebido) {
            return recebido.size();
        }
    }

    private final class ComandoPty implements Command {
        private final String linha;
        private final boolean shell;
        private InputStream teclado;
        private OutputStream tela;
        private ExitCallback aoSair;
        private volatile Sessao sessaoAtual;

        ComandoPty(String linha, boolean shell) {
            this.linha = linha;
            this.shell = shell;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.teclado = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.tela = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            // com PTY o stderr vai junto da saída; nada a fazer
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.aoSair = callback;
        }

        @Override
        public void start(ChannelSession canal, Environment env) throws IOException {
            // primeiro tudo o que o teste vai LER, depois os contadores em que ele ESPERA: quem vê o contador já vê os dados
            tiposDeTerminal.add(String.valueOf(env.getEnv().get(Environment.ENV_TERM)));
            tamanhosIniciais.add(env.getEnv().get(Environment.ENV_COLUMNS) + "x" + env.getEnv().get(Environment.ENV_LINES));
            env.addSignalListener((c, sinal) -> janelas.add(env.getEnv().get(Environment.ENV_COLUMNS) + "x"
                + env.getEnv().get(Environment.ENV_LINES)), Signal.WINCH);
            Sessao sessao = new Sessao(linha, new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] um = new byte[1];
                    int n = read(um, 0, 1);
                    return n < 0 ? -1 : um[0] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = teclado.read(b, off, len);
                    if (n > 0) {
                        synchronized (recebido) {
                            recebido.write(b, off, n);
                        }
                    }
                    return n;
                }
            }, tela, env, aoSair);
            sessaoAtual = sessao;
            sessoes.add(sessao);
            if (shell) {
                shellsAbertos.incrementAndGet();
            }
            canaisAbertos.incrementAndGet();
            Thread t = new Thread(() -> {
                try {
                    roteiro.rodar(sessao);
                } catch (Exception e) {
                    // o cliente fechou o canal por baixo do roteiro: normal nos testes
                } finally {
                    if (!sessao.saiu()) {
                        aoSair.onExit(0);
                    }
                }
            }, "terminal-falso");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void destroy(ChannelSession canal) {
            // o cliente fechou o canal: o programa "morre" (sem isso o MINA espera o comando sair e o canal nunca fecha do lado servidor)
            if (sessaoAtual != null && !sessaoAtual.saiu()) {
                sessaoAtual.sair(255);
            }
        }
    }
}
