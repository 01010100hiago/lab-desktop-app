package br.com.labdesktop.ui;

import br.com.labdesktop.plataforma.SistemaOperacional;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O ícone do app nas janelas (barra de tarefas e Alt+Tab no Windows e no Linux; o do macOS vem do {@code .app}, e aqui só serve para o app rodado direto pelo Gradle). Os PNGs de 16 a 512 px
 * são DESENHADOS em cada tamanho por {@code scripts/GerarIcones.java} e ficam em {@code ui/icone/}; o Java escolhe o mais perto do que o sistema pede. Sem janela de verdade (testes sem
 * cabeça) nada acontece.
 */
public final class IconesDoApp {

    private static final Logger LOG = LoggerFactory.getLogger(IconesDoApp.class);
    static final int[] TAMANHOS = {16, 24, 32, 48, 64, 128, 256, 512};
    private static volatile List<Image> imagens;

    private IconesDoApp() { }

    /** As imagens do ícone (carregadas uma vez). Lista vazia se os recursos não estiverem lá. */
    public static List<Image> imagens() {
        List<Image> l = imagens;
        if (l == null) {
            l = new ArrayList<>();
            for (int n : TAMANHOS) {
                try (InputStream in = IconesDoApp.class.getResourceAsStream("/br/com/labdesktop/ui/icone/icone-" + n + ".png")) {
                    if (in != null) {
                        l.add(ImageIO.read(in));
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.debug("Não li o ícone de {} px: {}", n, e.toString());
                }
            }
            imagens = List.copyOf(l);
            l = imagens;
        }
        return l;
    }

    /** Põe o ícone numa janela (um {@code JFrame} ou {@code JDialog}); o diálogo sem janela pai não aparece na barra de tarefas sem ícone. */
    public static void aplicar(Window janela) {
        List<Image> l = imagens();
        if (!l.isEmpty()) {
            janela.setIconImages(l);
        }
    }

    /** Dentro de uma imagem do jpackage o launcher define esta propriedade da JVM; rodando pelo Gradle ou pelo {@code java -cp} ela não existe. */
    static final String PROPRIEDADE_DO_JPACKAGE = "jpackage.app-version";

    /**
     * Só no Mac EMPACOTADO o ícone do Dock é o do {@code .app} (o {@code .icns} desenhado para o Dock, com as margens do macOS): trocá-lo pelo PNG bruto o deixaria diferente do ícone do
     * Finder. Fora do Mac e rodando solto (Gradle) o ícone do app vem destes PNGs.
     */
    static boolean deveTrocarOIconeDoApp(SistemaOperacional sistema, boolean imagemEmpacotada) {
        return !(sistema == SistemaOperacional.MAC && imagemEmpacotada);
    }

    /** O ícone do app no Dock ou na barra de tarefas, onde o Java deixa (macOS rodando solto e alguns Linux; o Windows usa o do executável). Nunca falha. */
    public static void aplicarAoApp() {
        if (!deveTrocarOIconeDoApp(SistemaOperacional.detectar(), System.getProperty(PROPRIEDADE_DO_JPACKAGE) != null)) {
            return;
        }
        try {
            List<Image> l = imagens();
            if (l.isEmpty() || !Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar t = Taskbar.getTaskbar();
            if (t.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                t.setIconImage(l.get(l.size() - 1));
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            LOG.debug("Sem ícone do app na barra de tarefas: {}", e.toString());
        }
    }
}
