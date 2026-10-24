package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * "Nunca azul nem ciano" (ARQUITETURA 8.2 e 2.1): varre TODAS as cores do {@code UIManager} nos dois temas e
 * reprova as de matiz azul ou ciano. A paleta ANSI do terminal é a única exceção prevista, e ela não
 * mora no {@code UIManager}.
 *
 * <p>O FlatLaf de fábrica é azul (acento {@code #2675BF}, seleção, foco, link) e o tema do macOS ainda lê a cor de
 * destaque do sistema; os arquivos {@code FlatMacLightLaf.properties} e {@code FlatMacDarkLaf.properties} do app
 * trocam tudo isso por bronze e âmbar. Este teste é a prova de que não sobrou nada.
 */
class TesteSemAzul {

    /** Matiz de 165 a 275 graus cobre do verde-água ao violeta azulado, passando por ciano e azul. */
    private static final float MATIZ_MIN = 165f;
    private static final float MATIZ_MAX = 275f;
    /** Cinzas com um leve viés frio (saturação baixa) não contam: não leem como azul. */
    private static final float SATURACAO_MIN = 0.15f;
    private static final float BRILHO_MIN = 0.15f;

    @BeforeAll
    static void prepara() {
        System.setProperty("java.awt.headless", "true");
        GerenciadorTema.preparar();
    }

    static boolean ehAzulOuCiano(Color c) {
        if (c.getAlpha() < 8) {
            return false; // praticamente transparente
        }
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        float matiz = hsb[0] * 360f;
        return matiz >= MATIZ_MIN && matiz <= MATIZ_MAX && hsb[1] >= SATURACAO_MIN && hsb[2] >= BRILHO_MIN;
    }

    private static Map<String, Color> todasAsCores() {
        Map<String, Color> cores = new TreeMap<>();
        for (Object chave : Collections.list(UIManager.getDefaults().keys())) {
            if (chave instanceof String nome) {
                Object valor;
                try {
                    valor = UIManager.get(nome);
                } catch (RuntimeException e) {
                    continue; // valor preguiçoso que depende de componente: não é cor fixa
                }
                if (valor instanceof Color cor) {
                    cores.put(nome, cor);
                }
            }
        }
        return cores;
    }

    private static void instalar(Tema tema) throws Exception {
        SwingUtilities.invokeAndWait(() -> new GerenciadorTema(tema, false).instalar());
    }

    private static List<String> ofensores() {
        List<String> ruins = new ArrayList<>();
        todasAsCores().forEach((nome, cor) -> {
            if (ehAzulOuCiano(cor)) {
                ruins.add(String.format("%s = #%02X%02X%02X (alfa %d)", nome, cor.getRed(), cor.getGreen(),
                    cor.getBlue(), cor.getAlpha()));
            }
        });
        return ruins;
    }

    @Test
    void temaClaroNaoTemAzulNemCiano() throws Exception {
        instalar(Tema.CLARO);
        assertTrue(todasAsCores().size() > 300, "o varredor deveria ver centenas de cores do FlatLaf");
        List<String> ruins = ofensores();
        assertTrue(ruins.isEmpty(), "Cores azuis ou ciano no tema CLARO:\n" + String.join("\n", ruins));
    }

    @Test
    void temaEscuroNaoTemAzulNemCiano() throws Exception {
        instalar(Tema.ESCURO);
        assertTrue(todasAsCores().size() > 300);
        List<String> ruins = ofensores();
        assertTrue(ruins.isEmpty(), "Cores azuis ou ciano no tema ESCURO:\n" + String.join("\n", ruins));
    }

    @Test
    void oVarredorReconheceAzulECianoDeVerdade() {
        // Sanidade do próprio teste: o que o FlatLaf traz de fábrica TEM de ser pego.
        assertTrue(ehAzulOuCiano(new Color(0x2675BF)), "acento padrão do FlatLaf");
        assertTrue(ehAzulOuCiano(new Color(0x007AFF)), "azul do macOS");
        assertTrue(ehAzulOuCiano(new Color(0x55BEF0)), "ciano do macOS");
        assertTrue(ehAzulOuCiano(new Color(0x59ADC4)), "verde-água do macOS");
        assertTrue(ehAzulOuCiano(new Color(0x4B6EAF)), "azul do tema escuro do FlatLaf");
        // E o que é nosso não pode ser pego.
        for (int cor : new int[] {0x87621A, 0xD4A64C, 0x2A7A54, 0x5FBE8E, 0xB03F37, 0xE8807A, 0xF5F2EB, 0x0E0D0B, 0x1C1A16}) {
            assertEquals(false, ehAzulOuCiano(new Color(cor)), "falso positivo em #" + Integer.toHexString(cor));
        }
    }

    @Test
    void osDoisTemasDefinemTodosOsTokensDoPainel() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            instalar(tema);
            for (String chave : Tokens.TODAS) {
                assertNotNull(UIManager.getColor(chave), "token " + chave + " ausente no tema " + tema);
            }
        }
    }

    @Test
    void osTokensDeAcentoEEstadoSaoOsDoPainel() throws Exception {
        instalar(Tema.CLARO);
        assertEquals(new Color(0x87621A), Tokens.cor(Tokens.ACENTO));
        assertEquals(new Color(0xF5F2EB), Tokens.cor(Tokens.BG));
        assertEquals(new Color(0xB03F37), Tokens.cor(Tokens.PERIGO));
        instalar(Tema.ESCURO);
        assertEquals(new Color(0xD4A64C), Tokens.cor(Tokens.ACENTO));
        assertEquals(new Color(0x0E0D0B), Tokens.cor(Tokens.BG));
        assertEquals(new Color(0xE8807A), Tokens.cor(Tokens.PERIGO));
    }
}
