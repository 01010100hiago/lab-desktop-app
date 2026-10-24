package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final Pin PIN = new Pin("ssh-ed25519", "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    @Test
    void editarSoONomeOuOUsuarioOuAPortaDoAgenteMantemOsPins() {
        ConfigServidor c = base().comPin(PIN).comNomeDoDispositivo("mac").comEstado2fa(Estado2fa.ATIVO);

        ConfigServidor soNome = c.editar("outro", "192.0.2.5", 2222, "labuser", 7000);
        assertEquals("outro", soNome.nome());
        assertEquals(List.of(PIN), soNome.pins(), "só o nome mudou: a impressão digital continua valendo");

        ConfigServidor soUsuario = c.editar("lab", "192.0.2.5", 2222, "outrousuario", 7000);
        assertEquals(List.of(PIN), soUsuario.pins());
        assertEquals("outrousuario", soUsuario.usuario());

        ConfigServidor soAgente = c.editar("lab", "192.0.2.5", 2222, "labuser", 7100);
        assertEquals(List.of(PIN), soAgente.pins());
        assertEquals(7100, soAgente.portaAgente());

        // O resto não se mexe: id, chave, token, 2FA, transporte.
        assertEquals(c.id(), soNome.id());
        assertEquals(c.chaveId(), soNome.chaveId());
        assertEquals("mac", soNome.nomeDoDispositivo());
        assertEquals(Estado2fa.ATIVO, soNome.estado2fa());
        assertEquals(c.transporte(), soNome.transporte());
    }

    @Test
    void editarOEnderecoOuAPortaDoSshApagaOsPins() {
        ConfigServidor c = base().comPin(PIN);
        assertTrue(c.editar("lab", "outro.local", 2222, "labuser", 7000).pins().isEmpty(), "outro endereço");
        assertTrue(c.editar("lab", "192.0.2.5", 22, "labuser", 7000).pins().isEmpty(), "outra porta");
        assertTrue(c.editar("lab", "outro.local", 22, "labuser", 7000).pins().isEmpty(), "os dois");
        assertEquals(List.of(PIN), c.pins(), "o original não muda");
    }

    @Test
    void oEnderecoSoComparaSemDiferencaDeMaiusculasEIgnoraEspacosNasPontas() {
        ConfigServidor c = new ConfigServidor("s1", "lab", "Lab.Local", 22, "u", "k1", 7000, List.of(PIN), null, null, null);
        assertEquals(List.of(PIN), c.editar("lab", "lab.local", 22, "u", 7000).pins(), "DNS não distingue maiúsculas");
        assertEquals(List.of(PIN), c.editar("lab", "  Lab.Local  ", 22, "u", 7000).pins());
        assertEquals("Lab.Local", c.editar("lab", "  Lab.Local  ", 22, "u", 7000).host());
        assertTrue(c.mesmoEnderecoQue(c.editar("lab", "LAB.LOCAL", 22, "u", 7000)));
        assertFalse(c.mesmoEnderecoQue(c.editar("lab", "lab.local", 23, "u", 7000)));
    }

    @Test
    void editarValidaComAsRegrasDoConstrutor() {
        ConfigServidor c = base();
        assertThrows(IllegalArgumentException.class, () -> c.editar("", "h", 22, "u", 7000));
        assertThrows(IllegalArgumentException.class, () -> c.editar("lab", "-oProxyCommand=x", 22, "u", 7000));
        assertThrows(IllegalArgumentException.class, () -> c.editar("lab", null, 22, "u", 7000));
        assertThrows(IllegalArgumentException.class, () -> c.editar("lab", "h", 0, "u", 7000));
        assertThrows(IllegalArgumentException.class, () -> c.editar("lab", "h", 22, "-u", 7000));
        assertThrows(IllegalArgumentException.class, () -> c.editar("lab", "h", 22, "u", 70000));
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
