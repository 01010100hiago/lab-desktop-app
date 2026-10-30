package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.git.ComandoGit.Natureza;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Todas as linhas de git do app: cada uma com a natureza certa, as opções de segurança da leitura, nada de {@code -f}, {@code --force}, {@code push}, {@code remote} nem {@code --global}. */
class ComandosDeGitTest {

    static final String PASTA = "/srv/lab/projects/loja-web";
    static final String H = "13e9892ed683963b757ee9dc6a1581997ffead16";
    static final String NONCE = "0123456789abcdef";
    static final String AREA = "/tmp/lab-desktop-git-0123456789abcdef";

    static List<ComandoGit> todos() {
        List<ComandoGit> l = new ArrayList<>();
        l.add(ComandosDeGit.versao());
        l.add(ComandosDeGit.ehRepositorio(PASTA));
        l.add(ComandosDeGit.status(PASTA));
        l.add(ComandosDeGit.ultimoCommit(PASTA, NONCE));
        l.add(ComandosDeGit.commits(PASTA, null, 0, 51, NONCE));
        l.add(ComandosDeGit.commits(PASTA, "feature/x", 50, 51, NONCE));
        l.add(ComandosDeGit.cabecalhoDoCommit(PASTA, H));
        l.add(ComandosDeGit.arquivosDoCommit(PASTA, H));
        l.add(ComandosDeGit.diffDoCommit(PASTA, H, "ação x.txt", null));
        l.add(ComandosDeGit.diffDoCommit(PASTA, H, "c.txt", "a.txt"));
        l.add(ComandosDeGit.branches(PASTA));
        l.add(ComandosDeGit.diffPendente(PASTA, "c.txt", null, false));
        l.add(ComandosDeGit.diffPendente(PASTA, "c.txt", "a.txt", true));
        l.add(ComandosDeGit.diffDeNaoRastreado(PASTA, "novo.txt"));
        l.add(ComandosDeGit.lerConfiguracao(PASTA, "user.name"));
        l.add(ComandosDeGit.lerConfiguracao(PASTA, "user.email"));
        l.add(ComandosDeGit.listarNaoRastreados(PASTA, AREA, null, false));
        l.add(ComandosDeGit.listarNaoRastreados(PASTA, AREA, "src/", true));
        l.add(ComandosDeGit.listarNaoRastreados(PASTA, null, null, false));
        l.add(ComandosDeGit.checarIgnorados(PASTA, AREA, new byte[0]));
        l.add(ComandosDeGit.checarIgnorados(PASTA, null, new byte[0]));
        l.add(ComandosDeGit.rastreadosQueParecemSegredo(PASTA));
        l.add(ComandosDeGit.hashCurtoDaCabeca(PASTA));
        l.add(ComandosDeGit.configuracaoQueExecuta(PASTA));
        l.add(ComandosDeGit.hashDaArvoreVazia(PASTA));
        l.add(ComandosDeGit.arquivosDeUmaPastaNova(PASTA, "dados-novos/"));
        l.add(ComandosDeGit.criarPastaDaArea(AREA));
        l.add(ComandosDeGit.criarAreaTemporaria(AREA));
        l.add(ComandosDeGit.escreverExclusoes(AREA, new byte[0]));
        l.add(ComandosDeGit.apagarAreaTemporaria(AREA));
        l.add(ComandosDeGit.iniciar(PASTA));
        l.add(ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.name", "Ana Silva"));
        l.add(ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.email", "ana@example.com"));
        l.add(ComandosDeGit.adicionarDaLista(PASTA, new byte[0]));
        l.add(ComandosDeGit.limparPreparo(PASTA));
        l.add(ComandosDeGit.commitar(PASTA, "Primeiro commit"));
        return l;
    }

    @Test
    void aNaturezaDeclaradaEAMesmaQueAFormaDoComandoDiz() {
        for (ComandoGit c : todos()) {
            assertEquals(c.natureza(), ComandosDeGit.naturezaPelaForma(c.argumentos()), c.argumentos().toString());
        }
    }

    @Test
    void naturezasPorGrupo() {
        assertEquals(Natureza.LEITURA, ComandosDeGit.status(PASTA).natureza());
        assertEquals(Natureza.LEITURA, ComandosDeGit.listarNaoRastreados(PASTA, AREA, null, false).natureza());
        assertEquals(Natureza.TEMPORARIO, ComandosDeGit.criarAreaTemporaria(AREA).natureza());
        assertEquals(Natureza.TEMPORARIO, ComandosDeGit.escreverExclusoes(AREA, new byte[0]).natureza());
        assertEquals(Natureza.TEMPORARIO, ComandosDeGit.apagarAreaTemporaria(AREA).natureza());
        for (ComandoGit c : List.of(ComandosDeGit.iniciar(PASTA), ComandosDeGit.adicionarDaLista(PASTA, new byte[0]), ComandosDeGit.commitar(PASTA, "x"), ComandosDeGit.limparPreparo(PASTA),
            ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.name", "Ana"))) {
            assertEquals(Natureza.ESCRITA, c.natureza(), c.argumentos().toString());
        }
    }

    @Test
    void formasPerigosasSaoEscritaMesmoComRoupaDeLeitura() {
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git", "--no-pager", "-C", PASTA, "push")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git", "-C", PASTA, "config", "--global", "user.name", "x")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git", "-C", PASTA, "config", "--get", "--local", "user.name")), "config --local nunca é leitura");
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git", "-C", PASTA, "add", "-f", "x")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git", "-C", PASTA, "clone", "x")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("rm", "-rf", "--", "/srv/lab")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("rm", "-rf", "--", "/tmp/lab-desktop-git-0123456789abcdef/../../etc")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("tee", "/etc/passwd")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git", "init", "-q", "--bare", "/srv/lab/projects/loja-web")), "init --bare fora da área temporária é escrita");
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of()));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("git")));
        assertEquals(Natureza.ESCRITA, ComandosDeGit.naturezaPelaForma(List.of("sh", "-c", "git status")));
    }

    @Test
    void nenhumComandoTemOpcaoDeForcaPushRemotoOuConfiguracaoGlobal() {
        for (ComandoGit c : todos()) {
            List<String> a = c.argumentos();
            for (String proibido : List.of("-f", "--force", "--force-with-lease", "push", "remote", "--global", "--system", "-A", "--all", "clone", "fetch", "pull", "checkout", "reset--hard", "--hard", "clean", "merge", "rebase")) {
                if (proibido.equals("reset--hard")) {
                    continue;
                }
                assertFalse(a.contains(proibido), proibido + " em " + a);
            }
            if (a.contains("add")) {
                assertEquals(List.of("add", "--pathspec-from-file=-", "--pathspec-file-nul"), a.subList(a.indexOf("add"), a.size()), "o add só lê a lista da entrada, nunca um ponto");
                assertFalse(a.contains("."));
            }
            if (a.contains("config") && c.natureza() == Natureza.ESCRITA) {
                assertTrue(a.contains("--local"), "a identidade só vai para o repositório: " + a);
            }
            if (a.contains("commit")) {
                assertTrue(a.contains("--no-gpg-sign") && a.contains("--no-verify"), "nenhuma senha de assinatura nem gancho no meio");
            }
            if (a.contains("init") && c.natureza() == Natureza.ESCRITA) {
                assertEquals(List.of("init", "-q", "-b", "main", "--template="), a.subList(a.indexOf("init"), a.size()), "sem o modelo de pasta do usuário: nenhum gancho copiado");
            }
        }
    }

    @Test
    void aLeituraLevaOsCuidadosFixos() {
        for (ComandoGit c : todos()) {
            if (c.natureza() != Natureza.LEITURA || c.argumentos().equals(List.of("git", "--version"))) {
                continue;
            }
            assertTrue(c.argumentos().contains("--no-pager"), c.argumentos().toString());
            assertTrue(c.argumentos().contains("core.quotepath=off"), "nome com acento sai exato: " + c.argumentos());
            assertTrue(c.argumentos().contains("core.fsmonitor=false"), "um repositório não faz o app rodar um programa dele: " + c.argumentos());
            assertEquals("0", c.ambiente().get("GIT_TERMINAL_PROMPT"));
            assertEquals("0", c.ambiente().get("GIT_OPTIONAL_LOCKS"));
            assertEquals("C", c.ambiente().get("LC_ALL"));
            assertEquals(PASTA, c.argumentos().get(c.argumentos().indexOf("-C") + 1));
        }
        assertEquals("0", ComandosDeGit.versao().ambiente().get("GIT_TERMINAL_PROMPT"));
    }

    @Test
    void soOsCaminhosELongosFormatosSaoTextoLivreEHashEBranchNunca() {
        ComandoGit c = ComandosDeGit.diffDoCommit(PASTA, H, "ação x.txt", "velho nome.txt");
        List<String> a = c.argumentos();
        assertEquals(Set.of(a.indexOf("ação x.txt"), a.indexOf("velho nome.txt")), c.livres());
        assertTrue(a.indexOf("--") < a.indexOf("ação x.txt"), "os caminhos vêm depois do --");
        assertTrue(a.contains("--literal-pathspecs"), "um caminho com * ou : não vira padrão");
        assertFalse(c.livres().contains(a.indexOf(H)));
        ComandoGit lg = ComandosDeGit.commits(PASTA, "feature/x", 0, 51, NONCE);
        assertEquals(Set.of(lg.argumentos().indexOf("feature/x")), lg.livres(), "o branch é texto livre (pode ter acento e símbolos); números e o resto não");
        assertEquals("--", lg.argumentos().get(lg.argumentos().size() - 1));
        assertTrue(lg.argumentos().indexOf("feature/x") < lg.argumentos().indexOf("--"));
        ComandoGit ident = ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.name", "Ana Silva");
        assertEquals(Set.of(ident.argumentos().size() - 1), ident.livres());
        assertEquals("Ana Silva", ident.argumentos().get(ident.argumentos().size() - 1));
        ComandoGit br = ComandosDeGit.branches(PASTA);
        assertEquals(1, br.livres().size(), "só o formato, que tem parênteses");
    }

    @Test
    void entradaForaDaRegraNaoViraComando() {
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.cabecalhoDoCommit(PASTA, "HEAD"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.arquivosDoCommit(PASTA, "--output=/tmp/x"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.diffDoCommit(PASTA, H, "../fora", null));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.diffDoCommit(PASTA, H, "ok.txt", "/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.commits(PASTA, "--all", 0, 10, NONCE));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.commits(PASTA, null, -1, 10, NONCE));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.commits(PASTA, null, 0, 0, NONCE));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.commits(PASTA, null, 0, 501, NONCE));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.status("relativa"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.status("-C/x"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.diffDeNaoRastreado(PASTA, "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.lerConfiguracao(PASTA, "core.sshCommand"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.gravarIdentidadeLocal(PASTA, "core.hooksPath", "x"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.name", "Ana <a@x>"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.email", "ana; ls"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.apagarAreaTemporaria("/srv/lab"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.apagarAreaTemporaria("/tmp/lab-desktop-git-xyz"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.apagarAreaTemporaria("/tmp/lab-desktop-git-0123456789abcdef/../.."));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeGit.escreverExclusoes("/etc", new byte[0]));
    }

    @Test
    void areaTemporariaSoComONomeExatoQueOAppGera() {
        assertTrue(ComandosDeGit.areaRemotaValida(AREA));
        assertFalse(ComandosDeGit.areaRemotaValida("/tmp/lab-desktop-git-0123456789abcde"));
        assertFalse(ComandosDeGit.areaRemotaValida("/tmp/lab-desktop-git-0123456789abcdeg"));
        assertFalse(ComandosDeGit.areaRemotaValida("/var/tmp/lab-desktop-git-0123456789abcdef"));
        assertFalse(ComandosDeGit.areaRemotaValida(AREA + "/x"));
        assertFalse(ComandosDeGit.areaRemotaValida(null));
        assertTrue(ComandosDeGit.areaTemporariaValida("/Users/ana/AppData/Local/Temp/lab-desktop-git-0123456789abcdef"));
        assertTrue(ComandosDeGit.areaTemporariaValida("C:\\Users\\ana\\Temp\\lab-desktop-git-0123456789abcdef"));
        assertFalse(ComandosDeGit.areaTemporariaValida("/tmp/../etc/lab-desktop-git-0123456789abcdef"));
        assertFalse(ComandosDeGit.areaTemporariaValida("lab-desktop-git-0123456789abcdef"));
    }

    @Test
    void oComandoComoTextoParaOKopiarEntreAspasQuandoPrecisa() {
        assertEquals("git --version", ComandosDeGit.versao().comoTexto());
        assertTrue(ComandosDeGit.gravarIdentidadeLocal(PASTA, "user.name", "Ana D'Ávila").comoTexto().endsWith("user.name 'Ana D'\\''Ávila'"));
    }

    @Test
    void oAmbienteDaLeituraNaoCarregaNadaAMais() {
        assertEquals(Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0", "LC_ALL", "C"), ComandosDeGit.status(PASTA).ambiente());
    }
}
