package br.com.labdesktop.transporte;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quem tem túnel externo aberto agora. Existe para UM motivo: o gancho de desligamento da JVM. Se o app fechar sem passar pelo caminho normal (força, queda, {@code kill}
 * brando), o gancho encerra os programas que sobraram, em melhor esforço, para não ficar processo órfão com a porta local ocupada. (Uma morte forçada da JVM, {@code kill -9}, não
 * dá chance a nenhum gancho: ver ARQUITETURA 7.14.)
 */
public final class RegistroDeTuneis {

    private static final Logger LOG = LoggerFactory.getLogger(RegistroDeTuneis.class);
    private static final RegistroDeTuneis DO_PROCESSO = new RegistroDeTuneis(true);

    private final Set<TunelDeTransporte> abertos = ConcurrentHashMap.newKeySet();
    private final boolean comGancho;
    private final AtomicBoolean ganchoInstalado = new AtomicBoolean();

    /** @param comGancho {@code true} instala o gancho de desligamento da JVM na primeira vez que alguém se registra */
    public RegistroDeTuneis(boolean comGancho) {
        this.comGancho = comGancho;
    }

    /** O registro do processo, com o gancho. */
    public static RegistroDeTuneis doProcesso() {
        return DO_PROCESSO;
    }

    void registrar(TunelDeTransporte tunel) {
        abertos.add(tunel);
        if (comGancho && ganchoInstalado.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(this::encerrarTodos, "encerrar-tuneis"));
            } catch (IllegalStateException desligando) {
                encerrarTodos(); // a JVM já está desligando: encerra já
            }
        }
    }

    void remover(TunelDeTransporte tunel) {
        abertos.remove(tunel);
    }

    /** Quantos túneis estão abertos. */
    public int quantos() {
        return abertos.size();
    }

    /** Encerra todos (o gancho, e o que se faz ao fechar o app). */
    public void encerrarTodos() {
        List<TunelDeTransporte> lista = List.copyOf(abertos);
        for (TunelDeTransporte t : lista) {
            try {
                t.close();
            } catch (RuntimeException e) {
                LOG.debug("Não consegui encerrar um túnel no desligamento: {}", e.toString());
            }
        }
    }
}
