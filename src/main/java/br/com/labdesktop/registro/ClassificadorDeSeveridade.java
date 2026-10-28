package br.com.labdesktop.registro;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Estima a severidade de uma linha do registro pelo TEXTO dela. É uma estimativa, e a tela diz isso: o comando fixo do app ({@code journalctl -o short-iso}) não traz a prioridade
 * que o sistema deu à mensagem, e o registro do Docker também não. Palavras inteiras (sem letra, número, sublinhado ou HÍFEN encostado: {@code error-pages.service} não é erro; {@code error=<nil>} também não), sem diferenciar maiúscula de minúscula ({@code error}, {@code erro}, {@code fatal}, {@code failed}...
 * = erro; {@code warn}, {@code timeout}, {@code refused}, {@code deprecated}... = aviso; {@code No space left on device} é erro); o resto é info. Puro.
 */
public final class ClassificadorDeSeveridade {

    /** Antes e depois da palavra não pode haver letra, número, sublinhado nem hífen: {@code error-pages.service} e {@code failed-units-check.timer} são nomes, não erros. */
    private static final String ANTES = "(?<![\\p{L}\\p{N}_-])";
    private static final String DEPOIS = "(?![\\p{L}\\p{N}_-])";

    private static final Pattern ERRO = Pattern.compile(ANTES + "(error|errors|erro|erros|fatal|critical|crit|emerg|alert|panic|segfault|failed|failure|falha|falhou|oom|out of memory|"
        + "oom-killer|killed|denied|negado|abort|aborted|exception|traceback|unable|cannot|can't|corrupt|corrupted|i/o error|no space left( on device)?|read-only file system)" + DEPOIS,
        Pattern.CASE_INSENSITIVE);
    private static final Pattern AVISO = Pattern.compile(ANTES + "(warn|warning|warnings|aviso|deprecated|timeout|timed out|retry|retrying|refused|unreachable|invalid|degraded|throttl\\w*|"
        + "restarting|unhealthy)" + DEPOIS, Pattern.CASE_INSENSITIVE);
    /**
     * A palavra "error" que diz que NÃO houve erro: {@code error=<nil>}, {@code error=null}, {@code "error":null}, {@code errors=0}, {@code failed=0}. Sai do texto antes de procurar
     * (um log de aplicação que escreve o campo vazio em toda linha de info não é uma linha de erro).
     */
    private static final Pattern CAMPO_SEM_ERRO = Pattern.compile(
        "\"?(error|errors|err|erro|erros|failed|failures?|falhas?)\"?\\s*[:=]\\s*(<nil>|nil|null|none|\"\"|''|0|false)" + DEPOIS, Pattern.CASE_INSENSITIVE);

    private ClassificadorDeSeveridade() { }

    /** A severidade estimada da mensagem (a origem não pesa: um serviço chamado {@code error-pages} não é erro). */
    public static Severidade de(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) {
            return Severidade.INFO;
        }
        String m = CAMPO_SEM_ERRO.matcher(mensagem.toLowerCase(Locale.ROOT)).replaceAll(" ");
        if (ERRO.matcher(m).find()) {
            return Severidade.ERRO;
        }
        if (AVISO.matcher(m).find()) {
            return Severidade.AVISO;
        }
        return Severidade.INFO;
    }
}
