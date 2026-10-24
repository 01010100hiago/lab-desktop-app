package br.com.labdesktop.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatadores de número, tamanho, tempo e dinheiro, portados do front web original do painel
 * web (o gabarito das telas): o mesmo texto para o mesmo dado (ARQUITETURA 4.2).
 *
 * <p>Decisões que valem para todos:
 * <ul>
 *   <li>Dado ausente ({@code null}, {@code NaN}, infinito) vira {@code "—"} (travessão), como no front.</li>
 *   <li>Os separadores pt-BR (vírgula decimal, ponto de milhar) são fixados aqui, sem consultar {@code Locale}.
 *       Assim o resultado não depende de a JVM ter os dados de localidade: o {@code jlink} sem
 *       {@code jdk.localedata} formataria em en-US sem avisar (ARQUITETURA 11.3 e 13).</li>
 *   <li>Arredondamento "meio para cima" sobre a representação decimal mais curta do {@code double} (a mesma
 *       que o {@code toLocaleString} do navegador usa): 9,95 com uma casa dá 10,0, e 2,5 sem casas dá 3.</li>
 *   <li>Sem estado e seguro entre threads; nenhum método toca Swing.</li>
 * </ul>
 */
public final class Formato {

    /** O que se mostra quando não há dado. */
    public static final String SEM_DADO = "—";

    private static final DecimalFormatSymbols SIMBOLOS_PT_BR = simbolosPtBr();
    private static final String[] MESES_CURTOS = {
        "jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"
    };
    private static final String[] UNIDADES_DE_BYTES = {"B", "KB", "MB", "GB", "TB"};
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Formato() { }

    private static DecimalFormatSymbols simbolosPtBr() {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setDecimalSeparator(',');
        s.setGroupingSeparator('.');
        s.setMinusSign('-');
        return s;
    }

