package br.com.labdesktop.ui.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.emulator.ColorPalette;

/**
 * A paleta do terminal (ARQUITETURA 7.4), nos DOIS temas: fundo e texto iguais aos da superfície do app (o terminal é uma caixa
 * como as outras, não um quadro escuro dentro da janela clara) e um ANSI de 16 cores sóbrias, ajustado para ler bem em cada fundo
 * (no claro as cores são escuras; no escuro, claras). O app nunca usa azul nem ciano vivos: o azul do ANSI vira ARDÓSIA e o ciano
 * vira SÁLVIA, os dois dessaturados. O cubo de 216 cores e o degradê de cinza (índices 16 a 255) seguem o xterm, só que as cores
 * azuladas e cianas do cubo também ficam apagadas: saturação abaixo do corte do {@code TesteSemAzul} ({@link #SATURACAO_DO_CUBO}) e
 * brilho limitado ({@link #BRILHO_MAXIMO_DO_CUBO}), o que dá tons escuros de ardósia, nunca um azul ou ciano claro e pastel. Cor RGB
 * que o programa manda de verdade (truecolor) passa como veio.
 *
 * <p>Os valores de fundo e de texto repetem os tokens {@code Lab.logsBg} e {@code Lab.logsTexto} dos dois temas (um teste confere).
 */
public final class PaletaTerminal extends ColorPalette {

    /** Fundo e texto do terminal no tema escuro (a superfície e o texto do tema). */
    public static final int FUNDO_ESCURO = 0x1D2024;
    public static final int TEXTO_ESCURO = 0xE6E8EA;
    /** Fundo e texto do terminal no tema claro. */
    public static final int FUNDO_CLARO = 0xFFFFFF;
    public static final int TEXTO_CLARO = 0x1B1F23;
    /** Seleção: cinza neutro, com o texto de sempre por cima. */
    public static final int SELECAO_FUNDO_ESCURO = 0x4A4F54;
    public static final int SELECAO_TEXTO_ESCURO = 0xF0F2F4;
    public static final int SELECAO_FUNDO_CLARO = 0xC6CBD0;
    public static final int SELECAO_TEXTO_CLARO = 0x1B1F23;

    /** Fundo do terminal no tema pedido. */
    public static int fundo(boolean escuro) {
        return escuro ? FUNDO_ESCURO : FUNDO_CLARO;
    }

    /** Texto do terminal no tema pedido. */
    public static int texto(boolean escuro) {
        return escuro ? TEXTO_ESCURO : TEXTO_CLARO;
    }

    /** Saturação máxima (0 a 1) das cores azuladas do ANSI de 16 (ardósia e sálvia): a exceção do desenho, só nesses quatro lugares. */
    public static final float LIMITE_DE_SATURACAO = 0.30f;
    /** Saturação das cores azuladas do cubo depois de apagadas: abaixo do corte de 0,15 do {@code TesteSemAzul}, com folga para o arredondamento do RGB. */
    public static final float SATURACAO_DO_CUBO = 0.12f;
    /** Brilho máximo das cores azuladas do cubo: tons escuros, como a ardósia do ANSI (nunca o azul claro de brilho cheio). */
    public static final float BRILHO_MAXIMO_DO_CUBO = 0.60f;
    /** O corte de saturação a partir do qual uma cor de matiz azul ou ciano conta como azul ou ciano (o do {@code TesteSemAzul}). */
    public static final float SATURACAO_DE_CORTE = 0.15f;
    /** Matiz (graus) em que começa e termina o que o app trata como azul ou ciano. */
    public static final float MATIZ_MINIMO = 165f;
    public static final float MATIZ_MAXIMO = 275f;

    /**
     * Os 16 do ANSI no tema escuro: preto, vermelho, verde, amarelo, azul (ardósia), magenta, ciano (sálvia), branco e as versões
     * vivas. O "preto" fica um degrau acima do fundo, como em todo terminal escuro.
     */
    static final int[] ANSI_ESCURO = {
        0x2A2D30, 0xC0584D, 0x7E9F65, 0xB7A66A, 0x6F7F92, 0xA9768F, 0x8FA89A, 0xC9CED3,
        0x747A80, 0xE0786C, 0x9CBF80, 0xCDBD7E, 0x8D9DB3, 0xC792AB, 0xA9C4B5, 0xF0F2F4};

    /**
     * Os 16 do ANSI no tema claro: as mesmas famílias, em tons escuros que passam de 4,5 para 1 sobre o fundo branco (o "branco" é
     * um cinza médio e o "branco vivo" é quase preto: num fundo claro, texto branco não se leria).
     */
    static final int[] ANSI_CLARO = {
        0x1B1F23, 0xB3372F, 0x2B7550, 0x7A6B2A, 0x566676, 0x8D4F73, 0x3F6B5A, 0x60686F,
        0x50565B, 0xC4453D, 0x2F7F58, 0x7F7233, 0x63768A, 0xA05C85, 0x4A7A68, 0x111315};

    private static final int[] NIVEIS_DO_CUBO = {0, 95, 135, 175, 215, 255};

    private final Color[] cores = new Color[256];

    /** A paleta do tema escuro. */
    public PaletaTerminal() {
        this(true);
    }

    public PaletaTerminal(boolean escuro) {
        int[] ansi = escuro ? ANSI_ESCURO : ANSI_CLARO;
        for (int i = 0; i < 16; i++) {
            cores[i] = deRgb(ansi[i]);
        }
        for (int i = 16; i < 232; i++) {
            int k = i - 16;
            int r = NIVEIS_DO_CUBO[k / 36];
            int g = NIVEIS_DO_CUBO[(k / 6) % 6];
            int b = NIVEIS_DO_CUBO[k % 6];
            cores[i] = deRgb(dessaturarSeAzulado((r << 16) | (g << 8) | b));
        }
        for (int i = 232; i < 256; i++) {
            int v = 8 + 10 * (i - 232);
            cores[i] = new Color(v, v, v);
        }
    }

    /** A cor da paleta escura no índice (0 a 255), como RGB. Aberto para os testes. */
    public static int rgbDoIndice(int indice) {
        return rgbDoIndice(indice, true);
    }

    /** A cor da paleta do tema pedido no índice (0 a 255), como RGB. Aberto para os testes. */
    public static int rgbDoIndice(int indice, boolean escuro) {
        Color c = new PaletaTerminal(escuro).cores[indice];
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    @Override
    protected Color getForegroundByColorIndex(int indice) {
        return cores[indice & 0xFF];
    }

    @Override
    protected Color getBackgroundByColorIndex(int indice) {
        return cores[indice & 0xFF];
    }

    /**
     * Uma cor azulada ou ciana (matiz de 165 a 275 graus, saturação a partir do corte) vira um tom escuro e apagado: saturação de
     * {@link #SATURACAO_DO_CUBO} e brilho de no máximo {@link #BRILHO_MAXIMO_DO_CUBO}. Só reduzir a saturação deixaria o azul de brilho
     * cheio virar um azul claro e pastel ({@code #B3B3FF}), que o usuário rejeita.
     */
    static int dessaturarSeAzulado(int rgb) {
        float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float matiz = hsb[0] * 360f;
        if (matiz >= MATIZ_MINIMO && matiz <= MATIZ_MAXIMO && hsb[1] >= SATURACAO_DE_CORTE) {
            return java.awt.Color.HSBtoRGB(hsb[0], SATURACAO_DO_CUBO, Math.min(hsb[2], BRILHO_MAXIMO_DO_CUBO)) & 0xFFFFFF;
        }
        return rgb;
    }

    private static Color deRgb(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
