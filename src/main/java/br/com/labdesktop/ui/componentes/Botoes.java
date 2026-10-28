package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.componentes.Botao.Variante;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JToggleButton;
import javax.swing.UIManager;

/** Fábricas dos botões do painel. Ícone é o NOME do SVG ({@code "atualizar"}), ou {@code null} para só texto. */
public final class Botoes {

    private Botoes() { }

    /** A altura dos botões baixos e dos de ícone: 32 px, o que cabe um dedo e o texto de 14. */
    public static final int ALTURA = 32;

    private static Botao criar(String texto, String icone, Variante variante, int tamanhoDoIcone) {
        Botao b = new Botao(texto, variante);
        if (icone != null) {
            b.setIcon(Icones.de(icone, tamanhoDoIcone));
            b.setIconTextGap(7);
        }
        return b;
    }

    /** Ação principal (destaque cheio: grafite no claro, quase-branco no escuro). */
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

    /** Botão baixo (32 px) para dentro de linhas e faixas. */
    public static Botao mini(String texto, String icone) {
        return miniDe(texto, icone, Variante.SECUNDARIO);
    }

    /** Botão mini ainda mais baixo (28 px), para dentro de LINHAS de tabela: 32 px em cada linha deixaria a tabela comprida. */
    public static Botao miniBaixo(String texto, String icone) {
        Botao b = miniDe(texto, icone, Variante.SECUNDARIO);
        b.putClientProperty(FlatClientProperties.STYLE, "arc: 8; minimumHeight: 28; margin: 1,10,1,10; " + Variante.SECUNDARIO.estilo());
        return b;
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
        Fontes.aplicar(b, Fontes.TEXTO, variante == Variante.PRIMARIO ? Font.BOLD : Font.PLAIN, 13f);
        b.setIconTextGap(6);
        return b;
    }

    private static String estiloMini(Variante v) {
        return "arc: 8; minimumHeight: " + ALTURA + "; margin: 2,12,2,12; " + v.estilo();
    }

    /** Botão quadrado só com ícone (32 px), com a dica como tooltip e nome acessível. */
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
        String estilo = variante == Variante.PRIMARIO ? estiloMini(variante) : "arc: 8; margin: 0,0,0,0; " + variante.estilo();
        b.putClientProperty(FlatClientProperties.STYLE, estilo + "; minimumWidth: " + ALTURA + "; minimumHeight: " + ALTURA + "; margin: 0,0,0,0");
        b.setPreferredSize(new Dimension(ALTURA, ALTURA));
        b.setMinimumSize(new Dimension(ALTURA, ALTURA));
        b.setMaximumSize(new Dimension(ALTURA, ALTURA));
        return b;
    }

    /** Filtro rápido (Todos, No ar...): acende em destaque suave quando marcado. */
    public static JToggleButton filtro(String texto, String dica) {
        JToggleButton b = new JToggleButton() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Color getForeground() {
                if (!isEnabled()) {
                    return Tokens.cor(Tokens.TEXTO3); // o mesmo cinza apagado do botão desabilitado (Botao)
                }
                return Tokens.cor(isSelected() ? Tokens.DESTAQUE : Tokens.TEXTO);
            }
        };
        b.putClientProperty("html.disable", Boolean.TRUE);
        b.setText(texto);
        if (dica != null) {
            b.setToolTipText(dica);
        }
        Fontes.aplicar(b, Fontes.TEXTO, Font.PLAIN, 13f);
        b.putClientProperty(FlatClientProperties.STYLE, "arc: 8; minimumHeight: " + ALTURA + "; margin: 2,12,2,12; "
            + "selectedBackground: $Lab.destaqueSuave; selectedForeground: $Lab.destaque; "
            + "pressedBackground: $Lab.destaqueSuave");
        b.addChangeListener(e -> {
            int estilo = b.isSelected() ? Font.BOLD : Font.PLAIN;
            if (b.getFont().getStyle() != estilo) {
                Fontes.aplicar(b, Fontes.TEXTO, estilo, 13f);
            }
        });
        return b;
    }

    /** Só para os botões criados fora destas fábricas conseguirem a mesma fonte de corpo. */
    public static Font fonteDeCorpo() {
        return UIManager.getFont("Button.font");
    }

    /** Um botão que parece link (texto de destaque), para o endereço público do projeto, "Ver detalhes". */
    public static Botao link(String texto) {
        Botao b = new Botao(texto, Variante.LINK);
        Fontes.aplicar(b, Fontes.TEXTO, Font.PLAIN, 13f);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }
}
