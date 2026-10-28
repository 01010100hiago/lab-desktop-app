package br.com.labdesktop.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Roda UM comando remoto por canal {@code exec} numa sessão SSH já autenticada, com entrada por stdin
 * (ARQUITETURA 7 e CONTRATO-API seção 6).
 *
 * <p>Regras de segurança:
 * <ul>
 *   <li>O que é sensível (código do 2FA, hash do token) vai SÓ por stdin, nunca na linha de comando: argv
 *       aparece em {@code ps} e no histórico do shell. Por isso a linha de comando é rejeitada se tiver
 *       caractere de controle.</li>
 *   <li>Nem o comando, nem o stdout, nem o stderr vão para o log: o stdout da configuração do 2FA é o segredo
 *       do 2FA (ARQUITETURA 10). O log só registra o status e os tamanhos.</li>
 *   <li>A saída é limitada ({@link #TETO_DE_SAIDA}): um servidor hostil não provoca {@code OutOfMemoryError}.</li>
 * </ul>
 * Bloqueia: chame de thread de fundo, nunca da EDT.
 */
public final class ExecRemoto {

    /** Teto de bytes guardados do stdout e do stderr (cada um). Os comandos do agente devolvem uma linha. */
    public static final int TETO_DE_SAIDA = 64 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(ExecRemoto.class);

    private final ClientSession sessao;
    private final Duration prazoParaAbrir;

    public ExecRemoto(ClientSession sessao, Duration prazoParaAbrir) {
        this.sessao = sessao;
        this.prazoParaAbrir = prazoParaAbrir;
    }

    /**
     * @param comando linha de comando FIXA (montada pelo app; nome de projeto só entra depois da regex)
     * @param stdin   bytes enviados ao stdin do comando e então EOF (pode ser vazio, nunca {@code null})
     * @param prazo   prazo total até o canal fechar
     * @throws IOException          canal não abriu, sessão caiu ou o prazo estourou
     * @throws InterruptedException se a thread foi interrompida (o canal é fechado)
     */
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        exigirLinhaSemControle(comando);
        SaidaLimitada out = new SaidaLimitada();
        SaidaLimitada err = new SaidaLimitada();
        try (ChannelExec canal = sessao.createExecChannel(comando)) {
            canal.setOut(out);
            canal.setErr(err);
            canal.open().verify(prazoParaAbrir);
            try (OutputStream entrada = canal.getInvertedIn()) {
                entrada.write(stdin);
                entrada.flush();
            } // fechar a entrada manda o EOF: o comando do agente termina de ler o stdin
            esperarOFim(canal, prazo);
            Integer status = canal.getExitStatus();
            ResultadoExec r = new ResultadoExec(status == null ? -1 : status, out.bytes(), err.bytes(),
                out.truncado() || err.truncado());
            LOG.debug("exec concluído: {}", r);
            return r;
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrompido durante o comando remoto.");
        }
    }

    /**
     * Espera o canal fechar numa {@link CompletableFuture} ligada ao fechamento do canal. Não se usa {@code canal.waitFor}: o do MINA 2.20 engole a
     * {@code InterruptedException} sem devolver a flag, então o Cancelar da tela (que interrompe a thread) nunca soltaria um comando que
     * bloqueia. A espera da {@code CompletableFuture} respeita a interrupção. Interrompido, FECHA o canal (o comando remoto recebe o hangup) e
     * lança {@link InterruptedException}; passou do prazo, lança {@link IOException}.
     */
    private static void esperarOFim(ChannelExec canal, Duration prazo) throws IOException, InterruptedException {
        CompletableFuture<Void> fechou = new CompletableFuture<>();
        canal.addCloseFutureListener(f -> fechou.complete(null));
        try {
            fechou.get(prazo.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            canal.close(true);
            throw e;
        } catch (TimeoutException e) {
            throw new ComandoDemorouException(prazo.toSeconds());
        } catch (ExecutionException e) {
            throw new IOException("O canal do comando remoto falhou.", e);
        }
    }

    static void exigirLinhaSemControle(String comando) {
        if (comando == null || comando.isBlank()) {
            throw new IllegalArgumentException("Comando remoto vazio.");
        }
        for (int i = 0; i < comando.length(); i++) {
            char c = comando.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("Comando remoto com caractere de controle.");
            }
        }
    }

    /** Guarda até o teto e descarta o resto (sem lançar: quem escreve é o thread de I/O do MINA). */
    private static final class SaidaLimitada extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncado;

        @Override
        public synchronized void write(int b) {
            if (buffer.size() < TETO_DE_SAIDA) {
                buffer.write(b);
            } else {
                truncado = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int cabe = Math.max(0, Math.min(len, TETO_DE_SAIDA - buffer.size()));
            if (cabe > 0) {
                buffer.write(b, off, cabe);
            }
            if (cabe < len) {
                truncado = true;
            }
        }

        synchronized byte[] bytes() {
            return buffer.toByteArray();
        }

        synchronized boolean truncado() {
            return truncado;
        }
    }
}
