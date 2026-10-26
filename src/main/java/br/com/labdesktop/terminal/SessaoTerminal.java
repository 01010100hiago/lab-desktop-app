package br.com.labdesktop.terminal;

import br.com.labdesktop.terminal.ClassificadorDeSaida.Veredito;
import br.com.labdesktop.terminal.TransporteDeTerminal.Disponibilidade;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Agendador.Cancelavel;
import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O ciclo de vida de UM terminal: abre o canal, percebe quando ele cai e reanexa sozinho (ARQUITETURA 7.3). Não importa Swing nem o
 * JediTerm: a interface recebe os avisos ({@link Ouvinte}) e cria o widget de cada anexação.
 *
 * <h2>Estados</h2>
 * Veja {@link EstadoDoTerminal}: ABRINDO, ATIVA, REANEXANDO (2, 4, 8, 16 e 30 s), SEM_CONEXAO (as cinco tentativas acabaram: falta
 * o botão "Reconectar"), SESSAO_FECHADA, ENCERRADA e BLOQUEADA.
 *
 * <h2>Regras</h2>
 * <ul>
 *   <li>Cada anexação é um canal novo e uma chamada nova a {@link Ouvinte#aoAnexar} (a interface cria um widget novo; reaproveitar o
 *       widget depois de {@code stop()} é frágil).</li>
 *   <li>Um canal que fecha é julgado pelo {@link ClassificadorDeSaida}: sessão ausente e fim normal NÃO entram em laço.</li>
 *   <li>As tentativas de reanexação só são gastas com a conexão disponível: com a rede fora por dois minutos, o terminal espera a
 *       conexão voltar (o {@code ConexaoSsh} é quem reconecta) e anexa assim que ela volta, sem desistir no meio.</li>
 *   <li>Nada digitado na queda é guardado ou reenviado: quem escreve é o conector, que só escreve com a sessão em ATIVA.</li>
 * </ul>
 *
 * <p>Todo o estado mora numa thread (como a {@code ConexaoSsh}); os avisos chegam nela: seja rápido e despache para a EDT.
 */
