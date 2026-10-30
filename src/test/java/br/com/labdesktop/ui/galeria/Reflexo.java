package br.com.labdesktop.ui.galeria;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * O acesso, por reflexão, ao que a página do projeto guarda em visibilidade de pacote ({@code br.com.labdesktop.ui.telas}): a aba de cada página, o contexto da página (de onde ela
 * lê o Git) e os poucos métodos que os testes de tela dali chamam direto. Os testes da galeria ficam em outro pacote de propósito e não mudam nada em {@code src/main}.
 */
final class Reflexo {

    private Reflexo() { }

    /** Chama o método {@code nome} (com o número de argumentos dado) de {@code alvo}, mesmo que a classe e o método sejam de pacote. Relança o que o método lançar. */
    @SuppressWarnings("unchecked")
    static <T> T chamar(Object alvo, String nome, Object... argumentos) {
        for (Class<?> c = alvo.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(nome) && m.getParameterCount() == argumentos.length && aceita(m, argumentos)) {
                    try {
                        m.setAccessible(true);
                        return (T) m.invoke(alvo, argumentos);
                    } catch (InvocationTargetException e) {
                        Throwable causa = e.getCause();
                        if (causa instanceof RuntimeException re) {
                            throw re;
                        }
                        if (causa instanceof Error er) {
                            throw er;
                        }
                        throw new IllegalStateException(causa);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        throw new IllegalStateException("método não encontrado: " + alvo.getClass().getName() + "." + nome + " com " + argumentos.length + " argumento(s)");
    }

    private static boolean aceita(Method m, Object[] argumentos) {
        Class<?>[] tipos = m.getParameterTypes();
        for (int i = 0; i < tipos.length; i++) {
            Class<?> t = tipos[i].isPrimitive() ? primitivoParaClasse(tipos[i]) : tipos[i];
            if (argumentos[i] != null && !t.isInstance(argumentos[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> primitivoParaClasse(Class<?> p) {
        if (p == int.class) {
            return Integer.class;
        }
        if (p == boolean.class) {
            return Boolean.class;
        }
        if (p == long.class) {
            return Long.class;
        }
        if (p == double.class) {
            return Double.class;
        }
        return p;
    }

    /** Troca o valor de um campo (de pacote, não final) de {@code alvo}. */
    static void definirCampo(Object alvo, String nome, Object valor) {
        for (Class<?> c = alvo.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(nome);
                f.setAccessible(true);
                f.set(alvo, valor);
                return;
            } catch (NoSuchFieldException e) {
                // procura na classe de cima
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("campo não encontrado: " + alvo.getClass().getName() + "." + nome);
    }
}
