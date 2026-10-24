package br.com.labdesktop.servidor;

import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.MonitorDeSono;
import br.com.labdesktop.util.Fundo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O coordenador: um {@link ServidorLab} por servidor do cofre (ARQUITETURA 4.2). Vários servidores não
 * multiplicam a carga de um mesmo servidor: cada um tem a sua conexão e o seu Sondador.
 *
 * <p>Vive enquanto o cofre está aberto. Ao BLOQUEAR o cofre, fecha sessões e túneis de todos
 * ({@link AcessoAoCofre#aoBloquear}); a casca descarta este gerenciador ({@link #fechar()}) e cria outro quando
 * o cofre for aberto de novo.
 */
public final class GerenciadorServidores implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GerenciadorServidores.class);

    private final AcessoAoCofre cofre;
    private final PedidoDeCodigo2fa pedidoDeCodigo;
    private final ConfigSsh cfgSsh;
    private final Map<String, ServidorLab> servidores = new LinkedHashMap<>();
    private final List<Consumer<List<ServidorLab>>> observadoresDaLista = new CopyOnWriteArrayList<>();
    private final List<Consumer<ServidorLab>> ligadoresDeSono = new ArrayList<>();
    private boolean fechado;

    public GerenciadorServidores(AcessoAoCofre cofre, PedidoDeCodigo2fa pedidoDeCodigo) {
        this(cofre, pedidoDeCodigo, ConfigSsh.padrao());
    }

    /** Cria um {@link ServidorLab} (ainda parado) para cada servidor que o cofre tem e liga o bloqueio. */
    public GerenciadorServidores(AcessoAoCofre cofre, PedidoDeCodigo2fa pedidoDeCodigo, ConfigSsh cfgSsh) {
        this.cofre = cofre;
        this.pedidoDeCodigo = pedidoDeCodigo;
        this.cfgSsh = cfgSsh;
        for (ConfigServidor c : cofre.servidores()) {
            servidores.put(c.id(), new ServidorLab(c.id(), cofre, pedidoDeCodigo, cfgSsh));
        }
        cofre.aoBloquear(this::aoBloquearOCofre);
    }

    /** Os servidores, na ordem do cofre. */
    public synchronized List<ServidorLab> servidores() {
        return List.copyOf(servidores.values());
    }

    public synchronized Optional<ServidorLab> servidor(String id) {
        return Optional.ofNullable(servidores.get(id));
    }

    /**
     * Passa a receber a lista de servidores toda vez que ela muda (servidor adicionado ou removido), pelo despachante
     * da EDT ({@code Fundo.naEdt}). A lista atual chega logo em seguida. É o que mantém a barra de servidores, a
     * tela de servidores e a navegação em dia depois do assistente "Adicionar servidor".
     */
    public Inscricao observarLista(Consumer<List<ServidorLab>> ouvinte) {
        observadoresDaLista.add(ouvinte);
        List<ServidorLab> atual = servidores();
        Fundo.naEdt(() -> ouvinte.accept(atual));
        return () -> observadoresDaLista.remove(ouvinte);
    }

    private void avisarLista() {
        List<ServidorLab> atual = servidores();
        for (Consumer<List<ServidorLab>> o : observadoresDaLista) {
            Fundo.naEdt(() -> o.accept(atual));
        }
    }

    /** Começa a conectar todos. */
    public void iniciarTodos() {
        servidores().forEach(ServidorLab::iniciar);
    }

    /**
     * Passa a acompanhar um servidor que o cofre acabou de ganhar (o assistente "Adicionar servidor" grava no
     * cofre e então chama isto). Não inicia a conexão.
     */
    public ServidorLab adicionar(String servidorId) {
        ServidorLab novo;
        synchronized (this) {
            if (fechado) {
                throw new IllegalStateException("O gerenciador já foi fechado.");
            }
            ServidorLab existente = servidores.get(servidorId);
            if (existente != null) {
                return existente;
            }
            novo = new ServidorLab(servidorId, cofre, pedidoDeCodigo, cfgSsh);
            servidores.put(servidorId, novo);
            for (Consumer<ServidorLab> ligar : ligadoresDeSono) {
                ligar.accept(novo);
            }
        }
        avisarLista();
        return novo;
    }

    /**
     * Só a CONEXÃO: para de acompanhar e fecha um servidor, e o cofre continua com ele. Para o botão "Remover" use
     * {@link #removerServidor}, que cuida do cofre, da chave e do acesso no servidor.
     */
    public CompletableFuture<Void> remover(String servidorId) {
        ServidorLab lab;
        synchronized (this) {
            lab = servidores.remove(servidorId);
        }
        if (lab == null) {
            return CompletableFuture.completedFuture(null);
        }
        avisarLista();
        return lab.fechar();
    }

    // =====================================================================================================
    // Editar
    // =====================================================================================================

    /**
     * O que a edição fez, para a tela contar ao usuário.
     *
     * @param config           a configuração gravada
     * @param mudouAlgo        {@code false} se os dados novos eram iguais aos de antes (nada foi gravado nem reconectado)
     * @param impressaoApagada o endereço ou a porta mudou e a impressão digital confirmada foi apagada: a próxima conexão
     *                         pede para confirmá-la de novo
     */
    public record ResultadoDaEdicao(ConfigServidor config, boolean mudouAlgo, boolean impressaoApagada) { }

    /**
     * Grava no cofre a edição de nome, endereço, porta, usuário (e porta do agente) de um servidor e reconecta com os dados
     * novos. As mesmas regras do assistente valem ({@link Dados#validar}); a regra do pin (mudou o endereço ou a porta,
     * apaga a impressão digital) está em {@link ConfigServidor#editar} e roda DENTRO da gravação atômica do cofre, então
     * uma dica do 2FA gravada ao mesmo tempo não se perde. Bloqueia (disco): fora da EDT.
     *
     * @throws IllegalArgumentException dados que não valem, ou servidor que já não existe
     */
    public ResultadoDaEdicao atualizar(String servidorId, Dados dados) throws CofreException {
        dados.validar();
        ServidorLab lab = servidor(servidorId)
            .orElseThrow(() -> new IllegalArgumentException("Esse servidor não está mais na lista."));
        AtomicReference<ConfigServidor> antes = new AtomicReference<>();
        AtomicReference<ConfigServidor> depois = new AtomicReference<>();
        cofre.alterarServidor(servidorId, atual -> {
            ConfigServidor nova = atual.editar(dados.nome(), dados.host(), dados.porta(), dados.usuario(), dados.portaDoAgente());
            antes.set(atual);
            depois.set(nova);
            return nova;
        });
        boolean mudou = !antes.get().equals(depois.get());
        boolean impressaoApagada = !antes.get().pins().isEmpty() && depois.get().pins().isEmpty();
        if (mudou) {
            lab.aoEditarNoCofre();
        }
        return new ResultadoDaEdicao(depois.get(), mudou, impressaoApagada);
    }

    // =====================================================================================================
    // Remover
    // =====================================================================================================

    /**
     * O que o usuário escolheu no diálogo "Remover servidor".
     *
     * @param apagarAChave       apagar a chave do servidor do cofre (só se nenhum outro servidor a usa; senão ela fica)
     * @param revogarNoServidor  tirar o acesso deste app no servidor, se a conexão estiver de pé; melhor esforço. Apaga o token e,
     *                           só se a chave foi criada pelo app e nenhum outro servidor a usa, tira também a chave do servidor
     */
    public record OpcoesDeRemocao(boolean apagarAChave, boolean revogarNoServidor) { }

    /**
     * O que a remoção fez.
     *
     * @param existia               {@code false} se o servidor já não existia (nada aconteceu, e não é erro)
     * @param chaveApagada          a chave saiu do cofre (e foi zerada na memória)
     * @param tokenRevogado         o token deste Mac foi apagado no agente
     * @param chaveTiradaDoServidor a chave deste app foi tirada do {@code authorized_keys} do servidor
     * @param avisos                o que não deu certo na revogação, em português (a remoção seguiu mesmo assim)
     */
    public record ResultadoDaRemocao(boolean existia, boolean chaveApagada, boolean tokenRevogado,
                                     boolean chaveTiradaDoServidor, List<String> avisos) {

        public ResultadoDaRemocao {
            avisos = List.copyOf(avisos);
        }

        static final ResultadoDaRemocao NADA = new ResultadoDaRemocao(false, false, false, false, List.of());
    }

    /** Quanto esperar a conexão fechar antes de apagar o servidor do cofre (o fechamento termina sozinho em segundo plano). */
    private static final long PRAZO_PARA_FECHAR_SEGUNDOS = 15;

    /**
     * Os nomes dos OUTROS servidores que usam a mesma chave SSH (vazio se ela é só deste). Compara a chave pública (a
     * impressão digital), não só o id: o cofre aceitava duas entradas com a mesma chave (o mesmo arquivo importado duas
     * vezes), e para o servidor elas são UMA chave. Tirar a chave do {@code authorized_keys} por causa de um só deles
     * deixaria o outro sem acesso.
     */
    public List<String> outrosQueUsamAChaveDe(String servidorId) {
        ConfigServidor este = cofre.servidor(servidorId).orElse(null);
        if (este == null) {
            return List.of();
        }
        String impressao = cofre.infoDaChave(este.chaveId()).map(InfoChave::impressao).orElse(null);
        return cofre.servidores().stream()
            .filter(o -> !o.id().equals(servidorId) && usaAMesmaChave(o, este, impressao))
            .map(ConfigServidor::nome)
            .toList();
    }

    private boolean usaAMesmaChave(ConfigServidor outro, ConfigServidor este, String impressaoDeste) {
        if (outro.chaveId().equals(este.chaveId())) {
            return true;
        }
        return impressaoDeste != null
            && cofre.infoDaChave(outro.chaveId()).map(c -> impressaoDeste.equals(c.impressao())).orElse(false);
    }

    /** Quem criou a chave do servidor (o app ou o usuário): o diálogo "Remover" diz ao usuário se ela será tirada do servidor. */
    public InfoChave.Origem origemDaChaveDe(String servidorId) {
        return cofre.servidor(servidorId)
            .flatMap(c -> cofre.infoDaChave(c.chaveId()))
            .map(InfoChave::origem)
            .orElse(InfoChave.Origem.DESCONHECIDA);
    }

    /**
     * Remove um servidor de ponta a ponta, nesta ordem: (1) lê do cofre o que a revogação vai precisar (chave pública, origem
     * dela, nome do dispositivo); (2) apaga do cofre o servidor, o token e, se pedido e se ninguém mais a usa, a chave, numa
     * gravação só: se o cofre não gravar, NADA mais aconteceu (nem no servidor, nem na lista) e a exceção sobe; (3) tira o
     * servidor da lista, para as telas soltarem as assinaturas; (4) se pedido e se a conexão está de pé, revoga o acesso do
     * app no servidor (token e, se foi o app que a criou e ninguém mais a usa, a chave; melhor esforço, o que falhar vira
     * aviso); (5) fecha a conexão, o túnel, a API e o Sondador.
     *
     * <p>O cofre vem ANTES da revogação porque a revogação é irreversível: com o disco cheio, o servidor voltaria à lista com
     * um token e uma chave que o servidor já não aceita. A conexão fica aberta até o fim justamente para a revogação
     * conseguir falar com o servidor depois de o cofre tê-lo esquecido. Servidor que já não existe devolve
     * {@link ResultadoDaRemocao#existia()} {@code false} e não é erro. Bloqueia (SSH e disco): fora da EDT.
     */
    public ResultadoDaRemocao removerServidor(String servidorId, OpcoesDeRemocao opcoes) throws CofreException {
        ServidorLab lab;
        synchronized (this) {
            lab = servidores.get(servidorId);
        }
        ConfigServidor config = cofre.servidor(servidorId).orElse(null);
        if (lab == null && config == null) {
            return ResultadoDaRemocao.NADA;
        }
        List<String> avisos = new ArrayList<>();
        ServidorLab.AcessoDoApp acesso = null;
        boolean outroUsaAChave = false;
        if (lab != null && config != null && opcoes.revogarNoServidor()) {
            acesso = lab.lerAcessoDoApp().orElse(null);
            outroUsaAChave = !outrosQueUsamAChaveDe(servidorId).isEmpty();
        }

        boolean chaveApagada = false;
        if (config != null) {
            try {
                chaveApagada = cofre.removerServidor(servidorId, opcoes.apagarAChave());
            } catch (CofreException | RuntimeException e) {
                // Nada foi tocado ainda: o servidor continua na lista, conectado, com token e chave valendo.
                LOG.warn("Não consegui apagar o servidor {} do cofre: {}", servidorId, e.toString());
                throw e;
            }
            if (lab != null) {
                lab.aoSairDoCofre();
            }
        }

        boolean estavaNaLista;
        synchronized (this) {
            estavaNaLista = servidores.remove(servidorId) != null;
        }
        if (estavaNaLista) {
            avisarLista();
        }

        boolean tokenRevogado = false;
        boolean chaveTirada = false;
        if (lab != null && opcoes.revogarNoServidor()) {
            ServidorLab.ResultadoDaRevogacao r = lab.revogarAcessoDoApp(acesso, !outroUsaAChave);
            tokenRevogado = r.tokenRevogado();
            chaveTirada = r.chaveTiradaDoServidor();
            avisos.addAll(r.avisos());
            if (outroUsaAChave && acesso != null) {
                avisos.add("A chave deste servidor continua no acesso do usuário " + acesso.usuario() + " no servidor porque "
                    + "outro servidor seu também a usa.");
            }
        }
        if (lab != null) {
            fecharEsperando(lab);
        }
        return new ResultadoDaRemocao(true, chaveApagada, tokenRevogado, chaveTirada, avisos);
    }

    private void fecharEsperando(ServidorLab lab) {
        try {
            lab.fechar().get(PRAZO_PARA_FECHAR_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            LOG.warn("A conexão de {} demorou para fechar; sigo com a remoção: {}", lab.id(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Liga o aviso de sono do Mac a todos os servidores, inclusive aos que entrarem depois (o assistente "Adicionar
     * servidor" cadastra com o app já aberto). O que a chamada devolve desfaz a ligação de todos.
     */
    public Runnable acompanhar(MonitorDeSono monitor) {
        Map<String, List<Runnable>> desfazer = new java.util.HashMap<>();
        Consumer<ServidorLab> ligar = lab -> {
            List<Runnable> d = new ArrayList<>();
            d.add(monitor.aoDormir(lab::aoDormir));
            d.add(monitor.aoAcordar(lab::aoAcordar));
            synchronized (desfazer) {
                desfazer.put(lab.id(), d);
            }
        };
        synchronized (this) {
            servidores.values().forEach(ligar);
            ligadoresDeSono.add(ligar);
        }
        return () -> {
            synchronized (this) {
                ligadoresDeSono.remove(ligar);
            }
            synchronized (desfazer) {
                desfazer.values().forEach(l -> l.forEach(Runnable::run));
                desfazer.clear();
            }
        };
    }

    /** Aplica o estado da janela a todos os servidores. */
    public void definirJanela(EstadoJanela janela) {
        servidores().forEach(s -> s.definirJanela(janela));
    }

    private void aoBloquearOCofre() {
        LOG.info("Cofre bloqueado: fechando as conexões de {} servidor(es).", servidores().size());
        servidores().forEach(ServidorLab::bloquear);
    }

    /** Encerra todos (SSH, túneis, APIs, Sondadores). Idempotente. */
    public CompletableFuture<Void> fechar() {
        List<ServidorLab> labs;
        synchronized (this) {
            fechado = true;
            labs = List.copyOf(servidores.values());
            servidores.clear();
        }
        CompletableFuture<?>[] todos = labs.stream().map(ServidorLab::fechar).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(todos);
    }

    @Override
    public void close() {
        fechar();
    }
}