public final class SessaoTerminal implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SessaoTerminal.class);
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    /** A espera antes de cada tentativa de reanexação; o fim da lista é o fim das tentativas. */
    public static final List<Duration> ESPERAS = List.of(
        Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30));
    /** Quanto o fim da saída pode demorar a ser lido antes de o canal fechado ser julgado com o que já se leu. */
    private static final long ESPERA_DA_ULTIMA_LEITURA_MS = 1500;

    /** O que a interface precisa saber. Chamado na thread da sessão. */
    public interface Ouvinte {

        /** O estado mudou. */
        void aoMudarEstado(EstadoDoTerminal estado);

        /**
         * Um canal novo está aberto e a sessão já está em ATIVA. A interface liga um widget novo a ele. {@code geracao} identifica a
         * anexação: {@link #ativa(long)} só é verdadeiro para a última.
         */
        void aoAnexar(CanalDeTerminal canal, long geracao);
    }

    private final TransporteDeTerminal transporte;
    private final ComandoDeTerminal comando;
    private final Supplier<TamanhoDeTerminal> tamanho;
    private final Agendador agendador;
    private final List<Ouvinte> ouvintes = new CopyOnWriteArrayList<>();
    private final ExecutorService ator;
    private final Runnable desinscrever;

    // ----- estado do ator -----
    private long geracao;
    private int falhasSeguidas;
    private CanalObservado canal;
    private Instant anexadoEm;
    private Cancelavel timer;
    private boolean disponivel = true;
    private boolean abrindo;
    private boolean encerrado;

    // ----- visível para outras threads -----
    private volatile EstadoDoTerminal estado = EstadoDoTerminal.ABRINDO;
    private volatile long geracaoAtiva = -1;

    public SessaoTerminal(TransporteDeTerminal transporte, ComandoDeTerminal comando, Supplier<TamanhoDeTerminal> tamanho,
                          Agendador agendador) {
        this.transporte = transporte;
        this.comando = comando;
        this.tamanho = tamanho;
        this.agendador = agendador;
        int n = CONTADOR.incrementAndGet();
        this.ator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terminal-" + n);
            t.setDaemon(true);
            return t;
        });
        this.desinscrever = transporte.observarDisponibilidade(d -> post(() -> aoMudarDisponibilidade(d)));
    }

    public void adicionarOuvinte(Ouvinte ouvinte) {
        ouvintes.add(ouvinte);
    }

    public ComandoDeTerminal comando() {
        return comando;
    }

    public EstadoDoTerminal estado() {
        return estado;
    }

    /** {@code true} só enquanto a anexação {@code geracao} é a atual E a sessão está ATIVA: a única hora em que se escreve. */
    public boolean ativa(long geracao) {
        return estado.ativa() && geracaoAtiva == geracao;
    }

    // =====================================================================================================
    // API pública (qualquer thread): só enfileira
    // =====================================================================================================

    /** Abre o canal pela primeira vez. */
    public void iniciar() {
        post(() -> {
            if (!encerrado && geracao == 0) {
                tentarAnexar(EstadoDoTerminal.ABRINDO);
            }
        });
    }

    /** O botão "Reconectar": zera as tentativas e abre de novo. Vale fora de ATIVA e ABRINDO. */
    public void reconectar() {
        post(() -> {
            if (encerrado) {
                return;
            }
            switch (estado.tipo()) {
                case ATIVA, ABRINDO, BLOQUEADA -> { }
                default -> {
                    falhasSeguidas = 0;
                    tentarAnexar(EstadoDoTerminal.ABRINDO);
                }
            }
        });
    }

    /**
     * A tela soube que a sessão do Claude voltou a estar aberta (Retomar, Abrir e Reabrir produzem um container novo): reanexa na
     * hora, sem esperar a próxima tentativa. Só age se o terminal estava esperando uma sessão que não existia.
     */
    public void aoSessaoAberta() {
        post(() -> {
            if (!encerrado && (estado.tipo() == EstadoDoTerminal.Tipo.SESSAO_FECHADA
                || estado.tipo() == EstadoDoTerminal.Tipo.REANEXANDO)) {
                falhasSeguidas = 0;
                tentarAnexar(EstadoDoTerminal.ABRINDO);
            }
        });
    }

    /** O usuário fechou a aba (ou a tela saiu): fecha o canal e não avisa mais ninguém. Idempotente. */
    public void fechar() {
        try {
            ator.execute(() -> {
                if (encerrado) {
                    return;
                }
                encerrado = true;
                cancelarTimer();
                desinscrever.run();
                fecharCanal();
                ator.shutdown();
            });
        } catch (RejectedExecutionException e) {
            // já encerrada
        }
    }

    @Override
    public void close() {
        fechar();
    }

    // =====================================================================================================
    // Ator
    // =====================================================================================================

    private void post(Runnable r) {
        postar(r);
    }

    /** {@code false} se a sessão já encerrou (evento tardio: quem tem um recurso na mão precisa soltá-lo). */
    private boolean postar(Runnable r) {
        try {
            ator.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.error("Erro inesperado no terminal: {}", e.toString(), e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void tentarAnexar(EstadoDoTerminal mostrar) {
        cancelarTimer();
        fecharCanal();
        long g = ++geracao;
        abrindo = true;
        mudar(mostrar);
        TamanhoDeTerminal t = tamanho.get();
        Fundo.ioVirtual().execute(() -> {
            try {
                CanalDeTerminal aberto = transporte.abrir(comando, t);
                if (!postar(() -> aoAbrir(g, aberto))) {
                    aberto.fechar(); // a aba fechou enquanto o canal abria
                }
            } catch (IOException | RuntimeException e) {
                post(() -> aoFalharAbertura(g, e));
            }
        });
    }

    private void aoAbrir(long g, CanalDeTerminal aberto) {
        if (encerrado || g != geracao) {
            aberto.fechar(); // tentativa velha ou aba já fechada: o canal que acabou de abrir não serve a ninguém
            return;
        }
        abrindo = false;
        CanalObservado c = new CanalObservado(aberto);
        canal = c;
        anexadoEm = agendador.agora();
        falhasSeguidas = 0;
        geracaoAtiva = g;
        mudar(EstadoDoTerminal.ATIVA);
        for (Ouvinte o : ouvintes) {
            try {
                o.aoAnexar(c, g);
            } catch (RuntimeException e) {
                LOG.warn("Ouvinte do terminal falhou ao anexar: {}", e.toString());
            }
        }
        Fundo.ioVirtual().execute(() -> vigiar(g, c));
    }

    private void aoFalharAbertura(long g, Throwable erro) {
        if (encerrado || g != geracao) {
            return;
        }
        abrindo = false;
        LOG.info("Terminal não abriu: {}", erro.toString());
        if (erro instanceof TerminalRecusadoException) {
            mudar(EstadoDoTerminal.semConexao(erro.getMessage()));
            return;
        }
        if (estado.tipo() == EstadoDoTerminal.Tipo.ABRINDO) {
            falhasSeguidas = 0; // a primeira abertura (ou o "Reconectar") não gasta nenhuma das cinco tentativas
            agendarReanexacao();
            return;
        }
        falhasSeguidas++;
        if (falhasSeguidas >= ESPERAS.size()) {
            mudar(EstadoDoTerminal.semConexao(textoDeFalha(erro)));
            return;
        }
        agendarReanexacao();
    }

    /** O canal fechou (por qualquer motivo): espera o fim da saída ser lido e decide o que fazer. Roda numa thread virtual. */
    private void vigiar(long g, CanalObservado c) {
        Integer status;
        try {
            status = c.aguardarFim();
            c.aguardarLeituraDoFim(ESPERA_DA_ULTIMA_LEITURA_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        byte[] cauda = c.cauda();
        post(() -> aoFecharCanal(g, status, cauda));
    }

    private void aoFecharCanal(long g, Integer status, byte[] cauda) {
        if (encerrado || g != geracao || !estado.ativa()) {
            return; // fomos nós que fechamos (bloqueio, nova anexação, aba fechada)
        }
        geracaoAtiva = -1;
        Duration vida = Duration.between(anexadoEm, agendador.agora());
        Veredito v = ClassificadorDeSaida.classificar(vida, status, cauda, comando.ehShell());
        canal = null;
        switch (v) {
            case SESSAO_FECHADA -> mudar(EstadoDoTerminal.SESSAO_FECHADA);
            case TERMINOU -> mudar(EstadoDoTerminal.encerrada(status));
            case QUEDA -> {
                falhasSeguidas = 0;
                transporte.sugerirVerificacao();
                agendarReanexacao();
            }
        }
    }

    private void agendarReanexacao() {
        cancelarTimer();
        int numero = falhasSeguidas + 1;
        mudar(EstadoDoTerminal.reanexando(numero));
        Duration espera = ESPERAS.get(falhasSeguidas);
        long g = geracao;
        timer = agendador.agendar(espera, () -> post(() -> {
            if (!encerrado && g == geracao && estado.reanexando() && disponivel) {
                tentarAnexar(EstadoDoTerminal.reanexando(numero));
            } // sem conexão agora: a tentativa NÃO é gasta; aoMudarDisponibilidade anexa quando ela voltar
        }));
    }

    private void aoMudarDisponibilidade(Disponibilidade d) {
        if (encerrado) {
            return;
        }
        disponivel = d == Disponibilidade.DISPONIVEL;
        switch (d) {
            case BLOQUEADO -> {
                cancelarTimer();
                abrindo = false;
                geracao++; // o que estiver abrindo agora nasce velho e é fechado
                fecharCanal();
                mudar(EstadoDoTerminal.BLOQUEADA);
            }
            case INDISPONIVEL -> {
                if (estado.ativa() && canal != null) {
                    canal.abortar(); // a conexão caiu: não há quem avisar: não espera o canal perceber sozinho; o fechamento dispara a reanexação
                }
            }
            case DISPONIVEL -> {
                if (estado.reanexando() && !abrindo) {
                    EstadoDoTerminal mostrado = estado;
                    tentarAnexar(mostrado); // a conexão voltou: anexa já, sem esperar o resto da espera
                }
            }
        }
    }

    private void fecharCanal() {
        CanalObservado c = canal;
        canal = null;
        geracaoAtiva = -1;
        if (c != null) {
            c.fechar();
        }
    }

    private void cancelarTimer() {
        if (timer != null) {
            timer.cancelar();
            timer = null;
        }
    }

    private void mudar(EstadoDoTerminal novo) {
        if (novo.equals(estado)) {
            return;
        }
        estado = novo;
        for (Ouvinte o : ouvintes) {
            try {
                o.aoMudarEstado(novo);
            } catch (RuntimeException e) {
                LOG.warn("Ouvinte do terminal falhou: {}", e.toString());
            }
        }
    }

    private static String textoDeFalha(Throwable erro) {
        String m = erro.getMessage();
        return m == null || m.isBlank() ? "Não consegui abrir o terminal no servidor." : m;
    }
}
