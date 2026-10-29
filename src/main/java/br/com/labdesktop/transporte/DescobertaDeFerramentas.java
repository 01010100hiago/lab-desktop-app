package br.com.labdesktop.transporte;

import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Descobre onde estão o {@code aws}, o plugin do Session Manager e o {@code cloudflared} neste computador: primeiro as pastas do {@code PATH}, depois as pastas CONHECIDAS de cada
 * sistema. A lista fixa existe porque um aplicativo aberto pelo Finder, pelo menu Iniciar ou por um atalho não herda o PATH do terminal (o Homebrew mora em
 * {@code /opt/homebrew/bin}, e isso não está no PATH de um app aberto pelo Finder).
 *
 * <p>Classe PURA: recebe o ambiente (sistema, pasta pessoal, variáveis) e uma pergunta "este caminho existe e é executável?" (em produção olha o disco; nos testes é um sistema de arquivos
 * de mentira, o que permite provar o Windows e o Linux num Mac). Os caminhos são TEXTO do sistema que o ambiente representa, nunca {@code Path}. No Windows só {@code .exe} (um {@code .cmd}
 * ou {@code .bat} passaria por um interpretador de comandos, e o app nunca usa shell).
 */
public final class DescobertaDeFerramentas implements LocalizadorDeFerramenta {

    private final AmbienteDoSistema ambiente;
    private final Predicate<String> existeEEhExecutavel;

    /**
     * @param ambiente            o computador (o PATH vem de {@code ambiente.variavel("PATH")})
     * @param existeEEhExecutavel diz se um caminho (texto) é um programa que se pode rodar
     */
    public DescobertaDeFerramentas(AmbienteDoSistema ambiente, Predicate<String> existeEEhExecutavel) {
        this.ambiente = ambiente;
        this.existeEEhExecutavel = existeEEhExecutavel;
    }

    /** O de produção: este computador, olhando o disco. */
    public static DescobertaDeFerramentas doSistema() {
        AmbienteDoSistema a = AmbienteDoSistema.detectar();
        return new DescobertaDeFerramentas(a, caminho -> {
            java.nio.file.Path p = java.nio.file.Path.of(caminho);
            return a.sistema() == SistemaOperacional.WINDOWS
                ? java.nio.file.Files.exists(p) && !java.nio.file.Files.isDirectory(p)
                : java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.isExecutable(p);
        });
    }

    public AmbienteDoSistema ambiente() {
        return ambiente;
    }

    @Override
    public Optional<String> localizar(FerramentaExterna ferramenta) {
        String nome = nomeDoArquivo(ferramenta);
        for (String pasta : pastasDeBusca(ferramenta)) {
            if (pasta.isBlank()) {
                continue;
            }
            String candidato = ambiente.juntar(pasta, nome);
            if (existeEEhExecutavel.test(candidato)) {
                return Optional.of(candidato);
            }
        }
        return Optional.empty();
    }

    /** O nome do arquivo no sistema: com {@code .exe} no Windows. */
    public String nomeDoArquivo(FerramentaExterna ferramenta) {
        return ferramenta.nomeBase() + (ambiente.sistema() == SistemaOperacional.WINDOWS ? ".exe" : "");
    }

    /** As pastas onde se procura, em ordem: o PATH e depois as conhecidas do sistema (sem repetir). */
    public List<String> pastasDeBusca(FerramentaExterna ferramenta) {
        List<String> pastas = new ArrayList<>(pastasDoPath());
        for (String p : pastasConhecidas(ferramenta)) {
            if (!pastas.contains(p)) {
                pastas.add(p);
            }
        }
        return pastas;
    }

    /** As pastas do PATH do processo (vazio se não há). */
    public List<String> pastasDoPath() {
        String path = ambiente.variavel("PATH");
        if (path == null) {
            path = ambiente.variavel("Path");
        }
        List<String> l = new ArrayList<>();
        if (path != null) {
            String separador = ambiente.sistema() == SistemaOperacional.WINDOWS ? ";" : ":";
            for (String p : path.split(java.util.regex.Pattern.quote(separador))) {
                String limpa = p.strip();
                if (limpa.length() > 1 && limpa.startsWith("\"") && limpa.endsWith("\"")) {
                    limpa = limpa.substring(1, limpa.length() - 1); // o PATH do Windows às vezes traz pastas entre aspas
                }
                if (!limpa.isEmpty()) {
                    l.add(limpa);
                }
            }
        }
        return l;
    }

    /** As pastas conhecidas do sistema para cada ferramenta (o app aberto por um atalho não tem o PATH do terminal). */
    public List<String> pastasConhecidas(FerramentaExterna ferramenta) {
        return switch (ambiente.sistema()) {
            case MAC -> switch (ferramenta) {
                case AWS -> List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/local/aws-cli");
                case PLUGIN_SSM -> List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/local/sessionmanagerplugin/bin");
                case CLOUDFLARED -> List.of("/opt/homebrew/bin", "/usr/local/bin");
            };
            case WINDOWS -> {
                String pf = valorOu("ProgramFiles", "C:\\Program Files");
                String pf86 = valorOu("ProgramFiles(x86)", "C:\\Program Files (x86)");
                yield switch (ferramenta) {
                    case AWS -> List.of(ambiente.juntar(pf, "Amazon", "AWSCLIV2"));
                    case PLUGIN_SSM -> List.of(ambiente.juntar(pf, "Amazon", "SessionManagerPlugin", "bin"));
                    case CLOUDFLARED -> List.of(ambiente.juntar(pf86, "cloudflared"), ambiente.juntar(pf, "cloudflared"));
                };
            }
            case LINUX -> {
                List<String> base = new ArrayList<>(List.of("/usr/local/bin", "/usr/bin", ambiente.juntar(ambiente.casa(), ".local", "bin"), "/snap/bin"));
                if (ferramenta == FerramentaExterna.PLUGIN_SSM) {
                    base.add("/usr/local/sessionmanagerplugin/bin");
                }
                yield List.copyOf(base);
            }
        };
    }

    private String valorOu(String variavel, String padrao) {
        String v = ambiente.variavel(variavel);
        return v == null ? padrao : v;
    }

    /** A pasta de um caminho completo, em texto (corta no último {@code /} ou {@code \}); vazio se não há. */
    public static String pastaDe(String caminhoCompleto) {
        int i = Math.max(caminhoCompleto.lastIndexOf('/'), caminhoCompleto.lastIndexOf('\\'));
        return i <= 0 ? "" : caminhoCompleto.substring(0, i);
    }
}
