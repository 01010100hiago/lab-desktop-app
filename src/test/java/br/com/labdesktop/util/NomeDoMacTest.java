package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import org.junit.jupiter.api.Test;

class NomeDoMacTest {

    @Test
    void devolveUmNomeUsavelSemQuebraDeLinha() {
        String nome = NomeDoMac.hostname();
        assertFalse(nome.isBlank());
        assertFalse(nome.contains("\n") || nome.contains("\r"));
        // E dele sai sempre um nome de dispositivo aceito pelo agente.
        assertTrue(ValidadorDeNomes.dispositivoValido(ValidadorDeNomes.sugerirNomeDeDispositivo(nome)));
    }
}
