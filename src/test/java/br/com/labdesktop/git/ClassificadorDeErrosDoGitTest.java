package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** O que o git (ou o shell) diz vira uma frase simples; o comando do {@code safe.directory} é só TEXTO para o usuário. */
class ClassificadorDeErrosDoGitTest {

    private static FalhaDoGit c(int status, String stderr) {
        return ClassificadorDeErrosDoGit.classificar(status, stderr, "/srv/lab/projects/loja-web");
    }

    @Test
    void gitNaoInstaladoEmTodasAsFormasQueUmShellTem() {
        for (String e : new String[] {"sh: 1: git: not found", "bash: git: command not found", "env: 'git': No such file or directory", "env: ‘git’: No such file or directory",
            "zsh: command not found: git", "'git' is not recognized as an internal or external command"}) {
            assertEquals(FalhaDoGit.Tipo.GIT_NAO_INSTALADO, c(127, e).tipo(), e);
        }
        assertEquals(FalhaDoGit.Tipo.GIT_NAO_INSTALADO, c(127, "git: not found").tipo());
        assertEquals("O Git não está instalado nesta máquina.", ClassificadorDeErrosDoGit.naoInstalado().frase());
    }

    @Test
    void donoDiferenteDaOComandoExatoComAPastaDoGit() {
        String git = "fatal: detected dubious ownership in repository at '/srv/lab/projects/loja-web'\nTo add an exception for this directory, call:\n\n\tgit config --global --add safe.directory /srv/lab/projects/loja-web\n";
        FalhaDoGit f = c(128, git);
        assertEquals(FalhaDoGit.Tipo.PROPRIEDADE_DUVIDOSA, f.tipo());
        assertEquals("git config --global --add safe.directory /srv/lab/projects/loja-web", f.comandoParaCopiar());
        assertTrue(f.frase().contains("outro usuário"));
        assertTrue(f.frase().contains("não faz isso sozinho"));
    }

    @Test
    void aPastaComEspacoOuAspasVaiEntreAspasNoComando() {
        FalhaDoGit f = ClassificadorDeErrosDoGit.classificar(128, "fatal: detected dubious ownership in repository at '/home/ana/meus projetos/x'", null);
        assertEquals("git config --global --add safe.directory '/home/ana/meus projetos/x'", f.comandoParaCopiar());
        // pasta que o git escreveu com caractere de controle: não confia nela, usa a do app
        FalhaDoGit g = ClassificadorDeErrosDoGit.classificar(128, "fatal: detected dubious ownership in repository at '/x/it\t; rm -rf /'", "/srv/lab/projects/app-exemplo");
        assertEquals("git config --global --add safe.directory /srv/lab/projects/app-exemplo", g.comandoParaCopiar());
        assertNull(ClassificadorDeErrosDoGit.propriedadeDuvidosa(null).comandoParaCopiar());
        assertEquals("git config --global --add safe.directory '/a/b'\\''c'", ClassificadorDeErrosDoGit.propriedadeDuvidosa("/a/b'c").comandoParaCopiar());
    }

    @Test
    void naoERepositorioSemCommitsSemIdentidadeEPermissao() {
        assertEquals(FalhaDoGit.Tipo.NAO_E_REPOSITORIO, c(128, "fatal: not a git repository (or any of the parent directories): .git").tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_COMMITS, c(128, "fatal: your current branch 'main' does not have any commits yet").tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_COMMITS, c(128, "fatal: ambiguous argument 'HEAD': unknown revision or path not in the working tree.").tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_IDENTIDADE, c(128, "Author identity unknown\n\n*** Please tell me who you are.\n\nRun\n  git config --global user.email").tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_IDENTIDADE, c(128, "fatal: unable to auto-detect email address (got 'ana@host.(none)')").tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_PERMISSAO, c(128, "fatal: Unable to create '/srv/x/.git/index.lock': Permission denied").tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_PERMISSAO, c(1, "error: cannot open .git/FETCH_HEAD: Permission denied").tipo());
    }

    @Test
    void hashInexistenteNaoVirouSemCommits() {
        FalhaDoGit f = c(128, "fatal: bad object 0000000");
        assertEquals(FalhaDoGit.Tipo.OUTRO, f.tipo());
        assertEquals(FalhaDoGit.Tipo.OUTRO, c(128, "fatal: unknown revision 'abc1234'").tipo());
    }

    @Test
    void outrosErrosTrazemOQueOGitDisseSemSegredo() {
        FalhaDoGit f = c(1, "error: algo deu errado com https://ana:ghp_" + "A".repeat(36) + "@github.com/x/y");
        assertEquals(FalhaDoGit.Tipo.OUTRO, f.tipo());
        assertTrue(f.frase().contains("(código 1)"));
        assertFalse(f.frase().contains("ghp_" + "A".repeat(36)), "o Redator mascara o token: " + f.frase());
        assertTrue(c(1, "").frase().contains("sem mais detalhes"));
        assertTrue(c(1, "x".repeat(2000)).frase().length() < 500, "frase com teto");
    }

    @Test
    void versaoMinimaDoGit() {
        assertTrue(ClassificadorDeErrosDoGit.antigoDemais("git version 2.27.0"));
        assertTrue(ClassificadorDeErrosDoGit.antigoDemais("git version 1.8.3.1"));
        assertFalse(ClassificadorDeErrosDoGit.antigoDemais("git version 2.28.0"));
        assertFalse(ClassificadorDeErrosDoGit.antigoDemais("git version 2.50.1 (Apple Git-155)"));
        assertFalse(ClassificadorDeErrosDoGit.antigoDemais("git version 3.0.0"));
        assertFalse(ClassificadorDeErrosDoGit.antigoDemais("não sei"), "versão ilegível não reprova: o git diz o erro");
        assertEquals(2, ClassificadorDeErrosDoGit.versao("git version 2.43.0")[0]);
        assertEquals(43, ClassificadorDeErrosDoGit.versao("git version 2.43.0")[1]);
        assertTrue(ClassificadorDeErrosDoGit.antigo("git version 2.20.1").frase().contains("2.28"));
    }
}
