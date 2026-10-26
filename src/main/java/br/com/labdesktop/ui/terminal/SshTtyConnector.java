package br.com.labdesktop.ui.terminal;

import br.com.labdesktop.terminal.CanalDeTerminal;
import br.com.labdesktop.terminal.TamanhoDeTerminal;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Agendador.Cancelavel;
import br.com.labdesktop.util.Fundo;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Liga o JediTerm a UM canal de terminal (ARQUITETURA 7.2): {@code TtyConnector} do JediTerm 3.76. O nome diz SSH porque é o transporte de
 * hoje, mas ele só enxerga um {@link CanalDeTerminal}: o canal de um PTY local serve igual.
 *
 * <ul>
 *   <li><b>Leitura</b>: {@code InputStreamReader} em UTF-8 sobre a saída do canal, que junta o stderr. Um caractere multibyte cortado
 *       entre dois pacotes SSH espera o resto chegar, em vez de virar lixo.</li>
 *   <li><b>{@code write} nunca enfileira</b>: se a sessão não está em ATIVA (ou o canal não está aberto), lança {@link IOException} na
 *       hora, a tecla é descartada ({@link Ouvinte#aoDescartarTecla()}) e NUNCA é reenviada depois de reconectar. Um Enter ou um "1"
 *       velho aprovaria depois um pedido de permissão do Claude.</li>
 *   <li><b>Vigia de escrita</b>: o {@code TerminalStarter} do JediTerm executa {@code sendBytes} e {@code postResize} numa thread só;
 *       um {@code write} travado (a janela SSH encheu, a rede morreu) seguraria teclas e redimensionamento. Se um {@code write} não
 *       voltar em {@link #PRAZO_DE_ESCRITA}, o canal é fechado, o que destrava a thread e dispara a reanexação.</li>
 *   <li><b>Redimensionar</b>: {@code resize} vira {@code window-change} no canal (o programa remoto recebe SIGWINCH).</li>
 * </ul>
 */
public final class SshTtyConnector implements TtyConnector {

    private static final Logger LOG = LoggerFactory.getLogger(SshTtyConnector.class);

    /** Quanto um {@code write} pode demorar antes de o canal ser fechado. */
    public static final Duration PRAZO_DE_ESCRITA = Duration.ofSeconds(5);

    /** O que o conector avisa à tela. Chamado em threads do JediTerm: despache para a EDT. */
    public interface Ouvinte {

        /** Uma tecla (ou colagem) foi jogada fora porque o terminal não está ativo. */
        void aoDescartarTecla();

        /** O terminal mudou de tamanho (o canal já foi avisado). */
        void aoRedimensionar(TamanhoDeTerminal tamanho);

        /** Uma escrita travou e o canal foi fechado. */
        void aoEscritaTravada();
    }

    private static final Ouvinte SEM_OUVINTE = new Ouvinte() {
        @Override
        public void aoDescartarTecla() { }

        @Override
        public void aoRedimensionar(TamanhoDeTerminal tamanho) { }

        @Override
        public void aoEscritaTravada() { }
    };

    private final CanalDeTerminal canal;
    private final BooleanSupplier ativa;
    private final Duration prazoDeEscrita;
    private final Agendador agendador;
    private final Ouvinte ouvinte;
    private final String nome;
    private final InputStreamReader leitor;
    private final Object trancaDaEscrita = new Object();

    /**
     * @param ativa {@code true} só enquanto a sessão está ATIVA e esta é a anexação atual: a única hora em que se escreve
     */
    public SshTtyConnector(CanalDeTerminal canal, BooleanSupplier ativa, String nome, Ouvinte ouvinte) {
        this(canal, ativa, nome, ouvinte, PRAZO_DE_ESCRITA, Agendador.doFundo());
    }

    /** Com o prazo e o relógio trocáveis (testes). */
    public SshTtyConnector(CanalDeTerminal canal, BooleanSupplier ativa, String nome, Ouvinte ouvinte, Duration prazoDeEscrita,
                           Agendador agendador) {
        this.canal = canal;
        this.ativa = ativa;
        this.nome = nome;
        this.ouvinte = ouvinte == null ? SEM_OUVINTE : ouvinte;
        this.prazoDeEscrita = prazoDeEscrita;
        this.agendador = agendador;
        this.leitor = new InputStreamReader(canal.saida(), StandardCharsets.UTF_8);
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return leitor.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (!ativa.getAsBoolean() || !canal.aberto()) {
            ouvinte.aoDescartarTecla();
            throw new IOException("Sem conexão: tecla descartada.");
        }
        AtomicBoolean travou = new AtomicBoolean();
        Cancelavel vigia = agendador.agendar(prazoDeEscrita, () -> {
            travou.set(true);
            ouvinte.aoEscritaTravada();
            Fundo.ioVirtual().execute(canal::fechar); // fechar destrava a escrita presa; fora da thread do relógio, que é só de disparo
        });
        try {
            synchronized (trancaDaEscrita) {
                OutputStream teclado = canal.entrada();
                teclado.write(bytes);
                teclado.flush();
            }
        } catch (IOException e) {
            if (travou.get()) {
                throw new IOException("A escrita travou e o canal foi fechado.", e);
            }
            throw e;
        } finally {
            vigia.cancelar();
        }
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return canal.aberto();
    }

    @Override
    public void resize(TermSize tamanho) {
        TamanhoDeTerminal t = new TamanhoDeTerminal(tamanho.getColumns(), tamanho.getRows());
        ouvinte.aoRedimensionar(t);
        if (!canal.aberto()) {
            return;
        }
        try {
            canal.redimensionar(t);
        } catch (IOException e) {
            LOG.debug("Não consegui avisar o novo tamanho do terminal: {}", e.toString());
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        Integer status = canal.aguardarFim();
        return status == null ? -1 : status;
    }

    @Override
    public boolean ready() throws IOException {
        try {
            return leitor.ready();
        } catch (IOException e) {
            return false; // canal fechado por baixo: o próximo read devolve o fim
        }
    }

    @Override
    public String getName() {
        return nome;
    }

    @Override
    public void close() {
        canal.fechar();
    }
}
