package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Os formatadores pt-BR da tela do servidor: vírgula decimal, unidades e o traço do dado ausente. */
class FormatosDoServidorTest {

    @Test
    void bytesEmBKbMbGbTbComVirgulaDecimal() {
        assertEquals("0 B", FormatosDoServidor.bytes(0));
        assertEquals("716 B", FormatosDoServidor.bytes(716));
        assertEquals("1.023 B", FormatosDoServidor.bytes(1023));
        assertEquals("1 KB", FormatosDoServidor.bytes(1024));
        assertEquals("15 KB", FormatosDoServidor.bytes(15 * 1024));
        assertEquals("245 MB", FormatosDoServidor.bytes(245L * 1024 * 1024));
        assertEquals("2,4 GB", FormatosDoServidor.bytes((long) (2.4 * 1024 * 1024 * 1024)));
        assertEquals("1,5 TB", FormatosDoServidor.bytes((long) (1.5 * 1024L * 1024 * 1024 * 1024)));
        assertEquals("—", FormatosDoServidor.bytes(null));
    }

    @Test
    void bitsPorSegundoVemDeBytesPorSegundo() {
        assertEquals("0 b/s", FormatosDoServidor.bitsPorSegundo(0));
        assertEquals("80 b/s", FormatosDoServidor.bitsPorSegundo(10));
        assertEquals("5,7 kb/s", FormatosDoServidor.bitsPorSegundo(716), "716 bytes/s são 5.728 bits/s");
        assertEquals("1 kb/s", FormatosDoServidor.bitsPorSegundo(125));
        assertEquals("100 kb/s", FormatosDoServidor.bitsPorSegundo(12_500));
        assertEquals("1 Mb/s", FormatosDoServidor.bitsPorSegundo(125_000));
        assertEquals("2,6 Mb/s", FormatosDoServidor.bitsPorSegundo(326_385));
        assertEquals("940 Mb/s", FormatosDoServidor.bitsPorSegundo(117_500_000));
        assertEquals("1 Gb/s", FormatosDoServidor.bitsPorSegundo(125_000_000));
        assertEquals("—", FormatosDoServidor.bitsPorSegundo(null));
        assertEquals("—", FormatosDoServidor.bitsPorSegundo(Double.NaN));
    }

    @Test
    void tempoLigadoPorExtenso() {
        assertEquals("menos de 1 min", FormatosDoServidor.tempoLigado(0));
        assertEquals("menos de 1 min", FormatosDoServidor.tempoLigado(59.9));
        assertEquals("1 min", FormatosDoServidor.tempoLigado(60));
        assertEquals("12 min", FormatosDoServidor.tempoLigado(12 * 60 + 5));
        assertEquals("1 h", FormatosDoServidor.tempoLigado(3600));
        assertEquals("3 h 57 min", FormatosDoServidor.tempoLigado(3 * 3600 + 57 * 60 + 12));
        assertEquals("1 d", FormatosDoServidor.tempoLigado(86_400));
        assertEquals("5 d 7 h", FormatosDoServidor.tempoLigado(5 * 86_400 + 7 * 3600 + 1800));
        assertEquals("—", FormatosDoServidor.tempoLigado(null));
        assertEquals("—", FormatosDoServidor.tempoLigado(-5));
    }

    @Test
    void porcentagemDeFracaoEDePercentual() {
        assertEquals("0%", FormatosDoServidor.porcentagem(0));
        assertEquals("8,3%", FormatosDoServidor.porcentagem(0.0831));
        assertEquals("42%", FormatosDoServidor.porcentagem(0.42));
        assertEquals("100%", FormatosDoServidor.porcentagem(1.0));
        assertEquals("—", FormatosDoServidor.porcentagem(null));
        assertEquals("5,2%", FormatosDoServidor.percentual(5.18));
        assertEquals("120%", FormatosDoServidor.percentual(120.0));
        assertEquals("—", FormatosDoServidor.percentual(null), "cpu null na primeira varredura");
    }

    @Test
    void temperaturaCargaEInteiro() {
        assertEquals("62 °C", FormatosDoServidor.temperatura(62.0));
        assertEquals("87 °C", FormatosDoServidor.temperatura(86.6));
        assertEquals("—", FormatosDoServidor.temperatura(null));
        assertEquals("0,52", FormatosDoServidor.carga(0.52));
        assertEquals("1,35", FormatosDoServidor.carga(1.349));
        assertEquals("1.318", FormatosDoServidor.inteiro(1318));
    }

    @Test
    void versaoSoONumero() {
        assertEquals("2.1.303", FormatosDoServidor.versao("2.1.303 (Claude Code)"));
        assertEquals("29.9.1", FormatosDoServidor.versao("Docker version 29.9.1, build dfc520d"));
        assertEquals("5.6.0", FormatosDoServidor.versao("Docker Compose version v5.6.0"));
        assertEquals("22.23.3", FormatosDoServidor.versao("v22.23.3"));
        assertEquals("12.15.0", FormatosDoServidor.versao("12.15.0"));
        assertEquals("estranha", FormatosDoServidor.versao(" estranha "));
        assertEquals("—", FormatosDoServidor.versao(null));
        assertEquals("—", FormatosDoServidor.versao(" "));
    }

    @Test
    void horasEDatasNoFusoPedido() {
        ZoneId sp = ZoneId.of("America/Sao_Paulo");
        Instant i = Instant.parse("2026-10-20T02:53:40Z");
        assertEquals("23:53", FormatosDoServidor.horaMinuto(i, sp));
        assertEquals("19/10 23:53", FormatosDoServidor.diaEHora(i, sp));
        assertEquals("19/10 23:53 (há 4 d)", FormatosDoServidor.desde(i, Instant.parse("2026-10-24T05:00:00Z"), sp));
        assertEquals("—", FormatosDoServidor.horaMinuto(null, sp));
        assertEquals("—", FormatosDoServidor.desde(null, i, sp));
    }
}
