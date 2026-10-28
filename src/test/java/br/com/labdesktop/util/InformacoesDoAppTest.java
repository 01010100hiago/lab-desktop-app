package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Versao;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Os dados da tela Sobre: o agente empacotado, a versão, o sistema e a pasta de dados sem a pasta do usuário. */
class InformacoesDoAppTest {

    private static final CaminhosApp CAMINHOS = CaminhosApp.comPasta(Path.of("/Users/maria/Library/Application Support/LabDesktop"));

    @Test
    void leOAgenteEmpacotadoDeVerdadeEAVersaoDoApp() {
        InformacoesDoApp i = InformacoesDoApp.coletar(CAMINHOS);
        assertEquals("Lab Desktop", i.nome());
        assertEquals(Versao.atual(), i.versao());
        if (br.com.labdesktop.teste.PacoteDeTeste.temPacoteDeVerdade()) {
            assertTrue(i.agenteVersao().matches("\\d+\\.\\d+\\.\\d+-[0-9a-f]{7}"), i.agenteVersao());
            assertTrue(i.agenteCommit().matches("[0-9a-f]{40}"), i.agenteCommit());
            assertTrue(i.agenteCommit().startsWith(i.agenteVersao().substring(i.agenteVersao().indexOf('-') + 1)), "a versão do agente leva os 7 primeiros do commit");
        } else {
            assertEquals("não incluído", i.agenteVersao(), "app compilado sem o pacote do agente");
            assertEquals("não incluído", i.agenteCommit());
        }
        assertEquals(System.getProperty("os.arch"), i.arquitetura());
    }

    @Test
    void oAgenteDeMentiraELidoDoJsonEOQueFaltaVira_naoIncluido() {
        InformacoesDoApp com = InformacoesDoApp.coletar(CAMINHOS, "/Users/maria",
            new ByteArrayInputStream("{\"versao\":\"9.9.9-abcdef0\",\"commit\":\"abcdef0123456789abcdef0123456789abcdef01\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("9.9.9-abcdef0", com.agenteVersao());
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", com.agenteCommit());
        InformacoesDoApp sem = InformacoesDoApp.coletar(CAMINHOS, "/Users/maria", null);
        assertEquals("não incluído", sem.agenteVersao());
        InformacoesDoApp ruim = InformacoesDoApp.coletar(CAMINHOS, "/Users/maria", new ByteArrayInputStream("isto nao e json".getBytes(StandardCharsets.UTF_8)));
        assertEquals("não incluído", ruim.agenteCommit(), "um arquivo ilegível não derruba a tela Sobre");
    }

    @Test
    void aPastaDoUsuarioViraTilNaPastaDeDadosENaCopia() {
        InformacoesDoApp i = InformacoesDoApp.coletar(CAMINHOS, "/Users/maria", null);
        assertEquals("~/Library/Application Support/LabDesktop", i.pastaDeDados());
        assertFalse(i.pastaDeDados().contains("maria"), "o nome da pessoa não sai do app");
        assertFalse(i.comoTexto().contains("maria"));
    }

    @Test
    void redigirPastaTrataOsCasosDeBorda() {
        assertEquals("~", InformacoesDoApp.redigirPasta(Path.of("/Users/maria"), "/Users/maria"));
        assertEquals("/Users/mariana/x", InformacoesDoApp.redigirPasta(Path.of("/Users/mariana/x"), "/Users/maria"), "o prefixo tem de ser uma pasta inteira");
        assertEquals("/srv/dados", InformacoesDoApp.redigirPasta(Path.of("/srv/dados"), "/Users/maria"));
        assertEquals("/srv/dados", InformacoesDoApp.redigirPasta(Path.of("/srv/dados"), null));
        assertEquals("/srv/dados", InformacoesDoApp.redigirPasta(Path.of("/srv/dados"), " "));
    }

    @Test
    void oTextoParaCopiarTemVersaoSistemaJavaEAgenteENadaMais() {
        InformacoesDoApp i = new InformacoesDoApp("Lab Desktop", "0.1.0", "0.1.0-abc1234", "abc1234def5678abc1234def5678abc1234def56", "21.0.12 (Homebrew)", "Mac OS X 15.6", "aarch64",
            "~/Library/Application Support/LabDesktop");
        assertEquals("Lab Desktop 0.1.0\nSistema: Mac OS X 15.6 (aarch64)\nJava: 21.0.12 (Homebrew)\nAgente empacotado: 0.1.0-abc1234 (commit abc1234def5678abc1234def5678abc1234def56)",
            i.comoTexto());
    }
}
