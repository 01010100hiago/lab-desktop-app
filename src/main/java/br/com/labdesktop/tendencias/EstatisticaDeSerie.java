package br.com.labdesktop.tendencias;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mínimo, média, máximo, percentil 95 e a hora do pico de uma série. PURA e tolerante: série vazia, um ponto só, {@code null} no meio, NaN e infinito (ignorados) e relógio fora
 * de ordem (as amostras são ordenadas por instante antes de qualquer conta). Sem Swing, sem relógio do sistema.
 *
 * <p>Percentil 95 pelo método do "posto mais próximo": com {@code n} valores ordenados, o de posição {@code ceil(0,95 x n)} (de 1 a n). Com 1 valor, é ele; com 20, é o 19º.
 */
public final class EstatisticaDeSerie {

    private EstatisticaDeSerie() { }

    /**
     * O resumo de uma série. Todos os campos são {@code null} quando não há nenhum valor válido.
     *
     * @param validos    quantos valores válidos entraram
     * @param minimo     o menor valor (das médias)
     * @param media      a média dos valores
     * @param maximo     o maior valor: o pico de verdade (da série de máximos, se veio) ou, sem ela, o maior da própria série
     * @param p95        o percentil 95 dos valores
     * @param instanteDoPico quando o maior valor aconteceu (o início da célula dele); se vários empatam, o MAIS RECENTE
     */
    public record Resumo(int validos, Double minimo, Double media, Double maximo, Double p95, Instant instanteDoPico) {

        /** Não há nenhum valor. */
        public boolean vazio() {
            return validos == 0;
        }
    }

    private static final Resumo SEM_DADO = new Resumo(0, null, null, null, null, null);

    /** O resumo da série, usando o maior valor da própria série como pico. */
    public static Resumo de(List<Amostra> valores) {
        return de(valores, null);
    }

    /**
     * O resumo da série. {@code maximos} (pode ser {@code null} ou vazia) é a série dos MAIORES valores de cada célula: se tem algum valor válido, o máximo e a hora do pico saem
     * dela (a média de uma célula de 3 horas esconde o pico de 90% de CPU; o máximo guardado não).
     */
    public static Resumo de(List<Amostra> valores, List<Amostra> maximos) {
        List<Amostra> v = validasOrdenadas(valores);
        if (v.isEmpty()) {
            return SEM_DADO;
        }
        double soma = 0;
        double menor = Double.POSITIVE_INFINITY;
        double[] ordenados = new double[v.size()];
        for (int i = 0; i < v.size(); i++) {
            double x = v.get(i).valor();
            soma += x;
            menor = Math.min(menor, x);
            ordenados[i] = x;
        }
        java.util.Arrays.sort(ordenados);
        double p95 = ordenados[Math.max(1, (int) Math.ceil(0.95 * ordenados.length)) - 1];
        List<Amostra> paraOPico = maximos == null || validasOrdenadas(maximos).isEmpty() ? v : validasOrdenadas(maximos);
        Amostra pico = paraOPico.get(0);
        for (Amostra a : paraOPico) {
            if (a.valor() >= pico.valor()) {
                pico = a; // empate: fica o mais recente (a lista está em ordem de tempo)
            }
        }
        return new Resumo(v.size(), menor, soma / v.size(), pico.valor(), p95, pico.quando());
    }

    /** As amostras com número de verdade, em ordem de instante (estável: empate de instante mantém a ordem de chegada). */
    static List<Amostra> validasOrdenadas(List<Amostra> a) {
        List<Amostra> v = new ArrayList<>();
        if (a != null) {
            for (Amostra x : a) {
                if (x != null && x.valida()) {
                    v.add(x);
                }
            }
        }
        v.sort(Comparator.comparing(Amostra::quando));
        return v;
    }
}
