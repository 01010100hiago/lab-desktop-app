package br.com.labdesktop.local;

import java.time.Duration;

/**
 * Tempos da {@link ConexaoLocal}. Os testes usam {@link #rapida()}.
 *
 * @param intervaloSemAgente      de quanto em quanto tempo insistir no teste do agente quando o computador está aí e o agente não responde
 * @param esperaAposAcordar       folga depois de o computador acordar antes de conferir o agente
 * @param falhasAteOIntervaloLento quantas respostas "agente fora" SEGUIDAS levam ao intervalo lento (12: um minuto a 5 s)
 * @param intervaloLento          de quanto em quanto tempo insistir depois disso (30 s): um Docker parado ou ausente não precisa de teste a cada 5 s para sempre
 */
public record ConfigLocal(Duration intervaloSemAgente, Duration esperaAposAcordar, int falhasAteOIntervaloLento, Duration intervaloLento) {

    public static final int FALHAS_ATE_O_INTERVALO_LENTO = 12;
    public static final Duration INTERVALO_LENTO = Duration.ofSeconds(30);

    public ConfigLocal {
        if (falhasAteOIntervaloLento < 1) {
            throw new IllegalArgumentException("falhasAteOIntervaloLento < 1");
        }
    }

    /** Com o intervalo lento do padrão (12 respostas "fora" seguidas, depois 30 s). */
    public ConfigLocal(Duration intervaloSemAgente, Duration esperaAposAcordar) {
        this(intervaloSemAgente, esperaAposAcordar, FALHAS_ATE_O_INTERVALO_LENTO, INTERVALO_LENTO);
    }

    public static ConfigLocal padrao() {
        return new ConfigLocal(Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    public static ConfigLocal rapida() {
        return new ConfigLocal(Duration.ofMillis(200), Duration.ofMillis(100));
    }

    /** Muda quando começa o intervalo lento e quanto ele vale (os testes usam valores pequenos). */
    public ConfigLocal comIntervaloLento(int falhasSeguidas, Duration intervalo) {
        return new ConfigLocal(intervaloSemAgente, esperaAposAcordar, falhasSeguidas, intervalo);
    }
}
