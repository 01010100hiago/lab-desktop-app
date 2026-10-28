package br.com.labdesktop.plataforma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Onde o app guarda as coisas em cada sistema: texto puro, provado nos três sistemas num sistema só (ARQUITETURA 11.4). */
class PastasDoAppTest {

    @Test
    void noMacFicaSobLibrary() {
        var a = AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of());
        var p = PastasDoApp.de(a);
        assertEquals("/Users/ana/Library/Application Support/LabDesktop", p.dados());
        assertEquals("/Users/ana/Library/Logs/LabDesktop", p.logs());
    }

    @Test
    void noWindowsOsDadosFicamEmAppdataELogsEmLocalappdata() {
        var p = PastasDoApp.de(AmbienteDoSistema.windowsPadrao("ana"));
        assertEquals("C:\\Users\\ana\\AppData\\Roaming\\LabDesktop", p.dados());
        assertEquals("C:\\Users\\ana\\AppData\\Local\\LabDesktop\\Logs", p.logs());
    }

    @Test
    void noWindowsAVariavelManda_eUsuarioComEspacoEAcentoFunciona() {
        var a = AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "D:\\Pessoas\\João Silva", "João Silva", Map.of(
            "APPDATA", "D:\\Perfis\\João Silva\\Roaming", "LOCALAPPDATA", "D:\\Perfis\\João Silva\\Local"));
        var p = PastasDoApp.de(a);
        assertEquals("D:\\Perfis\\João Silva\\Roaming\\LabDesktop", p.dados());
        assertEquals("D:\\Perfis\\João Silva\\Local\\LabDesktop\\Logs", p.logs());
    }

    @Test
    void noWindowsSemVariavelOuComValorQueNaoECaminhoUsaOPerfil() {
        for (Map<String, String> vars : java.util.List.of(Map.<String, String>of(), Map.of("APPDATA", "", "LOCALAPPDATA", "  "),
            Map.of("APPDATA", "Roaming", "LOCALAPPDATA", "..\\Local"))) {
            var p = PastasDoApp.de(AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", "ana", vars));
            assertEquals("C:\\Users\\ana\\AppData\\Roaming\\LabDesktop", p.dados());
            assertEquals("C:\\Users\\ana\\AppData\\Local\\LabDesktop\\Logs", p.logs());
        }
    }

    @Test
    void noLinuxSegueOXdgSoQuandoAbsoluto() {
        var padrao = PastasDoApp.de(AmbienteDoSistema.de(SistemaOperacional.LINUX, "/home/ana", "ana", Map.of()));
        assertEquals("/home/ana/.local/share/LabDesktop", padrao.dados());
        assertEquals("/home/ana/.local/state/LabDesktop", padrao.logs());

        var xdg = PastasDoApp.de(AmbienteDoSistema.de(SistemaOperacional.LINUX, "/home/ana", "ana",
            Map.of("XDG_DATA_HOME", "/dados/ana", "XDG_STATE_HOME", "/estado/ana")));
        assertEquals("/dados/ana/LabDesktop", xdg.dados());
        assertEquals("/estado/ana/LabDesktop", xdg.logs());

        var relativo = PastasDoApp.de(AmbienteDoSistema.de(SistemaOperacional.LINUX, "/home/ana", "ana", Map.of("XDG_DATA_HOME", "dados")));
        assertEquals("/home/ana/.local/share/LabDesktop", relativo.dados(), "a especificação manda ignorar valor relativo");
    }

    @Test
    void nenhumaPastaFicaDentroDaInstalacaoNemMisturaSeparadores() {
        for (SistemaOperacional so : SistemaOperacional.values()) {
            var a = so == SistemaOperacional.WINDOWS ? AmbienteDoSistema.windowsPadrao("ana")
                : AmbienteDoSistema.de(so, "/h/ana", "ana", Map.of());
            var p = PastasDoApp.de(a);
            String errado = so == SistemaOperacional.WINDOWS ? "/" : "\\";
            assertFalse(p.dados().contains(errado), so + ": separador trocado em " + p.dados());
            assertFalse(p.logs().contains(errado), so + ": separador trocado em " + p.logs());
            assertTrue(p.dados().endsWith("LabDesktop"));
            assertFalse(p.dados().contains("Program Files") || p.dados().startsWith("/Applications"), "a instalação nunca guarda dado do usuário");
        }
    }
}
