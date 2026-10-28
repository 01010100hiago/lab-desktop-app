package br.com.labdesktop.tendencias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.HistoricoDoAgente;
import br.com.labdesktop.api.modelo.InfoServidor;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os dados da janela, o relatório (tabela, frases, estimativa) e a exportação em CSV e texto, com valores conhecidos. */
class RelatorioEExportacaoTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Quarta-feira 28/10/2026, 12:00 em São Paulo. */
    static final Instant AGORA = Instant.parse("2026-10-28T15:00:00Z");

    static List<Double> lista(int n, java.util.function.IntFunction<Double> f) {
        List<Double> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(f.apply(i));
        }
        return l;
    }

    /** Uma semana: células de 40 min (252), disco subindo 1 ponto por dia a partir de 70%, CPU com um pico de 91% na quinta 22/10 às 14:10, e nada na primeira hora de cada tipo. */
    static HistoricoDoAgente umaSemana() {
        Duration passo = Duration.ofMinutes(40);
        Instant inicio = AGORA.minus(passo.multipliedBy(252));
        int celulaDoPico = (int) Duration.between(inicio, Instant.parse("2026-10-22T17:10:00Z")).dividedBy(passo);
        return new HistoricoDoAgente("7d", 2400, inicio, AGORA, AGORA,
            lista(252, i -> 0.10 + (i % 3) / 100d),
            lista(252, i -> i == celulaDoPico ? 0.91 : 0.30),
            lista(252, i -> 0.60),
            lista(252, i -> 0.70 + 0.01 * (i * 2400d / 86400d)),
            lista(252, i -> i % 2 == 0 ? 2.0 * 1024 * 1024 : null),
            lista(252, i -> i % 2 == 0 ? 8.0 * 1024 * 1024 : null),
            lista(252, i -> 125_000.0),
            lista(252, i -> 12_500.0));
    }

    @Test
    void daGradeDoAgenteParaAsSeisSeriesComOInstanteDeCadaCelula() {
        DadosDeTendencia d = DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, umaSemana(), AGORA);
        assertTrue(d.deLongoPrazo());
        assertEquals(Duration.ofMinutes(40), d.passo());
        assertEquals(252, d.amostras(Metrica.CPU).size());
        assertEquals(d.inicio(), d.amostras(Metrica.CPU).get(0).quando());
        assertEquals(d.inicio().plus(Duration.ofMinutes(40)), d.amostras(Metrica.CPU).get(1).quando());
        assertEquals(AGORA, d.fim());
        assertEquals(252, d.amostrasDeMaximo(Metrica.CPU).size());
        assertEquals(0, d.amostrasDeMaximo(Metrica.MEMORIA).size(), "só CPU e escrita têm máximo");
        assertTrue(d.temAlgumDado());
        assertTrue(d.temDado(Metrica.ESCRITA_NO_DISCO));
    }

    @Test
    void agenteSemPassoNemInicioUsaOPassoDaJanelaETerminaNoAgora() {
        HistoricoDoAgente h = new HistoricoDoAgente("10m", null, null, null, null, List.of(0.1, 0.2, 0.3), null, null, null, null, null, null, null);
        DadosDeTendencia d = DadosDeTendencia.deHistorico(JanelaDeTempo.DEZ_MINUTOS, h, AGORA);
        assertEquals(Duration.ofSeconds(10), d.passo());
        assertEquals(AGORA.minusSeconds(30), d.inicio());
        assertEquals(AGORA, d.fim());
        assertFalse(d.temDado(Metrica.DISCO));
    }

    @Test
    void osDezMinutosAntigosNaoTemDiscoNemEscritaMasTemOResto() {
        InfoServidor.Historico antigo = new InfoServidor.Historico(10, List.of(0.1, 0.2), List.of(0.5, 0.5), List.of(100.0, 200.0), List.of(10.0, 20.0), List.of(50.0, 51.0));
        DadosDeTendencia d = DadosDeTendencia.dosDezMinutos(antigo, AGORA);
        assertFalse(d.deLongoPrazo());
        assertEquals(JanelaDeTempo.DEZ_MINUTOS, d.janela());
        assertTrue(d.temDado(Metrica.CPU) && d.temDado(Metrica.MEMORIA) && d.temDado(Metrica.DOWNLOAD) && d.temDado(Metrica.UPLOAD));
        assertFalse(d.temDado(Metrica.DISCO));
        assertFalse(d.temDado(Metrica.ESCRITA_NO_DISCO));
        assertEquals(AGORA.minusSeconds(20), d.inicio());
        assertTrue(DadosDeTendencia.dosDezMinutos(null, AGORA).amostras(Metrica.CPU).isEmpty(), "sem historico nenhum: tudo vazio, sem quebrar");
        assertFalse(DadosDeTendencia.vazio(JanelaDeTempo.UM_DIA, AGORA).temAlgumDado());
    }

    @Test
    void aFraseDizOPicoDoDiscoEACPUComDiaDaSemanaEHora() {
        RelatorioDeTendencias r = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, umaSemana(), AGORA), SP, AGORA);
        List<String> f = r.frases();
        assertTrue(f.get(0).startsWith("Período: última semana, de 21/10 "), f.get(0));
        String cpu = f.stream().filter(x -> x.startsWith("CPU")).findFirst().orElseThrow();
        assertTrue(cpu.startsWith("CPU chegou a 91,0% na quinta às 14:"), cpu);
        String disco = f.stream().filter(x -> x.startsWith("Disco")).findFirst().orElseThrow();
        assertTrue(disco.startsWith("Disco chegou a 7") && disco.contains("% hoje às ") && disco.contains("(média de "), disco);
        String download = f.stream().filter(x -> x.startsWith("Download")).findFirst().orElseThrow();
        assertTrue(download.contains("1 Mb/s"), download);
    }

    @Test
    void quandoFoiEmTextoSimples() {
        Instant hoje = Instant.parse("2026-10-28T14:10:00Z");
        assertEquals("hoje às 11:10", RelatorioDeTendencias.quandoFoi(hoje, AGORA, SP));
        assertEquals("ontem às 14:10", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-27T17:10:00Z"), AGORA, SP));
        assertEquals("na segunda às 14:10", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-26T17:10:00Z"), AGORA, SP));
        assertEquals("na quinta às 14:10", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-22T17:10:00Z"), AGORA, SP));
        assertEquals("no domingo às 09:00", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-25T12:00:00Z"), AGORA, SP));
        assertEquals("no sábado às 09:00", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-24T12:00:00Z"), AGORA, SP));
        assertEquals("em 21/10 às 14:10", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-21T17:10:00Z"), AGORA, SP), "7 dias atrás já sai com a data");
        assertEquals("", RelatorioDeTendencias.quandoFoi(null, AGORA, SP));
        // o dia é o do fuso do usuário: 00:30 UTC de hoje ainda é "ontem" em São Paulo
        assertEquals("ontem às 21:30", RelatorioDeTendencias.quandoFoi(Instant.parse("2026-10-28T00:30:00Z"), AGORA, SP));
    }

    @Test
    void estimativaDoDiscoSoComVinteEQuatroHorasEDitaComoEstimativa() {
        RelatorioDeTendencias semana = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, umaSemana(), AGORA), SP, AGORA);
        String f = semana.fraseDaEstimativa();
        assertTrue(f.startsWith("Estimativa do disco: no ritmo atual, enche em cerca de 2"), f);
        assertTrue(f.contains("É uma estimativa"), f);
        assertEquals(EstimativaDeDisco.Tipo.ENCHENDO, semana.estimativaDoDisco().tipo());

        HistoricoDoAgente dezMin = new HistoricoDoAgente("10m", 10, AGORA.minusSeconds(600), AGORA, AGORA, lista(60, i -> 0.1), null, lista(60, i -> 0.5), lista(60, i -> 0.7), null, null, null, null);
        RelatorioDeTendencias curto = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.DEZ_MINUTOS, dezMin, AGORA), SP, AGORA);
        assertEquals("Estimativa do disco: precisa de pelo menos 24 horas de dados. Escolha 1 dia ou mais.", curto.fraseDaEstimativa());
        assertFalse(curto.estimativaDoDisco().temEstimativa());

        HistoricoDoAgente semDisco = new HistoricoDoAgente("7d", 2400, AGORA.minus(Duration.ofMinutes(40).multipliedBy(252)), AGORA, AGORA, lista(252, i -> 0.1), null, null, null, null, null, null, null);
        RelatorioDeTendencias sem = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, semDisco, AGORA), SP, AGORA);
        assertEquals("Estimativa do disco: sem dado do disco neste período.", sem.fraseDaEstimativa());
    }

    @Test
    void metricaSemDadoDizSemDadoEAgenteAntigoAvisaQueEPrecisaAtualizar() {
        InfoServidor.Historico antigo = new InfoServidor.Historico(10, lista(60, i -> 0.2), lista(60, i -> 0.5), lista(60, i -> 100.0), lista(60, i -> 10.0), List.of());
        RelatorioDeTendencias r = RelatorioDeTendencias.de(DadosDeTendencia.dosDezMinutos(antigo, AGORA), SP, AGORA);
        List<String> f = r.frases();
        assertTrue(f.contains("Disco: sem dado neste período."), f.toString());
        assertTrue(f.contains("Escrita no disco: sem dado neste período."), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.contains("versão antiga") && x.contains("Atualize o agente")), f.toString());
        assertTrue(r.linhas().get(Metrica.DISCO.ordinal()).resumo().vazio());
    }

    @Test
    void periodoSoComUmTrechoDeDadoAvisaQueOsNumerosValemParaEleSo() {
        List<Double> parcial = lista(252, i -> i >= 200 ? 0.4 : null);
        HistoricoDoAgente h = new HistoricoDoAgente("7d", 2400, AGORA.minus(Duration.ofMinutes(40).multipliedBy(252)), AGORA, AGORA, parcial, null, parcial, parcial, null, null, parcial, parcial);
        RelatorioDeTendencias r = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, h, AGORA), SP, AGORA);
        assertTrue(r.cobertura() < 0.25);
        assertTrue(r.frases().stream().anyMatch(x -> x.startsWith("Atenção: só há dado em cerca de 21% do período")), r.frases().toString());
        // tudo vazio: sem aviso de cobertura (não há trecho nenhum)
        RelatorioDeTendencias nada = RelatorioDeTendencias.de(DadosDeTendencia.vazio(JanelaDeTempo.UM_DIA, AGORA), SP, AGORA);
        assertFalse(nada.frases().stream().anyMatch(x -> x.startsWith("Atenção")));
        assertEquals(6, nada.linhas().size());
    }

    @Test
    void tabelaTemMinimoMediaMaximoP95EHoraDoPicoDeCadaMetrica() {
        RelatorioDeTendencias r = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, umaSemana(), AGORA), SP, AGORA);
        EstatisticaDeSerie.Resumo cpu = r.linhas().get(0).resumo();
        assertEquals(Metrica.CPU, r.linhas().get(0).metrica());
        assertEquals(0.10, cpu.minimo(), 1e-9);
        assertEquals(0.11, cpu.media(), 1e-9);
        assertEquals(0.91, cpu.maximo(), 1e-9, "o pico vem da série de máximos");
        assertEquals(0.12, cpu.p95(), 1e-9);
        assertEquals("91,0%", RelatorioDeTendencias.valorNaTabela(Metrica.CPU, cpu.maximo()));
        assertEquals("—", RelatorioDeTendencias.valorNaTabela(Metrica.CPU, null));
        assertEquals("1 Mb/s", RelatorioDeTendencias.valorNaTabela(Metrica.DOWNLOAD, 125_000.0));
        assertEquals("8,0 MB/s", RelatorioDeTendencias.valorNaTabela(Metrica.ESCRITA_NO_DISCO, 8.0 * 1024 * 1024));
    }

    // ---- exportação ----

    @Test
    void csvValorConhecidoComBomPontoEVirgulaEVirgulaDecimal() {
        DadosDeTendencia d = new DadosDeTendencia(JanelaDeTempo.DEZ_MINUTOS, Instant.parse("2026-10-28T15:00:00Z"), Duration.ofSeconds(10),
            java.util.Map.of(Metrica.CPU, Arrays.asList(0.123, null), Metrica.MEMORIA, Arrays.asList(0.5, 0.51), Metrica.DISCO, Arrays.asList(0.835, 0.835),
                Metrica.ESCRITA_NO_DISCO, Arrays.asList(1_572_864.0, null), Metrica.DOWNLOAD, Arrays.asList(125_000.0, 1_250_000.0), Metrica.UPLOAD, Arrays.asList(12_500.0, null)),
            java.util.Map.of(Metrica.CPU, Arrays.asList(0.5, null), Metrica.ESCRITA_NO_DISCO, Arrays.asList(2_097_152.0, null)), AGORA, true);
        String csv = ExportadorDeRelatorio.csv(d, SP);
        assertTrue(csv.startsWith(ExportadorDeRelatorio.BOM + "Data e hora;CPU (%);CPU máxima (%);Memória (%);Disco (%);Escrita no disco (MB/s);Escrita máxima (MB/s);Download (Mb/s);Upload (Mb/s)\r\n"), csv);
        String[] linhas = csv.split("\r\n");
        assertEquals(3, linhas.length);
        assertEquals("28/10/2026 12:00:00;12,3;50,0;50,0;83,5;1,500;2,000;1,000;0,100", linhas[1]);
        assertEquals("28/10/2026 12:00:10;;;51,0;83,5;;;10,000;", linhas[2], "sem dado = campo vazio, e a vírgula decimal sem separador de milhar");
        assertEquals(9, linhas[1].split(";", -1).length);
        assertFalse(linhas[1].contains("."), "nenhum ponto decimal: vírgula pt-BR");
    }

    @Test
    void csvDeSerieVaziaTemSoOCabecalhoENuncaQuebra() {
        String csv = ExportadorDeRelatorio.csv(DadosDeTendencia.vazio(JanelaDeTempo.UM_DIA, AGORA), SP);
        assertEquals(1, csv.split("\r\n").length);
        assertTrue(csv.startsWith(ExportadorDeRelatorio.BOM));
    }

    @Test
    void numeroSemNotacaoCientificaNemMilhar() {
        assertEquals("1234567,891", ExportadorDeRelatorio.numero(1234567.8912, 3));
        assertEquals("0,000", ExportadorDeRelatorio.numero(0, 3));
        assertEquals("100,0", ExportadorDeRelatorio.numero(100, 1));
        assertEquals("0,1", ExportadorDeRelatorio.numero(0.05, 1), "meio para cima");
    }

    @Test
    void textoCopiavelTemResumoETabelaAlinhada() {
        RelatorioDeTendencias r = RelatorioDeTendencias.de(DadosDeTendencia.deHistorico(JanelaDeTempo.UMA_SEMANA, umaSemana(), AGORA), SP, AGORA);
        String t = ExportadorDeRelatorio.texto(r, "lab", AGORA);
        assertTrue(t.startsWith("Relatório de tendências do servidor lab\nGerado em 28/10/2026 12:00\n"), t);
        assertTrue(t.contains("RESUMO\n- Período: última semana"), t);
        assertTrue(t.contains("CPU chegou a 91,0% na quinta às 14:"), t);
        assertTrue(t.contains("\nTABELA\nMétrica "), t);
        String[] linhas = t.split("\n");
        int cab = Arrays.asList(linhas).indexOf("TABELA") + 1;
        int colunaDoMinimo = linhas[cab].indexOf("Mínimo");
        for (int i = cab + 1; i < cab + 7; i++) {
            assertEquals(' ', linhas[i].charAt(colunaDoMinimo - 1), "as colunas alinham: " + linhas[i]);
            assertTrue(linhas[i].length() > colunaDoMinimo);
        }
        assertTrue(t.contains("Escrita no disco"));
        assertTrue(t.contains("Download e Upload em bits por segundo"));
    }

    @Test
    void nomeSugeridoSemAcentoNemEspacoNemBarra() {
        assertEquals("tendencias-lab-1-semana-20261028-1200.csv", ExportadorDeRelatorio.nomeSugerido("lab", JanelaDeTempo.UMA_SEMANA, AGORA, SP, "csv"));
        assertEquals("tendencias-este-computador-1-mes-20261028-1200.txt", ExportadorDeRelatorio.nomeSugerido("Este computador", JanelaDeTempo.UM_MES, AGORA, SP, "txt"));
        assertEquals("tendencias-servidor-da-joao-x-10-min-20261028-1200.csv", ExportadorDeRelatorio.nomeSugerido("Servidor da João/../x", JanelaDeTempo.DEZ_MINUTOS, AGORA, SP, "csv"));
        String estranho = ExportadorDeRelatorio.nomeSugerido("../../etc/passwd", JanelaDeTempo.UM_DIA, AGORA, SP, "csv");
        assertFalse(estranho.contains("/") || estranho.contains(".."), estranho);
        assertTrue(estranho.endsWith(".csv"));
        assertEquals("tendencias-1-dia-20261028-1200.csv", ExportadorDeRelatorio.nomeSugerido(null, JanelaDeTempo.UM_DIA, AGORA, SP, "csv"));
    }
}