    private static boolean semDado(Number n) {
        if (n == null) {
            return true;
        }
        double d = n.doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d);
    }

    // ---------------------------------------------------------------------------------------------
    // Números
    // ---------------------------------------------------------------------------------------------

    /**
     * Número em pt-BR. Sem {@code casas}: uma casa para fração abaixo de 10 em módulo ({@code 3,1}), nenhuma
     * nos demais ({@code 1.235}).
     */
    public static String fmtNum(Number n) {
        if (semDado(n)) {
            return SEM_DADO;
        }
        double d = n.doubleValue();
        boolean fracionario = d % 1 != 0;
        return fmtNum(n, Math.abs(d) < 10 && fracionario ? 1 : 0);
    }

    /** Número em pt-BR com exatamente {@code casas} casas decimais (0 a 10). */
    public static String fmtNum(Number n, int casas) {
        if (semDado(n)) {
            return SEM_DADO;
        }
        if (casas < 0 || casas > 10) {
            throw new IllegalArgumentException("casas fora de 0 a 10: " + casas);
        }
        BigDecimal valor = n instanceof BigDecimal bd ? bd : BigDecimal.valueOf(n.doubleValue());
        DecimalFormat formato = new DecimalFormat("#,##0", SIMBOLOS_PT_BR);
        formato.setGroupingUsed(true);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        formato.setMinimumFractionDigits(casas);
        formato.setMaximumFractionDigits(casas);
        return formato.format(valor);
    }

    /** Tamanho em B, KB, MB, GB ou TB (base 1024): {@code 1.023 B}, {@code 2 KB}, {@code 5,0 GB}. */
    public static String fmtBytes(Number bytes) {
        return formatarBytes(bytes, null);
    }

    public static String fmtBytes(Number bytes, int casas) {
        return formatarBytes(bytes, casas);
    }

    private static String formatarBytes(Number bytes, Integer casas) {
        if (semDado(bytes)) {
            return SEM_DADO;
        }
        double v = bytes.doubleValue();
        int i = 0;
        while (v >= 1024 && i < UNIDADES_DE_BYTES.length - 1) {
            v /= 1024;
            i++;
        }
        return fmtNum(v, casas == null ? (i >= 3 ? 1 : 0) : casas) + " " + UNIDADES_DE_BYTES[i];
    }

    /** Fração (0 a 1) em porcentagem sem casas: {@code 0,756} vira {@code 76%}. */
    public static String fmtPct(Number fracao) {
        return fmtPct(fracao, 0);
    }

    public static String fmtPct(Number fracao, int casas) {
        if (semDado(fracao)) {
            return SEM_DADO;
        }
        return fmtNum(BigDecimal.valueOf(fracao.doubleValue()).multiply(BigDecimal.valueOf(100)), casas) + "%";
    }

    /** Bytes por segundo: {@code 1,5 MB/s}. */
    public static String fmtBps(Number bytesPorSegundo) {
        return semDado(bytesPorSegundo) ? SEM_DADO : fmtBytes(bytesPorSegundo) + "/s";
    }

    public static String fmtBps(Number bytesPorSegundo, int casas) {
        return semDado(bytesPorSegundo) ? SEM_DADO : fmtBytes(bytesPorSegundo, casas) + "/s";
    }

    /** Valor em dólar com 2 casas ({@code US$ 12,50}): o custo equivalente de API usa sempre isto. */
    public static String fmtUsd(Number valor) {
        return fmtUsd(valor, 2);
    }

    public static String fmtUsd(Number valor, int casas) {
        return semDado(valor) ? SEM_DADO : "US$ " + fmtNum(valor, casas);
    }

    /** Número grande (tokens, respostas) em forma compacta: {@code 1,5 mil}, {@code 2,50 mi}, {@code 1,00 bi}. */
    public static String fmtCompacto(Number n) {
        if (semDado(n)) {
            return SEM_DADO;
        }
        double v = n.doubleValue();
        String sinal = v < 0 ? "-" : "";
        v = Math.abs(v);
        if (v >= 1e9) {
            return sinal + fmtNum(v / 1e9, 2) + " bi";
        }
        if (v >= 1e6) {
            return sinal + fmtNum(v / 1e6, 2) + " mi";
        }
        if (v >= 1e3) {
            return sinal + fmtNum(v / 1e3, 1) + " mil";
        }
        return sinal + fmtNum(v, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Tempo
    // ---------------------------------------------------------------------------------------------

    /** Tempo ligado em segundos: {@code 3d 4h}, {@code 1h 5min} ou {@code 2min}. */
    public static String fmtUptime(Number segundos) {
        if (semDado(segundos)) {
            return SEM_DADO;
        }
        double s = segundos.doubleValue();
        long d = (long) Math.floor(s / 86400);
        long h = (long) Math.floor(s % 86400 / 3600);
        long m = (long) Math.floor(s % 3600 / 60);
        if (d != 0) {
            return d + "d " + h + "h";
        }
        return h != 0 ? h + "h " + m + "min" : m + "min";
    }

    /** "há 5 min", "há 3 h", "há 2 d" ou "agora" (menos de 90 s, ou instante no futuro) em relação a {@code agora}. */
    public static String fmtHa(Instant instante, Instant agora) {
        if (instante == null) {
            return SEM_DADO;
        }
        double s = Duration.between(instante, agora).toMillis() / 1000.0;
        if (s < 90) {
            return "agora";
        }
        if (s < 3600) {
            return "há " + Math.round(s / 60) + " min";
        }
        if (s < 86400) {
            return "há " + Math.round(s / 3600) + " h";
        }
        return "há " + Math.round(s / 86400) + " d";
    }

    /** {@link #fmtHa(Instant, Instant)} em relação ao relógio do sistema. */
    public static String fmtHa(Instant instante) {
        return fmtHa(instante, Instant.now());
    }

    /** Hora local no formato {@code 14:05:09}. */
    public static String fmtHora(Instant instante, ZoneId fuso) {
        return instante == null ? SEM_DADO : HORA.format(instante.atZone(fuso));
    }

    /** {@link #fmtHora(Instant, ZoneId)} no fuso do sistema. */
    public static String fmtHora(Instant instante) {
        return fmtHora(instante, ZoneId.systemDefault());
    }

    /**
     * {@code AAAA-MM-DD} em dia e mês curto, como o gráfico de 30 dias do painel web: {@code 2026-09-23}
     * vira {@code 23 de set}. Data ilegível volta como veio.
     */
    public static String fmtDiaCurto(String dia) {
        if (dia == null) {
            return SEM_DADO;
        }
        try {
            LocalDate data = LocalDate.parse(dia);
            return String.format("%02d de %s", data.getDayOfMonth(), MESES_CURTOS[data.getMonthValue() - 1]);
        } catch (DateTimeException e) {
            return dia;
        }
    }
}
