package br.com.labdesktop.arquivos;

/**
 * As permissões de um arquivo em palavras que o usuário entende, no lugar de {@code rwxr-xr-x}: quem pode LER e quem pode
 * ALTERAR. O texto técnico ({@code rwxr-xr-x}, {@code 755}) fica para a dica da coluna, para quem quiser conferir.
 */
public final class PermissoesEmTexto {

    private PermissoesEmTexto() { }

    /**
     * Em poucas palavras (cabe na coluna): "Todos alteram", "Grupo altera", "Todos leem", "Dono e grupo", "Só o dono", "Só leitura",
     * "Ninguém". {@code "—"} se o servidor não disse. A frase inteira, para a dica, está em {@link #explicacao}.
     */
    public static String simples(int modo) {
        if (modo < 0) {
            return "—";
        }
        boolean donoGrava = (modo & 0200) != 0;
        boolean grupoLe = (modo & 040) != 0;
        boolean grupoGrava = (modo & 020) != 0;
        boolean outrosLem = (modo & 04) != 0;
        boolean outrosGravam = (modo & 02) != 0;
        if ((modo & 0777) == 0) {
            return "Ninguém";
        }
        if (outrosGravam) {
            return "Todos alteram";
        }
        if (grupoGrava) {
            return "Grupo altera";
        }
        if (donoGrava) {
            return outrosLem ? "Todos leem" : grupoLe ? "Dono e grupo" : "Só o dono";
        }
        return "Só leitura";
    }

    /** A frase inteira para a dica da célula: quem pode ler e quem pode alterar. */
    public static String explicacao(int modo) {
        if (modo < 0) {
            return "";
        }
        if ((modo & 0777) == 0) {
            return "Ninguém pode ler nem alterar (só o administrador do servidor).";
        }
        String ler = (modo & 04) != 0 ? "Todos podem ler" : (modo & 040) != 0 ? "O dono e o grupo podem ler" : (modo & 0400) != 0 ? "Só o dono pode ler" : "Ninguém pode ler";
        String alterar = (modo & 02) != 0 ? "todos podem alterar" : (modo & 020) != 0 ? "o dono e o grupo podem alterar"
            : (modo & 0200) != 0 ? "só o dono pode alterar" : "ninguém pode alterar";
        return ler + "; " + alterar + ".";
    }

    /** {@code rwxr-xr-x}. */
    public static String tecnico(int modo) {
        if (modo < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(9);
        int[] bits = {0400, 0200, 0100, 040, 020, 010, 04, 02, 01};
        char[] letras = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < 9; i++) {
            sb.append((modo & bits[i]) != 0 ? letras[i] : '-');
        }
        return sb.toString();
    }

    /** {@code 755}. */
    public static String octal(int modo) {
        return modo < 0 ? "" : String.format("%03o", modo & 0777);
    }
}
