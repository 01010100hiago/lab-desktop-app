package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import br.com.labdesktop.ui.VerificaEdt;

/** A barra de crédito do plano: 75 % âmbar, 90 % terracota, sempre com número e texto. */
@ExtendWith(VerificaEdt.class)
class PainelDeCreditoTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    /** Limites do plano com as duas janelas em percentuais dados. */
    static LimitesConta limites(double cincoHoras, double semana, boolean disponivel, boolean antiga, Integer freio) {
        String json = "{\"disponivel\":" + disponivel + ",\"antiga\":" + antiga + ",\"lido_em\":\"2026-10-24T14:57:00Z\","
            + "\"cinco_horas\":{\"usado_pct\":" + cincoHoras + ",\"zera_em\":\"2026-10-24T17:10:00Z\"},"
            + "\"semana\":{\"usado_pct\":" + semana + ",\"zera_em\":\"2026-10-29T18:00:00Z\"},"
            + "\"freio\":{\"pct\":" + (freio == null ? "null" : freio) + ",\"ativo\":" + (freio != null) + "},"
            + "\"pausados\":[],\"por_projeto\":{}}";
        return FixturesDeApi.de(json, LimitesConta.class);
    }

    private static PainelDeCredito painel() {
        return Visual.naEdt(PainelDeCredito::new);
    }

    @Test
    void asBarrasMudamDeCorEm75E90ComONumeroAoLado() {
        Visual.tema(Tema.CLARO);
        PainelDeCredito p = painel();
        Visual.naEdt(() -> p.atualizar(limites(74.9, 75.0, true, false, null), AGORA, FUSO));
        assertEquals(Severidade.NORMAL, p.barraDeCincoHoras().severidade(), "74,9% ainda é normal");
        assertEquals(Severidade.ALTA, p.barraDaSemana().severidade(), "75,0% já é âmbar");
        assertEquals("75%", p.percentualDaSemana());

        Visual.naEdt(() -> p.atualizar(limites(89.9, 90.0, true, false, null), AGORA, FUSO));
        assertEquals(Severidade.ALTA, p.barraDeCincoHoras().severidade());
        assertEquals(Severidade.CRITICA, p.barraDaSemana().severidade(), "90,0% é terracota");
        assertEquals(0.9, p.barraDaSemana().fracao(), 1e-9);
    }

    @Test
    void semLeituraDizEmTextoENaoFingeZeroPorCento() {
        Visual.tema(Tema.CLARO);
        PainelDeCredito p = painel();
        Visual.naEdt(() -> p.atualizar(null, AGORA, FUSO));
        assertEquals("lendo…", p.textoDeSituacao());
        Visual.naEdt(() -> p.atualizar(limites(50, 50, false, false, null), AGORA, FUSO));
        assertFalse(p.mostraAsJanelas());
        assertTrue(p.textoSemLeitura().contains("Nenhuma sessão"), p.textoSemLeitura());
        Visual.naEdt(() -> p.atualizar(limites(50, 50, true, true, null), AGORA, FUSO));
        assertTrue(p.textoDeSituacao().startsWith("leitura antiga"), p.textoDeSituacao());
        assertFalse(p.mostraAsJanelas(), "leitura antiga não mostra números que podem estar velhos");
    }

    @Test
    void oFreioAparecePorExtensoEComTracoNaBarraDaSemana() {
        Visual.tema(Tema.CLARO);
        PainelDeCredito p = painel();
        Visual.naEdt(() -> p.atualizar(limites(10, 40, true, false, 90), AGORA, FUSO));
        assertTrue(p.textoDoFreio().contains("90%"), p.textoDoFreio());
        Visual.naEdt(() -> p.atualizar(limites(10, 40, true, false, null), AGORA, FUSO));
        assertEquals("Freio global desligado", p.textoDoFreio());
    }

    @Test
    void desenhaNosDoisTemas() {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            PainelDeCredito p = painel();
            Visual.naEdt(() -> p.atualizar(limites(42, 93, true, false, 90), AGORA, FUSO));
            BufferedImage img = Visual.renderizar(p, 1000, Visual.alturaPreferida(p, 1000));
            Visual.salvar(img, "painel-de-credito-" + tema.name().toLowerCase());
        }
    }
}
