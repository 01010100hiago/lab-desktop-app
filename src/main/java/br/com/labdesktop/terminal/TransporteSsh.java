package br.com.labdesktop.terminal;

import br.com.labdesktop.ssh.ConexaoSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.LimiteDeSessoesException;
import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.common.future.CloseFuture;

/**
 * O {@link TransporteDeTerminal} sobre a sessão SSH de UM servidor ({@link ConexaoSsh#abrirCanalPty} e
 * {@link ConexaoSsh#abrirShellPty}, ARQUITETURA 7.1). O terminal NÃO depende do agente: ele funciona em qualquer estado em que a
 * sessão SSH está de pé, inclusive {@code SSH_OK_SEM_AGENTE}.
 */
public final class TransporteSsh implements TransporteDeTerminal {

    private final ConexaoSsh conexao;
    private final BooleanSupplier donoExclusivo;

    /** @param donoExclusivo lê a preferência "dono exclusivo" a cada abertura ({@code attach -d}) */
    public TransporteSsh(ConexaoSsh conexao, BooleanSupplier donoExclusivo) {
        this.conexao = conexao;
        this.donoExclusivo = donoExclusivo;
    }

    public TransporteSsh(ConexaoSsh conexao) {
        this(conexao, () -> false);
    }

    @Override
    public CanalDeTerminal abrir(ComandoDeTerminal comando, TamanhoDeTerminal tamanho) throws IOException {
        try {
            PtyCapableChannelSession canal = switch (comando) {
                case ComandoDeTerminal.ShellDoServidor s -> conexao.abrirShellPty(tamanho.colunas(), tamanho.linhas());
                case ComandoDeTerminal.ShellDoProjeto s -> {
                    if (s.local()) {
                        throw new TerminalRecusadoException("Uma pasta de \"Este computador\" não vale num servidor SSH.", null);
                    }
                    yield conexao.abrirShellDoProjetoPty(s.pasta(), tamanho.colunas(), tamanho.linhas());
                }
                case ComandoDeTerminal.SessaoDoClaude s ->
                    conexao.abrirCanalPty(s.projeto(), donoExclusivo.getAsBoolean(), tamanho.colunas(), tamanho.linhas());
            };
            return new CanalSsh(canal);
        } catch (LimiteDeSessoesException e) {
            throw new TerminalRecusadoException(e.getMessage(), e);
        }
    }

    @Override
    public Runnable observarDisponibilidade(Consumer<Disponibilidade> ouvinte) {
        return conexao.observar(info -> ouvinte.accept(deEstado(info.estado())));
    }

    @Override
    public void sugerirVerificacao() {
        conexao.verificarAgora();
    }

    static Disponibilidade deEstado(EstadoConexao estado) {
        if (estado == EstadoConexao.BLOQUEADO_COFRE) {
            return Disponibilidade.BLOQUEADO;
        }
        return estado.permiteExec() ? Disponibilidade.DISPONIVEL : Disponibilidade.INDISPONIVEL;
    }

    /** O canal PTY do MINA visto como {@link CanalDeTerminal}. */
    private static final class CanalSsh implements CanalDeTerminal {

        private static final Duration FATIA_DE_ESPERA = Duration.ofSeconds(30);
        private static final Duration ESPERA_DA_RESPOSTA = Duration.ofSeconds(2);
        private static final Set<ClientChannelEvent> FECHADO = EnumSet.of(ClientChannelEvent.CLOSED);

        private final PtyCapableChannelSession canal;

        CanalSsh(PtyCapableChannelSession canal) {
            this.canal = canal;
        }

        @Override
        public InputStream saida() {
            return canal.getInvertedOut();
        }

        @Override
        public OutputStream entrada() {
            return canal.getInvertedIn();
        }

        @Override
        public void redimensionar(TamanhoDeTerminal tamanho) throws IOException {
            canal.sendWindowChange(tamanho.colunas(), tamanho.linhas());
        }

        @Override
        public boolean aberto() {
            return canal.isOpen();
        }

        @Override
        public Integer aguardarFim() throws InterruptedException {
            Set<ClientChannelEvent> eventos = canal.getChannelState();
            while (!eventos.contains(ClientChannelEvent.CLOSED)) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Interrompido à espera do fim do canal.");
                }
                eventos = canal.waitFor(FECHADO, FATIA_DE_ESPERA);
            }
            return canal.getExitStatus();
        }

        @Override
        public Integer statusDeSaida() {
            return canal.getExitStatus();
        }

        /**
         * {@code close(false)}: o MINA manda EOF e CLOSE ao servidor. O fechamento IMEDIATO ({@code close(true)}) só solta o canal do
         * lado do app: o servidor nunca fica sabendo, e o {@code docker exec ... tmux attach} fica órfão até a sessão SSH inteira cair.
         */
        @Override
        public void fechar() {
            if (!canal.isOpen()) {
                return;
            }
            CloseFuture fechamento = canal.close(false);
            Fundo.ioVirtual().execute(() -> {
                // se o servidor não responder ao CLOSE (a rede morreu no meio), o canal é arrancado depois da espera
                try {
                    if (!fechamento.await(ESPERA_DA_RESPOSTA)) {
                        canal.close(true);
                    }
                } catch (IOException e) {
                    canal.close(true);
                }
            });
        }

        @Override
        public void abortar() {
            canal.close(true);
        }
    }
}
