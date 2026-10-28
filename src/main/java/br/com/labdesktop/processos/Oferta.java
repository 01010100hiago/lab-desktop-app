package br.com.labdesktop.processos;

/**
 * O que a tela oferece para UM processo da lista, decidido só pelo que a lista disse (nada de rede): quais botões aparecem, se estão ligados,
 * e as frases de cada caso. Puro e testável; é a regra dos "estados dos botões".
 *
 * @param situacao        {@link Situacao#LIBERADO}, {@link Situacao#SEM_PERMISSAO} ou {@link Situacao#PROTEGIDO}
 * @param via             por onde a ação chega (processo, container); {@code null} se protegido
 * @param podeReiniciar   o botão Reiniciar existe (container); para um processo comum ele nem aparece
 * @param protecao        o motivo de ser protegido (vazio se não é)
 * @param explicacao      o que a ação faz, na linguagem do usuário (para a confirmação)
 * @param porQueNaoReinicia só um processo comum: por que não há Reiniciar (a tela diz uma vez, abaixo da lista)
 * @param comandoParaODono sem permissão: o comando exato para o usuário rodar sozinho (sem {@code sudo -n}: o app nunca pede nem digita senha)
 */
public record Oferta(Situacao situacao, Via via, boolean podeReiniciar, String protecao, String explicacao, String porQueNaoReinicia,
                     String comandoParaODono) {

    public enum Situacao {
        /** O usuário pode pedir. */
        LIBERADO,
        /** É de outro usuário (ou do administrador): o app só mostra o comando para o usuário rodar. */
        SEM_PERMISSAO,
        /** Nunca se oferece. */
        PROTEGIDO
    }

    /** O botão Encerrar existe e vale clicar (abre a confirmação ou o aviso de sem permissão). */
    public boolean podeEncerrar() {
        return situacao != Situacao.PROTEGIDO;
    }

    /** O botão Reiniciar liga: só para container, e só se for o usuário quem pode agir (container usa o Docker, não o usuário do processo). */
    public boolean reiniciarLigado() {
        return podeReiniciar && situacao == Situacao.LIBERADO;
    }

    public boolean protegido() {
        return situacao == Situacao.PROTEGIDO;
    }
}
