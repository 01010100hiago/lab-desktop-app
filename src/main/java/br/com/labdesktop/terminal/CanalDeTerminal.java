package br.com.labdesktop.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Um canal de terminal já aberto, visto de quem o usa: bytes que entram, bytes que saem, redimensionar e fechar. Não supõe SSH
 * (nada de {@code ClientSession} aqui): o transporte local devolve a mesma coisa sobre um PTY do próprio Mac.
 *
 * <p>A saída já traz o stderr junto (um PTY junta os dois fluxos).
 */
public interface CanalDeTerminal {

    /** O que o terminal remoto imprime; chega ao fim (EOF) quando o canal fecha. */
    InputStream saida();

    /** O teclado: o que se escreve aqui chega ao programa remoto. Pode bloquear se o remoto não lê. */
    OutputStream entrada();

    /** Avisa o programa remoto do novo tamanho (no SSH, {@code window-change}; ele recebe SIGWINCH). */
    void redimensionar(TamanhoDeTerminal tamanho) throws IOException;

    /** {@code true} enquanto o canal está aberto. */
    boolean aberto();

    /**
     * Bloqueia até o canal fechar e devolve o status de saída do programa remoto, ou {@code null} se o canal caiu sem status (a
     * conexão se perdeu).
     */
    Integer aguardarFim() throws InterruptedException;

    /** O status de saída, se o canal já fechou com um; {@code null} se ainda aberto ou fechou sem status. */
    Integer statusDeSaida();

    /**
     * Fecha o canal AVISANDO o outro lado (no SSH, EOF e CLOSE chegam ao servidor: o {@code docker exec} e o cliente do tmux recebem o
     * hangup e saem). Volta na hora e destrava quem estiver preso numa escrita; se o outro lado não responder, o canal é arrancado
     * em poucos segundos. Idempotente.
     */
    void fechar();

    /**
     * Fecha agora, SEM avisar o outro lado: para quando a conexão já se foi e não há ninguém para avisar. Por padrão, o mesmo que
     * {@link #fechar()}.
     */
    default void abortar() {
        fechar();
    }
}
