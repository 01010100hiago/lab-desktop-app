package br.com.labdesktop.ui.tema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * As fontes EMBUTIDAS (Inter e JetBrains Mono, as mesmas no Mac, no Windows e no Linux: nada depende de fonte do sistema) desenham todo caractere que os textos do app usam. Varre os
 * literais de texto do código-fonte e confere cada caractere fora do ASCII nas duas fontes: se um dia um texto usar um símbolo que elas não têm, o teste diz qual (e o Java, sem
 * isso, o desenharia como um quadradinho no Windows, onde o fallback de fonte é outro).
 */
class FontesEmbutidasCobremOTextoDoAppTest {

    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    @Test
    void interEJetBrainsMonoDesenhamTodoCaractereDosTextosDoApp() throws IOException {
        FlatInterFont.install();
        FlatJetBrainsMonoFont.install();
        Font inter = new Font(FlatInterFont.FAMILY, Font.PLAIN, 14);
        Font mono = new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, 14);
        assertEquals(FlatInterFont.FAMILY, inter.getFamily(), "a Inter embutida está registrada");
        assertEquals(FlatJetBrainsMonoFont.FAMILY, mono.getFamily(), "a JetBrains Mono embutida está registrada");

        TreeSet<Integer> usados = new TreeSet<>();
        try (Stream<Path> s = Files.walk(Path.of("src", "main", "java"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(p);
                Matcher m = LITERAL.matcher(fonte);
                while (m.find()) {
                    m.group().codePoints().filter(c -> c > 0x7E && c >= 0xA0).forEach(usados::add); // acentos do português e símbolos; controles ficam de fora
                }
            }
        }
        assertTrue(usados.size() > 20, "o varredor viu os acentos e os símbolos do app: " + usados.size());
        List<String> semInter = new ArrayList<>();
        List<String> semMono = new ArrayList<>();
        for (int c : usados) {
            if (!inter.canDisplay(c)) {
                semInter.add(String.format("U+%04X %s", c, new String(Character.toChars(c))));
            }
            if (!mono.canDisplay(c)) {
                semMono.add(String.format("U+%04X %s", c, new String(Character.toChars(c))));
            }
        }
        assertEquals(List.of(), semInter, "a Inter embutida não desenha estes caracteres dos textos do app");
        assertEquals(List.of(), semMono, "a JetBrains Mono embutida não desenha estes caracteres dos textos do app (os textos técnicos usam a mono)");
    }
}
