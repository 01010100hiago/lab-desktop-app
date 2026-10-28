package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A medida da partida e a regra "a janelinha de abertura só aparece se a partida passa de 1 segundo". */
class MedidaDaPartidaTest {

    @Test
    void ateUmSegundoNaoPrecisaDeJanelinhaESemMedidaElaAparece() {
        assertFalse(MedidaDaPartida.passaDoLimite(Optional.of(0L)));
        assertFalse(MedidaDaPartida.passaDoLimite(Optional.of(640L)));
        assertFalse(MedidaDaPartida.passaDoLimite(Optional.of(1000L)), "exatamente 1 s ainda não passa");
        assertTrue(MedidaDaPartida.passaDoLimite(Optional.of(1001L)));
        assertTrue(MedidaDaPartida.passaDoLimite(Optional.of(4200L)));
        assertTrue(MedidaDaPartida.passaDoLimite(Optional.empty()), "sem medida: a janelinha aparece e esta partida grava a medida");
    }

    @Test
    void idaEVoltaGravaOQueMediu(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        assertEquals(Optional.empty(), MedidaDaPartida.ler(c));
        MedidaDaPartida.gravar(c, 1850);
        assertEquals(Optional.of(1850L), MedidaDaPartida.ler(c));
        MedidaDaPartida.gravar(c, 420);
        assertEquals(Optional.of(420L), MedidaDaPartida.ler(c));
        assertEquals("420", Files.readString(c.arquivoPartida()));
    }

    @Test
    void arquivoRuimNuncaLancaEVale_comoSemMedida(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        c.prepararPastas();
        for (String ruim : new String[] {"", "abc", "-5", "12.5", "99999999999999999999", "900000", "1e3", "\u0000\u0001"}) {
            Files.writeString(c.arquivoPartida(), ruim, StandardCharsets.UTF_8);
            assertEquals(Optional.empty(), MedidaDaPartida.ler(c), "'" + ruim + "' não é uma medida");
        }
        Files.writeString(c.arquivoPartida(), "x".repeat(5000), StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), MedidaDaPartida.ler(c), "arquivo grande demais é recusado antes de ler");
        Files.writeString(c.arquivoPartida(), " 730\n", StandardCharsets.UTF_8);
        assertEquals(Optional.of(730L), MedidaDaPartida.ler(c), "espaço e quebra de linha em volta não atrapalham");
    }
}
