package br.com.labdesktop.teste;

import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.CofreBloqueadoException;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;

/**
 * Um {@link AcessoAoCofre} de 40 linhas para os testes de ssh, api e servidor: evita abrir um cofre de verdade
 * (e pagar o Argon2id). Guarda UM servidor, um par de chaves e, se houver, o token.
 */
public final class CofreFalso implements AcessoAoCofre {

    public static final String ID = "s1";

    private volatile ConfigServidor config;
    private final KeyPair par;
    private volatile TokenAgente token;
    private volatile boolean bloqueado;
    private volatile boolean removido;
    private volatile InfoChave.Origem origemDaChave = InfoChave.Origem.GERADA_PELO_APP;
    private final List<Runnable> aoBloquear = new CopyOnWriteArrayList<>();
    public final AtomicInteger leiturasDaChave = new AtomicInteger();
    public final List<String> pinsGravados = new CopyOnWriteArrayList<>();
    /** Ids dos servidores que o teste mandou remover, e se a chave foi junto. */
    public final List<String> servidoresRemovidos = new CopyOnWriteArrayList<>();
    public final List<Boolean> chavesApagadas = new CopyOnWriteArrayList<>();

    public CofreFalso(String host, int portaSsh, List<Pin> pins) {
        try {
            this.par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        this.config = new ConfigServidor(ID, "lab", host, portaSsh, "labuser", "k1", 7000, pins,
            Transporte.DIRETO, null, Estado2fa.DESLIGADO);
    }

    public KeyPair par() {
        return par;
    }

    public CofreFalso comPortaDoAgente(int portaAgente) {
        ConfigServidor c = config;
        config = new ConfigServidor(c.id(), c.nome(), c.host(), c.porta(), c.usuario(), c.chaveId(), portaAgente,
            c.pins(), c.transporte(), c.nomeDoDispositivo(), c.estado2fa());
        return this;
    }

    /** O servidor passa a ser "Este computador" (sem SSH, sem chave), com o agente nesta porta de 127.0.0.1. */
    public CofreFalso comoLocal(int portaAgente) {
        ConfigServidor c = config;
        config = new ConfigServidor(c.id(), c.nome(), ConfigServidor.HOST_LOCAL, 0, "", null, portaAgente, List.of(), Transporte.LOCAL,
            c.nomeDoDispositivo(), c.estado2fa());
        return this;
    }

    /** Quem criou a chave do servidor (o padrão é o app): decide se a remoção a tira do servidor. */
    public CofreFalso comOrigemDaChave(InfoChave.Origem origem) {
        this.origemDaChave = origem;
        return this;
    }

    public CofreFalso comToken(TokenAgente t, String nomeDoDispositivo) {
        this.token = t;
        config = config.comNomeDoDispositivo(nomeDoDispositivo);
        return this;
    }

    public void bloquear() {
        bloqueado = true;
        aoBloquear.forEach(Runnable::run);
    }

    private void exigirAberto() {
        if (bloqueado) {
            throw new CofreBloqueadoException();
        }
    }

    @Override
    public List<ConfigServidor> servidores() {
        exigirAberto();
        return removido ? List.of() : List.of(config);
    }

    @Override
    public Optional<ConfigServidor> servidor(String servidorId) {
        exigirAberto();
        return ID.equals(servidorId) && !removido ? Optional.of(config) : Optional.empty();
    }

    @Override
    public KeyPair keyPairDoServidor(String servidorId) throws CofreException {
        exigirAberto();
        leiturasDaChave.incrementAndGet();
        return par;
    }

    @Override
    public Optional<TokenAgente> tokenDoServidor(String servidorId) {
        exigirAberto();
        return Optional.ofNullable(token);
    }

    @Override
    public void gravarPin(String servidorId, Pin pin) {
        exigirAberto();
        pinsGravados.add(pin.impressao());
        config = config.comPin(pin);
    }

    @Override
    public void limparPins(String servidorId) {
        exigirAberto();
        config = config.semPins();
    }

    @Override
    public void gravarToken(String servidorId, TokenAgente novo, String nomeDoDispositivo) {
        exigirAberto();
        this.token = novo;
        config = config.comNomeDoDispositivo(nomeDoDispositivo);
    }

    @Override
    public synchronized void gravarEstado2fa(String servidorId, Estado2fa estado) { // atômico como o cofre de verdade (a marca automática grava em outra thread)
        exigirAberto();
        config = config.comEstado2fa(estado);
    }

    @Override
    public synchronized void alterarServidor(String servidorId, UnaryOperator<ConfigServidor> mudanca) {
        exigirAberto();
        if (!ID.equals(servidorId) || removido) {
            throw new IllegalArgumentException("Servidor desconhecido.");
        }
        config = mudanca.apply(config);
    }

    @Override
    public boolean removerServidor(String servidorId, boolean apagarAChaveSeLivre) {
        exigirAberto();
        if (!ID.equals(servidorId) || removido) {
            throw new IllegalArgumentException("Servidor desconhecido.");
        }
        removido = true;
        servidoresRemovidos.add(servidorId);
        chavesApagadas.add(apagarAChaveSeLivre);
        return apagarAChaveSeLivre;
    }

    @Override
    public Optional<InfoChave> infoDaChave(String chaveId) {
        exigirAberto();
        return Optional.of(new InfoChave("k1", "lab", "ssh-ed25519",
            PublicKeyEntry.toString(par.getPublic()) + " lab-desktop@teste", KeyUtils.getFingerPrint(par.getPublic()),
            Instant.parse("2026-10-24T12:00:00Z"), origemDaChave));
    }

    @Override
    public void aoBloquear(Runnable ouvinte) {
        aoBloquear.add(ouvinte);
    }
}
