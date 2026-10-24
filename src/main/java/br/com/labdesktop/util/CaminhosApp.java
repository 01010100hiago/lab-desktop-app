package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Onde o app guarda as coisas no disco (ARQUITETURA 5.1). Imutável e sem E/S até {@link #prepararPastas()}.
 *
 * <pre>
 * ~/Library/Application Support/LabDesktop/   (0700)
 *     cofre.labcofre  (0600)   cofre.labcofre.bak   cofre.labcofre.tmp   cofre.lock
 *     preferencias.json        estado-avisos.json
 * ~/Library/Logs/LabDesktop/lab-desktop.log
 * </pre>
 *
 * <p>{@code --pasta <dir>} troca a pasta de dados (testes, desenvolvimento com {@code cofre-dev}, abrir um
 * backup do cofre em outro lugar). Os logs continuam no lugar padrão, para a verificação de segredos em
 * {@code ~/Library/Logs/LabDesktop} valer também nesse modo.
 */
public final class CaminhosApp {

    public static final String NOME_COFRE = "cofre.labcofre";
    public static final String OPCAO_PASTA = "--pasta";

    private final Path pastaDados;
    private final Path pastaLogs;

    private CaminhosApp(Path pastaDados, Path pastaLogs) {
        this.pastaDados = pastaDados.toAbsolutePath().normalize();
        this.pastaLogs = pastaLogs.toAbsolutePath().normalize();
    }

    /** Os caminhos de produção, sob {@code user.home}. */
    /** {@code true} se a pasta de dados é a de produção (a mesma de {@link #padrao()}), onde mora o cofre de verdade. */
    public boolean ehPastaPadrao() {
        return pastaDados.equals(pastaDadosPadrao().toAbsolutePath().normalize());
    }

    public static CaminhosApp padrao() {
        return new CaminhosApp(pastaDadosPadrao(), pastaLogsPadrao());
    }

    /** Pasta de dados escolhida, logs no lugar padrão (é o que {@code --pasta} faz). */
    public static CaminhosApp comPasta(Path pastaDados) {
        return new CaminhosApp(Objects.requireNonNull(pastaDados, "pastaDados"), pastaLogsPadrao());
    }

    /** Pasta de dados e de logs escolhidas (testes: nada fora do diretório temporário do teste). */
    public static CaminhosApp de(Path pastaDados, Path pastaLogs) {
        return new CaminhosApp(Objects.requireNonNull(pastaDados, "pastaDados"),
            Objects.requireNonNull(pastaLogs, "pastaLogs"));
    }

    /**
     * Lê {@code --pasta <dir>} ou {@code --pasta=<dir>} dos argumentos; sem a opção, o padrão. Caminho relativo
     * vale a partir da pasta de trabalho (no {@code ./gradlew run}, a raiz do projeto).
     */
    public static CaminhosApp deArgumentos(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals(OPCAO_PASTA)) {
                    if (i + 1 >= args.length || args[i + 1].isBlank()) {
                        throw new IllegalArgumentException("--pasta pede um diretório.");
                    }
                    return comPasta(Path.of(args[i + 1]));
                }
                if (a.startsWith(OPCAO_PASTA + "=")) {
                    String valor = a.substring(OPCAO_PASTA.length() + 1);
                    if (valor.isBlank()) {
                        throw new IllegalArgumentException("--pasta pede um diretório.");
                    }
                    return comPasta(Path.of(valor));
                }
            }
        }
        return padrao();
    }

    private static Path pastaDadosPadrao() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "LabDesktop");
    }

    private static Path pastaLogsPadrao() {
        return Path.of(System.getProperty("user.home"), "Library", "Logs", "LabDesktop");
    }

    public Path pastaDados() {
        return pastaDados;
    }

    public Path pastaLogs() {
        return pastaLogs;
    }

    public Path arquivoCofre() {
        return pastaDados.resolve(NOME_COFRE);
    }

    public Path arquivoCofreBak() {
        return pastaDados.resolve(NOME_COFRE + ".bak");
    }

    public Path arquivoCofreTmp() {
        return pastaDados.resolve(NOME_COFRE + ".tmp");
    }

    public Path arquivoTrava() {
        return pastaDados.resolve("cofre.lock");
    }

    public Path arquivoPreferencias() {
        return pastaDados.resolve("preferencias.json");
    }

    public Path arquivoEstadoAvisos() {
        return pastaDados.resolve("estado-avisos.json");
    }

    public Path arquivoLog() {
        return pastaLogs.resolve("lab-desktop.log");
    }

    /** Cria a pasta de dados com 0700 (ou corrige se estiver aberta demais). */
    public void prepararPastas() throws IOException {
        Permissoes.criarPastaPrivada(pastaDados);
    }

    /** Cria a pasta de logs com 0700. */
    public void prepararPastaDeLogs() throws IOException {
        Permissoes.criarPastaPrivada(pastaLogs);
    }

    @Override
    public String toString() {
        return "CaminhosApp[dados=" + pastaDados + ", logs=" + pastaLogs + "]";
    }
}
