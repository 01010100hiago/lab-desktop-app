package br.com.labdesktop.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Quando mostrar o cursor de espera: o modelo PURO de "tem ação demorando". Cada ação do usuário que roda em segundo plano ({@link Tarefas}) registra que
 * começou e que terminou; o cursor de espera só entra se alguma estiver rodando há mais de {@link #ATRASO} (uma ação que responde em meio segundo não faz
 * o cursor piscar), e sai quando a última termina. Sem relógio próprio: quem usa passa o "agora".
 */
public final class EsperaLonga {

    /** Quanto uma ação precisa durar para o cursor de espera aparecer. */
    public static final Duration ATRASO = Duration.ofMillis(300);

    /** Uma ação em andamento: quem a começou a devolve ao terminar (identidade, não valor: duas ações começadas no mesmo instante são duas). */
    public static final class Acao {
        private final Instant inicio;

        private Acao(Instant inicio) {
            this.inicio = inicio;
        }
    }

    private final List<Acao> acoes = new ArrayList<>();

    /** Uma ação começou agora. Guarde o que volta e entregue a {@link #terminou(Acao)}. */
    public synchronized Acao comecou(Instant agora) {
        Acao a = new Acao(agora);
        acoes.add(a);
        return a;
    }

    /** ESSA ação terminou (não a mais antiga: uma ação curta que termina não pode apagar o início de uma longa que ainda roda). Terminar duas vezes ou uma desconhecida não faz nada. */
    public synchronized void terminou(Acao acao) {
        acoes.remove(acao);
    }

    /** Há alguma ação rodando (mesmo que ainda dentro do atraso)? */
    public synchronized boolean haAcaoRodando() {
        return !acoes.isEmpty();
    }

    /** O cursor de espera deve estar na tela agora? Só se a ação rodando há mais tempo já passou do atraso. */
    public synchronized boolean cursorDeEsperaAgora(Instant agora) {
        Instant maisAntiga = null;
        for (Acao a : acoes) {
            if (maisAntiga == null || a.inicio.isBefore(maisAntiga)) {
                maisAntiga = a.inicio;
            }
        }
        return maisAntiga != null && !agora.isBefore(maisAntiga.plus(ATRASO));
    }
}
