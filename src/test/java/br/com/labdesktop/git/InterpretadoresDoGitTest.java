package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os parsers do git com saídas de valor conhecido (escritas à mão no formato exato que o git produz): log, numstat, status, branches e diff. */
class InterpretadoresDoGitTest {

    private static final String H1 = "13e9892ed683963b757ee9dc6a1581997ffead16";
    private static final String H2 = "252c020e112a7bee691a478 76e5c8ee5162fbbb0".replace(" ", "");

    // -------------------------------------------------------------------------------------------- log

    private static final String NONCE = "0123456789abcdef";
    private static final String M = "\0" + NONCE + "\0";

    /** Um registro no formato exato do app: marca, e os sete campos separados por NUL. */
    private static String reg(String hash, String curto, String autor, String email, String at, String pais, String assunto, String stat) {
        return M + hash + "\0" + curto + "\0" + autor + "\0" + email + "\0" + at + "\0" + pais + "\0" + assunto + (stat == null ? "\n" : "\n\n " + stat + "\n");
    }

    @Test
    void logComDoisCommitsEOTotalDeArquivos() {
        String saida = reg(H1, "13e9892", "Ana Silva", "ana@example.com", "1793304758", H2, "Segundo commit", "2 files changed, 2 insertions(+)")
            + reg(H2, "252c020", "Ana Silva", "ana@example.com", "1793304758", "", "Primeiro", "3 files changed, 3 insertions(+)");
        List<Commit> l = InterpretadorDoLog.interpretar(saida, NONCE);
        assertEquals(2, l.size());
        Commit c = l.get(0);
        assertEquals(H1, c.hash());
        assertEquals("13e9892", c.curto());
        assertEquals("Ana Silva", c.autor());
        assertEquals("ana@example.com", c.email());
        assertEquals(Instant.ofEpochSecond(1793304758L), c.quando());
        assertEquals(List.of(H2), c.pais());
        assertEquals("Segundo commit", c.assunto());
        assertEquals(2, c.arquivos());
        assertFalse(c.ehMerge());
        assertTrue(l.get(1).pais().isEmpty(), "o primeiro commit não tem pai");
        assertEquals(3, l.get(1).arquivos());
    }

    @Test
    void umArquivoSoELinhaSemPluralEMergeSemTotal() {
        String saida = reg(H1, "13e9892", "Ana", "a@example.com", "1", H2 + " " + "a".repeat(40), "Mescla", null) + reg(H2, "252c020", "Ana", "a@example.com", "2", "", "um", "1 file changed, 1 insertion(+)");
        List<Commit> l = InterpretadorDoLog.interpretar(saida, NONCE);
        assertTrue(l.get(0).ehMerge());
        assertEquals(-1, l.get(0).arquivos(), "merge sem diferença: sem linha de totais");
        assertEquals(1, l.get(1).arquivos());
    }

    @Test
    void assuntoComControleEBidiSaiLimpoEHtmlSaiNeutralizado() {
        String assunto = "<html><b>oi</b>\u202Etxt.exe\u001b[31m";
        Commit c = InterpretadorDoLog.interpretar(reg(H1, "13e9892", "Ana\u202E", "a@example.com", "1", "", assunto, null), NONCE).get(0);
        assertFalse(c.assunto().startsWith("<html"), "o Swing trataria como HTML");
        assertFalse(c.assunto().contains("\u202E"));
        assertFalse(c.assunto().contains("\u001b"));
        assertEquals("Ana", c.autor());
    }

    @Test
    void assuntoEAutorComOsSeparadoresAntigosNaoPartemARegistroNemSomem() {
        String estranho = "um\u001edois\u001ftres";
        String saida = reg(H1, "13e9892", "Ana\u001fSilva", "a@example.com", "1", "", estranho, "1 file changed, 1 insertion(+)") + reg(H2, "252c020", "Beto", "b@example.com", "2", "", "outro", null);
        List<Commit> l = InterpretadorDoLog.interpretar(saida, NONCE);
        assertEquals(2, l.size(), "nenhum commit some da lista");
        assertEquals("umdoistres", l.get(0).assunto(), "o controle some da tela, o commit fica");
        assertEquals("AnaSilva", l.get(0).autor());
        assertEquals(1, l.get(0).arquivos());
        assertEquals("outro", l.get(1).assunto());
    }

