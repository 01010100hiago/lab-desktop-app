package br.com.labdesktop.processos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Processos REAIS para os testes de Processos: abre um {@code sleep} órfão (reparentado ao sistema, para o teste não precisar recolhê-lo e ele nunca
 * ficar como zumbi) e o encerra no fim. Só mexe nos processos que o próprio teste abriu.
 */
final class ProcessosDeVerdade implements AutoCloseable {

    private final List<Integer> abertos = new ArrayList<>();

    /** Um {@code sleep} que atende ao SIGTERM. */
    int abrirEducado() throws IOException, InterruptedException {
        return abrir("sleep 120 >/dev/null 2>&1 & echo $!");
    }

    /** Um {@code sleep} que IGNORA o SIGTERM (o sinal ignorado é herdado pelo filho): só o SIGKILL o encerra. */
    int abrirTeimoso() throws IOException, InterruptedException {
        return abrir("trap \"\" TERM; sleep 120 >/dev/null 2>&1 & echo $!");
    }

    private int abrir(String script) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(false).start();
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor(10, TimeUnit.SECONDS);
        int pid = Integer.parseInt(saida);
        abertos.add(pid);
        return pid;
    }

    static boolean vivo(int pid) {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", "ps -o stat= -p " + pid).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(10, TimeUnit.SECONDS);
            return !s.isEmpty() && !s.startsWith("Z");
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static void matarAMao(int pid) throws IOException, InterruptedException {
        new ProcessBuilder("/bin/sh", "-c", "kill -KILL " + pid + " 2>/dev/null").start().waitFor(10, TimeUnit.SECONDS);
        for (int i = 0; i < 50 && vivo(pid); i++) {
            Thread.sleep(100);
        }
    }

    @Override
    public void close() {
        for (int pid : abertos) {
            try {
                matarAMao(pid);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
