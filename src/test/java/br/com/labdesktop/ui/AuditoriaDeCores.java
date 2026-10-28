package br.com.labdesktop.ui;

import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Mede o que o usuário VÊ: pinta a tela e, para cada componente interativo (botão, botão de alternar, caixa, opção, campo, lista suspensa), olha os pixels
 * pintados. O fundo é a cor que mais aparece dentro do componente e a tinta (texto ou ícone) é o pixel de maior contraste com esse fundo; assim
 * pegam-se também as cores que o Swing escolhe sozinho (o botão "padrão" do FlatLaf, o estado de mouse em cima) e que nenhum token explica.
 * Cada componente é medido em cinco estados: normal, desabilitado, mouse em cima, apertado e marcado (só os de alternar).
 *
 * <p>Limites: 4,5 para texto e 3,0 para ícone sem texto (WCAG). "Fundo puro" é preto (#000000) ou branco (#FFFFFF) que NÃO é nenhum token do tema
 * em uso: um token pode ser branco (a superfície do claro), o que não pode é o Swing ou um componente pintar preto ou branco por conta própria.
 */
public final class AuditoriaDeCores {

    public static final double MINIMO_DE_TEXTO = 4.5;
    public static final double MINIMO_DE_ICONE = 3.0;

    /** Os estados medidos. */
    public enum Estado { NORMAL, DESABILITADO, MOUSE_EM_CIMA, APERTADO, MARCADO }

    /** Uma medida: quem, em que estado, as cores vistas e o veredito. */
    public record Medida(String quem, Estado estado, Color fundo, Color tinta, double contraste, double minimo, String fundoPuro) {

        public boolean falhou() {
            return contraste < minimo || fundoPuro != null;
        }

        @Override
        public String toString() {
            return String.format("%-70s %-13s fundo %s tinta %s contraste %5.2f (minimo %.1f)%s", quem, estado, hex(fundo), hex(tinta), contraste, minimo,
                fundoPuro == null ? "" : "  FUNDO " + fundoPuro + " FORA DO TOKEN");
        }
    }

    private AuditoriaDeCores() { }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    static double luminancia(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) {
            v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    public static double contraste(Color a, Color b) {
        double la = luminancia(a);
        double lb = luminancia(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Os componentes que o usuário toca ou lê como controle (visíveis até a raiz). */
    static List<JComponent> interativos(JComponent raiz) {
        List<JComponent> l = new ArrayList<>();
        coletar(raiz, raiz, l);
        return l;
    }

    private static void coletar(JComponent raiz, Component c, List<JComponent> l) {
        if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0) {
            return;
        }
        if (c instanceof AbstractButton || c instanceof JTextComponent || c instanceof JComboBox<?>) {
            // o botão de limpar que o FlatLaf põe DENTRO de um campo de busca é do campo, não da tela
            boolean pecaDeCampo = c instanceof AbstractButton && c.getParent() instanceof JTextComponent;
            if (!pecaDeCampo && ehMedivel((JComponent) c)) {
                l.add((JComponent) c);
            }
        }
        if (c instanceof Container k) {
            for (Component filho : k.getComponents()) {
                coletar(raiz, filho, l);
            }
        }
    }

    /** Está à vista? Um componente dentro de uma rolagem (a tabela de atalhos) que ficou para baixo, fora da janela dela, não é pintado e não tem o que medir. */
    private static boolean estaAVista(JComponent c) {
        java.awt.Rectangle visivel = c.getVisibleRect();
        return visivel.width * 2 >= c.getWidth() && visivel.height * 2 >= c.getHeight() && visivel.width > 0 && visivel.height > 0; // pelo menos metade dele à vista
    }

    private static boolean ehMedivel(JComponent c) {
        if (!estaAVista(c)) {
            return false;
        }
        if (c instanceof AbstractButton b) {
            return (b.getText() != null && !b.getText().isBlank()) || b.getIcon() != null;
        }
        if (c instanceof JTextComponent t) {
            return t.getText() != null && !t.getText().isBlank() && !(c.getClass().getName().contains("JediTerm"));
        }
        if (c instanceof JComboBox<?> combo) {
            return combo.getSelectedItem() != null; // um seletor vazio não tem texto para medir
        }
        return true;
    }

    static String nome(JComponent c, JComponent raiz) {
        String texto = "";
        if (c instanceof AbstractButton b) {
            texto = b.getText() != null && !b.getText().isBlank() ? b.getText() : String.valueOf(b.getToolTipText());
        } else if (c instanceof JTextComponent t) {
            texto = t.getText().length() > 24 ? t.getText().substring(0, 24) : t.getText();
        }
        String fundoDaCaixa = c.getParent() == null ? "" : c.getParent().getClass().getSimpleName();
        Class<?> tipo = c.getClass();
        while (tipo.getSimpleName().isEmpty() && tipo.getSuperclass() != null) {
            tipo = tipo.getSuperclass(); // classe anônima: vale o nome da que ela estende
        }
        return tipo.getSimpleName() + "[" + texto.replace('\n', ' ') + "]@" + fundoDaCaixa;
    }

    /** Pinta, sem suavizar o texto (o pixel da letra tem a cor exata da fonte), a árvore já dimensionada. */
    static BufferedImage pintar(JComponent raiz, int largura, int altura) {
        BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(Tokens.cor(Tokens.BG));
        g.fillRect(0, 0, largura, altura);
        raiz.paint(g);
        g.dispose();
        return img;
    }

    private static Rectangle ondeEsta(JComponent c, JComponent raiz) {
        Point p = SwingUtilities.convertPoint(c.getParent(), c.getLocation(), raiz);
        return new Rectangle(p.x, p.y, c.getWidth(), c.getHeight());
    }

    /** Fundo = cor mais frequente no miolo; tinta = pixel de maior contraste com o fundo. */
    private static Color[] fundoETinta(BufferedImage img, Rectangle r) {
        Rectangle area = r.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
        int folga = Math.min(3, Math.min(area.width, area.height) / 4);
        Map<Integer, Integer> contagem = new HashMap<>();
        for (int y = area.y + folga; y < area.y + area.height - folga; y++) {
            for (int x = area.x + folga; x < area.x + area.width - folga; x++) {
                contagem.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
            }
        }
        if (contagem.isEmpty()) {
            return new Color[] {Color.MAGENTA, Color.MAGENTA};
        }
        int fundoRgb = contagem.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        Color fundo = new Color(fundoRgb);
        Color tinta = fundo;
        double melhor = 1;
        for (var e : contagem.entrySet()) {
            Color c = new Color(e.getKey());
            double k = contraste(c, fundo);
            if (k > melhor) {
                melhor = k;
                tinta = c;
            }
        }
        return new Color[] {fundo, tinta};
    }

    /** Fundo = cor mais frequente no miolo do componente; tinta = pixel de maior contraste com ele DENTRO da área do texto. */
    private static Color[] fundoETintaDoTexto(BufferedImage img, Rectangle miolo, Rectangle texto) {
        Color[] base = fundoETinta(img, miolo);
        if (texto == miolo) {
            return base;
        }
        Rectangle area = texto.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
        Color tinta = base[0];
        double melhor = 1;
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                Color c = new Color(img.getRGB(x, y) & 0xFFFFFF);
                double k = contraste(c, base[0]);
                if (k > melhor) {
                    melhor = k;
                    tinta = c;
                }
            }
        }
        return new Color[] {base[0], tinta};
    }

    /** No ícone o fundo é o que o cerca (a cor mais frequente no miolo do botão) e a tinta o pixel de maior contraste dentro do ícone. */
    private static Color[] fundoETintaDoIcone(BufferedImage img, Rectangle r) {
        Rectangle area = r.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
        Map<Integer, Integer> contagem = new HashMap<>();
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                contagem.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
            }
        }
        // o fundo do ícone é o canto (o ícone é um traço fino e deixa a maior parte do quadrado vazio)
        Color fundo = new Color(img.getRGB(area.x, area.y) & 0xFFFFFF);
        Color tinta = fundo;
        double melhor = 1;
        for (var e : contagem.entrySet()) {
            Color c = new Color(e.getKey());
            double k = contraste(c, fundo);
            if (k > melhor) {
                melhor = k;
                tinta = c;
            }
        }
        return new Color[] {fundo, tinta};
    }

    private static boolean ehTokenDoTema(Color c) {
        for (String t : Tokens.TODAS) {
            Color k = Tokens.cor(t);
            if (k.getAlpha() == 255 && (k.getRGB() & 0xFFFFFF) == (c.getRGB() & 0xFFFFFF)) {
                return true;
            }
        }
        return false;
    }

    private static String fundoPuro(Color fundo) {
        int rgb = fundo.getRGB() & 0xFFFFFF;
        if ((rgb == 0x000000 || rgb == 0xFFFFFF) && !ehTokenDoTema(fundo)) {
            return hex(fundo);
        }
        return null;
    }

    /** Onde o ícone e o texto do botão são pintados (mesma conta do BasicButtonUI); {@code null} no que o botão não tem. */
    private static Rectangle[] ondeEstaOIconeETexto(AbstractButton b, JComponent raiz) {
        javax.swing.Icon icone = b.isEnabled() ? b.getIcon() : (b.getDisabledIcon() != null ? b.getDisabledIcon() : b.getIcon());
        java.awt.Insets in = b.getInsets();
        Rectangle vista = new Rectangle(in.left, in.top, b.getWidth() - in.left - in.right, b.getHeight() - in.top - in.bottom);
        Rectangle iconeR = new Rectangle();
        Rectangle textoR = new Rectangle();
        String texto = b.getText() == null || b.getText().isBlank() ? null : b.getText();
        SwingUtilities.layoutCompoundLabel(b, b.getFontMetrics(b.getFont()), texto, icone, b.getVerticalAlignment(), b.getHorizontalAlignment(),
            b.getVerticalTextPosition(), b.getHorizontalTextPosition(), vista, iconeR, textoR, texto == null ? 0 : b.getIconTextGap());
        Point p = SwingUtilities.convertPoint(b, iconeR.getLocation(), raiz);
        Point q = SwingUtilities.convertPoint(b, textoR.getLocation(), raiz);
        return new Rectangle[] {icone == null ? null : new Rectangle(p.x, p.y, iconeR.width, iconeR.height),
            texto == null ? null : new Rectangle(q.x, q.y, textoR.width, textoR.height)};
    }

    /** Guarda como o componente estava para voltar depois da medida. */
    private record Guardado(JComponent c, boolean habilitado, boolean marcado, boolean emCima, boolean armado, boolean apertado) { }

    private static Guardado guardar(JComponent c) {
        if (c instanceof AbstractButton b) {
            ButtonModel m = b.getModel();
            return new Guardado(c, b.isEnabled(), b.isSelected(), m.isRollover(), m.isArmed(), m.isPressed());
        }
        return new Guardado(c, c.isEnabled(), false, false, false, false);
    }

    private static void restaurar(Guardado g) {
        g.c().setEnabled(g.habilitado());
        if (g.c() instanceof AbstractButton b) {
            ButtonModel m = b.getModel();
            // desarmar ANTES de soltar: um botão apertado e armado que é solto dispara a ação dele (era um clique de verdade no meio da medida)
            m.setArmed(false);
            m.setPressed(g.apertado());
            m.setArmed(g.armado());
            m.setRollover(g.emCima());
            b.setSelected(g.marcado());
        }
    }

    private static boolean aplicar(JComponent c, Estado e) {
        switch (e) {
            case NORMAL -> { }
            case DESABILITADO -> c.setEnabled(false);
            case MOUSE_EM_CIMA -> {
                if (!(c instanceof AbstractButton b) || !b.isEnabled()) {
                    return false;
                }
                b.getModel().setRollover(true);
            }
            case APERTADO -> {
                if (!(c instanceof AbstractButton b) || !b.isEnabled()) {
                    return false;
                }
                b.getModel().setRollover(true);
                b.getModel().setArmed(true);
                b.getModel().setPressed(true);
            }
            case MARCADO -> {
                if (!(c instanceof JToggleButton b)) {
                    return false;
                }
                b.setSelected(true);
            }
        }
        return true;
    }

    /**
     * Mede todos os controles da árvore (já visível e dimensionada) nos cinco estados. A árvore volta ao estado de antes. Na EDT.
     */
    public static List<Medida> auditar(JComponent raiz, int largura, int altura) {
        return auditar(raiz, largura, altura, false);
    }

    /** @param forcarMarcado marca à força os botões de alternar (só para peças soltas, sem ouvintes que refaçam a tela) */
    public static List<Medida> auditar(JComponent raiz, int largura, int altura, boolean forcarMarcado) {
        raiz.setSize(largura, altura);
        Visual.layoutar(raiz);
        List<JComponent> alvos = interativos(raiz);
        List<Guardado> antes = new ArrayList<>();
        alvos.forEach(c -> antes.add(guardar(c)));
        List<Medida> medidas = new ArrayList<>();
        try {
            for (Estado e : Estado.values()) {
                List<JComponent> nesteEstado = new ArrayList<>();
                for (JComponent c : alvos) {
                    if (e == Estado.MARCADO && !forcarMarcado) {
                        continue; // o estado marcado de verdade já é medido no NORMAL (quem está marcado); marcar à força dispara ouvintes que refazem a tela
                    }
                    if (aplicar(c, e) && (e != Estado.MARCADO || c.isEnabled())) {
                        nesteEstado.add(c);
                    }
                }
                if (nesteEstado.isEmpty()) {
                    continue;
                }
                BufferedImage img = pintar(raiz, largura, altura);
                for (JComponent c : nesteEstado) {
                    Rectangle r = ondeEsta(c, raiz);
                    if (r.intersection(new Rectangle(0, 0, largura, altura)).isEmpty()) {
                        continue; // fora do que foi pintado (rolou para baixo): não há pixel para medir
                    }
                    Rectangle[] partes = c instanceof AbstractButton ab ? ondeEstaOIconeETexto(ab, raiz) : null;
                    Rectangle soOTexto = partes != null && partes[0] != null && partes[1] != null ? partes[1] : r;
                    Color[] ft = fundoETintaDoTexto(img, r, soOTexto);
                    boolean soIcone = c instanceof AbstractButton b && (b.getText() == null || b.getText().isBlank());
                    double minimo = soIcone ? MINIMO_DE_ICONE : MINIMO_DE_TEXTO;
                    Estado visto = e == Estado.NORMAL && c instanceof JToggleButton t && t.isSelected() ? Estado.MARCADO : e;
                    medidas.add(new Medida(nome(c, raiz), visto, ft[0], ft[1], contraste(ft[0], ft[1]), minimo, fundoPuro(ft[0])));
                    if (c instanceof AbstractButton b && b.getText() != null && !b.getText().isBlank()) {
                        Rectangle ri = partes[0];
                        if (ri != null && ri.width > 2 && !ri.intersection(new Rectangle(0, 0, largura, altura)).isEmpty()) {
                            Color[] it = fundoETintaDoIcone(img, ri);
                            medidas.add(new Medida(nome(c, raiz) + " (icone)", visto, it[0], it[1], contraste(it[0], it[1]), MINIMO_DE_ICONE, null));
                        }
                    }
                }
                for (Guardado g : antes) {
                    restaurar(g);
                }
            }
        } finally {
            antes.forEach(AuditoriaDeCores::restaurar);
        }
        return medidas;
    }
}
