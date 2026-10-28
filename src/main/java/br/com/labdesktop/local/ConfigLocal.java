package br.com.labdesktop.local;

import java.time.Duration;

/**
 * Tempos da {@link ConexaoLocal}. Os testes usam {@link #rapida()}.
 *
 * @param intervaloSemAgente de quanto em quanto tempo insistir no teste do agente quando o computador está aí e o agente não responde
 * @param esperaAposAcordar  folga depois de o computador acordar antes de conferir o agente
 */
public record ConfigLocal(Duration intervaloSemAgente, Duration esperaAposAcordar) {

    public static ConfigLocal padrao() {
        return new ConfigLocal(Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    public static ConfigLocal rapida() {
        return new ConfigLocal(Duration.ofMillis(200), Duration.ofMillis(100));
    }
}
