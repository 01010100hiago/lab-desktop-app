package br.com.labdesktop.ui.tema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.util.Preferencias.Tema;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A escala central de fontes: o mapa dos tamanhos, o fator do usuário e a troca em execução. */
@ExtendWith(VerificaEdt.class)
class FontesTest {

    @AfterEach
    void voltarA100() {
        Visual.naEdt(() -> Fontes.definirPercentual(100));
    }

    @Test
    void osTamanhosNominaisViramATabelaDeLeitura() {
        assertEquals(12f, Fontes.escalar(10.5f), "rótulo de coluna: 12");
        assertEquals(12f, Fontes.escalar(11f));
        assertEquals(12.5f, Fontes.escalar(11.5f), "legenda: no mínimo 12");
        assertEquals(13f, Fontes.escalar(12f), "secundário: no mínimo 13");
        assertEquals(13.5f, Fontes.escalar(12.5f), "tabelas: 13 a 14");
        assertEquals(14f, Fontes.escalar(13f), "texto base: 14");
        assertEquals(24f, Fontes.escalar(Fontes.NOMINAL_DO_VALOR_GRANDE), "valor grande dos indicadores: pelo menos 24");
        assertEquals(Fontes.BASE, Fontes.escalar(Fontes.NOMINAL_PADRAO), "sem tamanho pedido, é o base");
    }

    @Test
    void nenhumTamanhoNominalDeUsoFicaAbaixoDoMinimo() {
        for (float nominal : new float[] {8f, 9f, 10f, 10.5f, 11f, 11.5f, 12f, 12.5f, 13f, 14f, 15f, 17f, 18f, 22f}) {
            assertTrue(Fontes.escalar(nominal) >= Fontes.MINIMO, nominal + " virou " + Fontes.escalar(nominal));
        }
    }

    @Test
    void osFatoresDoMenu() {
        assertEquals(java.util.List.of(100, 115, 130), java.util.Arrays.stream(Fontes.ESCALAS).boxed().toList());
        assertEquals(115, Fontes.maior(100));
        assertEquals(130, Fontes.maior(115));
        assertEquals(130, Fontes.maior(130), "o maior continua o maior");
        assertEquals(115, Fontes.menor(130));
        assertEquals(100, Fontes.menor(115));
        assertEquals(100, Fontes.menor(100));
        assertEquals(100, Fontes.normalizar(3));
        assertEquals(130, Fontes.normalizar(500));
        assertEquals(115, Fontes.normalizar(110));
    }

    @Test
    void oTextoBaseDosComponentesPadraoTem14() {
        Visual.tema(Tema.CLARO);
        assertEquals(14f, Visual.naEdt(() -> UIManager.getFont("Label.font").getSize2D()));
        assertEquals(14f, Visual.naEdt(() -> UIManager.getFont("Button.font").getSize2D()));
        assertEquals(14f, Visual.naEdt(() -> UIManager.getFont("TextField.font").getSize2D()));
        assertEquals(14f, Visual.naEdt(() -> UIManager.getFont("monospaced.font").getSize2D()));
    }

    @Test
    void trocarOFatorRefazAFonteDeQuemJaEstaNaTelaESoNaEscala() {
        Visual.tema(Tema.CLARO);
        GerenciadorTema g = new GerenciadorTema(Tema.CLARO, false);
        Visual.naEdt(g::instalar);
        Rotulo texto = Visual.naEdt(() -> new Rotulo("x").tamanho(12.5f));
        Rotulo grande = Visual.naEdt(() -> new Rotulo("1").mono(Fontes.NOMINAL_DO_VALOR_GRANDE).negrito());
        assertEquals(13.5f, Visual.naEdt(() -> texto.getFont().getSize2D()));
        Visual.naEdt(() -> {
            Fontes.definirPercentual(130);
            g.reaplicarFontes();
        });
        assertEquals(130, Fontes.percentual());
        assertEquals(17.5f, Visual.naEdt(() -> texto.getFont().getSize2D()), "13,5 vezes 1,3, em meio ponto");
        assertEquals(31f, Visual.naEdt(() -> grande.getFont().getSize2D()), "24 vezes 1,3");
        assertEquals(18f, Visual.naEdt(() -> UIManager.getFont("Label.font").getSize2D()), "o texto base dos componentes padrão também");
        assertTrue(Visual.naEdt(() -> grande.getFont().isBold()), "o estilo não se perde");
        Visual.naEdt(() -> {
            Fontes.definirPercentual(100);
            g.reaplicarFontes();
        });
        assertEquals(13.5f, Visual.naEdt(() -> texto.getFont().getSize2D()));
        assertEquals(14f, Visual.naEdt(() -> UIManager.getFont("Label.font").getSize2D()));
    }
}
