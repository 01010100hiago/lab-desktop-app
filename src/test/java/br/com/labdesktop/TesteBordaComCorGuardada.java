package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code BorderFactory.createLineBorder(Tokens.cor(...))} e {@code createMatteBorder(..., Tokens.cor(...))} GUARDAM a cor do tema que valia na construção: o usuário troca claro e
 * escuro com a janela aberta e o contorno fica com a cor de antes (O divisor da Ajuda, a borda da tabela de atalhos, o editor de arquivos). A borda do
 * app que tem cor de tema é {@code Bordas.contorno(token)} ou {@code Bordas.lados(...)}, que leem o token na pintura. Aqui o vigia barato para tela nova.
 * ({@code TrocaDeTemaEmAjustesPopupsEDialogosTest} prova o efeito.)
 */
class TesteBordaComCorGuardada {

    private static final Pattern PROIBIDO = Pattern.compile("create(Line|Matte)Border\\(.*(Tokens\\.cor|UIManager\\.getColor|new Color)");

    @Test
    void nenhumaBordaDaInterfaceGuardaACorDoTema() throws IOException {
        List<String> achados = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src", "main", "java", "br", "com", "labdesktop", "ui"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                int n = 0;
                for (String linha : Files.readAllLines(p)) {
                    n++;
                    String t = linha.strip();
                    if (!t.startsWith("//") && !t.startsWith("*") && PROIBIDO.matcher(t).find()) {
                        achados.add(p + ":" + n + ": " + t);
                    }
                }
            }
        }
        assertTrue(achados.isEmpty(), "use Bordas.contorno(token) ou Bordas.lados(...), que leem o token na pintura:\n" + String.join("\n", achados));
    }
}
