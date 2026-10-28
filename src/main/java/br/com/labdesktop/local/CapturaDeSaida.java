package br.com.labdesktop.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lê um fluxo de um processo até o fim guardando no máximo {@link #TETO} bytes (um programa que imprime sem parar não estoura a memória): o
 * COMEÇO e o FIM. O fim é o que importa num erro (o {@code docker build} diz o motivo da falha na última linha), então o que passa do teto
 * fica só com os primeiros {@link #CABECA} bytes e os últimos {@link #CAUDA}, com uma marca no meio.
 */
final class CapturaDeSaida {

    static final int TETO = 64 * 1024;
    static final int CABECA = 16 * 1024;
    static final String MARCA = "\n[... saída cortada ...]\n";
    static final int CAUDA = TETO - CABECA - MARCA.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

    private final ByteArrayOutputStream cabeca = new ByteArrayOutputStream();
    private final byte[] cauda = new byte[CAUDA];
    private int caudaTotal; // quantos bytes já passaram pela cauda
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
        int paraACabeca = Math.max(0, Math.min(n, CABECA - cabeca.size()));
        if (paraACabeca > 0) {
            cabeca.write(b, 0, paraACabeca);
        }
        for (int i = paraACabeca; i < n; i++) { // o resto entra no anel da cauda
            cauda[caudaTotal % CAUDA] = b[i];
            caudaTotal++;
        }
        if (caudaTotal > CAUDA) {
            truncado = true;
        }
    }

    /** Espera o fim do fluxo (o processo já terminou ou foi morto). */
    void esperar() throws InterruptedException {
        leitora.join(3000);
    }

    synchronized byte[] bytes() {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        r.writeBytes(cabeca.toByteArray());
        if (caudaTotal <= CAUDA) {
            r.write(cauda, 0, caudaTotal);
        } else {
            r.writeBytes(MARCA.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int inicio = caudaTotal % CAUDA; // o byte mais antigo do anel
            r.write(cauda, inicio, CAUDA - inicio);
            r.write(cauda, 0, inicio);
        }
        return r.toByteArray();
    }

    synchronized boolean truncado() {
        return truncado;
    }
}
