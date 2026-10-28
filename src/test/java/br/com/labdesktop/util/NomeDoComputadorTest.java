package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.plataforma.ConsultaDeSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NomeDoComputadorTest {

    @Test
    void devolveUmNomeUsavelSemQuebraDeLinha() {
        String nome = NomeDoComputador.hostname();
        assertFalse(nome.isBlank());
        assertFalse(nome.contains("\n") || nome.contains("\r"));
        // E dele sai sempre um nome de dispositivo aceito pelo agente.
        assertTrue(ValidadorDeNomes.dispositivoValido(ValidadorDeNomes.sugerirNomeDeDispositivo(nome)));
    }

    @Test
    void noWindowsVemDeComputernameSemRodarProcesso() {
        List<List<String>> perguntas = new ArrayList<>();
        String n = NomeDoComputador.hostname(br.com.labdesktop.plataforma.AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "", "", Map.of("COMPUTERNAME", "DESKTOP-ANA\r\n")), (c, prazo) -> {
            perguntas.add(c);
            return ConsultaDeSistema.Resposta.falhou();
        });
        assertEquals("DESKTOP-ANA", n);
        assertEquals(List.of(), perguntas, "nenhum processo");
    }

    @Test
    void noMacESemHostnameRodaOProgramaHostnameSemShell() {
        List<List<String>> perguntas = new ArrayList<>();
        String n = NomeDoComputador.hostname(br.com.labdesktop.plataforma.AmbienteDoSistema.de(SistemaOperacional.MAC, "", "", Map.of()), (c, prazo) -> {
            perguntas.add(c);
            return new ConsultaDeSistema.Resposta(0, "MacBook-da-Ana.local\n");
        });
        assertEquals("MacBook-da-Ana.local", n);
        assertEquals(List.of(List.of("hostname")), perguntas);
    }

    @Test
    void semNadaVale() {
        assertEquals("pc", NomeDoComputador.hostname(br.com.labdesktop.plataforma.AmbienteDoSistema.de(SistemaOperacional.LINUX, "", "", Map.of()), (c, prazo) -> ConsultaDeSistema.Resposta.falhou()));
        assertEquals("pc", NomeDoComputador.hostname(br.com.labdesktop.plataforma.AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "", "", Map.of("COMPUTERNAME", "  ")), (c, prazo) -> new ConsultaDeSistema.Resposta(0, "\n")));
    }
}
