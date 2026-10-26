package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * O substituto barato dos subprojetos Gradle (ARQUITETURA 4.3): varre {@code src/main/java} e falha se a
 * camada de domínio importar a interface gráfica, ou se {@code cofre}, {@code ssh} e {@code api} importarem
 * {@code ui}.
 */
class TesteCamadas {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");

    /**
     * util, seguranca, cofre, ssh, api, servidor, arquivos (o domínio do gerenciador de arquivos, sobre SFTP) e terminal (o domínio do
     * terminal: transporte, ciclo de vida, classificador e portão de entrada; o JediTerm e o Swing ficam em {@code ui.terminal}).
     */
    private static final Set<String> DOMINIO = Set.of("util", "seguranca", "cofre", "ssh", "api", "servidor", "arquivos", "terminal");

    private static final Pattern GRAFICOS = Pattern.compile(
        "\\b(javax\\.swing|java\\.awt|com\\.formdev|com\\.jediterm)\\b");
    /**
     * Única exceção: {@code java.awt.desktop} é API de integração com o sistema (sono do Mac, tela cheia, Dock),
     * sem componente gráfico. O {@code MonitorDeSono} do pacote {@code ssh} usa o {@code SystemSleepListener}
     * (ARQUITETURA 6.3), que mora ali.
     */
    private static final Pattern PERMITIDO_AWT = Pattern.compile("\\bjava\\.awt\\.desktop\\b");
    private static final Pattern UI = Pattern.compile("\\bbr\\.com\\.labdesktop\\.ui\\b");

    private static List<Path> fontesDe(String pacote) throws IOException {
        Path pasta = RAIZ.resolve(pacote);
        if (!Files.isDirectory(pasta)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(pasta)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** Linhas de código: ignora comentários de linha e de bloco (a documentação pode citar "Swing"). */
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
            saida.add(t);
        }
        return saida;
    }

    @Test
    void camadaDeDominioNaoImportaInterfaceGrafica() throws IOException {
        List<String> violacoes = new ArrayList<>();
        int arquivos = 0;
        for (String pacote : DOMINIO) {
            for (Path fonte : fontesDe(pacote)) {
                arquivos++;
                for (String linha : codigoDe(fonte)) {
                    String semPermitido = PERMITIDO_AWT.matcher(linha).replaceAll("");
                    if (GRAFICOS.matcher(semPermitido).find()) {
                        violacoes.add(fonte + ": " + linha);
                    }
                }
            }
        }
        assertTrue(arquivos > 0, "não encontrou nenhum fonte do domínio em " + RAIZ.toAbsolutePath());
        assertTrue(violacoes.isEmpty(), "Domínio importando interface gráfica:\n" + String.join("\n", violacoes));
    }

    @Test
    void cofreSshEApiNaoImportamUi() throws IOException {
        List<String> violacoes = new ArrayList<>();
        for (String pacote : List.of("cofre", "ssh", "api", "util", "seguranca", "servidor", "arquivos", "terminal")) {
            for (Path fonte : fontesDe(pacote)) {
                for (String linha : codigoDe(fonte)) {
                    if (UI.matcher(linha).find()) {
                        violacoes.add(fonte + ": " + linha);
                    }
                }
            }
        }
        assertTrue(violacoes.isEmpty(), "Pacote de domínio importando ui:\n" + String.join("\n", violacoes));
    }

    @Test
    void oPrincipalNaoEDominioEPodeUsarSwing() throws IOException {
        // Sanidade do próprio teste: o varredor enxerga Swing onde ele existe de verdade.
        boolean achou = codigoDe(RAIZ.resolve("Principal.java")).stream().anyMatch(l -> GRAFICOS.matcher(l).find());
        assertTrue(achou, "o varredor deveria achar o import de Swing/FlatLaf no Principal");
        assertFalse(DOMINIO.contains("ui"));
    }
}
