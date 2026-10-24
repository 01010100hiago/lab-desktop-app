package br.com.labdesktop.teste;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Um proxy TCP de teste que sabe fazer BURACO NEGRO: as conexões que estavam abertas continuam abertas, mas
 * param de entregar bytes nos dois sentidos (como um Wi-Fi que cai sem fechar o socket). Conexões novas passam
 * normalmente. É o jeito de provar que o heartbeat percebe a queda e que o app reconecta.
 */
public final class ProxyTcp implements AutoCloseable {

    private final ServerSocket escuta;
    private final int portaDestino;
    private final List<Ligacao> ligacoes = new CopyOnWriteArrayList<>();
    private volatile boolean fechado;
    public final AtomicInteger conexoesAceitas = new AtomicInteger();

    public ProxyTcp(int portaDestino) throws IOException {
        this.portaDestino = portaDestino;
        this.escuta = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::aceitar, "proxy-tcp");
        t.setDaemon(true);
        t.start();
    }

    public int porta() {
        return escuta.getLocalPort();
    }

    /** As conexões já abertas viram buraco negro para sempre; as novas passam. */
    public void abrirBuracoNegro() {
        ligacoes.forEach(l -> l.buracoNegro = true);
    }

    /** Fecha as conexões abertas de verdade (RST/EOF), como um servidor que reiniciou. */
    public void derrubarConexoes() {
        ligacoes.forEach(Ligacao::fechar);
    }

    private void aceitar() {
        while (!fechado) {
            try {
                Socket cliente = escuta.accept();
                conexoesAceitas.incrementAndGet();
                Socket destino = new Socket(InetAddress.getLoopbackAddress(), portaDestino);
                Ligacao l = new Ligacao(cliente, destino);
                ligacoes.add(l);
                bombear(l, cliente.getInputStream(), destino.getOutputStream());
                bombear(l, destino.getInputStream(), cliente.getOutputStream());
            } catch (IOException e) {
                if (fechado) {
                    return;
                }
            }
        }
    }

    private void bombear(Ligacao l, InputStream de, OutputStream para) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = de.read(b)) != -1) {
                    if (l.buracoNegro) {
                        continue; // engole: nem entrega nem fecha
                    }
                    para.write(b, 0, n);
                    para.flush();
                }
            } catch (IOException e) {
                // um dos lados fechou
            } finally {
                if (!l.buracoNegro) {
                    l.fechar();
                }
            }
        }, "proxy-tcp-bomba");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() throws IOException {
        fechado = true;
        escuta.close();
        ligacoes.forEach(Ligacao::fechar);
    }

    private static final class Ligacao {
        final Socket a;
        final Socket b;
        volatile boolean buracoNegro;

        Ligacao(Socket a, Socket b) {
            this.a = a;
            this.b = b;
        }

        void fechar() {
            try {
                a.close();
            } catch (IOException ignorado) {
                // já fechado
            }
            try {
                b.close();
            } catch (IOException ignorado) {
                // já fechado
            }
        }
    }
}
