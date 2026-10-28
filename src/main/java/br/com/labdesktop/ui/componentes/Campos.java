package br.com.labdesktop.ui.componentes;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;

/** Fábricas dos campos de texto do painel ({@code .input}, {@code .input-mini}, {@code .mono}). */
public final class Campos {

    private Campos() { }

    private static final String ESTILO = "arc: 8; margin: 5,10,5,10";

    /** Campo de texto comum. */
    public static JTextField texto(int colunas, String dica) {
        JTextField c = new JTextField(colunas);
        c.putClientProperty(FlatClientProperties.STYLE, ESTILO);
        if (dica != null) {
            c.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, dica);
        }
        return c;
    }

    /** Campo em fonte monoespaçada (host, impressão, nome de projeto). */
    public static JTextField mono(int colunas, String dica) {
        JTextField c = texto(colunas, dica);
        br.com.labdesktop.ui.tema.Fontes.aplicar(c, br.com.labdesktop.ui.tema.Fontes.MONO, Font.PLAIN, 12.5f);
        return c;
    }

    /** Campo de busca com o ícone da lupa e o botão de limpar do FlatLaf. */
    public static JTextField busca(int colunas, String dica) {
        JTextField c = texto(colunas, dica);
        c.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icones.de("busca", 14));
        c.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, Boolean.TRUE);
        c.putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 3,6,3,6");
        c.getAccessibleContext().setAccessibleName(dica);
        return c;
    }

    /** Campo de senha com o olho para revelar o que foi digitado. */
    public static JPasswordField senha(int colunas) {
        JPasswordField c = new JPasswordField(colunas);
        c.putClientProperty(FlatClientProperties.STYLE, ESTILO + "; showRevealButton: true");
        return c;
    }

    /**
     * Apaga o que está num campo de senha, DE VERDADE: {@code setText("")} só tira o texto da vista e os caracteres ficariam no buffer interno do documento (a "folga" do
     * {@code GapContent}). Sobrescreve o conteúdo com {@code \0} do mesmo tamanho e só depois o remove. É o melhor que o Swing deixa fazer (o {@code String} que o campo mostrou
     * ao digitar não é alcançável); o app nunca guarda o token digitado em {@code String}.
     */
    public static void apagarSenha(JPasswordField c) {
        javax.swing.text.Document d = c.getDocument();
        int n = d.getLength();
        if (n == 0) {
            return;
        }
        try {
            if (d instanceof javax.swing.text.AbstractDocument ad) {
                ad.replace(0, n, "\0".repeat(n), null);
            }
            d.remove(0, d.getLength());
        } catch (javax.swing.text.BadLocationException e) {
            c.setText("");
        }
        // O buffer interno do documento (a "folga" do GapContent) pode reter pedaços do que foi digitado: troca por um documento novo, mantendo quem o ouve.
        if (d instanceof javax.swing.text.AbstractDocument velho) {
            javax.swing.text.PlainDocument novo = new javax.swing.text.PlainDocument();
            for (javax.swing.event.DocumentListener l : velho.getDocumentListeners()) {
                novo.addDocumentListener(l);
            }
            c.setDocument(novo);
        }
    }

    /** Os seis dígitos do autenticador: só números e o espaço do meio, até 7 caracteres, grande e centralizado. */
    public static JTextField codigoDeAutenticador() {
        JTextField c = new JTextField(8);
        c.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 8,12,8,12");
        c.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "000 000");
        c.setHorizontalAlignment(JTextField.CENTER);
        br.com.labdesktop.ui.tema.Fontes.aplicar(c, br.com.labdesktop.ui.tema.Fontes.MONO, Font.BOLD, br.com.labdesktop.ui.tema.Fontes.NOMINAL_DO_VALOR_GRANDE);
        ((PlainDocument) c.getDocument()).setDocumentFilter(new FiltroDeCodigo());
        c.getAccessibleContext().setAccessibleName("Código de 6 dígitos do autenticador");
        c.setMaximumSize(new java.awt.Dimension(260, c.getPreferredSize().height));
        return c;
    }

    /** Aceita dígitos e espaço, no máximo 7 caracteres ({@code 123 456}). Cola texto sujo: o que não serve é descartado. */
    static final class FiltroDeCodigo extends DocumentFilter {

        static final int MAX = 7;

        static String filtrar(String entrada) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entrada.length(); i++) {
                char ch = entrada.charAt(i);
                if ((ch >= '0' && ch <= '9') || ch == ' ' || ch == ' ') {
                    sb.append(ch == ' ' ? ' ' : ch);
                }
            }
            return sb.toString();
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String texto, AttributeSet attr) throws BadLocationException {
            replace(fb, offset, 0, texto, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int tamanho, String texto, AttributeSet attr)
            throws BadLocationException {
            String novo = texto == null ? "" : filtrar(texto);
            int atual = fb.getDocument().getLength() - tamanho;
            int cabe = Math.max(0, MAX - atual);
            if (novo.length() > cabe) {
                novo = novo.substring(0, cabe);
            }
            super.replace(fb, offset, tamanho, novo, attr);
        }
    }

    /** Margens em volta de um painel de diálogo. */
    public static Insets margemDeDialogo() {
        return new Insets(22, 24, 18, 24);
    }
}
