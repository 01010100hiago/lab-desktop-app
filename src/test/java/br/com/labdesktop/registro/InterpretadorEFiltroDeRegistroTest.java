package br.com.labdesktop.registro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O parser do registro (journal, syslog, docker), a severidade estimada e o filtro: valores conhecidos e o que o servidor pode mandar de ruim. */
class InterpretadorEFiltroDeRegistroTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final Instant AGORA = Instant.parse("2026-10-28T15:00:00Z");

    static final String JOURNAL = """
        -- No entries --
        2026-10-28T11:55:01-0300 lab sshd[1203]: Accepted publickey for labuser from 10.0.0.9 port 51234 ssh2
        2026-10-28T11:56:10-0300 lab kernel: usb 1-1: new high-speed USB device number 5
        2026-10-28T11:57:42-0300 lab CRON[4410]: pam_unix(cron:session): session opened for user root
        2026-10-28T11:58:00-0300 lab dockerd[900]: time="2026-10-28T14:58:00Z" level=warning msg="Your kernel does not support swap limit capabilities"
        2026-10-28T11:59:30-0300 lab sshd[1250]: Failed password for invalid user admin from 203.0.113.9 port 4000 ssh2
        """;

    @Test
    void journalComDataIsoComHoraProgramaMensagemESeveridade() {
        InterpretadorDeRegistro.Leitura l = InterpretadorDeRegistro.doSistema(JOURNAL, AGORA, SP);
        assertEquals(5, l.linhas().size(), "o cabeçalho -- No entries -- sai");
        assertFalse(l.cortada());
        LinhaDeRegistro primeira = l.linhas().get(0);
        assertEquals(Instant.parse("2026-10-28T14:55:01Z"), primeira.hora());
        assertEquals("11:55:01", primeira.horaEmTexto(SP));
        assertEquals("sshd", primeira.origem());
        assertEquals("Accepted publickey for labuser from 10.0.0.9 port 51234 ssh2", primeira.mensagem());
        assertEquals(Severidade.INFO, primeira.severidade());
        assertEquals("11:55:01  sshd  Accepted publickey for labuser from 10.0.0.9 port 51234 ssh2", primeira.paraCopiar(SP));
        assertEquals("kernel", l.linhas().get(1).origem(), "origem sem [pid]");
        assertEquals("CRON", l.linhas().get(2).origem());
        assertEquals(Severidade.AVISO, l.linhas().get(3).severidade());
        assertEquals(Severidade.ERRO, l.linhas().get(4).severidade(), "Failed password");
    }

    @Test
    void maisNovasEmCimaInverteSemMudarNada() {
        List<LinhaDeRegistro> l = InterpretadorDeRegistro.doSistema(JOURNAL, AGORA, SP).linhas();
        List<LinhaDeRegistro> r = InterpretadorDeRegistro.maisNovasEmCima(l);
        assertEquals(l.get(4), r.get(0));
        assertEquals(l.get(0), r.get(4));
        assertEquals("sshd", r.get(0).origem());
    }

    @Test
    void syslogTradicionalSemAnoNemFusoUsaOAnoDeAgoraEOFusoDoDono() {
        String saida = "Oct 28 11:55:01 lab sshd[1203]: Server listening on 0.0.0.0 port 22.\nOct  5 03:04:05 lab systemd[1]: Started Daily apt download activities.\n";
        List<LinhaDeRegistro> l = InterpretadorDeRegistro.doSistema(saida, AGORA, SP).linhas();
        assertEquals(2, l.size());
        // a mais antiga (5 de outubro) vem primeiro: a saída é reordenada pela hora
        assertEquals("systemd", l.get(0).origem());
        assertEquals(Instant.parse("2026-10-05T06:04:05Z"), l.get(0).hora());
        assertEquals(Instant.parse("2026-10-28T14:55:01Z"), l.get(1).hora());
    }

    @Test
    void syslogDeDezembroLidoEmJaneiroEDoAnoPassado() {
        Instant janeiro = Instant.parse("2027-01-02T15:00:00Z");
        LinhaDeRegistro l = InterpretadorDeRegistro.doSistema("Dec 31 23:59:59 lab cron[1]: fim do ano\n", janeiro, SP).linhas().get(0);
        assertEquals(Instant.parse("2027-01-01T02:59:59Z"), l.hora(), "31/12 de 2026 às 23:59:59 em São Paulo");
    }

    @Test
    void rsyslogComDataIsoEComMicrossegundosEntra() {
        LinhaDeRegistro l = InterpretadorDeRegistro.doSistema("2026-10-28T11:55:01.123456-03:00 lab nginx: [warn] 12#12: conflicting server name\n", AGORA, SP).linhas().get(0);
        assertEquals(Instant.parse("2026-10-28T14:55:01.123456Z"), l.hora());
        assertEquals("nginx", l.origem());
        assertEquals(Severidade.AVISO, l.severidade());
    }

    @Test
    void dockerLogsComTimestampsOrigemENomeDoContainer() {
        String saida = "2026-10-28T14:00:00.123456789Z node: tudo certo, escutando em 127.0.0.1:7070\n2026-10-28T14:00:05Z [painel] erro ao ler /proc/diskstats\n";
        InterpretadorDeRegistro.Leitura l = InterpretadorDeRegistro.doDocker(saida, "painel-lab");
        assertEquals(2, l.linhas().size());
        assertEquals("painel-lab", l.linhas().get(0).origem());
        assertEquals(Instant.parse("2026-10-28T14:00:00.123456789Z"), l.linhas().get(0).hora());
        assertEquals("node: tudo certo, escutando em 127.0.0.1:7070", l.linhas().get(0).mensagem());
        assertEquals(Severidade.ERRO, l.linhas().get(1).severidade());
    }

    @Test
    void saidaVaziaOuSoDeLixoNaoQuebra() {
        assertTrue(InterpretadorDeRegistro.doSistema(null, AGORA, SP).linhas().isEmpty());
        assertTrue(InterpretadorDeRegistro.doSistema("", AGORA, SP).linhas().isEmpty());
        assertTrue(InterpretadorDeRegistro.doSistema("-- No entries --\n\n   \n", AGORA, SP).linhas().isEmpty());
        assertTrue(InterpretadorDeRegistro.doDocker(null, "x").linhas().isEmpty());
        // linha sem hora (continuação de uma mensagem): entra sem hora, nada some
        List<LinhaDeRegistro> l = InterpretadorDeRegistro.doSistema("2026-10-28T11:55:01-0300 lab app[1]: caiu\n    at Object.<anonymous> (x.js:1)\n", AGORA, SP).linhas();
        assertEquals(2, l.size());
        assertNull(l.get(1).hora());
        assertEquals("—", l.get(1).horaEmTexto(SP));
        // data impossível não é hora
        assertNull(InterpretadorDeRegistro.doSistema("Feb 30 10:00:00 lab app: x\n", AGORA, SP).linhas().get(0).hora());
    }

    @Test
    void relogioForaDeOrdemEOrdenadoEAsLinhasSemHoraAcompanhamACimaDelas() {
        String saida = """
            2026-10-28T12:00:30-0300 lab a[1]: terceira
            2026-10-28T12:00:10-0300 lab b[1]: primeira
            continuação da primeira
            2026-10-28T12:00:20-0300 lab c[1]: segunda
            """;
        List<LinhaDeRegistro> l = InterpretadorDeRegistro.doSistema(saida, AGORA, SP).linhas();
        assertEquals(List.of("primeira", "continuação da primeira", "segunda", "terceira"), l.stream().map(LinhaDeRegistro::mensagem).toList());
    }

    @Test
    void textoDoServidorNuncaViraHtmlNemTrazControleBidiOuToken() {
        String token = "lab_" + "A".repeat(43);
        String saida = "2026-10-28T11:55:01-0300 lab app[1]: <html><b>oi</b></html> ‮evil‬ \u0007 sino \u001b[31mvermelho\u001b[0m Authorization: Bearer " + token + "\n";
        LinhaDeRegistro l = InterpretadorDeRegistro.doSistema(saida, AGORA, SP).linhas().get(0);
        assertFalse(l.mensagem().toLowerCase().startsWith("<html"), l.mensagem());
        assertFalse(l.mensagem().contains("‮") || l.mensagem().contains("\u0007") || l.mensagem().contains("\u001b"), "controles e bidi fora: " + l.mensagem());
        assertFalse(l.mensagem().contains(token), "nenhum token: " + l.mensagem());
        assertFalse(l.paraCopiar(SP).contains(token));
        LinhaDeRegistro puro = InterpretadorDeRegistro.doSistema("2026-10-28T11:55:01-0300 lab app[1]: <html>inteira\n", AGORA, SP).linhas().get(0);
        assertFalse(puro.mensagem().toLowerCase().startsWith("<html"), "um texto que começa com <html é neutralizado: " + puro.mensagem());
    }

    @Test
    void linhaGiganteViraNoMaximoDuasMilCaracteresELinhasDemaisSaemComOAviso() {
        String enorme = "2026-10-28T11:55:01-0300 lab app[1]: " + "x".repeat(50_000) + "\n";
        LinhaDeRegistro l = InterpretadorDeRegistro.doSistema(enorme, AGORA, SP).linhas().get(0);
        assertTrue(l.mensagem().length() <= InterpretadorDeRegistro.LIMITE_DA_LINHA, "tamanho " + l.mensagem().length());
        StringBuilder muitas = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            muitas.append("2026-10-28T11:55:").append(String.format("%02d", i % 60)).append("-0300 lab app[1]: linha ").append(i).append('\n');
        }
        InterpretadorDeRegistro.Leitura r = InterpretadorDeRegistro.doSistema(muitas.toString(), AGORA, SP);
        assertEquals(InterpretadorDeRegistro.LIMITE_DE_LINHAS, r.linhas().size());
        assertTrue(r.cortada());
    }

    @Test
    void severidadeEstimadaPeloTextoValoresConhecidos() {
        assertEquals(Severidade.ERRO, ClassificadorDeSeveridade.de("Out of memory: Killed process 1234 (node)"));
        assertEquals(Severidade.ERRO, ClassificadorDeSeveridade.de("level=error msg=\"boom\""));
        assertEquals(Severidade.ERRO, ClassificadorDeSeveridade.de("Erro ao conectar"));
        assertEquals(Severidade.ERRO, ClassificadorDeSeveridade.de("segfault at 0 ip 000"));
        assertEquals(Severidade.AVISO, ClassificadorDeSeveridade.de("WARNING: deprecated option"));
        assertEquals(Severidade.AVISO, ClassificadorDeSeveridade.de("connect: Connection refused"));
        assertEquals(Severidade.AVISO, ClassificadorDeSeveridade.de("request timed out"));
        assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de("Started Daily apt download activities."));
        assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de("Accepted publickey for labuser"));
        assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de("terror-free zone"), "só palavra inteira: 'terror' não é 'error'");
        assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de(null));
        assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de("   "));
        assertTrue(Severidade.ERRO.alemDe(Severidade.AVISO) && Severidade.AVISO.alemDe(Severidade.AVISO) && !Severidade.INFO.alemDe(Severidade.AVISO));
        assertEquals(List.of("Info", "Aviso", "Erro"), java.util.Arrays.stream(Severidade.values()).map(Severidade::palavra).toList());
    }

    @Test
    void filtroPorTextoEPorSeveridadeAvisosIncluemOsErros() {
        List<LinhaDeRegistro> l = InterpretadorDeRegistro.doSistema(JOURNAL, AGORA, SP).linhas();
        assertEquals(5, FiltroDeRegistro.NENHUM.aplicar(l).size());
        assertFalse(FiltroDeRegistro.NENHUM.ativo());
        assertEquals(2, new FiltroDeRegistro("", FiltroDeRegistro.Nivel.AVISOS).aplicar(l).size(), "o aviso do dockerd e o erro do sshd");
        assertEquals(1, new FiltroDeRegistro("", FiltroDeRegistro.Nivel.ERROS).aplicar(l).size());
        assertEquals(2, new FiltroDeRegistro("SSHD", FiltroDeRegistro.Nivel.TUDO).aplicar(l).size(), "sem diferenciar maiúscula, e vale para a origem");
        assertEquals(1, new FiltroDeRegistro("usb", FiltroDeRegistro.Nivel.TUDO).aplicar(l).size(), "e para a mensagem");
        assertEquals(1, new FiltroDeRegistro("  failed ", FiltroDeRegistro.Nivel.ERROS).aplicar(l).size(), "espaços nas pontas não contam");
        assertEquals(0, new FiltroDeRegistro("nada disto", FiltroDeRegistro.Nivel.TUDO).aplicar(l).size());
        assertTrue(new FiltroDeRegistro("x", null).ativo());
        assertEquals(List.of("Tudo", "Avisos", "Erros"), java.util.Arrays.stream(FiltroDeRegistro.Nivel.values()).map(FiltroDeRegistro.Nivel::rotulo).toList());
    }

    @Test
    void camposVaziosNomesDeServicoEEspacoEmDiscoSaemComASeveridadeCerta() {
        // o app de verdade escreve "error=<nil>" em linha de info, e o systemd cita serviços com a palavra no nome: nada disso é erro
        for (String info : new String[] {
            "level=info msg=\"proxy ok\" error=<nil> took=3ms",
            "{\"level\":\"info\",\"msg\":\"pronto\",\"error\":null}",
            "{\"level\":\"info\",\"error\": null,\"code\":200}",
            "Started error-pages.service.",
            "Stopping failed-units-check.timer.",
            "level=info errors=0 failed=0 retried=false",
            "ts=2026-10-28 level=info err=<nil>"}) {
            assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de(info), info);
        }
        // e o que É problema continua sendo
        for (String erro : new String[] {
            "level=error msg=\"falhou\" error=\"connection reset\"",
            "{\"level\":\"error\",\"error\":\"boom\"}",
            "docker-proxy: Failed to start container",
            "app: write /var/lib/x: No space left on device",
            "kernel: Out of memory: Killed process 123 (node)",
            "systemd[1]: foo.service: Failed with result 'exit-code'.",
            "EXT4-fs error (device sda1): ext4_find_entry"}) {
            assertEquals(Severidade.ERRO, ClassificadorDeSeveridade.de(erro), erro);
        }
        assertEquals(Severidade.AVISO, ClassificadorDeSeveridade.de("nginx: connection refused while connecting to upstream"));
        assertEquals(Severidade.AVISO, ClassificadorDeSeveridade.de("Restarting app.service"));
        assertEquals(Severidade.INFO, ClassificadorDeSeveridade.de("Started retry-timer.service"), "o hífen cola a palavra ao nome do serviço");
    }
}
