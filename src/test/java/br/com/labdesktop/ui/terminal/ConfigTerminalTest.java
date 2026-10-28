package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.Preferencias;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** As decisões de {@link ConfigTerminal} e {@link PaletaTerminal} (ARQUITETURA 7.4), uma por uma. */
class ConfigTerminalTest {

    private static ConfigTerminal padrao() {
        return new ConfigTerminal(Preferencias.padrao());
    }

    @Test
    void osPadroesDoDesenho() {
        ConfigTerminal c = padrao();
        assertFalse(c.altSendsEscape(), "Option não vira Esc: o ABNT2 gera caracteres com Option");
        assertTrue(c.shiftEnterSendsEscCR(), "Shift+Enter escreve linha nova no Claude");
        assertFalse(c.simulateMouseScrollWithArrowKeysInAlternativeScreen(), "a roda não pode digitar setas no prompt do Claude");
        assertFalse(c.copyOnSelect());
        assertFalse(c.emulateX11CopyPaste());
        assertFalse(c.pasteOnMiddleMouseClick());
        assertFalse(c.audibleBell(), "sino mudo");
        assertEquals(10_000, c.getBufferMaxLinesCount());
        assertFalse(c.getTypeAheadSettings().isEnabled(), "o eco previsto atrapalha o tmux e o Claude");
        assertFalse(c.useInverseSelectionColor(), "a seleção âmbar precisa valer");
    }

    @Test
    void asPreferenciasMudamSoOQueDevem() {
        assertTrue(new ConfigTerminal(Preferencias.padrao().comOpcaoComoMeta(true)).altSendsEscape());
        ConfigTerminal copiar = new ConfigTerminal(Preferencias.padrao().comCopiarAoSelecionar(true));
        assertTrue(copiar.copyOnSelect());
        assertFalse(copiar.emulateX11CopyPaste(), "copiar ao selecionar não liga o colar do botão do meio");
        assertEquals(16f, new ConfigTerminal(Preferencias.padrao().comTamanhoFonteTerminal(16)).getTerminalFontSize());
    }

    @Test
    void fonteEJetBrainsMono13() {
        ConfigTerminal c = padrao();
        assertEquals(FlatJetBrainsMonoFont.FAMILY, c.getTerminalFont().getFamily());
        assertEquals(13, c.getTerminalFont().getSize());
        assertEquals(13f, c.getTerminalFontSize());
        assertTrue(c.getTerminalFont().canDisplay('a'));
    }

    @Test
    void fundoETextoSaoOsDaSuperficieDeCadaTema() {
        ConfigTerminal escuro = new ConfigTerminal(Preferencias.padrao(), true);
        assertEquals(new Color(0x1D, 0x20, 0x24), escuro.getDefaultBackground().toColor());
        assertEquals(new Color(0xE6, 0xE8, 0xEA), escuro.getDefaultForeground().toColor());
        assertEquals(new Color(0x4A, 0x4F, 0x54), escuro.getSelectionColor().getBackground().toColor(), "seleção cinza, sem âmbar");
        ConfigTerminal claro = new ConfigTerminal(Preferencias.padrao(), false);
        assertEquals(new Color(0xFF, 0xFF, 0xFF), claro.getDefaultBackground().toColor());
        assertEquals(new Color(0x1B, 0x1F, 0x23), claro.getDefaultForeground().toColor());
        assertEquals(new Color(0xC6, 0xCB, 0xD0), claro.getSelectionColor().getBackground().toColor());
    }

    @Test
    void trocarOTemaTrocaAPaletaENaHoraNoMesmoConfig() {
        ConfigTerminal c = new ConfigTerminal(Preferencias.padrao(), true);
        PaletaTerminal escura = (PaletaTerminal) c.getTerminalColorPalette();
        assertEquals(new Color(0xE6, 0xE8, 0xEA), c.getDefaultForeground().toColor());
        c.definirEscuro(false);
        assertFalse(c.escuro());
        assertEquals(new Color(0x1B, 0x1F, 0x23), c.getDefaultForeground().toColor());
        assertTrue(c.getTerminalColorPalette() != escura, "outra paleta");
        assertEquals(new Color(0xB3, 0x37, 0x2F), c.getTerminalColorPalette().getForeground(TerminalColor.index(1)), "vermelho do tema claro");
    }

    @Test
    @SuppressWarnings("deprecation")
    void oEstiloPadraoNaoUsaOPretoEBrancoDaPaletaEAcompanhaOTema() {
        // sem isto o texto escrito antes do primeiro "reset" do programa sairia com os índices 0 e 15 da paleta (caixas brancas no escuro)
        ConfigTerminal c = new ConfigTerminal(Preferencias.padrao(), true);
        var estilo = c.getDefaultStyle();
        assertFalse(estilo.getForeground().isIndexed());
        assertFalse(estilo.getBackground().isIndexed());
        assertEquals(new Color(0xE6, 0xE8, 0xEA), estilo.getForeground().toColor());
        assertEquals(new Color(0x1D, 0x20, 0x24), estilo.getBackground().toColor());
        c.definirEscuro(false); // o MESMO estilo (as células o guardam) passa a valer no tema claro
        assertEquals(new Color(0x1B, 0x1F, 0x23), estilo.getForeground().toColor());
        assertEquals(new Color(0xFF, 0xFF, 0xFF), estilo.getBackground().toColor());
    }

