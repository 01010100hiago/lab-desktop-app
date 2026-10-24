package br.com.labdesktop.ui.telas;

import br.com.labdesktop.util.Formato;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Os textos de número da tela do servidor, em pt-BR (vírgula decimal, ponto de milhar) e para quem não é técnico:
 * tempo ligado por extenso ("3 h 57 min"), velocidade da rede em bits ("5,7 kb/s", como a operadora vende) e
 * "—" quando o dado não veio. Apoia-se no {@link Formato} (que fixa os separadores sem depender do {@code Locale} da
 * JVM) e só acrescenta o que é próprio desta tela. Puro e sem estado.
 */
public final class FormatosDoServidor {

    /** O que se mostra quando o dado não veio. */
    public static final String SEM_DADO = Formato.SEM_DADO;

    private static final DateTimeFormatter HORA_MINUTO = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final Pattern VERSAO = Pattern.compile("v?(\\d+(?:\\.\\d+)+)");

    private FormatosDoServidor() { }

    private static boolean semDado(Number n) {
        return n == null || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue());
    }

    /** Tamanho em B, KB, MB, GB ou TB. */
    public static String bytes(Number bytes) {
        return Formato.fmtBytes(bytes);
    }

    /**
     * Velocidade da rede em BITS por segundo ({@code b/s}, {@code kb/s}, {@code Mb/s}, {@code Gb/s}, base 1000, como
     * se mede internet). A entrada é BYTES por segundo (é o que o agente manda em {@code rx_bps} e {@code tx_bps}).
     */
    public static String bitsPorSegundo(Number bytesPorSegundo) {
        if (semDado(bytesPorSegundo)) {
            return SEM_DADO;
        }
        double bits = bytesPorSegundo.doubleValue() * 8;
        if (bits < 1_000) {
            return Formato.fmtNum(Math.round(bits), 0) + " b/s";
        }
        if (bits < 1_000_000) {
            return Formato.fmtNum(bits / 1_000) + " kb/s";
        }
        if (bits < 1_000_000_000) {
            return Formato.fmtNum(bits / 1_000_000) + " Mb/s";
        }
        return Formato.fmtNum(bits / 1_000_000_000) + " Gb/s";
    }

    /** Tempo ligado: {@code 5 d 7 h}, {@code 3 h 57 min}, {@code 12 min} ou {@code menos de 1 min}. */
    public static String tempoLigado(Number segundos) {
        if (semDado(segundos) || segundos.doubleValue() < 0) {
            return SEM_DADO;
        }
        long total = (long) Math.floor(segundos.doubleValue());
        long d = total / 86_400;
        long h = total % 86_400 / 3_600;
        long m = total % 3_600 / 60;
        if (d > 0) {
            return d + " d" + (h > 0 ? " " + h + " h" : "");
        }
        if (h > 0) {
            return h + " h" + (m > 0 ? " " + m + " min" : "");
        }
        return m > 0 ? m + " min" : "menos de 1 min";
    }

    /** Fração de 0 a 1 em porcentagem: uma casa abaixo de 10 % ({@code 8,3%}), nenhuma acima ({@code 42%}). */
    public static String porcentagem(Number fracao) {
        return semDado(fracao) ? SEM_DADO : Formato.fmtNum(umaCasa(fracao.doubleValue() * 100)) + "%";
    }

    /** Arredonda a uma casa para 7,000000000000001 não virar "7,0%". */
    private static double umaCasa(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Valor que já é percentual (a CPU de um processo, 100 = um núcleo inteiro): {@code 5,2%}, {@code 120%}. */
    public static String percentual(Number pct) {
        return semDado(pct) ? SEM_DADO : Formato.fmtNum(umaCasa(pct.doubleValue())) + "%";
    }

    /** Temperatura em graus: {@code 62 °C}. */
    public static String temperatura(Number celsius) {
        return semDado(celsius) ? SEM_DADO : Formato.fmtNum(celsius, 0) + " °C";
    }

    /** Carga média com duas casas: {@code 0,52}. */
    public static String carga(Number carga) {
        return semDado(carga) ? SEM_DADO : Formato.fmtNum(carga, 2);
    }

    /** Hora e minuto: {@code 14:05}. */
    public static String horaMinuto(Instant instante, ZoneId fuso) {
        return instante == null ? SEM_DADO : HORA_MINUTO.format(instante.atZone(fuso));
    }

    /** Dia, mês, hora e minuto: {@code 19/10 23:53}. */
    public static String diaEHora(Instant instante, ZoneId fuso) {
        return instante == null ? SEM_DADO : DIA_E_HORA.format(instante.atZone(fuso));
    }

    /** {@code 19/10 23:53 (há 4 d)}: quando e há quanto tempo. */
    public static String desde(Instant instante, Instant agora, ZoneId fuso) {
        return instante == null ? SEM_DADO : diaEHora(instante, fuso) + " (" + Formato.fmtHa(instante, agora) + ")";
    }

    /**
     * Só o número de versão de um texto de {@code --version}: "2.1.303 (Claude Code)" vira {@code 2.1.303}, "Docker
     * version 29.9.1, build dfc520d" vira {@code 29.9.1}, "v22.23.3" vira {@code 22.23.3}. Sem número, devolve o
     * texto como veio.
     */
    public static String versao(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return SEM_DADO;
        }
        Matcher m = VERSAO.matcher(bruto);
        return m.find() ? m.group(1) : bruto.strip();
    }

    /** Número inteiro (contagem), ou traço. */
    public static String inteiro(Number n) {
        return semDado(n) ? SEM_DADO : Formato.fmtNum(n, 0);
    }
}
