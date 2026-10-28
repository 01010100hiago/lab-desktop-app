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

    @Test
    void caractereQueEnganaOLeitorViraSublinhadoENomeContaComoMudado() {
        // o clássico: "backup" + U+202E (inverte o texto) + "gpj.sql" aparece como "backupqls.jpg"
        NomeLocalSeguro.Nome n = NomeLocalSeguro.de("backup\u202Egpj.sql").orElseThrow();
        assertEquals("backup_gpj.sql", n.seguro());
        assertTrue(n.mudou());
        for (int cp : new int[] {0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202D, 0x2066, 0x2069, 0x2060, 0xFEFF, 0xAD, 0x2028, 0x2029, 0x061C, 0x80, 0x85, 0x9F}) {
            String nome = "a" + new String(Character.toChars(cp)) + "b.sql";
            assertEquals("a_b.sql", seguro(nome), "U+" + Integer.toHexString(cp));
            assertTrue(NomeLocalSeguro.de(nome).orElseThrow().mudou(), "U+" + Integer.toHexString(cp));
        }
        assertEquals("relatório 日本語.sql", seguro("relatório 日本語.sql"), "letra de verdade não é tocada");
    }

    @Test
    void aparelhosExtrasDoWindowsTambemGanhamPrefixo() {
        for (String n : new String[] {"CONIN$", "conout$", "CLOCK$.txt", "COM\u00B9", "com\u00B2.sql", "LPT\u00B3.log", "CON .txt", "nul .gz"}) {
            assertTrue(seguro(n).startsWith("_"), n);
        }
        assertEquals("CONIN.sql", seguro("CONIN.sql"));
        assertEquals("COM10.sql", seguro("COM10.sql"), "COM10 não é aparelho");
    }

    @Test
    void caminhoDoWindowsCom260CaracteresOuMaisPedePastaMaisCurta() {
        String pasta = "C:\\" + "a".repeat(200);
        assertEquals(Optional.empty(), NomeLocalSeguro.longoDemaisNoWindows(pasta, "db.sql"));
        String frase = NomeLocalSeguro.longoDemaisNoWindows(pasta, "n".repeat(60) + ".sql").orElseThrow();
        assertTrue(frase.contains("260") && frase.contains("pasta de destino mais curta"), frase);
        // o limite exato: 259 cabe, 260 não
        assertEquals(Optional.empty(), NomeLocalSeguro.longoDemaisNoWindows("C".repeat(222), "x".repeat(36)), "222 + 1 + 36 = 259 cabe");
        assertTrue(NomeLocalSeguro.longoDemaisNoWindows("C".repeat(223), "x".repeat(36)).isPresent(), "260 não cabe");
        // o parcial (36 caracteres) conta mesmo que o nome seja curto
        assertTrue(NomeLocalSeguro.longoDemaisNoWindows("C".repeat(223), "a.sql").isPresent());
    }
}
