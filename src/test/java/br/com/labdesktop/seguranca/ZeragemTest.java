package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZeragemTest {

    @Test
    void zeraBytesECharsEAceitaNulo() {
        byte[] b = {1, 2, 3};
        char[] c = {'a', 'b'};
        Zeragem.zerar(b);
        Zeragem.zerar(c);
        assertArrayEquals(new byte[3], b);
        assertArrayEquals(new char[2], c);
        Zeragem.zerar((byte[]) null);
        Zeragem.zerar((char[]) null);
        Zeragem.zerar((Segredo[]) null);
    }

    @Test
    void todoZeroConfereOConteudoDeVerdade() {
        assertTrue(Zeragem.todoZero(new byte[8]));
        assertTrue(Zeragem.todoZero(null));
        assertTrue(Zeragem.todoZero(new byte[0]));
        byte[] b = new byte[8];
        b[7] = 1;
        assertFalse(Zeragem.todoZero(b));
        b[7] = 0;
        b[0] = (byte) 0x80;
        assertFalse(Zeragem.todoZero(b));
    }

    @Test
    void zerarVariosSegredosComNuloNoMeio() {
        class Fake implements Segredo {
            boolean zerado;

            @Override
            public void zerar() {
                zerado = true;
            }

            @Override
            public boolean estaZerado() {
                return zerado;
            }
        }
        Fake a = new Fake();
        Fake b = new Fake();
        Zeragem.zerar(a, null, b);
        assertTrue(a.zerado && b.zerado);
    }

    @Test
    void closeDoSegredoZera() {
        byte[] dado = {9, 9};
        Segredo s = new Segredo() {
            @Override
            public void zerar() {
                Zeragem.zerar(dado);
            }

            @Override
            public boolean estaZerado() {
                return Zeragem.todoZero(dado);
            }
        };
        try (s) {
            assertFalse(s.estaZerado());
        }
        assertTrue(s.estaZerado());
    }
}
