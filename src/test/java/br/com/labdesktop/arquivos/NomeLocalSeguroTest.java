package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O nome com que um backup baixado é gravado neste computador: nunca um caminho, nunca um caractere que o Windows recusa. */
class NomeLocalSeguroTest {

    private static String seguro(String nome) {
        return NomeLocalSeguro.de(nome).orElseThrow().seguro();
    }

    @Test
    void nomeNormalFicaComoEstaESemAviso() {
        NomeLocalSeguro.Nome n = NomeLocalSeguro.de("app-loja-2026-10-28T12-00-00.sql.gz").orElseThrow();
        assertEquals("app-loja-2026-10-28T12-00-00.sql.gz", n.seguro());
        assertFalse(n.mudou());
        assertEquals("relatório-março (final).sql", seguro("relatório-março (final).sql"), "acento e parênteses valem");
    }

    @Test
    void caminhoNuncaEUmNome() {
        for (String ruim : new String[] {"../etc/passwd", "/etc/passwd", "a/b.sql", "..", ".", "", "   ", "\\\\servidor\\pasta\\x.sql", "C:\\Users\\ana\\x.sql", "c:x.sql", "..\\x.sql",
            "a\\..\\b.sql", "x\0y", "x\ny", "x\u0007y"}) {
            assertEquals(Optional.empty(), NomeLocalSeguro.de(ruim), "[" + ruim.replace("\0", "\\0") + "]");
        }
        assertEquals(Optional.empty(), NomeLocalSeguro.de(null));
    }

    @Test
    void caracteresReservadosDoWindowsViramSublinhadoEAvisam() {
        NomeLocalSeguro.Nome n = NomeLocalSeguro.de("dump:2026-10-28 12:00?*.sql").orElseThrow();
        assertEquals("dump_2026-10-28 12_00__.sql", n.seguro());
        assertTrue(n.mudou());
        assertEquals("a_b_c_d_e_f.sql", seguro("a<b>c\"d|e\\f.sql"));
    }

    @Test
    void pontoEEspacoNoFimSaemPoisOWindowsOsApaga() {
        assertEquals("backup", seguro("backup. "));
        assertEquals("backup.sql", seguro("backup.sql..."));
        assertEquals(Optional.empty(), NomeLocalSeguro.de("..."), "só pontos não é nome");
    }

    @Test
    void nomeDeAparelhoDoWindowsGanhaUmPrefixo() {
        for (String n : new String[] {"CON", "con.txt", "PRN.sql", "aux", "NUL.gz", "COM1", "lpt9.log"}) {
            assertTrue(seguro(n).startsWith("_"), n);
        }
        assertEquals("console.sql", seguro("console.sql"), "só o nome exato, sem a extensão, é de aparelho");
        assertEquals("CONFIG.sql", seguro("CONFIG.sql"));
    }

    @Test
    void nomeCompridoECortadoPorBytesGuardandoAExtensao() {
        String nome = "ç".repeat(200) + ".sql.gz"; // 400 bytes só no corpo
        String s = seguro(nome);
        assertTrue(s.getBytes(StandardCharsets.UTF_8).length <= 200, "bytes: " + s.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(s.endsWith(".gz"));
        String longoSemExtensao = "a".repeat(300);
        assertEquals(200, seguro(longoSemExtensao).length());
    }

    @Test
    void nomeComEmojiEOutrosAlfabetosPassa() {
        assertEquals("backup-日本語.sql", seguro("backup-日本語.sql"));
    }
}