    @Test
    void autorQueImitaAMarcaFixaNaoPartePorqueAMarcaTemUmNonceQueNinguemSabe() {
        String saida = reg(H1, "13e9892", "LABREC", "a@example.com", "1", "", "assunto", null) + reg(H2, "252c020", "\0" + NONCE.substring(0, 15) + "x", "b@example.com", "2", "", "outro", null);
        List<Commit> l = InterpretadorDoLog.interpretar(saida, NONCE);
        assertEquals(2, l.size());
        assertEquals("LABREC", l.get(0).autor());
    }

    @Test
    void registroQueNaoDaParaLerViraCommitIlegivelComOHashENuncaSome() {
        String saida = M + H1 + "\0pedaco" + "\n" + reg(H2, "252c020", "Ana", "a@example.com", "2", "", "bom", null);
        List<Commit> l = InterpretadorDoLog.interpretar(saida, NONCE);
        assertEquals(2, l.size());
        assertEquals(H1, l.get(0).hash());
        assertEquals(InterpretadorDoLog.ILEGIVEL, l.get(0).assunto());
        assertEquals("bom", l.get(1).assunto());
        Commit semHash = InterpretadorDoLog.interpretar(M + "lixo\n", NONCE).get(0);
        assertEquals(InterpretadorDoLog.ILEGIVEL, semHash.assunto());
        assertEquals(1, InterpretadorDoLog.interpretar(reg(H1, "13e9892", "A", "a@x", "não-é-número", "", "x", null), NONCE).size(), "a data ilegível não derruba o commit");
    }

    @Test
    void listaVaziaDaVazioEHashDeSessentaEQuatroLetrasValeNoLog() {
        assertTrue(InterpretadorDoLog.interpretar("", NONCE).isEmpty());
        assertTrue(InterpretadorDoLog.interpretar(null, NONCE).isEmpty());
        String h64 = "ab".repeat(32);
        List<Commit> l = InterpretadorDoLog.interpretar(reg(h64, h64.substring(0, 7), "Ana", "a@x", "1", "", "num repositório SHA-256", null), NONCE);
        assertEquals(h64, l.get(0).hash(), "o hash inteiro de um repositório SHA-256 tem 64 letras");
        assertEquals("num repositório SHA-256", l.get(0).assunto());
        assertThrows(IllegalArgumentException.class, () -> InterpretadorDoLog.formato("curto"));
        assertTrue(InterpretadorDoLog.nonceValido(InterpretadorDoLog.novoNonce()));
    }

    @Test
    void cabecalhoComMensagemInteira() {
        String saida = H1 + "\0" + "13e9892\0Ana\0a@example.com\0" + "5\0" + H2 + "\0Assunto\n\nCorpo da mensagem\ncom duas linhas.\n";
        InterpretadorDoLog.Cabecalho c = InterpretadorDoLog.interpretarCabecalho(saida);
        assertEquals("Assunto", c.commit().assunto());
        assertEquals("Assunto\n\nCorpo da mensagem\ncom duas linhas.", c.mensagem());
        assertNull(InterpretadorDoLog.interpretarCabecalho(""));
        assertNull(InterpretadorDoLog.interpretarCabecalho(null));
        String comControle = H1 + "\0" + "13e9892\0Ana\0a@example.com\0" + "5\0\0Assunto\u001edois\n";
        assertEquals("Assuntodois", InterpretadorDoLog.interpretarCabecalho(comControle).commit().assunto(), "o U+001E de uma mensagem não atrapalha o cabeçalho");
    }

    // -------------------------------------------------------------------------------------------- numstat

