package br.com.labdesktop.cofre;

import java.time.Duration;
import java.time.Instant;

/**
 * Atraso crescente depois de senha errada (ARQUITETURA 5.4): o diálogo de senha-mestra consulta
 * {@link #restante(Instant)} e desabilita o botão. Vale por execução do app (em memória): contra quem tem o
 * ARQUIVO do cofre o que protege é o custo do Argon2id, não este atraso; ele só desencoraja tentativa à mão
 * na tela. O app nunca apaga o cofre sozinho.
 *
 * <p>Sem relógio global: quem chama passa o {@code agora}, e o teste controla o tempo.
 */
public final class AtrasoDeSenha {

    /** Teto do atraso. */
    public static final Duration TETO = Duration.ofSeconds(30);
    /** As duas primeiras falhas não esperam (dedo errado acontece). */
    static final int FALHAS_SEM_ATRASO = 2;

    private int falhasSeguidas;
    private Instant liberadoEm = Instant.MIN;

    /** Atraso depois de {@code falhas} falhas seguidas: 0, 0, 1 s, 2 s, 4 s, 8 s, 16 s, 30 s... */
    public static Duration atrasoApos(int falhas) {
        if (falhas <= FALHAS_SEM_ATRASO) {
            return Duration.ZERO;
        }
        long segundos = 1L << Math.min(falhas - FALHAS_SEM_ATRASO - 1, 10);
        Duration d = Duration.ofSeconds(segundos);
        return d.compareTo(TETO) > 0 ? TETO : d;
    }

    public synchronized void registrarFalha(Instant agora) {
        falhasSeguidas++;
        liberadoEm = agora.plus(atrasoApos(falhasSeguidas));
    }

    public synchronized void registrarSucesso() {
        falhasSeguidas = 0;
        liberadoEm = Instant.MIN;
    }

    /** Quanto falta para poder tentar de novo ({@link Duration#ZERO} se já pode). */
    public synchronized Duration restante(Instant agora) {
        if (!agora.isBefore(liberadoEm)) {
            return Duration.ZERO;
        }
        return Duration.between(agora, liberadoEm);
    }

    public synchronized int falhasSeguidas() {
        return falhasSeguidas;
    }
}
