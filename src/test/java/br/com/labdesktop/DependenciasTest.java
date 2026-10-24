package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O build declara TODAS as dependências do ARQUITETURA 15 para o próximo agente não precisar editar o
 * {@code build.gradle.kts}. Isto confere que cada uma chegou ao classpath (carrega uma classe-chave, sem
 * inicializá-la) e que as exclusões do ZXing não quebram o que o app vai usar.
 */
class DependenciasTest {

    private static void carrega(String classe) {
        try {
            Class.forName(classe, false, DependenciasTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Dependência ausente do classpath: " + classe, e);
        }
    }

    @Test
    void todasAsBibliotecasDoDocumentoEstaoNoClasspath() {
        carrega("org.apache.sshd.client.SshClient");            // sshd-core
        carrega("org.apache.sshd.server.SshServer");            // sshd-core (testes)
        carrega("org.apache.sshd.common.config.keys.KeyUtils"); // sshd-common
        carrega("org.bouncycastle.crypto.generators.Argon2BytesGenerator"); // bcprov
        carrega("org.bouncycastle.openssl.PEMParser");          // bcpkix
        carrega("com.jediterm.terminal.TtyConnector");          // jediterm-core
        carrega("com.jediterm.terminal.ui.JediTermWidget");     // jediterm-ui
        carrega("com.formdev.flatlaf.FlatLaf");                 // flatlaf
        carrega("com.formdev.flatlaf.themes.FlatMacLightLaf");
        carrega("com.formdev.flatlaf.extras.FlatSVGIcon");      // flatlaf-extras
        carrega("com.formdev.flatlaf.fonts.inter.FlatInterFont");
        carrega("com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont");
        carrega("com.google.zxing.qrcode.QRCodeWriter");        // zxing core
        carrega("com.google.zxing.client.j2se.MatrixToImageWriter"); // zxing javase
        carrega("com.fasterxml.jackson.databind.ObjectMapper");
        carrega("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        carrega("org.slf4j.LoggerFactory");
        carrega("org.slf4j.simple.SimpleLogger");               // slf4j-simple (runtimeOnly)
    }

    @Test
    void asExclusoesDoZxingJavaseValem() {
        // jcommander (CLI do ZXing) e jai-imageio-core (leitores extras do ImageIO) ficam de fora.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.beust.jcommander.JCommander"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.jcommander.JCommander"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.github.jaiimageio.plugins.tiff.TIFFImageReadParam"));
    }

    @Test
    void qrDeIdaEVoltaSemOsTransitivosExcluidos() throws Exception {
        // O mesmo caminho do "Configurar 2FA" (ARQUITETURA 10.1): QRCodeWriter com correção M e margem 4,
        // MatrixToImageWriter em grafite sobre branco, lido de volta com MultiFormatReader.
        String uri = "otpauth://totp/Painel%20do%20Lab:lab?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Painel%20do%20Lab";
        BitMatrix matriz = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 280, 280,
            Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        BufferedImage imagem = MatrixToImageWriter.toBufferedImage(matriz, new MatrixToImageConfig(0xFF1C1A16, 0xFFFFFFFF));
        assertEquals(280, imagem.getWidth());
        var lido = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(imagem))));
        assertEquals(uri, lido.getText());
        assertTrue(lido.getText().contains("secret="));
    }
}
