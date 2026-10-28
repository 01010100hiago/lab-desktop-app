package br.com.labdesktop.tendencias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.tendencias.EstimativaDeDisco.Tipo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Estatística e estimativa do disco: valores conhecidos, série vazia, um ponto, {@code null}, NaN e relógio fora de ordem. */
class EstatisticaEEstimativaTest {

    static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    static List<Amostra> serie(Duration passo, Double... valores) {
        List<Amostra> l = new ArrayList<>();
        for (int i = 0; i < valores.length; i++) {
            l.add(new Amostra(T0.plus(passo.multipliedBy(i)), valores[i]));
        }
        return l;
    }

    // ---- estatística ----

    @Test
    void valoresConhecidosDeMinimoMediaMaximoP95EHoraDoPico() {
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(serie(Duration.ofMinutes(1), 10.0, 20.0, 30.0, 40.0, 100.0));
        assertEquals(5, r.validos());
        assertEquals(10.0, r.minimo());
        assertEquals(40.0, r.media());
        assertEquals(100.0, r.maximo());
        assertEquals(100.0, r.p95(), "5 valores: posto ceil(0,95 x 5) = 5");
        assertEquals(T0.plus(Duration.ofMinutes(4)), r.instanteDoPico());
    }

    @Test
    void percentil95PeloPostoMaisProximo() {
        Double[] de1a20 = new Double[20];
        Double[] de1a100 = new Double[100];
        for (int i = 0; i < 100; i++) {
            de1a100[i] = i + 1.0;
            if (i < 20) {
                de1a20[i] = i + 1.0;
            }
        }
        assertEquals(19.0, EstatisticaDeSerie.de(serie(Duration.ofSeconds(10), de1a20)).p95(), "20 valores: o 19º");
        assertEquals(95.0, EstatisticaDeSerie.de(serie(Duration.ofSeconds(10), de1a100)).p95(), "100 valores: o 95º");
    }

    @Test
    void serieVaziaOuToda_nulaNaoTemNada() {
        assertTrue(EstatisticaDeSerie.de(List.of()).vazio());
        assertTrue(EstatisticaDeSerie.de(null).vazio());
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(serie(Duration.ofSeconds(10), null, null, Double.NaN, Double.POSITIVE_INFINITY));
        assertTrue(r.vazio());
        assertNull(r.minimo());
        assertNull(r.media());
        assertNull(r.maximo());
        assertNull(r.p95());
        assertNull(r.instanteDoPico());
    }

    @Test
    void umPontoSoEMinimoMediaMaximoEP95AoMesmoTempo() {
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(serie(Duration.ofSeconds(10), 7.5));
        assertEquals(1, r.validos());
        assertEquals(7.5, r.minimo());
        assertEquals(7.5, r.media());
        assertEquals(7.5, r.maximo());
        assertEquals(7.5, r.p95());
        assertEquals(T0, r.instanteDoPico());
    }

    @Test
    void nuloNoMeioNaoEntraNaMediaNemViraZero() {
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(serie(Duration.ofSeconds(10), 10.0, null, 30.0, Double.NaN));
        assertEquals(2, r.validos());
        assertEquals(20.0, r.media(), "(10 + 30) / 2, e não / 4");
        assertEquals(10.0, r.minimo(), "o nulo não é zero");
        assertEquals(T0.plusSeconds(20), r.instanteDoPico());
    }

    @Test
    void relogioForaDeOrdemEOrdenadoEAHoraDoPicoContinuaCerta() {
        List<Amostra> embaralhada = new ArrayList<>(serie(Duration.ofMinutes(1), 1.0, 50.0, 3.0, 4.0));
        Collections.reverse(embaralhada);
        Collections.swap(embaralhada, 0, 2);
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(embaralhada);
        assertEquals(50.0, r.maximo());
        assertEquals(T0.plus(Duration.ofMinutes(1)), r.instanteDoPico(), "o pico é do instante dele, não da posição na lista");
        assertEquals(14.5, r.media());
    }

