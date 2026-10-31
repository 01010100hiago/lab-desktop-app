package br.com.labdesktop.sonda;

import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextField;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

/** SONDA TEMPORARIA (nao entra na main): diz o que o runner do Windows mede de verdade. */
class SondaDeFontesTest {

    private static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }

    private static void fonte(StringBuilder sb, String chave, JComponent amostra) {
        Font f = UIManager.getFont(chave);
        if (f == null) {
            sb.append(chave).append(" = null\n");
            return;
        }
        amostra.setFont(f);
        FontMetrics fm = amostra.getFontMetrics(f);
        sb.append(String.format("%-14s family=%-18s name=%-22s style=%d size=%.1f  asc=%d desc=%d lead=%d h=%d  w(Aparência Navegação)=%d w(Adicionar)=%d  canDisplay(ç)=%s%n", chave, f.getFamily(), f.getName(), f.getStyle(),
            f.getSize2D(), fm.getAscent(), fm.getDescent(), fm.getLeading(), fm.getHeight(), fm.stringWidth("Aparência Navegação"), fm.stringWidth("Adicionar"), f.canDisplay('ç')));
    }

    @Test
    void sonda() {
        Visual.tema(Tema.CLARO);
        StringBuilder sb = new StringBuilder("SONDA\n");
        sb.append("os=").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(" java=").append(System.getProperty("java.version"))
            .append(" laf=").append(UIManager.getLookAndFeel().getClass().getSimpleName()).append(" headless=").append(GraphicsEnvironment.isHeadless()).append('\n');
        sb.append("props flatlaf.uiScale=").append(System.getProperty("flatlaf.uiScale")).append(" sun.java2d.uiScale=").append(System.getProperty("sun.java2d.uiScale")).append('\n');
        try {
            sb.append("screenDpi=").append(Toolkit.getDefaultToolkit().getScreenResolution()).append('\n');
        } catch (Throwable e) {
            sb.append("screenDpi: ").append(e).append('\n');
        }
        sb.append("win.defaultGUI.font=").append(Toolkit.getDefaultToolkit().getDesktopProperty("win.defaultGUI.font")).append(" win.messagebox.font=").append(Toolkit.getDefaultToolkit().getDesktopProperty("win.messagebox.font")).append('\n');
        try {
            sb.append("defaultTransform=").append(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform()).append('\n');
        } catch (Throwable e) {
            sb.append("defaultTransform: ").append(e).append('\n');
        }
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        sb.append("desktophints=").append(hints).append('\n');
        sb.append("uiscale system=").append(com.formdev.flatlaf.util.UIScale.getSystemScaleFactor((java.awt.GraphicsConfiguration) null)).append(" user=").append(com.formdev.flatlaf.util.UIScale.getUserScaleFactor()).append('\n');
        String[] familias = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        sb.append("familias com Inter/Segoe/JetBrains: ").append(Arrays.stream(familias).filter(n -> n.contains("Inter") || n.contains("Segoe UI") || n.contains("JetBrains")).toList()).append('\n');
        Visual.naEdt(() -> {
            JLabel amostra = new JLabel("x");
            for (String chave : new String[] {"defaultFont", "h1.font", "h2.font", "h3.font", "h4.font", "semibold.font", "light.font", "small.font", "mini.font", "medium.font", "large.font", "monospaced.font", "Label.font", "Button.font", "TextField.font", "TabbedPane.font", "Table.font", "ToolTip.font", "Menu.font"}) {
                fonte(sb, chave, amostra);
            }
            for (String nome : new String[] {"Segoe UI Semibold", "Segoe UI", "Inter SemiBold", "Inter", "Helvetica Neue", "Dialog"}) {
                for (int tam : new int[] {14, 17}) {
                    Font f = new Font(nome, Font.PLAIN, tam);
                    FontMetrics fm = amostra.getFontMetrics(f);
                    sb.append(String.format("novo %-18s %2d -> family=%-18s asc=%d desc=%d lead=%d h=%d w(Aparência Navegação)=%d%n", nome, tam, f.getFamily(), fm.getAscent(), fm.getDescent(), fm.getLeading(), fm.getHeight(), fm.stringWidth("Aparência Navegação")));
                }
            }
            FontRenderContext frc = amostra.getFontMetrics(UIManager.getFont("defaultFont")).getFontRenderContext();
            sb.append("frc aa=").append(frc.getAntiAliasingHint()).append(" fm=").append(frc.getFractionalMetricsHint()).append(" tx=").append(frc.getTransform()).append('\n');
            for (String k : new String[] {"Button.margin", "Button.minimumWidth", "Button.arc", "Button.borderWidth", "Component.focusWidth", "Component.minimumWidth", "Component.minimumHeight", "Component.arc", "Component.borderWidth", "ScrollBar.width", "TextComponent.arc",
                "Button.iconTextGap", "TextField.margin", "Button.innerFocusWidth", "TabbedPane.tabHeight", "Table.rowHeight", "ScrollPane.border", "Button.default.borderWidth", "Button.borderColor", "Component.borderColor"}) {
                sb.append(k).append(" = ").append(UIManager.get(k)).append('\n');
            }
            for (Botao.Variante v : new Botao.Variante[] {Botao.Variante.SECUNDARIO, Botao.Variante.PRIMARIO}) {
                Botao b = new Botao("Adicionar", v);
                sb.append("Botao ").append(v).append(" pref=").append(b.getPreferredSize()).append(" insets=").append(b.getInsets()).append(" margin=").append(b.getMargin()).append(" font=").append(b.getFont()).append('\n');
            }
            JTextField campo = new JTextField("abc");
            sb.append("JTextField pref=").append(campo.getPreferredSize()).append(" font=").append(campo.getFont().getFamily()).append(campo.getFont().getSize2D()).append('\n');
            sb.append("JScrollBar pref=").append(new JScrollBar().getPreferredSize()).append('\n');
            JLabel titulo = new JLabel("Ajustes");
            titulo.setFont(UIManager.getFont("h3.font"));
            sb.append("JLabel h3 pref=").append(titulo.getPreferredSize()).append('\n');
            JLabel corpo = new JLabel("Ajustes");
            corpo.setFont(UIManager.getFont("defaultFont"));
            sb.append("JLabel default pref=").append(corpo.getPreferredSize()).append('\n');
            return null;
        });
        // pixels do contorno de um botao secundario sobre o fundo da tela (a medida de AuditoriaDeCores)
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Visual.naEdt(() -> {
                JPanel painel = new JPanel(null);
                painel.setOpaque(true);
                painel.setBackground(br.com.labdesktop.ui.tema.Tokens.cor(br.com.labdesktop.ui.tema.Tokens.BG));
                Botao b = new Botao("Cancelar", Botao.Variante.SECUNDARIO);
                painel.add(b);
                painel.setSize(220, 80);
                Dimension d = b.getPreferredSize();
                b.setBounds(30, 20, d.width, d.height);
                painel.doLayout();
                BufferedImage img = Visual.renderizar(painel, 220, 80);
                int x = 30 + d.width / 2;
                StringBuilder col = new StringBuilder();
                for (int y = 16; y <= 30; y++) {
                    col.append(y).append(':').append(hex(img.getRGB(x, y))).append(' ');
                }
                StringBuilder lin = new StringBuilder();
                int y2 = 20 + d.height / 2;
                for (int xx = 26; xx <= 40; xx++) {
                    lin.append(xx).append(':').append(hex(img.getRGB(xx, y2))).append(' ');
                }
                sb.append("tema ").append(t).append(" botao bounds=").append(b.getBounds()).append(" bg=").append(hex(painel.getBackground().getRGB())).append('\n');
                sb.append("  coluna no meio (y:cor) ").append(col).append('\n');
                sb.append("  linha no meio (x:cor) ").append(lin).append('\n');
                sb.append("  Button.borderColor token: ").append(UIManager.getColor("Button.borderColor")).append(" Component.borderColor=").append(UIManager.getColor("Component.borderColor")).append('\n');
                return null;
            });
        }
        System.out.println(sb);
    }
}
