package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** As regras do túnel no JSON da Cloudflare: entram antes da final, o resto da configuração volta igual, e nada é feito sobre o original. */
class RegraDaApiTest {

    private static ObjectNode json(String texto) throws Exception {
        return (ObjectNode) Json.mapper().readTree(texto);
    }

    private static final String BASE = "{\"ingress\":[{\"hostname\":\"a.example.com\",\"service\":\"http://a:1\",\"originRequest\":{\"noTLSVerify\":true}},{\"service\":\"http_status:404\"}],"
        + "\"warp-routing\":{\"enabled\":false}}";

    @Test
    void entraAntesDaFinalEOrestoVoltaIgualSemTocarNoOriginal() throws Exception {
        ObjectNode original = json(BASE);
        ObjectNode copiaDoOriginal = original.deepCopy();
        RegraDaApi.Edicao e = RegraDaApi.acrescentar(original, "LAB-demo.example.com", "http://demo:3000");
        assertEquals(copiaDoOriginal, original, "o original não é alterado");
        assertNotSame(original, e.config());
        assertEquals(List.of("a.example.com -> http://a:1", "lab-demo.example.com -> http://demo:3000", "(todo o resto) -> http_status:404"), RegraDaApi.linhas(e.config()));
        assertEquals(json("{\"noTLSVerify\":true}"), e.config().path("ingress").get(0).path("originRequest"));
        assertEquals(json("{\"enabled\":false}"), e.config().path("warp-routing"));
        assertEquals(List.of(), e.avisos());
        assertEquals(false, e.jaEstava());
    }

    @Test
    void curingaOuRegraSoDeCaminhoDoDonoAntesDaFinalFazAregraNovaEntrarAntesComAviso() throws Exception {
        ObjectNode curinga = json("{\"ingress\":[{\"hostname\":\"a.example.com\",\"service\":\"http://a:1\"},{\"hostname\":\"*.example.com\",\"service\":\"http://proxy:80\"},"
            + "{\"service\":\"http_status:404\"}]}");
        RegraDaApi.Edicao e = RegraDaApi.acrescentar(curinga, "lab-demo.example.com", "http://demo:3000");
        assertEquals(List.of("a.example.com -> http://a:1", "lab-demo.example.com -> http://demo:3000", "*.example.com -> http://proxy:80", "(todo o resto) -> http_status:404"),
            RegraDaApi.linhas(e.config()));
        assertEquals(1, e.avisos().size());
        assertTrue(e.avisos().get(0).contains("*.example.com") && e.avisos().get(0).contains("ANTES"), e.avisos().get(0));
        ObjectNode caminho = json("{\"ingress\":[{\"path\":\"/api/.*\",\"service\":\"http://api:1\"},{\"service\":\"http_status:404\"}]}");
        RegraDaApi.Edicao c = RegraDaApi.acrescentar(caminho, "lab-demo.example.com", "http://demo:3000");
        assertEquals("lab-demo.example.com", c.config().path("ingress").get(0).path("hostname").asText());
        assertEquals(1, c.avisos().size());
        ObjectNode deOutro = json("{\"ingress\":[{\"hostname\":\"*.outro.com.br\",\"service\":\"http://x:1\"},{\"service\":\"http_status:404\"}]}");
        assertEquals(List.of(), RegraDaApi.acrescentar(deOutro, "lab-demo.example.com", "http://demo:3000").avisos());
    }

    @Test
    void semAFinalOuSemRegrasOuSemIngressOAppAcrescentaAFinalComAviso() throws Exception {
        RegraDaApi.Edicao sem = RegraDaApi.acrescentar(json("{\"ingress\":[{\"hostname\":\"a.example.com\",\"service\":\"http://a:1\"}]}"), "b.example.com", "http://b:2");
        assertEquals(List.of("a.example.com -> http://a:1", "b.example.com -> http://b:2", "(todo o resto) -> http_status:404"), RegraDaApi.linhas(sem.config()));
        assertTrue(sem.avisos().get(0).contains("não tinha a regra final"));
        RegraDaApi.Edicao vazia = RegraDaApi.acrescentar(json("{\"ingress\":[]}"), "b.example.com", "http://b:2");
        assertEquals(List.of("b.example.com -> http://b:2", "(todo o resto) -> http_status:404"), RegraDaApi.linhas(vazia.config()));
        assertTrue(vazia.avisos().get(0).contains("não tinha regras"));
        RegraDaApi.Edicao nada = RegraDaApi.acrescentar(json("{}"), "b.example.com", "http://b:2");
        assertEquals(2, RegraDaApi.linhas(nada.config()).size());
        assertThrows(RegraDaApi.Problema.class, () -> RegraDaApi.acrescentar(json("{\"ingress\":\"estranho\"}"), "b.example.com", "http://b:2"));
    }

    @Test
    void regraQueJaExisteEIdempotenteComOMesmoServicoEProblemaComOutro() throws Exception {
        RegraDaApi.Edicao igual = RegraDaApi.acrescentar(json(BASE), "a.example.com", "http://a:1");
        assertTrue(igual.jaEstava());
        assertEquals(json(BASE), igual.config());
        RegraDaApi.Problema p = assertThrows(RegraDaApi.Problema.class, () -> RegraDaApi.acrescentar(json(BASE), "a.example.com", "http://outro:9"));
        assertTrue(p.getMessage().contains("aponta para http://a:1"), p.getMessage());
    }

    @Test
    void removerTiraSoAquelaRegraENuncaAFinalEConfereOServico() throws Exception {
        ObjectNode duas = RegraDaApi.acrescentar(json(BASE), "b.example.com", "http://b:2").config();
        ObjectNode sem = RegraDaApi.remover(duas, "b.example.com", "http://b:2");
        assertEquals(json(BASE), sem);
        assertNull(RegraDaApi.remover(duas, "nao-tem.example.com", null), "não havia regra");
        assertNull(RegraDaApi.remover(json("{}"), "b.example.com", null));
        RegraDaApi.Problema p = assertThrows(RegraDaApi.Problema.class, () -> RegraDaApi.remover(duas, "b.example.com", "http://outro:9"));
        assertTrue(p.getMessage().contains("não para o que este app criou"), p.getMessage());
        assertNull(RegraDaApi.remover(duas, "", null), "a regra final não tem hostname: nunca casa");
        assertEquals(Optional.of("http://b:2"), RegraDaApi.servicoDe(duas, "B.example.com"));
        assertEquals(Optional.empty(), RegraDaApi.servicoDe(duas, "x.example.com"));
        assertEquals(Optional.empty(), RegraDaApi.servicoDe(null, "x.example.com"));
    }
}
