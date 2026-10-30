package br.com.labdesktop.ssh;

import java.time.Duration;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Espera entre tentativas de reconexão: 1, 2, 4, 8, 15, 30, 60 s, com jitter de mais ou menos 20 %
 * (ARQUITETURA 6.3). O jitter evita que dois servidores caídos juntos voltem a bater no mesmo instante.
 * Depois do último degrau, fica em 60 s. {@link #zerar()} volta ao primeiro (chamado depois de 60 s estável).
 *
 * <p>Não é thread-safe de propósito: só o ator da {@code ConexaoSsh} a usa.
 */
public final class PoliticaDeBackoff {

    /** Os degraus em unidades de {@code escala} (1 s em produção). */
    private static final List<Integer> DEGRAUS = List.of(1, 2, 4, 8, 15, 30, 60);
    private static final double JITTER = 0.20;

    private final Duration escala;
    private final DoubleSupplier sorteio;
    private int chamadas;

    /**
     * @param escala  duração de uma unidade de degrau (1 s em produção, milissegundos nos testes)
     * @param sorteio devolve um número em [0, 1); injetável para o teste ser determinístico
     */
    public PoliticaDeBackoff(Duration escala, DoubleSupplier sorteio) {
        this.escala = escala;
        this.sorteio = sorteio;
    }

    public static PoliticaDeBackoff padrao() {
        return new PoliticaDeBackoff(Duration.ofSeconds(1), Math::random);
    }

    /** A espera da próxima tentativa; avança o degrau. */
    public Duration proximo() {
        int unidades = DEGRAUS.get(Math.min(chamadas, DEGRAUS.size() - 1));
        chamadas++;
        double fator = 1.0 + JITTER * (2.0 * sorteio.getAsDouble() - 1.0);
        long nanos = Math.round(escala.toNanos() * unidades * fator);
        return Duration.ofNanos(Math.max(1, nanos));
    }

    /**
     * A espera do ritmo LENTO (depois de muitas falhas seguidas): {@code base} com o mesmo jitter de 20 %, para dois servidores caídos juntos não voltarem a bater no mesmo instante. Não avança os
     * degraus: o ritmo normal recomeça de onde estava quando o usuário manda tentar agora.
     */
    public Duration lenta(Duration base) {
        double fator = 1.0 + JITTER * (2.0 * sorteio.getAsDouble() - 1.0);
        return Duration.ofNanos(Math.max(1, Math.round(base.toNanos() * fator)));
    }

    /** Quantas esperas já foram devolvidas desde o último {@link #zerar()}. */
    public int tentativas() {
        return chamadas;
    }

    /** Volta ao primeiro degrau. */
    public void zerar() {
        chamadas = 0;
    }
}
