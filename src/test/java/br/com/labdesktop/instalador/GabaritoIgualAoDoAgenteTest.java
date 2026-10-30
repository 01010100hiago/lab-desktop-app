package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.projetos.GabaritoDoProjeto;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * UMA fonte de verdade para o esqueleto sem guia: o texto que {@link GabaritoDoProjeto} escreve no computador local é, letra por letra, o que o
 * agente completo (o que cria projetos) escreve com {@code guia_ia: false}. Esse agente NÃO é o que o app empacota (o agente que o app instala
 * só lê e não cria projeto), então o teste só roda quando se aponta o {@code painel.js} dele:
 * {@code ./gradlew test -Plabdesktop.painelDoLab=/caminho/do/painel.js --tests '*GabaritoIgualAoDoAgente*'} (ou a variável de ambiente
 * {@code LABDESKTOP_PAINEL_DO_LAB}). O teste tira a função {@code arquivosSemGuiaIa} desse {@code painel.js}, roda no {@code node} e compara arquivo por
 * arquivo. Sem o caminho, ou sem {@code node} neste computador, pula; o texto do gabarito em si continua preso por {@code GabaritoDoProjetoTest}.
 */
class GabaritoIgualAoDoAgenteTest {

    private static Path achar(String nome) {
        List<String> pastas = new java.util.ArrayList<>(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"));
        String path = System.getenv("PATH");
        if (path != null) {
            pastas.addAll(List.of(path.split(java.io.File.pathSeparator)));
        }
        for (String p : pastas) {
            for (String exe : List.of(nome, nome + ".exe")) {
                Path c = Path.of(p, exe);
                if (Files.isRegularFile(c) && Files.isExecutable(c)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** O texto da função e da constante do .gitignore, achados no código do agente por contagem de chaves (sem confundir as de dentro das aspas). */
    static String trecho(String js, String inicio) {
        int i = js.indexOf(inicio);
        assertTrue(i >= 0, "o painel.js do agente não tem " + inicio);
        int abre = js.indexOf('{', i);
        int nivel = 0;
        char aspa = 0;
        for (int k = abre; k < js.length(); k++) {
            char c = js.charAt(k);
            if (aspa != 0) {
                if (c == '\\') {
                    k++;
                } else if (c == aspa) {
                    aspa = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                aspa = c;
            } else if (c == '/' && k + 1 < js.length() && js.charAt(k + 1) == '*') {
                k = js.indexOf("*/", k) + 1;
            } else if (c == '/' && k + 1 < js.length() && js.charAt(k + 1) == '/') {
                k = js.indexOf('\n', k);
            } else if (c == '{') {
                nivel++;
            } else if (c == '}' && --nivel == 0) {
                return js.substring(i, k + 1);
            }
        }
        throw new AssertionError("função sem fim: " + inicio);
    }

    @Test
    void oTextoDoLocalEIgualAoQueOAgenteEscreveComGuiaIaFalse() throws Exception {
        Path node = achar("node");
        assumeTrue(node != null, "sem node neste computador");
        String caminho = System.getProperty("labdesktop.painelDoLab", System.getenv("LABDESKTOP_PAINEL_DO_LAB"));
        assumeTrue(caminho != null && !caminho.isBlank() && Files.isRegularFile(Path.of(caminho)),
            "sem o painel.js do agente de projetos (-Plabdesktop.painelDoLab=...): o app não o empacota");
        String painel = Files.readString(Path.of(caminho), StandardCharsets.UTF_8);
        Matcher ignore = Pattern.compile("var GITIGNORE_DO_PROJETO = .*;\n").matcher(painel);
        assertTrue(ignore.find(), "a constante do .gitignore está no código do agente");
        String script = ignore.group() + "\n" + trecho(painel, "function arquivosSemGuiaIa(") + "\n"
            + "console.log(JSON.stringify(arquivosSemGuiaIa(process.argv[1], process.argv[2])));\n";
        for (String nome : List.of("demo", "meu-app-2", "a", "123", "1e3", "0x1f", "true", "null")) { // os que parecem número ou booleano no YAML vão entre aspas
            Process p = new ProcessBuilder(node.toString(), "-e", script, nome, "2026-10-28").redirectErrorStream(true).start();
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, p.exitValue(), saida);
            Map<String, String> doAgente = Json.mapper().readValue(saida.strip(), new TypeReference<LinkedHashMap<String, String>>() { });
            Map<String, String> doApp = GabaritoDoProjeto.arquivos(nome, LocalDate.of(2026, 10, 28));
            assertEquals(List.copyOf(doApp.keySet()), List.copyOf(doAgente.keySet()), "os mesmos arquivos, na mesma ordem");
            for (String arquivo : doApp.keySet()) {
                assertEquals(doApp.get(arquivo), doAgente.get(arquivo), nome + "/" + arquivo + ": o app e o agente escrevem textos diferentes");
            }
        }
    }
}
