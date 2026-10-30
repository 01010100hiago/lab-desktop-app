package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.seguranca.SegredoZeradoException;
import org.junit.jupiter.api.Test;

class SegredoTotpTest {

    private static final String SEGREDO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/Lab%20Desktop:lab?secret=" + SEGREDO + "&issuer=Lab%20Desktop";

    @Test
    void naoEhRecordEOToStringNaoVazaNada() {
        SegredoTotp s = SegredoTotp.de(SEGREDO, URI);
        assertFalse(SegredoTotp.class.isRecord());
        assertFalse(s.toString().contains(SEGREDO));
        assertFalse(s.toString().contains("otpauth"));
    }

    @Test
    void zerarApagaOsBytesEProibeUsoDepois() {
        SegredoTotp s = SegredoTotp.de(SEGREDO, URI);
        assertFalse(s.estaZerado());
        s.close(); // try-with-resources zera
        assertTrue(s.estaZerado());
        assertThrows(SegredoZeradoException.class, s::segredo);
        assertThrows(SegredoZeradoException.class, s::uri);
        assertThrows(SegredoZeradoException.class, s::segredoEmGrupos);
        s.zerar(); // idempotente
    }

    @Test
    void recusaFormatoErrado() {
        assertThrows(IllegalArgumentException.class, () -> SegredoTotp.de("curto", URI));
        assertThrows(IllegalArgumentException.class, () -> SegredoTotp.de(SEGREDO.toLowerCase(), URI));
        assertThrows(IllegalArgumentException.class, () -> SegredoTotp.de(SEGREDO, "https://example.com"));
        assertThrows(IllegalArgumentException.class, () -> SegredoTotp.de(SEGREDO, URI + "\nmais"));
        assertThrows(IllegalArgumentException.class, () -> SegredoTotp.de(null, URI));
        assertThrows(IllegalArgumentException.class, () -> SegredoTotp.de(SEGREDO, null));
    }
}
