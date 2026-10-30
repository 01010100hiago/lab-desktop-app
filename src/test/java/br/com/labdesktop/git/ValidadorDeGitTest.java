package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** As regras de tudo o que o app põe numa linha do git: hash, branch, caminho de arquivo, pasta, nome e e-mail de quem assina. */
class ValidadorDeGitTest {

    @Test
    void hashDeSeteAQuarentaLetrasHexadecimaisEmMinusculas() {
        assertTrue(ValidadorDeGit.hashValido("abcdef0"));
        assertTrue(ValidadorDeGit.hashValido("a".repeat(40)));
        assertFalse(ValidadorDeGit.hashValido("abcdef"), "6 é pouco");
        assertTrue(ValidadorDeGit.hashValido("a".repeat(64)), "o hash inteiro de um repositório SHA-256");
        assertFalse(ValidadorDeGit.hashValido("a".repeat(65)));
        assertTrue(ValidadorDeGit.hashInteiro("a".repeat(40)) && ValidadorDeGit.hashInteiro("a".repeat(64)));
        assertFalse(ValidadorDeGit.hashInteiro("a".repeat(41)) || ValidadorDeGit.hashInteiro("abcdef0"));
        assertFalse(ValidadorDeGit.hashValido("ABCDEF0"), "maiúscula não");
        assertFalse(ValidadorDeGit.hashValido("abcdefg"));
        assertFalse(ValidadorDeGit.hashValido("HEAD"));
        assertFalse(ValidadorDeGit.hashValido("--output=/tmp/x"));
        assertFalse(ValidadorDeGit.hashValido("abcdef0; rm -rf /"));
        assertFalse(ValidadorDeGit.hashValido("abcdef0\n"), "quebra de linha no fim não passa pelo $");
        assertFalse(ValidadorDeGit.hashValido(null));
        assertThrows(IllegalArgumentException.class, () -> ValidadorDeGit.exigirHash("HEAD~1"));
    }

    @Test
    void branchPorListaBranca() {
        for (String ok : new String[] {"main", "feature/entrega", "release-1.2", "hotfix_9", "a", "Feature/ABC-123", "ação/versão", "fix#12", "feat+x", "user@host", "a;b", "a$b", "a(b)", "ok'nome"}) {
            assertTrue(ValidadorDeGit.branchValido(ok), ok);
        }
        for (String ruim : new String[] {"", "-main", "--upload-pack=x", "a..b", "a//b", "a/", "a.", "a.lock", "x/.oculto", "x/y.lock", "com espaço", "a\nb", "a\tb", "a\\b",
            "~x", "x^", "x:y", "x?", "x[y", "x*", "@{u}", "@", "a@{b", "a".repeat(201)}) {
            assertFalse(ValidadorDeGit.branchValido(ruim), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ValidadorDeGit.exigirBranch("--force"));
    }

    @Test
    void caminhoRelativoSemSairDoRepositorio() {
        for (String ok : new String[] {"a.txt", "src/app.js", "ação x.txt", "pasta com espaço/arquivo 'aspas'.txt", ".gitignore", "a/b/c/d.txt", "$HOME.txt", "a;b.txt"}) {
            assertTrue(ValidadorDeGit.caminhoRelativoValido(ok), ok);
        }
        for (String ruim : new String[] {"", "/etc/passwd", "../fora", "a/../../fora", "a/./b", "./a", "a//b", "a/", "C:/x", "c:\\x", "a\\b", "a\nb", "a\tb", "a\0b", "a\u001bb", "x".repeat(1025)}) {
            assertFalse(ValidadorDeGit.caminhoRelativoValido(ruim), ruim.replace("\n", "\\n"));
        }
        assertFalse(ValidadorDeGit.caminhoRelativoValido(null));
        assertThrows(IllegalArgumentException.class, () -> ValidadorDeGit.exigirCaminhoRelativo("../x"));
    }

    @Test
    void pastaDoProjetoAbsolutaSemPontoPonto() {
        assertTrue(ValidadorDeGit.pastaValida("/srv/lab/projects/loja-web"));
        assertTrue(ValidadorDeGit.pastaValida("/Users/ana/LabLocal/projects/app-exemplo"));
        assertTrue(ValidadorDeGit.pastaValida("C:\\Users\\ana\\LabLocal\\projects\\app-exemplo"));
        assertTrue(ValidadorDeGit.pastaValida("/Users/João Silva/projetos/x"));
        for (String ruim : new String[] {"", "loja-web", "projects/loja-web", "-C/x", "/srv/../etc", "/srv/lab\nx", "/srv/lab\0x", "C:relativo", "x".repeat(1025)}) {
            assertFalse(ValidadorDeGit.pastaValida(ruim), ruim.replace("\n", "\\n"));
        }
        assertThrows(IllegalArgumentException.class, () -> ValidadorDeGit.exigirPasta("relativa"));
    }

    @Test
    void nomeEEmailDeQuemAssina() {
        assertTrue(ValidadorDeGit.nomeDeAutorValido("Ana Silva"));
        assertTrue(ValidadorDeGit.nomeDeAutorValido("Joana D'Ávila"));
        assertTrue(ValidadorDeGit.nomeDeAutorValido("O'Brien"));
        for (String ruim : new String[] {"", " ", " Ana", "Ana ", "Ana <ana@x>", "Ana\nSilva", "Ana\tSilva", "A\\B", "x".repeat(101)}) {
            assertFalse(ValidadorDeGit.nomeDeAutorValido(ruim), ruim.replace("\n", "\\n"));
        }
        assertFalse(ValidadorDeGit.nomeDeAutorValido(null));
        assertTrue(ValidadorDeGit.emailValido("ana@example.com"));
        assertTrue(ValidadorDeGit.emailValido("ana.silva+git@example.com"));
        for (String ruim : new String[] {"", "ana", "ana@", "@example.com", "ana@x", "ana silva@example.com", "ana@example.com; ls", "ana@example.com\n", "ana@x$.com", "<a@example.com>"}) {
            assertFalse(ValidadorDeGit.emailValido(ruim), ruim.replace("\n", "\\n"));
        }
        assertFalse(ValidadorDeGit.emailValido(null));
    }
}
