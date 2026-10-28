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
     * O shell de login do usuário, já dentro da pasta do projeto (se ela existir; senão na pasta pessoal, com um aviso).
     *
     * <p><b>No servidor SSH</b> ({@code local} falso) o caminho vai para uma linha de shell remoto, então a regra é estrita: só letras, números,
     * ponto, hífen, sublinhado e barra, absoluto e sem {@code ..}. <b>Em "Este computador"</b> ({@code local} verdadeiro) a pasta é só o
     * diretório de trabalho do processo (nunca parte de uma linha de comando), então a regra é neutra de sistema: caminho do app absoluto
     * ({@code /Users/ana/x} ou {@code /C:/Users/João Silva/x}), sem {@code ..} e sem caractere de controle; espaço e acento valem.
     *
     * @throws IllegalArgumentException o caminho não passa na regra
     */
    record ShellDoProjeto(String pasta, boolean local) implements ComandoDeTerminal {
        private static final java.util.regex.Pattern CAMINHO = java.util.regex.Pattern.compile("^/[A-Za-z0-9._/-]{1,512}$");
        private static final java.util.regex.Pattern CONTROLE = java.util.regex.Pattern.compile("\\p{Cntrl}");

        /** O shell do projeto num servidor SSH (regra estrita). */
        public ShellDoProjeto(String pasta) {
            this(pasta, false);
        }

        public ShellDoProjeto {
            boolean ok = pasta != null && !java.util.Arrays.asList(pasta.split("/")).contains("..")
                && (local ? pastaLocalValida(pasta) : CAMINHO.matcher(pasta).matches());
            if (!ok) {
                throw new IllegalArgumentException("Pasta de projeto inválida para o terminal.");
            }
        }

        private static boolean pastaLocalValida(String pasta) {
            return pasta.startsWith("/") && pasta.length() <= 1024 && !CONTROLE.matcher(pasta).find();
        }

        /** O shell do projeto de "Este computador" (regra neutra de sistema: aceita espaço, acento e {@code /C:/Users/...}). */
        public static ShellDoProjeto doComputador(String pastaDoApp) {
            return new ShellDoProjeto(pastaDoApp, true);
        }

        /** {@code true} se o caminho serve para {@link ShellDoProjeto} num servidor SSH (senão o app abre o shell do servidor). */
        public static boolean valida(String pasta) {
            return valida(pasta, false);
        }

        /** {@code true} se o caminho serve na regra do servidor SSH ({@code local} falso) ou na de "Este computador" ({@code local} verdadeiro). */
        public static boolean valida(String pasta, boolean local) {
            try {
                new ShellDoProjeto(pasta, local);
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
