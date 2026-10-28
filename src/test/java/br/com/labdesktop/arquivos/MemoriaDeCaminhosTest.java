package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoriaDeCaminhosTest {

    @Test
    void padraoDaRaizDosProjetosEOCaminhoDoLab() {
        assertEquals("/srv/lab/projects", MemoriaDeCaminhos.emMemoria().raizDosProjetos("s1"));
        assertEquals(RaizDoServidor.PROJETOS, MemoriaDeCaminhos.emMemoria().raizEscolhida("s1"));
        assertTrue(MemoriaDeCaminhos.emMemoria().ultimoCaminhoDoServidor("s1").isEmpty());
    }

    @Test
    void lembraPorServidorEPorProjetoEGravaEmDisco(@TempDir Path tmp) throws Exception {
        Path arquivo = tmp.resolve("memoria-arquivos.json");
        MemoriaDeCaminhos m = MemoriaDeCaminhos.doArquivo(arquivo);
        m.lembrarDoServidor("s1", RaizDoServidor.MINHA_PASTA, "/home/ana/notas");
        m.lembrarDoServidor("s2", RaizDoServidor.PROJETOS, "/srv/lab/projects/x");
        m.lembrarDoProjeto("s1", "orbita", "/srv/lab/projects/orbita/src");
        m.lembrarDoProjeto("s1", "bazar", "/srv/lab/projects/bazar");
        m.definirRaizDosProjetos("s2", "/dados/projetos/");
        m.esvaziar();

        MemoriaDeCaminhos lida = MemoriaDeCaminhos.doArquivo(arquivo);
        assertEquals(RaizDoServidor.MINHA_PASTA, lida.raizEscolhida("s1"));
        assertEquals(Optional.of("/home/ana/notas"), lida.ultimoCaminhoDoServidor("s1"));
        assertEquals(Optional.of("/srv/lab/projects/x"), lida.ultimoCaminhoDoServidor("s2"));
        assertEquals(Optional.of("/srv/lab/projects/orbita/src"), lida.ultimoCaminhoDoProjeto("s1", "orbita"));
        assertEquals(Optional.of("/srv/lab/projects/bazar"), lida.ultimoCaminhoDoProjeto("s1", "bazar"));
        assertTrue(lida.ultimoCaminhoDoProjeto("s2", "orbita").isEmpty(), "cada servidor tem os seus");
        assertEquals("/dados/projetos", lida.raizDosProjetos("s2"));
        assertEquals("/srv/lab/projects", lida.raizDosProjetos("s1"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(arquivo)));
    }

    @Test
    void raizDosProjetosInvalidaOuBarraNaoValem() {
        MemoriaDeCaminhos m = MemoriaDeCaminhos.emMemoria();
        assertThrows(ErroSftp.class, () -> m.definirRaizDosProjetos("s1", "/"));
        assertThrows(ErroSftp.class, () -> m.definirRaizDosProjetos("s1", "relativo"));
        assertThrows(ErroSftp.class, () -> m.definirRaizDosProjetos("s1", ""));
        assertThrows(ErroSftp.class, () -> m.definirRaizDosProjetos("s1", "/a\nb"));
        assertEquals("/srv/lab/projects", m.raizDosProjetos("s1"));
    }

    @Test
    void voltarAoPadraoApagaAConfiguracao(@TempDir Path tmp) throws Exception {
        MemoriaDeCaminhos m = MemoriaDeCaminhos.doArquivo(tmp.resolve("m.json"));
        m.definirRaizDosProjetos("s1", "/dados");
        assertEquals("/dados", m.raizDosProjetos("s1"));
        m.definirRaizDosProjetos("s1", "/srv/lab/projects");
        assertEquals("/srv/lab/projects", m.raizDosProjetos("s1"));
    }

    @Test
    void arquivoEstragadoOuEditadoACaoViraMemoriaVazia(@TempDir Path tmp) throws Exception {
        Path arquivo = tmp.resolve("m.json");
        Files.writeString(arquivo, "{ isto não é json");
        assertTrue(MemoriaDeCaminhos.doArquivo(arquivo).ultimoCaminhoDoServidor("s1").isEmpty());
        Files.writeString(arquivo, "{\"servidores\":{\"s1\":{\"raizDosProjetos\":\"relativo/lixo\",\"raiz\":\"NAO_EXISTE\",\"campoNovo\":1}}}");
        MemoriaDeCaminhos m = MemoriaDeCaminhos.doArquivo(arquivo);
        assertEquals("/srv/lab/projects", m.raizDosProjetos("s1"));
        assertEquals(RaizDoServidor.PROJETOS, m.raizEscolhida("s1"));
        assertFalse(m.ultimoCaminhoDoServidor("s1").isPresent());
    }

    @Test
    void naoGuardaProjetosSemLimite() {
        MemoriaDeCaminhos m = MemoriaDeCaminhos.emMemoria();
        for (int i = 0; i < 700; i++) {
            m.lembrarDoProjeto("s1", "p" + i, "/x/p" + i);
        }
        assertTrue(m.ultimoCaminhoDoProjeto("s1", "p0").isEmpty(), "os mais antigos saíram");
        assertTrue(m.ultimoCaminhoDoProjeto("s1", "p699").isPresent());
    }

    @Test
    void lembraOPeriodoDasTendenciasPorServidorEGravaEmDisco(@TempDir Path tmp) throws Exception {
        Path arquivo = tmp.resolve("memoria-arquivos.json");
        MemoriaDeCaminhos m = MemoriaDeCaminhos.doArquivo(arquivo);
        assertEquals(null, m.janelaDasTendencias("s1"), "nunca escolheu: sem memória");
        m.definirJanelaDasTendencias("s1", "7d");
        m.definirJanelaDasTendencias("s2", "1h");
        m.esvaziar();
        MemoriaDeCaminhos lida = MemoriaDeCaminhos.doArquivo(arquivo);
        assertEquals("7d", lida.janelaDasTendencias("s1"));
        assertEquals("1h", lida.janelaDasTendencias("s2"), "cada servidor tem o seu");
        assertEquals(null, lida.janelaDasTendencias("s3"));
        // código inválido nunca entra, e o que já estava fica
        lida.definirJanelaDasTendencias("s1", "2h");
        lida.definirJanelaDasTendencias("s1", null);
        lida.definirJanelaDasTendencias("s1", "constructor");
        assertEquals("7d", lida.janelaDasTendencias("s1"));
        // convive com o resto da memória do servidor
        lida.lembrarDoServidor("s1", RaizDoServidor.MINHA_PASTA, "/home/ana");
        lida.esvaziar();
        MemoriaDeCaminhos outra = MemoriaDeCaminhos.doArquivo(arquivo);
        assertEquals("7d", outra.janelaDasTendencias("s1"));
        assertEquals(Optional.of("/home/ana"), outra.ultimoCaminhoDoServidor("s1"));
    }

    @Test
    void arquivoEditadoComPeriodoComLixoNaoDerrubaEVoltaAoPadrao(@TempDir Path tmp) throws Exception {
        Path arquivo = tmp.resolve("memoria-arquivos.json");
        Files.writeString(arquivo, "{\"versao\":1,\"servidores\":{\"s1\":{\"janelaDasTendencias\":\"1 ano\"},\"s2\":{\"janelaDasTendencias\":\"30d\"}}}");
        MemoriaDeCaminhos m = MemoriaDeCaminhos.doArquivo(arquivo);
        assertEquals(null, m.janelaDasTendencias("s1"));
        assertEquals("30d", m.janelaDasTendencias("s2"));
    }
}
