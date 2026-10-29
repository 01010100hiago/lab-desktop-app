package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.EntradasHostis;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * As regras estritas de "Como conectar": cada campo, as bordas de tamanho e as entradas HOSTIS que nunca podem chegar a um argumento de programa externo.
 */
class ValidadorDeTransporteTest {

    @Test
    void nenhumaEntradaHostilValeEmCampoAlgum() {
        for (String h : EntradasHostis.TODAS) {
            assertFalse(ValidadorDeTransporte.instanciaValida(h), "instância: " + visivel(h));
            assertFalse(ValidadorDeTransporte.regiaoValida(h), "região: " + visivel(h));
            assertFalse(ValidadorDeTransporte.hostnameValido(h), "hostname: " + visivel(h));
            assertFalse(ValidadorDeTransporte.erroDaInstancia(h).isEmpty(), "instância dá erro: " + visivel(h));
            assertFalse(ValidadorDeTransporte.erroDoHostname(h).isEmpty(), "hostname dá erro: " + visivel(h));
            if (!h.isBlank()) {
                assertFalse(ValidadorDeTransporte.perfilValido(h), "perfil: " + visivel(h));
                assertFalse(ValidadorDeTransporte.erroDoPerfil(h).isEmpty(), "perfil dá erro: " + visivel(h));
                assertFalse(ValidadorDeTransporte.erroDaRegiao(h).isEmpty(), "região dá erro: " + visivel(h));
            }
        }
    }

    private static String visivel(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…(" + s.length() + ")" : s.replace("\n", "\\n").replace("\r", "\\r").replace("\u0000", "\\0");
    }

    // ----- instância -----

    @Test
    void instanciaAceitaIOuMiComOitoADezesseteHexadecimaisMinusculos() {
        for (String ok : List.of("i-0abc1234", "i-0123456789abcdef0", "mi-0123456789abcdef0", "mi-abcdef12", "i-00000000")) {
            assertTrue(ValidadorDeTransporte.instanciaValida(ok), ok);
            assertEquals("", ValidadorDeTransporte.erroDaInstancia(ok));
        }
        for (String ruim : List.of("i-0abc123", "i-0123456789abcdef01", "mi-0abc12", "I-0abc1234", "i-0ABC1234", "i-0abcg234", "x-0abc1234", "i0abc1234def567890", "i--0abc1234", "i-", "", "ami-0abc1234")) {
            assertFalse(ValidadorDeTransporte.instanciaValida(ruim), ruim);
        }
        assertFalse(ValidadorDeTransporte.instanciaValida(null));
        assertFalse(ValidadorDeTransporte.erroDaInstancia(null).isEmpty());
    }

    // ----- região -----

    @Test
    void regiaoAceitaOsFormatosDaAwsEUmaVaziaVaiParaOPerfil() {
        for (String ok : List.of("us-east-1", "eu-west-3", "ap-southeast-2", "us-gov-west-1", "sa-east-1", "me-south-1", "ap-northeast-1", "ca-central-1", "il-central-1", "eusc-de-east-1", "us-iso-east-1", "cn-north-1")) {
            assertTrue(ValidadorDeTransporte.regiaoValida(ok), ok);
            assertEquals("", ValidadorDeTransporte.erroDaRegiao(ok));
        }
        for (String ruim : List.of("US-EAST-1", "us-east", "us_east_1", "useast1", "us-east-1;", "us-east-123", "-us-east-1", "us--east-1", "u-east-1", "us-east-1\n", "x".repeat(31))) {
            assertFalse(ValidadorDeTransporte.regiaoValida(ruim), ruim);
        }
        assertTrue(ValidadorDeTransporte.regiaoOpcionalValida(null));
        assertTrue(ValidadorDeTransporte.regiaoOpcionalValida(""));
        assertEquals("", ValidadorDeTransporte.erroDaRegiao(""));
        assertEquals("", ValidadorDeTransporte.erroDaRegiao(null));
        assertFalse(ValidadorDeTransporte.regiaoOpcionalValida("us east 1"));
    }

    // ----- perfil -----

    @Test
    void perfilEOpcionalENuncaComecaComHifenOuPonto() {
        for (String ok : List.of("default", "meu-perfil", "meu.perfil_1", "A", "_x", "9a", "a".repeat(64))) {
            assertTrue(ValidadorDeTransporte.perfilValido(ok), ok);
            assertEquals("", ValidadorDeTransporte.erroDoPerfil(ok));
        }
        assertTrue(ValidadorDeTransporte.perfilValido(null));
        assertTrue(ValidadorDeTransporte.perfilValido(""));
        for (String ruim : List.of("-x", "--profile=x", ".x", "..", "a b", "a".repeat(65), "perfil;1", "perfil\n")) {
            assertFalse(ValidadorDeTransporte.perfilValido(ruim), ruim);
        }
    }

