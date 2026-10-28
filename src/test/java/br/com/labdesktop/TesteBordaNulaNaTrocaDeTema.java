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
 * {@code setBorder(null)} num componente do Swing deixa a borda "órfã": quando o tema troca ({@code FlatLaf.updateUI}), o FlatLaf põe de volta a borda padrão do
 * componente (o contorno em volta das áreas rolantes, a margem do texto), e a tela muda de lugar uns pixels com a janela aberta. É a causa de a tela "pular" ao trocar o
 * tema. A borda vazia de verdade ({@code BorderFactory.createEmptyBorder()}) fica. {@code TrocaDeTemaNaJanelaTest} prova o efeito nas telas; aqui o vigia
 * barato para tela nova.
 */
class TesteBordaNulaNaTrocaDeTema {

    private static final Pattern PROIBIDO = Pattern.compile("\\bset(Viewport)?Border\\(\\s*null\\s*\\)");

    @Test
    void nenhumComponenteDaInterfaceZeraABordaComNulo() throws IOException {
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
        assertTrue(achados.isEmpty(), "use BorderFactory.createEmptyBorder() em vez de setBorder(null) (o tema troca e a borda padrão volta):\n" + String.join("\n", achados));
    }
}
