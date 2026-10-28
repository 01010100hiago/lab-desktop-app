package br.com.labdesktop.tendencias;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * O relatório fora do app: CSV para planilha e texto para copiar. PURO.
 *
 * <p><b>Formato do CSV :</b> UTF-8 COM marca BOM (o Excel e o LibreOffice abrem com os acentos certos), separador {@code ;}, vírgula decimal (o jeito pt-BR:
 * abre direto nas colunas do Excel brasileiro, sem assistente), fim de linha {@code \r\n}, data e hora {@code dd/MM/aaaa HH:mm:ss} no fuso do computador do usuário, uma linha por célula da
 * grade (célula sem dado = campo vazio; a primeira coluna é o INÍCIO do intervalo e o título diz o fuso). As unidades vão no título da coluna e os números saem SEM unidade e sem separador de milhar: porcentagem de 0 a 100 (uma casa), B/s inteiro
 * para a escrita e kb/s para Download e Upload (três casas, quilobit de 1.000): unidades pequenas, para um tráfego baixo não virar 0,000. Campo com {@code ;}, aspas ou quebra de linha vai entre aspas (não há nenhum
 * hoje: os campos são números, datas e títulos fixos).
 */
public final class ExportadorDeRelatorio {

    private static final DateTimeFormatter DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter DATA_DO_NOME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter DATA_E_HORA_CURTA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** A marca de ordem de bytes que faz o Excel reconhecer UTF-8. */
    public static final String BOM = "﻿";

    private ExportadorDeRelatorio() { }

    /** Os títulos das colunas do CSV, na ordem, com a rede (um servidor comum). */
    public static List<String> titulosDoCsv() {
        return titulosDoCsv(false, ZoneId.systemDefault());
    }

    /**
     * Os títulos. A primeira coluna diz o que é e em que fuso: é o INÍCIO de cada intervalo (cada linha é a média de um intervalo, de 10 segundos a 3 horas). Escrita em bytes por segundo (inteiro)
     * e rede em quilobits por segundo (três casas): números pequenos não viram 0,000.
     */
    public static List<String> titulosDoCsv(boolean semRede, ZoneId fuso) {
        List<String> t = new ArrayList<>(List.of("Início do intervalo (" + fuso.getId() + ")", "CPU (%)", "CPU máxima (%)", "Memória (%)", "Disco (%)", "Escrita no disco (B/s)",
            "Escrita máxima (B/s)"));
        if (!semRede) {
            t.add("Download (kb/s)");
            t.add("Upload (kb/s)");
        }
        return t;
    }

    /** O CSV da série inteira (com o BOM no começo). Em "Este computador" não há as colunas de rede (a rede medida é a do container do agente). */
    public static String csv(DadosDeTendencia dados, ZoneId fuso) {
        StringBuilder sb = new StringBuilder(BOM);
        sb.append(String.join(";", titulosDoCsv(dados.semRede(), fuso))).append("\r\n");
        int n = 0;
        for (Metrica m : dados.metricas()) {
            n = Math.max(n, dados.amostras(m).size());
        }
        List<List<Amostra>> colunas = new ArrayList<>(List.of(dados.amostras(Metrica.CPU), dados.amostrasDeMaximo(Metrica.CPU), dados.amostras(Metrica.MEMORIA), dados.amostras(Metrica.DISCO),
            dados.amostras(Metrica.ESCRITA_NO_DISCO), dados.amostrasDeMaximo(Metrica.ESCRITA_NO_DISCO)));
        if (!dados.semRede()) {
            colunas.add(dados.amostras(Metrica.DOWNLOAD));
            colunas.add(dados.amostras(Metrica.UPLOAD));
        }
        for (int i = 0; i < n; i++) {
            Instant quando = dados.inicio().plus(dados.passo().multipliedBy(i));
            List<String> campos = new ArrayList<>();
            campos.add(DATA_E_HORA.format(quando.atZone(fuso)));
            for (int c = 0; c < colunas.size(); c++) {
                List<Amostra> col = colunas.get(c);
                Double v = i < col.size() && col.get(i).valida() ? col.get(i).valor() : null;
                campos.add(v == null ? "" : celula(c, v));
            }
            sb.append(String.join(";", campos.stream().map(ExportadorDeRelatorio::campo).toList())).append("\r\n");
        }
        return sb.toString();
    }

