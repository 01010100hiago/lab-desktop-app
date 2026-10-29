package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.CofreBloqueadoException;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.transporte.AbridorDeDestino;
import br.com.labdesktop.transporte.DestinoSsh;
import br.com.labdesktop.transporte.FalhaDoTunel;
import br.com.labdesktop.transporte.FalhaDoTunelException;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Agendador.Cancelavel;
import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.PublicKeyAuthenticationReporter;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conexão SSH de UM servidor: abre, mantém e recupera a sessão e o túnel para o agente (ARQUITETURA 6.3).
 *
 * <h2>Modelo: ator de thread única</h2>
 * Todo estado mora numa thread ({@code conexao-<id>}). Todo evento externo (iniciar, tentar agora, sono, bloquear,
 * queda de sessão, resultado de uma tentativa) apenas ENFILEIRA uma tarefa nela, então as transições nunca correm
 * em paralelo. O ator nunca bloqueia: conectar, autenticar, sondar e verificar rodam em threads virtuais e
 * devolvem o resultado por evento, marcado com o número da tentativa; resultado de tentativa antiga é ignorado.
 *
 * <h2>Estados</h2>
 * Veja {@link EstadoConexao}. {@code CONECTADO} só vale depois de {@code GET /api/versao} responder 200 (pela
 * {@link SondaDoAgente}). Falha de rede reconecta com {@link PoliticaDeBackoff}; chave recusada tenta UMA vez
 * mais depois de 5 s e para (para não disparar o fail2ban); pin ausente ou divergente nunca reconecta.
 *
 * <h2>Estado por tentativa, não por conexão</h2>
 * O {@code SshClient} é um só por servidor e uma tentativa velha pode ainda estar bloqueada num connect quando a
 * nova começa (o Mac dormiu no meio de uma conexão). Por isso o que se aprende de cada tentativa (veredito do
 * pin, "chave recusada", último erro da sessão) fica na PRÓPRIA {@link ClientSession}, em atributos, e nunca em
 * campos desta classe: o resultado de uma tentativa só pode ser lido pela tentativa que o criou.
 *
 * <h2>Sem porta aberta</h2>
 * Num servidor {@code SSM} ou {@code Cloudflare} o SSH não conecta no endereço do servidor: a cada tentativa o {@link AbridorDeDestino} sobe um túnel externo (um programa do
 * computador do usuário, por lista de argumentos) numa porta aleatória de 127.0.0.1 e o SSH conecta nela. O túnel é fechado junto com a sessão (queda, nova tentativa, editar, remover,
 * bloquear, fechar). Se o programa do túnel terminar sozinho, a sessão cai com a causa em palavras simples e a reconexão segue o mesmo backoff de sempre (nunca em laço apertado).
 * Os pins ficam presos ao servidor (o id, no cofre), não à porta aleatória: a mudança de porta e a troca de modo não os tocam, e uma chave de host diferente continua recusada.
 *
 * <h2>Segurança</h2>
 * O {@link KeyPair} sai do cofre, vai para {@code addPublicKeyIdentity} e a referência é solta logo depois da
 * autenticação. Nenhuma identidade de {@code ~/.ssh} entra (ver {@link ClienteSshFactory}). O verificador só
 * compara com o pin do cofre; sem pin, esta classe nem abre a conexão (a confiança é dada pela
 * {@link SondagemDeHost} e pelo diálogo do usuário).
 */
public final class ConexaoSsh implements ConexaoDoServidor {

    private static final Logger LOG = LoggerFactory.getLogger(ConexaoSsh.class);

    private enum Fase { CONECTANDO, AUTENTICANDO, TUNEL }

    private final String servidorId;
    private final AcessoAoCofre cofre;
    private final ConfigSsh cfg;
    private final SondaDoAgente sonda;
    private final Agendador agendador;
    private final PoliticaDeBackoff backoff;
    private final AbridorDeDestino abridor;

    private final ExecutorService ator;
    private final List<Consumer<InfoConexao>> ouvintes = new CopyOnWriteArrayList<>();
    private final AtomicLong contadorDeGeracao = new AtomicLong();

    /** O servidor recusou a chave do app nesta sessão (gravado pelo relator de autenticação do MINA). */
    private static final AttributeKey<Boolean> CHAVE_RECUSADA = new AttributeKey<>();
    /** Última exceção que o MINA apontou nesta sessão (gravada pelo ouvinte do cliente). */
    private static final AttributeKey<Throwable> ERRO_DA_SESSAO = new AttributeKey<>();
    /** O servidor respondeu "não" à chave (gravado pelo relator quando o MINA aponta a falha daquela chave). */
    private static final AttributeKey<Boolean> SERVIDOR_RECUSOU = new AttributeKey<>();
    /** O app chegou a oferecer a chave ao servidor nesta sessão. */
    private static final AttributeKey<Boolean> CHAVE_OFERECIDA = new AttributeKey<>();
    /** A impressão (SHA256:...) da chave do cofre que o app usou nesta sessão: informação pública, vai para a tela e para o log. */
    private static final AttributeKey<String> IMPRESSAO_DA_CHAVE = new AttributeKey<>();
    /** O túnel externo terminou sozinho e foi por isso que a sessão caiu: a causa vai para a mensagem. */
    private static final AttributeKey<FalhaDoTunel> FALHA_DO_TUNEL = new AttributeKey<>();

    /** O que uma tentativa que falhou deixou na sessão dela: tudo o que a classificação da falha precisa. */
    private record Autopsia(VerificadorDeChaveDoServidor.Veredito veredito, boolean chaveRecusada, Throwable erroDaSessao, boolean servidorRecusou,
                           boolean chaveOferecida, String impressaoDaChave) {

        static final Autopsia VAZIA = new Autopsia(null, false, null, false, false, null);

        /** Lê os atributos; {@code s} é {@code null} quando nem a sessão chegou a existir (TCP não conectou). */
        static Autopsia de(ClientSession s) {
            if (s == null) {
                return VAZIA;
            }
            return new Autopsia(s.getAttribute(VerificadorDeChaveDoServidor.VEREDITO_DA_SESSAO),
                Boolean.TRUE.equals(s.getAttribute(CHAVE_RECUSADA)), s.getAttribute(ERRO_DA_SESSAO), Boolean.TRUE.equals(s.getAttribute(SERVIDOR_RECUSOU)),
                Boolean.TRUE.equals(s.getAttribute(CHAVE_OFERECIDA)), s.getAttribute(IMPRESSAO_DA_CHAVE));
        }
    }

