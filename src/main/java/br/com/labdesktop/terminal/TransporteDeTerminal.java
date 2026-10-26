package br.com.labdesktop.terminal;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * A "tomada" do terminal: abre um {@link CanalDeTerminal} para um {@link ComandoDeTerminal} num certo tamanho. A implementação
 * {@link TransporteSsh} usa a sessão SSH do servidor; o transporte local ("Este computador") usará um PTY do próprio Mac sem que a
 * interface perceba a diferença (ARQUITETURA, "Servidor local").
 *
 * <p>Bloqueia: chame de thread de fundo, nunca da EDT.
 */
public interface TransporteDeTerminal {

    /** Se o transporte pode abrir canais agora. */
    enum Disponibilidade {
        /** Dá para abrir (no SSH: há sessão autenticada). */
        DISPONIVEL,
        /** Não dá agora, mas pode voltar sozinho (no SSH: reconectando, o Mac dormindo). */
        INDISPONIVEL,
        /** O cofre foi bloqueado: nunca mais, nesta sessão. Os canais devem ser fechados. */
        BLOQUEADO
    }

    /**
     * Abre o canal.
     *
     * @throws IOException   não há como abrir agora (sem conexão, canal recusado, comando falhou ao subir)
     * @throws br.com.labdesktop.terminal.TerminalRecusadoException o servidor recusou de forma que repetir não resolve
     */
    CanalDeTerminal abrir(ComandoDeTerminal comando, TamanhoDeTerminal tamanho) throws IOException;

    /**
     * Passa a receber a disponibilidade (primeiro o valor de agora). Os avisos chegam em threads do próprio transporte: seja rápido
     * e despache. Devolve o que desfaz a inscrição.
     */
    Runnable observarDisponibilidade(Consumer<Disponibilidade> ouvinte);

    /** Um canal caiu sem o usuário pedir: o transporte pode conferir a saúde da conexão agora (no SSH, um keepalive com resposta). */
    default void sugerirVerificacao() { }
}
