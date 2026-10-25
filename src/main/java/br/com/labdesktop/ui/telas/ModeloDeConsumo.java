package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.api.modelo.TokensDoProjeto.Acumulado;
import br.com.labdesktop.api.modelo.TokensDoProjeto.Dia;
import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.ui.componentes.GraficoDeBarras.Barra;
import br.com.labdesktop.ui.telas.ModeloDeKpis.Kpi;
import br.com.labdesktop.util.Formato;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Tudo o que a aba "Consumo e limite" mostra, já em texto (puro, sem Swing): os quatro totais (hoje, 7 dias, 30 dias e tudo),
 * as barras dos últimos 30 dias, as linhas por modelo e por sessão, a linha do Harness e o bloco "Limite da semana". O custo é
 * SEMPRE "US$ equivalente de API" (a conta é assinatura, não cobra por token) e o app só formata o que o agente manda.
 */
public final class ModeloDeConsumo {

    /** O bloco "Limite da semana": o que escrever, que botões fazem sentido e como pintar a barra. */
    public record BlocoDoLimite(
        String valor,
        String estado,
        Gravidade gravidade,
        String icone,
        boolean temBarra,
        double fracao,
        Severidade severidade,
        String explicacao,
        boolean pausado,
        boolean liberado,
        boolean temTeto) { }

    private ModeloDeConsumo() { }

    // ---------------------------------------------------------------------------------------------
    // Totais
    // ---------------------------------------------------------------------------------------------

    /** Os quatro totais, na ordem da tela, como indicadores sem selo. {@code tokens} pode ser {@code null} (ainda calculando). */
    public static List<Kpi> totais(TokensDoProjeto tokens) {
        TokensDoProjeto.Totais t = tokens == null ? null : tokens.totais();
        return List.of(total("hoje", "HOJE", t == null ? null : t.hoje(), "Hoje"),
            total("semana", "7 DIAS", t == null ? null : t.semana(), "Nos últimos 7 dias"),
            total("mes", "30 DIAS", t == null ? null : t.mes(), "Nos últimos 30 dias"),
            total("tudo", "TOTAL", t == null ? null : t.tudo(), "Desde o primeiro uso"));
    }

    private static Kpi total(String chave, String rotulo, Acumulado a, String periodo) {
        if (a == null) {
            return new Kpi(chave, rotulo, "moeda", Formato.SEM_DADO, "sem leitura", periodo + ": o consumo ainda não chegou.",
                NivelDeSaude.SEM_DADO, "", "");
        }
        String volume = FormatosDoProjeto.volume(a.saida(), a.respostas());
        String dica = periodo + ": " + Formato.fmtUsd(a.custoUsd() == null ? 0 : a.custoUsd()) + " (equivalente de API, a conta é "
            + "assinatura) · " + volume + ".";
        return new Kpi(chave, rotulo, "moeda", Formato.fmtUsd(a.custoUsd() == null ? 0 : a.custoUsd()), volume, dica,
            NivelDeSaude.SEM_DADO, "", "");
    }

    // ---------------------------------------------------------------------------------------------
    // Gráfico dos 30 dias
    // ---------------------------------------------------------------------------------------------

    /** Uma barra por dia (30), do mais antigo ao mais novo; dia sem uso vira barra de valor zero com a dica "sem consumo". */
    public static List<Barra> barras(TokensDoProjeto tokens) {
        List<Barra> l = new ArrayList<>();
        if (tokens == null) {
            return l;
        }
        for (Dia d : tokens.porDia()) {
            double custo = d.custoUsd() == null || d.custoUsd().isNaN() ? 0 : Math.max(0, d.custoUsd());
            String dia = Formato.fmtDiaCurto(d.dia());
            boolean usou = custo > 0 || (d.respostas() != null && d.respostas() > 0);
            String dica = usou
                ? dia + ": " + Formato.fmtUsd(custo) + " · " + FormatosDoProjeto.volume(d.saida(), d.respostas())
                : dia + ": sem consumo";
            l.add(new Barra(dia, custo, dica));
        }
        return l;
    }

    /** O texto do leitor de tela para o gráfico: o total do período e o dia mais caro. */
    public static String resumoDoGrafico(TokensDoProjeto tokens) {
        List<Dia> dias = tokens == null ? List.of() : tokens.porDia();
        if (dias.isEmpty()) {
            return "Consumo dos últimos 30 dias: sem dados.";
        }
        double soma = 0;
        Dia maior = null;
        for (Dia d : dias) {
            double c = d.custoUsd() == null ? 0 : d.custoUsd();
            soma += c;
            if (maior == null || c > (maior.custoUsd() == null ? 0 : maior.custoUsd())) {
                maior = d;
            }
        }
        double cMaior = maior.custoUsd() == null ? 0 : maior.custoUsd();
        return "Consumo dos últimos " + dias.size() + " dias: " + Formato.fmtUsd(soma) + " no total"
            + (cMaior > 0 ? "; o dia mais caro foi " + Formato.fmtDiaCurto(maior.dia()) + ", com " + Formato.fmtUsd(cMaior) : "; sem uso no período")
            + ".";
    }

