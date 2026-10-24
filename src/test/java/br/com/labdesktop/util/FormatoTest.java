package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Os valores esperados saíram do front web original rodando no Node 20 (ICU completo, {@code pt-BR}):
 * o front web é o gabarito (ARQUITETURA 4.2).
 */
class FormatoTest {

    private static final String T = Formato.SEM_DADO;
    private Locale original;

    @BeforeEach
    void forcarLocalidadeQueNaoEPtBr() {
        // Prova que o resultado não depende da localidade da JVM (sem jdk.localedata ela cairia em en-US).
        original = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterEach
    void restaurar() {
        Locale.setDefault(original);
    }

    @Test
    void numeroPadraoUmaCasaAbaixoDeDezEZeroDeDezEmDiante() {
        assertEquals("1.235", Formato.fmtNum(1234.5));
        assertEquals("3,1", Formato.fmtNum(3.14));
        assertEquals("3", Formato.fmtNum(3));
        assertEquals("0", Formato.fmtNum(0));
        assertEquals("-3,1", Formato.fmtNum(-3.14));
        assertEquals("0,1", Formato.fmtNum(0.05));
        assertEquals("11", Formato.fmtNum(10.5));
        assertEquals("1.000.000", Formato.fmtNum(1_000_000));
        assertEquals("1.000", Formato.fmtNum(999.5));
    }

    @Test
    void numeroArredondaMeioParaCimaPelaRepresentacaoDecimalCurta() {
        assertEquals("10,0", Formato.fmtNum(9.95)); // 9.95 é 9.9499999... em binário; o navegador também dá 10,0
        assertEquals("3", Formato.fmtNum(2.5, 0));
        assertEquals("0,13", Formato.fmtNum(0.125, 2));
    }

    @Test
    void numeroComCasasExplicitas() {
        assertEquals("1.234.567,89", Formato.fmtNum(1234567.891, 2));
        assertEquals("12.345,7", Formato.fmtNum(12345.678, 1));
        assertEquals("5,00", Formato.fmtNum(5, 2));
    }

    @Test
    void semDadoViraTravessao() {
        assertEquals(T, Formato.fmtNum(null));
        assertEquals(T, Formato.fmtNum(Double.NaN));
        assertEquals(T, Formato.fmtNum(Double.POSITIVE_INFINITY, 2));
        assertEquals(T, Formato.fmtBytes(null));
        assertEquals(T, Formato.fmtPct(null));
        assertEquals(T, Formato.fmtBps(null));
        assertEquals(T, Formato.fmtUsd(null));
        assertEquals(T, Formato.fmtCompacto(null));
        assertEquals(T, Formato.fmtUptime(null));
        assertEquals(T, Formato.fmtHa(null, Instant.now()));
        assertEquals(T, Formato.fmtHora(null));
        assertEquals(T, Formato.fmtDiaCurto(null));
    }

    @Test
    void casasForaDaFaixaSaoRecusadas() {
        assertThrows(IllegalArgumentException.class, () -> Formato.fmtNum(1, -1));
        assertThrows(IllegalArgumentException.class, () -> Formato.fmtNum(1, 11));
    }

    @Test
    void bytes() {
        assertEquals("0 B", Formato.fmtBytes(0));
        assertEquals("1.023 B", Formato.fmtBytes(1023));
        assertEquals("1 KB", Formato.fmtBytes(1024));
        assertEquals("2 KB", Formato.fmtBytes(1536));
        assertEquals("1 MB", Formato.fmtBytes(1_048_576));
        assertEquals("118 MB", Formato.fmtBytes(123_456_789));
        assertEquals("5,0 GB", Formato.fmtBytes(5L * 1024 * 1024 * 1024));
        assertEquals("1,5 TB", Formato.fmtBytes(1_649_267_441_664L));
        assertEquals("2.000,0 TB", Formato.fmtBytes(2_199_023_255_552_000L));
        assertEquals("1,5 KB", Formato.fmtBytes(1536, 1));
    }

    @Test
    void porcentagemEBytesPorSegundo() {
        assertEquals("76%", Formato.fmtPct(0.756));
        assertEquals("75,6%", Formato.fmtPct(0.756, 1));
        assertEquals("100%", Formato.fmtPct(1));
        assertEquals("0%", Formato.fmtPct(0));
        assertEquals("1%", Formato.fmtPct(0.005));
        assertEquals("123,40%", Formato.fmtPct(1.234, 2));
        assertEquals("2 KB/s", Formato.fmtBps(1536));
        assertEquals("1,5 KB/s", Formato.fmtBps(1536, 1));
    }

    @Test
    void dolarEFormaCompacta() {
        assertEquals("US$ 12,50", Formato.fmtUsd(12.5));
        assertEquals("US$ 1.234,00", Formato.fmtUsd(1234));
        assertEquals("US$ 3,142", Formato.fmtUsd(3.14159, 3));
        assertEquals("0", Formato.fmtCompacto(0));
        assertEquals("999", Formato.fmtCompacto(999));
        assertEquals("1,0 mil", Formato.fmtCompacto(1000));
        assertEquals("1,5 mil", Formato.fmtCompacto(1500));
        assertEquals("1,2 mil", Formato.fmtCompacto(1234));
        assertEquals("1,00 mi", Formato.fmtCompacto(1_000_000));
        assertEquals("2,50 mi", Formato.fmtCompacto(2_500_000));
        assertEquals("1,00 bi", Formato.fmtCompacto(1_000_000_000));
        assertEquals("-1,5 mil", Formato.fmtCompacto(-1500));
        assertEquals("-2,00 mi", Formato.fmtCompacto(-2_000_000));
        assertEquals("1.000,0 mil", Formato.fmtCompacto(999_999)); // igual ao front (sem promover para "mi")
    }

    @Test
    void tempoLigado() {
        assertEquals("2min", Formato.fmtUptime(120));
        assertEquals("0min", Formato.fmtUptime(30));
        assertEquals("1h 1min", Formato.fmtUptime(3700));
        assertEquals("1d 1h", Formato.fmtUptime(90_061));
        assertEquals("30d 0h", Formato.fmtUptime(30L * 86400));
    }

    @Test
    void haQuantoTempo() {
        Instant agora = Instant.parse("2026-10-20T12:00:00Z");
        assertEquals("agora", Formato.fmtHa(agora.minusSeconds(89), agora));
        assertEquals("agora", Formato.fmtHa(agora.plusSeconds(500), agora)); // relógio do servidor adiantado
        assertEquals("há 2 min", Formato.fmtHa(agora.minusSeconds(90), agora));
        assertEquals("há 59 min", Formato.fmtHa(agora.minusSeconds(3540), agora));
        assertEquals("há 1 h", Formato.fmtHa(agora.minusSeconds(3600), agora));
        assertEquals("há 23 h", Formato.fmtHa(agora.minusSeconds(23 * 3600), agora));
        assertEquals("há 1 d", Formato.fmtHa(agora.minusSeconds(86400), agora));
        assertEquals("há 3 d", Formato.fmtHa(agora.minusSeconds(3 * 86400 + 600), agora));
    }

    @Test
    void horaEDiaCurto() {
        assertEquals("14:05:09", Formato.fmtHora(Instant.parse("2026-10-20T17:05:09Z"), ZoneId.of("America/Sao_Paulo")));
        assertEquals("23 de set", Formato.fmtDiaCurto("2026-09-23"));
        assertEquals("05 de jan", Formato.fmtDiaCurto("2026-01-05"));
        assertEquals("31 de dez", Formato.fmtDiaCurto("2026-12-31"));
        assertEquals("01 de mai", Formato.fmtDiaCurto("2026-05-01"));
        assertEquals("ontem", Formato.fmtDiaCurto("ontem"));
    }
}