    @Test
    void empateDePicoFicaComOMaisRecente() {
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(serie(Duration.ofMinutes(1), 9.0, 3.0, 9.0, 1.0));
        assertEquals(T0.plus(Duration.ofMinutes(2)), r.instanteDoPico());
    }

    @Test
    void oMaximoGuardadoPeloAgenteMandaNoPicoEAMediaFicaComAsMedias() {
        List<Amostra> medias = serie(Duration.ofHours(3), 0.2, 0.3, 0.25);
        List<Amostra> maximos = serie(Duration.ofHours(3), 0.5, 0.97, 0.6);
        EstatisticaDeSerie.Resumo r = EstatisticaDeSerie.de(medias, maximos);
        assertEquals(0.97, r.maximo(), "a média de 3 horas esconde o pico; o máximo guardado não");
        assertEquals(T0.plusSeconds(3 * 3600), r.instanteDoPico());
        assertEquals(0.25, r.media(), 1e-12);
        assertEquals(0.2, r.minimo());
        // máximos todos nulos: volta a olhar a própria série
        assertEquals(0.3, EstatisticaDeSerie.de(medias, serie(Duration.ofHours(3), null, null, null)).maximo());
        assertEquals(0.3, EstatisticaDeSerie.de(medias, List.of()).maximo());
    }

    // ---- estimativa do disco ----

    private static Double[] rampa(int pontos, double inicio, double porDia, double passoEmDias) {
        Double[] v = new Double[pontos];
        for (int i = 0; i < pontos; i++) {
            v[i] = inicio + porDia * passoEmDias * i;
        }
        return v;
    }

    @Test
    void discoSubindoDoisPontosPercentuaisPorDiaEncheEmDezenoveDias() {
        // 7 dias de pontos de hora em hora: 50% e +2 pontos por dia -> no fim 64%; faltam 36 pontos = 18 dias
        List<Amostra> uso = serie(Duration.ofHours(1), rampa(7 * 24, 0.50, 0.02, 1 / 24d));
        EstimativaDeDisco.Resultado r = EstimativaDeDisco.de(uso, Duration.ofHours(1));
        assertEquals(Tipo.ENCHENDO, r.tipo());
        assertEquals(0.02, r.porDia(), 1e-9);
        assertEquals(0.64 - 1 / 24d * 0.02, r.nivelAgora(), 1e-6);
        assertEquals((1 - r.nivelAgora()) / 0.02, r.diasParaEncher(), 1e-6);
        assertEquals(18, Math.round(r.diasParaEncher()), "cerca de 18 dias");
        assertEquals(Duration.ofDays(7), r.cobertura());
        assertTrue(r.temEstimativa());
    }

    @Test
    void menosDeVinteEQuatroHorasOuMenosDeDozePontosNaoEstima() {
        EstimativaDeDisco.Resultado vinteETresHoras = EstimativaDeDisco.de(serie(Duration.ofMinutes(10), rampa(138, 0.5, 0.01, 1 / 144d)), Duration.ofMinutes(10));
        assertEquals(Tipo.POUCO_DADO, vinteETresHoras.tipo(), "138 x 10 min = 23 h");
        assertNull(vinteETresHoras.diasParaEncher());
        assertEquals(Tipo.POUCO_DADO, EstimativaDeDisco.de(serie(Duration.ofDays(1), 0.5, 0.6, 0.7), Duration.ofDays(1)).tipo(), "3 pontos: poucos para uma reta");
        EstimativaDeDisco.Resultado exato = EstimativaDeDisco.de(serie(Duration.ofMinutes(10), rampa(144, 0.5, 0.01, 1 / 144d)), Duration.ofMinutes(10));
        assertEquals(Tipo.ENCHENDO, exato.tipo(), "144 x 10 min = 24 h cheias já estima (a cobertura conta até o fim da última célula)");
    }

