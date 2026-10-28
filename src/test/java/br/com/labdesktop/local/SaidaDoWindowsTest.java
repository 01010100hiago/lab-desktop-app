package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A saída do Windows decodificada de VERDADE: o {@code wsl.exe} em UTF-16LE, com e sem marca, o CP850 do console e o UTF-8. */
class SaidaDoWindowsTest {

    private static final Charset CP850 = Charset.forName("IBM850");

    private static byte[] utf16leComBom(String s) {
        byte[] t = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] r = new byte[t.length + 2];
        r[0] = (byte) 0xFF;
        r[1] = (byte) 0xFE;
        System.arraycopy(t, 0, r, 2, t.length);
        return r;
    }

    @Test
    void utf16leComMarcaVira_texto_semNulEsemBom() {
        String esperado = "Distribuição Padrão: Ubuntu\r\nVersão Padrão: 2\r\n";
        assertEquals(esperado, SaidaDoWindows.texto(utf16leComBom(esperado)));
    }

    @Test
    void utf16leSemMarcaComoOWslEscreveNoPipeTambemVira_texto() {
        String s = "Default Distribution: Ubuntu\nDefault Version: 2\n";
        byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
        assertEquals(s, SaidaDoWindows.texto(b));
        // lido como UTF-8 seria "D\0e\0f\0..." — é exatamente o erro que a decodificação evita
        assertTrue(new String(b, StandardCharsets.UTF_8).contains("\u0000"));
        assertFalse(SaidaDoWindows.texto(b).contains("\u0000"));
    }

    @Test
    void utf16leComAcentoESemMarcaTambem() {
        String s = "O Subsistema do Windows para Linux não está instalado.";
        assertEquals(s, SaidaDoWindows.texto(s.getBytes(StandardCharsets.UTF_16LE)));
    }

    @Test
    void utf16beComMarcaEUtf8ComMarca() {
        byte[] be = "Olá".getBytes(StandardCharsets.UTF_16BE);
        byte[] comMarca = new byte[be.length + 2];
        comMarca[0] = (byte) 0xFE;
        comMarca[1] = (byte) 0xFF;
        System.arraycopy(be, 0, comMarca, 2, be.length);
        assertEquals("Olá", SaidaDoWindows.texto(comMarca));
        byte[] u8 = "Olá".getBytes(StandardCharsets.UTF_8);
        byte[] u8Marca = new byte[u8.length + 3];
        u8Marca[0] = (byte) 0xEF;
        u8Marca[1] = (byte) 0xBB;
        u8Marca[2] = (byte) 0xBF;
        System.arraycopy(u8, 0, u8Marca, 3, u8.length);
        assertEquals("Olá", SaidaDoWindows.texto(u8Marca));
    }

    @Test
    void utf8SemMarcaEACodePageDoConsoleQuandoOUtf8Falha() {
        assertEquals("ação: ok", SaidaDoWindows.texto("ação: ok".getBytes(StandardCharsets.UTF_8)));
        // CP850 (console do Brasil): "ç" é 0x87 e "ã" é 0xC6, bytes que não são UTF-8 válido
        byte[] cp = "ação: ok".getBytes(CP850);
        assertEquals("ação: ok", SaidaDoWindows.texto(cp, CP850));
    }

    @Test
    void vazioENuloNaoQuebram() {
        assertEquals("", SaidaDoWindows.texto(new byte[0]));
        assertEquals("", SaidaDoWindows.texto(null));
        assertEquals("", SaidaDoWindows.primeiraLinha("  \r\n \n"));
        assertEquals("primeira", SaidaDoWindows.primeiraLinha("\r\n  primeira  \r\nsegunda"));
    }

    @Test
    void versaoPadraoDoWslEmTresIdiomasESoQuandoAFraseEInteira() {
        assertEquals(2, SaidaDoWindows.versaoPadraoDoWsl("Default Distribution: Ubuntu\nDefault Version: 2\n"));
        assertEquals(1, SaidaDoWindows.versaoPadraoDoWsl("Distribuição Padrão: Ubuntu\r\nVersão Padrão: 1\r\n"));
        assertEquals(2, SaidaDoWindows.versaoPadraoDoWsl("Distribución predeterminada: Ubuntu\nVersión predeterminada: 2"));
        assertEquals(0, SaidaDoWindows.versaoPadraoDoWsl("WSL version: 2.0.9.0\nKernel version: 5.15.133.1-1\n"), "versão do programa não é a versão padrão");
        assertEquals(0, SaidaDoWindows.versaoPadraoDoWsl(""));
        assertEquals(0, SaidaDoWindows.versaoPadraoDoWsl(null));
        // lida de bytes de verdade, em UTF-16LE
        assertEquals(1, SaidaDoWindows.versaoPadraoDoWsl(SaidaDoWindows.texto("Versão Padrão: 1\r\n".getBytes(StandardCharsets.UTF_16LE))));
    }
}
