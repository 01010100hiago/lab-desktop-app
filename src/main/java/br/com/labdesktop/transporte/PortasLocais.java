package br.com.labdesktop.transporte;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * As portas de 127.0.0.1 do túnel: escolher uma LIVRE e saber se alguém já escuta nela. Atrás de uma interface para os testes simularem a corrida (outro programa
 * pega a porta entre a escolha e o uso).
 */
public interface PortasLocais {

    /**
     * Uma porta aleatória livre de 127.0.0.1. É reservada e LIBERADA na hora: outro programa ainda pode pegá-la antes de o túnel subir, por isso quem usa confere se o programa
     * subiu e, se a porta foi tomada, tenta de novo com outra.
     */
    int reservar() throws IOException;

    /** Alguém escuta nesta porta de 127.0.0.1 agora? Confere SEM conectar (conectar abriria uma sessão do SSM, ou o login da Cloudflare, à toa). */
    boolean alguemEscuta(int porta);

    /** O endereço de TODAS as portas do túnel: só o laço de volta, nunca a rede (127.0.0.1). */
    InetAddress LACO = laco();

    private static InetAddress laco() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A porta aleatória aberta de verdade, só em 127.0.0.1 (quem a pede lê o número e a fecha). Separada para o teste provar que a reserva nunca é na rede. */
    static ServerSocket abrirParaReservar() throws IOException {
        return new ServerSocket(0, 1, LACO);
    }

    /** As portas do sistema. */
    static PortasLocais doSistema() {
        return new PortasLocais() {
            private final InetAddress laco = LACO;

            @Override
            public int reservar() throws IOException {
                try (ServerSocket s = abrirParaReservar()) {
                    return s.getLocalPort();
                }
            }

            @Override
            public boolean alguemEscuta(int porta) {
                try (ServerSocket s = new ServerSocket()) {
                    s.setReuseAddress(false);
                    s.bind(new InetSocketAddress(laco, porta), 1);
                    return false; // consegui ocupar: ninguém escuta
                } catch (BindException ocupada) {
                    return true;
                } catch (IOException outro) {
                    return false;
                }
            }
        };
    }
}
