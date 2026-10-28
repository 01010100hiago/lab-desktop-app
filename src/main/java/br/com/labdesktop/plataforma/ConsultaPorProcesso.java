package br.com.labdesktop.plataforma;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A {@link ConsultaDeSistema} de verdade: um {@link ProcessBuilder} com a lista de argumentos, prazo curto e o processo morto se demorar. Tolerante a qualquer falha. */
public final class ConsultaPorProcesso implements ConsultaDeSistema {

    private static final int TETO = 8 * 1024;

    @Override
    public Resposta perguntar(List<String> comando, Duration prazo) {
        Process p = null;
        try {
            p = new ProcessBuilder(comando).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] saida = p.getInputStream().readNBytes(TETO); // a saída que importa é curta; o resto se descarta junto com o processo
            if (!p.waitFor(prazo.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return Resposta.falhou();
            }
            return new Resposta(p.exitValue(), new String(saida, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Resposta.falhou(); // sem o programa (outro sistema), sem permissão
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resposta.falhou();
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
