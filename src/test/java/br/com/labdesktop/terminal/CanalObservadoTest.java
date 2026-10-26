package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CanalObservadoTest {

    private static CanalDeTerminal canalCom(byte[] saida) {
        return new CanalDeTerminal() {
            private final InputStream in = new ByteArrayInputStream(saida);

            @Override
            public InputStream saida() {
                return in;
            }

            @Override
            public OutputStream entrada() {
                return new ByteArrayOutputStream();
            }

            @Override
            public void redimensionar(TamanhoDeTerminal t) { }

            @Override
            public boolean aberto() {
                return true;
            }

            @Override
            public Integer aguardarFim() {
                return 0;
            }

            @Override
            public Integer statusDeSaida() {
                return 0;
            }

            @Override
            public void fechar() { }
        };
    }

    @Test
    void guardaOsUltimosDoisKilobytesEmOrdem() throws IOException {
        byte[] tudo = new byte[5000];
        for (int i = 0; i < tudo.length; i++) {
            tudo[i] = (byte) ('a' + (i % 26));
        }
        CanalObservado c = new CanalObservado(canalCom(tudo));
        byte[] lixo = new byte[777];
        while (c.saida().read(lixo) != -1) {
            // lê em pedaços que não dividem 2048: o anel precisa dar a volta no meio de um pedaço
        }
        assertArrayEquals(Arrays.copyOfRange(tudo, 5000 - 2048, 5000), c.cauda());
    }

    @Test
    void saidaCurtaVemInteira() throws IOException {
        byte[] t = "Error response from daemon: No such container: x\r\n".getBytes(StandardCharsets.UTF_8);
        CanalObservado c = new CanalObservado(canalCom(t));
        c.saida().readAllBytes();
        assertArrayEquals(t, c.cauda());
        assertEquals(0, new CanalObservado(canalCom(new byte[0])).cauda().length);
    }

    @Test
    void sabeQuandoOLeitorViuOFim() throws Exception {
        CanalObservado c = new CanalObservado(canalCom("abc".getBytes(StandardCharsets.UTF_8)));
        assertFalse(c.aguardarLeituraDoFim(30), "ninguém leu até o fim ainda");
        c.saida().readAllBytes();
        assertTrue(c.aguardarLeituraDoFim(30));
    }
}
