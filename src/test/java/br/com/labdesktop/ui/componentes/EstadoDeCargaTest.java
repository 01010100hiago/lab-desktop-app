package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.componentes.EstadoDeCarga.Fase;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** O modelo puro do "carregando": as transições e o prazo, com valores conhecidos (sem relógio, sem Swing). */
class EstadoDeCargaTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    private static final Duration PRAZO = Duration.ofSeconds(15);

    private static EstadoDeCarga novo() {
        return EstadoDeCarga.carregando(T0, PRAZO);
    }

    @Test
    void comecaCarregandoComOIndicadorAFrenteESemSaida() {
        EstadoDeCarga e = novo();
        assertEquals(Fase.CARREGANDO, e.fase());
        assertTrue(e.mostraIndicador());
        assertFalse(e.oferecerTentarDeNovo());
    }

    @Test
    void aFrasePadraoDizCarregandoEOAssunto() {
        assertEquals("Carregando projetos...", EstadoDeCarga.frase("projetos"));
        assertEquals("Carregando a pasta...", EstadoDeCarga.frase("a pasta"));
    }

    @Test
    void antesDoPrazoContinuaCarregandoEExatamenteNoPrazoViraDemorou() {
        assertSame(novo().fase(), novo().passou(T0.plusSeconds(14)).fase());
        assertEquals(Fase.CARREGANDO, novo().passou(T0.plusMillis(14_999)).fase());
        EstadoDeCarga e = novo().passou(T0.plusSeconds(15));
        assertEquals(Fase.DEMOROU, e.fase());
        assertFalse(e.mostraIndicador(), "demorou: o girando some, entra a frase de que demorou");
        assertTrue(e.oferecerTentarDeNovo());
        assertEquals("Demorou mais que o normal.", EstadoDeCarga.FRASE_DEMOROU);
        assertEquals("Tentar de novo", EstadoDeCarga.BOTAO_TENTAR_DE_NOVO);
    }

    @Test
    void passarOTempoSemMudarDevolveOMesmoObjeto() {
        EstadoDeCarga e = novo();
        assertSame(e, e.passou(T0.plusSeconds(1)));
        EstadoDeCarga d = e.passou(T0.plusSeconds(20));
        assertSame(d, d.passou(T0.plusSeconds(60)), "já demorou: nada a mudar");
    }

    @Test
    void oDadoQueChegaDeixaProntoDeQualquerFase() {
        assertEquals(Fase.PRONTO, novo().chegou().fase());
        assertEquals(Fase.PRONTO, novo().passou(T0.plusSeconds(30)).chegou().fase(), "uma resposta atrasada ainda vale");
        assertEquals(Fase.PRONTO, novo().falhou("erro").chegou().fase());
        assertFalse(novo().chegou().mostraIndicador());
        EstadoDeCarga pronto = novo().chegou();
        assertSame(pronto, pronto.chegou());
    }

    @Test
    void aFalhaViraErroComAFraseEComSaida() {
        EstadoDeCarga e = novo().falhou("O servidor recusou.");
        assertEquals(Fase.ERRO, e.fase());
        assertEquals("O servidor recusou.", e.mensagem());
        assertTrue(e.oferecerTentarDeNovo());
        assertEquals(Fase.ERRO, novo().passou(T0.plusSeconds(60)).falhou("x").fase(), "falhar depois de demorar também é erro");
    }

    @Test
    void comODadoNaTelaUmaFalhaNaoMudaNada() {
        EstadoDeCarga pronto = novo().chegou();
        assertSame(pronto, pronto.falhou("a consulta seguinte caiu"), "a tela segue com a última leitura");
    }

    @Test
    void tentarDeNovoRecomecaOPrazoDoDemorouEDoErro() {
        Instant depois = T0.plusSeconds(40);
        EstadoDeCarga d = novo().passou(T0.plusSeconds(20)).tentarDeNovo(depois);
        assertEquals(Fase.CARREGANDO, d.fase());
        assertEquals(depois, d.inicio());
        assertEquals(Fase.CARREGANDO, d.passou(depois.plusSeconds(14)).fase(), "o prazo conta de novo a partir de agora");
        assertEquals(Fase.DEMOROU, d.passou(depois.plusSeconds(15)).fase());
        assertEquals(Fase.CARREGANDO, novo().falhou("x").tentarDeNovo(depois).fase());
        assertEquals("", novo().falhou("x").tentarDeNovo(depois).mensagem(), "a mensagem do erro some junto");
    }

    @Test
    void tentarDeNovoNaoMexeEmQuemJaEstaCarregandoNemEmQuemEstaPronto() {
        EstadoDeCarga e = novo();
        assertSame(e, e.tentarDeNovo(T0.plusSeconds(5)), "carregando: o prazo não recomeça a cada clique");
        EstadoDeCarga p = novo().chegou();
        assertSame(p, p.tentarDeNovo(T0.plusSeconds(5)));
    }

    @Test
    void prazoInvalidoViraOPadraoDeQuinzeSegundos() {
        assertEquals(Duration.ofSeconds(15), EstadoDeCarga.carregando(T0, null).prazo());
        assertEquals(Duration.ofSeconds(15), EstadoDeCarga.carregando(T0, Duration.ZERO).prazo());
        assertEquals(Duration.ofSeconds(15), EstadoDeCarga.carregando(T0, Duration.ofSeconds(-3)).prazo());
        assertEquals(Duration.ofSeconds(15), EstadoDeCarga.PRAZO_PADRAO);
    }
}
