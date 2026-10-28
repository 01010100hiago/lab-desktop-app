package br.com.labdesktop.acesso;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * O freio das tentativas de senha, para o app não martelar o servidor nem fazer o fail2ban dele (padrão: 5 falhas em 10 minutos) banir o endereço do usuário. A
 * contagem é ACUMULADA numa janela (padrão: no máximo 4 senhas recusadas em 10 minutos por servidor, porta e usuário): as recusas não "zeram" por passar uma
 * pausa curta; só saem da conta quando envelhecem além da janela. Entre as primeiras recusas há uma pausa que cresce (nenhuma depois da 1ª, 5 s depois da 2ª, 15 s
 * depois da 3ª); com o máximo atingido a próxima tentativa só vale quando a recusa mais antiga sai da janela. Uma senha aceita zera a conta. Só conta o que mandou a
 * senha e ela foi recusada (ou a conexão foi largada logo depois): endereço errado, host que mudou e servidor sem senha não mandaram senha e não contam.
 *
 * <p>Só na memória (nada em disco); some quando o app fecha. Modelo puro: o relógio entra por parâmetro.
 */
public final class LimiteDeTentativas {

    /** As pausas depois da 1ª, 2ª e 3ª recusa seguidas dentro da janela. */
    public static final List<Duration> PAUSAS_PADRAO = List.of(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(15));

    private final int maximo;
    private final Duration janela;
    private final List<Duration> pausas;
    private final Supplier<Instant> relogio;
    private final Map<String, Deque<Instant>> recusas = new HashMap<>();

    public LimiteDeTentativas(int maximo, Duration janela, Supplier<Instant> relogio) {
        this(maximo, janela, PAUSAS_PADRAO, relogio);
    }

    public LimiteDeTentativas(int maximo, Duration janela, List<Duration> pausas, Supplier<Instant> relogio) {
        if (maximo < 1) {
            throw new IllegalArgumentException("O máximo de tentativas é pelo menos 1.");
        }
        this.maximo = maximo;
        this.janela = janela;
        this.pausas = List.copyOf(pausas);
        this.relogio = relogio;
    }

    /** A chave de uma conta: servidor (minúsculas, sem o ponto final e sem espaço), porta e usuário (também em minúsculas, para o freio ser mais cauteloso, não menos). */
    public static String chave(String host, int porta, String usuario) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h + ":" + porta + ":" + usuario.strip().toLowerCase(Locale.ROOT);
    }

    /** Quanto falta para poder tentar de novo, ou vazio se pode tentar agora. */
    public synchronized Optional<Duration> pausaRestante(String chave) {
        Deque<Instant> lista = podar(chave);
        if (lista == null || lista.isEmpty()) {
            return Optional.empty();
        }
        int n = lista.size();
        Instant liberadoEm;
        if (n >= maximo) {
            liberadoEm = lista.peekFirst().plus(janela);
        } else {
            Duration pausa = pausas.isEmpty() ? Duration.ZERO : pausas.get(Math.min(n, pausas.size()) - 1);
            liberadoEm = lista.peekLast().plus(pausa);
        }
        Duration falta = Duration.between(relogio.get(), liberadoEm);
        return falta.isNegative() || falta.isZero() ? Optional.empty() : Optional.of(falta);
    }

    /** Quantas tentativas ainda cabem na janela. */
    public synchronized int restantes(String chave) {
        Deque<Instant> lista = podar(chave);
        return Math.max(0, maximo - (lista == null ? 0 : lista.size()));
    }

    /** Uma senha foi recusada. */
    public synchronized void registrarFalha(String chave) {
        podar(chave);
        recusas.computeIfAbsent(chave, k -> new ArrayDeque<>()).addLast(relogio.get());
    }

    /** Uma senha foi aceita. */
    public synchronized void registrarSucesso(String chave) {
        recusas.remove(chave);
    }

    /** Tira da conta as recusas que já saíram da janela. */
    private Deque<Instant> podar(String chave) {
        Deque<Instant> lista = recusas.get(chave);
        if (lista == null) {
            return null;
        }
        Instant corte = relogio.get().minus(janela);
        while (!lista.isEmpty() && !lista.peekFirst().isAfter(corte)) {
            lista.removeFirst();
        }
        if (lista.isEmpty()) {
            recusas.remove(chave);
        }
        return lista;
    }
}
