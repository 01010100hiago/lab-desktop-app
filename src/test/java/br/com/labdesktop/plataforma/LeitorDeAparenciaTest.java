package br.com.labdesktop.plataforma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Claro e escuro do sistema nos três sistemas, com respostas FALSAS (nenhum processo roda): o que cada leitor pergunta e como entende a resposta. */
class LeitorDeAparenciaTest {

    /** Uma consulta falsa: anota o que foi perguntado e responde pelo que o teste programou. */
    static final class Falsa implements ConsultaDeSistema {
        final List<List<String>> perguntas = new ArrayList<>();
        final Function<List<String>, Resposta> responde;

        Falsa(Function<List<String>, Resposta> responde) {
            this.responde = responde;
        }

        @Override
        public Resposta perguntar(List<String> comando, Duration prazo) {
            perguntas.add(comando);
            return responde.apply(comando);
        }
    }

    private static final AmbienteDoSistema WINDOWS = AmbienteDoSistema.windowsPadrao("ana");
    private static final AmbienteDoSistema MAC = AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of());
    private static final AmbienteDoSistema LINUX = AmbienteDoSistema.de(SistemaOperacional.LINUX, "/home/ana", "ana", Map.of());

    private static String reg(int valor) {
        return "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n    AppsUseLightTheme    REG_DWORD    0x" + Integer.toHexString(valor) + "\r\n\r\n";
    }

    @Test
    void windowsEscuroQuandoAppsUseLightThemeEZeroEClaroQuandoEUm() {
        assertTrue(LeitorDeAparencia.escuro(WINDOWS, new Falsa(c -> new ConsultaDeSistema.Resposta(0, reg(0)))));
        assertFalse(LeitorDeAparencia.escuro(WINDOWS, new Falsa(c -> new ConsultaDeSistema.Resposta(0, reg(1)))));
    }

    @Test
    void windowsPerguntaORegExeDaPastaDoSistemaComArgumentosEmListaFixos() {
        Falsa f = new Falsa(c -> new ConsultaDeSistema.Resposta(0, reg(0)));
        LeitorDeAparencia.escuro(WINDOWS, f);
        assertEquals(List.of(List.of("C:\\Windows\\System32\\reg.exe", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")), f.perguntas);
        // SystemRoot em outro disco vale; um valor que não é caminho absoluto não vale (nada de PATH nem de pasta relativa)
        var outro = AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "D:\\Users\\ana", "ana", Map.of("SystemRoot", "D:\\WINNT"));
        assertEquals("D:\\WINNT\\System32\\reg.exe", LeitorDeAparencia.comandoDoWindows(outro).get(0));
        var ruim = AmbienteDoSistema.de(SistemaOperacional.WINDOWS, "C:\\Users\\ana", "ana", Map.of("SystemRoot", "..\\x"));
        assertEquals("C:\\Windows\\System32\\reg.exe", LeitorDeAparencia.comandoDoWindows(ruim).get(0));
    }

    @Test
    void windowsToleraFalhaChaveAusenteELixo() {
        for (ConsultaDeSistema.Resposta r : List.of(ConsultaDeSistema.Resposta.falhou(), new ConsultaDeSistema.Resposta(1, "ERROR: The system was unable to find the specified registry key or value."),
            new ConsultaDeSistema.Resposta(0, ""), new ConsultaDeSistema.Resposta(0, "lixo qualquer"), new ConsultaDeSistema.Resposta(0, "AppsUseLightTheme REG_DWORD 0xZZ"))) {
            assertFalse(LeitorDeAparencia.escuro(WINDOWS, new Falsa(c -> r)), r.toString());
        }
        // saída em português ou com tabs também é lida (o valor é o que importa)
        assertTrue(LeitorDeAparencia.interpretarWindows(0, "    AppsUseLightTheme\tREG_DWORD\t0x0"));
        assertTrue(LeitorDeAparencia.interpretarWindows(0, "AppsUseLightTheme    REG_DWORD    0x00000000"));
    }

    @Test
    void macEscuroSoComStatusZeroEPalavraDark() {
        Falsa f = new Falsa(c -> new ConsultaDeSistema.Resposta(0, "Dark\n"));
        assertTrue(LeitorDeAparencia.escuro(MAC, f));
        assertEquals(List.of(List.of("defaults", "read", "-g", "AppleInterfaceStyle")), f.perguntas);
        assertFalse(LeitorDeAparencia.escuro(MAC, new Falsa(c -> new ConsultaDeSistema.Resposta(1, "The domain/default pair of (kCFPreferencesAnyApplication, AppleInterfaceStyle) does not exist"))));
        assertFalse(LeitorDeAparencia.escuro(MAC, new Falsa(c -> ConsultaDeSistema.Resposta.falhou())));
        assertFalse(LeitorDeAparencia.interpretarMac(0, "Light"));
        assertFalse(LeitorDeAparencia.interpretarMac(0, null));
    }

    @Test
    void linuxLeOEsquemaDeCoresDoGnomeEUsaONomeDoTemaSoSeNaoHouverEsquema() {
        assertTrue(LeitorDeAparencia.escuro(LINUX, new Falsa(c -> new ConsultaDeSistema.Resposta(0, "'prefer-dark'\n"))));
        assertFalse(LeitorDeAparencia.escuro(LINUX, new Falsa(c -> new ConsultaDeSistema.Resposta(0, "'prefer-light'\n"))));
        Falsa padrao = new Falsa(c -> c.contains("gtk-theme") ? new ConsultaDeSistema.Resposta(0, "'Adwaita-dark'\n") : new ConsultaDeSistema.Resposta(0, "'default'\n"));
        assertFalse(LeitorDeAparencia.escuro(LINUX, padrao), "o GNOME respondeu 'default': vale o claro, sem olhar o nome do tema");
        Falsa semEsquema = new Falsa(c -> c.contains("gtk-theme") ? new ConsultaDeSistema.Resposta(0, "'Yaru-dark'\n") : ConsultaDeSistema.Resposta.falhou());
        assertTrue(LeitorDeAparencia.escuro(LINUX, semEsquema));
        assertEquals(2, semEsquema.perguntas.size());
        assertFalse(LeitorDeAparencia.escuro(LINUX, new Falsa(c -> ConsultaDeSistema.Resposta.falhou())), "sem GNOME: claro");
    }

    @Test
    void nenhumComandoTemShellNemTextoDeUsuario() {
        List<List<String>> todos = List.of(LeitorDeAparencia.comandoDoMac(), LeitorDeAparencia.comandoDoWindows(WINDOWS), LeitorDeAparencia.comandoDoLinuxEsquema(), LeitorDeAparencia.comandoDoLinuxTema());
        for (List<String> c : todos) {
            for (String a : c) {
                assertFalse(a.contains("\"") || a.contains(";") || a.contains("|") || a.contains("&") || a.equals("sh") || a.equals("cmd") || a.equals("powershell"), c.toString());
            }
        }
        var comNomeEstranho = AmbienteDoSistema.windowsPadrao("a & calc");
        assertEquals(LeitorDeAparencia.comandoDoWindows(WINDOWS), LeitorDeAparencia.comandoDoWindows(comNomeEstranho), "o nome do usuário nunca entra no comando");
    }
}
