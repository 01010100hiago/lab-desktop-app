package br.com.labdesktop.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Um transporte de terminal de mentira: o teste decide o que cada abertura devolve e quando o canal cai. */
final class TransporteFalso implements TransporteDeTerminal {

    /** Um canal de mentira: o teste escreve a "tela" e fecha do lado remoto. */
    static final class CanalFalso implements CanalDeTerminal {
        private final PipedOutputStream remoto = new PipedOutputStream();
        private final PipedInputStream saida;
        final java.io.ByteArrayOutputStream teclado = new java.io.ByteArrayOutputStream();
        final List<TamanhoDeTerminal> tamanhos = new CopyOnWriteArrayList<>();
        private final CountDownLatch fim = new CountDownLatch(1);
        private volatile Integer status;
        private volatile boolean aberto = true;
        final AtomicInteger fechamentosLocais = new AtomicInteger();

        CanalFalso() {
            try {
                this.saida = new PipedInputStream(remoto, 64 * 1024);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** O servidor imprime. */
        void imprimir(String texto) {
            try {
                remoto.write(texto.getBytes(StandardCharsets.UTF_8));
                remoto.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** O lado remoto fecha o canal com o status (ou sem status, como numa queda de rede). */
        void fecharRemoto(Integer statusDeSaida) {
            this.status = statusDeSaida;
            this.aberto = false;
            try {
                remoto.close();
            } catch (IOException e) {
                // já fechado
            }
            fim.countDown();
        }

        @Override
        public InputStream saida() {
            return saida;
        }

        @Override
        public OutputStream entrada() {
            return teclado;
        }

        @Override
        public void redimensionar(TamanhoDeTerminal tamanho) {
            tamanhos.add(tamanho);
        }

        @Override
        public boolean aberto() {
            return aberto;
        }

        @Override
        public Integer aguardarFim() throws InterruptedException {
            fim.await();
            return status;
        }

        @Override
        public Integer statusDeSaida() {
            return status;
        }

        @Override
        public void fechar() {
            fechamentosLocais.incrementAndGet();
            fecharRemoto(null);
        }
    }

    /** O que uma abertura faz: devolve o canal ou lança. */
    @FunctionalInterface
    interface Abertura {
        CanalDeTerminal abrir(int numero) throws IOException;
    }

    final AtomicInteger aberturas = new AtomicInteger();
    final List<ComandoDeTerminal> comandos = new CopyOnWriteArrayList<>();
    final List<TamanhoDeTerminal> tamanhos = new CopyOnWriteArrayList<>();
    final List<CanalFalso> canais = new CopyOnWriteArrayList<>();
    final AtomicInteger verificacoes = new AtomicInteger();
    private final List<Consumer<Disponibilidade>> ouvintes = new CopyOnWriteArrayList<>();
    private volatile Abertura abertura = n -> {
        CanalFalso c = new CanalFalso();
        return c;
    };
    private volatile CountDownLatch seguraAbertura;

    TransporteFalso comAbertura(Abertura a) {
        this.abertura = a;
        return this;
    }

    /** Faz as próximas aberturas esperarem até {@code solta.countDown()}. */
    CountDownLatch segurarAberturas() {
        seguraAbertura = new CountDownLatch(1);
        return seguraAbertura;
    }

    /** Abre sempre com sucesso, guardando cada canal em {@link #canais}. */
    TransporteFalso sempreAbre() {
        return comAbertura(n -> {
            CanalFalso c = new CanalFalso();
            canais.add(c);
            return c;
        });
    }

    /** Um canal novo, guardado em {@link #canais}. */
    CanalFalso abrirNovo() {
        CanalFalso c = new CanalFalso();
        canais.add(c);
        return c;
    }

    CanalFalso ultimoCanal() {
        return canais.get(canais.size() - 1);
    }

    @Override
    public CanalDeTerminal abrir(ComandoDeTerminal comando, TamanhoDeTerminal tamanho) throws IOException {
        int n = aberturas.incrementAndGet();
        comandos.add(comando);
        tamanhos.add(tamanho);
        CountDownLatch espera = seguraAbertura;
        if (espera != null) {
            try {
                espera.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrompido");
            }
        }
        return abertura.abrir(n);
    }

    @Override
    public Runnable observarDisponibilidade(Consumer<Disponibilidade> ouvinte) {
        ouvintes.add(ouvinte);
        return () -> ouvintes.remove(ouvinte);
    }

    @Override
    public void sugerirVerificacao() {
        verificacoes.incrementAndGet();
    }

    void emitir(Disponibilidade d) {
        new ArrayList<>(ouvintes).forEach(o -> o.accept(d));
    }
}
