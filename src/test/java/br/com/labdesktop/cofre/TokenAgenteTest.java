package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenAgenteTest {

    private static String texto(TokenAgente t) {
        return t.cabecalhoAuthorization().substring("Bearer ".length());
    }

    @Test
    void tokenGeradoTemOFormatoDoContrato() {
        TokenAgente t = TokenAgente.gerar();
        String txt = texto(t);
        assertEquals(47, txt.length());
        assertTrue(ValidadorDeNomes.TOKEN.matcher(txt).matches(), txt);
        assertTrue(txt.startsWith("lab_"));
        assertTrue(t.cabecalhoAuthorization().startsWith("Bearer lab_"));
    }

    @Test
    void tokensGeradosSaoDistintos() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            assertTrue(vistos.add(texto(TokenAgente.gerar())));
        }
    }

    @Test
    void hashEOSha256DoTextoInteiroEmHexMinusculo() throws Exception {
        TokenAgente t = TokenAgente.gerar();
        String esperado = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(texto(t).getBytes(StandardCharsets.UTF_8)));
        assertEquals(esperado, t.hashSha256Hex());
        assertTrue(ValidadorDeNomes.hashSha256Valido(t.hashSha256Hex()));
    }

    @Test
    void toStringNaoMostraNada() {
        TokenAgente t = TokenAgente.gerar();
        assertEquals("TokenAgente[redigido]", t.toString());
        assertFalse(t.toString().contains(texto(t).substring(4)));
    }

    @Test
    void copiaEhIndependente() {
        TokenAgente a = TokenAgente.gerar();
        TokenAgente b = a.copia();
        assertEquals(a.hashSha256Hex(), b.hashSha256Hex());
        a.zerar();
        assertTrue(a.estaZerado());
        assertFalse(b.estaZerado());
        assertEquals(47, texto(b).length());
    }

    @Test
    void zerarDeVerdadeEUsoDepoisFalha() {
        TokenAgente t = TokenAgente.gerar();
        assertFalse(t.estaZerado());
        t.zerar();
        assertTrue(t.estaZerado());
        assertThrows(SegredoZeradoException.class, t::cabecalhoAuthorization);
        assertThrows(SegredoZeradoException.class, t::hashSha256Hex);
        assertThrows(SegredoZeradoException.class, t::copia);
        t.zerar(); // idempotente
    }

    @Test
    void closeZera() {
        TokenAgente t = TokenAgente.gerar();
        try (t) {
            assertFalse(t.estaZerado());
        }
        assertTrue(t.estaZerado());
    }

    @Test
    void deBytesValidaOFormatoECopia() {
        byte[] ok = ("lab_" + "a".repeat(43)).getBytes(StandardCharsets.US_ASCII);
        TokenAgente t = TokenAgente.deBytes(ok);
        ok[5] = 'Z'; // o original não é o do token
        assertEquals("Bearer lab_" + "a".repeat(43), t.cabecalhoAuthorization());

        assertThrows(IllegalArgumentException.class, () -> TokenAgente.deBytes(null));
        assertThrows(IllegalArgumentException.class, () -> TokenAgente.deBytes("lab_curto".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class,
            () -> TokenAgente.deBytes(("xxx_" + "a".repeat(43)).getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class,
            () -> TokenAgente.deBytes(("lab_" + "a".repeat(42) + "!").getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class,
            () -> TokenAgente.deBytes(("lab_" + "a".repeat(42) + "\n").getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void doisTokensComOMesmoTextoTemOMesmoHashMasNaoSaoIguais() {
        byte[] b = ("lab_" + "b".repeat(43)).getBytes(StandardCharsets.US_ASCII);
        TokenAgente x = TokenAgente.deBytes(b);
        TokenAgente y = TokenAgente.deBytes(b);
        assertEquals(x.hashSha256Hex(), y.hashSha256Hex());
        assertNotEquals(x, y, "identidade: comparar segredo é com método próprio");
    }
}
