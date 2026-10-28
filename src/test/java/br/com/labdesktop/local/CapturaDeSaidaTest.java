package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A captura guarda o começo E o fim (num {@code docker build} que falha, o motivo vem no fim). */
class CapturaDeSaidaTest {

    private static String capturar(String texto) throws Exception {
        CapturaDeSaida c = new CapturaDeSaida(new ByteArrayInputStream(texto.getBytes(StandardCharsets.UTF_8)), "teste");
        c.esperar();
        return new String(c.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void saidaPequenaVemInteira() throws Exception {
        assertEquals("linha 1\nlinha 2\n", capturar("linha 1\nlinha 2\n"));
    }

    @Test
    void saidaGrandeGuardaOComecoEOFimComUmaMarcaNoMeioSemPassarDoTeto() throws Exception {
        StringBuilder sb = new StringBuilder("COMECO DO BUILD\n");
        for (int i = 0; i < 20_000; i++) {
            sb.append("passo ").append(i).append(" baixando camada\n");
        }
        sb.append("ERRO: npm ERR! motivo de verdade da falha\n");
        CapturaDeSaida c = new CapturaDeSaida(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), "teste");
        c.esperar();
        String saida = new String(c.bytes(), StandardCharsets.UTF_8);
        assertTrue(c.truncado());
        assertTrue(saida.startsWith("COMECO DO BUILD\n"), "o começo ficou");
        assertTrue(saida.endsWith("ERRO: npm ERR! motivo de verdade da falha\n"), "o FIM ficou: é onde o erro aparece");
        assertTrue(saida.contains("[... saída cortada ...]"));
        assertTrue(c.bytes().length <= CapturaDeSaida.TETO, "não passou do teto: " + c.bytes().length);
        assertFalse(saida.contains("passo 10000 baixando"), "o meio foi cortado");
    }

    @Test
    void noLimiteExatoDoTetoDaCabecaENaoCorta() throws Exception {
        String t = "x".repeat(CapturaDeSaida.CABECA + CapturaDeSaida.CAUDA);
        CapturaDeSaida c = new CapturaDeSaida(new ByteArrayInputStream(t.getBytes(StandardCharsets.UTF_8)), "teste");
        c.esperar();
        assertFalse(c.truncado());
        assertEquals(t, new String(c.bytes(), StandardCharsets.UTF_8));
    }
}
