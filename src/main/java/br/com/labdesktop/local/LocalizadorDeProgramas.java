package br.com.labdesktop.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Acha um programa neste computador: nas pastas do {@code PATH} e nas pastas conhecidas da plataforma. Um aplicativo aberto pelo Finder (ou pelo
 * menu Iniciar) herda um PATH mínimo, que não tem {@code /usr/local/bin} nem {@code /opt/homebrew/bin} (onde o Docker e o Homebrew moram no
 * Mac), então só o PATH não basta.
 */
public final class LocalizadorDeProgramas {

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
            p -> Files.isRegularFile(p) && (plataforma.sistema() == SistemaOperacional.WINDOWS || Files.isExecutable(p)));
    }

    /**
     * @param programa nome simples ({@code docker}, {@code git}): nunca um caminho nem uma linha
     */
    public Optional<Path> localizar(String programa) {
        if (programa == null || programa.isBlank() || programa.contains("/") || programa.contains("\\") || programa.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        List<String> nomes = sistema == SistemaOperacional.WINDOWS ? List.of(programa + ".exe", programa + ".cmd", programa + ".bat") : List.of(programa);
        for (String pasta : pastas) {
            if (pasta.isBlank()) {
                continue;
            }
            for (String nome : nomes) {
                Path candidato = Path.of(pasta).resolve(nome);
                if (ehExecutavel.test(candidato)) {
                    return Optional.of(candidato);
                }
            }
        }
        return Optional.empty();
    }

    /** As pastas onde se procura (para montar o PATH do processo filho). */
    public List<String> pastas() {
        return pastas;
    }
}
