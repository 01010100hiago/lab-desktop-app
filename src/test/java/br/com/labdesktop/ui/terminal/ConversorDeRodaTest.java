package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConversorDeRodaTest {

    @Test
    void tresNotchesViramUmaPaginaNoSentidoDaRoda() {
        ConversorDeRoda c = new ConversorDeRoda();
        assertEquals(0, c.aoRodar(-1));
        assertEquals(0, c.aoRodar(-1));
        assertEquals(-1, c.aoRodar(-1), "roda para cima (negativa) = Page Up");
        assertEquals(0, c.aoRodar(1));
        assertEquals(0, c.aoRodar(1));
        assertEquals(1, c.aoRodar(1), "roda para baixo = Page Down");
    }

    @Test
    void trackpadAcumulaAsFracoesEDisparaSoAoPassarOLimite() {
        ConversorDeRoda c = new ConversorDeRoda();
        int paginas = 0;
        for (int i = 0; i < 25; i++) {
            paginas += Math.abs(c.aoRodar(-0.2)); // 25 x 0,2 = 5 notches: uma página só (3), e o resto não chega a outra
        }
        assertEquals(1, paginas);
    }

    @Test
    void umGestoForteDaNoMaximoUmaPaginaPorEvento() {
        ConversorDeRoda c = new ConversorDeRoda();
        assertEquals(-1, c.aoRodar(-40.0));
        assertEquals(0, c.aoRodar(-1.0), "o excesso do gesto forte não fica guardado");
        assertEquals(1, c.aoRodar(30.0));
    }

    @Test
    void mudarDeSentidoRecomecaAConta() {
        ConversorDeRoda c = new ConversorDeRoda();
        c.aoRodar(-2.5);
        assertEquals(0, c.aoRodar(1.0), "os -2,5 de antes não somam com o outro lado");
        assertEquals(0, c.aoRodar(1.0));
        assertEquals(1, c.aoRodar(1.0));
    }

    @Test
    void zeroENanSaoIgnoradosEZerarLimpa() {
        ConversorDeRoda c = new ConversorDeRoda();
        assertEquals(0, c.aoRodar(0));
        assertEquals(0, c.aoRodar(Double.NaN));
        c.aoRodar(-2.9);
        c.zerar();
        assertEquals(0, c.aoRodar(-0.2));
    }
}
