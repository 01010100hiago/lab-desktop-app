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
 *   <li>Fonte JetBrains Mono, 13 por padrão; cores neutras nos dois temas ({@link PaletaTerminal}); seleção cinza; links sem azul.</li>
 * </ul>
 *
 * <p>Cmd é do app, o resto é do terminal: os atalhos nativos do JediTerm no macOS (Cmd+C, Cmd+V, Cmd+K, Cmd+setas) ficam como estão, e
 * NENHUM item de menu usa esses aceleradores (o menu da tela do macOS engoliria o atalho).
 */
public final class ConfigTerminal extends DefaultSettingsProvider {

    /** Linhas de histórico do terminal. */
    public static final int LINHAS_DE_HISTORICO = 10_000;

    static {
        FlatJetBrainsMonoFont.install(); // idempotente: o app já a instala ao montar o tema; os testes não
    }

    private static final ColorPalette PALETA_ESCURA = new PaletaTerminal(true);
    private static final ColorPalette PALETA_CLARA = new PaletaTerminal(false);

    private final float tamanhoDaFonte;
    private final boolean opcaoComoMeta;
    private final boolean copiarAoSelecionar;
    private final Font fonte;
    /** O tema em que o terminal está pintando; muda na hora quando o usuário troca o tema do app ({@link #definirEscuro}). */
    private volatile boolean escuro;
    private final TerminalColor textoPadrao = new TerminalColor(() -> jediCor(PaletaTerminal.texto(escuro)));
    private final TerminalColor fundoPadrao = new TerminalColor(() -> jediCor(PaletaTerminal.fundo(escuro)));

    public ConfigTerminal(Preferencias prefs, boolean escuro) {
        this.tamanhoDaFonte = prefs.tamanhoFonteTerminal();
        this.opcaoComoMeta = prefs.opcaoComoMeta();
        this.copiarAoSelecionar = prefs.copiarAoSelecionar();
        this.fonte = new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, Math.round(tamanhoDaFonte));
        this.escuro = escuro;
    }

    /** No tema escuro. */
    public ConfigTerminal(Preferencias prefs) {
        this(prefs, true);
    }

    public ConfigTerminal() {
        this(Preferencias.padrao());
    }

    /** Troca o tema das cores (a paleta, o texto e o fundo, a seleção). Quem pinta precisa repintar depois. */
    public void definirEscuro(boolean novo) {
        this.escuro = novo;
    }

    public boolean escuro() {
        return escuro;
    }

    private static com.jediterm.core.Color jediCor(int rgb) {
        return new com.jediterm.core.Color(r(rgb), g(rgb), b(rgb));
    }

    private static TerminalColor cor(int rgb) {
        return TerminalColor.rgb(r(rgb), g(rgb), b(rgb));
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
        return escuro ? PALETA_ESCURA : PALETA_CLARA;
    }

    /**
     * O estilo de quem não pediu cor nenhuma. Sem isto o JediTerm usa preto e branco da PALETA (índices 0 e 15), e o texto escrito antes
     * do primeiro "reset" do programa (a mensagem do dia do servidor, o primeiro prompt) sairia como caixas brancas no tema escuro. As duas
     * cores são DINÂMICAS (lidas a cada pintura): as células que as guardam seguem o tema quando o usuário o troca.
     */
    @Override
    @SuppressWarnings("deprecation") // é o método que o StyleState do JediTerm 3.76 ainda lê; sem sobrescrevê-lo o padrão é preto e branco da paleta
    public TextStyle getDefaultStyle() {
        return new TextStyle(textoPadrao, fundoPadrao);
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return textoPadrao;
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return fundoPadrao;
    }

    @Override
    public TextStyle getSelectionColor() {
        return escuro
            ? new TextStyle(cor(PaletaTerminal.SELECAO_TEXTO_ESCURO), cor(PaletaTerminal.SELECAO_FUNDO_ESCURO))
            : new TextStyle(cor(PaletaTerminal.SELECAO_TEXTO_CLARO), cor(PaletaTerminal.SELECAO_FUNDO_CLARO));
    }

    @Override
    public boolean useInverseSelectionColor() {
        return false; // com a inversão ligada, a seleção cinza acima seria ignorada
    }

    @Override
    public TextStyle getFoundPatternColor() {
        return escuro ? new TextStyle(cor(0x16181B), cor(0x9AA1A8)) : new TextStyle(cor(0x1B1F23), cor(0xAEB5BB));
    }

    @Override
    public TextStyle getHyperlinkColor() {
        return new TextStyle(cor(escuro ? 0xF0F2F4 : 0x111315), getDefaultBackground());
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
