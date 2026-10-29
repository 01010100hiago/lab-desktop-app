package br.com.labdesktop.transporte;

import java.util.concurrent.CompletableFuture;

/**
 * Um programa externo que o app lançou (o túnel). Atrás de uma interface para os testes usarem um lançador de mentira (que sobe, cai, demora, solta lixo na saída) e para provar que o
 * processo é encerrado.
 */
public interface ProcessoExterno {

    /** O processo ainda roda? */
    boolean vivo();

    /** Completa com o código de saída quando o processo termina (por conta própria ou por {@link #encerrar()}). */
    CompletableFuture<Integer> aoSair();

    /**
     * O fim da saída (padrão e erro juntos), TEXTO sem tratamento: quem usa passa pelo {@code Redator}, trunca e nunca trata como comando nem como HTML. Limitado ao que o
     * lançador guarda (poucos KiB).
     */
    String saida();

    /**
     * Encerra o processo E os filhos dele (o {@code aws} lança o plugin do Session Manager como filho: matar só o {@code aws} deixaria o plugin órfão): pede com educação, espera um pouco e
     * força. Idempotente; volta quando o processo saiu (ou depois do prazo curto).
     */
    void encerrar();
}
