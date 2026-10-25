package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A validação de caminho do domínio: o que entra no servidor passa por aqui antes de virar chamada SFTP. */
class CaminhoRemotoTest {

    @Test
    void normalizaPontosBarrasRepetidasEBarraDoFim() throws ErroSftp {
        assertEquals("/srv/lab", CaminhoRemoto.normalizar("/srv//lab/"));
        assertEquals("/srv/lab", CaminhoRemoto.normalizar("/srv/./lab/."));
        assertEquals("/srv", CaminhoRemoto.normalizar("/srv/lab/.."));
        assertEquals("/", CaminhoRemoto.normalizar("/"));
        assertEquals("/", CaminhoRemoto.normalizar("///"));
    }

    @Test
    void doisPontosNoTopoFicamNoTopoComoNoPosix() throws ErroSftp {
        assertEquals("/", CaminhoRemoto.normalizar("/../../.."));
        assertEquals("/etc", CaminhoRemoto.normalizar("/srv/../../etc"));
    }

    @Test
    void recusaVazioRelativoEControle() {
        for (String ruim : new String[] {"", "srv/lab", "./x", "../x", "/a\nb", "/a\u0000b", "/a\tb", "/a\u007fb", "/a\u001b[31mb"}) {
            ErroSftp e = assertThrows(ErroSftp.class, () -> CaminhoRemoto.normalizar(ruim), ruim);
            assertEquals(ErroSftp.Tipo.NOME_INVALIDO, e.tipo(), ruim);
        }
        assertThrows(ErroSftp.class, () -> CaminhoRemoto.normalizar(null));
    }

    @Test
    void umNomeEUmNome() throws ErroSftp {
        assertEquals("relatório final.md", CaminhoRemoto.validarNome("  relatório final.md "));
        for (String ruim : new String[] {"", "   ", ".", "..", "a/b", "/", "x\ny", "a\u0000", "x".repeat(256)}) {
            assertThrows(ErroSftp.class, () -> CaminhoRemoto.validarNome(ruim), ruim);
        }
        assertEquals("x".repeat(255), CaminhoRemoto.validarNome("x".repeat(255)));
    }

    @Test
    void juntaPastaENome() throws ErroSftp {
        assertEquals("/a/b", CaminhoRemoto.juntar("/a", "b"));
        assertEquals("/b", CaminhoRemoto.juntar("/", "b"));
        assertThrows(ErroSftp.class, () -> CaminhoRemoto.juntar("/a", "../b"));
        assertThrows(ErroSftp.class, () -> CaminhoRemoto.juntar("/a", "b/c"));
    }

    @Test
    void paiNomeEPartes() {
        assertEquals("/srv", CaminhoRemoto.pai("/srv/lab"));
        assertEquals("/", CaminhoRemoto.pai("/srv"));
        assertEquals("/", CaminhoRemoto.pai("/"));
        assertEquals("lab", CaminhoRemoto.nome("/srv/lab/"));
        assertEquals("", CaminhoRemoto.nome("/"));
        assertEquals(List.of("srv", "lab"), CaminhoRemoto.partes("/srv/lab"));
        assertEquals(List.of(), CaminhoRemoto.partes("/"));
    }

    @Test
    void dentroDeCompara_por_trechos_e_nao_por_prefixo_de_texto() {
        assertTrue(CaminhoRemoto.dentroDe("/srv/lab", "/srv/lab"));
        assertTrue(CaminhoRemoto.dentroDe("/srv/lab", "/srv/lab/x/y"));
        assertFalse(CaminhoRemoto.dentroDe("/srv/lab", "/srv/lab2"));
        assertFalse(CaminhoRemoto.dentroDe("/srv/lab", "/srv"));
        assertFalse(CaminhoRemoto.dentroDe("/srv/lab", "/srv/lab/../etc"));
        assertTrue(CaminhoRemoto.dentroDe("/", "/qualquer/coisa"));
    }

    @Test
    void relativoAPartirDaRaiz() {
        assertEquals("", CaminhoRemoto.relativo("/srv/lab", "/srv/lab"));
        assertEquals("a/b", CaminhoRemoto.relativo("/srv/lab", "/srv/lab/a/b"));
        assertEquals("srv/lab", CaminhoRemoto.relativo("/", "/srv/lab"));
    }

    @Test
    void resolveAlvoDeLinkAbsolutoERelativo() throws ErroSftp {
        assertEquals("/etc", CaminhoRemoto.resolverAlvo("/srv/lab/orbita", "/etc"));
        assertEquals("/srv/lab/outro", CaminhoRemoto.resolverAlvo("/srv/lab/orbita", "../outro"));
        assertEquals("/srv/lab/orbita/dados", CaminhoRemoto.resolverAlvo("/srv/lab/orbita", "dados"));
        assertEquals("/", CaminhoRemoto.resolverAlvo("/srv", "../../.."));
    }
}
