package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.tema.Tokens;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Font;
import javax.swing.Icon;
import javax.swing.JButton;

/**
 * O botão do app, nas variantes do painel web ({@code .btn-prim}, {@code .btn-sec}, {@code .btn-perigo},
 * {@code .btn-fantasma}, {@code .btn-mini}, {@code .btn-icone}). O visual vem do {@code FlatLaf} por
 * {@code FlatClientProperties.STYLE} com referências aos tokens ({@code $Lab.acento}), então claro, escuro e
 * sistema trocam sozinhos. {@code html.disable} fica ligado (o texto de um botão pode vir do servidor).
 *
 * <p>O ícone (que segue a cor do texto do componente) enxerga a cor certa porque {@link #getForeground()} devolve o
 * token da variante: num botão primário, ícone e texto saem brancos sobre o bronze, nos dois temas.
 */
public final class Botao extends JButton {

    private static final long serialVersionUID = 1L;

    /** As variantes. */
    public enum Variante {
        PRIMARIO("background: $Lab.acento; foreground: $Lab.acentoTexto; borderColor: $Lab.acento; "
            + "hoverBackground: $Lab.acentoForte; pressedBackground: $Lab.acentoForte; "
            + "hoverBorderColor: $Lab.acentoForte; focusedBorderColor: $Lab.acentoBrilho; "
            + "disabledBackground: $Lab.superficie3; disabledText: $Lab.texto3; disabledBorderColor: $Lab.borda",
            Tokens.ACENTO_TEXTO, true),
        SECUNDARIO("", Tokens.TEXTO, false),
        PERIGO("foreground: $Lab.perigo; hoverBackground: $Lab.perigoSuave; hoverBorderColor: $Lab.perigo",
            Tokens.PERIGO, false),
        LINK("buttonType: borderless; foreground: $Lab.acento; hoverForeground: $Lab.acentoForte; "
            + "hoverBackground: null; pressedBackground: null; margin: 0,2,0,2", Tokens.ACENTO, false),
        FANTASMA("buttonType: borderless; foreground: $Lab.texto2; hoverForeground: $Lab.texto; "
            + "hoverBackground: $Lab.superficie3; pressedBackground: $Lab.superficie3",
            Tokens.TEXTO2, false);

        private final String estilo;
        private final String tokenDoTexto;
        private final boolean negrito;

        Variante(String estilo, String tokenDoTexto, boolean negrito) {
            this.estilo = estilo;
            this.tokenDoTexto = tokenDoTexto;
            this.negrito = negrito;
        }
    }

    private final Variante variante;

    public Botao(String texto, Variante variante) {
        super();
        this.variante = variante;
        putClientProperty("html.disable", Boolean.TRUE);
        setText(texto);
        setFocusable(true);
        putClientProperty(FlatClientProperties.STYLE, variante.estilo);
        if (variante.negrito) {
            setFont(getFont().deriveFont(Font.BOLD));
        }
    }

    @Override
    public void setText(String texto) {
        super.setText(texto == null ? null : TextoSeguro.limparLinha(texto));
    }

    @Override
    public void setToolTipText(String texto) {
        super.setToolTipText(texto == null || texto.isBlank() ? null : TextoSeguro.limparLinha(texto));
    }

    @Override
    public Color getForeground() {
        // o construtor do JButton já pergunta a cor (installUI) antes de o campo existir
        return variante == null ? super.getForeground() : Tokens.cor(variante.tokenDoTexto);
    }

    public Variante variante() {
        return variante;
    }

    private boolean ocupado;
    private boolean habilitadoAntes = true;
    private Icon iconeDeRepouso;
    private Runnable pararAnimacao = () -> { };

    /**
     * Liga ou desliga o "ocupado" (o {@code aria-busy} do painel web): o ícone vira um arco que gira e o botão fica
     * desabilitado até a ação terminar. {@link #setIcon} e {@link #setEnabled} chamados NO MEIO do ocupado valem
     * para quando ele acabar. Na EDT.
     */
    public void definirOcupado(boolean sim) {
        if (sim == ocupado) {
            return;
        }
        if (sim) {
            iconeDeRepouso = getIcon();
            habilitadoAntes = isEnabled();
            ocupado = true;
            IconeOcupado girando = new IconeOcupado(14, null);
            super.setIcon(girando);
            setDisabledIcon(girando); // o ícone desabilitado padrão é uma foto parada: o arco precisa ser o mesmo
            super.setEnabled(false);
            pararAnimacao = IconeOcupado.animar(this);
        } else {
            pararAnimacao.run();
            pararAnimacao = () -> { };
            ocupado = false;
            super.setIcon(iconeDeRepouso);
            setDisabledIcon(null);
            super.setEnabled(habilitadoAntes);
        }
    }

    public boolean ocupado() {
        return ocupado;
    }

    /** Atalho: liga o ocupado e devolve o que o desliga. */
    public Runnable ocupar() {
        definirOcupado(true);
        return () -> definirOcupado(false);
    }

    @Override
    public void setIcon(Icon icone) {
        if (ocupado) {
            iconeDeRepouso = icone;
        } else {
            super.setIcon(icone);
        }
    }

    @Override
    public void setEnabled(boolean habilitado) {
        if (ocupado) {
            habilitadoAntes = habilitado;
        } else {
            super.setEnabled(habilitado);
        }
    }
}
