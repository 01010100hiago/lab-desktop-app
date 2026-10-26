package br.com.labdesktop.terminal;

import br.com.labdesktop.seguranca.ValidadorDeNomes;

/**
 * O que um terminal executa: o shell de login do servidor, ou o anexo à sessão do Claude de um projeto. É um valor fechado de
 * propósito: o transporte (SSH ou PTY local) decide COMO rodar cada um, e nada vindo do usuário vira texto de
 * comando por aqui.
 */
public sealed interface ComandoDeTerminal {

    /** O shell de login do usuário no servidor, em PTY. */
    record ShellDoServidor() implements ComandoDeTerminal {
        public static final ShellDoServidor INSTANCIA = new ShellDoServidor();
    }

    /**
     * Anexa à sessão tmux do Claude do projeto (a raiz do lab é o projeto {@code lab}). O nome é validado na construção.
     *
     * @throws IllegalArgumentException o nome não passa em {@code ^[a-z0-9][a-z0-9-]{0,48}$}
     */
    record SessaoDoClaude(String projeto) implements ComandoDeTerminal {
        public SessaoDoClaude {
            if (!ValidadorDeNomes.projetoValido(projeto)) {
                throw new IllegalArgumentException("Nome de projeto inválido para o terminal.");
            }
        }
    }

    /** {@code true} quando fechar o terminal por conta própria (status 0 ou {@code exit}) é o fim normal da conversa. */
    default boolean ehShell() {
        return this instanceof ShellDoServidor;
    }
}
