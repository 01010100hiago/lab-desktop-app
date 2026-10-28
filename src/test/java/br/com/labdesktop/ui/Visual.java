package br.com.labdesktop.ui;

import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Apoio dos testes de tela SEM janela (o Swing sem cabeça não cria {@code JFrame}): instala o tema, monta o layout
 * de um componente solto, pinta num {@code BufferedImage} e grava o PNG em {@code build/telas/} para o olho
 * humano conferir. Também liga a EDT ao {@link Fundo}, como o {@code Principal} faz.
 */
public final class Visual {

    private static boolean pronto;

    private Visual() { }

    /** Prepara a JVM de teste: sem cabeça, fontes e tema registrados, EDT ligada ao Fundo. */
    public static synchronized void preparar() {
        if (pronto) {
            return;
        }
        System.setProperty("java.awt.headless", "true");
        Fundo.definirEdt(SwingUtilities::invokeLater);
        GerenciadorTema.preparar();
        VerificadorDeEdt.instalar();
        pronto = true;
    }

    /** Instala o tema (na EDT) e espera. */
    public static void tema(Tema tema) {
        preparar();
        naEdt(() -> new GerenciadorTema(tema, false).instalar());
    }

    /** Roda na EDT e espera; relança o que a tarefa lançar. */
    public static void naEdt(Runnable tarefa) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                tarefa.run();
            } else {
                SwingUtilities.invokeAndWait(tarefa);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Roda na EDT e devolve o resultado. */
    public static <T> T naEdt(java.util.function.Supplier<T> tarefa) {
        AtomicReference<T> r = new AtomicReference<>();
        naEdt(() -> r.set(tarefa.get()));
        return r.get();
    }

    /** Dimensiona, faz o layout (recursivo) e pinta sobre o fundo do tema. */
    public static BufferedImage renderizar(JComponent c, int largura, int altura) {
        return naEdt(() -> {
            c.setSize(largura, altura);
            layoutar(c);
            BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Tokens.cor(Tokens.BG));
            g.fillRect(0, 0, largura, altura);
            c.paint(g);
            g.dispose();
            return img;
        });
    }

    /** Altura que o componente pede para uma largura (para renderizar sem cortar). */
    public static int alturaPreferida(JComponent c, int largura) {
        return naEdt(() -> {
            c.setSize(largura, 10);
            layoutar(c);
            int h = c.getPreferredSize().height;
            c.setSize(largura, h);
            layoutar(c);
            return c.getPreferredSize().height;
        });
    }

    /**
     * Faz o layout até estabilizar: o {@code TextoCorrido} e o {@code Selo} descobrem a altura certa só depois de
     * receberem a largura e pedem um novo layout à EDT (o "revalidate" deles). Na janela de verdade isso é uma passada a
     * mais; aqui o teste espera a EDT esvaziar entre as passadas para pintar o que o usuário veria.
     */
    public static void layoutar(Component c) {
        // Mínimo de 4 passadas (o que as telas antigas sempre tiveram) e, depois, até as medidas pararem de mudar: uma tela
        // com colunas de peso (a página do projeto) leva mais passadas para estabilizar do que o revalidate leva na janela.
        long anterior = medidas(c);
        for (int i = 0; i < 16; i++) {
            layoutarUmaVez(c); // cada passada invalida antes, como o revalidate faria: os layouts guardam os tamanhos
            long agora = medidas(c);
            if (i >= 3 && agora == anterior) {
                return;
            }
            anterior = agora;
        }
    }

    /** Uma impressão digital das posições e tamanhos de tudo, para saber se o layout parou de mexer. */
    private static long medidas(Component c) {
        long h = c.getX() * 31L + c.getY() * 17L + c.getWidth() * 13L + c.getHeight() * 7L;
        if (c instanceof Container k) {
            for (Component filho : k.getComponents()) {
                h = h * 1_000_003L + medidas(filho);
            }
        }
        return h;
    }

    private static void layoutarUmaVez(Component c) {
        if (c instanceof Container k) {
            k.invalidate();
            k.doLayout();
            for (Component filho : k.getComponents()) {
                layoutarUmaVez(filho);
            }
            k.doLayout();
        }
    }

    /** Grava o PNG em build/telas/&lt;nome&gt;.png (dentro do diretório do projeto, ignorado pelo git). */
    public static Path salvar(BufferedImage img, String nome) {
        try {
            // com o LaF forçado (-Plabdesktop.laf=generico, "como no Windows" num Mac) os PNGs vão para uma pasta à parte e não misturam com os do estilo natural
            Path pasta = Path.of("build", System.getProperty(EstiloDeLaf.PROPRIEDADE) == null ? "telas" : "telas-" + EstiloDeLaf.padrao().name().toLowerCase(java.util.Locale.ROOT));
            Files.createDirectories(pasta);
            Path arquivo = pasta.resolve(nome + ".png");
            ImageIO.write(img, "png", arquivo.toFile());
            return arquivo;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Grava a imagem da GALERIA do README: o conteúdo (já renderizado) dentro da moldura de janela do macOS, em {@code build/galeria/<nome>.png}. O título da
     * janela é "Lab Desktop"; o de um diálogo é o dele. Não mexe no PNG sem moldura de {@link #salvar}.
     */
    public static Path salvarNaGaleria(BufferedImage conteudo, String nome, String titulo, boolean escuro, MolduraDeJanela.Tipo tipo) {
        try {
            Path pasta = Path.of("build", "galeria");
            Files.createDirectories(pasta);
            Path arquivo = pasta.resolve(nome + ".png");
            ImageIO.write(MolduraDeJanela.aplicar(conteudo, titulo, escuro, tipo), "png", arquivo.toFile());
            return arquivo;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Põe no topo todas as barras de rolagem verticais visíveis sob o componente (a imagem da galeria começa pelo começo da tela). */
    public static void rolarParaOTopo(java.awt.Container raiz) {
        naEdt(() -> rolar(raiz));
    }

    private static void rolar(java.awt.Component c) {
        if (c instanceof javax.swing.JScrollPane sp) {
            sp.getVerticalScrollBar().setValue(0);
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                rolar(f);
            }
        }
    }

    /**
     * A galeria nos dois temas: renderiza o componente no tema de agora (claro), grava com a moldura, troca para o escuro, renderiza de novo e volta ao claro.
     * Os nomes saem como {@code <nome>-claro.png} e {@code <nome>-escuro.png} em {@code build/galeria/}.
     */
    public static void galeriaNosDoisTemas(JComponent c, int largura, int altura, String nome, String titulo, MolduraDeJanela.Tipo tipo) {
        ExemplosDaGaleria.aplicar(c); // endereços, portas e impressões com cara de exemplo (só na tela, depois de os testes conferirem os valores de verdade)
        salvarNaGaleria(renderizar(c, largura, altura), nome + "-claro", titulo, false, tipo);
        tema(br.com.labdesktop.util.Preferencias.Tema.ESCURO);
        naEdt(() -> SwingUtilities.updateComponentTreeUI(c));
        ExemplosDaGaleria.aplicar(c);
        salvarNaGaleria(renderizar(c, largura, altura), nome + "-escuro", titulo, true, tipo);
        tema(br.com.labdesktop.util.Preferencias.Tema.CLARO);
        naEdt(() -> SwingUtilities.updateComponentTreeUI(c));
    }

    /** Conta pixels de uma cor (com tolerância) numa região: prova que algo foi pintado na cor do token. */
    public static int contarPixels(BufferedImage img, Color cor, int tolerancia) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color p = new Color(img.getRGB(x, y), true);
                if (Math.abs(p.getRed() - cor.getRed()) <= tolerancia && Math.abs(p.getGreen() - cor.getGreen()) <= tolerancia
                    && Math.abs(p.getBlue() - cor.getBlue()) <= tolerancia) {
                    n++;
                }
            }
        }
        return n;
    }
}
