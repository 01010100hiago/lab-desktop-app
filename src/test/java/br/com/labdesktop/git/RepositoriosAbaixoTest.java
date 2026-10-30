package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A saída do {@code find} é TEXTO que vem de um servidor: só vale o que é {@code <raiz>/<subpasta>/.git} de verdade, em até 3 níveis, fora das pastas de dependências, sem {@code ..}, sem
 * controle no nome, com no máximo 20 achados e na ordem do alfabeto. Valores conhecidos, sem git nem disco.
 */
class RepositoriosAbaixoTest {

    private static final String RAIZ = "/srv/lab/projects/loja-web";

    private static String saida(String... caminhosRelativos) {
        StringBuilder sb = new StringBuilder();
        for (String c : caminhosRelativos) {
            sb.append(RAIZ).append('/').append(c).append('\0');
        }
        return sb.toString();
    }

    private static List<String> achar(String... relativos) {
        return RepositoriosAbaixo.interpretar(RAIZ, saida(relativos)).caminhos();
    }

    @Test
    void umRepositorioEmApp() {
        RepositoriosAbaixo.Achados a = RepositoriosAbaixo.interpretar(RAIZ, saida("app/.git"));
        assertEquals(List.of("app"), a.caminhos());
        assertFalse(a.cortado());
    }

    @Test
    void doisRepositoriosSaemEmOrdemDeAlfabetoSemRepetir() {
        assertEquals(List.of("api", "web"), achar("web/.git", "api/.git", "web/.git"));
    }

    @Test
    void ateTresNiveisDoGitEntramEQuatroNao() {
        assertEquals(List.of("a/b"), achar("a/b/.git"), "a/b/.git está a 3 níveis da raiz: entra");
        assertEquals(List.of(), achar("a/b/c/.git"), "4 níveis: ignorado");
        assertEquals(List.of("x"), achar("a/b/c/d/.git", "x/.git"));
    }

    @Test
    void pastasDeDependenciasENaoEntramMesmoQueOFindAsTenhaImpresso() {
        assertEquals(List.of(), achar("node_modules/pkg/.git", "vendor/lib/.git", ".cache/x/.git", ".venv/lib/.git", "dist/sub/.git", "build/gerado/.git"));
        assertEquals(List.of(), achar("app/node_modules/.git"), "uma pasta ignorada em qualquer nível");
        assertEquals(List.of("app"), achar("app/.git", "node_modules/pkg/.git"));
    }

    @Test
    void oQueNaoComecaPelaRaizOuTemPontoPontoOuControleEDescartado() {
        assertEquals(List.of(), RepositoriosAbaixo.interpretar(RAIZ, "/etc/outro/.git\0" + "/srv/lab/projects/loja-web-irmao/app/.git\0").caminhos(), "fora da raiz");
        assertEquals(List.of(), achar("../outro/.git", "app/../../x/.git", "a/./b/.git"), "nada de .. nem .");
        assertEquals(List.of(), achar("app\nruim/.git", "app\tx/.git", "ap\u0007p/.git"), "controle no nome: descartado em silêncio");
        assertEquals(List.of(), achar(".git", "app/.git/.git", "app/.git/config", "app/algo"), "só <subpasta>/.git, nunca o .git da raiz nem de dentro de um .git");
        assertEquals(List.of(), RepositoriosAbaixo.interpretar(RAIZ, "").caminhos());
        assertEquals(List.of(), RepositoriosAbaixo.interpretar(RAIZ, "lixo sem nul").caminhos());
    }

    @Test
    void nomeComEspacoAcentoEAspaEUmNomeLegitimo() {
        assertEquals(List.of("ação d'ávila", "meu app"), achar("meu app/.git", "ação d'ávila/.git"));
    }

