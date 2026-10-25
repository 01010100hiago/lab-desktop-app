package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import br.com.labdesktop.api.modelo.Limite;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Os textos do teto por projeto são os do painel web (UI.limite): o mesmo texto para o mesmo dado. */
class TextosDeLimiteTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final Instant AGORA = Instant.parse("2026-10-24T15:00:00Z"); // 12:00 em São Paulo (sábado)

    private static Limite limite(Integer teto, Double usado, String estado) {
        return new Limite(teto, usado, estado, false, null, null, null);
    }

    @Test
    void usadoPertoDoTetoTruncaEmVezDeArredondarParaEncostar() {
        assertEquals("19,6%", TextosDeLimite.fmtUsado(limite(20, 19.6, "atencao")), "19,6 de 20 não pode virar 20%");
        assertEquals("19,9%", TextosDeLimite.fmtUsado(limite(20, 19.95, "atencao")));
        assertEquals("20%", TextosDeLimite.fmtUsado(limite(20, 20.0, "pausado")));
        assertEquals("22%", TextosDeLimite.fmtUsado(limite(20, 22.1, "pausado")));
        assertEquals("14%", TextosDeLimite.fmtUsado(limite(20, 14.2, "ok")));
        assertEquals("3,1%", TextosDeLimite.fmtUsado(limite(null, 3.14, "ok")), "abaixo de 10 mostra uma casa");
        assertEquals("—", TextosDeLimite.fmtUsado(limite(20, null, "ok")));
    }

    @Test
    void textoComESemTeto() {
        assertEquals("14% de 20% da semana", TextosDeLimite.texto(limite(20, 14.2, "ok")));
        assertEquals("sem limite, 3,1% da semana", TextosDeLimite.texto(limite(null, 3.14, "ok")));
        assertEquals("14% de 20%", TextosDeLimite.textoCurto(limite(20, 14.2, "ok")));
        assertEquals("", TextosDeLimite.texto(null));
    }

    @Test
    void severidadeVemDoEstadoENaoDaFracao() {
        assertEquals(Severidade.NORMAL, TextosDeLimite.severidade(limite(20, 2.0, "ok")));
        assertEquals(Severidade.ALTA, TextosDeLimite.severidade(limite(20, 15.0, "atencao")), "atenção começa em 75% do teto");
        assertEquals(Severidade.CRITICA, TextosDeLimite.severidade(limite(20, 22.0, "pausado")));
        assertEquals(Severidade.NORMAL, TextosDeLimite.severidade(null));
    }

    @Test
    void barraSoExisteComTeto() {
        assertEquals(true, TextosDeLimite.temBarra(limite(20, 1.0, "ok")));
        assertEquals(false, TextosDeLimite.temBarra(limite(null, 1.0, "ok")));
        assertEquals(false, TextosDeLimite.temBarra(limite(0, 1.0, "ok")));
        assertEquals(false, TextosDeLimite.temBarra(null));
    }

    @Test
    void rotuloDaPausaDiferenciaTetoDeFreio() {
        Limite porFreio = new Limite(20, 5.0, "pausado", false, new br.com.labdesktop.api.modelo.Pausa("freio", AGORA), null, null);
        Limite porTeto = new Limite(20, 22.0, "pausado", false, new br.com.labdesktop.api.modelo.Pausa("teto", AGORA), null, null);
        assertEquals("pausada pelo freio", TextosDeLimite.rotuloPausa(porFreio));
        assertEquals("pausada pelo limite", TextosDeLimite.rotuloPausa(porTeto));
        assertEquals(true, TextosDeLimite.pausaPorFreio(porFreio));
        assertEquals(false, TextosDeLimite.pausaPorFreio(porTeto));
    }

    @Test
    void restanteNasUnidadesDoPainel() {
        assertNull(TextosDeLimite.restante(null, AGORA));
        assertNull(TextosDeLimite.restante(AGORA.minusSeconds(5), AGORA), "já passou");
        assertEquals("menos de 1 min", TextosDeLimite.restante(AGORA.plusSeconds(20), AGORA));
        assertEquals("45 min", TextosDeLimite.restante(AGORA.plusSeconds(45 * 60), AGORA));
        assertEquals("2 h 10 min", TextosDeLimite.restante(AGORA.plusSeconds(130 * 60), AGORA));
        assertEquals("3 h", TextosDeLimite.restante(AGORA.plusSeconds(180 * 60), AGORA));
        assertEquals("5 d 3 h", TextosDeLimite.restante(AGORA.plusSeconds((5 * 24 + 3) * 3600), AGORA));
        assertEquals("2 d", TextosDeLimite.restante(AGORA.plusSeconds(48 * 3600), AGORA));
    }

    @Test
    void zeraTextoComHoraLocalEDiaQuandoNaoEHoje() {
        assertEquals("zera em 2 h 10 min (14:10)", TextosDeLimite.zeraTexto(AGORA.plusSeconds(130 * 60), AGORA, FUSO));
        // 5 d 3 h à frente: sexta-feira? 24/10/2026 é sábado; +5d3h = quinta 29/10, 15:00 local
        assertEquals("zera em 5 d 3 h (qui., 29/10 15:00)", TextosDeLimite.zeraTexto(AGORA.plusSeconds((5 * 24 + 3) * 3600), AGORA, FUSO));
        assertEquals("", TextosDeLimite.zeraTexto(AGORA.minusSeconds(60), AGORA, FUSO));
        assertEquals("", TextosDeLimite.zeraTexto(null, AGORA, FUSO));
    }

    @Test
    void aSessaoRaizTemNomeAmigavel() {
        assertEquals("sessão do lab", TextosDeLimite.nomeAmigavel("lab"));
        assertEquals("app-loja", TextosDeLimite.nomeAmigavel("app-loja"));
    }
}
