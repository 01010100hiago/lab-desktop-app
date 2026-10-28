package br.com.labdesktop.registro;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Estima a severidade de uma linha do registro pelo TEXTO dela. É uma estimativa, e a tela diz isso: o comando fixo do app ({@code journalctl -o short-iso}) não traz a prioridade
 * que o sistema deu à mensagem, e o registro do Docker também não. Palavras inteiras, sem diferenciar maiúscula de minúscula ({@code error}, {@code erro}, {@code fatal}, {@code failed}...
 * = erro; {@code warn}, {@code timeout}, {@code refused}, {@code deprecated}... = aviso); o resto é info. Puro.
 */
public final class ClassificadorDeSeveridade {

    private static final Pattern ERRO = Pattern.compile(
        "\\b(error|errors|erro|erros|fatal|critical|crit|emerg|alert|panic|segfault|failed|failure|falha|falhou|oom|out of memory|oom-killer|killed|denied|negado|abort|aborted|exception|"
            + "traceback|unable|cannot|can't|corrupt|corrupted|i/o error)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVISO = Pattern.compile(
        "\\b(warn|warning|warnings|aviso|deprecated|timeout|timed out|retry|retrying|refused|unreachable|invalid|no space|degraded|throttl\\w*|restarting|unhealthy)\\b",
        Pattern.CASE_INSENSITIVE);

    private ClassificadorDeSeveridade() { }

    /** A severidade estimada da mensagem (a origem não pesa: um serviço chamado {@code error-pages} não é erro). */
    public static Severidade de(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) {
            return Severidade.INFO;
        }
        String m = mensagem.toLowerCase(Locale.ROOT);
        if (ERRO.matcher(m).find()) {
            return Severidade.ERRO;
        }
        if (AVISO.matcher(m).find()) {
            return Severidade.AVISO;
        }
        return Severidade.INFO;
    }
}
