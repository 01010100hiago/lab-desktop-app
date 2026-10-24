package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigServidorTest {

    private static ConfigServidor base() {
        return new ConfigServidor("s1", "lab", "192.0.2.5", 2222, "labuser", "k1", 7000,
            List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
    }

    @Test
    void novoPreencheOsPadroes() {
        ConfigServidor c = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", "k1");
        assertTrue(ValidadorDeNomes.idLocalValido(c.id()), c.id());
        assertEquals(7000, c.portaAgente());
        assertTrue(c.pins().isEmpty());
        assertEquals(Transporte.DIRETO, c.transporte());
        assertNull(c.nomeDoDispositivo());
        assertEquals(Estado2fa.DESLIGADO, c.estado2fa());
        assertNotEquals(c.id(), ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", "k1").id());
    }

    @Test
    void validaOsCampos() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("S1", "lab", "h", 22, "u", "k1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "  ", "h", 22, "u", "k1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "-oProxyCommand=x", 22, "u", "k1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "h", 0, "u", "k1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "h", 65_536, "u", "k1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "h", 22, "-u", "k1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "h", 22, "u", "K1", 7000, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "h", 22, "u", "k1", 0, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "h", 22, "u", "k1", 7000, null, null, "Nome Ruim", null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "x".repeat(65), "h", 22, "u", "k1", 7000, null, null, null, null));
    }

    @Test
    void nuloViraPadraoENomeEhLimpo() {
        ConfigServidor c = new ConfigServidor("s1", "  <html>lab\n‮  ", "h", 22, "u", "k1", 7000, null, null, null, null);
        assertTrue(c.pins().isEmpty());
        assertEquals(Transporte.DIRETO, c.transporte());
        assertEquals(Estado2fa.DESLIGADO, c.estado2fa());
        assertTrue(c.nome().endsWith("<html>lab"), c.nome());
        assertTrue(c.nome().charAt(0) != '<', "o Swing não pode ver <html> no começo");
    }

    @Test
    void pinsSaoImutaveisECopiados() {
        java.util.ArrayList<Pin> lista = new java.util.ArrayList<>();
        ConfigServidor c = new ConfigServidor("s1", "lab", "h", 22, "u", "k1", 7000, lista, null, null, null);
        lista.add(new Pin("ssh-ed25519", "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertTrue(c.pins().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> c.pins().add(lista.get(0)));
    }

    @Test
    void withersCriamOutroEMantemOResto() {
        ConfigServidor c = base();
        Pin ed = new Pin("ssh-ed25519", "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Pin ed2 = new Pin("ssh-ed25519", "SHA256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        ConfigServidor com = c.comPin(ed).comNomeDoDispositivo("mac").comEstado2fa(Estado2fa.PENDENTE);
        assertEquals(List.of(ed), com.pins());
        assertEquals("mac", com.nomeDoDispositivo());
        assertEquals(Estado2fa.PENDENTE, com.estado2fa());
        assertEquals("lab", com.nome());
        assertTrue(c.pins().isEmpty(), "o original não muda");
        assertEquals(List.of(ed2), com.comPin(ed2).pins(), "mesmo algoritmo: troca");
        assertTrue(com.semPins().pins().isEmpty());
        assertEquals("k9", c.comChaveId("k9").chaveId());
    }

    @Test
    void estado2faLeOTextoDoAgente() {
        assertEquals(Estado2fa.DESLIGADO, Estado2fa.deRotulo("desligado"));
        assertEquals(Estado2fa.PENDENTE, Estado2fa.deRotulo("pendente"));
        assertEquals(Estado2fa.ATIVO, Estado2fa.deRotulo(" ATIVO "));
        assertEquals(Estado2fa.DESLIGADO, Estado2fa.deRotulo("qualquer coisa"));
        assertEquals(Estado2fa.DESLIGADO, Estado2fa.deRotulo(null));
        assertEquals("ativo", Estado2fa.ATIVO.rotulo());
    }

    @Test
    void transporteCloudflaredPedeHostname() {
        assertThrows(IllegalArgumentException.class, () -> Transporte.cloudflared(" "));
        assertEquals("ssh.example.com", Transporte.cloudflared("ssh.example.com").hostname());
        assertNull(new Transporte(Transporte.Tipo.DIRETO, "ignorado").hostname());
    }

    @Test
    void pinValidaOFormato() {
        assertThrows(IllegalArgumentException.class, () -> new Pin("", "SHA256:abcdefghijklm"));
        assertThrows(IllegalArgumentException.class, () -> new Pin("ssh ed25519", "SHA256:abcdefghijklm"));
        assertThrows(IllegalArgumentException.class, () -> new Pin("ssh-ed25519", "MD5:abcdefghijklm"));
        assertThrows(IllegalArgumentException.class, () -> new Pin("ssh-ed25519", "SHA256:abc def ghijklm"));
        assertThrows(IllegalArgumentException.class, () -> new Pin("ssh-ed25519", null));
    }
}
