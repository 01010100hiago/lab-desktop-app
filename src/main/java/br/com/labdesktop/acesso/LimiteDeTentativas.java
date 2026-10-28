package br.com.labdesktop.acesso;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * O freio das tentativas de senha, para o app não martelar o servidor (e não bloquear a conta do usuário por engano): depois de {@code maximo}
 * senhas recusadas SEGUIDAS para o mesmo usuário no mesmo servidor, uma pausa curta; passada a pausa, mais {@code maximo}. Uma senha aceita
 * zera a conta. Só conta o que mandou a senha e ela foi recusada (ou a conexão foi largada logo depois): endereço errado, host que mudou e
 * servidor sem senha não mandaram senha e não contam. Fica só na memória (nada em disco) e some quando o app fecha. Modelo puro: o relógio
 * entra por parâmetro.
 */
public final class LimiteDeTentativas {

    private final int maximo;
    private final Duration pausa;
    private final Supplier<Instant> relogio;
    private final Map<String, Conta> contas = new HashMap<>();

    private static final class Conta {
        int seguidas;
        Instant liberadoEm;
    }

    public LimiteDeTentativas(int maximo, Duration pausa, Supplier<Instant> relogio) {
        if (maximo < 1) {
            throw new IllegalArgumentException("O máximo de tentativas é pelo menos 1.");
        }
        this.maximo = maximo;
        this.pausa = pausa;
        this.relogio = relogio;
    }

    /** A chave de uma conta: servidor, porta e usuário (sem diferenciar maiúscula de minúscula). */
    public static String chave(String host, int porta, String usuario) {
        return (host.strip() + ":" + porta + ":" + usuario.strip()).toLowerCase(Locale.ROOT);
    }

    /** Quanto falta para poder tentar de novo, ou vazio se pode tentar agora. */
    public synchronized Optional<Duration> pausaRestante(String chave) {
        Conta c = contas.get(chave);
        if (c == null || c.liberadoEm == null) {
            return Optional.empty();
        }
        Duration falta = Duration.between(relogio.get(), c.liberadoEm);
        if (falta.isNegative() || falta.isZero()) {
            c.liberadoEm = null; // a pausa passou: recomeça a contar do zero
            c.seguidas = 0;
            return Optional.empty();
        }
        return Optional.of(falta);
    }

    /** Uma senha foi recusada. */
    public synchronized void registrarFalha(String chave) {
        Conta c = contas.computeIfAbsent(chave, k -> new Conta());
        c.seguidas++;
        if (c.seguidas >= maximo) {
            c.liberadoEm = relogio.get().plus(pausa);
        }
    }

    /** Uma senha foi aceita. */
    public synchronized void registrarSucesso(String chave) {
        contas.remove(chave);
    }
}