    /** A legenda curta embaixo do gráfico: {@code US$ 68,20 nos 30 dias · maior dia: 16 de out (US$ 8,40)}. */
    public static String legendaDoGrafico(TokensDoProjeto tokens) {
        List<Dia> dias = tokens == null ? List.of() : tokens.porDia();
        double soma = 0;
        Dia maior = null;
        double cMaior = 0;
        for (Dia d : dias) {
            double c = d.custoUsd() == null ? 0 : d.custoUsd();
            soma += c;
            if (c > cMaior) {
                cMaior = c;
                maior = d;
            }
        }
        String base = Formato.fmtUsd(soma) + " em " + dias.size() + " dias";
        return maior == null ? base : base + " · maior dia: " + Formato.fmtDiaCurto(maior.dia()) + " (" + Formato.fmtUsd(cMaior) + ")";
    }

    /** O que o gráfico escreve no alto: o maior valor. */
    public static String valorDoAlto(double v) {
        return Formato.fmtUsd(v);
    }

    // ---------------------------------------------------------------------------------------------
    // Tabelas e Harness
    // ---------------------------------------------------------------------------------------------

    /** {@code 2 h 10 min} entre duas datas, ou traço. */
    public static String duracao(Instant inicio, Instant fim) {
        if (inicio == null || fim == null || fim.isBefore(inicio)) {
            return Formato.SEM_DADO;
        }
        Duration d = Duration.between(inicio, fim);
        long s = d.getSeconds();
        return s < 60 ? "menos de 1 min" : FormatosDoServidor.tempoLigado(s);
    }

    /** {@code atribuicao}: {@code direta} ou {@code estimada}, em palavras para o usuário. */
    public static String atribuicao(String a) {
        if (a == null || a.isBlank()) {
            return Formato.SEM_DADO;
        }
        return switch (a) {
            case "direta" -> "direta";
            case "estimada" -> "estimada";
            default -> a;
        };
    }

    /** A linha do Harness ({@code 19 entregas · 21 iniciados · 206,6 mil tokens}), ou {@code null} se o agente não mandou. */
    public static String harness(TokensDoProjeto tokens) {
        var h = tokens == null ? null : tokens.harness();
        if (h == null || (h.entregas().isEmpty() && (h.iniciados() == null || h.iniciados() == 0)
            && (h.tokensTotal() == null || h.tokensTotal() == 0))) {
            return null;
        }
        List<String> partes = new ArrayList<>();
        int n = h.entregas().size();
        partes.add(n + (n == 1 ? " entrega" : " entregas"));
        if (h.iniciados() != null) {
            partes.add(Formato.fmtNum(h.iniciados(), 0) + (h.iniciados() == 1 ? " iniciado" : " iniciados"));
        }
        if (h.tokensTotal() != null) {
            partes.add(Formato.fmtCompacto(h.tokensTotal()) + " tokens");
        }
        return "Harness: " + String.join(" · ", partes);
    }

    // ---------------------------------------------------------------------------------------------
    // Limite da semana
    // ---------------------------------------------------------------------------------------------

    /** O bloco "Limite da semana" para um {@code Limite} (que pode ser {@code null}: o agente ainda não leu). */
    public static BlocoDoLimite limite(Limite l, Instant agora, ZoneId fuso) {
        if (l == null) {
            return new BlocoDoLimite(Formato.SEM_DADO, "sem leitura", Gravidade.NEUTRO, "info", false, 0, Severidade.NORMAL,
                "O agente ainda não informou o consumo da semana deste projeto.", false, false, false);
        }
        boolean liberado = Boolean.TRUE.equals(l.liberado());
        boolean temTeto = l.tetoPct() != null;
        String zera = TextosDeLimite.zeraTexto(l.zeraEm(), agora, fuso);
        String base = "Fatia estimada da semana do plano usada por este projeto (a conta toda vale 100%).";
        String estado;
        Gravidade g;
        String icone;
        String explicacao;
        if (l.pausado()) {
            estado = "pausado";
            g = Gravidade.PERIGO;
            icone = "pausa";
            explicacao = base + (TextosDeLimite.pausaPorFreio(l)
                ? " A sessão do Claude está pausada pelo freio da conta."
                : " A sessão do Claude está pausada porque o projeto chegou ao limite.") + (zera.isEmpty() ? "" : " Semana " + zera + ".");
        } else if (liberado) {
            estado = "liberado";
            g = Gravidade.OK;
            icone = "ok-circulo";
            explicacao = base + " Sem limite e sem freio até a semana virar" + (zera.isEmpty() ? "." : " (" + zera + ").");
        } else if (!temTeto) {
            estado = "sem limite";
            g = Gravidade.NEUTRO;
            icone = "info";
            explicacao = base + " Nenhum limite definido: use Definir limite para pausar a sessão quando o projeto passar de um valor."
                + (zera.isEmpty() ? "" : " Semana " + zera + ".");
        } else if ("atencao".equals(l.estado())) {
            estado = "atenção";
            g = Gravidade.ATENCAO;
            icone = "alerta";
            explicacao = base + " O projeto está perto do limite." + (zera.isEmpty() ? "" : " Semana " + zera + ".");
        } else {
            estado = "dentro do limite";
            g = Gravidade.OK;
            icone = "ok-circulo";
            explicacao = base + (zera.isEmpty() ? "" : " Semana " + zera + ".");
        }
        return new BlocoDoLimite(TextosDeLimite.textoCurto(l), estado, g, icone, TextosDeLimite.temBarra(l),
            Math.min(1.0, TextosDeLimite.fracaoDoTeto(l)), TextosDeLimite.severidade(l), explicacao, l.pausado(), liberado, temTeto);
    }
}
