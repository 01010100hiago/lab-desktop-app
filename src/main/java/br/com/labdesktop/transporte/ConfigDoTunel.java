package br.com.labdesktop.transporte;

import java.time.Duration;

/**
 * Os tempos do túnel externo.
 *
 * @param prazoParaSubir     quanto esperar a porta local aceitar conexões (a sessão do SSM leva alguns segundos para abrir)
 * @param passoDaEspera      de quanto em quanto tempo conferir a porta e o processo
 * @param tentativasDePorta  quantas portas diferentes tentar se a sorteada for tomada por outro programa antes de o túnel subir
 * @param esperaPelaCausa    quanto esperar, depois de PERDER a sessão SSH que andava pelo túnel, o programa dele ser dado como morto e a saída ser lida (o sistema fecha os sockets um
 *                           instante antes): é o que deixa a tela dizer "seu login da AWS expirou" em vez de só "conexão caiu"
 */
public record ConfigDoTunel(Duration prazoParaSubir, Duration passoDaEspera, int tentativasDePorta, Duration esperaPelaCausa) {

    public ConfigDoTunel {
        if (prazoParaSubir.isNegative() || prazoParaSubir.isZero() || passoDaEspera.isNegative() || passoDaEspera.isZero() || tentativasDePorta < 1 || esperaPelaCausa.isNegative()) {
            throw new IllegalArgumentException("tempos do túnel inválidos");
        }
    }

    /** Produção: 30 s para subir, olha a cada 50 ms, até 3 portas, 1 s para a causa de uma queda. */
    public static ConfigDoTunel padrao() {
        return new ConfigDoTunel(Duration.ofSeconds(30), Duration.ofMillis(50), 3, Duration.ofSeconds(1));
    }

    /** Testes. */
    public static ConfigDoTunel rapida() {
        return new ConfigDoTunel(Duration.ofSeconds(4), Duration.ofMillis(10), 3, Duration.ofMillis(300));
    }

    /** A mesma configuração com outra espera pela causa de uma queda (o teste que prova a causa dá folga: a espera acaba assim que o programa sai, então folga não custa tempo). */
    public ConfigDoTunel comEsperaPelaCausa(Duration nova) {
        return new ConfigDoTunel(prazoParaSubir, passoDaEspera, tentativasDePorta, nova);
    }

    public ConfigDoTunel comPrazo(Duration novo) {
        return new ConfigDoTunel(novo, passoDaEspera, tentativasDePorta, esperaPelaCausa);
    }
}
