package br.com.labdesktop.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * Acha um programa neste computador: nas pastas do {@code PATH} e nas pastas conhecidas da plataforma. Um aplicativo aberto pelo Finder (ou pelo
 * menu Iniciar) herda um PATH mínimo, que não tem {@code /usr/local/bin} nem {@code /opt/homebrew/bin} (onde o Docker e o Homebrew moram no
 * Mac), então só o PATH não basta.
 */
public final class LocalizadorDeProgramas {

    /**
     * No Mac, estes nomes em {@code /usr/bin} são só ATALHOS da Apple: sem as ferramentas de linha de comando instaladas, rodá-los abre o aviso
     * "instalar as ferramentas" do sistema. O app nunca os executa: procura o programa de verdade (Homebrew, ferramentas da Apple, Xcode).
     */
    static final java.util.Set<String> ATALHOS_DO_MAC = java.util.Set.of("git", "make", "clang", "gcc", "python3", "svn", "ld", "cc", "c++");
    private static final Path PASTA_DOS_ATALHOS_DO_MAC = Path.of("/usr/bin");

    private final List<String> pastas;
    private final Predicate<Path> ehExecutavel;
    private final SistemaOperacional sistema;

    /**
     * @param pastas       as pastas onde procurar, na ordem (o PATH primeiro, depois as conhecidas)
     * @param ehExecutavel diz se um caminho é um arquivo executável (em produção, olha o disco)
     */
    public LocalizadorDeProgramas(SistemaOperacional sistema, List<String> pastas, Predicate<Path> ehExecutavel) {
        this.sistema = sistema;
        this.pastas = List.copyOf(pastas);
        this.ehExecutavel = ehExecutavel;
    }

    /** O de produção: o PATH da JVM e as pastas conhecidas da plataforma. */
    public static LocalizadorDeProgramas doSistema(PlataformaLocal plataforma) {
        List<String> todas = new ArrayList<>(plataforma.pastasDoPath(System.getenv("PATH")));
        for (String extra : plataforma.diretoriosDeProgramas()) {
            if (!todas.contains(extra)) {
                todas.add(extra);
            }
        }
        return new LocalizadorDeProgramas(plataforma.sistema(), todas,
            p -> ehProgramaDeVerdade(plataforma.sistema(), p));
    }

    /**
     * No Mac e no Linux, um arquivo comum e executável. No Windows, só a existência: o {@code winget.exe} (e outros "app execution aliases" de
     * {@code WindowsApps}) é um ponto de reparse que {@code Files.isRegularFile} costuma dizer que não é arquivo, embora rode.
     */
    static boolean ehProgramaDeVerdade(SistemaOperacional sistema, Path p) {
        if (sistema == SistemaOperacional.WINDOWS) {
            return Files.exists(p) && !Files.isDirectory(p);
        }
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    /**
     * @param programa nome simples ({@code docker}, {@code git}): nunca um caminho nem uma linha
     */
    public Optional<Path> localizar(String programa) {
        if (programa == null || programa.isBlank() || programa.contains("/") || programa.contains("\\") || programa.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        // No Windows só .exe: um .cmd ou .bat roda pelo cmd.exe, que reinterpreta &, |, ^ e % dos argumentos (injeção por argumento). O docker, o git, o wsl e o winget são .exe.
        List<String> nomes = sistema == SistemaOperacional.WINDOWS ? List.of(programa + ".exe") : List.of(programa);
        for (String pasta : pastas) {
            if (pasta.isBlank()) {
                continue;
            }
            for (String nome : nomes) {
                Path candidato = Path.of(pasta).resolve(nome);
                if (ehAtalhoDoMac(candidato, nome)) {
                    continue;
                }
                if (ehExecutavel.test(candidato)) {
                    return Optional.of(candidato);
                }
            }
        }
        return Optional.empty();
    }

    private boolean ehAtalhoDoMac(Path candidato, String nome) {
        return sistema == SistemaOperacional.MAC && ATALHOS_DO_MAC.contains(nome) && PASTA_DOS_ATALHOS_DO_MAC.equals(candidato.getParent());
    }

    /** As pastas onde se procura (para montar o PATH do processo filho). */
    public List<String> pastas() {
        return pastas;
    }
}
