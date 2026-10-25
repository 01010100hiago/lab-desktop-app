package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A carga da página do servidor: o detalhe e a segurança são lentos e NUNCA rodam com a janela escondida. Quais abas assinam
 * cada tópico está em {@code TelaServidorTest.cadaAbaSoUsaOsSeusTopicos}.
 */
class CadenciaDosTopicosDoServidorTest {

    @Test
    void detalheDoServidorTem10sAtivoE60sSemFocoEParaEscondido() {
        Topico<?> t = Topico.DETALHE_SERVIDOR;
        assertEquals("servidor-detalhe", t.chave());
        assertEquals(Optional.of(Duration.ofSeconds(10)), t.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(Duration.ofSeconds(60)), t.intervalo(EstadoJanela.SEM_FOCO, false));
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.ESCONDIDA, false));
    }

    @Test
    void aCadenciaDoServidorNaoAumentou() {
        assertEquals(Optional.of(Duration.ofSeconds(10)), Topico.SERVIDOR.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(Duration.ofSeconds(60)), Topico.SERVIDOR.intervalo(EstadoJanela.SEM_FOCO, false));
    }

    @Test
    void segurancaTem60sAtivoESeisMinutosSemFocoEParaEscondida() {
        Topico<ResumoDeSeguranca> t = Topico.seguranca(() -> null);
        assertEquals(Optional.of(Duration.ofSeconds(60)), t.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(Duration.ofMinutes(6)), t.intervalo(EstadoJanela.SEM_FOCO, false));
        assertEquals(Optional.empty(), t.intervalo(EstadoJanela.ESCONDIDA, false));
        assertTrue(t.chave().equals("seguranca"));
    }
}
