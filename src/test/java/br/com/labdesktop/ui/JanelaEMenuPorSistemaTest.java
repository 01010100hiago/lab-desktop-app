package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.plataforma.SistemaOperacional;
import java.awt.Image;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** O que fechar a janela faz e o item "Sair" em cada sistema: o Mac esconde, o Windows e o Linux saem (uma janela escondida deixaria o app vivo e invisível). */
class JanelaEMenuPorSistemaTest {

    @Test
    void noMacFecharEsconde() {
        AtomicInteger saidas = new AtomicInteger();
        ComportamentoDaJanela c = new ComportamentoDaJanela(SistemaOperacional.MAC, saidas::incrementAndGet);
        assertTrue(c.fecharEscondeAJanela());
        assertFalse(c.temItemSair(), "no Mac o Sair é o Cmd+Q do menu do app");
    }

    @Test
    void noWindowsENoLinuxFecharSaiEHaItemSair() {
        for (SistemaOperacional so : new SistemaOperacional[] {SistemaOperacional.WINDOWS, SistemaOperacional.LINUX}) {
            ComportamentoDaJanela c = new ComportamentoDaJanela(so, () -> { });
            assertFalse(c.fecharEscondeAJanela(), so.name());
            assertTrue(c.temItemSair(), so.name());
        }
    }

    @Test
    void semAcaoDeSairTodoSistemaMantemOJeitoAntigoQueEEsconder() {
        for (SistemaOperacional so : SistemaOperacional.values()) {
            ComportamentoDaJanela c = new ComportamentoDaJanela(so, null);
            assertTrue(c.fecharEscondeAJanela(), so.name());
            assertFalse(c.temItemSair(), so.name());
        }
    }

    @Test
    void oIconeDoAppTemOsTamanhosEEstaNaPaletaNeutra() {
        var imagens = IconesDoApp.imagens();
        assertEquals(IconesDoApp.TAMANHOS.length, imagens.size());
        for (Image i : imagens) {
            assertNotNull(i);
            var b = (java.awt.image.BufferedImage) i;
            assertEquals(b.getWidth(), b.getHeight());
            // o centro do fundo é grafite: nada de azul, ciano nem dourado
            if (b.getWidth() >= 64) {
                int rgb = b.getRGB(b.getWidth() / 10 * 3, b.getHeight() / 10 * 2) & 0xFFFFFF;
                float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
                assertTrue(hsb[1] < 0.15f, "o ícone é neutro: saturação " + hsb[1]);
            }
        }
    }
}
