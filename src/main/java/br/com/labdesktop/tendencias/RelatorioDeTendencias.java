package br.com.labdesktop.tendencias;

import br.com.labdesktop.util.Formato;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * O relatório de um período: para cada métrica, mínimo, média, máximo, percentil 95 e a hora do pico; frases de resumo em português simples ("Disco chegou a 83% na quinta às
 * 14:10"); a estimativa do disco (só com 3 dias ou mais de dados, e dita como estimativa) e o aviso de quanto do período tem dado de verdade. PURO: o relógio e o fuso entram por
 * parâmetro, nada é lido do sistema.
 */
public final class RelatorioDeTendencias {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final String[] DIAS = {"na segunda", "na terça", "na quarta", "na quinta", "na sexta", "no sábado", "no domingo"};

    /**
     * Como ler os números (a mesma nota na tela e no texto copiado). Cada ponto do período é uma MÉDIA de um intervalo (10 s, 20 s, 5 min, 40 min e 3 horas nos cinco períodos); só a CPU e a
     * Escrita no disco guardam também o maior valor de cada intervalo (o pico das leituras de 2 segundos).
     */
    public static final String NOTA_DOS_NUMEROS = "Máximo e hora do pico: para CPU e Escrita no disco, o maior valor das leituras de 2 segundos; para as outras métricas, o maior valor entre os intervalos "
        + "do período (cada um é uma média: 10 s, 20 s, 5 min, 40 min e 3 horas, do período de 10 min ao de 1 mês), e a hora é o INÍCIO desse intervalo. "
        + "P95: 95% dos intervalos do período ficaram abaixo desse valor.";

    /** Uma linha da tabela. */
    public record Linha(Metrica metrica, EstatisticaDeSerie.Resumo resumo) { }

    private final DadosDeTendencia dados;
    private final ZoneId fuso;
    private final Instant agora;
    private final List<Linha> linhas = new ArrayList<>();
    private final EstimativaDeDisco.Resultado estimativa;
    private final double cobertura;

    private RelatorioDeTendencias(DadosDeTendencia dados, ZoneId fuso, Instant agora) {
        this.dados = dados;
        this.fuso = fuso;
        this.agora = agora;
        for (Metrica m : dados.metricas()) {
            linhas.add(new Linha(m, EstatisticaDeSerie.de(dados.amostras(m), m.temMaximo() ? dados.amostrasDeMaximo(m) : null)));
        }
        this.estimativa = dados.janela().cobreTresDiasOuMais()
            ? EstimativaDeDisco.de(dados.amostras(Metrica.DISCO), dados.passo())
            : new EstimativaDeDisco.Resultado(EstimativaDeDisco.Tipo.POUCO_DADO, null, null, Duration.ZERO, null);
        double melhor = 0;
        for (Metrica m : dados.metricas()) {
            List<Amostra> a = dados.amostras(m);
            if (!a.isEmpty()) {
                melhor = Math.max(melhor, a.stream().filter(Amostra::valida).count() / (double) Math.max(a.size(), dados.janela().pontos()));
            }
        }
        this.cobertura = melhor;
    }

    public static RelatorioDeTendencias de(DadosDeTendencia dados, ZoneId fuso, Instant agora) {
        return new RelatorioDeTendencias(dados, fuso, agora);
    }

    public DadosDeTendencia dados() {
        return dados;
    }

    public List<Linha> linhas() {
        return List.copyOf(linhas);
    }

    public EstimativaDeDisco.Resultado estimativaDoDisco() {
        return estimativa;
    }

    /** Que parte do período tem dado (0 a 1): a da métrica com mais dado. */
    public double cobertura() {
        return cobertura;
    }

    public ZoneId fuso() {
        return fuso;
    }

    // ---------------------------------------------------------------------------------------------
    // Texto
    // ---------------------------------------------------------------------------------------------

    /** O valor na tabela: porcentagem com uma casa, o resto como no gráfico; o traço se não há. */
    public static String valorNaTabela(Metrica m, Double v) {
        return v == null ? Formato.SEM_DADO : m.ehPorcentagem() ? UnidadesDeMedida.porcentagem(v, 1) : m.formatar(v);
    }

    /** A hora do pico em texto simples: {@code hoje às 14:10}, {@code ontem às 14:10}, {@code na quinta às 14:10} (até 6 dias atrás) ou {@code em 23/10 às 14:10}. */
    public static String quandoFoi(Instant pico, Instant agora, ZoneId fuso) {
        if (pico == null) {
            return "";
        }
        ZonedDateTime p = pico.atZone(fuso);
        LocalDate hoje = agora.atZone(fuso).toLocalDate();
        long dias = ChronoUnit.DAYS.between(p.toLocalDate(), hoje);
        String hora = " às " + HORA.format(p);
        if (dias == 0) {
            return "hoje" + hora;
        }
        if (dias == 1) {
            return "ontem" + hora;
        }
        if (dias > 1 && dias <= 6) {
            return DIAS[p.getDayOfWeek().getValue() - 1] + hora;
        }
        return "em " + DIA_MES.format(p) + hora;
    }

    /** As frases do resumo, em ordem: o período, uma por métrica, o aviso de cobertura e a estimativa do disco. */
    public List<String> frases() {
        List<String> f = new ArrayList<>();
        f.add("Período: " + dados.janela().rotuloLongo() + ", de " + DIA_E_HORA.format(dados.inicio().atZone(fuso)) + " a " + DIA_E_HORA.format(agora.atZone(fuso)) + ".");
        if (!dados.deLongoPrazo()) {
            f.add("Este agente é de uma versão antiga e guarda só os últimos 10 minutos: Disco e Escrita no disco não são gravados nele. Atualize o agente para ver períodos maiores.");
        }
        for (Linha l : linhas) {
            EstatisticaDeSerie.Resumo r = l.resumo();
            if (r.vazio()) {
                f.add(l.metrica().nome() + ": sem dado neste período.");
            } else {
                f.add(l.metrica().nome() + " chegou a " + valorNaTabela(l.metrica(), r.maximo()) + " " + quandoFoi(r.instanteDoPico(), agora, fuso) + notaDoIntervalo(l.metrica())
                    + " (média de " + valorNaTabela(l.metrica(), r.media()) + ").");
            }
        }
        if (dados.temAlgumDado() && cobertura < 0.9) {
            f.add("Atenção: só há dado em cerca de " + Math.max(1, Math.round(cobertura * 100)) + "% do período (o agente grava desde que foi ligado, e fica sem gravar quando está parado). "
                + "Os números valem só para esse trecho.");
        }
        f.add(fraseDaEstimativa());
        return f;
    }

    /**
     * Com intervalos longos, a "hora do pico" é o começo do intervalo onde ele aconteceu, e a frase diz isso (senão "às 14:10" pareceria o minuto exato). Métrica com máximo guardado (CPU e escrita) fala
     * do pico das leituras de 2 segundos, mas só se sabe em que intervalo foi.
     */
    private String notaDoIntervalo(Metrica m) {
        long s = dados.passo().toSeconds();
        if (s < 300) {
            return "";
        }
        String tamanho = s >= 3600 && s % 3600 == 0 ? (s / 3600 == 1 ? "1 hora" : (s / 3600) + " horas") : s >= 60 && s % 60 == 0 ? (s / 60) + " minutos" : s + " segundos";
        return " (início de um intervalo de " + tamanho + ")";
    }

    /** A frase da estimativa do disco (sempre dita como estimativa). */
    public String fraseDaEstimativa() {
        if (!dados.janela().cobreTresDiasOuMais()) {
            return "Estimativa do disco: precisa de pelo menos 3 dias de dados. Escolha 1 semana ou mais.";
        }
        if (dados.amostras(Metrica.DISCO).stream().noneMatch(Amostra::valida)) {
            return "Estimativa do disco: sem dado do disco neste período.";
        }
        EstimativaDeDisco.Resultado r = estimativa;
        return switch (r.tipo()) {
            case POUCO_DADO -> "Estimativa do disco: ainda não há 3 dias de dados do disco, então não dá para estimar.";
            case ESTAVEL -> "Estimativa do disco: o uso está estável (muda menos de 0,05 ponto percentual por dia). É uma estimativa pela tendência do período.";
            case DIMINUINDO -> "Estimativa do disco: o uso está diminuindo (cerca de " + Formato.fmtNum(Math.abs(r.porDia()) * 100, 1) + " ponto percentual por dia). É uma estimativa pela tendência do período.";
            case ENCHENDO -> "Estimativa do disco: no ritmo atual, enche " + emDias(r.diasParaEncher()) + ". É uma estimativa pela tendência do período; muda se o uso mudar.";
            case ENCHENDO_LONGE -> "Estimativa do disco: no ritmo atual, leva mais de um ano para encher. É uma estimativa pela tendência do período.";
            case CHEIO -> "Estimativa do disco: o uso já está perto de 100% e continua subindo. Libere espaço. É uma estimativa pela tendência do período.";
        };
    }

    private static String emDias(Double dias) {
        if (dias == null) {
            return "";
        }
        if (dias < 1) {
            return "em menos de 1 dia";
        }
        long n = Math.round(dias);
        return n == 1 ? "em cerca de 1 dia" : "em cerca de " + n + " dias";
    }
}