    @Test
    void numstatComRenomeadoBinarioEAcento() {
        String saida = "1\t0\t\0a.txt\0c.txt\0" + "3\t1\tsrc/app.js\0" + "-\t-\tlogo.png\0" + "2\t0\tação x.txt\0";
        List<ArquivoDoCommit> l = InterpretadorDoNumstat.interpretar(saida);
        assertEquals(4, l.size());
        assertEquals(new ArquivoDoCommit("c.txt", "a.txt", 1, 0), l.get(0));
        assertTrue(l.get(0).renomeado());
        assertEquals(new ArquivoDoCommit("src/app.js", null, 3, 1), l.get(1));
        assertTrue(l.get(2).binario());
        assertEquals(-1, l.get(2).acrescentadas());
        assertEquals("ação x.txt", l.get(3).caminho());
    }

    @Test
    void numstatVazioECortadoNoMeio() {
        assertTrue(InterpretadorDoNumstat.interpretar("").isEmpty());
        assertTrue(InterpretadorDoNumstat.interpretar(null).isEmpty());
        assertEquals(1, InterpretadorDoNumstat.interpretar("1\t1\ta.txt\0" + "2\t0\t\0velho.txt\0").size(), "renomeado cortado no meio é ignorado, o resto vale");
        assertTrue(InterpretadorDoNumstat.interpretar("lixo sem tabulação\0").isEmpty());
    }

    // -------------------------------------------------------------------------------------------- status

    @Test
    void statusComBranchAlteracoesRenomeadoEConflito() {
        String saida = "# branch.oid " + H1 + "\0# branch.head main\0# branch.upstream origin/main\0# branch.ab +2 -1\0"
            + "1 .M N... 100644 100644 100644 0ffa4caa4d7c8a6e31923d3af4702de217797e20 0ffa4caa4d7c8a6e31923d3af4702de217797e20 d.txt\0"
            + "1 A. N... 000000 100644 100644 0000000000000000000000000000000000000000 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 src/novo arquivo.js\0"
            + "1 D. N... 100644 000000 000000 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 0000000000000000000000000000000000000000 velho.txt\0"
            + "2 R. N... 100644 100644 100644 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 R100 c.txt\0a.txt\0"
            + "u UU N... 100644 100644 100644 100644 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 conflito.txt\0"
            + "? z.txt\0? ação.txt\0! ignorado.log\0";
        StatusDoGit s = InterpretadorDoStatus.interpretar(saida);
        assertEquals("main", s.branch());
        assertFalse(s.cabecaSolta());
        assertEquals(H1, s.commitAtual());
        assertEquals("origin/main", s.upstream());
        assertEquals(2, s.aFrente());
        assertEquals(1, s.atras());
        assertEquals(7, s.alteracoes(), "o ignorado não conta");
        assertEquals(new Pendencia("d.txt", null, Pendencia.Tipo.MODIFICADO, false, true), s.pendencias().get(0));
        assertEquals(new Pendencia("src/novo arquivo.js", null, Pendencia.Tipo.ADICIONADO, true, false), s.pendencias().get(1));
        assertEquals(Pendencia.Tipo.APAGADO, s.pendencias().get(2).tipo());
        assertEquals(new Pendencia("c.txt", "a.txt", Pendencia.Tipo.RENOMEADO, true, false), s.pendencias().get(3));
        assertEquals(Pendencia.Tipo.CONFLITO, s.pendencias().get(4).tipo());
        assertEquals(new Pendencia("z.txt", null, Pendencia.Tipo.NAO_RASTREADO, false, true), s.pendencias().get(5));
        assertEquals("ação.txt", s.pendencias().get(6).caminho());
    }

