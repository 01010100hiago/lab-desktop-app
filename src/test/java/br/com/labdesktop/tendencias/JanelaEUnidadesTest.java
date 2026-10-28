package br.com.labdesktop.tendencias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os cinco períodos e as unidades (valores conhecidos): o que o usuário lê nos gráficos e no relatório. */
class JanelaEUnidadesTest {

    @Test
    void osCincoPeriodosTemOCodigoDoAgenteORotuloEOTamanhoDaGrade() {
        assertEquals(List.of("10m", "1h", "1d", "7d", "30d"), java.util.Arrays.stream(JanelaDeTempo.values()).map(JanelaDeTempo::codigo).toList());
        assertEquals(List.of("10 min", "1 hora", "1 dia", "1 semana", "1 mês"), java.util.Arrays.stream(JanelaDeTempo.values()).map(JanelaDeTempo::rotuloCurto).toList());
        assertEquals(List.of(60, 180, 288, 252, 240), java.util.Arrays.stream(JanelaDeTempo.values()).map(JanelaDeTempo::pontos).toList(), "a grade que o agente devolve");
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            assertTrue(j.pontos() <= 300, "no máximo 300 pontos por resposta: " + j);
            assertEquals(0, j.duracao().toSeconds() % j.passoDoAgente().toSeconds(), "a grade fecha exata: " + j);
        }
        assertEquals("última semana", JanelaDeTempo.UMA_SEMANA.rotuloLongo());
        assertEquals(Duration.ofDays(30), JanelaDeTempo.UM_MES.duracao());
        assertEquals(JanelaDeTempo.DEZ_MINUTOS, JanelaDeTempo.padrao());
    }

    @Test
    void aCadenciaDeConsultaCresceComOPeriodoENuncaPedeMaisRapidoQueOAgenteGrava() {
        assertEquals(Duration.ofSeconds(10), JanelaDeTempo.DEZ_MINUTOS.cadenciaDeConsulta());
        for (JanelaDeTempo j : JanelaDeTempo.values()) {
            assertTrue(j.cadenciaDeConsulta().compareTo(Duration.ofSeconds(10)) >= 0, j.name());
        }
        assertTrue(JanelaDeTempo.UM_MES.cadenciaDeConsulta().compareTo(JanelaDeTempo.UM_DIA.cadenciaDeConsulta()) > 0);
    }

    @Test
    void codigoDesconhecidoOuNuloNaoViraJanela() {
        assertEquals(JanelaDeTempo.UM_DIA, JanelaDeTempo.doCodigo("1d").orElseThrow());
        for (String ruim : new String[] {null, "", "2h", "1D", "constructor", "10m ", "30"}) {
            assertTrue(JanelaDeTempo.doCodigo(ruim).isEmpty(), String.valueOf(ruim));
        }
    }

    @Test
    void soAPartirDeTresDiasAEstimativaDoDiscoFazSentido() {
        assertFalse(JanelaDeTempo.UMA_HORA.cobreTresDiasOuMais());
        assertFalse(JanelaDeTempo.UM_DIA.cobreTresDiasOuMais());
        assertTrue(JanelaDeTempo.UMA_SEMANA.cobreTresDiasOuMais());
        assertTrue(JanelaDeTempo.UM_MES.cobreTresDiasOuMais());
    }

    @Test
    void osNomesDosSeisGraficosSaoOsExatos() {
        assertEquals(List.of("CPU", "Memória", "Disco", "Escrita no disco", "Download", "Upload"), java.util.Arrays.stream(Metrica.values()).map(Metrica::nome).toList());
        assertTrue(Metrica.CPU.temMaximo() && Metrica.ESCRITA_NO_DISCO.temMaximo());
        assertFalse(Metrica.DOWNLOAD.temMaximo());
    }

    @Test
    void porcentagemValoresConhecidos() {
        assertEquals("76%", UnidadesDeMedida.porcentagem(0.756));
        assertEquals("4,2%", UnidadesDeMedida.porcentagem(0.042), "uma casa abaixo de 10%");
        assertEquals("0%", UnidadesDeMedida.porcentagem(0.0));
        assertEquals("100%", UnidadesDeMedida.porcentagem(1.0));
        assertEquals("75,6%", UnidadesDeMedida.porcentagem(0.756, 1));
        assertEquals("—", UnidadesDeMedida.porcentagem(null));
        assertEquals("—", UnidadesDeMedida.porcentagem(Double.NaN));
        assertEquals("—", UnidadesDeMedida.porcentagem(Double.POSITIVE_INFINITY, 1));
    }

    @Test
    void downloadEUploadSaoEmBitsPorSegundoComAMesmaUnidadeNosDois() {
        assertEquals("0 b/s", UnidadesDeMedida.bitsPorSegundo(0.0));
        assertEquals("100 b/s", UnidadesDeMedida.bitsPorSegundo(12.5));
        assertEquals("12 kb/s", UnidadesDeMedida.bitsPorSegundo(1500.0), "1500 bytes/s = 12000 bits/s");
        assertEquals("1 Mb/s", UnidadesDeMedida.bitsPorSegundo(125_000.0));
        assertEquals("100 Mb/s", UnidadesDeMedida.bitsPorSegundo(12_500_000.0));
        assertEquals("1 Gb/s", UnidadesDeMedida.bitsPorSegundo(125_000_000.0));
        assertEquals("—", UnidadesDeMedida.bitsPorSegundo(null));
        assertEquals("—", UnidadesDeMedida.bitsPorSegundo(Double.NaN));
        assertEquals(Metrica.DOWNLOAD.formatar(250_000.0), Metrica.UPLOAD.formatar(250_000.0), "a mesma conta e a mesma unidade para os dois");
        assertEquals("2 Mb/s", Metrica.DOWNLOAD.formatar(250_000.0));
    }

    @Test
    void escritaNoDiscoEmBytesPorSegundoComUmaCasaDeKbParaCima() {
        assertEquals("340 B/s", UnidadesDeMedida.bytesPorSegundo(340.0));
        assertEquals("1,5 KB/s", UnidadesDeMedida.bytesPorSegundo(1536.0));
        assertEquals("1,5 MB/s", UnidadesDeMedida.bytesPorSegundo(1.5 * 1024 * 1024));
        assertEquals("0 B/s", UnidadesDeMedida.bytesPorSegundo(-5.0), "o contador nunca anda para trás: negativo vira zero");
        assertEquals("—", UnidadesDeMedida.bytesPorSegundo(null));
    }

    @Test
    void numerosPurosDaPlanilha() {
        assertEquals(1.0, UnidadesDeMedida.megabitsPorSegundo(125_000), 1e-12);
        assertEquals(1.0, UnidadesDeMedida.megabytesPorSegundo(1024 * 1024), 1e-12);
    }
}
