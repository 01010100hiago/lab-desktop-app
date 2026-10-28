package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.projetos.NomeDoProjeto.Resultado;
import org.junit.jupiter.api.Test;

/** A regra do nome do projeto, que é a do agente ({@code nomeValido} do agente) mais "sem hífen no fim". */
class NomeDoProjetoTest {

    private static String mensagem(String nome) {
        Resultado r = NomeDoProjeto.conferir(nome);
        assertFalse(r.valido(), "devia recusar: " + nome);
        return r.mensagem();
    }

    @Test
    void nomesQueValem() {
        for (String nome : new String[] {"a", "0", "meu-projeto", "app2", "2app", "a-b-c", "x".repeat(49), "9".repeat(49), "loja-do-ze"}) {
            assertTrue(NomeDoProjeto.valido(nome), nome);
            assertEquals(new Resultado(true, ""), NomeDoProjeto.conferir(nome), nome);
        }
    }

    @Test
    void tamanhoUmValeESessentaETresNaoVale() {
        assertTrue(NomeDoProjeto.valido("a"));
        assertTrue(NomeDoProjeto.valido("a".repeat(49)), "o agente aceita até 49");
        assertFalse(NomeDoProjeto.valido("a".repeat(50)));
        assertFalse(NomeDoProjeto.valido("a".repeat(63)), "o agente de verdade corta o nome em 49 caracteres");
        assertFalse(NomeDoProjeto.valido("a".repeat(64)));
        assertEquals("No máximo 49 caracteres (este tem 64).", mensagem("a".repeat(64)));
        assertEquals("No máximo 49 caracteres (este tem 50).", mensagem("a".repeat(50)));
    }

    @Test
    void vazioNaoEErroDeTexto() {
        assertEquals(new Resultado(false, ""), NomeDoProjeto.conferir(""));
        assertEquals(new Resultado(false, ""), NomeDoProjeto.conferir(null));
        assertFalse(NomeDoProjeto.valido(""));
        assertFalse(NomeDoProjeto.valido(null));
    }

    @Test
    void maiusculasAcentosEEspacosTemFraseSimples() {
        assertEquals("Use só letras minúsculas (sem maiúsculas).", mensagem("MeuProjeto"));
        assertEquals("Use só letras minúsculas (sem maiúsculas).", mensagem("Ab"));
        assertTrue(mensagem("ação").startsWith("Sem acentos"));
        assertTrue(mensagem("café-bar").startsWith("Sem acentos"));
        assertTrue(mensagem("meu projeto").startsWith("Sem espaços"));
        assertTrue(mensagem(" meu").startsWith("Sem espaços"));
        assertTrue(mensagem("meu ").startsWith("Sem espaços"));
        assertTrue(mensagem("meu\tprojeto").startsWith("Sem espaços"));
        assertTrue(mensagem("meu projeto").startsWith("Sem espaços"), "espaço sem quebra");
    }

    @Test
    void hifenNasPontas() {
        assertEquals("O nome não pode começar com hífen.", mensagem("-abc"));
        assertEquals("O nome não pode começar com hífen.", mensagem("-"));
        assertEquals("O nome não pode terminar com hífen.", mensagem("abc-"));
        assertFalse(NomeDoProjeto.valido("abc-"), "o agente aceitaria; o app é um pouco mais estrito e nunca mais largo");
        assertTrue(br.com.labdesktop.seguranca.ValidadorDeNomes.projetoValido("abc-"), "(confere o que o agente aceita)");
        assertFalse(NomeDoProjeto.valido("--"));
    }

    @Test
    void nomesParecidosComCaminho() {
        for (String ruim : new String[] {"..", ".", "../etc", "a/b", "/abs", "a\\b", "a..b", ".oculto", "a.b", "~", "a;b", "a$(x)", "a b;ls", "a\nb", "a\u0000b"}) {
            assertFalse(NomeDoProjeto.valido(ruim), ruim);
            assertFalse(NomeDoProjeto.conferir(ruim).valido(), ruim);
            assertFalse(NomeDoProjeto.conferir(ruim).mensagem().isEmpty(), ruim);
        }
        assertTrue(mensagem("../etc").startsWith("Só letras minúsculas, números e hífen"));
        assertTrue(mensagem("a/b").startsWith("Só letras minúsculas, números e hífen"));
    }

    @Test
    void reservadosSaoOsDoAgente() {
        assertTrue(NomeDoProjeto.reservado("infra"));
        assertTrue(NomeDoProjeto.reservado("painel"));
        assertTrue(NomeDoProjeto.reservado("lab"));
        assertFalse(NomeDoProjeto.reservado("meu-lab"));
        assertFalse(NomeDoProjeto.reservado(null));
    }

    @Test
    void sugestaoDeNomeAPartirDeTexto() {
        assertEquals("meu-repo", NomeDoProjeto.sugerirDe("Meu_Repo"));
        assertEquals("cafe-da-manha", NomeDoProjeto.sugerirDe("Café da Manhã"));
        assertEquals("a-b", NomeDoProjeto.sugerirDe("--a..b--"));
        assertEquals("", NomeDoProjeto.sugerirDe("___"));
        assertEquals("", NomeDoProjeto.sugerirDe(null));
        assertEquals("x".repeat(49), NomeDoProjeto.sugerirDe("x".repeat(80)));
        String comHifenNoCorte = NomeDoProjeto.sugerirDe("a".repeat(48) + "-bbbb");
        assertTrue(NomeDoProjeto.valido(comHifenNoCorte), comHifenNoCorte);
        assertEquals("api-v2", NomeDoProjeto.sugerirDe("API.v2"));
    }
}
