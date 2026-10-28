package br.com.labdesktop.tendencias;

import br.com.labdesktop.util.Formato;

/**
 * Os números das Tendências em texto pt-BR (vírgula decimal), com a unidade que o usuário entende. As DUAS decisões de unidade:
 * <ul>
 *   <li><b>Download e Upload em BITS por segundo</b> ({@code b/s}, {@code kb/s}, {@code Mb/s}, {@code Gb/s}, base 1000), uma unidade só para os dois, como a operadora
 *       vende internet e como o app já mostrava a rede. O agente manda BYTES por segundo; a conversão (vezes 8) é feita aqui.</li>
 *   <li><b>Escrita no disco em BYTES por segundo</b> ({@code B/s}, {@code KB/s}, {@code MB/s}, {@code GB/s}), como o app mostra todo tamanho de arquivo
 *       ({@link Formato#fmtBytes}).</li>
 * </ul>
 * Puro: sem Swing. {@code null}, NaN e infinito viram o traço ({@link Formato#SEM_DADO}).
 */
public final class UnidadesDeMedida {

    private UnidadesDeMedida() { }

    private static boolean semDado(Double v) {
        return v == null || v.isNaN() || v.isInfinite();
    }

    /** Fração de 0 a 1 em porcentagem: {@code 0,756} vira {@code 76%} (sem casas) ou {@code 75,6%} (com uma). */
    public static String porcentagem(Double fracao, int casas) {
        return semDado(fracao) ? Formato.SEM_DADO : Formato.fmtPct(fracao, casas);
    }

    /** Porcentagem para o valor grande de um gráfico: uma casa abaixo de 10%, nenhuma acima ({@code 4,2%}, {@code 76%}). */
    public static String porcentagem(Double fracao) {
        if (semDado(fracao)) {
            return Formato.SEM_DADO;
        }
        return Formato.fmtPct(fracao, Math.abs(fracao) < 0.1 && fracao != 0 ? 1 : 0);
    }

    /** Velocidade da rede em BITS por segundo, a partir de BYTES por segundo. */
    public static String bitsPorSegundo(Double bytesPorSegundo) {
        if (semDado(bytesPorSegundo)) {
            return Formato.SEM_DADO;
        }
        double bits = bytesPorSegundo * 8;
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

    /** Velocidade de gravação em disco, em BYTES por segundo: {@code 340 B/s}, {@code 1,2 MB/s}. */
    public static String bytesPorSegundo(Double bytesPorSegundo) {
        if (semDado(bytesPorSegundo)) {
            return Formato.SEM_DADO;
        }
        double v = Math.max(0, bytesPorSegundo);
        return Formato.fmtBps(v, v < 1024 ? 0 : 1); // "340 B/s" e "1,5 MB/s": uma casa quando já é KB ou mais
    }

    /** Megabits por segundo como número puro, para a planilha ({@code 12,345}); sem unidade no valor (a unidade está no título da coluna). */
    public static double megabitsPorSegundo(double bytesPorSegundo) {
        return bytesPorSegundo * 8 / 1_000_000d;
    }

    /** Megabytes (1024 x 1024) por segundo como número puro, para a planilha. */
    public static double megabytesPorSegundo(double bytesPorSegundo) {
        return bytesPorSegundo / (1024d * 1024d);
    }
}
