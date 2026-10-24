package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.extras.FlatSVGIcon.ColorFilter;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * Os ícones SVG do app (um arquivo por ícone em {@code ui/icones}, com a lista em {@code indice.txt}),
 * carregados com {@link FlatSVGIcon}. Todos são viewBox 24, traço 2, sem preenchimento e nunca emoji.
 *
 * <p>A cor sai do tema na hora de pintar: o ícone comum assume a cor do texto do componente que o desenha
 * (botão, rótulo, desabilitado e tudo o mais seguem sozinhos); {@link #colorido} fixa um token, para estados
 * (verde, âmbar, terracota). Estado nunca é só cor: o ícone sempre vem junto de um texto.
 */
public final class Icones {

    private static final String PASTA = "br/com/labdesktop/ui/icones/";

    private Icones() { }

    /** Um ícone que usa a cor do texto do componente onde é pintado. */
    public static FlatSVGIcon de(String nome, int tamanho) {
        FlatSVGIcon icone = carregar(nome, tamanho);
        icone.setColorFilter(new ColorFilter((Component c, Color original) ->
            c != null ? c.getForeground() : UIManager.getColor("Label.foreground")));
        return icone;
    }

    /** Um ícone que usa sempre o token de cor dado ({@code Tokens.OK}, {@code Tokens.PERIGO}...). */
    public static Icon colorido(String nome, int tamanho, String tokenDeCor) {
        FlatSVGIcon icone = carregar(nome, tamanho);
        icone.setColorFilter(new ColorFilter((Color original) -> Tokens.cor(tokenDeCor)));
        return icone;
    }

    private static FlatSVGIcon carregar(String nome, int tamanho) {
        if (nome == null || !nome.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Nome de ícone inválido: " + nome);
        }
        return new FlatSVGIcon(PASTA + nome + ".svg", tamanho, tamanho, Icones.class.getClassLoader());
    }

    /**
     * Carrega o jsvg e rasteriza um ícone fora da EDT. O primeiro ícone pintado custa mais de 500 ms só de carregar as
     * classes do leitor de SVG; na EDT isso era uma travada visível na primeira janela (o {@code EdtWatchdog}
     * acusava). Pode ser chamado de qualquer thread, antes mesmo de existir um tema; se falhar, o primeiro ícone
     * real paga o custo de sempre.
     */
    public static void preAquecer() {
        Graphics2D g = null;
        try {
            BufferedImage folha = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            g = folha.createGraphics();
            carregar("alerta", 16).paintIcon(null, g, 0, 0);
        } catch (RuntimeException | LinkageError e) {
            // só esquenta
        } finally {
            if (g != null) {
                g.dispose();
            }
        }
    }

    /** Os nomes de todos os ícones empacotados (de {@code indice.txt}, gerado pelo script de portar). */
    public static List<String> nomes() {
        try (InputStream in = Icones.class.getClassLoader().getResourceAsStream(PASTA + "indice.txt")) {
            if (in == null) {
                throw new IllegalStateException("indice.txt dos ícones não está nos recursos.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Não consegui ler o índice dos ícones.", e);
        }
    }
}
