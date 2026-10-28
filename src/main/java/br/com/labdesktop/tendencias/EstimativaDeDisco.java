package br.com.labdesktop.tendencias;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "No ritmo atual, o disco enche em cerca de N dias": uma reta (mínimos quadrados) pelos pontos de uso do disco do período. É uma ESTIMATIVA simples e honesta, e só aparece com
 * pelo menos 3 DIAS de dados (e pelo menos {@value #PONTOS_MINIMOS} pontos), e a reta usa um número INTEIRO de dias (os últimos 3, 4, 5... dias completos): um servidor
 * tem um ciclo de 24 h (o backup da noite sobe o disco e a limpeza o desce), e uma reta sobre 24 h exatas, ou sobre um dia e meio, lê um pedaço desse ciclo como tendência
 * ("enche em 31 dias" ou "diminuindo" por causa do backup). Com dias inteiros o ciclo do dia se cancela; o ciclo da SEMANA continua existindo e a frase diz que é estimativa.
 * Pura: sem Swing e sem relógio do sistema.
 */
public final class EstimativaDeDisco {

    /** Menos de 3 dias de dados: sem estimativa. */
    public static final Duration COBERTURA_MINIMA = Duration.ofDays(3);
    /** O ciclo do servidor que a reta precisa cobrir por inteiro. */
    static final Duration CICLO = Duration.ofDays(1);
    public static final int PONTOS_MINIMOS = 12;
    /** Abaixo de 0,05 ponto percentual por dia, o disco está "estável". */
    static final double LIMITE_DO_ESTAVEL = 0.0005;
    /** Mais de um ano para encher: "mais de um ano". */
    static final double DIAS_LONGE = 365;

    private EstimativaDeDisco() { }

    /** O que a reta diz. */
    public enum Tipo { POUCO_DADO, ESTAVEL, DIMINUINDO, ENCHENDO, ENCHENDO_LONGE, CHEIO }

    /**
     * @param tipo            o veredito
     * @param diasParaEncher  quantos dias até 100% no ritmo atual ({@code null} fora de {@code ENCHENDO} e {@code ENCHENDO_LONGE}; 0 em {@code CHEIO})
     * @param porDia          quanto o uso muda por dia, como fração (0,012 = 1,2 ponto percentual por dia); {@code null} sem estimativa
     * @param cobertura       quanto tempo de dados entrou na conta (da primeira célula ao fim da última)
     * @param nivelAgora      o uso do disco que a reta dá no último ponto (0 a 1); {@code null} sem estimativa
     */
    public record Resultado(Tipo tipo, Double diasParaEncher, Double porDia, Duration cobertura, Double nivelAgora) {

        /** Há uma estimativa (e não "pouco dado")? */
        public boolean temEstimativa() {
            return tipo != Tipo.POUCO_DADO;
        }
    }

    /**
     * @param uso   o uso do disco (fração 0 a 1) em cada célula; {@code null} e NaN são ignorados e a ordem não importa
     * @param passo o tamanho de cada célula (a cobertura conta até o FIM da última)
     */
    public static Resultado de(List<Amostra> uso, Duration passo) {
        List<Amostra> v = EstatisticaDeSerie.validasOrdenadas(uso);
        Duration p = passo == null || passo.isNegative() ? Duration.ZERO : passo;
        if (v.isEmpty()) {
            return new Resultado(Tipo.POUCO_DADO, null, null, Duration.ZERO, null);
        }
        Instant primeiro = v.get(0).quando();
        Instant ultimo = v.get(v.size() - 1).quando();
        Duration cobertura = Duration.between(primeiro, ultimo).plus(p);
        if (v.size() < PONTOS_MINIMOS || cobertura.compareTo(COBERTURA_MINIMA) < 0) {
            return new Resultado(Tipo.POUCO_DADO, null, null, cobertura, null);
        }
        // só dias INTEIROS, contados do fim para trás: o ciclo diário se cancela na reta
        long diasInteiros = cobertura.dividedBy(CICLO);
        Instant corte = ultimo.plus(p).minus(CICLO.multipliedBy(diasInteiros));
        v = v.stream().filter(a -> !a.quando().isBefore(corte)).toList();
        if (v.size() < PONTOS_MINIMOS) {
            return new Resultado(Tipo.POUCO_DADO, null, null, cobertura, null);
        }
        primeiro = v.get(0).quando();
        // Uma média por dia INTEIRO (contado do fim para trás): como cada média leva o ciclo do dia por inteiro, o ciclo se cancela e a reta passa pelas médias.
        // Dia com menos da metade dos pontos do dia mais cheio (buraco no registro) fica de fora.
        Instant fim = ultimo.plus(p);
        int[] contagem = new int[(int) diasInteiros];
        double[] somaX = new double[contagem.length];
        double[] somaY = new double[contagem.length];
        for (Amostra a : v) {
            long milis = Duration.between(a.quando(), fim).toMillis();
            int j = (int) Math.max(0, Math.min(contagem.length - 1, (long) Math.ceil(milis / 86_400_000d) - 1));
            contagem[j]++;
            somaX[j] += Duration.between(primeiro, a.quando()).toMillis() / 86_400_000d;
            somaY[j] += a.valor();
        }
        int maior = 0;
        for (int c : contagem) {
            maior = Math.max(maior, c);
        }
        // x em dias desde o primeiro ponto (números pequenos: a reta não perde precisão), y = uso médio do dia
        double n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (int j = 0; j < contagem.length; j++) {
            if (contagem[j] == 0 || contagem[j] * 2 < maior) {
                continue;
            }
            double x = somaX[j] / contagem[j];
            double y = somaY[j] / contagem[j];
            n++;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        if (n < 3) {
            return new Resultado(Tipo.POUCO_DADO, null, null, cobertura, null); // menos de 3 dias com dado de verdade
        }
        double denominador = n * sxx - sx * sx;
        if (denominador == 0) {
            return new Resultado(Tipo.POUCO_DADO, null, null, cobertura, null); // todos no mesmo instante: não há reta
        }
        double inclinacao = (n * sxy - sx * sy) / denominador;
        double intercepto = (sy - inclinacao * sx) / n;
        double xUltimo = Duration.between(primeiro, ultimo).toMillis() / 86_400_000d;
        double nivel = Math.max(0, Math.min(1, intercepto + inclinacao * xUltimo));
        if (Math.abs(inclinacao) < LIMITE_DO_ESTAVEL) {
            return new Resultado(Tipo.ESTAVEL, null, inclinacao, cobertura, nivel);
        }
        if (inclinacao < 0) {
            return new Resultado(Tipo.DIMINUINDO, null, inclinacao, cobertura, nivel);
        }
        if (nivel >= 0.995) {
            return new Resultado(Tipo.CHEIO, 0d, inclinacao, cobertura, nivel);
        }
        double dias = (1 - nivel) / inclinacao;
        if (dias > DIAS_LONGE) {
            return new Resultado(Tipo.ENCHENDO_LONGE, dias, inclinacao, cobertura, nivel);
        }
        return new Resultado(Tipo.ENCHENDO, dias, inclinacao, cobertura, nivel);
    }
}
