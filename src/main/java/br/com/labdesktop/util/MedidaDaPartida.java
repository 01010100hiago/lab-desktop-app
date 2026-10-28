package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;

/**
 * A medida da partida do app: quanto levou, na última vez, entre o processo começar e a primeira janela aparecer. A regra: a janelinha de abertura
 * ("Abrindo...") só existe se a partida passa de 1 segundo; numa máquina rápida ela nunca aparece. Como a janelinha precisa aparecer ANTES de se saber quanto a
 * partida de agora vai levar, vale a medida da vez anterior, guardada num arquivo de um número só na pasta de dados; sem medida ainda (primeira vez, ou arquivo
 * ilegível) a janelinha aparece, e a partida dela já grava a medida.
 *
 * <p>Lê e grava com E/S: fora da EDT. Ler NUNCA lança por causa do conteúdo.
 */
public final class MedidaDaPartida {

    /** Acima disto a partida é lenta e a janelinha de abertura aparece. */
    public static final Duration LIMITE = Duration.ofSeconds(1);
    /** Medida acima disto não é partida: é o app deixado dormindo antes da primeira janela, ou lixo no arquivo. */
    private static final long TETO_PLAUSIVEL_MS = 10 * 60_000L;

    private MedidaDaPartida() { }

    /** A janelinha de abertura deve aparecer, dada a medida da vez anterior? (sem medida: sim) */
    public static boolean passaDoLimite(Optional<Long> medidaAnteriorMs) {
        return medidaAnteriorMs.map(ms -> ms > LIMITE.toMillis()).orElse(true);
    }

    /** A medida da vez anterior, ou vazio se não há (ou não presta). */
    public static Optional<Long> ler(CaminhosApp caminhos) {
        try {
            var arquivo = caminhos.arquivoPartida();
            if (!Files.isRegularFile(arquivo)) {
                return Optional.empty();
            }
            String texto = new String(ArquivoPrivado.lerLimitado(arquivo, 64), StandardCharsets.UTF_8).strip();
            return interpretar(texto);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** O texto do arquivo como medida: só um inteiro de 0 a 10 minutos vale. */
    static Optional<Long> interpretar(String texto) {
        try {
            long ms = Long.parseLong(texto);
            return ms >= 0 && ms <= TETO_PLAUSIVEL_MS ? Optional.of(ms) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Guarda a medida desta partida (atômico, 0600). */
    public static void gravar(CaminhosApp caminhos, long ms) throws IOException {
        caminhos.prepararPastas();
        ArquivoPrivado.gravarAtomico(caminhos.arquivoPartida(), Long.toString(Math.max(0, ms)).getBytes(StandardCharsets.UTF_8));
    }
}
