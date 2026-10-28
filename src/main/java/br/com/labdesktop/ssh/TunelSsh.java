package br.com.labdesktop.ssh;

import java.io.IOException;
import java.net.InetSocketAddress;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O forward local do app para o agente: {@code 127.0.0.1:<porta efêmera>} no Mac chega em
 * {@code 127.0.0.1:<portaAgente>} DENTRO do servidor (ARQUITETURA 6.4). A porta local é só em loopback e
 * muda a cada reconexão; o {@code ClienteApi} é por geração de túnel.
 */
public final class TunelSsh implements AutoCloseable, PontaDoAgente {

    private static final Logger LOG = LoggerFactory.getLogger(TunelSsh.class);

    private final ExplicitPortForwardingTracker rastreador;
    private final long geracao;

    TunelSsh(ExplicitPortForwardingTracker rastreador, long geracao) {
        this.rastreador = rastreador;
        this.geracao = geracao;
    }

    /** A porta local efêmera (o {@code ClienteApi} monta {@code http://127.0.0.1:<porta>}). */
    @Override
    public int portaLocal() {
        return rastreador.getBoundAddress().getPort();
    }

    /** O endereço em que o app escuta. Sempre loopback (um teste confere). */
    public InetSocketAddress enderecoLocal() {
        return rastreador.getBoundAddress().toInetSocketAddress();
    }

    /** Cresce a cada túnel novo da mesma {@link ConexaoSsh}. */
    @Override
    public long geracao() {
        return geracao;
    }

    public boolean estaAberto() {
        return rastreador.isOpen();
    }

    @Override
    public void close() {
        try {
            rastreador.close();
        } catch (IOException e) {
            LOG.debug("Falha ao fechar o túnel (já estava caído?): {}", e.toString());
        }
    }

    @Override
    public String toString() {
        return "TunelSsh[127.0.0.1:" + portaLocal() + ", geracao " + geracao + "]";
    }
}
