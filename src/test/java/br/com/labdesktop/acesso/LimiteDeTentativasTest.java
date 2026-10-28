package br.com.labdesktop.acesso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** O freio acumulado: no máximo 4 recusas em 10 minutos, pausa que cresce, chave normalizada, sucesso zera. */
class LimiteDeTentativasTest {

    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));
    private final LimiteDeTentativas limite = new LimiteDeTentativas(4, Duration.ofMinutes(10), agora::get);

    private void passa(long segundos) {
        agora.updateAndGet(t -> t.plusSeconds(segundos));
    }

    @Test
    void pausaCrescenteEDepoisBloqueioAteARecusaMaisAntigaSairDaJanela() {
        String c = LimiteDeTentativas.chave("lab.exemplo", 22, "ana");
        assertTrue(limite.pausaRestante(c).isEmpty());
        limite.registrarFalha(c);
        assertTrue(limite.pausaRestante(c).isEmpty(), "depois da 1ª não há pausa");
        assertEquals(3, limite.restantes(c));
        passa(1);
        limite.registrarFalha(c);
        assertEquals(Duration.ofSeconds(5), limite.pausaRestante(c).orElseThrow(), "depois da 2ª, 5 s");
        passa(5);
        assertTrue(limite.pausaRestante(c).isEmpty());
        limite.registrarFalha(c);
        assertEquals(Duration.ofSeconds(15), limite.pausaRestante(c).orElseThrow(), "depois da 3ª, 15 s");
        passa(15);
        limite.registrarFalha(c);
        assertEquals(0, limite.restantes(c));
        passa(60);
        assertTrue(limite.pausaRestante(c).orElseThrow().toMinutes() >= 8, "com 4, só quando a mais antiga sair da janela: " + limite.pausaRestante(c));
        passa(8 * 60 + 40);
        assertTrue(limite.pausaRestante(c).isEmpty(), "a primeira saiu da janela");
        assertEquals(2, limite.restantes(c), "a 1ª e a 2ª saíram da janela");
    }

    @Test
    void naoZeraPorPassarUmaPausaCurta() {
        String c = LimiteDeTentativas.chave("lab", 22, "ana");
        for (int i = 0; i < 3; i++) {
            limite.registrarFalha(c);
            passa(30);
        }
        assertEquals(1, limite.restantes(c), "três recusas em 90 s seguem contando");
    }

    @Test
    void chaveNormalizadaEIndependenteDeOutrasContas() {
        assertEquals(LimiteDeTentativas.chave("LAB.Exemplo. ", 22, " Ana"), LimiteDeTentativas.chave("lab.exemplo", 22, "ana"));
        limite.registrarFalha(LimiteDeTentativas.chave("a", 22, "x"));
        assertEquals(4, limite.restantes(LimiteDeTentativas.chave("b", 22, "x")));
        assertEquals(4, limite.restantes(LimiteDeTentativas.chave("a", 2222, "x")));
    }

    @Test
    void sucessoZeraAConta() {
        String c = LimiteDeTentativas.chave("a", 22, "x");
        limite.registrarFalha(c);
        limite.registrarFalha(c);
        limite.registrarSucesso(c);
        assertEquals(4, limite.restantes(c));
        assertTrue(limite.pausaRestante(c).isEmpty());
    }
}
