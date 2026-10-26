package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.Preferencias;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
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

    @Test
    void oAnsiTemAzulECianoSoDessaturados() {
        // 4 = azul (ardósia), 6 = ciano (sálvia), 12 e 14 as versões vivas
        for (int i = 0; i < 16; i++) {
            int rgb = PaletaTerminal.rgbDoIndice(i);
            java.awt.Color cor = new java.awt.Color(rgb);
            float[] hsb = java.awt.Color.RGBtoHSB(cor.getRed(), cor.getGreen(), cor.getBlue(), null);
            float matiz = hsb[0] * 360f;
            boolean azulado = matiz >= PaletaTerminal.MATIZ_MINIMO && matiz <= PaletaTerminal.MATIZ_MAXIMO;
            if (azulado) {
                assertTrue(hsb[1] <= PaletaTerminal.LIMITE_DE_SATURACAO, "ANSI " + i + " azulado e saturado demais: #" + Integer.toHexString(rgb));
            }
        }
        assertEquals(0x6F7F92, PaletaTerminal.rgbDoIndice(4), "ardósia");
        assertEquals(0x8FA89A, PaletaTerminal.rgbDoIndice(6), "sálvia");
    }

    @Test
    void nenhumaCorDeAzulOuCianoVivoNaPaletaInteira() {
        // o mesmo critério do TesteSemAzul (matiz de azul ou ciano e saturação acima do corte), só que sobre os 256 índices
        for (int i = 0; i < 256; i++) {
            java.awt.Color cor = new java.awt.Color(PaletaTerminal.rgbDoIndice(i));
            float[] hsb = java.awt.Color.RGBtoHSB(cor.getRed(), cor.getGreen(), cor.getBlue(), null);
            float matiz = hsb[0] * 360f;
            if (matiz >= PaletaTerminal.MATIZ_MINIMO && matiz <= PaletaTerminal.MATIZ_MAXIMO && hsb[2] >= 0.15f) {
                assertTrue(hsb[1] <= PaletaTerminal.LIMITE_DE_SATURACAO + 0.01f,
                    "índice " + i + " é azul ou ciano vivo: #" + Integer.toHexString(cor.getRGB() & 0xFFFFFF));
            }
        }
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
