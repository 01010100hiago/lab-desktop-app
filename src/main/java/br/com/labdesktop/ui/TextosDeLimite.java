package br.com.labdesktop.ui;

import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.util.Formato;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Os textos do teto de consumo por projeto, portados do front web original (o gabarito):
 * o mesmo texto para o mesmo dado. Tudo puro (sem Swing), então os testes conferem cada frase.
 *
 * <p>O número é uma fatia ESTIMADA da semana do plano em pontos percentuais da conta (100 = a semana toda). O
 * app nunca calcula isso: só formata o que o agente manda.
 */
public final class TextosDeLimite {

    private static final String[] DIAS_DA_SEMANA = {"seg.", "ter.", "qua.", "qui.", "sex.", "sáb.", "dom."};
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_MES = DateTimeFormatter.ofPattern("dd/MM");

    private TextosDeLimite() { }

    /**
     * O uso em porcentagem. Perto do teto mostra uma casa decimal TRUNCADA: 19,6 contra um teto de 20 não pode
     * sair "20% de 20%" com estado de atenção e sem pausa (nunca arredonda para cima até encostar no teto).
     */
    public static String fmtUsado(Limite l) {
        Double u = l == null ? null : l.usadoPp();
        if (u == null || u.isNaN()) {
            return Formato.SEM_DADO;
        }
        Integer teto = l.tetoPct();
        if (teto != null && u < teto && Math.round(u) >= teto) {
            return Formato.fmtNum(Math.floor(u * 10) / 10, 1) + "%";
        }
        return Formato.fmtNum(u) + "%";
    }

    /** {@code fmtPp}: um percentual avulso ({@code 22%}) ou o travessão. */
    public static String fmtPp(Number n) {
        return n == null ? Formato.SEM_DADO : Formato.fmtNum(n) + "%";
    }

    /** {@code 14% de 20% da semana} ou {@code sem limite, 3% da semana}. */
    public static String texto(Limite l) {
        if (l == null) {
            return "";
        }
        return l.tetoPct() != null
            ? fmtUsado(l) + " de " + Formato.fmtNum(l.tetoPct()) + "% da semana"
            : "sem limite, " + fmtUsado(l) + " da semana";
    }

    /** Só o par, sem "da semana": {@code 14% de 20%}. */
    public static String textoCurto(Limite l) {
        if (l == null) {
            return "";
        }
        return l.tetoPct() != null
            ? fmtUsado(l) + " de " + Formato.fmtNum(l.tetoPct()) + "%"
            : "sem limite, " + fmtUsado(l);
    }

    /** A severidade que vem do ESTADO do servidor (atenção começa em 75 % do teto, não acima): pausado crítico, atenção alta. */
    public static Severidade severidade(Limite l) {
        if (l == null) {
            return Severidade.NORMAL;
        }
        return "pausado".equals(l.estado()) ? Severidade.CRITICA : "atencao".equals(l.estado()) ? Severidade.ALTA : Severidade.NORMAL;
    }

    /** {@code pausada pelo freio} ou {@code pausada pelo limite}. */
    public static String rotuloPausa(Limite l) {
        return l != null && l.pausa() != null && "freio".equals(l.pausa().motivo()) ? "pausada pelo freio" : "pausada pelo limite";
    }

    /** {@code true} se a pausa foi o freio global (a conta toda), não o teto do projeto. */
    public static boolean pausaPorFreio(Limite l) {
        return l != null && l.pausa() != null && "freio".equals(l.pausa().motivo());
    }

    /** A barra só existe quando há teto: sem referência, uma barra vazia ou cheia só enganaria. */
    public static boolean temBarra(Limite l) {
        return l != null && l.tetoPct() != null && l.tetoPct() > 0;
    }

    /** Fração do teto já usada (0 a 1 ou mais), ou 0 sem leitura. */
    public static double fracaoDoTeto(Limite l) {
        Double f = l == null ? null : l.fracaoDoTeto();
        return f == null ? 0 : f;
    }

    /**
     * O tempo que falta até {@code zeraEm}: {@code 5 d 3 h}, {@code 2 h 10 min}, {@code 45 min}, ou
     * {@code menos de 1 min}. {@code null} se não há data ou ela já passou ({@code fmtRestanteCredito}).
     */
    public static String restante(Instant zeraEm, Instant agora) {
        if (zeraEm == null) {
            return null;
        }
        long ms = zeraEm.toEpochMilli() - agora.toEpochMilli();
        if (ms <= 0) {
            return null;
        }
        long min = Math.round(ms / 60000.0);
        if (min < 1) {
            return "menos de 1 min";
        }
        long h = min / 60;
        long m = min % 60;
        if (h >= 24) {
            long d = h / 24;
            long hr = h % 24;
            return d + " d" + (hr != 0 ? " " + hr + " h" : "");
        }
        return h > 0 ? h + " h" + (m != 0 ? " " + m + " min" : "") : m + " min";
    }

    /** Hora local; fora de hoje, com dia da semana e data: {@code sáb., 24/10 15:15} ("21:00" sozinho não diria de qual dia). */
    public static String quandoLocal(Instant instante, Instant agora, ZoneId fuso) {
        if (instante == null) {
            return null;
        }
        ZonedDateTime t = instante.atZone(fuso);
        String hora = HORA.format(t);
        if (t.toLocalDate().equals(agora.atZone(fuso).toLocalDate())) {
            return hora;
        }
        return DIAS_DA_SEMANA[t.getDayOfWeek().getValue() - 1] + ", " + DIA_E_MES.format(t) + " " + hora;
    }

    /** {@code zera em 5 d 3 h (sáb., 24/10 15:15)}, ou vazio se a data não existe ou já passou. */
    public static String zeraTexto(Instant zeraEm, Instant agora, ZoneId fuso) {
        String dur = restante(zeraEm, agora);
        if (dur == null) {
            return "";
        }
        String hora = quandoLocal(zeraEm, agora, fuso);
        return "zera em " + dur + (hora != null ? " (" + hora + ")" : "");
    }

    /** O nome de um projeto para o usuário: a sessão raiz ({@code lab}) vira "sessão do lab". */
    public static String nomeAmigavel(String projeto) {
        return "lab".equals(projeto) ? "sessão do lab" : projeto;
    }
}