    private final VerificadorDeChaveDoServidor verificador;

    // ----- estado do ator: só a thread conexao-<id> lê e escreve -----
    private EstadoConexao estado = EstadoConexao.DESCONECTADO;
    private Falha falha;
    private int tentativa;
    private Instant proximaEm;
    private Instant semConexaoDesde;
    private long idTentativa;
    private ClientSession sessao;
    private TunelSsh tunel;
    /** O caminho até o SSH desta sessão (nos modos sem porta aberta, o túnel externo): fecha junto com ela. */
    private DestinoSsh destinoAtual;
    private Cancelavel timer;
    private Cancelavel timerEstavel;
    private boolean retentouAutenticacao;
    private boolean verificando;
    private boolean encerrado;

    // ----- visíveis para outras threads -----
    private volatile InfoConexao info = InfoConexao.inicial();
    private volatile ClientSession sessaoViva;
    private volatile TunelSsh tunelAtual;
    private SshClient cliente; // criado sob demanda, guardado por "this"
    /** A thread que está abrindo o túnel externo desta tentativa (para interromper ao descartá-la: um túnel que demora não segura o fechamento). */
    private volatile Thread threadAbrindoOTunel;

    public ConexaoSsh(String servidorId, AcessoAoCofre cofre, ConfigSsh cfg, SondaDoAgente sonda) {
        this(servidorId, cofre, cfg, sonda, Agendador.doFundo(),
            new PoliticaDeBackoff(cfg.escalaBackoff(), Math::random));
    }

    /** Só o modo direto: um servidor sem porta aberta não conecta (quem precisa de túnel passa o {@link AbridorDeDestino} de verdade). */
    public ConexaoSsh(String servidorId, AcessoAoCofre cofre, ConfigSsh cfg, SondaDoAgente sonda,
                      Agendador agendador, PoliticaDeBackoff backoff) {
        this(servidorId, cofre, cfg, sonda, agendador, backoff, AbridorDeDestino.SO_DIRETO);
    }

