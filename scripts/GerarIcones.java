import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Gera os ícones do Lab Desktop (uma vez; os arquivos gerados ficam no git): grafite e quase-branco, sem dourado nem azul. Um quadrado de cantos arredondados com
 * o "prompt" do terminal ({@code >_}). Cada tamanho é DESENHADO no seu tamanho (não reduzido de um grande), para o de 16 px continuar legível.
 *
 * <p>Uso: {@code java scripts/GerarIcones.java <pasta-do-projeto>}. Escreve:
 * <ul>
 *   <li>{@code src/main/resources/br/com/labdesktop/ui/icone/icone-<N>.png} (16 a 512): o ícone das janelas (barra de tarefas do Windows, Alt+Tab, Linux);</li>
 *   <li>{@code empacotamento/icones/lab-desktop.ico} (Windows: 16, 24, 32, 48, 64, 128 e 256, PNG dentro do ICO), {@code lab-desktop.icns} (Mac) e {@code lab-desktop.png} (Linux, 512).</li>
 * </ul>
 * Sem dependência nenhuma além do JDK: o ICO e o ICNS são escritos à mão (formatos simples, com PNG dentro).
 */
public class GerarIcones {

    static final Color GRAFITE = new Color(0x26292C);
    static final Color GRAFITE_CLARO = new Color(0x34383B);
    static final Color TRACO = new Color(0xECEEF0);

    public static void main(String[] args) throws IOException {
        Path raiz = Path.of(args.length > 0 ? args[0] : ".");
        Path janelas = raiz.resolve("src/main/resources/br/com/labdesktop/ui/icone");
        Path pacote = raiz.resolve("empacotamento/icones");
        Files.createDirectories(janelas);
        Files.createDirectories(pacote);
        for (int n : new int[] {16, 24, 32, 48, 64, 128, 256, 512}) {
            Files.write(janelas.resolve("icone-" + n + ".png"), png(n));
        }
        Files.write(pacote.resolve("lab-desktop.png"), png(512));
        Files.write(pacote.resolve("lab-desktop.ico"), ico(new int[] {16, 24, 32, 48, 64, 128, 256}));
        Files.write(pacote.resolve("lab-desktop.icns"), icns());
        System.out.println("Ícones gerados em " + janelas + " e " + pacote);
    }

    static BufferedImage desenhar(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double margem = n * 0.06;
        double lado = n - 2 * margem;
        g.setPaint(new GradientPaint(0, 0, GRAFITE_CLARO, 0, n, GRAFITE));
        g.fill(new RoundRectangle2D.Double(margem, margem, lado, lado, lado * 0.22, lado * 0.22));
        g.setColor(TRACO);
        float espessura = (float) Math.max(1.6, n * 0.085);
        g.setStroke(new BasicStroke(espessura, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D seta = new Path2D.Double();
        seta.moveTo(n * 0.30, n * 0.34);
        seta.lineTo(n * 0.50, n * 0.50);
        seta.lineTo(n * 0.30, n * 0.66);
        g.draw(seta);
        g.draw(new java.awt.geom.Line2D.Double(n * 0.55, n * 0.68, n * 0.72, n * 0.68));
        g.dispose();
        return img;
    }

    static byte[] png(int n) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(desenhar(n), "png", o);
        return o.toByteArray();
    }

    /** ICO com cada imagem em PNG (aceito desde o Windows Vista). */
    static byte[] ico(int[] tamanhos) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(saida);
        byte[][] imagens = new byte[tamanhos.length][];
        for (int i = 0; i < tamanhos.length; i++) {
            imagens[i] = png(tamanhos[i]);
        }
        d.write(new byte[] {0, 0, 1, 0});
        d.write(le16(tamanhos.length));
        int deslocamento = 6 + 16 * tamanhos.length;
        for (int i = 0; i < tamanhos.length; i++) {
            int n = tamanhos[i];
            d.write(n >= 256 ? 0 : n);
            d.write(n >= 256 ? 0 : n);
            d.write(0);
            d.write(0);
            d.write(le16(1));
            d.write(le16(32));
            d.write(le32(imagens[i].length));
            d.write(le32(deslocamento));
            deslocamento += imagens[i].length;
        }
        for (byte[] b : imagens) {
            d.write(b);
        }
        return saida.toByteArray();
    }

    /** ICNS com PNG dentro de cada tipo: icp4 (16), icp5 (32), icp6 (64), ic07 (128), ic08 (256), ic09 (512) e ic10 (1024). Tudo big-endian. */
    static byte[] icns() throws IOException {
        Object[][] tipos = {{"icp4", 16}, {"icp5", 32}, {"icp6", 64}, {"ic07", 128}, {"ic08", 256}, {"ic09", 512}, {"ic10", 1024}};
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(corpo);
        for (Object[] t : tipos) {
            byte[] b = png((Integer) t[1]);
            d.write(((String) t[0]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            d.writeInt(8 + b.length);
            d.write(b);
        }
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(saida);
        s.write("icns".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        s.writeInt(8 + corpo.size());
        s.write(corpo.toByteArray());
        return saida.toByteArray();
    }

    static byte[] le16(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8)};
    }

    static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
