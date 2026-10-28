package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** O número de processo só entra numa linha de comando se for um inteiro de 2 a 4194304. */
class ValidadorDePidTest {

    @Test
    void aceitaNumerosNormais() {
        assertTrue(ValidadorDePid.valido(2));
        assertTrue(ValidadorDePid.valido(4242));
        assertTrue(ValidadorDePid.valido(ValidadorDePid.MAIOR_PID));
        assertEquals(4242, ValidadorDePid.exigir(4242));
    }

    @Test
    void recusaOPrimeiroProcessoOZeroENegativos() {
        assertFalse(ValidadorDePid.valido(1), "o PID 1 nunca");
        assertFalse(ValidadorDePid.valido(0));
        assertFalse(ValidadorDePid.valido(-1), "kill -1 mata tudo o que o usuário tem");
        assertFalse(ValidadorDePid.valido(-4242));
        assertFalse(ValidadorDePid.valido(ValidadorDePid.MAIOR_PID + 1));
        assertFalse(ValidadorDePid.valido(Integer.MAX_VALUE));
        assertFalse(ValidadorDePid.valido(null));
    }

    @Test
    void exigirLancaParaOQueNaoVale() {
        assertThrows(IllegalArgumentException.class, () -> ValidadorDePid.exigir(1));
        assertThrows(IllegalArgumentException.class, () -> ValidadorDePid.exigir(null));
        assertThrows(IllegalArgumentException.class, () -> ValidadorDePid.exigir(-5));
    }
}
