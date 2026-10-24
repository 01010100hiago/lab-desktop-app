package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.componentes.Botao.Variante;
import br.com.labdesktop.ui.tema.Tokens;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JToggleButton;
import javax.swing.UIManager;

/** Fábricas dos botões do painel. Ícone é o NOME do SVG ({@code "atualizar"}), ou {@code null} para só texto. */
public final class Botoes {

    private Botoes() { }

    private static Botao criar(String texto, String icone, Variante variante, int tamanhoDoIcone) {
        Botao b = new Botao(texto, variante);
        if (icone != null) {
            b.setIcon(Icones.de(icone, tamanhoDoIcone));
            b.setIconTextGap(7);
        }
        return b;
    }

    /** Ação principal (bronze). */
    public static Botao primario(String texto, String icone) {
        return criar(texto, icone, Variante.PRIMARIO, 16);
    }

    /** Ação comum. */
    public static Botao secundario(String texto, String icone) {
        return criar(texto, icone, Variante.SECUNDARIO, 16);
    }

    /** Ação que desfaz ou apaga (texto terracota). */
    public static Botao perigo(String texto, String icone) {
        return criar(texto, icone, Variante.PERIGO, 16);
    }

    /** Sem borda até o mouse passar (cancelar, voltar). */
    public static Botao fantasma(String texto, String icone) {
        return criar(texto, icone, Variante.FANTASMA, 16);
    }

    /** Botão baixo (30 px) para dentro de linhas e faixas. */
    public static Botao mini(String texto, String icone) {
        return miniDe(texto, icone, Variante.SECUNDARIO);
    }

    public static Botao miniPrimario(String texto, String icone) {
        return miniDe(texto, icone, Variante.PRIMARIO);
    }

    public static Botao miniPerigo(String texto, String icone) {
        return miniDe(texto, icone, Variante.PERIGO);
    }

    private static Botao miniDe(String texto, String icone, Variante variante) {
        Botao b = criar(texto, icone, variante, 14);
        b.putClientProperty(FlatClientProperties.STYLE, estiloMini(variante));
        b.setFont(b.getFont().deriveFont(variante == Variante.PRIMARIO ? Font.BOLD : Font.PLAIN, 12.5f));
        b.setIconTextGap(6);
        return b;
    }

    private static String estiloMini(Variante v) {
        String base = switch (v) {
            case PRIMARIO -> "background: $Lab.acento; foreground: $Lab.acentoTexto; borderColor: $Lab.acento; "
                + "hoverBackground: $Lab.acentoForte; pressedBackground: $Lab.acentoForte; hoverBorderColor: $Lab.acentoForte; "
                + "disabledBackground: $Lab.superficie3; disabledText: $Lab.texto3";
            case PERIGO -> "foreground: $Lab.perigo; hoverBackground: $Lab.perigoSuave; hoverBorderColor: $Lab.perigo";
            default -> "";
        };
        return "arc: 8; minimumHeight: 28; margin: 2,10,2,10; " + base;
    }

    /** Botão quadrado só com ícone (30 px), com a dica como tooltip e nome acessível. */
    public static Botao icone(String icone, String dica) {
        return iconeDe(icone, dica, Variante.SECUNDARIO);
    }

    public static Botao iconePrimario(String icone, String dica) {
        return iconeDe(icone, dica, Variante.PRIMARIO);
    }

    public static Botao iconeFantasma(String icone, String dica) {
        return iconeDe(icone, dica, Variante.FANTASMA);
    }

    private static Botao iconeDe(String icone, String dica, Variante variante) {
        Botao b = new Botao(null, variante);
        b.setIcon(Icones.de(icone, 14));
        b.setToolTipText(dica);
        b.getAccessibleContext().setAccessibleName(dica);
        String estilo = variante == Variante.PRIMARIO ? estiloMini(variante) : "arc: 8; margin: 0,0,0,0; "
            + (variante == Variante.FANTASMA ? "buttonType: borderless; hoverBackground: $Lab.superficie3" : "");
        b.putClientProperty(FlatClientProperties.STYLE, estilo + "; minimumWidth: 30; minimumHeight: 30; margin: 0,0,0,0");
        b.setPreferredSize(new Dimension(30, 30));
        b.setMinimumSize(new Dimension(30, 30));
        b.setMaximumSize(new Dimension(30, 30));
        return b;
    }

    /** Filtro rápido (Todos, No ar...): acende em âmbar suave quando marcado. */
    public static JToggleButton filtro(String texto, String dica) {
        JToggleButton b = new JToggleButton() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Color getForeground() {
                return Tokens.cor(isSelected() ? Tokens.ACENTO : Tokens.TEXTO);
            }
        };
        b.putClientProperty("html.disable", Boolean.TRUE);
        b.setText(texto);
        if (dica != null) {
            b.setToolTipText(dica);
        }
        b.setFont(b.getFont().deriveFont(12.5f));
        b.putClientProperty(FlatClientProperties.STYLE, "arc: 8; minimumHeight: 28; margin: 2,10,2,10; "
            + "selectedBackground: $Lab.acentoSuave; selectedForeground: $Lab.acento; "
            + "pressedBackground: $Lab.acentoSuave");
        b.addChangeListener(e -> {
            Font f = b.getFont();
            Font esperado = f.deriveFont(b.isSelected() ? Font.BOLD : Font.PLAIN);
            if (f.getStyle() != esperado.getStyle()) {
                b.setFont(esperado);
            }
        });
        return b;
    }

    /** Só para os botões criados fora destas fábricas conseguirem a mesma fonte de corpo. */
    public static Font fonteDeCorpo() {
        return UIManager.getFont("Button.font");
    }

    /** Um botão que parece link (texto bronze), para o endereço público do projeto, "Ver detalhes". */
    public static Botao link(String texto) {
        Botao b = new Botao(texto, Variante.LINK);
        b.setFont(b.getFont().deriveFont(12.5f));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }
}
