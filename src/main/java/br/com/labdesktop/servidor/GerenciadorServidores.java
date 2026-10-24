package br.com.labdesktop.servidor;

import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.ConfigServidor;
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

    /** Para de acompanhar e fecha um servidor (o cofre é quem o apaga). */
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
