package br.com.labdesktop.terminal;

import br.com.labdesktop.seguranca.ValidadorDeNomes;

/**
 * O que um terminal executa: o shell de login do servidor, o shell aberto na pasta de um projeto, ou o anexo à sessão do Claude de
 * um projeto (este último é o único que supõe Claude no servidor). É um valor fechado de
 * propósito: o transporte (SSH ou PTY local) decide COMO rodar cada um, e nada vindo do usuário vira texto de
 * comando por aqui.
 */
public sealed interface ComandoDeTerminal {

    /** O shell de login do usuário no servidor, em PTY. */
    record ShellDoServidor() implements ComandoDeTerminal {
        public static final ShellDoServidor INSTANCIA = new ShellDoServidor();
    }

    /**
     * O shell de login do usuário, já dentro da pasta do projeto (se ela existir; senão na pasta pessoal, com um aviso). O caminho
     * é validado na construção: só letras, números, ponto, hífen, sublinhado e barra, absoluto e sem {@code ..}.
     *
     * @throws IllegalArgumentException o caminho não passa na regra
     */
    record ShellDoProjeto(String pasta) implements ComandoDeTerminal {
        private static final java.util.regex.Pattern CAMINHO = java.util.regex.Pattern.compile("^/[A-Za-z0-9._/-]{1,512}$");

        public ShellDoProjeto {
            if (pasta == null || !CAMINHO.matcher(pasta).matches() || java.util.Arrays.asList(pasta.split("/")).contains("..")) {
                throw new IllegalArgumentException("Pasta de projeto inválida para o terminal.");
            }
        }

        /** {@code true} se o caminho serve para {@link ShellDoProjeto} (senão o app abre o shell do servidor). */
        public static boolean valida(String pasta) {
            try {
                new ShellDoProjeto(pasta);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
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

    /** {@code true} nos shells (do servidor ou do projeto): sair por conta própria ({@code exit}) é o fim normal, e nada é julgado pelo texto. */
    default boolean ehShell() {
        return !(this instanceof SessaoDoClaude);
    }
}
