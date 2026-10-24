package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AtrasoDeSenhaTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void asDuasPrimeirasFalhasNaoEsperamEDepoisCresce() {
        assertEquals(Duration.ZERO, AtrasoDeSenha.atrasoApos(0));
        assertEquals(Duration.ZERO, AtrasoDeSenha.atrasoApos(1));
        assertEquals(Duration.ZERO, AtrasoDeSenha.atrasoApos(2));
        assertEquals(Duration.ofSeconds(1), AtrasoDeSenha.atrasoApos(3));
        assertEquals(Duration.ofSeconds(2), AtrasoDeSenha.atrasoApos(4));
        assertEquals(Duration.ofSeconds(4), AtrasoDeSenha.atrasoApos(5));
        assertEquals(Duration.ofSeconds(8), AtrasoDeSenha.atrasoApos(6));
        assertEquals(Duration.ofSeconds(16), AtrasoDeSenha.atrasoApos(7));
        assertEquals(Duration.ofSeconds(30), AtrasoDeSenha.atrasoApos(8));
        assertEquals(Duration.ofSeconds(30), AtrasoDeSenha.atrasoApos(500));
    }

    @Test
    void restanteDesceComOTempoEZeraNoSucesso() {
        AtrasoDeSenha a = new AtrasoDeSenha();
        assertEquals(Duration.ZERO, a.restante(T0));
        a.registrarFalha(T0);
        a.registrarFalha(T0);
        assertEquals(Duration.ZERO, a.restante(T0));
        a.registrarFalha(T0); // terceira: 1 s
        assertEquals(Duration.ofSeconds(1), a.restante(T0));
        assertEquals(Duration.ofMillis(400), a.restante(T0.plusMillis(600)));
        assertEquals(Duration.ZERO, a.restante(T0.plusSeconds(1)));
        a.registrarFalha(T0.plusSeconds(1)); // quarta: 2 s
        assertEquals(Duration.ofSeconds(2), a.restante(T0.plusSeconds(1)));
        assertEquals(4, a.falhasSeguidas());
        a.registrarSucesso();
        assertEquals(Duration.ZERO, a.restante(T0.plusSeconds(1)));
        assertEquals(0, a.falhasSeguidas());
    }
}
