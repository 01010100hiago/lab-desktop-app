package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O repositório Git escolhido em cada projeto fica nas preferências do app, por servidor e projeto, e sobrevive a fechar e abrir o app. */
class RepositoriosGitLembradosTest {

    @Test
    void lembraPorServidorEProjetoEVoltaDepoisDeFecharEAbrirOApp(@TempDir Path tmp) {
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        PreferenciasAtuais prefs = new PreferenciasAtuais(caminhos, Preferencias.padrao());
        RepositoriosGitLembrados lembrados = RepositoriosGitLembrados.dasPreferencias(prefs);
        assertNull(lembrados.lembrado("s1", "loja-web"));
        lembrados.lembrar("s1", "loja-web", "app");
        lembrados.lembrar("s2", "loja-web", "web");
        assertEquals("app", lembrados.lembrado("s1", "loja-web"));
        assertEquals("web", lembrados.lembrado("s2", "loja-web"));
        assertNull(lembrados.lembrado("s1", "carteira"));
        prefs.descarregar();

        // o app abre de novo: lê o arquivo
        RepositoriosGitLembrados depois = RepositoriosGitLembrados.dasPreferencias(new PreferenciasAtuais(caminhos, Preferencias.carregar(caminhos)));
        assertEquals("app", depois.lembrado("s1", "loja-web"));
        depois.lembrar("s1", "loja-web", null);
        assertNull(depois.lembrado("s1", "loja-web"));
        assertEquals("web", depois.lembrado("s2", "loja-web"), "esquecer um não mexe no outro");
    }

    @Test
    void valorQueNaoValeNaoEntraEONenhumNaoLembraNada() {
        RepositoriosGitLembrados nenhum = RepositoriosGitLembrados.NENHUM;
        nenhum.lembrar("s", "p", "app");
        assertNull(nenhum.lembrado("s", "p"));
    }
}
