package br.com.labdesktop.util;

import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.PastasDoApp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Onde o app guarda as coisas no disco (ARQUITETURA 5.1). Imutável e sem E/S até {@link #prepararPastas()}.
 *
 * <pre>
 * &lt;pasta de dados&gt;/   (só o dono: 0700 no Mac e no Linux, ACL do dono no Windows)
 *     cofre.labcofre  (só o usuário)   cofre.labcofre.bak   cofre.labcofre.tmp   cofre.lock
 *     preferencias.json        estado-avisos.json       memoria-arquivos.json
 *     backups-arquivos/&lt;servidor&gt;/&lt;data-hora&gt;/&lt;caminho&gt;   (cópias de segurança dos arquivos editados)
 * &lt;pasta de logs&gt;/lab-desktop.log
 * </pre>
 *
 * <p>As pastas padrão de cada sistema estão em {@link PastasDoApp}: no Mac {@code ~/Library/Application Support/LabDesktop} e
 * {@code ~/Library/Logs/LabDesktop}; no Windows {@code %APPDATA%\LabDesktop} e {@code %LOCALAPPDATA%\LabDesktop\Logs}; no Linux o
 * {@code XDG_DATA_HOME} e o {@code XDG_STATE_HOME}.
 *
 * <p>{@code --pasta <dir>} troca a pasta de dados (testes, desenvolvimento com {@code cofre-dev}, abrir um
 * backup do cofre em outro lugar). Os logs continuam no lugar padrão, para a verificação de segredos na pasta de logs
 * valer também nesse modo.
 */
public final class CaminhosApp {

    public static final String NOME_COFRE = "cofre.labcofre";
    public static final String OPCAO_PASTA = "--pasta";

    private final Path pastaDados;
    private final Path pastaLogs;
    private final Path pastaNativos;

    private CaminhosApp(Path pastaDados, Path pastaLogs, Path pastaNativos) {
        this.pastaDados = pastaDados.toAbsolutePath().normalize();
        this.pastaLogs = pastaLogs.toAbsolutePath().normalize();
        this.pastaNativos = pastaNativos.toAbsolutePath().normalize();
    }

    /** Pastas escolhidas: os nativos ficam dentro da pasta de dados (testes e {@code --pasta} não escapam do diretório escolhido). */
    private CaminhosApp(Path pastaDados, Path pastaLogs) {
        this(pastaDados, pastaLogs, pastaDados.resolve("nativos"));
    }

    /** Os caminhos de produção, sob {@code user.home}. */
    /** {@code true} se a pasta de dados é a de produção (a mesma de {@link #padrao()}), onde mora o cofre de verdade. */
    public boolean ehPastaPadrao() {
        return pastaDados.equals(pastaDadosPadrao().toAbsolutePath().normalize());
    }

    public static CaminhosApp padrao() {
        return new CaminhosApp(pastaDadosPadrao(), pastaLogsPadrao(), Path.of(PastasDoApp.de(AmbienteDoSistema.detectar()).nativos()));
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
        return Path.of(PastasDoApp.de(AmbienteDoSistema.detectar()).dados());
    }

    private static Path pastaLogsPadrao() {
        return Path.of(PastasDoApp.de(AmbienteDoSistema.detectar()).logs());
    }

    public Path pastaDados() {
        return pastaDados;
    }

    public Path pastaLogs() {
        return pastaLogs;
    }

    /** Onde o PTY e o JNA extraem as bibliotecas nativas: no Windows em {@code %LOCALAPPDATA%} (não viaja no perfil), nos outros dentro da pasta de dados. */
    public Path pastaNativos() {
        return pastaNativos;
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

    /** Onde o gerenciador de arquivos lembra o último caminho por servidor e por projeto (sem segredo). */
    public Path arquivoMemoriaDeArquivos() {
        return pastaDados.resolve("memoria-arquivos.json");
    }

    /** As cópias de segurança locais dos arquivos editados no servidor ({@code <servidor>/<data-hora>/<caminho>}). */
    public Path pastaBackupsDeArquivos() {
        return pastaDados.resolve("backups-arquivos");
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
