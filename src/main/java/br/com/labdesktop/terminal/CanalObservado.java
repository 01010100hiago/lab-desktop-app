package br.com.labdesktop.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Um {@link CanalDeTerminal} que guarda os últimos bytes que o terminal LEU (a "cauda"), para o {@link ClassificadorDeSaida} julgar
 * por que o canal fechou: quem lê o canal é o emulador, e a mensagem de erro do Docker chega pelo mesmo caminho que a tela do
 * Claude. Também sabe se o leitor já viu o fim (EOF), para a sessão não julgar antes de a última mensagem ser lida.
 */
final class CanalObservado implements CanalDeTerminal {

    private final CanalDeTerminal canal;
    private final InputStream saida;
    private final byte[] anel = new byte[ClassificadorDeSaida.BYTES_EXAMINADOS];
    private long total;
    private final CountDownLatch fimLido = new CountDownLatch(1);

    CanalObservado(CanalDeTerminal canal) {
        this.canal = canal;
        this.saida = new Espiao(canal.saida());
    }

    @Override
    public InputStream saida() {
        return saida;
    }

    @Override
    public OutputStream entrada() {
        return canal.entrada();
    }

    @Override
    public void redimensionar(TamanhoDeTerminal tamanho) throws IOException {
        canal.redimensionar(tamanho);
    }

    @Override
    public boolean aberto() {
        return canal.aberto();
    }

    @Override
    public Integer aguardarFim() throws InterruptedException {
        return canal.aguardarFim();
    }

    @Override
    public Integer statusDeSaida() {
        return canal.statusDeSaida();
    }

    @Override
    public void fechar() {
        canal.fechar();
    }

    /** Espera (até {@code prazoMs}) o leitor chegar ao fim da saída. {@code false} se ninguém leu até o fim a tempo. */
    boolean aguardarLeituraDoFim(long prazoMs) throws InterruptedException {
        return fimLido.await(prazoMs, TimeUnit.MILLISECONDS);
    }

    /** Os últimos bytes lidos (no máximo 2 KB), em ordem. */
    synchronized byte[] cauda() {
        int n = (int) Math.min(total, anel.length);
        byte[] r = new byte[n];
        int fim = (int) (total % anel.length);
        for (int i = 0; i < n; i++) {
            r[i] = anel[Math.floorMod(fim - n + i, anel.length)];
        }
        return r;
    }

    private synchronized void guardar(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            anel[(int) (total % anel.length)] = b[off + i];
            total++;
        }
    }

    private final class Espiao extends InputStream {
        private final InputStream origem;

        Espiao(InputStream origem) {
            this.origem = origem;
        }

        @Override
        public int read() throws IOException {
            byte[] um = new byte[1];
            int n = read(um, 0, 1);
            return n < 0 ? -1 : um[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n;
            try {
                n = origem.read(b, off, len);
            } catch (IOException e) {
                fimLido.countDown(); // canal fechado por baixo da leitura: o fim também foi "visto"
                throw e;
            }
            if (n < 0) {
                fimLido.countDown();
            } else if (n > 0) {
                guardar(b, off, n);
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return origem.available();
        }

        @Override
        public void close() throws IOException {
            origem.close();
        }
    }
}
