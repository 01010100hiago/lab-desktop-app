package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** O QR do 2FA tem de ser LEGÍVEL de verdade (decodificado de volta pelo ZXing) nos dois temas, e sem azul. */
class QrDaUriTest {

    private static final String URI = "otpauth://totp/Lab%20Desktop:lab?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        + "&issuer=Lab%20Desktop&algorithm=SHA1&digits=6&period=30";

    private static String ler(BufferedImage img) throws Exception {
        try {
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)))).getText();
        } catch (NotFoundException e) {
            throw new AssertionError("o ZXing não achou o QR na imagem", e);
        }
    }

    @Test
    void decodificaDeVoltaAUriExataNosDoisTemas() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            BufferedImage img = Visual.naEdt(() -> {
                try {
                    return QrDaUri.gerar(URI, 260);
                } catch (com.google.zxing.WriterException e) {
                    throw new AssertionError(e);
                }
            });
            assertEquals(260, img.getWidth());
            assertEquals(URI, ler(img), "tema " + tema);
        }
    }

    @Test
    void usaGrafiteSobreBrancoENenhumPixelAzul() throws Exception {
        Visual.tema(Tema.ESCURO);
        BufferedImage img = Visual.naEdt(() -> {
            try {
                return QrDaUri.gerar(URI, 200);
            } catch (com.google.zxing.WriterException e) {
                throw new AssertionError(e);
            }
        });
        int claros = 0;
        int escuros = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y));
                int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
                int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
                assertTrue(max - min < 40, "pixel colorido no QR: " + c + " em " + x + "," + y);
                if (max > 200) {
                    claros++;
                } else {
                    escuros++;
                }
            }
        }
        assertTrue(claros > escuros / 2 && escuros > 0, "o QR tem fundo claro e módulos escuros mesmo no tema escuro");
    }

    @Test
    void segredoLongoDemaisFalhaComErroEmVezDeImagemInutil() {
        String enorme = "otpauth://totp/x?secret=" + "A".repeat(5000);
        org.junit.jupiter.api.Assertions.assertThrows(com.google.zxing.WriterException.class, () -> QrDaUri.gerar(enorme, 260));
    }
}
