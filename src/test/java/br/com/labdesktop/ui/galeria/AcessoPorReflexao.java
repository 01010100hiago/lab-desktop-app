package br.com.labdesktop.ui.galeria;

import java.lang.reflect.Method;

/**
 * Chama os acessores "só para os testes" que ficam visíveis só dentro do pacote da tela ({@code ui.telas}) sem criar uma classe nesse pacote: os testes de galeria moram em
 * {@code ui.galeria} e não mexem em {@code src/main}.
 */
final class AcessoPorReflexao {

    private AcessoPorReflexao() { }

    /** Chama o método de nome e número de argumentos dados (acha-o na classe ou nas mães) e devolve o que ele devolveu. */
    static Object chamar(Object alvo, String nome, Object... argumentos) {
        for (Class<?> c = alvo.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(nome) && m.getParameterCount() == argumentos.length) {
                    try {
                        m.setAccessible(true);
                        return m.invoke(alvo, argumentos);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        if (e.getCause() instanceof RuntimeException r) {
                            throw r;
                        }
                        if (e.getCause() instanceof Error r) {
                            throw r;
                        }
                        throw new IllegalStateException(e.getCause());
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        throw new IllegalArgumentException("sem o método " + nome + " em " + alvo.getClass());
    }
}
