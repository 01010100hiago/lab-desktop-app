package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O nome do endereço público: um nível só, rótulo de DNS, o que se monta a partir dele (valores conhecidos). */
class NomeDoEnderecoTest {

    @Test
    void rotulosQueValem() {
        for (String ok : List.of("lab-demo", "a", "meu-app-2", "123", "a".repeat(63), "x1-y2")) {
            assertTrue(NomeDoEndereco.valido(ok), ok);
            assertTrue(NomeDoEndereco.conferir(ok).valido(), ok);
        }
    }

    @Test
    void cadaErroTemUmaFraseSimples() {
        assertEquals("", NomeDoEndereco.conferir("").mensagem(), "campo vazio não é erro de texto");
        assertEquals("", NomeDoEndereco.conferir(null).mensagem());
        assertTrue(NomeDoEndereco.conferir("a.b").mensagem().startsWith("Só um nível"), NomeDoEndereco.conferir("a.b").mensagem());
        assertTrue(NomeDoEndereco.conferir("lab.demo.example.com").mensagem().startsWith("Só um nível"));
        assertTrue(NomeDoEndereco.conferir("Lab-Demo").mensagem().contains("minúsculas"));
        assertTrue(NomeDoEndereco.conferir("lab demo").mensagem().contains("Sem espaços"));
        assertTrue(NomeDoEndereco.conferir("lab-ação").mensagem().contains("Sem acentos"));
        assertTrue(NomeDoEndereco.conferir("lab_demo").mensagem().contains("Sem sublinhado"));
        assertTrue(NomeDoEndereco.conferir("-lab").mensagem().contains("começar nem terminar com hífen"));
        assertTrue(NomeDoEndereco.conferir("lab-").mensagem().contains("começar nem terminar com hífen"));
        assertTrue(NomeDoEndereco.conferir("a".repeat(64)).mensagem().contains("No máximo 63"));
        assertTrue(NomeDoEndereco.conferir("xn--abc").mensagem().contains("xn--"));
        assertTrue(NomeDoEndereco.conferir("lab/demo").mensagem().contains("Só letras minúsculas"));
        assertTrue(NomeDoEndereco.conferir("*").mensagem().contains("Só letras minúsculas"), "curinga não");
        for (String ruim : List.of("a.b", "Lab", "lab demo", "lab_demo", "-a", "a-", "a".repeat(64), "xn--a", "a/b", "*", "a;b", "a\nb")) {
            assertFalse(NomeDoEndereco.valido(ruim), ruim);
        }
    }

    @Test
    void sugestaoEPrefixoMaisProjetoCortadoParaCaber() {
        assertEquals("lab-demo", NomeDoEndereco.sugerir("lab", "demo"));
        assertEquals("lab-demo", NomeDoEndereco.sugerir(null, "demo"));
        assertEquals("site-meu-app", NomeDoEndereco.sugerir("site", "meu-app"));
        String grande = NomeDoEndereco.sugerir("lab", "a".repeat(49));
        assertTrue(NomeDoEndereco.valido(grande) && grande.length() <= 63, grande);
        assertEquals("", NomeDoEndereco.sugerir("Lab Ruim", "demo"), "prefixo que não serve: sem sugestão");
    }

    @Test
    void hostnameUrlAlvoEDestino() {
        assertEquals("lab-demo.example.com", NomeDoEndereco.hostname("lab-demo", "example.com"));
        assertEquals("https://lab-demo.example.com/", NomeDoEndereco.urlPublica("lab-demo.example.com"));
        assertEquals("11111111-2222-3333-4444-555555555555.cfargotunnel.com", NomeDoEndereco.alvoDoTunel("11111111-2222-3333-4444-555555555555"));
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.alvoDoTunel("lab"), "o alvo é sempre o UUID, nunca o nome do túnel");
        assertEquals("http://demo-app:3000", NomeDoEndereco.servicoDoTunel("demo-app", 3000));
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.hostname("a.b", "example.com"));
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.hostname("lab", "zona"));
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.servicoDoTunel("Demo App", 80));
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.servicoDoTunel("demo", 0));
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.servicoDoTunel("demo", 70000));
    }

    @Test
    void aMarcaDoAppNoComentarioVaiEVolta() {
        assertEquals("lab-desktop: demo", NomeDoEndereco.comentarioDoDns("demo"));
        assertEquals(Optional.of("demo"), NomeDoEndereco.projetoDoComentario("lab-desktop: demo"));
        assertEquals(Optional.of("meu-app"), NomeDoEndereco.projetoDoComentario("lab-desktop:meu-app"));
        assertEquals(Optional.empty(), NomeDoEndereco.projetoDoComentario("do usuário"));
        assertEquals(Optional.empty(), NomeDoEndereco.projetoDoComentario(""));
        assertEquals(Optional.empty(), NomeDoEndereco.projetoDoComentario(null));
        assertEquals(Optional.empty(), NomeDoEndereco.projetoDoComentario("lab-desktop: Nome Ruim"), "a marca sem projeto válido não vale como do app");
        assertEquals(Optional.empty(), NomeDoEndereco.projetoDoComentario("  lab-desktop: demo"), "a marca está no COMEÇO do comentário");
        assertThrows(IllegalArgumentException.class, () -> NomeDoEndereco.comentarioDoDns("Nome Ruim"));
    }
}
