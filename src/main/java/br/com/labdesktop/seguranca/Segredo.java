package br.com.labdesktop.seguranca;

/**
 * Tudo que guarda segredo em memória (chave privada, token, segredo do TOTP) implementa isto.
 *
 * <p>Regras do projeto (ARQUITETURA 4.3 e 5.5), válidas para quem implementar:
 * <ul>
 *   <li>NÃO é {@code record}: o {@code toString} automático do record imprimiria o conteúdo.
 *       O {@code toString} da implementação tem de ser redigido (sem nenhum byte do segredo).</li>
 *   <li>O conteúdo fica em {@code byte[]} ou {@code char[]}, nunca em {@code String}, e {@link #zerar()}
 *       preenche o array com zeros (não só troca a referência).</li>
 *   <li>{@code equals} e {@code hashCode} são os de identidade: comparar segredo pelo conteúdo é com
 *       método próprio de tempo constante, se um dia for preciso.</li>
 * </ul>
 */
public interface Segredo extends AutoCloseable {

    /** Preenche com zeros o conteúdo guardado. Idempotente. Depois disso o objeto não serve mais. */
    void zerar();

    /**
     * {@code true} se o conteúdo guardado está todo em zero. Confere os bytes de verdade (não é um
     * indicador separado), porque é o que o teste de "zerado ao bloquear" precisa provar.
     */
    boolean estaZerado();

    /** Igual a {@link #zerar()}, para uso em {@code try-with-resources}. */
    @Override
    default void close() {
        zerar();
    }
}
