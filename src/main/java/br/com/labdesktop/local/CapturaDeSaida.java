package br.com.labdesktop.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Lê um fluxo de um processo até o fim guardando no máximo {@link #TETO} bytes (um programa que imprime sem parar não estoura a memória). */
final class CapturaDeSaida {

    static final int TETO = 64 * 1024;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean truncado;
    private final Thread leitora;

    CapturaDeSaida(InputStream fluxo, String nome) {
        this.leitora = Thread.ofVirtual().name(nome).start(() -> {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = fluxo.read(b)) != -1) {
                    guardar(b, n);
                }
            } catch (IOException e) {
                // o processo foi morto: o que chegou até aqui basta
            }
        });
    }

    private synchronized void guardar(byte[] b, int n) {
        int cabe = Math.max(0, Math.min(n, TETO - buffer.size()));
        if (cabe > 0) {
            buffer.write(b, 0, cabe);
        }
        if (cabe < n) {
            truncado = true;
        }
    }

    /** Espera o fim do fluxo (o processo já terminou ou foi morto). */
    void esperar() throws InterruptedException {
        leitora.join(3000);
    }

    synchronized byte[] bytes() {
        return buffer.toByteArray();
    }

    synchronized boolean truncado() {
        return truncado;
    }
}