    @Test
    void oAnsiDoTemaClaroLeBemSobreOFundoBranco() {
        for (int i = 1; i < 16; i++) {
            if (i == 7 || i == 8) {
                continue; // cinzas de apoio ("branco" e "preto vivo"), lidos como texto secundário
            }
            assertTrue(contraste(PaletaTerminal.rgbDoIndice(i, false), PaletaTerminal.FUNDO_CLARO) >= 4.5,
                "ANSI " + i + " no claro: " + contraste(PaletaTerminal.rgbDoIndice(i, false), PaletaTerminal.FUNDO_CLARO));
        }
        for (int i : new int[] {7, 8}) {
            assertTrue(contraste(PaletaTerminal.rgbDoIndice(i, false), PaletaTerminal.FUNDO_CLARO) >= 4.5, "ANSI " + i + " no claro");
        }
        assertTrue(contraste(PaletaTerminal.TEXTO_CLARO, PaletaTerminal.FUNDO_CLARO) >= 7);
        assertTrue(contraste(PaletaTerminal.TEXTO_ESCURO, PaletaTerminal.FUNDO_ESCURO) >= 7);
    }

    @Test
    void oAnsiDoTemaEscuroLeBemSobreOFundoEscuroForaOsDoisPretos() {
        for (int i = 1; i < 16; i++) {
            if (i == 8) {
                assertTrue(contraste(PaletaTerminal.rgbDoIndice(i), PaletaTerminal.FUNDO_ESCURO) >= 3.5, "o preto vivo (texto de apoio) ainda se lê");
                continue;
            }
            assertTrue(contraste(PaletaTerminal.rgbDoIndice(i), PaletaTerminal.FUNDO_ESCURO) >= 3.5,
                "ANSI " + i + " no escuro: " + contraste(PaletaTerminal.rgbDoIndice(i), PaletaTerminal.FUNDO_ESCURO));
        }
    }