    public ConexaoSsh(String servidorId, AcessoAoCofre cofre, ConfigSsh cfg, SondaDoAgente sonda,
                      Agendador agendador, PoliticaDeBackoff backoff, AbridorDeDestino abridor) {
        this.servidorId = servidorId;
        this.cofre = cofre;
        this.cfg = cfg;
        this.sonda = sonda;
        this.agendador = agendador;
        this.backoff = backoff;
        this.abridor = abridor;
        this.verificador = new VerificadorDeChaveDoServidor(this::pinsAtuais);
        this.ator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "conexao-" + servidorId);
            t.setDaemon(true);
            return t;
        });
    }

    // =====================================================================================================
    // API pública (qualquer thread): só enfileira
    // =====================================================================================================

    /** Começa a conectar. Sem efeito se já está em andamento. */
    @Override
    public void iniciar() {
        post(() -> {
            if (estado == EstadoConexao.DESCONECTADO || estado == EstadoConexao.BLOQUEADO_COFRE) {
                backoff.zerar();
                retentouAutenticacao = false;
                tentativa = 0;
                iniciarTentativa();
            }
        });
    }

    /**
     * "Tentar agora": pula a espera do backoff. Com o SSH de pé (agente fora, token recusado, aguardando 2FA)
     * só repete o teste do agente, sem reconectar. É também o que o usuário dispara depois de confiar no host.
     */
    @Override
    public void tentarAgora() {
        post(() -> {
            switch (estado) {
                case SSH_OK_SEM_AGENTE, TOKEN_RECUSADO, AGUARDANDO_2FA -> {
                    cancelarTimer();
                    if (sessao != null) {
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

    /**
     * Joga fora a sessão atual (se houver) e conecta de novo do zero, com o que o cofre tem AGORA. É o que se faz depois de
     * editar o endereço, a porta ou o usuário do servidor: "Tentar agora" não serve, porque com a conexão de pé ele não
     * reconecta. Mac dormindo e cofre bloqueado ficam como estão (acordar e desbloquear já reconectam).
     */
    @Override
    public void reconectar() {
        post(() -> {
            if (encerrado || estado == EstadoConexao.SUSPENSO || estado == EstadoConexao.BLOQUEADO_COFRE) {
                return;
            }
            tentativa = 0;
            reiniciarDoZero();
        });
    }

    /**
     * Confere agora se a sessão e o agente respondem: um keepalive com resposta (prazo de 5 s) e depois o teste
     * do agente. É o que o Sondador pede quando uma consulta falha com erro de rede (ARQUITETURA 6.3).
     * Sessão morta vai para RECONECTANDO; agente mudo com o SSH de pé vai para SSH_OK_SEM_AGENTE.
     */
    @Override
    public void verificarAgora() {
        post(() -> {
            if (verificando || sessao == null || !(estado == EstadoConexao.CONECTADO
                || estado == EstadoConexao.SSH_OK_SEM_AGENTE)) {
                return;
            }
            verificando = true;
            long id = idTentativa;
            ClientSession s = sessao;
            TunelSsh t = tunel;
            Fundo.ioVirtual().execute(() -> verificarEmFundo(id, s, t));
        });
    }

    /** O agente respondeu 401 sem {@code codigo} a uma consulta qualquer: o token deste Mac não vale mais. */
    @Override
    public void informarTokenRecusado() {
        post(() -> {
            if (sessao != null && (estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SSH_OK_SEM_AGENTE)) {
                cancelarTimer();
                mudar(EstadoConexao.TOKEN_RECUSADO, MensagensDeFalha.tokenRecusado(), null);
            }
        });
    }

    /** O agente exige o código do autenticador e o usuário cancelou o pedido: para de consultar até ele digitar. */
    @Override
    public void informarCodigo2faPendente() {
        post(() -> {
            if (sessao != null && (estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SSH_OK_SEM_AGENTE)) {
                cancelarTimer();
                mudar(EstadoConexao.AGUARDANDO_2FA, MensagensDeFalha.codigo2faPendente(), null);
            }
        });
    }

    /** O Mac vai dormir (veja {@link MonitorDeSono}). */
    @Override
    public void aoDormir() {
        post(() -> {
            switch (estado) {
                case CONECTANDO, CONECTADO, SSH_OK_SEM_AGENTE, RECONECTANDO, TOKEN_RECUSADO, AGUARDANDO_2FA,
                     ERRO_AUTENTICACAO -> {
                    cancelarTimer();
                    descartarSessaoAtual(); // o TCP vai morrer; ignora o que chegar dela daqui em diante
                    mudar(EstadoConexao.SUSPENSO, null, null);
                }
                default -> { /* parado ou esperando o usuário: dormir não muda nada */ }
            }
        });
    }

    /** O Mac acordou: espera a rede voltar e reconecta SEM esperar o heartbeat (a sessão está morta). */
    @Override
    public void aoAcordar() {
        post(() -> {
            if (estado != EstadoConexao.SUSPENSO) {
                return;
            }
            cancelarTimer();
            timer = agendador.agendar(cfg.esperaAposAcordar(), () -> post(() -> {
                if (estado == EstadoConexao.SUSPENSO) {
                    reiniciarDoZero();
                }
            }));
        });
    }

    /** O cofre foi bloqueado: fecha sessão e túnel e para. Não há reconexão enquanto o cofre estiver fechado. */
    @Override
    public void bloquear() {
        post(() -> {
            cancelarTimer();
            descartarSessaoAtual();
            tentativa = 0;
            mudar(EstadoConexao.BLOQUEADO_COFRE, null, null);
        });
    }

    /**
     * Encerra de vez (sessão, túnel e o cliente SSH) e para o ator. O futuro completa quando o cliente parou.
     * Idempotente.
     */
    @Override
    public CompletableFuture<Void> fechar() {
        CompletableFuture<Void> pronto = new CompletableFuture<>();
        try {
            ator.execute(() -> {
                CompletableFuture<Void> descartado = CompletableFuture.completedFuture(null);
                try {
                    encerrado = true;
                    cancelarTimer();
                    descartado = descartarSessaoAtual();
                    mudar(EstadoConexao.DESCONECTADO, null, null);
                } finally {
                    SshClient c;
                    synchronized (this) {
                        c = cliente;
                        cliente = null;
                    }
                    // só termina depois de a sessão e o túnel externo (o programa do SSM ou da Cloudflare) terem sido fechados
                    descartado.whenComplete((v, e) -> {
                        if (c == null) {
                            pronto.complete(null);
                        } else {
                            Fundo.ioVirtual().execute(() -> {
                                try {
                                    c.stop();
                                } catch (RuntimeException ex) {
                                    LOG.debug("Falha ao parar o cliente SSH: {}", ex.toString());
                                } finally {
                                    pronto.complete(null);
                                }
                            });
                        }
                    });
                    ator.shutdown();
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

    /** O retrato atual. Sem segredo; seguro entre threads. */
    @Override
    public InfoConexao info() {
        return info;
    }

    /**
     * Passa a receber cada mudança (na thread do ator: seja rápido e não bloqueie; despache para a EDT se for
     * tela). O primeiro retrato chega logo em seguida. Devolve o que desfaz a inscrição.
     */
    @Override
    public Runnable observar(Consumer<InfoConexao> ouvinte) {
        ouvintes.add(ouvinte);
        post(() -> ouvinte.accept(info));
        return () -> ouvintes.remove(ouvinte);
    }

    /** O túnel da geração atual, se houver. */
    public Optional<TunelSsh> tunel() {
        return Optional.ofNullable(tunelAtual);
    }

    @Override
    public Optional<TunelSsh> pontaDoAgente() {
        return tunel();
    }

    /**
     * Roda um comando na sessão SSH atual (canal {@code exec}), com stdin. Funciona nos estados em que há
     * sessão, inclusive com o agente fora ou o token recusado: é assim que se registra o token novo.
     *
     * @throws IOException se não há sessão SSH autenticada agora
     */
    @Override
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        ClientSession s = sessaoViva;
        if (s == null || !s.isOpen()) {
            throw new IOException("Não há conexão SSH aberta com o servidor.");
        }
        return new ExecRemoto(s, cfg.abrirCanal()).executar(comando, stdin, prazo);
    }

    /**
     * Abre UM canal SFTP na sessão SSH atual (o gerenciador de arquivos abre um por tela e o fecha ao sair). Funciona nos
     * mesmos estados que {@link #executar}: SSH de pé, com ou sem agente.
     *
     * @throws SftpIndisponivelException a sessão está de pé e o servidor não aceita SFTP
     * @throws IOException               não há sessão SSH autenticada agora (ou ela caiu durante a abertura)
     */
    public SftpClient abrirSftp() throws IOException {
        ClientSession s = sessaoViva;
        if (s == null || !s.isOpen()) {
            throw new IOException("Não há conexão SSH aberta com o servidor.");
        }
        try {
            return SftpClientFactory.instance().createSftpClient(s);
        } catch (IOException e) {
            if (s.isOpen()) {
                throw new SftpIndisponivelException(e); // a sessão vive: o servidor recusou o subsistema
            }
            throw e;
        }
    }

    /**
     * Abre um canal com PTY ligado à sessão do Claude do projeto: {@code docker exec -it ... tmux -u attach -t work} (ARQUITETURA
     * 7.1). Sem {@code -d}. O nome do projeto é validado ANTES de qualquer coisa tocar a sessão.
     *
     * @param colunas largura inicial em caracteres (o terminal mede depois do primeiro layout; 120 por 32 se não deu)
     * @throws IllegalArgumentException o nome do projeto não passa na regra do agente
     * @throws LimiteDeSessoesException o servidor recusou mais um canal de sessão
     * @throws IOException              não há sessão SSH autenticada agora, ou o canal não abriu
     */
    public PtyCapableChannelSession abrirCanalPty(String projeto, int colunas, int linhas) throws IOException {
        return abrirCanalPty(projeto, false, colunas, linhas);
    }

    /** Como {@link #abrirCanalPty(String, int, int)}, com a opção "dono exclusivo" ({@code attach -d}). */
    public PtyCapableChannelSession abrirCanalPty(String projeto, boolean donoExclusivo, int colunas, int linhas) throws IOException {
        String comando = LinhaDePty.sessaoDoClaude(projeto, donoExclusivo); // valida o nome antes de tocar a sessão
        ExecRemoto.exigirLinhaSemControle(comando);
        ClientSession s = sessaoParaCanal();
        return abrirPty(s.createExecChannel(comando), colunas, linhas);
    }

    /** Abre um canal com PTY no shell de login do usuário no servidor ({@code xterm-256color}, a mesma regra de PTY). */
    public PtyCapableChannelSession abrirShellPty(int colunas, int linhas) throws IOException {
        ClientSession s = sessaoParaCanal();
        return abrirPty(s.createShellChannel(), colunas, linhas);
    }

    /** Como {@link #abrirShellPty}, mas já dentro da pasta do projeto ({@link LinhaDePty#shellDoProjeto}). */
    public PtyCapableChannelSession abrirShellDoProjetoPty(String pasta, int colunas, int linhas) throws IOException {
        String comando = LinhaDePty.shellDoProjeto(pasta); // valida o caminho antes de tocar a sessão
        ExecRemoto.exigirLinhaSemControle(comando);
        ClientSession s = sessaoParaCanal();
        return abrirPty(s.createExecChannel(comando), colunas, linhas);
    }

    private ClientSession sessaoParaCanal() throws IOException {
        ClientSession s = sessaoViva;
        if (s == null || !s.isOpen()) {
            throw new IOException("Não há conexão SSH aberta com o servidor.");
        }
        return s;
    }

    private PtyCapableChannelSession abrirPty(PtyCapableChannelSession canal, int colunas, int linhas) throws IOException {
        canal.setUsePty(true);
        canal.setPtyType(LinhaDePty.TIPO_DE_TERMINAL);
        canal.setPtyColumns(Math.max(1, colunas));
        canal.setPtyLines(Math.max(1, linhas));
        canal.setPtyWidth(0);
        canal.setPtyHeight(0);
        canal.setRedirectErrorStream(true); // com PTY o stderr já vem junto; isto garante que nada fique parado no pipe do erro
        try {
            canal.open().verify(cfg.abrirCanal());
            return canal;
        } catch (IOException | RuntimeException e) {
            canal.close(true);
            if (recusadoPorLimite(e)) {
                throw new LimiteDeSessoesException(e);
            }
            throw e;
        }
    }

    /** O MINA embrulha a recusa do servidor ({@code SSH_OPEN_ADMINISTRATIVELY_PROHIBITED}) numa {@code SshException}: olha a cadeia. */
    private static boolean recusadoPorLimite(Throwable erro) {
        Throwable t = erro;
        for (int fundo = 0; t != null && fundo < 8; fundo++, t = t.getCause()) {
            if (t instanceof org.apache.sshd.common.channel.exception.SshChannelOpenException aberto
                && aberto.getReasonCode() == SshConstants.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED) {
                return true;
            }
        }
        return false;
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
                    LOG.error("Erro inesperado na conexão {}: {}", servidorId, e.toString(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // ator já encerrado: evento tardio de uma sessão que está fechando; descarta.
        }
    }

    /** Como {@link #post}, mas diz se o ator ainda aceitava (um ator já encerrado recusa, e quem entrega algo que precisa de limpeza deve limpar). */
    private boolean tentarPost(Runnable r) {
        try {
            ator.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.error("Erro inesperado na conexão {}: {}", servidorId, e.toString(), e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void reiniciarDoZero() {
        cancelarTimer();
        descartarSessaoAtual();
        backoff.zerar();
        retentouAutenticacao = false;
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
        if (servidor.pins().isEmpty()) {
            // Host nunca confirmado: nem abre a conexão. A sondagem e o diálogo do usuário vêm antes.
            mudar(EstadoConexao.ERRO_CHAVE_HOST, MensagensDeFalha.pinAusente(), null);
            return;
        }
        long id = ++idTentativa;
        mudar(EstadoConexao.CONECTANDO, falha, null);
        Fundo.ioVirtual().execute(() -> conectarEmFundo(id, servidor));
    }

    /** Roda em thread virtual: nada aqui toca o estado do ator; o resultado volta por {@code post}. */
    private void conectarEmFundo(long id, ConfigServidor servidor) {
        Fase fase = Fase.CONECTANDO;
        ClientSession s = null;
        TunelSsh t = null;
        DestinoSsh destino = null;
        try {
            // Sem porta aberta: sobe o túnel externo (ou, no direto, só devolve o endereço do servidor). A porta de um túnel é nova a cada tentativa.
            threadAbrindoOTunel = Thread.currentThread();
            try {
                destino = abridor.abrir(servidor.transporte(), servidor.host(), servidor.porta(), falha -> post(() -> aoCairOTunel(id, falha)));
            } finally {
                if (threadAbrindoOTunel == Thread.currentThread()) {
                    threadAbrindoOTunel = null; // só limpa o que é seu: uma tentativa nova pode já ter posto a thread dela
                }
            }
            SshClient c = clienteParaConectar(servidor);
            s = c.connect(servidor.usuario(), destino.host(), destino.porta()).verify(cfg.conectar()).getSession();
            fase = Fase.AUTENTICANDO;
            KeyPair par = cofre.keyPairDoServidor(servidor.id());
            s.setAttribute(IMPRESSAO_DA_CHAVE, KeyUtils.getFingerPrint(par.getPublic()));
            s.addPublicKeyIdentity(par);
            s.auth().verify(cfg.autenticar());
            par = null; // solta a referência: o MINA guarda a que precisa enquanto a sessão existir
            fase = Fase.TUNEL;
            var rastreador = s.createLocalPortForwardingTracker(
                new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress("127.0.0.1", servidor.portaAgente()));
            t = new TunelSsh(rastreador, contadorDeGeracao.incrementAndGet());
            ClientSession sessaoPronta = s;
            TunelSsh tunelPronto = t;
            DestinoSsh destinoPronto = destino;
            if (!tentarPost(() -> aoSshPronto(id, sessaoPronta, tunelPronto, destinoPronto))) {
                fecharQuieto(s, t, destino); // o ator já encerrou (fechar() passou): ninguém mais vai fechar isto, e o programa do túnel não pode ficar órfão
            }
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Autopsia autopsia = Autopsia.de(s); // antes de fechar: o que esta sessão aprendeu
            // a causa do túnel (o programa saiu: login da AWS expirado, instância sem SSM...) vale mais que o erro de rede que veio dela
            FalhaDoTunel doTunel = e instanceof FalhaDoTunelException x ? x.falha()
                : destino == null ? null : destino.falhaDoTunel().or(destino::causaPelaSaida).orElse(null); // túnel de pé e SSH falhando: a saída pode trazer o login que falta
            boolean tunelDeTransporte = servidor.transporte().ehTunel();
            fecharQuieto(s, t, destino);
            Fase onde = fase;
            Throwable erro = e;
            tentarPost(() -> aoFalharConexao(id, onde, erro, autopsia, doTunel, tunelDeTransporte));
        }
    }

    private void aoSshPronto(long id, ClientSession s, TunelSsh t, DestinoSsh destino) {
        if (id != idTentativa || encerrado || estado != EstadoConexao.CONECTANDO) {
            fecharQuieto(s, t, destino); // tentativa velha (bloqueou, dormiu ou reiniciou no meio)
            return;
        }
        sessao = s;
        tunel = t;
        destinoAtual = destino;
        sessaoViva = s;
        tunelAtual = t;
        s.addCloseFutureListener(f -> post(() -> aoSessaoFechada(id)));
        mudar(EstadoConexao.CONECTANDO, null, null); // publica a porta local; o teste do agente vem agora
        iniciarSonda(id);
    }

    private void iniciarSonda(long id) {
        TunelSsh t = tunel;
        if (t == null) {
            return;
        }
        Fundo.ioVirtual().execute(() -> {
            SondaDoAgente.Resultado r;
            try {
                r = sonda.sondar(t);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.warn("A sonda do agente falhou ({}): {}", servidorId, Redator.redigir(e));
                r = SondaDoAgente.Resultado.AGENTE_FORA;
            }
            SondaDoAgente.Resultado veredito = r;
            post(() -> aoResultadoDaSonda(id, veredito));
        });
    }

    private void aoResultadoDaSonda(long id, SondaDoAgente.Resultado r) {
        if (id != idTentativa || encerrado || sessao == null) {
            return;
        }
        cancelarTimer();
        switch (r) {
            case OK -> {
                boolean jaEstava = estado == EstadoConexao.CONECTADO;
                tentativa = 0;
                retentouAutenticacao = false;
                mudar(EstadoConexao.CONECTADO, null, null);
                if (!jaEstava) {
                    // Só depois de 60 s de pé o backoff volta ao início: uma conexão que cai a cada 5 s não
                    // pode martelar o servidor com tentativas de 1 s.
                    cancelarTimerEstavel();
                    timerEstavel = agendador.agendar(cfg.estavelApos(), () -> post(() -> {
                        if (id == idTentativa && estado == EstadoConexao.CONECTADO) {
                            backoff.zerar();
                        }
                    }));
                }
            }
            case AGENTE_FORA, AGENTE_DESATUALIZADO -> {
                cancelarTimerEstavel();
                mudar(EstadoConexao.SSH_OK_SEM_AGENTE,
                    r == SondaDoAgente.Resultado.AGENTE_FORA ? MensagensDeFalha.agenteFora() : MensagensDeFalha.agenteDesatualizado(),
                    null);
                // O SSH e os terminais seguem; o app insiste no teste do agente sem derrubar nada (um deploy do
                // painel no meio do caminho faz o agente desatualizado virar CONECTADO sozinho).
                timer = agendador.agendar(cfg.intervaloSemAgente(), () -> post(() -> {
                    if (id == idTentativa && estado == EstadoConexao.SSH_OK_SEM_AGENTE) {
                        iniciarSonda(id);
                    }
                }));
            }
            case TOKEN_RECUSADO -> {
                cancelarTimerEstavel();
                mudar(EstadoConexao.TOKEN_RECUSADO, MensagensDeFalha.tokenRecusado(), null);
            }
            case REQUER_2FA -> {
                cancelarTimerEstavel();
                mudar(EstadoConexao.AGUARDANDO_2FA, MensagensDeFalha.codigo2faPendente(), null);
            }
        }
    }

    private void aoFalharConexao(long id, Fase fase, Throwable erro, Autopsia autopsia, FalhaDoTunel doTunel, boolean tunelDeTransporte) {
        if (id != idTentativa || encerrado || estado != EstadoConexao.CONECTANDO) {
            return;
        }
        if (erro instanceof CofreBloqueadoException) {
            mudar(EstadoConexao.BLOQUEADO_COFRE, null, null);
            return;
        }
        if (doTunel != null) {
            // o túnel externo não subiu ou morreu (programa ausente, login expirado, prazo...): a frase é a da causa, e a próxima tentativa segue o backoff de sempre
            tentativa++;
            reconectarComBackoff(Falha.doTunel(doTunel));
            return;
        }
        VerificadorDeChaveDoServidor.Veredito veredito = autopsia.veredito();
        if (veredito != null && (veredito.resultado() == VerificadorDeChaveDoServidor.Resultado.DIVERGENTE
            || veredito.resultado() == VerificadorDeChaveDoServidor.Resultado.SEM_PIN_PARA_O_ALGORITMO)) {
            // Sem retry e sem "aceitar mesmo assim": só "redefinir pin" explícito, com a senha-mestra.
            mudar(EstadoConexao.ERRO_CHAVE_HOST, MensagensDeFalha.pinDivergente(veredito.divergencia()), null);
            return;
        }
        String semChaveDoTipoPinado = negociacaoDeChaveDoHostFalhou(erro);
        if (semChaveDoTipoPinado != null) {
            // O cliente só oferece o algoritmo do pin; o servidor não tem chave desse tipo (trocou de chave?).
            // Tentar de novo não resolve: pede a decisão do usuário, como no pin divergente.
            mudar(EstadoConexao.ERRO_CHAVE_HOST, MensagensDeFalha.semChaveDoTipoPinado(semChaveDoTipoPinado), null);
            return;
        }
        if (erro instanceof CofreException) {
            mudar(EstadoConexao.ERRO_AUTENTICACAO,
                new Falha(Falha.Tipo.CHAVE_ILEGIVEL, "Não consegui ler a chave SSH guardada no cofre."), null);
            return;
        }
        if (autopsia.chaveRecusada() || (fase == Fase.AUTENTICANDO && pareceRecusaDeAutenticacao(erro))) {
            // A causa REAL vai para o log (redigida): "chave recusada" na tela esconde se o servidor disse não ou se o app nem conseguiu assinar.
            LOG.warn("Autenticação falhou em {} (fase {}, servidor recusou={}, chave oferecida={}, impressão da chave do app={}): {}; último erro da sessão: {}", servidorId, fase,
                autopsia.servidorRecusou(), autopsia.chaveOferecida(), autopsia.impressaoDaChave(), descrever(erro), descrever(autopsia.erroDaSessao()));
            Falha motivo = classificarAutenticacao(autopsia.servidorRecusou(), autopsia.chaveOferecida(), erro, autopsia.impressaoDaChave());
            if (!retentouAutenticacao) {
                retentouAutenticacao = true;
                Instant quando = agendador.agora().plus(cfg.esperaErroAutenticacao());
                mudar(EstadoConexao.ERRO_AUTENTICACAO, motivo, quando);
                timer = agendador.agendar(cfg.esperaErroAutenticacao(), () -> post(() -> {
                    if (estado == EstadoConexao.ERRO_AUTENTICACAO && !encerrado) {
                        iniciarTentativa();
                    }
                }));
            } else {
                mudar(EstadoConexao.ERRO_AUTENTICACAO, motivo, null);
            }
            return;
        }
        tentativa++;
        reconectarComBackoff(tunelDeTransporte
            ? MensagensDeFalha.sshPeloTunel(erro, autopsia.erroDaSessao())
            : MensagensDeFalha.rede(erro, autopsia.erroDaSessao()));
    }

    /** O túnel externo terminou sozinho depois de pronto: derruba a sessão que andava por ele, com a causa na mensagem. */
    private void aoCairOTunel(long id, FalhaDoTunel falha) {
        if (id != idTentativa || encerrado) {
            return; // o túnel desta tentativa já foi descartado
        }
        ClientSession s = sessao;
        if (s == null) {
            return; // ainda conectando: o connect falha e a causa do túnel é lida lá (conectarEmFundo)
        }
        s.setAttribute(FALHA_DO_TUNEL, falha);
        Fundo.ioVirtual().execute(() -> {
            try {
                s.close(true);
            } catch (RuntimeException e) {
                LOG.debug("Falha ao fechar a sessão do túnel que caiu: {}", e.toString());
            }
        });
    }

    /**
     * "O servidor recusou a chave" (ele disse não, ou o app a ofereceu e a falha foi de autenticação) contra "o app não conseguiu usar a chave" (nem a ofereceu, ou a assinatura
     * deu erro de segurança): a ação do usuário é outra (instalar a chave no servidor, ou olhar o log).
     */
    static Falha classificarAutenticacao(boolean servidorRecusou, boolean chaveOferecida, Throwable erro, String impressao) {
        if (servidorRecusou) {
            return MensagensDeFalha.chaveRecusada(impressao);
        }
        boolean errouAoAssinar = false;
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof java.security.GeneralSecurityException) {
                errouAoAssinar = true;
            }
        }
        if (errouAoAssinar || !chaveOferecida) {
            return MensagensDeFalha.naoUsouAChave(impressao);
        }
        return MensagensDeFalha.chaveRecusada(impressao);
    }

    private void aoSessaoFechada(long id) {
        if (id != idTentativa || encerrado) {
            return; // a sessão foi descartada por nós (bloqueio, sono, nova tentativa)
        }
        Throwable motivo = sessao == null ? null : sessao.getAttribute(ERRO_DA_SESSAO);
        FalhaDoTunel doTunel = sessao == null ? null : sessao.getAttribute(FALHA_DO_TUNEL);
        if (doTunel == null && destinoAtual != null) {
            doTunel = destinoAtual.falhaDoTunel().orElse(null); // o aviso da queda pode ainda não ter chegado: confere na hora
        }
        descartarSessaoAtual();
        tentativa++;
        reconectarComBackoff(doTunel != null ? Falha.doTunel(doTunel) : MensagensDeFalha.sessaoPerdida(motivo));
    }

    private void reconectarComBackoff(Falha motivo) {
        cancelarTimer();
        cancelarTimerEstavel();
        Duration espera = backoff.proximo();
        Instant quando = agendador.agora().plus(espera);
        mudar(EstadoConexao.RECONECTANDO, motivo, quando);
        long epoca = idTentativa;
        timer = agendador.agendar(espera, () -> post(() -> {
            if (estado == EstadoConexao.RECONECTANDO && epoca == idTentativa && !encerrado) {
                iniciarTentativa();
            }
        }));
    }

    // ----- verificação ativa -----

    private void verificarEmFundo(long id, ClientSession s, TunelSsh t) {
        boolean viva = true;
        try {
            keepaliveComResposta(s, cfg.verificacaoAtiva());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            post(() -> verificando = false);
            return;
        } catch (IOException | TimeoutException | ExecutionException | RuntimeException e) {
            viva = false;
            LOG.info("Verificação ativa de {} falhou: {}", servidorId, Redator.redigir(e));
        }
        if (!viva) {
            try {
                s.close(true);
            } catch (RuntimeException e) {
                LOG.debug("Falha ao fechar a sessão morta: {}", e.toString());
            }
            post(() -> {
                verificando = false;
                aoSessaoFechada(id);
            });
            return;
        }
        SondaDoAgente.Resultado r;
        try {
            r = sonda.sondar(t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            post(() -> verificando = false);
            return;
        } catch (RuntimeException e) {
            r = SondaDoAgente.Resultado.AGENTE_FORA;
        }
        SondaDoAgente.Resultado veredito = r;
        post(() -> {
            verificando = false;
            aoResultadoDaSonda(id, veredito);
        });
    }

    /**
     * Pergunta "você está aí?" e espera QUALQUER resposta no prazo. O OpenSSH responde ao
     * {@code keepalive@openssh.com} com sucesso ou com "request failure" (pedido desconhecido): as duas provam
     * que a sessão vive. Só o silêncio prova que não. Não usa {@code Session.request(String, Buffer, Duration)}
     * porque ele lança {@code BufferException} ao ler a resposta, mesmo quando ela chega.
     */
    private static void keepaliveComResposta(ClientSession s, Duration prazo)
        throws IOException, TimeoutException, ExecutionException, InterruptedException {
        CompletableFuture<Integer> resposta = new CompletableFuture<>();
        Buffer pedido = s.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST, 64);
        pedido.putString(ClienteSshFactory.PEDIDO_DE_KEEPALIVE);
        pedido.putBoolean(true);
        s.request(pedido, ClienteSshFactory.PEDIDO_DE_KEEPALIVE, (comando, corpo) -> resposta.complete(comando));
        resposta.get(prazo.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ----- utilitários do ator -----

    private void mudar(EstadoConexao novo, Falha f, Instant proxima) {
        estado = novo;
        falha = f;
        proximaEm = proxima;
        if (novo == EstadoConexao.CONECTADO) {
            semConexaoDesde = null;
        } else if (semConexaoDesde == null && novo != EstadoConexao.DESCONECTADO) {
            semConexaoDesde = agendador.agora();
        }
        publicar();
    }

    private void publicar() {
        TunelSsh t = tunel;
        InfoConexao nova = new InfoConexao(estado, falha, tentativa, proximaEm, semConexaoDesde,
            t == null ? 0 : t.geracao(), t == null ? null : t.portaLocal());
        if (nova.equals(info)) {
            return;
        }
        info = nova;
        for (Consumer<InfoConexao> o : ouvintes) {
            try {
                o.accept(nova);
            } catch (RuntimeException e) {
                LOG.warn("Ouvinte da conexão {} falhou: {}", servidorId, e.toString());
            }
        }
    }

    /**
     * Fecha sessão, túnel do agente e caminho até o SSH (o túnel externo) em segundo plano e invalida os eventos que ainda chegarem deles. O futuro completa quando tudo foi
     * fechado (o {@link #fechar()} espera por ele para o programa do túnel não ficar órfão).
     */
    private CompletableFuture<Void> descartarSessaoAtual() {
        idTentativa++;
        ClientSession s = sessao;
        TunelSsh t = tunel;
        DestinoSsh d = destinoAtual;
        sessao = null;
        tunel = null;
        destinoAtual = null;
        sessaoViva = null;
        tunelAtual = null;
        verificando = false;
        cancelarTimerEstavel();
        Thread abrindo = threadAbrindoOTunel;
        if (abrindo != null) {
            abrindo.interrupt(); // uma tentativa ainda esperando o túnel subir: o túnel que subir tarde é encerrado por quem o abre
        }
        CompletableFuture<Void> pronto = new CompletableFuture<>();
        if (s == null && t == null && d == null) {
            pronto.complete(null);
        } else {
            Fundo.ioVirtual().execute(() -> {
                try {
                    fecharQuieto(s, t, d);
                } finally {
                    pronto.complete(null);
                }
            });
        }
        return pronto;
    }

    private static void fecharQuieto(ClientSession s, TunelSsh t, DestinoSsh d) {
        try {
            if (t != null) {
                t.close();
            }
            if (s != null) {
                try {
                    s.close(true);
                } catch (RuntimeException e) {
                    LOG.debug("Falha ao fechar a sessão: {}", e.toString());
                }
            }
        } finally {
            if (d != null) {
                d.close(); // por último: encerra o programa do túnel externo (e os filhos dele)
            }
        }
    }

    private void cancelarTimer() {
        if (timer != null) {
            timer.cancelar();
            timer = null;
        }
    }

    private void cancelarTimerEstavel() {
        if (timerEstavel != null) {
            timerEstavel.cancelar();
            timerEstavel = null;
        }
    }

    private List<Pin> pinsAtuais() {
        try {
            return cofre.servidor(servidorId).map(ConfigServidor::pins).orElse(List.of());
        } catch (CofreBloqueadoException e) {
            return List.of(); // sem pins a verificação falha: nada de conectar com o cofre fechado
        }
    }

    /** Cria (uma vez) o cliente SSH deste servidor e ajusta os algoritmos de host aos pins de agora. */
    private synchronized SshClient clienteParaConectar(ConfigServidor servidor) {
        if (cliente == null) {
            cliente = ClienteSshFactory.criar(verificador, cfg, AlgoritmosDeHost.paraPins(servidor.pins()));
            cliente.setPublicKeyAuthenticationReporter(new PublicKeyAuthenticationReporter() {
                @Override
                public void signalAuthenticationAttempt(ClientSession s, String servico, KeyPair chave, String algoritmo) {
                    s.setAttribute(CHAVE_OFERECIDA, Boolean.TRUE);
                }

                @Override
                public void signalAuthenticationFailure(ClientSession s, String servico, KeyPair chave,
                                                        boolean parcial, List<String> metodos) {
                    s.setAttribute(CHAVE_RECUSADA, Boolean.TRUE);
                    s.setAttribute(SERVIDOR_RECUSOU, Boolean.TRUE);
                }

                @Override
                public void signalAuthenticationExhausted(ClientSession s, String servico) {
                    s.setAttribute(CHAVE_RECUSADA, Boolean.TRUE);
                }
            });
            // No cliente (e não na sessão, depois do connect): pega também o que o MINA aponta antes de
            // o connect() devolver a sessão. Cada exceção vai para a sessão que a sofreu.
            cliente.addSessionListener(new SessionListener() {
                @Override
                public void sessionException(Session sessaoMina, Throwable erro) {
                    sessaoMina.setAttribute(ERRO_DA_SESSAO, erro);
                }
            });
            cliente.start();
        } else {
            // Um pin novo (outro algoritmo) muda o que se oferece ao servidor.
            cliente.setSignatureFactoriesNames(AlgoritmosDeHost.paraPins(servidor.pins()));
        }
        return cliente;
    }

    /** A mensagem do MINA quando cliente e servidor não têm algoritmo de chave de host em comum, ou {@code null}. */
    private static String negociacaoDeChaveDoHostFalhou(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("server host key algorithms")) {
                return m;
            }
        }
        return null;
    }

    /** A exceção e a cadeia de causas em uma linha, já sem segredo (para o log). */
    private static String descrever(Throwable erro) {
        if (erro == null) {
            return "(nenhum)";
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Throwable t = erro; t != null && n < 5; t = t.getCause(), n++) {
            if (n > 0) {
                b.append(" <- ");
            }
            b.append(t.getClass().getSimpleName()).append(": ").append(Redator.redigir(t));
        }
        return b.toString();
    }

    private static boolean pareceRecusaDeAutenticacao(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException
                || t instanceof ConnectException) {
                return false;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("No more authentication methods") || m.contains("Authentication failed")
                || m.contains("authentication failed"))) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================================================
    // Mensagens (pt-BR, sem segredo)
    // =====================================================================================================

    static final class MensagensDeFalha {

        private MensagensDeFalha() { }

        static Falha pinAusente() {
            return new Falha(Falha.Tipo.PIN_AUSENTE,
                "A chave deste servidor ainda não foi confirmada. Confira a impressão antes de conectar.");
        }

        static Falha pinDivergente(VerificadorDeChaveDoServidor.Divergencia d) {
            if (d == null) {
                return new Falha(Falha.Tipo.PIN_DIVERGENTE, "A chave do servidor não confere com a confirmada.");
            }
            if (d.esperada() == null) {
                return new Falha(Falha.Tipo.PIN_DIVERGENTE, "O servidor apresentou uma chave " + d.algoritmo() + " ("
                    + d.recebida() + ") que você não confirmou. Não conecte sem conferir.");
            }
            return new Falha(Falha.Tipo.PIN_DIVERGENTE, "A chave do servidor mudou: esperada " + d.esperada()
                + ", recebida " + d.recebida() + ". Pode ser interceptação; não conecte sem conferir.");
        }

        private static final java.util.regex.Pattern LISTAS_DO_MINA =
            java.util.regex.Pattern.compile("\\(client: (.*?) / server: (.*?)\\)");

        static Falha semChaveDoTipoPinado(String mensagemDoMina) {
            var m = LISTAS_DO_MINA.matcher(mensagemDoMina);
            String detalhe = m.find() ? " (confirmado: " + m.group(1) + "; o servidor oferece: " + m.group(2) + ")" : "";
            return new Falha(Falha.Tipo.PIN_DIVERGENTE, "O servidor não oferece o tipo de chave que você confirmou"
                + detalhe + ". Se as chaves do servidor foram trocadas, redefina a confirmação do host.");
        }

        static Falha chaveRecusada(String impressao) {
            return new Falha(Falha.Tipo.CHAVE_RECUSADA,
                "O servidor recusou a chave do app" + comImpressao(impressao) + ". Confira se esta chave está no authorized_keys do usuário no servidor. "
                    + "Se você usa mais de um cofre (por exemplo o app instalado e o modo de desenvolvimento), cada cofre tem a sua chave.");
        }

        static Falha naoUsouAChave(String impressao) {
            return new Falha(Falha.Tipo.CHAVE_NAO_USADA,
                "O app não chegou a usar a chave" + comImpressao(impressao) + ": a assinatura falhou ou o servidor não aceita chave pública para este usuário. O servidor não a recusou; o motivo está no log do app.");
        }

        private static String comImpressao(String impressao) {
            return impressao == null || impressao.isBlank() ? "" : " (impressão digital " + impressao + ")";
        }

        static Falha tokenRecusado() {
            return new Falha(Falha.Tipo.TOKEN_RECUSADO,
                "O agente recusou o token deste computador. Registre o token de novo.");
        }

        static Falha codigo2faPendente() {
            return new Falha(Falha.Tipo.CODIGO_2FA_PENDENTE, "Código do autenticador pendente.");
        }

        static Falha agenteDesatualizado() {
            return new Falha(Falha.Tipo.AGENTE_DESATUALIZADO,
                "O agente do painel é anterior a este app (não tem GET /api/versao). Publique o painel no servidor.");
        }

        static Falha agenteFora() {
            return new Falha(Falha.Tipo.AGENTE_FORA, "O agente do painel não responde (reiniciando?).");
        }

        static Falha rede(Throwable erro, Throwable daSessao) {
            return new Falha(Falha.Tipo.REDE, "Não consegui conectar: " + descrever(erro, daSessao) + ".");
        }

        /** O túnel externo abriu, mas o SSH não deu certo por ele (a causa de rede, em palavras simples, e para onde olhar). */
        static Falha sshPeloTunel(Throwable erro, Throwable daSessao) {
            return new Falha(Falha.Tipo.TRANSPORTE_OUTRO, "O túnel abriu, mas não consegui falar SSH por ele: " + descrever(erro, daSessao)
                + ". Confira se o SSH está ligado no servidor (e, no SSM, a porta SSH da instância).");
        }

        static Falha sessaoPerdida(Throwable motivo) {
            return new Falha(Falha.Tipo.REDE, "A conexão SSH caiu" + (motivo == null ? "" : " (" + descrever(motivo, null) + ")") + ".");
        }

        private static String descrever(Throwable erro, Throwable daSessao) {
            Throwable t = erro != null ? erro : daSessao;
            if (t == null) {
                return "motivo desconhecido";
            }
            Throwable raiz = t;
            while (raiz.getCause() != null && raiz.getCause() != raiz) {
                raiz = raiz.getCause();
            }
            if (raiz instanceof ConnectException) {
                return "conexão recusada";
            }
            if (raiz instanceof NoRouteToHostException) {
                return "sem rota até o servidor";
            }
            if (raiz instanceof UnknownHostException) {
                return "servidor desconhecido";
            }
            if (raiz instanceof SocketTimeoutException || raiz instanceof TimeoutException) {
                return "tempo esgotado";
            }
            String m = raiz.getMessage();
            if (m != null && m.contains("heartbeat requests without reply")) {
                return "o servidor parou de responder";
            }
            return m == null || m.isBlank() ? raiz.getClass().getSimpleName() : Redator.redigir(m);
        }
    }
}
