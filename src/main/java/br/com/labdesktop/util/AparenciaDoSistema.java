package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Se o macOS está no modo escuro (ARQUITETURA 8.2: "Sistema" lê {@code defaults read -g AppleInterfaceStyle} na
 * abertura e ao ganhar foco). O comando devolve {@code Dark} com status 0 no modo escuro; no claro a chave não
 * existe e o status é 1.
 *
 * <p>Roda um processo: chame de thread de fundo, nunca da EDT. Este pacote não conhece Swing; quem aplica o
 * resultado ao tema é o {@code GerenciadorTema}, na EDT.
 */
public final class AparenciaDoSistema {

    private AparenciaDoSistema() { }

    /** {@code true} se o sistema está no modo escuro; {@code false} no claro ou se não foi possível saber. */
    public static boolean escuro() {
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return interpretar(p.exitValue(), saida);
        } catch (IOException e) {
            return false; // fora do macOS, sem o comando: tema claro
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A regra, separada do processo para ser testada: escuro só com status 0 e a palavra {@code Dark}. */
    static boolean interpretar(int status, String saida) {
        return status == 0 && saida != null && saida.strip().equalsIgnoreCase("Dark");
    }
}
