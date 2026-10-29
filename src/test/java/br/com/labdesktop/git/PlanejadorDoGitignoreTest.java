package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O que o app acrescenta ao {@code .gitignore} (só acrescenta, na ordem certa, sem repetir), as linhas de um caminho exato e a lista de segredos, conferida pelo git de verdade. */
class PlanejadorDoGitignoreTest {

    @TempDir
    Path tmp;

    @Test
    void semGitignoreCriaOArquivoComTudoQueOAppGarante() {
        PlanejadorDoGitignore.Plano p = PlanejadorDoGitignore.planejar(null, List.of());
        assertTrue(p.criaArquivo());
        assertEquals(SegredosDoProjeto.todasAsLinhas(), p.linhasNovas());
        assertEquals(PlanejadorDoGitignore.COMENTARIO + "\n" + String.join("\n", SegredosDoProjeto.todasAsLinhas()) + "\n", p.textoFinal());
        assertTrue(p.mudaAlgo());
        assertTrue(p.linhasNovas().indexOf("!.env.example") > p.linhasNovas().indexOf(".env.*"), "a exceção vem depois da regra que ela libera");
    }

    @Test
    void soAcrescentaOQueFaltaSemMexerNoQueODonoEscreveu() {
        String dono = "# meu arquivo\nnode_modules/\n.env\ndist/\n";
        PlanejadorDoGitignore.Plano p = PlanejadorDoGitignore.planejar(dono, List.of());
        assertFalse(p.criaArquivo());
        assertFalse(p.linhasNovas().contains("node_modules/"));
        assertFalse(p.linhasNovas().contains(".env"));
        assertTrue(p.linhasNovas().contains(".env.*"));
        assertTrue(p.textoFinal().startsWith(dono + "\n" + PlanejadorDoGitignore.COMENTARIO + "\n"), p.textoFinal());
        assertEquals(dono, p.textoFinal().substring(0, dono.length()), "o texto do usuário continua igual, byte a byte");
    }

    @Test
    void rodarDeNovoNaoMudaNada() {
        String uma = PlanejadorDoGitignore.planejar("a\n", List.of("/clone/")).textoFinal();
        PlanejadorDoGitignore.Plano outra = PlanejadorDoGitignore.planejar(uma, List.of("/clone/"));
        assertFalse(outra.mudaAlgo());
        assertEquals(uma, outra.textoFinal());
    }

    @Test
    void linhaComEspacoNoFimContaComoJaTendo() {
        PlanejadorDoGitignore.Plano p = PlanejadorDoGitignore.planejar(".env  \nnode_modules/\t\n", List.of());
        assertFalse(p.linhasNovas().contains(".env"));
        assertFalse(p.linhasNovas().contains("node_modules/"));
    }

    @Test
    void crlfContinuaCrlfESemQuebraNoFimGanhaUma() {
        PlanejadorDoGitignore.Plano p = PlanejadorDoGitignore.planejar("a\r\nb", List.of());
        assertTrue(p.textoFinal().startsWith("a\r\nb\r\n\r\n" + PlanejadorDoGitignore.COMENTARIO + "\r\n"), p.textoFinal().replace("\r", "\\r"));
        assertFalse(p.textoFinal().replace("\r\n", "").contains("\n"), "nenhuma quebra solta no meio de um arquivo CRLF");
    }

    @Test
    void aExcecaoDoEnvExampleVoltaDepoisDeEnvAsteriscoMesmoQueOdonoTivesseAMaisAcima() {
        PlanejadorDoGitignore.Plano p = PlanejadorDoGitignore.planejar("!.env.example\n", List.of());
        int env = p.linhasNovas().indexOf(".env.*");
        assertTrue(env >= 0);
        assertEquals("!.env.example", p.linhasNovas().get(env + 1));
        // e vale de verdade: .env.example não é ignorado, .env.local é
        assumeTrue(GitDeTeste.temGit());
        GitDeTeste g = new GitDeTeste(tmp);
        Path repo = g.repositorio("loja-web");
        g.escrever(repo, ".gitignore", p.textoFinal());
        assertEquals(".env.local\n", g.bruto(repo, "check-ignore", "--", ".env.example", ".env.local").stdout().replace("\0", "\n").replaceAll("\n+", "\n").strip() + "\n");
    }

    @Test
    void extrasEntramNoFimSemRepetir() {
        PlanejadorDoGitignore.Plano p = PlanejadorDoGitignore.planejar(null, List.of("/clone/", "/dados/grande.sql", "/clone/"));
        assertEquals(1, p.linhasNovas().stream().filter("/clone/"::equals).count());
        assertTrue(p.linhasNovas().indexOf("/dados/grande.sql") > p.linhasNovas().indexOf("node_modules/"));
    }

    @Test
    void linhaParaUmCaminhoExatoProtegeOQueTemSentidoNoGitignore() {
        assertEquals("/dados/grande.sql", PlanejadorDoGitignore.linhaParaCaminho("dados/grande.sql", false));
        assertEquals("/clone/", PlanejadorDoGitignore.linhaParaCaminho("clone", true));
        assertEquals("/clone/", PlanejadorDoGitignore.linhaParaCaminho("clone/", true));
        assertEquals("/a\\*b\\?c\\[d", PlanejadorDoGitignore.linhaParaCaminho("a*b?c[d", false));
        assertThrows(IllegalArgumentException.class, () -> PlanejadorDoGitignore.linhaParaCaminho("a\\b", false), "barra invertida não é nome de arquivo do git");
        assertEquals("/#nao-e-comentario", PlanejadorDoGitignore.linhaParaCaminho("#nao-e-comentario", false));
        assertEquals("/!nao-e-negacao", PlanejadorDoGitignore.linhaParaCaminho("!nao-e-negacao", false));
        assertEquals("/espaco no fim\\ ", PlanejadorDoGitignore.linhaParaCaminho("espaco no fim ", false));
        assertThrows(IllegalArgumentException.class, () -> PlanejadorDoGitignore.linhaParaCaminho("../fora", false));
        assertThrows(IllegalArgumentException.class, () -> PlanejadorDoGitignore.linhaParaCaminho("a\nb", false));
    }

