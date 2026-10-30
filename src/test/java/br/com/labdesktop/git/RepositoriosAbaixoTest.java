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
}
