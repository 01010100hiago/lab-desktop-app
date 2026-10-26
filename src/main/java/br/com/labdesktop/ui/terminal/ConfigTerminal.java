package br.com.labdesktop.ui.terminal;

import br.com.labdesktop.util.Preferencias;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.TerminalTypeAheadSettings;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import java.awt.Font;

/**
 * As configurações do JediTerm do app (ARQUITETURA 7.4). Cada decisão aqui existe por um motivo:
 *
 * <ul>
 *   <li>{@code altSendsEscape} DESLIGADO: o padrão do JediTerm engoliria os caracteres que o ABNT2 gera com Option; só liga com a
 *       preferência "Option como Meta".</li>
 *   <li>{@code shiftEnterSendsEscCR} ligado: Shift+Enter escreve uma linha nova no prompt do Claude.</li>
 *   <li>Rolagem por seta na tela alternativa DESLIGADA: a roda do mouse digitaria setas no prompt do Claude.</li>
 *   <li>Copiar ao selecionar DESLIGADO (copiar sozinho sobrescreve a área de transferência sem avisar), e colar com o botão do meio
 *       também; só a preferência liga o primeiro.</li>
 *   <li>Sino mudo; buffer de 10.000 linhas; type-ahead desligado (o eco previsto atrapalha o tmux e o Claude).</li>
 *   <li>Fonte JetBrains Mono, 13 por padrão; cores quentes ({@link PaletaTerminal}); seleção âmbar; links sem azul.</li>
 * </ul>
 *
 * <p>Cmd é do app, o resto é do terminal: os atalhos nativos do JediTerm no macOS (Cmd+C, Cmd+V, Cmd+K, Cmd+setas) ficam como estão, e
 * NENHUM item de menu usa esses aceleradores (o menu da tela do macOS engoliria o atalho).
 */
public final class ConfigTerminal extends DefaultSettingsProvider {

    /** Linhas de histórico do terminal. */
    public static final int LINHAS_DE_HISTORICO = 10_000;

    private static final ColorPalette PALETA = new PaletaTerminal();
    private static final TerminalColor TEXTO = TerminalColor.rgb(r(PaletaTerminal.TEXTO), g(PaletaTerminal.TEXTO), b(PaletaTerminal.TEXTO));
    private static final TerminalColor FUNDO = TerminalColor.rgb(r(PaletaTerminal.FUNDO), g(PaletaTerminal.FUNDO), b(PaletaTerminal.FUNDO));

    private final float tamanhoDaFonte;
    private final boolean opcaoComoMeta;
    private final boolean copiarAoSelecionar;
    private final Font fonte;

    public ConfigTerminal(Preferencias prefs) {
        FlatJetBrainsMonoFont.install(); // idempotente: o app já a instala ao montar o tema; os testes não
        this.tamanhoDaFonte = prefs.tamanhoFonteTerminal();
        this.opcaoComoMeta = prefs.opcaoComoMeta();
        this.copiarAoSelecionar = prefs.copiarAoSelecionar();
        this.fonte = new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, Math.round(tamanhoDaFonte));
    }

    public ConfigTerminal() {
        this(Preferencias.padrao());
    }

    private static int r(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int g(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int b(int rgb) {
        return rgb & 0xFF;
    }

    @Override
    public Font getTerminalFont() {
        return fonte;
    }

    @Override
    public float getTerminalFontSize() {
        return tamanhoDaFonte;
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return PALETA;
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return TEXTO;
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return FUNDO;
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(TerminalColor.rgb(r(PaletaTerminal.SELECAO_TEXTO), g(PaletaTerminal.SELECAO_TEXTO), b(PaletaTerminal.SELECAO_TEXTO)),
            TerminalColor.rgb(r(PaletaTerminal.SELECAO_FUNDO), g(PaletaTerminal.SELECAO_FUNDO), b(PaletaTerminal.SELECAO_FUNDO)));
    }

    @Override
    public boolean useInverseSelectionColor() {
        return false; // com a inversão ligada, a seleção âmbar acima seria ignorada
    }

    @Override
    public TextStyle getFoundPatternColor() {
        return new TextStyle(TerminalColor.rgb(0x12, 0x11, 0x0E), TerminalColor.rgb(0x8A, 0x6E, 0x34));
    }

    @Override
    public TextStyle getHyperlinkColor() {
        return new TextStyle(TerminalColor.rgb(0xE3, 0xB8, 0x66), FUNDO);
    }

    @Override
    public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
        return HyperlinkStyle.HighlightMode.HOVER;
    }

    @Override
    public boolean altSendsEscape() {
        return opcaoComoMeta;
    }

    @Override
    public boolean shiftEnterSendsEscCR() {
        return true;
    }

    @Override
    public boolean simulateMouseScrollWithArrowKeysInAlternativeScreen() {
        return false;
    }

    @Override
    public boolean copyOnSelect() {
        return copiarAoSelecionar;
    }

    @Override
    public boolean pasteOnMiddleMouseClick() {
        return false;
    }

    @Override
    public boolean emulateX11CopyPaste() {
        return false;
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return LINHAS_DE_HISTORICO;
    }

    @Override
    public TerminalTypeAheadSettings getTypeAheadSettings() {
        return new TerminalTypeAheadSettings(false, 100L, TextStyle.EMPTY);
    }
}
