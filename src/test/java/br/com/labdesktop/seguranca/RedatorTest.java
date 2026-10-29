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

    // ----- segredos da AWS: a saída do aws vai para a tela e para o log -----

    @Test
    void mascaraAChaveDeAcessoDaAws() {
        for (String chave : new String[] {"AKIAIOSFODNN7EXAMPLE", "ASIAIOSFODNN7EXAMPLE"}) {
            String r = Redator.redigir("Using access key " + chave + " for the request");
            assertFalse(r.contains(chave), r);
            assertTrue(r.contains("***"), r);
        }
        assertEquals("AKIA curto e ASIAXX nao sao chave", Redator.redigir("AKIA curto e ASIAXX nao sao chave"));
    }

    @Test
    void mascaraOArquivoDeCredenciaisDaAws() {
        String r = Redator.redigir("aws_access_key_id = AKIAIOSFODNN7EXAMPLE\naws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n"
            + "aws_session_token=IQoJb3JpZ2luX2VjEXAMPLETOKEN");
        assertFalse(r.contains("wJalrXUtnFEMI"), r);
        assertFalse(r.contains("IQoJb3JpZ2luX2Vj"), r);
        assertFalse(r.contains("AKIAIOSFODNN7EXAMPLE"), r);
        assertTrue(r.contains("aws_secret_access_key=***"), r);
        assertTrue(r.contains("aws_session_token=***"), r);
    }

    @Test
    void mascaraOsParametrosAssinadosEOTokenValueDoSessionManager() {
        String url = "https://ssm.us-east-1.amazonaws.com/?X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20261029&X-Amz-Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7&X-Amz-Security-Token=FwoGZXIvYXdzEXAMPLE&X-Amz-Date=20261029";
        String r = Redator.redigir(url);
        assertFalse(r.contains("5d672d79c15b"), r);
        assertFalse(r.contains("FwoGZXIvYXdz"), r);
        assertFalse(r.contains("AKIAIOSFODNN7EXAMPLE"), r);
        assertTrue(r.contains("X-Amz-Date=20261029"), "o resto da URL fica: " + r);
        String json = Redator.redigir("{\"SessionId\":\"sessao-0abc\",\"TokenValue\":\"AAEAAdzF0XYZEXAMPLEtoken+/==\",\"StreamUrl\":\"wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/sessao-0abc\"}");
        assertFalse(json.contains("AAEAAdzF0XYZ"), json);
        assertTrue(json.contains("\"TokenValue\":\"***"), json);
        assertTrue(json.contains("StreamUrl"), json);
    }

    @Test
    void asFrasesDoTunelNaoDeixamPassarSegredoDaAwsDaSaidaDoPrograma() {
        var f = new br.com.labdesktop.transporte.FalhaDoTunel(br.com.labdesktop.transporte.FalhaDoTunel.Tipo.SAIU,
            "O programa aws terminou sozinho. O que ele disse: aws_secret_access_key=wJalrXUtnFEMIEXAMPLE AKIAIOSFODNN7EXAMPLE");
        assertFalse(f.mensagem().contains("wJalrXUtnFEMI") || f.mensagem().contains("AKIAIOSFODNN7"), f.mensagem());
    }
}
