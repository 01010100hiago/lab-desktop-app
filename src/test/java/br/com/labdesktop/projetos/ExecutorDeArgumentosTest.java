package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** O comando remoto: nenhum texto que o usuário digita vira comando, porque cada argumento tem de ser só de caracteres seguros. */
class ExecutorDeArgumentosTest {

    @Test
    void linhaRemotaSoComArgumentosSeguros() {
        assertEquals("docker network create --label com.docker.compose.network=default --label com.docker.compose.project=demo demo_default",
            ExecutorDeArgumentos.linhaRemota(ComandosDeProjeto.criarRede("demo"), Map.of()));
        UrlDeGit url = UrlDeGit.conferir("https://github.com/usuario/repo.git").url();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ALLOW_PROTOCOL", "https:ssh");
        assertEquals("env GIT_TERMINAL_PROMPT=0 GIT_ALLOW_PROTOCOL=https:ssh git clone -- https://github.com/usuario/repo.git /srv/lab/projects/demo",
            ExecutorDeArgumentos.linhaRemota(ComandosDeProjeto.clonar(url, "/srv/lab/projects/demo"), env));
        assertEquals("git clone -- git@github.com:usuario/repo.git /srv/lab/projects/demo",
            ExecutorDeArgumentos.linhaRemota(ComandosDeProjeto.clonar(UrlDeGit.conferir("git@github.com:usuario/repo.git").url(), "/srv/lab/projects/demo"), Map.of()));
    }

    @Test
    void argumentoComSimboloDeShellNuncaSai() {
        for (String ruim : List.of("a b", "a;b", "a|b", "a&b", "a$b", "a`b`", "a(b)", "a<b", "a>b", "a'b", "a\"b", "a\nb", "a\\b", "a*b", "a?b", "a!b", "a#b", "", "é", "a\u0000b")) {
            assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of("mv", ruim), Map.of()), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of("ls"), Map.of("minuscula", "x")));
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of("ls"), Map.of("A", "x y")));
        assertThrows(IllegalArgumentException.class, () -> ExecutorDeArgumentos.linhaRemota(List.of("ls"), Map.of("A", "x;y")));
    }

    @Test
    void nadaEMontadoComTextoDoDonoSemAntesPassarPelasRegras() {
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProjeto.criarRede("../x"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProjeto.criarRede("a b"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProjeto.criarRede("abc-"));
    }

    @Test
    void validarComposeLevaOCompostoPelaEntradaENuncaNaLinha() {
        List<String> c = ComandosDeProjeto.validarCompose("/srv/lab/projects/demo");
        assertEquals(List.of("docker", "compose", "--project-directory", "/srv/lab/projects/demo", "-f", "-", "config", "-q"), c);
    }

    @Test
    void comandoParaCopiarCitaOQueTemCaractereEstranho() {
        assertEquals("mv -n -- /home/ana/app /srv/lab/projects/app",
            ImportadorDeProjeto.comandoParaCopiar(ComandosDeProjeto.mover("/home/ana/app", "/srv/lab/projects/app")));
        assertEquals("mv -n -- '/home/ana/meu app' /srv/lab/projects/meu-app",
            ImportadorDeProjeto.comandoParaCopiar(ComandosDeProjeto.mover("/home/ana/meu app", "/srv/lab/projects/meu-app")));
        assertEquals("mv -n -- '/home/ana/it'\\''s' /x", ImportadorDeProjeto.comandoParaCopiar(ComandosDeProjeto.mover("/home/ana/it's", "/x")));
    }
}