    @Test
    void statusDeRepositorioSemCommitsECabecaSolta() {
        StatusDoGit s = InterpretadorDoStatus.interpretar("# branch.oid (initial)\0# branch.head main\0? a.txt\0");
        assertTrue(s.semCommits());
        assertNull(s.commitAtual());
        assertEquals("main", s.branch());
        assertEquals(-1, s.aFrente());
        assertEquals(1, s.alteracoes());
        StatusDoGit solta = InterpretadorDoStatus.interpretar("# branch.oid " + H1 + "\0# branch.head (detached)\0");
        assertTrue(solta.cabecaSolta());
        assertNull(solta.branch());
        assertEquals(0, solta.alteracoes());
        assertEquals(0, InterpretadorDoStatus.interpretar("").alteracoes());
        assertEquals(0, InterpretadorDoStatus.interpretar(null).alteracoes());
    }

    // -------------------------------------------------------------------------------------------- branches

    @Test
    void branchesComOAtualMarcadoEOsQueOGitAceitamTodosEntramSoOPerigosoFicaDeFora() {
        String us = "\u001f";
        String saida = "*" + us + "main" + us + "13e9892\n" + " " + us + "feature/entrega" + us + "252c020\n" + " " + us + "ação/versão" + us + "aaaaaaa\n" + " " + us + "fix#12" + us + "bbbbbbb\n"
            + " " + us + "feat+x" + us + "ccccccc\n" + " " + us + "user@host" + us + "ddddddd\n" + " " + us + "a;b" + us + "eeeeeee\n"
            + " " + us + "-perigo" + us + "fffffff\n" + " " + us + "com espaço" + us + "1111111\n" + " " + us + "a..b" + us + "2222222\n" + " " + us + "@" + us + "3333333\n" + "linha solta\n";
        List<Branch> b = InterpretadorDeBranches.interpretar(saida);
        assertEquals(List.of("main", "feature/entrega", "ação/versão", "fix#12", "feat+x", "user@host", "a;b"), b.stream().map(Branch::nome).toList());
        assertTrue(b.get(0).atual());
    }

    // -------------------------------------------------------------------------------------------- diff

    private static DiffDoArquivo diff(String texto) {
        return InterpretadorDeDiff.interpretar(texto, false);
    }

    @Test
    void diffComTrechoAcrescentadaTiradaEContexto() {
        DiffDoArquivo d = diff("diff --git a/c.txt b/c.txt\nindex 1..2 100644\n--- a/c.txt\n+++ b/c.txt\n@@ -1,2 +1,3 @@ função()\n a\n-b\n+B\n+c\n");
        assertEquals(List.of(LinhaDeDiff.Tipo.TRECHO, LinhaDeDiff.Tipo.CONTEXTO, LinhaDeDiff.Tipo.TIRADA, LinhaDeDiff.Tipo.ACRESCENTADA, LinhaDeDiff.Tipo.ACRESCENTADA),
            d.linhas().stream().map(LinhaDeDiff::tipo).toList());
        assertEquals("-1,2 +1,3 @@ função()", d.linhas().get(0).texto());
        assertEquals(2, d.acrescentadas());
        assertEquals(1, d.tiradas());
        assertFalse(d.binario());
        assertFalse(d.cortado());
        assertFalse(d.vazio());
        assertEquals("+", d.linhas().get(3).simbolo());
        assertEquals("-", d.linhas().get(2).simbolo());
        assertEquals("acrescentada", d.linhas().get(3).palavra());
        assertEquals("tirada", d.linhas().get(2).palavra());
    }

    @Test
    void linhaTiradaQueComecaComTracosNaoViraCabecalho() {
        DiffDoArquivo d = diff("diff --git a/x.sql b/x.sql\n--- a/x.sql\n+++ b/x.sql\n@@ -1,2 +1,1 @@\n--- comentário sql\n+-- novo comentário\n resto\n");
        assertEquals(1, d.tiradas());
        assertEquals(1, d.acrescentadas());
        assertEquals("-- comentário sql", d.linhas().get(1).texto());
    }

