package br.com.labdesktop.git;

import java.util.function.Function;

/** O que uma leitura do git devolveu: o valor, ou a falha explicada. Imutável. */
public sealed interface ResultadoDoGit<T> {

    /** Deu certo. */
    record Ok<T>(T valor) implements ResultadoDoGit<T> { }

    /** Não deu certo (a falha já vem explicada em palavras simples). */
    record Falhou<T>(FalhaDoGit falha) implements ResultadoDoGit<T> { }

    default boolean ok() {
        return this instanceof Ok;
    }

    /** O valor, ou {@code null} se falhou. */
    default T valorOuNulo() {
        return this instanceof Ok<T> o ? o.valor() : null;
    }

    default FalhaDoGit falhaOuNula() {
        return this instanceof Falhou<T> f ? f.falha() : null;
    }

    default <R> ResultadoDoGit<R> mapear(Function<T, R> f) {
        return this instanceof Ok<T> o ? new Ok<>(f.apply(o.valor())) : new Falhou<>(((Falhou<T>) this).falha());
    }
}
