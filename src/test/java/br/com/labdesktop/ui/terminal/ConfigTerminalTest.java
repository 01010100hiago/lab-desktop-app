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
    void coresQuentesFundoETextoDosLogs() {
        ConfigTerminal c = padrao();
        assertEquals(new Color(0x12, 0x11, 0x0E), c.getDefaultBackground().toColor());
        assertEquals(new Color(0xD8, 0xD2, 0xC6), c.getDefaultForeground().toColor());
        var selecao = c.getSelectionColor();
        assertEquals(new Color(0xC9, 0x9A, 0x3F), selecao.getBackground().toColor(), "seleção âmbar");
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
