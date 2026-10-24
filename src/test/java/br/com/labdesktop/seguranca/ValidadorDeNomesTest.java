package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidadorDeNomesTest {

    @Test
    void projetoSegueARegexDoAgente() {
        assertTrue(ValidadorDeNomes.projetoValido("lab"));
        assertTrue(ValidadorDeNomes.projetoValido("loja-pecas"));
        assertTrue(ValidadorDeNomes.projetoValido("a".repeat(49)));
        assertFalse(ValidadorDeNomes.projetoValido("a".repeat(50)));
        for (String ruim : new String[] {"", "-x", "X", "a b", "a;b", "a$(id)", "a`id`", "a\nb", "a/b", "../x", "a.b", null}) {
            assertFalse(ValidadorDeNomes.projetoValido(ruim), String.valueOf(ruim));
        }
        assertThrows(IllegalArgumentException.class, () -> ValidadorDeNomes.exigirProjeto("a;b"));
        assertEquals("ok", ValidadorDeNomes.exigirProjeto("ok"));
    }

    @Test
    void containerSegueARegexDoAgente() {
        assertTrue(ValidadorDeNomes.containerValido("lab-claude-lab"));
        assertTrue(ValidadorDeNomes.containerValido("Projeto_1.db-2"));
        assertFalse(ValidadorDeNomes.containerValido("_x"));
        assertFalse(ValidadorDeNomes.containerValido("a b"));
        assertFalse(ValidadorDeNomes.containerValido("a".repeat(129)));
    }

    @Test
    void nomeDoDispositivo() {
        assertTrue(ValidadorDeNomes.dispositivoValido("mac-de-teste"));
        assertFalse(ValidadorDeNomes.dispositivoValido("Mac"));
        assertFalse(ValidadorDeNomes.dispositivoValido(""));
        assertFalse(ValidadorDeNomes.dispositivoValido("a".repeat(41)));
        assertFalse(ValidadorDeNomes.dispositivoValido("a b"));
    }

    @Test
    void hashSha256() {
        assertTrue(ValidadorDeNomes.hashSha256Valido("a".repeat(64)));
        assertFalse(ValidadorDeNomes.hashSha256Valido("A".repeat(64)), "só minúsculas");
        assertFalse(ValidadorDeNomes.hashSha256Valido("a".repeat(63)));
        assertFalse(ValidadorDeNomes.hashSha256Valido("a".repeat(64) + "\n"));
    }

    @Test
    void codigoTotpAceitaEspacoDoMeio() {
        assertEquals("123456", ValidadorDeNomes.normalizarCodigoTotp("123 456"));
        assertEquals("123456", ValidadorDeNomes.normalizarCodigoTotp(" 123456 "));
        assertEquals("000123", ValidadorDeNomes.normalizarCodigoTotp("000 123"));
        assertNull(ValidadorDeNomes.normalizarCodigoTotp("12345"));
        assertNull(ValidadorDeNomes.normalizarCodigoTotp("1234567"));
        assertNull(ValidadorDeNomes.normalizarCodigoTotp("12a456"));
        assertNull(ValidadorDeNomes.normalizarCodigoTotp("١٢٣٤٥٦")); // dígitos árabes: não são [0-9]
        assertNull(ValidadorDeNomes.normalizarCodigoTotp(null));
    }

    @Test
    void sugereNomeDeDispositivoAPartirDoNomeDoMac() {
        assertEquals("mac-de-teste", ValidadorDeNomes.sugerirNomeDeDispositivo("Mac-de-Teste.local"));
        assertEquals("macbook-pro-de-maria", ValidadorDeNomes.sugerirNomeDeDispositivo("MacBook Pro de Maria"));
        assertEquals("mac", ValidadorDeNomes.sugerirNomeDeDispositivo(""));
        assertEquals("mac", ValidadorDeNomes.sugerirNomeDeDispositivo(null));
        assertEquals("mac", ValidadorDeNomes.sugerirNomeDeDispositivo("!!!"));
        String longo = ValidadorDeNomes.sugerirNomeDeDispositivo("a".repeat(100));
        assertEquals(40, longo.length());
        assertTrue(ValidadorDeNomes.dispositivoValido(longo));
        assertTrue(ValidadorDeNomes.dispositivoValido(ValidadorDeNomes.sugerirNomeDeDispositivo("Ação-ç.local")));
    }

    @Test
    void usuarioEHostNaoComecamComHifen() {
        assertTrue(ValidadorDeNomes.usuarioSshValido("labuser"));
        assertFalse(ValidadorDeNomes.usuarioSshValido("-oProxyCommand=x"));
        assertFalse(ValidadorDeNomes.usuarioSshValido("a b"));
        assertTrue(ValidadorDeNomes.hostValido("192.0.2.5"));
        assertTrue(ValidadorDeNomes.hostValido("lab.local"));
        assertTrue(ValidadorDeNomes.hostValido("fe80::1"));
        assertFalse(ValidadorDeNomes.hostValido("-oProxyCommand=x"));
        assertFalse(ValidadorDeNomes.hostValido("a b"));
        assertFalse(ValidadorDeNomes.hostValido("a;b"));
        assertFalse(ValidadorDeNomes.hostValido(""));
    }

    @Test
    void idLocal() {
        assertTrue(ValidadorDeNomes.idLocalValido("k1"));
        assertTrue(ValidadorDeNomes.idLocalValido("s3f9a1c2b"));
        assertFalse(ValidadorDeNomes.idLocalValido("K1"));
        assertFalse(ValidadorDeNomes.idLocalValido("-k1"));
        assertFalse(ValidadorDeNomes.idLocalValido(""));
    }
}
