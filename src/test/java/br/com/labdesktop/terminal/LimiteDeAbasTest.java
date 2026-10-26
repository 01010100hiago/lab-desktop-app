package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LimiteDeAbasTest {

    @Test
    void oitoAbasCabemANonaNao() {
        LimiteDeAbas limite = new LimiteDeAbas();
        for (int i = 0; i < 8; i++) {
            assertTrue(limite.reservar(), "aba " + (i + 1));
        }
        assertEquals(8, limite.emUso());
        assertFalse(limite.reservar(), "a nona é recusada");
        assertEquals(8, limite.emUso(), "recusar não reserva nada");
    }

    @Test
    void fecharUmaAbaLiberaAVaga() {
        LimiteDeAbas limite = new LimiteDeAbas();
        for (int i = 0; i < 8; i++) {
            limite.reservar();
        }
        limite.liberar();
        assertTrue(limite.reservar());
        assertFalse(limite.reservar());
    }

    @Test
    void liberarDemaisNaoDeixaOContadorNegativo() {
        LimiteDeAbas limite = new LimiteDeAbas();
        limite.liberar();
        limite.liberar();
        assertEquals(0, limite.emUso());
        assertTrue(limite.reservar());
        assertEquals(1, limite.emUso());
    }

    @Test
    void reservasEmParaleloNuncaPassamDoLimite() throws Exception {
        LimiteDeAbas limite = new LimiteDeAbas();
        AtomicInteger ganhou = new AtomicInteger();
        Thread[] ts = new Thread[40];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                if (limite.reservar()) {
                    ganhou.incrementAndGet();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(8, ganhou.get());
        assertEquals(8, limite.emUso());
    }

    @Test
    void aMensagemDaNonaDizOLimite() {
        assertTrue(LimiteDeAbas.mensagemDoLimite().contains("8 terminais"));
    }
}
