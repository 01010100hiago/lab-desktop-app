package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Versao;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** A janelinha de abertura: sem tela não faz nada (autoteste e modo sem cabeça seguem), e o desenho tem o nome do app e "Abrindo...". */
class JanelaDeAberturaTest {

    @Test
    void semTelaMostrarEFecharNaoFazemNadaENaoLancam() {
        System.setProperty("java.awt.headless", "true");
        assertDoesNotThrow(() -> {
            JanelaDeAbertura j = JanelaDeAbertura.mostrar();
            j.fechar();
            j.fechar(); // idempotente
        });
    }

    @Test
    void oDesenhoUsaCoresNeutrasEFixasENomeiaOApp() {
        Visual.preparar();
        JanelaDeAbertura.PainelDeAbertura p = Visual.naEdt(JanelaDeAbertura.PainelDeAbertura::new);
        assertEquals(Versao.NOME + ": abrindo", Visual.naEdt(() -> p.getAccessibleContext().getAccessibleName()));
        BufferedImage img = Visual.renderizar(p, 360, 170);
        Visual.salvar(img, "janelinha-de-abertura");
        // fundo grafite neutro: nenhum pixel com matiz azul vibrante (o TesteSemAzul varre o UIManager; aqui as cores são fixas; os cinzas do tema têm um leve viés frio, como o #1D2024)
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y));
                float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
                boolean azulOuCiano = hsb[0] * 360 >= 165 && hsb[0] * 360 <= 275 && hsb[1] >= 0.25f && hsb[2] >= 0.15f;
                assertTrue(!azulOuCiano, "pixel azulado em " + x + "," + y + ": " + c);
            }
        }
    }
}