    @Test
    void aLinhaDeUmCaminhoExatoIgnoraSoAqueleCaminhoSegundoOGitDeVerdade() throws Exception {
        assumeTrue(GitDeTeste.temGit());
        GitDeTeste g = new GitDeTeste(tmp);
        Path repo = g.repositorio("loja-web");
        List<String> nomes = new ArrayList<>(List.of("dados.sql", "dados[1].sql", "dados1.sql", "a*b.bin", "aXb.bin", "#frio.txt", "!quente.txt", "com espaço.dat", "ação.bin", "pasta/sub/x.bin", "p?.log", "pa.log"));
        StringBuilder gi = new StringBuilder();
        for (String n : List.of("dados[1].sql", "a*b.bin", "#frio.txt", "!quente.txt", "com espaço.dat", "ação.bin", "pasta/sub/x.bin", "p?.log")) {
            gi.append(PlanejadorDoGitignore.linhaParaCaminho(n, false)).append('\n');
            g.escrever(repo, n, "x");
        }
        for (String n : List.of("dados.sql", "dados1.sql", "aXb.bin", "pa.log")) {
            g.escrever(repo, n, "x");
        }
        g.escrever(repo, ".gitignore", gi.toString());
        String listados = g.git(repo, "-c", "core.quotepath=off", "ls-files", "-o", "--exclude-standard", "-z").replace("\0", "\n");
        List<String> entram = List.of(listados.split("\n"));
        assertTrue(entram.containsAll(List.of("dados.sql", "dados1.sql", "aXb.bin", "pa.log", ".gitignore")), entram.toString());
        for (String fora : List.of("dados[1].sql", "a*b.bin", "#frio.txt", "!quente.txt", "com espaço.dat", "ação.bin", "pasta/sub/x.bin", "p?.log")) {
            assertFalse(entram.contains(fora), "ficou de fora: " + fora + " em " + entram);
        }
        assertNotEquals(nomes.size(), entram.size());
    }

    @Test
    void aListaDeSegredosEOTesteEmJavaConcordamComOGitDeVerdade() throws Exception {
        assumeTrue(GitDeTeste.temGit());
        GitDeTeste g = new GitDeTeste(tmp);
        Path repo = g.repositorio("loja-web");
        g.escrever(repo, ".gitignore", String.join("\n", SegredosDoProjeto.PADROES) + "\n");
        List<String> nomes = List.of(".env", ".env.local", ".env.production", ".env.example", "app/.env", "app/.env.example", "chave.pem", "server.key", "id_rsa", "id_rsa.pub", "id_ed25519",
            "id_ecdsa", "id_dsa", "credentials.json", "credentials", "aws/credentials", ".npmrc", ".pypirc", ".netrc", "loja.p12", "loja.pfx", "x.keystore", "release.jks",
            "README.md", "src/app.js", "environment.ts", "env.js", "keys.md", "monkey.txt", "pem.txt", "credenciais.txt", "my.envrc", "dotenv.js");
        for (String n : nomes) {
            boolean ignoradoPeloGit = g.bruto(repo, "check-ignore", "-q", "--", n).status() == 0;
            assertEquals(ignoradoPeloGit, SegredosDoProjeto.pareceSegredo(n), "git x Java para " + n);
        }
        // as amostras do app: as que devem ser ignoradas são, a liberada não é
        for (String n : SegredosDoProjeto.amostrasIgnoradas()) {
            assertEquals(0, g.bruto(repo, "check-ignore", "-q", "--", n).status(), "deveria ser ignorada: " + n);
        }
        for (String n : SegredosDoProjeto.amostrasLiberadas()) {
            assertEquals(1, g.bruto(repo, "check-ignore", "-q", "--", n).status(), "deveria ser liberada: " + n);
        }
    }

    @Test
    void oTesteEmJavaAchaOSegredoPeloNomeEMaiusculaDaExtensaoTambem() {
        assertTrue(SegredosDoProjeto.pareceSegredo("a/b/.env"));
        assertTrue(SegredosDoProjeto.pareceSegredo("CERT.PEM"), "o git de um Mac ignora maiúscula; o app é mais rígido de propósito");
        assertFalse(SegredosDoProjeto.pareceSegredo(".env.example"));
        assertFalse(SegredosDoProjeto.pareceSegredo("pasta.env/arquivo.txt"), "só o nome do arquivo conta, não a pasta");
        assertFalse(SegredosDoProjeto.pareceSegredo(""));
        assertFalse(SegredosDoProjeto.pareceSegredo(null));
        assertEquals(SegredosDoProjeto.PADROES.size() + SegredosDoProjeto.LIXO_COMUM.size(), SegredosDoProjeto.todasAsLinhas().size());
        assertTrue(SegredosDoProjeto.LIXO_COMUM.contains("node_modules/"));
    }
}
