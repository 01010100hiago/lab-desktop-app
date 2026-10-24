package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedatorTest {

    private static final String TOKEN = "lab_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v";

    @Test
    void mascaraOTokenEmQualquerLugar() {
        assertEquals(43, TOKEN.length() - 4);
        String r = Redator.redigir("falhou com " + TOKEN + " no cabeçalho");
        assertFalse(r.contains(TOKEN));
        assertTrue(r.contains("lab_***"), r);
        assertTrue(r.startsWith("falhou com "));
    }

    @Test
    void mascaraOCabecalhoAuthorization() {
        String r = Redator.redigir("Authorization: Bearer " + TOKEN + "\nHost: 127.0.0.1");
        assertFalse(r.contains(TOKEN));
        assertTrue(r.contains("Authorization: ***"), r);
        assertTrue(r.contains("Host: 127.0.0.1"));
        assertEquals("authorization=***", Redator.redigir("authorization=Basic dXNlcjpzZW5oYQ=="));
    }

    @Test
    void mascaraBearerSolto() {
        assertFalse(Redator.redigir("enviou Bearer abcdefghijklmnop1234 ao servidor").contains("abcdefghijklmnop1234"));
    }

    @Test
    void mascaraBlocoDeChavePrivada() {
        String pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\nxyz\n-----END OPENSSH PRIVATE KEY-----";
        String r = Redator.redigir("antes\n" + pem + "\ndepois");
        assertFalse(r.contains("b3BlbnNzaC1r"), r);
        assertFalse(r.contains("BEGIN OPENSSH"), r);
        assertTrue(r.contains("antes") && r.contains("depois"));
    }

    @Test
    void mascaraBlocoCortadoSemEnd() {
        String r = Redator.redigir("log: -----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA");
        assertFalse(r.contains("b3BlbnNzaC1r"), r);
        assertTrue(r.startsWith("log: "));
    }

    @Test
    void mascaraSegredoDoOtpauthEAUriInteira() {
        String uri = "otpauth://totp/Painel%20do%20Lab:lab?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Painel%20do%20Lab";
        String r = Redator.redigir("QR: " + uri + " fim");
        assertFalse(r.contains("JBSWY3DP"), r);
        assertFalse(r.contains("otpauth://totp"), r);
        assertTrue(r.contains("otpauth://***"), r);
        // O secret= solto (fora de uma URI) também é mascarado.
        String solto = Redator.redigir("parametros: x=1&secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&y=2");
        assertFalse(solto.contains("JBSWY3DP"), solto);
        assertTrue(solto.contains("secret=***"), solto);
    }

    @Test
    void mascaraParesDeSenha() {
        assertFalse(Redator.redigir("senha=abc123 ok").contains("abc123"));
        assertFalse(Redator.redigir("passphrase: hunter2").contains("hunter2"));
    }

    @Test
    void textoSemSegredoFicaIgual() {
        String t = "Conectado a 192.0.2.5:2222 como labuser em 120 ms";
        assertEquals(t, Redator.redigir(t));
        assertEquals("", Redator.redigir((String) null));
    }

    @Test
    void redigirExcecaoUsaAMensagemRedigida() {
        assertEquals("falha ao chamar com lab_***",
            Redator.redigir(new RuntimeException("falha ao chamar com " + TOKEN)));
        assertEquals("IllegalStateException", Redator.redigir(new IllegalStateException()));
        assertEquals("", Redator.redigir((Throwable) null));
    }
}
