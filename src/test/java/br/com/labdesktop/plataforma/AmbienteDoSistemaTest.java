package br.com.labdesktop.plataforma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AmbienteDoSistemaTest {

    @Test
    void osNomeDeSistemaDaJvmViramOEnum() {
        assertEquals(SistemaOperacional.MAC, SistemaOperacional.de("Mac OS X"));
        assertEquals(SistemaOperacional.WINDOWS, SistemaOperacional.de("Windows 11"));
        assertEquals(SistemaOperacional.WINDOWS, SistemaOperacional.de("Windows Server 2022"));
        assertEquals(SistemaOperacional.LINUX, SistemaOperacional.de("Linux"));
        assertEquals(SistemaOperacional.LINUX, SistemaOperacional.de("FreeBSD"), "desconhecido vale Linux (o mais neutro)");
        assertEquals(SistemaOperacional.LINUX, SistemaOperacional.de(null));
    }

    @Test
    void variavelVaziaOuSoComEspacoNaoExiste() {
        var a = AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", "ana", Map.of("A", "", "B", "  ", "C", " x "));
        assertNull(a.variavel("A"));
        assertNull(a.variavel("B"));
        assertNull(a.variavel("Z"));
        assertEquals("x", a.variavel("C"));
    }

    @Test
    void separadorEJuntarSaoDoSistemaDoAmbienteENaoDoQueRodaOCodigo() {
        var w = AmbienteDoSistema.windowsPadrao("ana");
        assertEquals("\\", w.separador());
        assertEquals("C:\\a\\b\\c", w.juntar("C:\\a", "b", "c"));
        assertEquals("C:\\a\\b", w.juntar("C:\\a\\", "b"), "não duplica o separador");
        var m = AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of());
        assertEquals("/a/b/c", m.juntar("/a", "b", "c"));
    }

    @Test
    void caminhoAbsolutoDependeDoSistema() {
        var w = AmbienteDoSistema.windowsPadrao("ana");
        assertTrue(w.ehAbsoluto("C:\\Users\\ana"));
        assertTrue(w.ehAbsoluto("d:/dados"));
        assertTrue(w.ehAbsoluto("\\\\servidor\\pasta"));
        assertFalse(w.ehAbsoluto("\\Users\\ana"), "sem letra de disco não é absoluto no Windows");
        assertFalse(w.ehAbsoluto("dados\\x"));
        assertFalse(w.ehAbsoluto(""));
        var l = AmbienteDoSistema.de(SistemaOperacional.LINUX, "/home/ana", "ana", Map.of());
        assertTrue(l.ehAbsoluto("/home/ana"));
        assertFalse(l.ehAbsoluto("home/ana"));
        assertFalse(l.ehAbsoluto("C:\\x"));
    }

    @Test
    void oWindowsDeMentiraTemAsVariaveisDeVerdade() {
        var w = AmbienteDoSistema.windowsPadrao("ana");
        assertEquals("C:\\Users\\ana\\AppData\\Roaming", w.variavel("APPDATA"));
        assertEquals("C:\\Windows", w.variavel("SystemRoot"));
        assertEquals("ana", w.usuario());
    }
}
