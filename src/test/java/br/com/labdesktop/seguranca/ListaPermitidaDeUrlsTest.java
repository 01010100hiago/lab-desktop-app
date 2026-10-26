package br.com.labdesktop.seguranca;

import static br.com.labdesktop.seguranca.ListaPermitidaDeUrls.Tipo.HOST_DE_PROJETO;
import static br.com.labdesktop.seguranca.ListaPermitidaDeUrls.Tipo.LOGIN;
import static br.com.labdesktop.seguranca.ListaPermitidaDeUrls.Tipo.PULL_REQUEST;
import static br.com.labdesktop.seguranca.ListaPermitidaDeUrls.permitida;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ListaPermitidaDeUrlsTest {

    @Test
    void loginSoClaudeAiEClaudeCom() {
        assertTrue(permitida(LOGIN, "https://claude.ai/oauth/authorize?code=true&client_id=x"));
        assertTrue(permitida(LOGIN, "https://claude.com/login"));
        assertTrue(permitida(LOGIN, "HTTPS://Claude.AI/x"));
        assertFalse(permitida(LOGIN, "http://claude.ai/x"), "só https");
        assertFalse(permitida(LOGIN, "https://claude.ai.malvado.example/x"));
        assertFalse(permitida(LOGIN, "https://malvado.example/claude.ai"));
        assertFalse(permitida(LOGIN, "https://claude.ai@malvado.example/x"), "usuário na URL esconde o host verdadeiro");
        assertFalse(permitida(LOGIN, "https://malvado.example@claude.ai/x"), "qualquer userinfo é recusado");
        assertFalse(permitida(LOGIN, "https://claude.ai:8443/x"));
        assertFalse(permitida(LOGIN, "https://sub.claude.ai/x"), "sem subdomínio: igualdade exata");
        assertFalse(permitida(LOGIN, "javascript:alert(1)"));
        assertFalse(permitida(LOGIN, "file:///etc/passwd"));
        assertFalse(permitida(LOGIN, "https://claude.ai/x\n"), "controle na URL");
        assertFalse(permitida(LOGIN, ""));
        assertFalse(permitida(LOGIN, null));
        assertFalse(permitida(LOGIN, "https://claude.ai/" + "a".repeat(3000)));
    }

    @Test
    void pullRequestSoGithubComCaminhoDePr() {
        assertTrue(permitida(PULL_REQUEST, "https://github.com/usuario/lab-desktop/pull/12"));
        assertTrue(permitida(PULL_REQUEST, "https://github.com/usuario/repo.nome/pull/7/files"));
        assertFalse(permitida(PULL_REQUEST, "https://github.com/usuario/repo/issues/12"));
        assertFalse(permitida(PULL_REQUEST, "https://github.com/usuario/repo/pull/abc"));
        assertFalse(permitida(PULL_REQUEST, "https://github.com.malvado.example/usuario/repo/pull/1"));
        assertFalse(permitida(PULL_REQUEST, "https://gist.github.com/usuario/repo/pull/1"));
        assertFalse(permitida(PULL_REQUEST, "https://github.com/usuario/repo/pull/1/../../../x"));
    }

    @Test
    void hostDeProjetoSoSubdominioDaZona() {
        assertTrue(permitida(HOST_DE_PROJETO, "https://lab-app-loja.example.com/"));
        assertTrue(permitida(HOST_DE_PROJETO, "https://a.b.example.com/x"));
        assertFalse(permitida(HOST_DE_PROJETO, "https://example.com/"), "o domínio puro não é host de projeto");
        assertFalse(permitida(HOST_DE_PROJETO, "https://malvado.example/"));
        assertFalse(permitida(HOST_DE_PROJETO, "https://x.example.com.malvado.example/"));
        assertFalse(permitida(HOST_DE_PROJETO, "http://lab.example.com/"));
        assertFalse(permitida(HOST_DE_PROJETO, "https://-ruim.example.com/"));
        assertFalse(permitida(HOST_DE_PROJETO, "https://a_b.example.com/"));
    }

    @Test
    void devolveAUriNormalizadaQuandoValida() {
        assertEquals(URI.create("https://claude.ai/x"),
            ListaPermitidaDeUrls.validar(LOGIN, " https://claude.ai/x ").orElseThrow());
        assertTrue(ListaPermitidaDeUrls.validar(LOGIN, "nao é url").isEmpty());
    }

    @Test
    void linkDoTerminalSoHttpEHttpsComHostSemUsuarioESemControle() {
        assertTrue(ListaPermitidaDeUrls.validarLinkDoTerminal("https://example.com/a?b=1#c").isPresent());
        assertTrue(ListaPermitidaDeUrls.validarLinkDoTerminal("http://example.com:8080/x").isPresent());
        assertTrue(ListaPermitidaDeUrls.validarLinkDoTerminal("HTTPS://Example.com/x").isPresent());
        for (String ruim : new String[] {"file:///etc/passwd", "javascript:alert(1)", "ssh://host/x", "ftp://host/x", "mailto:a@b.c",
            "data:text/html,<b>x</b>", "x-apple.systempreferences:com.apple.x", "https://user@example.com/", "https://", "https:///x",
            "example.com/sem-esquema", "https://example.com/\nx", "https://example.com/\u001b[31m", "", null,
            "https://example.com/" + "a".repeat(3000)}) {
            assertFalse(ListaPermitidaDeUrls.validarLinkDoTerminal(ruim).isPresent(), "aceitou: " + ruim);
        }
    }
}
