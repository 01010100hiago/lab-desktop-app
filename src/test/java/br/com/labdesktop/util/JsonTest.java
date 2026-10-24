package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonTest {

    record Amostra(@JsonProperty("nome") String nome, @JsonProperty("quando") Instant quando) { }

    @Test
    void toleraCampoDesconhecidoEAusente() throws Exception {
        Amostra a = Json.mapper().readValue("{\"nome\":\"x\",\"novoCampo\":123}", Amostra.class);
        assertEquals("x", a.nome());
        assertNull(a.quando());
    }

    @Test
    void instantViraTextoIso() throws Exception {
        String json = Json.mapper().writeValueAsString(new Amostra("x", Instant.parse("2026-10-20T13:00:00Z")));
        assertTrue(json.contains("\"2026-10-20T13:00:00Z\""), json);
        assertEquals(Instant.parse("2026-10-20T13:00:00Z"), Json.mapper().readValue(json, Amostra.class).quando());
    }

    @Test
    void recusaStringMaiorQueOTeto() {
        String enorme = "a".repeat(Json.TETO_STRING + 10);
        assertThrows(JsonProcessingException.class,
            () -> Json.mapper().readValue("{\"nome\":\"" + enorme + "\"}", Amostra.class));
    }

    @Test
    void recusaAninhamentoAbsurdo() {
        String json = "[".repeat(200) + "]".repeat(200);
        assertThrows(JsonProcessingException.class, () -> Json.mapper().readTree(json));
    }

    @Test
    void naoUsaDefaultTyping() throws Exception {
        // Com "default typing" ligado, este JSON viraria uma ArrayList vazia (o primeiro elemento é o nome da
        // classe). Sem ele, é só uma lista com um texto e uma lista: nada é instanciado por nome.
        Object lido = Json.mapper().readValue("[\"java.util.ArrayList\",[]]", Object.class);
        assertEquals(List.of("java.util.ArrayList", List.of()), lido);
        // E um campo "@class" num objeto de tipo fixo é só um campo desconhecido, ignorado.
        assertEquals("x", Json.mapper()
            .readValue("{\"nome\":\"x\",\"@class\":\"java.lang.ProcessBuilder\"}", Amostra.class).nome());
    }
}
