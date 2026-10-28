package br.com.labdesktop.plataforma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O prazo da consulta vale de verdade: um programa que trava (não escreve e não sai) não segura quem pergunta, e programa inexistente é só "falhou". */
class ConsultaPorProcessoTest {

    /** Processo filho de teste: ou escreve e sai, ou trava em silêncio. */
    public static final class Filho {
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("trava")) {
                Thread.sleep(60_000);
            } else {
                System.out.print("resposta-curta");
            }
        }
    }

    private static List<String> filho(String modo) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String cp = Path.of(Filho.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-Xshare:auto", "-cp", cp, Filho.class.getName(), modo);
    }

    @Test
    void respostaNormalVoltaComStatusESaida() throws Exception {
        var r = new ConsultaPorProcesso().perguntar(filho("fala"), Duration.ofSeconds(30));
        assertTrue(r.ok(), r.toString());
        assertEquals("resposta-curta", r.saida());
    }

    @Test
    void processoQueTravaEmSilencioEstouraOPrazoSemSegurarQuemPergunta() throws Exception {
        List<String> comando = filho("trava");
        long inicio = System.nanoTime();
        var r = new ConsultaPorProcesso().perguntar(comando, Duration.ofMillis(1500));
        long gastoMs = (System.nanoTime() - inicio) / 1_000_000;
        assertFalse(r.ok());
        assertEquals(-1, r.status());
        assertTrue(gastoMs < 10_000, "a consulta travada segurou quem perguntou por " + gastoMs + " ms (o prazo era 1,5 s)");
    }

    @Test
    void programaInexistenteEApenasFalhou() {
        var r = new ConsultaPorProcesso().perguntar(List.of("programa-que-nao-existe-" + System.nanoTime()), Duration.ofSeconds(2));
        assertEquals(ConsultaDeSistema.Resposta.falhou(), r);
    }
}
