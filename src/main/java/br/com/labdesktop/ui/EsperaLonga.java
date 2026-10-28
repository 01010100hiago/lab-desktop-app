package br.com.labdesktop.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Quando mostrar o cursor de espera: o modelo PURO de "tem ação demorando". Cada ação do usuário que roda em segundo plano ({@link Tarefas}) registra que
 * começou e que terminou; o cursor de espera só entra se alguma estiver rodando há mais de {@link #ATRASO} (uma ação que responde em meio segundo não faz
 * o cursor piscar), e sai quando a última termina. Sem relógio próprio: quem usa passa o "agora".
 */
public final class EsperaLonga {

    /** Quanto uma ação precisa durar para o cursor de espera aparecer. */
    public static final Duration ATRASO = Duration.ofMillis(300);

    private final Deque<Instant> inicios = new ArrayDeque<>();

    /** Uma ação começou agora. */
    public synchronized void comecou(Instant agora) {
        inicios.addLast(agora);
    }

    /** Uma ação terminou (a mais antiga é a que sai: as ações são contadas, não identificadas). */
    public synchronized void terminou() {
        inicios.pollFirst();
    }

    /** Há alguma ação rodando (mesmo que ainda dentro do atraso)? */
    public synchronized boolean haAcaoRodando() {
        return !inicios.isEmpty();
    }

    /** O cursor de espera deve estar na tela agora? Só se a ação rodando há mais tempo já passou do atraso. */
    public synchronized boolean cursorDeEsperaAgora(Instant agora) {
        Instant maisAntiga = inicios.peekFirst();
        return maisAntiga != null && !agora.isBefore(maisAntiga.plus(ATRASO));
    }
}
