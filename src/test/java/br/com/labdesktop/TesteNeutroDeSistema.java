package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * O substituto barato de "não depender de um sistema": varre {@code src/main/java} e reprova uma dependência de Mac, de Unix ou de um sistema só FORA dos
 * poucos arquivos que existem justamente para isso. Para cada padrão há a lista dos arquivos onde ele é permitido; qualquer outro lugar que o use falha com o arquivo e a linha. Quem
 * precisar de algo por sistema acrescenta uma peça em {@code plataforma} (ou usa as que já existem) em vez de espalhar um {@code if}.
 */
class TesteNeutroDeSistema {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");

    /** padrão -> arquivos (sufixo do caminho) onde ele é permitido, e por quê. */
    private static final Map<String, Set<String>> PERMITIDOS = Map.ofEntries(
        Map.entry("\"defaults\"", Set.of("plataforma/LeitorDeAparencia.java")),
        Map.entry("osascript", Set.of()),
        Map.entry("pbcopy", Set.of()),
        Map.entry("pbpaste", Set.of()),
        Map.entry("lineSeparator\\(", Set.of()),
        Map.entry("File\\.separator", Set.of()),
        Map.entry("getMenuShortcutKeyMask", Set.of()),
        Map.entry("META_DOWN_MASK|META_MASK", Set.of("ui/componentes/Teclas.java")),
        Map.entry("FlatMac(Light|Dark)Laf", Set.of("ui/GerenciadorTema.java")),
        Map.entry("\"apple\\.", Set.of("Principal.java")),
        Map.entry("\"~/Library|Library/Application Support|Library/Logs", Set.of("plataforma/PastasDoApp.java")),
        Map.entry("PosixFilePermission|PosixFileAttribute", Set.of("util/ProtecaoPosix.java", "util/SenhaDeTeste.java", "arquivos/ClienteSftpLocal.java", "arquivos/ClienteSftpMina.java")),
        Map.entry("getProperty\\(\"os\\.name\"", Set.of("plataforma/SistemaOperacional.java", "plataforma/DescricaoDoSistema.java", "AutoTeste.java", "local/SondaReal.java")),
        Map.entry("\"/usr/bin", Set.of("local/LocalizadorDeProgramas.java", "local/PlataformaLocal.java")),
        Map.entry("\"/bin/(sh|bash)\"", Set.of("AutoTeste.java", "terminal/TransporteLocal.java")),
        Map.entry("\"cmd(\\.exe)?\"|\"powershell(\\.exe)?\"", Set.of("local/ComandosLocais.java", "terminal/TransporteLocal.java", "AutoTeste.java")),
        Map.entry("new ProcessBuilder\\(", Set.of("plataforma/ConsultaPorProcesso.java", "local/ProcessosLocais.java", "instalador/MontadorDoPacote.java")),
        Map.entry("Runtime\\.getRuntime\\(\\)\\.exec", Set.of()));

    private static List<String> codigoDe(Path arquivo) throws IOException {
        List<String> saida = new ArrayList<>();
        boolean emBloco = false;
        for (String linha : Files.readAllLines(arquivo)) {
            String t = linha.strip();
            if (emBloco) {
                if (t.contains("*/")) {
                    emBloco = false;
                }
                continue;
            }
            if (t.startsWith("/*")) {
                emBloco = !t.contains("*/");
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            saida.add(linha);
        }
        return saida;
    }

    @Test
    void nenhumaDependenciaDeSistemaForaDosArquivosQueExistemParaIsso() throws IOException {
        List<String> violacoes = new ArrayList<>();
        int arquivos = 0;
        try (Stream<Path> s = Files.walk(RAIZ)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                arquivos++;
                String caminho = RAIZ.relativize(f).toString().replace('\\', '/');
                List<String> linhas = codigoDe(f);
                for (var e : PERMITIDOS.entrySet()) {
                    if (e.getValue().contains(caminho)) {
                        continue;
                    }
                    Pattern p = Pattern.compile(e.getKey());
                    for (String l : linhas) {
                        if (p.matcher(l).find()) {
                            violacoes.add(caminho + " usa /" + e.getKey() + "/: " + l.strip());
                        }
                    }
                }
            }
        }
        assertTrue(arquivos > 300, "o varredor viu o código (" + arquivos + " arquivos)");
        assertTrue(violacoes.isEmpty(), "Dependência de sistema fora da peça própria (ARQUITETURA 11.4):\n" + String.join("\n", violacoes));
    }

    @Test
    void oVarredorReconheceUmaViolacaoDeVerdade() {
        // sanidade do próprio teste: o padrão pega o que deve
        assertTrue(Pattern.compile("\"defaults\"").matcher("new ProcessBuilder(\"defaults\", \"read\")").find());
        assertTrue(Pattern.compile("new ProcessBuilder\\(").matcher("Process p = new ProcessBuilder(cmd)").find());
    }
}