    /** Contraste WCAG entre duas cores RGB. */
    static double contraste(int a, int b) {
        double la = luminancia(a);
        double lb = luminancia(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminancia(int rgb) {
        double[] c = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        for (int i = 0; i < 3; i++) {
            double v = c[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** O critério REAL do TesteSemAzul (matiz de 165 a 275 graus, saturação de 0,15 e brilho de 0,15 para cima). */
    private static boolean ehAzulOuCiano(java.awt.Color c) {
        float[] hsb = java.awt.Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        float matiz = hsb[0] * 360f;
        return matiz >= 165f && matiz <= 275f && hsb[1] >= 0.15f && hsb[2] >= 0.15f;
    }

    /** As únicas cores da paleta que o critério acima pegaria: o azul e o ciano do ANSI (a ardósia), a exceção do desenho. */
    private static final java.util.Set<Integer> ANSI_AZULADOS = java.util.Set.of(4, 12);

    @Test
    void ansiDoTemaClaroTambemNaoTemAzulNemCianoForaDaArdosia() {
        for (int i = 0; i < 16; i++) {
            java.awt.Color cor = new java.awt.Color(PaletaTerminal.rgbDoIndice(i, false));
            if (ANSI_AZULADOS.contains(i)) {
                float[] hsb = java.awt.Color.RGBtoHSB(cor.getRed(), cor.getGreen(), cor.getBlue(), null);
                assertTrue(hsb[1] <= PaletaTerminal.LIMITE_DE_SATURACAO && hsb[2] <= 0.75f, "ardósia do claro: ANSI " + i);
            } else {
                assertFalse(ehAzulOuCiano(cor), "ANSI " + i + " do claro é azul ou ciano");
            }
        }
    }

    @Test
    void oVarredorEnxergaOAzulDeBrilhoCheioEOPastel() {
        // sanidade do próprio teste: o que o cubo do xterm traz de fábrica E o pastel que uma correção só de saturação deixava são pegos
        for (int rgb : new int[] {0x0000FF, 0x0087FF, 0x00FFFF, 0x5F5FFF, 0xB3B3FF, 0xB3CFFF, 0xB3FFFF}) {
            assertTrue(ehAzulOuCiano(new java.awt.Color(rgb)), "#" + Integer.toHexString(rgb));
        }
        assertFalse(ehAzulOuCiano(new java.awt.Color(PaletaTerminal.rgbDoIndice(21))), "o azul puro do cubo foi apagado");
    }

    @Test
    void oAnsiTemArdosiaESalviaOsDoisDessaturados() {
        // 4 = azul (ardósia), 12 = azul vivo, 6 = ciano (sálvia, do lado verde), 14 = ciano vivo
        for (int i : new int[] {4, 12}) {
            java.awt.Color cor = new java.awt.Color(PaletaTerminal.rgbDoIndice(i));
            float[] hsb = java.awt.Color.RGBtoHSB(cor.getRed(), cor.getGreen(), cor.getBlue(), null);
            assertTrue(hsb[1] <= PaletaTerminal.LIMITE_DE_SATURACAO, "ANSI " + i + " saturado demais");
            assertTrue(hsb[2] <= 0.75f, "ANSI " + i + " claro demais para ardósia");
        }
        for (int i = 0; i < 16; i++) {
            if (!ANSI_AZULADOS.contains(i)) {
                assertFalse(ehAzulOuCiano(new java.awt.Color(PaletaTerminal.rgbDoIndice(i))), "ANSI " + i + " é azul ou ciano");
            }
        }
        assertEquals(0x6F7F92, PaletaTerminal.rgbDoIndice(4), "ardósia");
        assertEquals(0x8FA89A, PaletaTerminal.rgbDoIndice(6), "sálvia");
    }

    @Test
    void nenhumaDasDuzentasECinquentaESeisCoresEAzulOuCianoPeloCriterioDoTesteSemAzul() {
        List<String> ruins = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            java.awt.Color cor = new java.awt.Color(PaletaTerminal.rgbDoIndice(i));
            if (ehAzulOuCiano(cor) && !ANSI_AZULADOS.contains(i)) {
                ruins.add(i + " = #" + Integer.toHexString(cor.getRGB() & 0xFFFFFF));
            }
        }
        assertTrue(ruins.isEmpty(), "azul ou ciano pelo critério do TesteSemAzul (fora a ardósia do ANSI): " + ruins);
    }

    @Test
    void asCoresAzuladasDoCuboSaoEscurasENuncaPastel() {
        for (int i = 16; i < 232; i++) {
            int original = cuboDoXterm(i);
            java.awt.Color o = new java.awt.Color(original);
            if (!ehAzulOuCiano(o)) {
                assertEquals(original, PaletaTerminal.rgbDoIndice(i), "o que não é azul passa como o xterm: " + i);
                continue;
            }
            java.awt.Color cor = new java.awt.Color(PaletaTerminal.rgbDoIndice(i));
            float[] hsb = java.awt.Color.RGBtoHSB(cor.getRed(), cor.getGreen(), cor.getBlue(), null);
            assertTrue(hsb[2] <= PaletaTerminal.BRILHO_MAXIMO_DO_CUBO + 0.01f, "índice " + i + " claro demais: #" + Integer.toHexString(cor.getRGB() & 0xFFFFFF));
        }
        int[] pastel = {0xB3B3FF, 0xB3CFFF, 0xB3FFFF};
        for (int p : pastel) {
            for (int i = 16; i < 232; i++) {
                assertTrue(PaletaTerminal.rgbDoIndice(i) != p, "o pastel #" + Integer.toHexString(p) + " não pode estar no índice " + i);
            }
        }
        assertTrue(((PaletaTerminal.rgbDoIndice(147) >> 16) & 0xFF) < 0xAF, "o azul claro #afafff do xterm (índice 147) virou um tom escuro");
    }

    /** A cor do cubo do xterm (sem apagar nada) no índice 16 a 231. */
    private static int cuboDoXterm(int indice) {
        int[] n = {0, 95, 135, 175, 215, 255};
        int k = indice - 16;
        return (n[k / 36] << 16) | (n[(k / 6) % 6] << 8) | n[k % 6];
    }

    @Test
    void ocuboEOCinzaSeguemOXtermForaDoAzul() {
        assertEquals(0xFF0000, PaletaTerminal.rgbDoIndice(196), "o vermelho do cubo é o do xterm");
        assertEquals(0x000000, PaletaTerminal.rgbDoIndice(16));
        assertEquals(0x080808, PaletaTerminal.rgbDoIndice(232));
        assertEquals(0xEEEEEE, PaletaTerminal.rgbDoIndice(255));
        // o azul puro do cubo (índice 21, #0000ff) perde saturação
        int azul = PaletaTerminal.rgbDoIndice(21);
        assertTrue(azul != 0x0000FF);
        assertEquals(true, (azul & 0xFF) > ((azul >> 16) & 0xFF), "continua azulado, só que apagado");
    }

    @Test
    void paletaResolveIndicesEDeixaORgbDoProgramaPassar() {
        ConfigTerminal c = padrao();
        PaletaTerminal p = (PaletaTerminal) c.getTerminalColorPalette();
        assertEquals(new Color(0xC0, 0x58, 0x4D), p.getForeground(TerminalColor.index(1)));
        assertEquals(new Color(0xC0, 0x58, 0x4D), p.getBackground(TerminalColor.index(1)));
        // truecolor que o programa manda de verdade passa como veio
        assertEquals(new Color(1, 2, 3), p.getForeground(TerminalColor.rgb(1, 2, 3)));
    }
}
