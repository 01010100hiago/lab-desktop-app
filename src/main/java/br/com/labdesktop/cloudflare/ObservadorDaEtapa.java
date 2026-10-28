package br.com.labdesktop.cloudflare;

/** Quem acompanha os passos (a tela): o estado de cada um e uma frase simples do que está acontecendo. Chamado na thread que trabalha (a tela leva para a EDT). */
@FunctionalInterface
public interface ObservadorDaEtapa {

    /** O estado de um passo. */
    enum Estado { ANDANDO, FEITO, PULADO, FALHOU, DESFEITO }

    void etapa(Etapa etapa, Estado estado, String frase);

    ObservadorDaEtapa NENHUM = (e, s, f) -> { };
}
