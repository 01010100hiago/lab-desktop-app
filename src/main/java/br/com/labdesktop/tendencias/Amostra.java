package br.com.labdesktop.tendencias;

import java.time.Instant;

/**
 * Um ponto de uma série: quando e quanto. {@code valor} {@code null} é "sem dado neste instante" (a estatística o ignora). A ordem da lista NÃO é confiável (relógio fora de
 * ordem): quem calcula ordena por {@code quando}.
 */
public record Amostra(Instant quando, Double valor) {

    /** Tem número de verdade (nem {@code null}, nem NaN, nem infinito) e instante? */
    public boolean valida() {
        return quando != null && valor != null && !valor.isNaN() && !valor.isInfinite();
    }
}
