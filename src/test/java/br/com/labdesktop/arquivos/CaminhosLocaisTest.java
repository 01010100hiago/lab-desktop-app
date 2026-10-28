package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** A ponte entre o caminho do app e o do sistema, nos dois jeitos (a do Windows é texto puro e roda num Mac). */
class CaminhosLocaisTest {

    private final CaminhosLocais posix = new CaminhosLocais(false);
    private final CaminhosLocais windows = new CaminhosLocais(true);

    @Test
    void noMacENoLinuxETudoIgual() {
        assertEquals("/Users/ana/x", posix.paraTextoDoSistema("/Users/ana/x"));
        assertEquals("/Users/ana/x", posix.doTextoDoSistema("/Users/ana/x"));
        assertEquals("/", posix.paraTextoDoSistema("/"));
    }

    @Test
    void noWindowsODiscoViraOPrimeiroTrecho() {
        assertEquals("C:\\Users\\ana\\LabLocal", windows.paraTextoDoSistema("/C:/Users/ana/LabLocal"));
        assertEquals("C:\\", windows.paraTextoDoSistema("/C:"));
        assertEquals("D:\\x y", windows.paraTextoDoSistema("/d:/x y"));
        assertNull(windows.paraTextoDoSistema("/"), "a raiz é a lista de discos");
        assertEquals("/C:/Users/ana", windows.doTextoDoSistema("C:\\Users\\ana"));
        assertEquals("/C:", windows.doTextoDoSistema("C:\\"));
        assertEquals("/D:/a/b", windows.doTextoDoSistema("d:/a/b/"));
        assertThrows(IllegalArgumentException.class, () -> windows.paraTextoDoSistema("/Users/ana"));
    }
}