    @Test
    void ateVinteAchadosEOResto() {
        List<String> muitos = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            muitos.add(String.format("r%02d/.git", i));
        }
        RepositoriosAbaixo.Achados a = RepositoriosAbaixo.interpretar(RAIZ, saida(muitos.toArray(String[]::new)));
        assertEquals(20, a.caminhos().size());
        assertTrue(a.cortado());
        assertEquals("r00", a.caminhos().get(0));
        assertEquals("r19", a.caminhos().get(19));
        RepositoriosAbaixo.Achados exato = RepositoriosAbaixo.interpretar(RAIZ, saida(muitos.subList(0, 20).toArray(String[]::new)));
        assertEquals(20, exato.caminhos().size());
        assertFalse(exato.cortado(), "vinte certinhos não é cortar");
    }

    @Test
    void aRaizComBarraNoFimOuNoWindowsTambemFunciona() {
        assertEquals(List.of("app"), RepositoriosAbaixo.interpretar(RAIZ + "/", saida("app/.git")).caminhos());
        assertEquals(List.of("app"), RepositoriosAbaixo.interpretar("C:\\Users\\ana\\LabLocal\\projects\\loja", "C:/Users/ana/LabLocal/projects/loja/app/.git\0").caminhos());
    }

    @Test
    void subpastaAceitaOMesmoCriterioDosDoisCaminhos() {
        assertTrue(RepositoriosAbaixo.subpastaAceita("app"));
        assertTrue(RepositoriosAbaixo.subpastaAceita("servicos/api"));
        assertFalse(RepositoriosAbaixo.subpastaAceita("a/b/c"));
        assertFalse(RepositoriosAbaixo.subpastaAceita("app/node_modules"));
        assertFalse(RepositoriosAbaixo.subpastaAceita("../x"));
        assertFalse(RepositoriosAbaixo.subpastaAceita("/abs"));
        assertFalse(RepositoriosAbaixo.subpastaAceita("app/"));
        assertFalse(RepositoriosAbaixo.subpastaAceita(null));
        assertFalse(RepositoriosAbaixo.subpastaAceita(".git"));
    }

    @Test
    void juntarPastaESubpastaComABarraDaPasta() {
        assertEquals("/srv/lab/projects/loja-web/app", ValidadorDeGit.juntar(RAIZ, "app"));
        assertEquals("/srv/lab/projects/loja-web/app", ValidadorDeGit.juntar(RAIZ + "/", "app"));
        assertEquals("C:\\Users\\ana\\x\\servicos\\api", ValidadorDeGit.juntar("C:\\Users\\ana\\x", "servicos/api"));
        for (String ruim : List.of("../x", "/etc", "a//b", "")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ValidadorDeGit.juntar(RAIZ, ruim), ruim);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Barra invertida na raiz e nomes que o app não mostra com segurança
    // ---------------------------------------------------------------------------------------------

    @Test
    void umaRaizLinuxComBarraInvertidaNoNomeNaoDescartaOsAchados() {
        String raiz = "/srv/lab/projects/loja\\web";
        RepositoriosAbaixo.Achados a = RepositoriosAbaixo.interpretar(raiz, raiz + "/app/.git\0" + raiz + "/api/.git\0");
        assertEquals(List.of("api", "app"), a.caminhos(), "a barra invertida de uma raiz Linux é do NOME: trocá-la por barra fazia todo achado sumir (e o projeto virar 'sem Git')");
        assertEquals(0, a.ilegiveis());
        assertEquals(List.of("app"), RepositoriosAbaixo.interpretar(raiz + "/", raiz + "/app/.git\0").caminhos());
    }

    @Test
    void caracteresBidiEDeFormatoDoUnicodeNaoPassamMasContamComoIlegiveis() {
        for (int c : new int[] {0x202E, 0x202D, 0x202A, 0x202B, 0x202C, 0x200E, 0x200F, 0x2066, 0x2067, 0x2068, 0x2069, 0x200B, 0x200D, 0xFEFF, 0x2028, 0x2029}) {
            String nome = "pasta" + new String(Character.toChars(c)) + "cod";
            assertFalse(RepositoriosAbaixo.nomeLegivel(nome), "U+" + Integer.toHexString(c));
            RepositoriosAbaixo.Achados a = RepositoriosAbaixo.interpretar(RAIZ, saida(nome + "/.git"));
            assertEquals(List.of(), a.caminhos(), "U+" + Integer.toHexString(c) + " não aparece na lista");
            assertEquals(1, a.ilegiveis(), "mas o repositório existe: " + Integer.toHexString(c));
            assertTrue(a.soIlegiveis());
            assertFalse(a.vazio());
            assertEquals(List.of("pasta com nome ilegível (1)"), a.paraAvisos());
        }
        // o que é nome de verdade continua valendo: acento, espaço, outras escritas
        for (String ok : List.of("ação", "meu app", "项目", "проект", "café-2", "app 2")) {
            assertTrue(RepositoriosAbaixo.nomeLegivel(ok), ok);
            assertEquals(List.of(ok), achar(ok + "/.git"));
        }
        // dois ilegíveis e um legível
        RepositoriosAbaixo.Achados mistos = RepositoriosAbaixo.interpretar(RAIZ, saida("a" + (char) 0x202E + "b/.git", "app/.git", "c\\d/.git", "a" + (char) 0x202E + "b/.git"));
        assertEquals(List.of("app"), mistos.caminhos());
        assertEquals(2, mistos.ilegiveis(), "a barra invertida de uma subpasta também não se mostra; a repetição conta uma vez");
        assertEquals(List.of("app", "pasta com nome ilegível (1)", "pasta com nome ilegível (2)"), mistos.paraAvisos());
        // o que o filtro estrutural tira (..: fora da raiz, pasta ignorada, fundo demais) NÃO é ilegível: é descartado de propósito
        assertEquals(0, RepositoriosAbaixo.interpretar(RAIZ, saida("../x/.git", "node_modules/a/.git", "a/b/c/.git")).ilegiveis());
        assertTrue(RepositoriosAbaixo.interpretar(RAIZ, saida("../x/.git")).vazio());
    }
}
