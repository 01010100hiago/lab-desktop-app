package br.com.labdesktop.local;

import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.CofreBloqueadoException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.ssh.ConexaoDoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ssh.InfoConexao;
import br.com.labdesktop.ssh.PontaDoAgente;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Agendador.Cancelavel;
import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conexão de "Este computador": o mesmo ator de thread única da {@code ConexaoSsh}, sem SSH e sem túnel. Não há o que conectar: a ponta do agente
 * é a porta que o Docker publica em {@code 127.0.0.1}, e tudo o que se verifica é se o agente responde ({@link SondaDaPonta}). Os estados são os de
 * {@link EstadoConexao}: {@code CONECTADO} depois de um 200 em {@code /api/versao}; {@code SSH_OK_SEM_AGENTE} (aqui: "o computador está aí e o
 * agente não responde", com o teste repetido a cada poucos segundos); {@code TOKEN_RECUSADO} e {@code AGUARDANDO_2FA} como no remoto. Nunca
 * {@code RECONECTANDO}, {@code ERRO_AUTENTICACAO} nem {@code ERRO_CHAVE_HOST}: não há rede nem chave. O computador dormir não derruba nada: ao
 * acordar, só se confere o agente.
 */
public final class ConexaoLocal implements ConexaoDoServidor {

    private static final Logger LOG = LoggerFactory.getLogger(ConexaoLocal.class);

    private final String servidorId;
    private final AcessoAoCofre cofre;
    private final ConfigLocal cfg;
    private final SondaDaPonta sonda;
    private final Agendador agendador;
    private final ProvisionadorRemoto.Executor linhas;

    private final ExecutorService ator;
    private final List<Consumer<InfoConexao>> ouvintes = new CopyOnWriteArrayList<>();

    // ----- estado do ator: só a thread conexao-local-<id> lê e escreve -----
    private EstadoConexao estado = EstadoConexao.DESCONECTADO;
    private Falha falha;
    private Instant semConexaoDesde;
    private long idTentativa;
    private long geracao;
    private PontaDoAgente ponta;
    private Cancelavel timer;
    private boolean verificando;
    private boolean encerrado;

    private volatile InfoConexao info = InfoConexao.inicial();
    private volatile PontaDoAgente pontaAtual;

    public ConexaoLocal(String servidorId, AcessoAoCofre cofre, ConfigLocal cfg, SondaDaPonta sonda, Agendador agendador,
                        ProvisionadorRemoto.Executor linhas) {
        this.servidorId = servidorId;
        this.cofre = cofre;
        this.cfg = cfg;
        this.sonda = sonda;
        this.agendador = agendador;
        this.linhas = linhas;
        this.ator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "conexao-local-" + servidorId);
            t.setDaemon(true);
            return t;
        });
    }

    // =====================================================================================================
    // API pública (qualquer thread): só enfileira
    // =====================================================================================================

    @Override
    public void iniciar() {
        post(() -> {
            if (estado == EstadoConexao.DESCONECTADO || estado == EstadoConexao.BLOQUEADO_COFRE) {
                iniciarTentativa();
            }
        });
    }

    @Override
    public void tentarAgora() {
        post(() -> {
            switch (estado) {
                case SSH_OK_SEM_AGENTE, TOKEN_RECUSADO, AGUARDANDO_2FA -> {
                    cancelarTimer();
                    if (ponta != null) {
                        iniciarSonda(idTentativa);
                    } else {
                        reiniciarDoZero();
                    }
                }
                case RECONECTANDO, ERRO_AUTENTICACAO, ERRO_CHAVE_HOST, DESCONECTADO -> reiniciarDoZero();
                default -> { /* CONECTADO, CONECTANDO, SUSPENSO, BLOQUEADO_COFRE: nada a pular */ }
            }
        });
    }

    @Override
    public void reconectar() {
        post(() -> {
            if (encerrado || estado == EstadoConexao.SUSPENSO || estado == EstadoConexao.BLOQUEADO_COFRE) {
                return;
            }
            reiniciarDoZero();
        });
    }

    @Override
    public void verificarAgora() {
        post(() -> {
            if (verificando || ponta == null || !(estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SSH_OK_SEM_AGENTE)) {
                return;
            }
            verificando = true;
            long id = idTentativa;
            PontaDoAgente p = ponta;
            Fundo.ioVirtual().execute(() -> {
                SondaDoAgente.Resultado r = sondar(p);
                if (r != null) {
                    post(() -> {
                        verificando = false;
                        aoResultadoDaSonda(id, r);
                    });
                } else {
                    post(() -> verificando = false);
                }
            });
        });
    }

    @Override
    public void informarTokenRecusado() {
        post(() -> {
            if (ponta != null && (estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SSH_OK_SEM_AGENTE)) {
                cancelarTimer();
                mudar(EstadoConexao.TOKEN_RECUSADO, new Falha(Falha.Tipo.TOKEN_RECUSADO, "O agente recusou o token deste app. Registre o token de novo."), null);
            }
        });
    }

    @Override
    public void informarCodigo2faPendente() {
        post(() -> {
            if (ponta != null && (estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SSH_OK_SEM_AGENTE)) {
                cancelarTimer();
                mudar(EstadoConexao.AGUARDANDO_2FA, new Falha(Falha.Tipo.CODIGO_2FA_PENDENTE, "Código do autenticador pendente."), null);
            }
        });
    }

    /** O computador vai dormir: o agente é um container local e não perde nada, então o estado fica como está. */
    @Override
    public void aoDormir() { }

    /** O computador acordou: espera um pouco (o Docker pode estar voltando) e confere o agente. */
    @Override
    public void aoAcordar() {
        post(() -> {
            cancelarTimerDeAcordar();
            timerDeAcordar = agendador.agendar(cfg.esperaAposAcordar(), this::verificarAgora);
        });
    }

    private Cancelavel timerDeAcordar;

    private void cancelarTimerDeAcordar() {
        if (timerDeAcordar != null) {
            timerDeAcordar.cancelar();
            timerDeAcordar = null;
        }
    }

    @Override
    public void bloquear() {
        post(() -> {
            cancelarTimer();
            descartarPonta();
            mudar(EstadoConexao.BLOQUEADO_COFRE, null, null);
        });
    }

    @Override
    public CompletableFuture<Void> fechar() {
        CompletableFuture<Void> pronto = new CompletableFuture<>();
        try {
            ator.execute(() -> {
                try {
                    encerrado = true;
                    cancelarTimer();
                    cancelarTimerDeAcordar();
                    descartarPonta();
                    mudar(EstadoConexao.DESCONECTADO, null, null);
                } finally {
                    ator.shutdown();
                    pronto.complete(null);
                }
            });
        } catch (RejectedExecutionException e) {
            pronto.complete(null);
        }
        return pronto;
    }

    @Override
    public void close() {
        fechar();
    }

    @Override
    public InfoConexao info() {
        return info;
    }

    @Override
    public Runnable observar(Consumer<InfoConexao> ouvinte) {
        ouvintes.add(ouvinte);
        post(() -> ouvinte.accept(info));
        return () -> ouvintes.remove(ouvinte);
    }

    @Override
    public Optional<PontaDoAgente> pontaDoAgente() {
        return Optional.ofNullable(pontaAtual);
    }

    @Override
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        return linhas.executar(comando, stdin, prazo);
    }

    // =====================================================================================================
    // Ator
    // =====================================================================================================

    private void post(Runnable r) {
        try {
            ator.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.error("Erro inesperado na conexão local {}: {}", servidorId, e.toString(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // ator já encerrado: evento tardio
        }
    }

    private void reiniciarDoZero() {
        cancelarTimer();
        descartarPonta();
        iniciarTentativa();
    }

    private void iniciarTentativa() {
        cancelarTimer();
        ConfigServidor servidor;
        try {
            servidor = cofre.servidor(servidorId).orElse(null);
        } catch (CofreBloqueadoException e) {
            mudar(EstadoConexao.BLOQUEADO_COFRE, null, null);
            return;
        }
        if (servidor == null) {
            mudar(EstadoConexao.DESCONECTADO, new Falha(Falha.Tipo.REDE, "Este servidor não está mais no cofre."), null);
            return;
        }
        long id = ++idTentativa;
        ponta = new PontaDoAgente.Fixa(servidor.portaAgente(), ++geracao);
        pontaAtual = ponta;
        mudar(EstadoConexao.CONECTANDO, null, null);
        iniciarSonda(id);
    }

    private void iniciarSonda(long id) {
        PontaDoAgente p = ponta;
        if (p == null) {
            return;
        }
        Fundo.ioVirtual().execute(() -> {
            SondaDoAgente.Resultado r = sondar(p);
            if (r != null) {
                post(() -> aoResultadoDaSonda(id, r));
            }
        });
    }

    /** Roda a sonda; {@code null} se a thread foi interrompida. Falha da sonda vale "agente fora". */
    private SondaDoAgente.Resultado sondar(PontaDoAgente p) {
        try {
            return sonda.sondar(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            LOG.warn("A sonda do agente local falhou ({}): {}", servidorId, Redator.redigir(e));
            return SondaDoAgente.Resultado.AGENTE_FORA;
        }
    }

    private void aoResultadoDaSonda(long id, SondaDoAgente.Resultado r) {
        if (id != idTentativa || encerrado || ponta == null) {
            return;
        }
        cancelarTimer();
        switch (r) {
            case OK -> mudar(EstadoConexao.CONECTADO, null, null);
            case AGENTE_FORA, AGENTE_DESATUALIZADO -> {
                mudar(EstadoConexao.SSH_OK_SEM_AGENTE, r == SondaDoAgente.Resultado.AGENTE_FORA
                    ? new Falha(Falha.Tipo.LOCAL_AGENTE_FORA, "O agente do laboratório local não responde (o Docker pode estar parado ou o agente reiniciando).")
                    : new Falha(Falha.Tipo.LOCAL_AGENTE_DESATUALIZADO, "O agente local é anterior a este app (não tem GET /api/versao). Atualize o agente."), null);
                timer = agendador.agendar(cfg.intervaloSemAgente(), () -> post(() -> {
                    if (id == idTentativa && estado == EstadoConexao.SSH_OK_SEM_AGENTE) {
                        iniciarSonda(id);
                    }
                }));
            }
            case TOKEN_RECUSADO -> mudar(EstadoConexao.TOKEN_RECUSADO,
                new Falha(Falha.Tipo.TOKEN_RECUSADO, "O agente recusou o token deste app. Registre o token de novo."), null);
            case REQUER_2FA -> mudar(EstadoConexao.AGUARDANDO_2FA, new Falha(Falha.Tipo.CODIGO_2FA_PENDENTE, "Código do autenticador pendente."), null);
        }
    }

    private void mudar(EstadoConexao novo, Falha f, Instant proxima) {
        estado = novo;
        falha = f;
        if (novo == EstadoConexao.CONECTADO) {
            semConexaoDesde = null;
        } else if (semConexaoDesde == null && novo != EstadoConexao.DESCONECTADO) {
            semConexaoDesde = agendador.agora();
        }
        publicar(proxima);
    }

    private void publicar(Instant proxima) {
        PontaDoAgente p = ponta;
        InfoConexao nova = new InfoConexao(estado, falha, 0, proxima, semConexaoDesde, p == null ? 0 : p.geracao(), p == null ? null : p.portaLocal());
        if (nova.equals(info)) {
            return;
        }
        info = nova;
        for (Consumer<InfoConexao> o : ouvintes) {
            try {
                o.accept(nova);
            } catch (RuntimeException e) {
                LOG.warn("Ouvinte da conexão local {} falhou: {}", servidorId, e.toString());
            }
        }
    }

    private void descartarPonta() {
        idTentativa++;
        ponta = null;
        pontaAtual = null;
        verificando = false;
    }

    private void cancelarTimer() {
        if (timer != null) {
            timer.cancelar();
            timer = null;
        }
    }
}
