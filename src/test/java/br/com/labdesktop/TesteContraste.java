package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.EstiloDeLaf;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.terminal.PaletaTerminal;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contraste WCAG dos pares texto sobre superfície NOS DOIS temas: nenhum par de texto abaixo de 4,5, e o
 * que não é texto (o anel de foco, o preenchimento da ação primária) abaixo de 3. A tabela dos números sai em
 * {@code build/contraste.txt} (os valores de referência do contraste). Também confere que o fundo e o texto do
 * terminal, que {@link PaletaTerminal} repete como constantes, são os tokens dos logs de cada tema.
 */
class TesteContraste {

    private static final double TEXTO = 4.5;
    private static final double COMPONENTE = 3.0;

    @BeforeAll
    static void prepara() {
        System.setProperty("java.awt.headless", "true");
        GerenciadorTema.preparar();
    }

    static double luminancia(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) {
            v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static double contraste(Color a, Color b) {
        double la = luminancia(a);
        double lb = luminancia(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** A cor (com opacidade) pintada em cima de um fundo opaco. */
    static Color sobre(Color frente, Color fundo) {
        float a = frente.getAlpha() / 255f;
        return new Color(Math.round(frente.getRed() * a + fundo.getRed() * (1 - a)), Math.round(frente.getGreen() * a + fundo.getGreen() * (1 - a)),
            Math.round(frente.getBlue() * a + fundo.getBlue() * (1 - a)));
    }

    /** O estilo do LaF do teste em curso: cada teste de cor roda nos DOIS (Mac e genérico, o do Windows e do Linux) com os mesmos tokens. */
    private static EstiloDeLaf estilo = EstiloDeLaf.MAC;

    private static void instalar(Tema tema) throws Exception {
        SwingUtilities.invokeAndWait(() -> new GerenciadorTema(tema, false, false, estilo).instalar());
    }

    private record Par(String texto, String superficie, double minimo) { }

    private static List<Par> pares() {
        List<Par> l = new ArrayList<>();
        String[] superficies = {Tokens.BG, Tokens.BG2, Tokens.SUPERFICIE, Tokens.SUPERFICIE2, Tokens.SUPERFICIE3};
        for (String texto : new String[] {Tokens.TEXTO, Tokens.TEXTO2, Tokens.TEXTO3}) {
            for (String s : superficies) {
                l.add(new Par(texto, s, TEXTO));
            }
            l.add(new Par(texto, Tokens.DESTAQUE_SUAVE, TEXTO)); // linha selecionada, selo de destaque
        }
        for (String s : superficies) {
            l.add(new Par(Tokens.DESTAQUE, s, TEXTO)); // link, aba escolhida, ícone de destaque
            for (String estado : new String[] {Tokens.OK, Tokens.ATENCAO, Tokens.PERIGO}) {
                l.add(new Par(estado, s, TEXTO));
            }
        }
        l.add(new Par(Tokens.DESTAQUE, Tokens.DESTAQUE_SUAVE, TEXTO));
        l.add(new Par(Tokens.OK, Tokens.OK_SUAVE, TEXTO));
        l.add(new Par(Tokens.ATENCAO, Tokens.ATENCAO_SUAVE, TEXTO));
        l.add(new Par(Tokens.PERIGO, Tokens.PERIGO_SUAVE, TEXTO));
        l.add(new Par(Tokens.DESTAQUE_TEXTO, Tokens.DESTAQUE, TEXTO)); // texto da ação primária
        l.add(new Par(Tokens.DESTAQUE_TEXTO, Tokens.DESTAQUE_FORTE, TEXTO)); // e dela com o mouse em cima
        l.add(new Par(Tokens.DESTAQUE_TEXTO, Tokens.PERIGO, TEXTO)); // botão do Enter de uma pergunta que apaga: texto de destaque sobre vermelho
        l.add(new Par(Tokens.DESTAQUE_TEXTO, Tokens.PERIGO_FORTE, TEXTO)); // e dele com o mouse em cima
        l.add(new Par(Tokens.TEXTO3, Tokens.SUPERFICIE3, TEXTO)); // botão desabilitado: texto apagado (também o ícone) sobre o fundo do desabilitado
        l.add(new Par(Tokens.LOGS_TEXTO, Tokens.LOGS_BG, TEXTO));
        l.add(new Par(Tokens.LOGS_TEXTO2, Tokens.LOGS_BG, TEXTO));
        l.add(new Par(Tokens.FOCO, Tokens.SUPERFICIE, COMPONENTE)); // anel de foco: componente, não texto
        l.add(new Par(Tokens.FOCO, Tokens.BG, COMPONENTE));
        l.add(new Par(Tokens.DESTAQUE, Tokens.SUPERFICIE, COMPONENTE)); // preenchimento da ação primária contra a superfície
        return l;
    }

    private static Color cor(String token, Color fundoOpaco) {
        Color c = Tokens.cor(token);
        return c.getAlpha() < 255 ? sobre(c, fundoOpaco) : c;
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    @ParameterizedTest
    @EnumSource(EstiloDeLaf.class)
    void osParesDeTextoPassamDe4ePontoCincoNosDoisTemas(EstiloDeLaf estiloDoTeste) throws Exception {
        estilo = estiloDoTeste;
        StringBuilder tabela = new StringBuilder();
        List<String> ruins = new ArrayList<>();
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            instalar(tema);
            Color base = Tokens.cor(Tokens.SUPERFICIE);
            tabela.append("== ").append(tema).append('\n');
            double pior = 99;
            for (Par p : pares()) {
                Color fundo = cor(p.superficie(), base);
                Color texto = cor(p.texto(), fundo);
                double c = contraste(texto, fundo);
                tabela.append(String.format("%-22s %s sobre %-22s %s  %5.2f  (minimo %.1f)%n", p.texto(), hex(texto), p.superficie(), hex(fundo), c, p.minimo()));
                if (p.minimo() == TEXTO) {
                    pior = Math.min(pior, c);
                }
                if (c < p.minimo()) {
                    ruins.add(String.format("%s: %s sobre %s = %.2f (minimo %.1f)", tema, p.texto(), p.superficie(), c, p.minimo()));
                }
            }
            tabela.append(String.format("pior par de texto em %s: %.2f%n", tema, pior));
        }
        Path saida = Path.of("build", estiloDoTeste == EstiloDeLaf.MAC ? "contraste.txt" : "contraste-generico.txt");
        try {
            Files.createDirectories(saida.getParent());
            Files.writeString(saida, tabela.toString());
        } catch (IOException e) {
            // só para consulta: o teste vale sem o arquivo
        }
        assertTrue(ruins.isEmpty(), "Pares abaixo do mínimo de contraste:\n" + String.join("\n", ruins));
    }

    @ParameterizedTest
    @EnumSource(EstiloDeLaf.class)
    void fundoETextoDoTerminalSaoOsTokensDosLogsDeCadaTema(EstiloDeLaf e) throws Exception {
        estilo = e;
        instalar(Tema.ESCURO);
        assertEquals(PaletaTerminal.FUNDO_ESCURO, Tokens.cor(Tokens.LOGS_BG).getRGB() & 0xFFFFFF);
        assertEquals(PaletaTerminal.TEXTO_ESCURO, Tokens.cor(Tokens.LOGS_TEXTO).getRGB() & 0xFFFFFF);
        assertEquals(PaletaTerminal.FUNDO_ESCURO, Tokens.cor(Tokens.SUPERFICIE).getRGB() & 0xFFFFFF, "o terminal é a superfície do tema");
        instalar(Tema.CLARO);
        assertEquals(PaletaTerminal.FUNDO_CLARO, Tokens.cor(Tokens.LOGS_BG).getRGB() & 0xFFFFFF);
        assertEquals(PaletaTerminal.TEXTO_CLARO, Tokens.cor(Tokens.LOGS_TEXTO).getRGB() & 0xFFFFFF);
        assertEquals(PaletaTerminal.FUNDO_CLARO, Tokens.cor(Tokens.SUPERFICIE).getRGB() & 0xFFFFFF);
    }
}
