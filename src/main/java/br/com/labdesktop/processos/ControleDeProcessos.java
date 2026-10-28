package br.com.labdesktop.processos;

import br.com.labdesktop.arquivos.Cancelamento;

/**
 * Encerrar e reiniciar processos de um lugar (um servidor por SSH ou este computador). A tela só fala com esta interface: {@link #avaliar} não faz
 * nada de rede (é só a regra), {@link #preparar} SÓ LÊ, e as três ações rodam depois do clique de confirmação. Tudo que não é {@code avaliar}
 * BLOQUEIA: thread de fundo, nunca a EDT.
 */
public interface ControleDeProcessos {

    /** O ambiente (para as regras de proteção e de permissão). */
    AmbienteDeProcessos ambiente();

    /** O que se oferece para este processo, só pelo que a lista disse. */
    default Oferta avaliar(ProcessoAlvo alvo) {
        return AvaliadorDeProcessos.avaliar(alvo, ambiente());
    }

    /**
     * Lê a identidade real do processo (e a unidade do systemd, se houver) para a confirmação. SÓ LÊ.
     *
     * @throws FalhaDeProcesso protegido, já saiu, não é mais o processo da lista, o servidor não deixa conferir
     */
    Previa preparar(ProcessoAlvo alvo) throws FalhaDeProcesso, InterruptedException;

    /**
     * Encerra: um processo comum recebe o pedido educado (SIGTERM) e o app espera alguns segundos mostrando o progresso; um container recebe
     * {@code docker stop}. NUNCA mata à força: se o programa não fechar, o resultado é {@link Desfecho#AINDA_RODANDO} e o usuário decide {@link #forcar}.
     */
    Resultado encerrar(Previa previa, ObservadorDeProcesso observador, Cancelamento cancelamento) throws InterruptedException;

    /** Mata à força (SIGKILL) um processo comum, depois de o usuário ver que o pedido educado não bastou. A identidade é conferida de novo. */
    Resultado forcar(Previa previa, ObservadorDeProcesso observador, Cancelamento cancelamento) throws InterruptedException;

    /** Reinicia: container ({@code docker restart}) ou serviço do systemd do usuário. Um processo comum não tem. */
    Resultado reiniciar(Previa previa, ObservadorDeProcesso observador, Cancelamento cancelamento) throws InterruptedException;
}
