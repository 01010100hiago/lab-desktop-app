package br.com.labdesktop.teste;

import java.util.List;

/** Textos hostis para os testes de validação: o que um shell, um programa ou um editor trataria de modo especial. */
public final class EntradasHostis {

    private EntradasHostis() { }

    /** Tudo o que um shell, um programa ou um editor trataria de modo especial: nenhuma entrada daqui pode valer em NENHUM campo. */
    public static final List<String> TODAS = List.of(
        ";", "a;b", "i-0abc1234;rm", "x b", " ", "a b", "$(id)", "${HOME}", "`id`", "'x'", "\"x\"", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u001bb", "-x", "--profile", "-oProxyCommand=x",
        "../x", "a/b", "a\\b", "x|y", "x&y", "x>y", "x<y", "x*", "x?", "%s", "%(x)s", "x#y", "x=y", "x,y", "x:y", "x@y", "日本語", "ｉ-0abc1234", "é", "a‮b", "a​b", "\ud83d\ude00",
        "i-0abc1234\n", "\ni-0abc1234", " i-0abc1234", "i-0abc1234 ", "x".repeat(300), "-".repeat(10));
}
