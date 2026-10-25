package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** O teto de cada modo e o que ele nunca apaga. */
class EscopoTest {

    private static Escopo projeto() throws ErroSftp {
        return Escopo.projeto("/srv/lab/projects", "orbita", "/home/labuser");
    }

    @Test
    void oProjetoVivePastaDeProjetosMaisONome() throws ErroSftp {
        Escopo e = projeto();
        assertEquals("/srv/lab/projects/orbita", e.raiz());
        assertEquals("orbita", e.nome());
        assertEquals("/srv/lab/projects/orbita/src/app.js", e.validar("/srv/lab/projects/orbita/src/./app.js"));
    }

    @Test
    void ProjetoComNomeEstranhoNaoViraEscopo() {
        for (String ruim : new String[] {"..", "a/b", "", "x\ny", "."}) {
            assertThrows(ErroSftp.class, () -> Escopo.projeto("/srv/lab/projects", ruim, "/home/x"), ruim);
        }
    }

    @Test
    void doisPontosQueEscapamDaRaizSaoRecusados() throws ErroSftp {
        Escopo e = projeto();
        for (String fuga : new String[] {"/srv/lab/projects/orbita/..", "/srv/lab/projects/orbita/../outro/x",
            "/srv/lab/projects/orbita/../../../../etc/passwd", "/etc/passwd", "/srv/lab/projects/orbita2", "/", "/srv/lab/projects"}) {
            ErroSftp erro = assertThrows(ErroSftp.class, () -> e.validar(fuga), fuga);
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, erro.tipo(), fuga);
        }
        assertFalse(e.contem("/srv/lab/projects/orbita/../outro"));
        assertTrue(e.contem("/srv/lab/projects/orbita/a/../b"));
    }

    @Test
    void relativoOuComControleEhInvalidoAntesDeSerForaDaRaiz() throws ErroSftp {
        Escopo e = projeto();
        assertEquals(ErroSftp.Tipo.NOME_INVALIDO, assertThrows(ErroSftp.class, () -> e.validar("orbita/x")).tipo());
        assertEquals(ErroSftp.Tipo.NOME_INVALIDO, assertThrows(ErroSftp.class, () -> e.validar("/srv/lab/projects/orbita/a\nb")).tipo());
    }

    @Test
    void naoSeApagaARaizNemOsQueASustentam() throws ErroSftp {
        Escopo e = projeto();
        assertTrue(e.protegido("/srv/lab/projects/orbita"), "a raiz do modo");
        assertTrue(e.protegido("/srv/lab/projects"), "a raiz dos projetos");
        assertTrue(e.protegido("/srv/lab"), "ancestral da raiz dos projetos: apagar levaria os projetos junto");
        assertTrue(e.protegido("/srv"));
        assertTrue(e.protegido("/"));
        assertTrue(e.protegido("/home/labuser"), "a home");
        assertTrue(e.protegido("/home"), "ancestral da home");
        assertFalse(e.protegido("/srv/lab/projects/orbita/src"));
        assertFalse(e.protegido("/srv/lab/projects/orbita/README.md"));
        assertFalse(e.protegido("/srv/lab/projects/outro"), "outro projeto não é intocável (só não se chega nele neste modo)");
        assertFalse(e.protegido("/home/labuser/notas"), "o que está DENTRO da home pode ser apagado");
        assertTrue(e.protegido("caminho-relativo"), "caminho inválido nunca é apagável");
    }

    @Test
    void modoTudoTemAsMesmasTravas() {
        Escopo tudo = Escopo.servidor("Tudo (/)", "/", "/srv/lab/projects", "/home/labuser");
        assertTrue(tudo.ehRaizDoServidor());
        assertTrue(tudo.contem("/etc/passwd"));
        assertTrue(tudo.protegido("/"));
        assertTrue(tudo.protegido("/srv/lab/projects"));
        assertTrue(tudo.protegido("/srv/lab"));
        assertTrue(tudo.protegido("/home/labuser"));
        assertFalse(tudo.protegido("/etc/hosts"));
    }

    @Test
    void migalhasAPartirDaRaiz() throws ErroSftp {
        Escopo e = projeto();
        assertEquals(List.of(), e.trechosAPartirDaRaiz("/srv/lab/projects/orbita"));
        assertEquals(List.of("src", "app"), e.trechosAPartirDaRaiz("/srv/lab/projects/orbita/src/app"));
        assertEquals(Set.of("/srv/lab/projects/orbita", "/", "/srv/lab/projects", "/home/labuser"), e.intocaveis());
    }
}
