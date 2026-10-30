package br.com.labdesktop.construcao;

/**
 * A versão que o {@code jpackage} recebe em {@code --app-version}, por sistema. Cada instalador tem a sua regra e a versão do app ({@code 0.1.320}) não vale em todos:
 *
 * <ul>
 *   <li><b>macOS</b>: até três números e o PRIMEIRO precisa ser maior que zero (o jpackage recusa {@code 0.x}). Soma-se 1 ao primeiro número, sempre: {@code 0.1.320} vira
 *       {@code 1.1.320} e a futura {@code 1.0.5} vira {@code 2.0.5}. Assim a versão do pacote nunca recua (mapear só o {@code 0.x} deixaria a {@code 1.0.0} de verdade MENOR que
 *       a {@code 1.1.320} do pacote da {@code 0.1.320}). O {@code CFBundleShortVersionString} é esta versão e o {@code CFBundleVersion} é o número de commits.</li>
 *   <li><b>Windows (.msi)</b>: {@code maior.menor.compilação}, com o maior de 0 a 255, o menor de 0 a 255 e a compilação de 0 a 65535 (limites do Windows Installer). Vale a
 *       versão do app como está; fora do limite o build falha com a explicação, em vez de o instalador gravar um número truncado.</li>
 *   <li><b>Linux</b>: números separados por ponto (vale tanto para deb quanto para rpm): a versão do app como está.</li>
 * </ul>
 *
 * Funções puras: não olham o sistema onde rodam (o build diz o sistema).
 */
public final class VersaoDoPacote {

    public enum Sistema { MAC, WINDOWS, LINUX }

    private static final int MAXIMO_MAIOR_E_MENOR_NO_MSI = 255;
    private static final int MAXIMO_COMPILACAO_NO_MSI = 65535;

    private VersaoDoPacote() { }

    /** O valor de {@code --app-version} do jpackage. */
    public static String appVersion(Sistema sistema, VersaoDaCompilacao v) {
        return switch (sistema) {
            case MAC -> (v.major() + 1) + "." + v.minor() + "." + v.numero();
            case WINDOWS -> {
                if (v.major() > MAXIMO_MAIOR_E_MENOR_NO_MSI || v.minor() > MAXIMO_MAIOR_E_MENOR_NO_MSI || v.numero() > MAXIMO_COMPILACAO_NO_MSI) {
                    throw new IllegalArgumentException("A versão " + v.versao() + " não cabe no .msi do Windows (maior e menor até " + MAXIMO_MAIOR_E_MENOR_NO_MSI
                        + ", compilação até " + MAXIMO_COMPILACAO_NO_MSI + "). Ajuste labdesktop.versaoBase.");
                }
                yield v.versao();
            }
            case LINUX -> v.versao();
        };
    }

    /** O {@code CFBundleVersion} do macOS: o número de commits (a versão curta do pacote já está em {@code CFBundleShortVersionString}). */
    public static String cfBundleVersion(VersaoDaCompilacao v) {
        return Integer.toString(v.numero());
    }
}
