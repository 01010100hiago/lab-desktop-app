package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/**
 * O rótulo do app. Três garantias que um {@code JLabel} puro não dá:
 * <ol>
 *   <li><b>Texto do servidor nunca vira HTML</b> (ARQUITETURA 8.2): o Swing renderiza HTML em rótulos cujo texto
 *       começa com {@code <html>}. Aqui {@code html.disable} fica ligado e todo texto passa por
 *       {@link TextoSeguro#limparLinha} (controles, bidi e {@code <html} neutralizados).</li>
 *   <li><b>A cor acompanha o tema</b>: com {@link #corDoToken}, a cor é lida do token a CADA pintura, então claro,
 *       escuro e sistema trocam sem refazer componente (e o {@code Icones.de}, que segue o texto, também).</li>
 *   <li><b>Trunca com reticências</b> em vez de empurrar o layout, com {@link #truncavel}.</li>
 * </ol>
 */
public class Rotulo extends JLabel {

    private static final long serialVersionUID = 1L;

    private String tokenDaCor;
    private boolean truncavel;
    private boolean negrito;
    private String familia = Fontes.TEXTO;
    private float nominal = Fontes.NOMINAL_PADRAO;

    @SuppressWarnings("this-escape") // configurar o componente no construtor é o jeito do Swing
    public Rotulo() {
        super();
        putClientProperty("html.disable", Boolean.TRUE);
    }

    @SuppressWarnings("this-escape")
    public Rotulo(String texto) {
        this();
        setText(texto);
    }

    @Override
    public void setText(String texto) {
        super.setText(texto == null ? "" : TextoSeguro.limparLinha(texto));
    }

    /** A dica também é texto do servidor às vezes: nunca HTML (o tooltip do Swing renderiza {@code <html>}). */
    @Override
    public void setToolTipText(String texto) {
        super.setToolTipText(texto == null || texto.isBlank() ? null : TextoSeguro.limparLinha(texto));
    }

    /** A cor vem do token a cada pintura ({@code Tokens.TEXTO2}, {@code Tokens.OK}...). */
    public Rotulo corDoToken(String token) {
        this.tokenDaCor = token;
        repaint();
        return this;
    }

    @Override
    public Color getForeground() {
        return tokenDaCor != null ? Tokens.cor(tokenDaCor) : super.getForeground();
    }

    /** O texto longo é cortado com reticências quando o layout dá menos largura que o preferido. */
    public Rotulo truncavel() {
        this.truncavel = true;
        return this;
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension d = super.getMinimumSize();
        return truncavel ? new Dimension(0, d.height) : d;
    }

    public Rotulo negrito() {
        this.negrito = true;
        refazerAFonte();
        return this;
    }

    /** O tamanho NOMINAL (o número de sempre); quem decide os pontos de verdade é {@link Fontes}, com o fator do usuário. */
    public Rotulo tamanho(float pontos) {
        this.nominal = pontos;
        refazerAFonte();
        return this;
    }

    /** A fonte monoespaçada do app (JetBrains Mono), no tamanho nominal dado. */
    public Rotulo mono(float pontos) {
        this.familia = Fontes.MONO;
        this.nominal = pontos;
        refazerAFonte();
        return this;
    }

    private void refazerAFonte() {
        Fontes.aplicar(this, familia, negrito ? Font.BOLD : Font.PLAIN, nominal);
    }

    public Rotulo icone(Icon icone) {
        setIcon(icone);
        return this;
    }

    public Rotulo aDireita() {
        setHorizontalAlignment(SwingConstants.RIGHT);
        return this;
    }

    public Rotulo aoCentro() {
        setHorizontalAlignment(SwingConstants.CENTER);
        return this;
    }

    // ---- atalhos de estilo do painel ----

    /** Texto comum. */
    public static Rotulo texto(String texto) {
        return new Rotulo(texto);
    }

    /** Texto secundário (cinza quente), no tamanho pequeno do painel. */
    public static Rotulo secundario(String texto) {
        return new Rotulo(texto).corDoToken(Tokens.TEXTO2);
    }

    /** Dica ou legenda (ainda mais discreta), 12 pt. */
    public static Rotulo dica(String texto) {
        return new Rotulo(texto).corDoToken(Tokens.TEXTO3).tamanho(12f);
    }

    /** Título de seção, 17 pt semibold ({@code h2} do painel). */
    public static Rotulo titulo(String texto) {
        Rotulo r = new Rotulo(texto);
        r.familia = Fontes.TITULO;
        r.negrito = true;
        r.nominal = 17f;
        r.refazerAFonte();
        return r;
    }

    /** Rótulo de campo, 12,5 pt medium. */
    public static Rotulo rotuloDeCampo(String texto) {
        return new Rotulo(texto).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    }

    /** Texto mono (impressão digital, comando). */
    public static Rotulo monoTexto(String texto, float pontos) {
        return new Rotulo(texto).mono(pontos);
    }
}
