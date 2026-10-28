package br.com.labdesktop.tendencias;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "No ritmo atual, o disco enche em cerca de N dias": uma reta (mínimos quadrados) pelos pontos de uso do disco do período. É uma ESTIMATIVA simples e honesta, e só aparece com
 * pelo menos 24 HORAS de dados (e pelo menos {@value #PONTOS_MINIMOS} pontos): com menos, o dia e a noite do servidor enganariam a reta. Pura: sem Swing e sem relógio do sistema.
 */
public final class EstimativaDeDisco {

    /** Menos de 24 h de dados: sem estimativa. */
    public static final Duration COBERTURA_MINIMA = Duration.ofHours(24);
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
        // x em dias desde o primeiro ponto (números pequenos: a reta não perde precisão), y = uso
        double n = v.size();
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (Amostra a : v) {
            double x = Duration.between(primeiro, a.quando()).toMillis() / 86_400_000d;
            double y = a.valor();
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
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