    @Test
    void diffBinarioVazioRenomeadoENovoArquivo() {
        DiffDoArquivo b = diff("diff --git a/l.png b/l.png\nindex 1..2 100644\nBinary files a/l.png and b/l.png differ\n");
        assertTrue(b.binario());
        assertTrue(b.linhas().isEmpty());
        assertFalse(b.vazio());
        assertTrue(diff("GIT binary patch\nliteral 0\n").binario());

        DiffDoArquivo v = diff("diff --git a/vazio b/vazio\nnew file mode 100644\nindex 0000000..e69de29\n");
        assertTrue(v.vazio());
        assertTrue(v.linhas().stream().anyMatch(l -> l.texto().equals("arquivo novo")));
        assertTrue(diff("").vazio());

        DiffDoArquivo r = diff("diff --git a/a.txt b/c.txt\nsimilarity index 90%\nrename from a.txt\nrename to c.txt\nindex 1..2 100644\n--- a/a.txt\n+++ b/c.txt\n@@ -1 +1,2 @@\n a\n+c\n");
        assertEquals("a.txt", r.antigo());
        assertEquals("renomeado de a.txt", r.linhas().get(0).texto());
        assertEquals("renomeado para c.txt", r.linhas().get(1).texto());
    }

    @Test
    void semFimDeLinhaEOAvisoEmPalavras() {
        DiffDoArquivo d = diff("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-velho\n\\ No newline at end of file\n+novo\n\\ No newline at end of file\n");
        assertTrue(d.semFimDeLinha());
        assertEquals(2, d.linhas().stream().filter(l -> l.tipo() == LinhaDeDiff.Tipo.AVISO).count());
        assertEquals("sem fim de linha no final do arquivo", d.linhas().stream().filter(l -> l.tipo() == LinhaDeDiff.Tipo.AVISO).findFirst().orElseThrow().texto());
    }

    @Test
    void limiteDeLinhasCortaEAvisa() {
        StringBuilder sb = new StringBuilder("--- a/x\n+++ b/x\n@@ -0,0 +1,5000 @@\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("+linha ").append(i).append('\n');
        }
        DiffDoArquivo d = diff(sb.toString());
        assertEquals(InterpretadorDeDiff.LINHAS_POR_ARQUIVO, d.linhas().size());
        assertTrue(d.cortado());
        assertTrue(d.comoTexto().endsWith("# diff cortado\n"));
        assertTrue(InterpretadorDeDiff.interpretar("--- a/x\n+++ b/x\n@@ -1 +1 @@\n+a\n", true).cortado(), "o executor cortou a saída por tamanho");
    }

    @Test
    void textoFinalDeLinhaCrlfTabEHtmlSaemLimpos() {
        DiffDoArquivo d = diff("--- a/x\n+++ b/x\n@@ -1 +1 @@\n+<html>\ttexto\r\n+\u001b[31mvermelho\n");
        assertFalse(d.linhas().get(1).texto().startsWith("<html"));
        assertFalse(d.linhas().get(1).texto().contains("\r"));
        assertFalse(d.linhas().get(1).texto().contains("\t"));
        assertFalse(d.linhas().get(2).texto().contains("\u001b"));
        String longa = "+" + "x".repeat(5000);
        assertTrue(diff("--- a/x\n+++ b/x\n@@ -0,0 +1 @@\n" + longa + "\n").linhas().get(1).texto().length() <= InterpretadorDeDiff.TETO_DA_LINHA + 1);
    }

    @Test
    void bytesQueNaoSaoUtf8ViramLatin1ComAviso() {
        byte[] latin1 = "--- a/x\n+++ b/x\n@@ -0,0 +1 @@\n+caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1);
        DiffDoArquivo d = InterpretadorDeDiff.interpretar(latin1, false);
        assertTrue(d.outraCodificacao());
        assertEquals("caf\u00e9", d.linhas().get(1).texto());
        DiffDoArquivo utf = InterpretadorDeDiff.interpretar("--- a/x\n+++ b/x\n@@ -0,0 +1 @@\n+caf\u00e9\n".getBytes(StandardCharsets.UTF_8), false);
        assertFalse(utf.outraCodificacao());
        assertEquals("caf\u00e9", utf.linhas().get(1).texto());
    }
}