    @Test
    void serieVaziaUmPontoENulosNaoEstimamENaoQuebram() {
        assertEquals(Tipo.POUCO_DADO, EstimativaDeDisco.de(List.of(), Duration.ofMinutes(5)).tipo());
        assertEquals(Tipo.POUCO_DADO, EstimativaDeDisco.de(null, Duration.ofMinutes(5)).tipo());
        assertEquals(Tipo.POUCO_DADO, EstimativaDeDisco.de(serie(Duration.ofMinutes(5), 0.7), Duration.ofMinutes(5)).tipo());
        assertEquals(Tipo.POUCO_DADO, EstimativaDeDisco.de(serie(Duration.ofMinutes(5), new Double[300]), Duration.ofMinutes(5)).tipo());
        assertEquals(Tipo.ESTAVEL, EstimativaDeDisco.de(serie(Duration.ofHours(1), rampa(48, 0.5, 0.0, 1)), null).tipo(), "passo nulo conta como zero, sem quebrar");
    }

    @Test
    void discoParadoEEstavelEDiscoCaindoEDiminuindo() {
        assertEquals(Tipo.ESTAVEL, EstimativaDeDisco.de(serie(Duration.ofHours(1), rampa(72, 0.61, 0.0, 1)), Duration.ofHours(1)).tipo());
        // barulho em volta de 61% (sobe e desce de hora em hora): sem tendência
        Double[] barulho = new Double[72];
        for (int i = 0; i < 72; i++) {
            barulho[i] = 0.61 + (i % 2 == 0 ? 0.003 : -0.003);
        }
        assertEquals(Tipo.ESTAVEL, EstimativaDeDisco.de(serie(Duration.ofHours(1), barulho), Duration.ofHours(1)).tipo());
        EstimativaDeDisco.Resultado cai = EstimativaDeDisco.de(serie(Duration.ofHours(1), rampa(72, 0.70, -0.02, 1 / 24d)), Duration.ofHours(1));
        assertEquals(Tipo.DIMINUINDO, cai.tipo());
        assertNull(cai.diasParaEncher());
    }

    @Test
    void muitoDevagarEMaisDeUmAnoEPertoDeCheioEAvisa() {
        // 0,1 ponto percentual por dia, em 20%: 800 dias
        EstimativaDeDisco.Resultado longe = EstimativaDeDisco.de(serie(Duration.ofHours(1), rampa(72, 0.20, 0.001, 1 / 24d)), Duration.ofHours(1));
        assertEquals(Tipo.ENCHENDO_LONGE, longe.tipo());
        assertTrue(longe.diasParaEncher() > 365);
        EstimativaDeDisco.Resultado cheio = EstimativaDeDisco.de(serie(Duration.ofHours(1), rampa(72, 0.99, 0.005, 1 / 24d)), Duration.ofHours(1));
        assertEquals(Tipo.CHEIO, cheio.tipo());
        assertEquals(0.0, cheio.diasParaEncher());
    }

    @Test
    void relogioForaDeOrdemENuloNoMeioNaoMudamAReta() {
        List<Amostra> certa = serie(Duration.ofHours(1), rampa(72, 0.50, 0.02, 1 / 24d));
        List<Amostra> embaralhada = new ArrayList<>(certa);
        Collections.shuffle(embaralhada, new java.util.Random(7));
        embaralhada.add(new Amostra(T0.plusSeconds(999), null));
        embaralhada.add(new Amostra(null, 0.9));
        embaralhada.add(null);
        EstimativaDeDisco.Resultado a = EstimativaDeDisco.de(certa, Duration.ofHours(1));
        EstimativaDeDisco.Resultado b = EstimativaDeDisco.de(embaralhada, Duration.ofHours(1));
        assertEquals(a.porDia(), b.porDia(), 1e-12);
        assertEquals(a.diasParaEncher(), b.diasParaEncher(), 1e-9);
        assertNotNull(b.cobertura());
    }

    @Test
    void todosNoMesmoInstanteNaoTemRetaENaoDividePorZero() {
        List<Amostra> tudoJunto = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tudoJunto.add(new Amostra(T0, 0.5 + i / 1000d));
        }
        assertEquals(Tipo.POUCO_DADO, EstimativaDeDisco.de(tudoJunto, Duration.ofDays(2)).tipo());
    }
}
