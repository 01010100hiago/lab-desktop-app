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
 * {@code FlatClientProperties.STYLE} com referências aos tokens ({@code $Lab.destaque}), então claro, escuro e
 * sistema trocam sozinhos. {@code html.disable} fica ligado (o texto de um botão pode vir do servidor).
 *
 * <p>O ícone (que segue a cor do texto do componente) enxerga a cor certa porque {@link #getForeground()} devolve o
 * token da variante: num botão primário, ícone e texto saem com a cor de texto do destaque (branco sobre grafite, grafite sobre quase-branco).
 */
public final class Botao extends JButton {

    private static final long serialVersionUID = 1L;

    /**
     * As variantes. O estilo de cada uma diz a cor do fundo, do texto e da borda em TODOS os estados que o FlatLaf pinta (normal, mouse em cima,
     * apertado, desabilitado e botão padrão do diálogo, o do Enter), sempre por token. Deixar um estado de fora faz o FlatLaf pintá-lo com as cores
     * dele (o botão padrão sai grafite com o texto da variante, ilegível: era o "botão de confirmação preto").
     */
    public enum Variante {
        PRIMARIO("background: $Lab.destaque; foreground: $Lab.destaqueTexto; borderColor: $Lab.destaque; "
            + "hoverBackground: $Lab.destaqueForte; pressedBackground: $Lab.destaqueApertado; "
            + "hoverBorderColor: $Lab.destaqueForte; focusedBorderColor: $Lab.foco; "
            + DESABILITADO
            + padrao("$Lab.destaque", "$Lab.destaqueTexto", "$Lab.destaque", "$Lab.destaqueForte", "$Lab.destaqueApertado", "$Lab.destaqueForte"),
            Tokens.DESTAQUE_TEXTO, true),
        SECUNDARIO(DESABILITADO
            + padrao("$Button.background", "$Lab.texto", "$Lab.destaque", "$Button.hoverBackground", "$Button.pressedBackground", "$Lab.destaque"),
            Tokens.TEXTO, false),
        PERIGO("foreground: $Lab.perigo; hoverBackground: $Lab.perigoSuave; pressedBackground: $Lab.perigoSuaveApertado; hoverBorderColor: $Lab.perigo; "
            + DESABILITADO
            // o botão do Enter de uma pergunta que apaga ou desfaz: cheio em vermelho com o texto de destaque (branco no claro, grafite no escuro)
            + padrao("$Lab.perigo", "$Lab.destaqueTexto", "$Lab.perigo", "$Lab.perigoForte", "$Lab.perigoApertado", "$Lab.perigoForte"),
            Tokens.PERIGO, false),
        LINK("buttonType: borderless; foreground: $Lab.destaque; hoverForeground: $Lab.destaqueForte; "
            + "hoverBackground: null; pressedBackground: null; margin: 0,2,0,2; "
            + padrao("null", "$Lab.destaque", "null", "null", "null", "null"), Tokens.DESTAQUE, false),
        FANTASMA("buttonType: borderless; foreground: $Lab.texto2; hoverForeground: $Lab.texto; "
            + "hoverBackground: $Lab.superficie3; pressedBackground: $Lab.borda; "
            + padrao("null", "$Lab.texto2", "null", "$Lab.superficie3", "$Lab.borda", "null"),
            Tokens.TEXTO2, false);

        private final String estilo;
        private final String tokenDoTexto;
        private final boolean negrito;

        Variante(String estilo, String tokenDoTexto, boolean negrito) {
            this.estilo = estilo;
            this.tokenDoTexto = tokenDoTexto;
            this.negrito = negrito;
        }

        /** O estilo FlatLaf completo da variante (sem tamanho nem margem): quem monta um botão de outro tamanho junta o seu à frente. */
        public String estilo() {
            return estilo;
        }
    }

    private static final String DESABILITADO = "disabledBackground: $Lab.superficie3; disabledText: $Lab.texto3; disabledBorderColor: $Lab.borda; ";

    private static String padrao(String fundo, String texto, String borda, String fundoEmCima, String fundoApertado, String bordaEmCima) {
        return "default.background: " + fundo + "; default.foreground: " + texto + "; default.borderColor: " + borda
            + "; default.hoverBackground: " + fundoEmCima + "; default.pressedBackground: " + fundoApertado
            + "; default.hoverBorderColor: " + bordaEmCima + "; default.focusedBorderColor: $Lab.foco; default.focusColor: $Lab.foco";
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
            br.com.labdesktop.ui.tema.Fontes.aplicar(this, br.com.labdesktop.ui.tema.Fontes.TEXTO, Font.BOLD,
                br.com.labdesktop.ui.tema.Fontes.NOMINAL_PADRAO);
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

    /**
     * A cor do texto E do ícone (o ícone pinta com a cor do componente) no estado de agora, uma só conta para todo botão: desabilitado é o texto
     * apagado (o ícone branco do primário sobre o fundo cinza do desabilitado sumia), o botão do Enter de uma pergunta que apaga é texto de destaque sobre
     * vermelho, e o resto é o token da variante. Lida na hora de pintar: a troca de tema vale sem refazer o botão.
     */
    @Override
    public Color getForeground() {
        // o construtor do JButton já pergunta a cor (installUI) antes de o campo existir
        if (variante == null) {
            return super.getForeground();
        }
        if (!isEnabled()) {
            return Tokens.cor(Tokens.TEXTO3);
        }
        if (variante == Variante.PERIGO && isDefaultButton()) {
            return Tokens.cor(Tokens.DESTAQUE_TEXTO);
        }
        return Tokens.cor(variante.tokenDoTexto);
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
            setDisabledIcon(iconeDeRepouso);
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
            // o ícone apagado que o Swing gera sozinho é um cinza de foto, sem relação com o tema; o mesmo ícone, pintado com a cor do botão
            // desabilitado (getForeground), sai no cinza do texto apagado
            setDisabledIcon(icone);
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
