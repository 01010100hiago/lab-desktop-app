package br.com.labdesktop.api;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * O prazo de cada {@link Rota}. {@link #padrao()} é o do contrato; {@link #limitadoA(Duration)} existe para os
 * testes não esperarem 12 minutos para provar que o prazo funciona.
 */
public final class TimeoutsPorRota {

    private final Map<Rota, Duration> prazos;

    private TimeoutsPorRota(Map<Rota, Duration> prazos) {
        this.prazos = prazos;
    }

    /** Os prazos do CONTRATO-API seção 2. */
    public static TimeoutsPorRota padrao() {
        Map<Rota, Duration> m = new EnumMap<>(Rota.class);
        for (Rota r : Rota.values()) {
            m.put(r, r.prazoPadrao());
        }
        return new TimeoutsPorRota(m);
    }

    /** Os prazos do contrato, mas nenhum maior que {@code teto} (para teste). */
    public static TimeoutsPorRota limitadoA(Duration teto) {
        Map<Rota, Duration> m = new EnumMap<>(Rota.class);
        for (Rota r : Rota.values()) {
            m.put(r, r.prazoPadrao().compareTo(teto) > 0 ? teto : r.prazoPadrao());
        }
        return new TimeoutsPorRota(m);
    }

    public Duration de(Rota rota) {
        return prazos.get(rota);
    }
}
