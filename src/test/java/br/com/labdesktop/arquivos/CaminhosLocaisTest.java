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

    // ---------------------------------------------------------------------------------------------
    // Valores conhecidos: o resultado esperado em cada SISTEMA, com o sistema injetado (nada depende de onde o teste roda)
    // ---------------------------------------------------------------------------------------------

    @Test
    void doTextoDoSistemaNoWindowsEmCadaFormaDeEscreverUmCaminho() {
        assertEquals("/D:/proj/a", windows.doTextoDoSistema("D:\\proj\\a"));
        assertEquals("/D:/proj/a", windows.doTextoDoSistema("D:/proj/a"));
        assertEquals("/D:/proj/a", windows.doTextoDoSistema("d:\\proj\\a\\"), "disco em minúscula e barra do fim");
        assertEquals("/D:/proj/a", windows.doTextoDoSistema("D:\\proj/a"), "as duas barras misturadas");
        assertEquals("/C:", windows.doTextoDoSistema("C:\\"), "a raiz do disco");
        assertEquals("/C:", windows.doTextoDoSistema("C:/"));
        assertEquals("/C:", windows.doTextoDoSistema("C:"), "só o disco");
        assertEquals("/D:/Pasta com espaço/ação", windows.doTextoDoSistema("D:\\Pasta com espaço\\ação"));
        assertEquals("/D:/proj/../a", windows.doTextoDoSistema("D:\\proj\\..\\a"), "o .. fica como veio: quem resolve é o CaminhoRemoto");
        assertEquals("/C:/x", windows.doTextoDoSistema("\\\\?\\C:\\x"), "o prefixo de caminho longo");
    }

    @Test
    void doTextoDoSistemaNoWindowsRecusaOQueNaoTemComoTraduzir() {
        assertThrows(IllegalArgumentException.class, () -> windows.doTextoDoSistema(""));
        assertThrows(IllegalArgumentException.class, () -> windows.doTextoDoSistema(null));
        assertThrows(IllegalArgumentException.class, () -> windows.doTextoDoSistema("C:pasta"), "disco sem barra: é a pasta atual do disco, que o app não sabe qual é");
        assertThrows(IllegalArgumentException.class, () -> posix.doTextoDoSistema(""));
    }

    @Test
    void pastaDeRedeEOutrosCaminhosSemDiscoNuncaViramCaminhoDeUmDisco() {
        // saem como texto que o paraTextoDoSistema recusa: o app falha fechado, nunca toca num caminho mal traduzido
        for (String semDisco : new String[] {"\\\\servidor\\share\\x", "\\\\?\\UNC\\servidor\\share", "/home/x", "proj\\a", "\\Users\\ana"}) {
            String app = windows.doTextoDoSistema(semDisco);
            assertEquals('/', app.charAt(0), semDisco);
            assertThrows(IllegalArgumentException.class, () -> windows.paraTextoDoSistema(app), semDisco + " -> " + app);
        }
        assertEquals("//servidor/share/x", windows.doTextoDoSistema("\\\\servidor\\share\\x"));
        assertEquals("//servidor/share", windows.doTextoDoSistema("\\\\?\\UNC\\servidor\\share"));
    }

    @Test
    void paraTextoDoSistemaNoWindowsEmValoresConhecidos() {
        assertEquals("D:\\proj\\a", windows.paraTextoDoSistema("/D:/proj/a"));
        assertEquals("D:\\", windows.paraTextoDoSistema("/d:"), "disco em minúscula vira maiúscula");
        assertEquals("D:\\", windows.paraTextoDoSistema("/D:/"));
        assertEquals("D:\\Pasta com espaço\\ação", windows.paraTextoDoSistema("/D:/Pasta com espaço/ação"));
        assertEquals("D:\\proj\\..\\a", windows.paraTextoDoSistema("/D:/proj/../a"), "não resolve .. (a cerca já resolveu antes)");
        assertNull(windows.paraTextoDoSistema("/"));
        for (String semDisco : new String[] {"/home/x", "/srv/lab", "/ab:/x", "/1:/x", "/:/x"}) {
            assertThrows(IllegalArgumentException.class, () -> windows.paraTextoDoSistema(semDisco), semDisco);
        }
    }

    @Test
    void idaEVoltaNoWindowsDevolveOMesmoCaminho() {
        for (String sistema : new String[] {"D:\\proj\\a", "C:\\", "E:\\Meus Projetos\\Lab Local\\x", "D:\\ação\\ü"}) {
            assertEquals(sistema, windows.paraTextoDoSistema(windows.doTextoDoSistema(sistema)), sistema);
        }
        for (String app : new String[] {"/D:/proj/a", "/C:", "/E:/Meus Projetos/x"}) {
            assertEquals(app, windows.doTextoDoSistema(windows.paraTextoDoSistema(app)), app);
        }
    }

    @Test
    void noMacENoLinuxOTextoNaoMudaNemOQueParecePasta() {
        for (String t : new String[] {"/home/x", "/Users/ana/Meus Projetos", "/", "/tmp/a b/c", "D:\\proj\\a", "relativo/x"}) {
            assertEquals(t, posix.doTextoDoSistema(t), t);
            assertEquals(t, posix.paraTextoDoSistema(t), t);
        }
    }

    @Test
    void alvoDeLinkNoWindowsTrocaAsBarrasEEntendeODisco() {
        String pasta = "/D:/proj/pasta";
        assertEquals("../x/y", windows.alvoDoLink(pasta, "..\\x\\y"));
        assertEquals("a/b", windows.alvoDoLink(pasta, "a\\b"));
        assertEquals("/E:/fora", windows.alvoDoLink(pasta, "E:\\fora"));
        assertEquals("/E:/fora/x", windows.alvoDoLink(pasta, "e:/fora/x/"));
        assertEquals("/D:/x", windows.alvoDoLink(pasta, "\\x"), "a raiz do disco do próprio link");
        assertEquals("/D:/x", windows.alvoDoLink("/D:", "\\x"));
        assertEquals("/D:/x", windows.alvoDoLink(pasta, "\\\\?\\D:\\x"));
        assertEquals("/D:/x", windows.alvoDoLink(pasta, "\\??\\D:\\x"));
        assertNull(windows.alvoDoLink(pasta, "\\\\servidor\\share"), "pasta de rede: o app recusa o link");
        assertNull(windows.alvoDoLink(pasta, "\\\\?\\UNC\\servidor\\share"));
        assertNull(windows.alvoDoLink(pasta, "C:pasta"));
        assertNull(windows.alvoDoLink(null, "\\x"), "sem saber o disco do link não há como dizer");
        assertNull(windows.alvoDoLink(pasta, ""));
        assertNull(windows.alvoDoLink(pasta, null));
    }

    @Test
    void alvoDeLinkNoMacENoLinuxEOTextoComoVeio() {
        assertEquals("../x", posix.alvoDoLink("/home/a", "../x"));
        assertEquals("/etc/passwd", posix.alvoDoLink("/home/a", "/etc/passwd"));
        assertEquals("a\\b", posix.alvoDoLink("/home/a", "a\\b"), "barra invertida é um nome de arquivo comum");
        assertNull(posix.alvoDoLink("/home/a", ""));
    }
}
