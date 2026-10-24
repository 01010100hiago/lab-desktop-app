package br.com.labdesktop.servidor;

/**
 * Uma assinatura que se desfaz com {@link #close()} (ou {@code try-with-resources}). A tela assina ao ficar
 * visível ({@code HierarchyListener} com {@code showing}) e solta ao esconder: sem assinante, o tópico não
 * consulta nada (ARQUITETURA 6.7). Idempotente; não lança.
 */
@FunctionalInterface
public interface Inscricao extends AutoCloseable {

    @Override
    void close();

    /** Uma inscrição que não faz nada. */
    static Inscricao vazia() {
        return () -> { };
    }
}
