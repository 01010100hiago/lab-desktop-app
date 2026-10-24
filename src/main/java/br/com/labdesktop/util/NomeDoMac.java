package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Nome deste Mac, para o comentário da chave pública ({@code lab-desktop@<mac>}) e para sugerir o
 * {@code nome_do_dispositivo} do token.
 *
 * <p>Não usa {@code InetAddress.getLocalHost()}: no macOS ele faz resolução de DNS e pode levar segundos
 * (ou travar) sem rede. Roda {@code hostname}, que lê o nome local direto, com prazo curto.
 */
public final class NomeDoMac {

    private static final String PADRAO = "mac";

    private NomeDoMac() { }

    /** Nome do host, sem quebras de linha; {@code "mac"} se não conseguir. Bloqueia por até ~2 s: fora da EDT. */
    public static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env.strip();
        }
        try {
            Process p = new ProcessBuilder("hostname").redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return PADRAO;
            }
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return saida.isEmpty() ? PADRAO : saida;
        } catch (IOException e) {
            return PADRAO;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PADRAO;
        }
    }
}