    // ----- porta -----

    @Test
    void portaVaiDe1A65535() {
        assertTrue(ValidadorDeTransporte.portaValida(1));
        assertTrue(ValidadorDeTransporte.portaValida(22));
        assertTrue(ValidadorDeTransporte.portaValida(65_535));
        assertFalse(ValidadorDeTransporte.portaValida(0));
        assertFalse(ValidadorDeTransporte.portaValida(-1));
        assertFalse(ValidadorDeTransporte.portaValida(65_536));
        assertEquals("", ValidadorDeTransporte.erroDaPorta("22"));
        assertEquals("", ValidadorDeTransporte.erroDaPorta(" 2222 "));
        for (String ruim : List.of("", " ", "abc", "22;", "0", "65536", "-1", "2.2", "２２")) {
            assertFalse(ValidadorDeTransporte.erroDaPorta(ruim).isEmpty(), ruim);
        }
    }

    // ----- hostname da Cloudflare -----

    @Test
    void hostnameAceitaNomeDnsELevaAMinusculas() {
        assertEquals(Optional.of("ssh.example.com"), ValidadorDeTransporte.hostnameNormalizado("ssh.example.com"));
        assertEquals(Optional.of("ssh.example.com"), ValidadorDeTransporte.hostnameNormalizado("SSH.Example.com"));
        assertEquals(Optional.of("a-b.c1.io"), ValidadorDeTransporte.hostnameNormalizado("a-b.c1.io"));
        assertEquals(Optional.of("xn--abc.com"), ValidadorDeTransporte.hostnameNormalizado("xn--abc.com"));
        assertEquals(Optional.of("a.b"), ValidadorDeTransporte.hostnameNormalizado("a.b"));
        assertTrue(ValidadorDeTransporte.hostnameValido("ssh-teste.example.com"));
        assertEquals("", ValidadorDeTransporte.erroDoHostname("ssh-teste.example.com"));
    }

    @Test
    void hostnameRecusaEsquemaCaminhoPortaUsuarioEFormasQuebradas() {
        for (String ruim : List.of("https://ssh.example.com", "http://a.b", "ssh://a.b", "a.b/c", "a.b/", "a.b:22", "user@a.b", "localhost", "semponto", "a..b", ".a.b", "a.b.", "-a.b", "a-.b", "a.-b",
            "a.b c", "a_b.com", "a.b.com\n", "ä.com", "ab.cöm", "a".repeat(64) + ".com", ("a".repeat(60) + ".").repeat(5) + "com", "192.0.2.4/x", "[::1]", "::1")) {
            assertFalse(ValidadorDeTransporte.hostnameValido(ruim), visivel(ruim));
            assertFalse(ValidadorDeTransporte.erroDoHostname(ruim).isEmpty(), visivel(ruim));
        }
        assertTrue(ValidadorDeTransporte.erroDoHostname("https://ssh.example.com").contains("sem https://"));
        assertTrue(ValidadorDeTransporte.erroDoHostname("").contains("Digite"));
        assertFalse(ValidadorDeTransporte.hostnameValido(null));
    }

    @Test
    void oKelvinEOutrasLetrasUnicodeQueViramAsciiNaCaixaBaixaNaoPassam() {
        // "K" (sinal Kelvin, U+212A) vira "k" ao baixar a caixa: se o filtro fosse depois de baixar, passaria. O filtro é ANTES.
        assertFalse(ValidadorDeTransporte.hostnameValido("\u212A.example.com"));
        assertFalse(ValidadorDeTransporte.hostnameValido("exemplo.\u0130x.com")); // I com ponto
    }

    @Test
    void asFrasesDeErroSaoSimplesESemSegredo() {
        for (String f : List.of(ValidadorDeTransporte.erroDaInstancia("x"), ValidadorDeTransporte.erroDaRegiao("x"), ValidadorDeTransporte.erroDoPerfil("-x"),
            ValidadorDeTransporte.erroDaPorta("x"), ValidadorDeTransporte.erroDoHostname("x"))) {
            assertFalse(f.isBlank());
            assertFalse(f.contains("Exception"), f);
            assertTrue(f.length() < 200, f);
        }
    }
}
