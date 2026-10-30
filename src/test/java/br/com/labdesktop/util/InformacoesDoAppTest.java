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
        assertEquals(Versao.commit(), i.commit());
        assertEquals(Versao.data(), i.dataDaCompilacao());
        assertTrue(i.agenteVersao().matches("\\d+\\.\\d+\\.\\d+-[0-9a-f]{7}"), i.agenteVersao());
        assertTrue(i.agenteCommit().matches("[0-9a-f]{40}"), i.agenteCommit());
        assertTrue(i.agenteCommit().startsWith(i.agenteVersao().substring(i.agenteVersao().indexOf('-') + 1)), "a versão do agente leva os 7 primeiros do commit");
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
        assertEquals(Path.of("~", "Library", "Application Support", "LabDesktop").toString(), i.pastaDeDados(), "com a barra do sistema");
        assertFalse(i.pastaDeDados().contains("maria"), "o nome da pessoa não sai do app");
        assertFalse(i.comoTexto().contains("maria"));
    }

    /** {@code --pasta ./dados} (relativo) não casava com a pasta do usuário e o caminho com o nome dela saía no Sobre e na cópia. */
    @Test
    void redigirPastaNormalizaAntesDeComparar() {
        String casa = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
        String sep = Path.of(casa).getFileSystem().getSeparator();
        assertEquals("~" + sep + "dados", InformacoesDoApp.redigirPasta(Path.of("dados"), casa), "relativo à pasta atual, que é a do usuário");
        assertEquals("~" + sep + "dados", InformacoesDoApp.redigirPasta(Path.of("a", "..", "dados"), casa), "com ponto-ponto no meio");
        assertEquals("~", InformacoesDoApp.redigirPasta(Path.of("."), casa));
        assertEquals("~", InformacoesDoApp.redigirPasta(Path.of(casa, "x", ".."), casa));
        String fora = InformacoesDoApp.redigirPasta(Path.of(casa, "..", "outra"), casa);
        assertTrue(!fora.startsWith("~") && !fora.contains(".."), "fora da pasta do usuário: o caminho absoluto limpo: " + fora);
    }

    private static String absoluto(String caminho) {
        return Path.of(caminho).toAbsolutePath().normalize().toString();
    }

    @Test
    void redigirPastaTrataOsCasosDeBorda() {
        assertEquals("~", InformacoesDoApp.redigirPasta(Path.of("/Users/maria"), "/Users/maria"));
        // fora da pasta do usuário o caminho sai absoluto e limpo, como o sistema o escreve (no Windows ganha o disco: D:\\srv\\dados)
        assertEquals(absoluto("/Users/mariana/x"), InformacoesDoApp.redigirPasta(Path.of("/Users/mariana/x"), "/Users/maria"), "o prefixo tem de ser uma pasta inteira");
        assertEquals(absoluto("/srv/dados"), InformacoesDoApp.redigirPasta(Path.of("/srv/dados"), "/Users/maria"));
        assertEquals(absoluto("/srv/dados"), InformacoesDoApp.redigirPasta(Path.of("/srv/dados"), null));
        assertEquals(absoluto("/srv/dados"), InformacoesDoApp.redigirPasta(Path.of("/srv/dados"), " "));
    }

    @Test
    void oTextoParaCopiarTemVersaoCommitDataSistemaJavaEAgenteENadaMais() {
        InformacoesDoApp i = new InformacoesDoApp("Lab Desktop", "0.1.320", "a207ad4", "2026-10-30", "0.1.0-dadf957", "dadf957f1f824b16fa1876f74848e33ab4c4a5ae", "21.0.12 (Homebrew)", "Mac OS X 15.6", "aarch64",
            "~/Library/Application Support/LabDesktop");
        assertEquals("Lab Desktop 0.1.320\nCommit: a207ad4\nCompilado em: 2026-10-30\nSistema: Mac OS X 15.6 (aarch64)\nJava: 21.0.12 (Homebrew)\nAgente empacotado: 0.1.0-dadf957 (commit dadf957f1f824b16fa1876f74848e33ab4c4a5ae)",
            i.comoTexto());
    }
}
