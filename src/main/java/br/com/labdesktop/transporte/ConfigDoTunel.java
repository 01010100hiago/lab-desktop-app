package br.com.labdesktop.transporte;

import java.time.Duration;

/**
 * Os tempos do túnel externo.
 *
 * @param prazoParaSubir     quanto esperar a porta local aceitar conexões (a sessão do SSM leva alguns segundos para abrir)
 * @param passoDaEspera      de quanto em quanto tempo conferir a porta e o processo
 * @param tentativasDePorta  quantas portas diferentes tentar se a sorteada for tomada por outro programa antes de o túnel subir
 */
public record ConfigDoTunel(Duration prazoParaSubir, Duration passoDaEspera, int tentativasDePorta) {

    public ConfigDoTunel {
        if (prazoParaSubir.isNegative() || prazoParaSubir.isZero() || passoDaEspera.isNegative() || passoDaEspera.isZero() || tentativasDePorta < 1) {
            throw new IllegalArgumentException("tempos do túnel inválidos");
        }
    }

    /** Produção: 30 s para subir, olha a cada 50 ms, até 3 portas. */
    public static ConfigDoTunel padrao() {
        return new ConfigDoTunel(Duration.ofSeconds(30), Duration.ofMillis(50), 3);
    }

    /** Testes. */
    public static ConfigDoTunel rapida() {
        return new ConfigDoTunel(Duration.ofSeconds(4), Duration.ofMillis(10), 3);
    }

    public ConfigDoTunel comPrazo(Duration novo) {
        return new ConfigDoTunel(novo, passoDaEspera, tentativasDePorta);
    }
}
