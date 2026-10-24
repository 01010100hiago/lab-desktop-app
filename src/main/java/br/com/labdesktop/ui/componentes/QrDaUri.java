package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.ui.tema.Tokens;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * O QR do "Configurar 2FA" (ARQUITETURA 10.1): {@code QRCodeWriter} do ZXing com correção de erro M e margem de 4
 * módulos, e {@code MatrixToImageWriter} com grafite sobre branco nos DOIS temas (o leitor precisa de contraste e
 * continua sem azul). O {@code BufferedImage} não se zera: o segredo existe na tela só enquanto o diálogo está
 * aberto (limite aceito, ARQUITETURA 5.5).
 */
public final class QrDaUri {

    private QrDaUri() { }

    /** Gera o QR com as cores dos tokens {@code Lab.qrTinta} e {@code Lab.qrFundo}. */
    public static BufferedImage gerar(String uri, int pixels) throws WriterException {
        return gerar(uri, pixels, Tokens.cor(Tokens.QR_TINTA), Tokens.cor(Tokens.QR_FUNDO));
    }

    public static BufferedImage gerar(String uri, int pixels, Color tinta, Color fundo) throws WriterException {
        BitMatrix matriz = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, pixels, pixels,
            Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        return MatrixToImageWriter.toBufferedImage(matriz, new MatrixToImageConfig(tinta.getRGB(), fundo.getRGB()));
    }
}
