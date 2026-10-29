package br.com.labdesktop.transporte;

import java.util.Optional;

/**
 * Para onde o cliente SSH deve conectar AGORA (host e porta) e o que fechar quando terminar. No modo direto é o endereço e a porta do servidor e fechar não faz nada; nos modos sem
 * porta aberta é {@code 127.0.0.1} e a porta aleatória de um túnel externo, e fechar encerra o programa do túnel.
 */
public interface DestinoSsh extends AutoCloseable {

    String host();

    int porta();

    /** O programa do túnel morreu sozinho? Vazio no direto, ou se ele está de pé. Confere na hora (não depende de um aviso que pode ainda não ter chegado). */
    default Optional<FalhaDoTunel> falhaDoTunel() {
        return Optional.empty();
    }

    /** Encerra o túnel, se houver. Idempotente e sem exceção. */
    @Override
    void close();

    /** O destino do modo direto. */
    static DestinoSsh direto(String host, int porta) {
        return new DestinoSsh() {
            @Override
            public String host() {
                return host;
            }

            @Override
            public int porta() {
                return porta;
            }

            @Override
            public void close() { }

            @Override
            public String toString() {
                return "direto " + host + ":" + porta;
            }
        };
    }
}
