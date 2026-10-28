package br.com.labdesktop.plataforma;

/**
 * Onde o app guarda os dados e o log em cada sistema, como TEXTO (ARQUITETURA 5.1 e 11.4). Escolhas:
 *
 * <ul>
 *   <li><b>macOS:</b> {@code ~/Library/Application Support/LabDesktop} e {@code ~/Library/Logs/LabDesktop};</li>
 *   <li><b>Windows:</b> {@code %APPDATA%\LabDesktop} para os dados e {@code %LOCALAPPDATA%\LabDesktop\Logs} para o log (um
 *       log não deve viajar num perfil de rede). Sem a variável, ou com um valor que não é caminho absoluto, a pasta padrão do perfil
 *       ({@code <casa>\AppData\Roaming} e {@code <casa>\AppData\Local});</li>
 *   <li><b>Nativos extraídos</b> (o PTY, o JNA): no Windows em {@code %LOCALAPPDATA%\LabDesktop\nativos}, e NÃO em {@code %APPDATA%} (Roaming): são programas
 *       que a máquina extrai e o antivírus pode limpar, não dado do usuário, e não devem viajar num perfil de rede. No Mac e no Linux, {@code <dados>/nativos};</li>
 *   <li><b>Linux:</b> {@code $XDG_DATA_HOME/LabDesktop} e {@code $XDG_STATE_HOME/LabDesktop} (só se forem absolutos, como manda a
 *       especificação), senão {@code ~/.local/share/LabDesktop} e {@code ~/.local/state/LabDesktop}.</li>
 * </ul>
 *
 * <p>A instalação do app (o {@code .msi}, o {@code .app}) NUNCA guarda dado do usuário: apagar ou atualizar o app não toca no cofre.
 */
public record PastasDoApp(String dados, String logs, String nativos) {

    public static final String NOME = "LabDesktop";

    public static PastasDoApp de(AmbienteDoSistema a) {
        return switch (a.sistema()) {
            case MAC -> {
                String dados = a.juntar(a.casa(), "Library", "Application Support", NOME);
                yield new PastasDoApp(dados, a.juntar(a.casa(), "Library", "Logs", NOME), a.juntar(dados, "nativos"));
            }
            case WINDOWS -> {
                String roaming = absolutaOu(a, "APPDATA", a.juntar(a.casa(), "AppData", "Roaming"));
                String local = absolutaOu(a, "LOCALAPPDATA", a.juntar(a.casa(), "AppData", "Local"));
                yield new PastasDoApp(a.juntar(roaming, NOME), a.juntar(local, NOME, "Logs"), a.juntar(local, NOME, "nativos"));
            }
            case LINUX -> {
                String dados = absolutaOu(a, "XDG_DATA_HOME", a.juntar(a.casa(), ".local", "share"));
                String estado = absolutaOu(a, "XDG_STATE_HOME", a.juntar(a.casa(), ".local", "state"));
                yield new PastasDoApp(a.juntar(dados, NOME), a.juntar(estado, NOME), a.juntar(dados, NOME, "nativos"));
            }
        };
    }

    private static String absolutaOu(AmbienteDoSistema a, String variavel, String padrao) {
        String v = a.variavel(variavel);
        return v != null && a.ehAbsoluto(v) ? v : padrao;
    }
}
