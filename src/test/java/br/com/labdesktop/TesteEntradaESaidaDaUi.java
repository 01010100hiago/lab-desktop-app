package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * "Rede e disco nunca na EDT" (ARQUITETURA 8.1) em forma de varredura: nenhum fonte de {@code ui} abre arquivo, socket,
 * processo ou cliente HTTP, nem dorme ou bloqueia esperando outra thread. Quem precisa de E/S chama a camada de
 * domínio por {@code Tarefas}/{@code Fundo}, que a executa fora da EDT e devolve o resultado nela.
 *
 * <p>As exceções são poucas, nomeadas e justificadas aqui; o teste falha se uma delas deixar de existir (a lista não
 * vira um depósito de entradas mortas).
 */
class TesteEntradaESaidaDaUi {

    private static final Path UI = Path.of("src", "main", "java", "br", "com", "labdesktop", "ui");

    private static final Map<String, Pattern> PROIBIDOS = Map.of(
        "dorme a thread", Pattern.compile("\\bThread\\.sleep\\b"),
        "arquivo ou pasta", Pattern.compile("\\b(Files\\.|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)\\b"),
        "rede", Pattern.compile("\\b(HttpClient|HttpURLConnection|new Socket|ServerSocket|URL\\.openStream|openConnection)\\b"),
        "processo", Pattern.compile("\\b(ProcessBuilder|Runtime\\.getRuntime)\\b"),
        "bloqueia esperando outra thread", Pattern.compile("\\.join\\(\\)|\\.get\\(\\s*\\d+\\s*,"),
        "sistema (Desktop)", Pattern.compile("\\bDesktop\\.getDesktop\\b"));

    /**
     * Arquivo e o que ele pode usar, com o motivo. {@code PedidoDeCodigoPorDialogo.pedir} roda na thread do
     * agente/túnel (virtual), NUNCA na EDT, e espera a resposta do usuário; {@code Navegador} é o único ponto que toca o
     * {@code Desktop}, e o faz dentro de {@code Fundo.emFundo}.
     */
    private static final Map<String, String> PERMITIDOS = Map.of(
        "dialogos/PedidoDeCodigoPorDialogo.java", "bloqueia esperando outra thread",
        "Navegador.java", "sistema (Desktop)");

    private static List<Path> fontes() throws IOException {
        try (Stream<Path> s = Files.walk(UI)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Código sem comentários (a documentação cita "Files" e "Thread.sleep" para explicar a regra). */
    private static List<String> codigo(Path arquivo) throws IOException {
        List<String> saida = new ArrayList<>();
        boolean emBloco = false;
        for (String linha : Files.readAllLines(arquivo)) {
            String t = linha.strip();
            if (emBloco) {
                emBloco = !t.contains("*/");
                continue;
            }
            if (t.startsWith("/*")) {
                emBloco = !t.contains("*/");
                continue;
            }
            if (!t.startsWith("//") && !t.startsWith("*")) {
                saida.add(t);
            }
        }
        return saida;
    }

    @Test
    void aInterfaceNaoFazEntradaESaidaNemEsperaPorOutraThread() throws IOException {
        List<String> violacoes = new ArrayList<>();
        for (Path fonte : fontes()) {
            String relativo = UI.relativize(fonte).toString().replace('\\', '/');
            for (String linha : codigo(fonte)) {
                for (var e : PROIBIDOS.entrySet()) {
                    boolean liberado = e.getKey().equals(PERMITIDOS.get(relativo));
                    if (!liberado && e.getValue().matcher(linha).find()) {
                        violacoes.add(relativo + " (" + e.getKey() + "): " + linha);
                    }
                }
            }
        }
        assertTrue(violacoes.isEmpty(), "E/S ou espera dentro de ui (use Tarefas/Fundo):\n" + String.join("\n", violacoes));
    }

    @Test
    void asExcecoesAindaPrecisamDeExistir() throws IOException {
        for (var e : PERMITIDOS.entrySet()) {
            Path fonte = UI.resolve(e.getKey());
            assertTrue(Files.isRegularFile(fonte), "exceção para um arquivo que não existe mais: " + e.getKey());
            Pattern padrao = PROIBIDOS.get(e.getValue());
            assertTrue(codigo(fonte).stream().anyMatch(l -> padrao.matcher(l).find()),
                e.getKey() + " não usa mais '" + e.getValue() + "': tire a exceção");
        }
    }

    @Test
    void oVarredorEnxergaOQueProcura() {
        // Sanidade: os padrões pegam o que dizem pegar (um regex quebrado deixaria o teste verde para sempre).
        assertTrue(PROIBIDOS.get("dorme a thread").matcher("Thread.sleep(10);").find());
        assertTrue(PROIBIDOS.get("arquivo ou pasta").matcher("var l = Files.readAllLines(p);").find());
        assertTrue(PROIBIDOS.get("rede").matcher("HttpClient c = HttpClient.newHttpClient();").find());
        assertTrue(PROIBIDOS.get("processo").matcher("new ProcessBuilder(\"ls\")").find());
        assertTrue(PROIBIDOS.get("bloqueia esperando outra thread").matcher("return f.join();").find());
        assertTrue(PROIBIDOS.get("bloqueia esperando outra thread").matcher("f.get(5, SECONDS)").find());
        assertTrue(PROIBIDOS.get("sistema (Desktop)").matcher("Desktop.getDesktop().browse(u)").find());
    }
}
