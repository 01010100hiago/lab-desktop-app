package br.com.labdesktop.plataforma;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link ConsultaDeSistema} de verdade: um {@link ProcessBuilder} com a lista de argumentos e um prazo que vale DE VERDADE. A leitura da saída roda numa thread à parte e a espera
 * por ela tem o prazo: um {@code gsettings} ou {@code reg} travado (sem escrever nem sair) não segura quem pergunta (a partida do app lê o tema do sistema), o processo é morto e a
 * resposta é "falhou". Tolerante a qualquer falha.
 */
public final class ConsultaPorProcesso implements ConsultaDeSistema {

    private static final int TETO = 8 * 1024;

    @Override
    public Resposta perguntar(List<String> comando, Duration prazo) {
        Process p = null;
        try {
            long fim = System.nanoTime() + prazo.toNanos();
            p = new ProcessBuilder(comando).redirectErrorStream(true).start();
            p.getOutputStream().close();
            CompletableFuture<byte[]> leitura = ler(p.getInputStream());
            byte[] saida = leitura.get(Math.max(1, restante(fim)), TimeUnit.NANOSECONDS);
            if (!p.waitFor(Math.max(1, restante(fim)), TimeUnit.NANOSECONDS)) {
                return Resposta.falhou(); // o finally mata
            }
            return new Resposta(p.exitValue(), new String(saida, StandardCharsets.UTF_8));
        } catch (TimeoutException | ExecutionException | IOException | RuntimeException e) {
            return Resposta.falhou(); // sem o programa (outro sistema), sem permissão, travou
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resposta.falhou();
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static long restante(long fim) {
        return fim - System.nanoTime();
    }

    /** A saída que importa é curta; o resto se descarta junto com o processo. A thread é daemon: se o processo não largar o cano, ela não segura o app. */
    private static CompletableFuture<byte[]> ler(InputStream entrada) {
        CompletableFuture<byte[]> futuro = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                futuro.complete(entrada.readNBytes(TETO));
            } catch (IOException | RuntimeException e) {
                futuro.completeExceptionally(e);
            }
        }, "consulta-de-sistema");
        t.setDaemon(true);
        t.start();
        return futuro;
    }
}
