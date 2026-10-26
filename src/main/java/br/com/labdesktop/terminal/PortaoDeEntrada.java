package br.com.labdesktop.terminal;

import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Agendador.Cancelavel;
import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Impede que o que o usuário digita no terminal e o que a barra lateral envia pelo REST se misturem (ARQUITETURA 7.5). O PTY é um canal
 * SSH e a barra fala HTTP, então nada no protocolo os ordena: sem este portão, uma mensagem da barra poderia cair no meio de uma
 * frase que o usuário está digitando.
 *
 * <ul>
 *   <li>Se o usuário digitou há menos de 800 ms, o envio ESPERA o silêncio (as teclas do usuário têm preferência).</li>
 *   <li>Os envios da barra (clique numa opção, envio de mensagem) rodam um de cada vez, numa fila de 1: um em andamento e, no
 *       máximo, um esperando. Um terceiro é recusado na hora, em vez de empilhar cliques repetidos.</li>
 *   <li>Nada é enviado enquanto o terminal está em {@code REANEXANDO}: o envio falha na hora, e o que estava na fila falha também.</li>
 * </ul>
 */
public final class PortaoDeEntrada {

    /** Quanto silêncio do teclado o envio da barra espera. */
    public static final Duration SILENCIO = Duration.ofMillis(800);
    /** Um envio em andamento mais um esperando. */
    public static final int CAPACIDADE = 2;

    private final Agendador relogio;
    private final Supplier<EstadoDoTerminal> estadoDoTerminal;
    private final Deque<Item<?>> fila = new ArrayDeque<>();
    private Instant ultimaTecla;
    private boolean executando;
    private Cancelavel alarme;

    private record Item<T>(Callable<T> envio, CompletableFuture<T> resultado) { }

    /** O envio foi recusado porque já há um em andamento e outro esperando. */
    public static final class OcupadoException extends IOException {
        private static final long serialVersionUID = 1L;

        OcupadoException() {
            super("Já há envios esperando a sua vez. Espere um instante.");
        }
    }

    /** O envio foi recusado porque o terminal está reconectando. */
    public static final class TerminalReanexandoException extends IOException {
        private static final long serialVersionUID = 1L;

        TerminalReanexandoException() {
            super("O terminal está reconectando; nada foi enviado. Tente de novo quando ele voltar.");
        }
    }

    public PortaoDeEntrada(Agendador relogio, Supplier<EstadoDoTerminal> estadoDoTerminal) {
        this.relogio = relogio;
        this.estadoDoTerminal = estadoDoTerminal;
    }

    /** O usuário apertou uma tecla (ou colou) no terminal. Qualquer thread. */
    public void aoDigitar() {
        synchronized (this) {
            ultimaTecla = relogio.agora();
        }
    }

    /** O usuário digitou há menos de {@link #SILENCIO}? */
    public synchronized boolean donoDigitandoAgora() {
        return ultimaTecla != null && Duration.between(ultimaTecla, relogio.agora()).compareTo(SILENCIO) < 0;
    }

    /**
     * Enfileira um envio. O futuro completa com o resultado do {@code envio}, ou falha com {@link OcupadoException} (fila cheia) ou
     * {@link TerminalReanexandoException}. O {@code envio} roda numa thread virtual, nunca na EDT.
     */
    public <T> CompletableFuture<T> enviar(Callable<T> envio) {
        CompletableFuture<T> resultado = new CompletableFuture<>();
        synchronized (this) {
            if (estadoDoTerminal.get().reanexando()) {
                resultado.completeExceptionally(new TerminalReanexandoException());
                return resultado;
            }
            if (fila.size() + (executando ? 1 : 0) >= CAPACIDADE) {
                resultado.completeExceptionally(new OcupadoException());
                return resultado;
            }
            fila.add(new Item<>(envio, resultado));
        }
        avancar();
        return resultado;
    }

    /** O estado do terminal mudou: se entrou em {@code REANEXANDO}, o que esperava na fila é recusado agora. */
    public void estadoMudou() {
        avancar();
    }

    /** Quantos envios há entre o que roda e o que espera. */
    public synchronized int pendentes() {
        return fila.size() + (executando ? 1 : 0);
    }

    private void avancar() {
        Item<?> item;
        synchronized (this) {
            if (executando || fila.isEmpty()) {
                return;
            }
            if (estadoDoTerminal.get().reanexando()) {
                recusarTudo();
                return;
            }
            if (ultimaTecla != null) {
                Duration faltam = SILENCIO.minus(Duration.between(ultimaTecla, relogio.agora()));
                if (faltam.compareTo(Duration.ZERO) > 0) {
                    if (alarme == null) {
                        alarme = relogio.agendar(faltam, () -> {
                            synchronized (PortaoDeEntrada.this) {
                                alarme = null;
                            }
                            avancar();
                        });
                    }
                    return;
                }
            }
            item = fila.poll();
            executando = true;
        }
        executar(item);
    }

    private void recusarTudo() {
        Item<?> i;
        while ((i = fila.poll()) != null) {
            i.resultado().completeExceptionally(new TerminalReanexandoException());
        }
    }

    private <T> void executar(Item<T> item) {
        Fundo.ioVirtual().execute(() -> {
            try {
                if (estadoDoTerminal.get().reanexando()) {
                    item.resultado().completeExceptionally(new TerminalReanexandoException());
                } else {
                    item.resultado().complete(item.envio().call());
                }
            } catch (Exception e) {
                item.resultado().completeExceptionally(e);
            } finally {
                synchronized (PortaoDeEntrada.this) {
                    executando = false;
                }
                avancar();
            }
        });
    }
}
