package br.com.labdesktop.terminal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Decide o que fazer quando o canal de um terminal fecha (ARQUITETURA 7.3): reanexar, ou NÃO entrar em laço.
 *
 * <ul>
 *   <li>Canal que fecha em menos de 10 s com saída contendo {@code No such container}, {@code is not running}, {@code no sessions},
 *       {@code can't find session} (ou {@code no server running}, o que o tmux diz sem nenhuma sessão): a sessão do Claude NÃO
 *       existe, reanexar só repetiria o erro. Vira {@link Veredito#SESSAO_FECHADA}.</li>
 *   <li>Status 0: o programa remoto saiu por conta própria (o bash do tmux, ou o {@code exit} do shell): {@link Veredito#TERMINOU}.</li>
 *   <li>No shell do servidor, qualquer status de verdade é o fim da conversa; só a queda sem status reanexa.</li>
 *   <li>O resto é queda da conexão: {@link Veredito#QUEDA}, reanexa.</li>
 * </ul>
 *
 * <p>Só os últimos 2 KB importam, e só nos primeiros 10 s: depois disso o texto da tela do Claude poderia conter qualquer coisa.
 */
public final class ClassificadorDeSaida {

    public enum Veredito {
        /** A sessão do Claude não existe (container ou sessão tmux ausentes). Sem laço de reanexação. */
        SESSAO_FECHADA,
        /** O programa remoto terminou sozinho. Sem laço. */
        TERMINOU,
        /** A conexão se perdeu: reanexa. */
        QUEDA
    }

    /** Quantos bytes finais da saída são examinados. */
    public static final int BYTES_EXAMINADOS = 2048;
    /** Só um canal que viveu menos que isto é julgado pela saída. */
    public static final Duration VIDA_DE_FALHA_RAPIDA = Duration.ofSeconds(10);

    private static final List<String> SINAIS_DE_SESSAO_AUSENTE = List.of(
        "no such container", "is not running", "no sessions", "can't find session", "no server running");

    private ClassificadorDeSaida() { }

    /**
     * @param vida   quanto tempo o canal ficou aberto
     * @param status o status de saída, ou {@code null} se o canal caiu sem um
     * @param cauda  os últimos bytes da saída (só os últimos {@value #BYTES_EXAMINADOS} são lidos)
     * @param shell  {@code true} no shell do servidor, {@code false} na sessão do Claude
     */
    public static Veredito classificar(Duration vida, Integer status, byte[] cauda, boolean shell) {
        if (!shell && vida.compareTo(VIDA_DE_FALHA_RAPIDA) < 0 && indicaSessaoAusente(cauda)) {
            return Veredito.SESSAO_FECHADA;
        }
        if (status != null && (status == 0 || shell)) {
            return Veredito.TERMINOU;
        }
        return Veredito.QUEDA;
    }

    /** A saída (já cortada em 2 KB ou não) diz que o container ou a sessão tmux não existem? Ignora maiúsculas. */
    public static boolean indicaSessaoAusente(byte[] cauda) {
        if (cauda == null || cauda.length == 0) {
            return false;
        }
        int de = Math.max(0, cauda.length - BYTES_EXAMINADOS);
        String texto = new String(cauda, de, cauda.length - de, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String sinal : SINAIS_DE_SESSAO_AUSENTE) {
            if (texto.contains(sinal)) {
                return true;
            }
        }
        return false;
    }
}