    private static String celula(int coluna, double v) {
        return switch (coluna) {
            case 0, 1, 2, 3 -> numero(v * 100, 1);
            case 4, 5 -> numero(Math.round(v), 0);
            default -> numero(UnidadesDeMedida.quilobitsPorSegundo(v), 3);
        };
    }

    /** Número com vírgula decimal e sem separador de milhar. */
    static String numero(double v, int casas) {
        return new BigDecimal(v).setScale(casas, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String campo(String s) {
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** O relatório em texto simples, alinhado em colunas, para copiar e colar. */
    public static String texto(RelatorioDeTendencias r, String nomeDoServidor, Instant geradoEm) {
        ZoneId fuso = r.fuso();
        StringBuilder sb = new StringBuilder();
        sb.append("Relatório de tendências do servidor ").append(nomeDoServidor == null || nomeDoServidor.isBlank() ? "" : nomeDoServidor.strip()).append('\n');
        sb.append("Gerado em ").append(DATA_E_HORA_CURTA.format(geradoEm.atZone(fuso))).append('\n').append('\n');
        sb.append("RESUMO\n");
        for (String f : r.frases()) {
            sb.append("- ").append(f).append('\n');
        }
        sb.append('\n').append("TABELA\n");
        String[] cabecalho = {"Métrica", "Mínimo", "Média", "Máximo", "P95", "Hora do pico"};
        List<String[]> linhas = new ArrayList<>();
        for (RelatorioDeTendencias.Linha l : r.linhas()) {
            EstatisticaDeSerie.Resumo e = l.resumo();
            linhas.add(new String[] {l.metrica().nome(), RelatorioDeTendencias.valorNaTabela(l.metrica(), e.minimo()), RelatorioDeTendencias.valorNaTabela(l.metrica(), e.media()),
                RelatorioDeTendencias.valorNaTabela(l.metrica(), e.maximo()), RelatorioDeTendencias.valorNaTabela(l.metrica(), e.p95()),
                e.instanteDoPico() == null ? "—" : DATA_E_HORA_CURTA.format(e.instanteDoPico().atZone(fuso))});
        }
        int[] largura = new int[cabecalho.length];
        for (int i = 0; i < cabecalho.length; i++) {
            largura[i] = cabecalho[i].length();
            for (String[] l : linhas) {
                largura[i] = Math.max(largura[i], l[i].length());
            }
        }
        sb.append(linhaDaTabela(cabecalho, largura)).append('\n');
        for (String[] l : linhas) {
            sb.append(linhaDaTabela(l, largura)).append('\n');
        }
        sb.append('\n').append(RelatorioDeTendencias.NOTA_DOS_NUMEROS).append('\n');
        sb.append(r.dados().semRede() ? "Escrita no disco em bytes por segundo. Download e Upload não aparecem: neste computador a rede que o agente mede é a do container dele, não a do computador.\n"
            : "Download e Upload em bits por segundo; Escrita no disco em bytes por segundo.\n");
        return sb.toString();
    }

    private static String linhaDaTabela(String[] campos, int[] largura) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(campos[i]);
            if (i < campos.length - 1) {
                sb.append(" ".repeat(largura[i] - campos[i].length()));
            }
        }
        return sb.toString();
    }

    /** O nome sugerido para o arquivo: {@code tendencias-lab-1-semana-20261028-1410.csv}, sem acento, espaço nem barra. */
    public static String nomeSugerido(String nomeDoServidor, JanelaDeTempo janela, Instant instante, ZoneId fuso, String extensao) {
        String servidor = Normalizer.normalize(nomeDoServidor == null ? "" : nomeDoServidor, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String periodo = Normalizer.normalize(janela.rotuloCurto(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        String base = "tendencias" + (servidor.isEmpty() ? "" : "-" + servidor.substring(0, Math.min(40, servidor.length()))) + "-" + periodo + "-" + DATA_DO_NOME.format(instante.atZone(fuso));
        return base + "." + extensao;
    }
}
