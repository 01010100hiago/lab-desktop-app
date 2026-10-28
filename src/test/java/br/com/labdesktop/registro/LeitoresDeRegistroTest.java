package br.com.labdesktop.registro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.local.ComandosLocais;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.local.ExecutorSoDeLeitura;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Os dois leitores do registro (SSH e local) com executores falsos: comandos fixos, permissão, fallback e falhas. */
class LeitoresDeRegistroTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final Instant AGORA = Instant.parse("2026-10-28T15:00:00Z");
    static final String JOURNAL = "2026-10-28T11:55:01-0300 lab sshd[1]: Accepted publickey\n2026-10-28T11:56:00-0300 lab sshd[2]: Failed password for root\n";

    static ResultadoExec saiu(int status, String out, String err) {
        return new ResultadoExec(status, out.getBytes(StandardCharsets.UTF_8), err.getBytes(StandardCharsets.UTF_8), false);
    }

    /** Um servidor falso: responde cada comando fixo com o que o teste programou, e anota todos. */
    static final class Servidor implements br.com.labdesktop.ssh.ProvisionadorRemoto.Executor {
        final List<String> comandos = new ArrayList<>();
        Function<String, ResultadoExec> resposta;
        IOException falha;

        @Override
        public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException {
            comandos.add(comando);
            if (falha != null) {
                throw falha;
            }
            return resposta.apply(comando);
        }
    }

    static LeitorDeRegistroPorSsh leitor(Servidor s, String usuario) {
        return new LeitorDeRegistroPorSsh(s, usuario, () -> AGORA, SP);
    }

    @Test
    void journalFuncionandoMostraMaisNovasEmCimaEDizDeOndeVem() throws Exception {
        Servidor s = new Servidor();
        s.resposta = c -> saiu(0, JOURNAL, "");
        ResultadoDoRegistro r = leitor(s, "labuser").ler();
        ResultadoDoRegistro.Lido lido = assertInstanceOf(ResultadoDoRegistro.Lido.class, r);
        assertEquals("do diário do sistema (journalctl)", lido.fonte());
        assertEquals(2, lido.linhas().size());
        assertEquals("Failed password for root", lido.linhas().get(0).mensagem(), "a mais nova em cima");
        assertEquals(List.of("journalctl -n 200 --no-pager -o short-iso"), s.comandos, "só o journalctl: deu certo de primeira");
    }

    @Test
    void osTresComandosSaoFixosSemSudoENadaDoUsuarioEntraNeles() throws Exception {
        assertEquals("journalctl -n 200 --no-pager -o short-iso", LeitorDeRegistroPorSsh.COMANDO_JOURNAL);
        assertEquals("tail -n 200 /var/log/syslog", LeitorDeRegistroPorSsh.COMANDO_SYSLOG);
        assertEquals("tail -n 200 /var/log/messages", LeitorDeRegistroPorSsh.COMANDO_MESSAGES);
        Servidor s = new Servidor();
        s.resposta = c -> saiu(1, "", "permission denied");
        leitor(s, "x; rm -rf /").ler();
        assertEquals(List.of(LeitorDeRegistroPorSsh.COMANDO_JOURNAL, LeitorDeRegistroPorSsh.COMANDO_SYSLOG, LeitorDeRegistroPorSsh.COMANDO_MESSAGES), s.comandos,
            "um usuário esquisito nunca vira comando: só os três de sempre");
        assertTrue(s.comandos.stream().noneMatch(c -> c.contains("sudo") || c.contains("x;")));
    }

    @Test
    void journalSoComAsMensagensDoProprioUsuarioCaiParaOArquivo() throws Exception {
        Servidor s = new Servidor();
        s.resposta = c -> switch (c) {
            case LeitorDeRegistroPorSsh.COMANDO_JOURNAL -> saiu(0, "-- No entries --\n", "Hint: You are currently not seeing messages from other users and the system.\n      Users in groups 'adm', 'systemd-journal' can see all messages.\n");
            case LeitorDeRegistroPorSsh.COMANDO_SYSLOG -> saiu(0, "Oct 28 11:55:01 lab sshd[1]: do arquivo syslog\n", "");
            default -> throw new AssertionError(c);
        };
        ResultadoDoRegistro.Lido lido = assertInstanceOf(ResultadoDoRegistro.Lido.class, leitor(s, "labuser").ler());
        assertEquals("do arquivo /var/log/syslog", lido.fonte());
        assertEquals("do arquivo syslog", lido.linhas().get(0).mensagem());
    }

    @Test
    void semJournalctlESemSyslogUsaMessages() throws Exception {
        Servidor s = new Servidor();
        s.resposta = c -> switch (c) {
            case LeitorDeRegistroPorSsh.COMANDO_JOURNAL -> saiu(127, "", "sh: 1: journalctl: not found");
            case LeitorDeRegistroPorSsh.COMANDO_SYSLOG -> saiu(1, "", "tail: cannot open '/var/log/syslog' for reading: No such file or directory");
            default -> saiu(0, "Oct 28 11:55:01 lab crond[1]: ok\n", "");
        };
        ResultadoDoRegistro.Lido lido = assertInstanceOf(ResultadoDoRegistro.Lido.class, leitor(s, "labuser").ler());
        assertEquals("do arquivo /var/log/messages", lido.fonte());
    }

    @Test
    void semPermissaoEmNenhumLugarDizAFraseEOComandoExatoParaODonoRodar() throws Exception {
        Servidor s = new Servidor();
        s.resposta = c -> switch (c) {
            case LeitorDeRegistroPorSsh.COMANDO_JOURNAL -> saiu(0, "", "Hint: You are currently not seeing messages from other users and the system.");
            case LeitorDeRegistroPorSsh.COMANDO_SYSLOG -> saiu(1, "", "tail: cannot open '/var/log/syslog' for reading: Permission denied");
            default -> saiu(1, "", "tail: cannot open '/var/log/messages' for reading: No such file or directory");
        };
        ResultadoDoRegistro.SemPermissao p = assertInstanceOf(ResultadoDoRegistro.SemPermissao.class, leitor(s, "labuser").ler());
        assertEquals("Seu usuário não tem permissão para ler o registro do sistema.", p.frase());
        assertEquals("sudo usermod -aG systemd-journal labuser", p.comandoParaCopiar());
        assertTrue(p.explicacao().contains("o app nunca pede nem usa senha de administrador"), p.explicacao());
        assertTrue(p.explicacao().contains("conecte de novo"), p.explicacao());
        assertEquals("sudo usermod -aG systemd-journal <seu-usuario>", LeitorDeRegistroPorSsh.comandoParaDarAcesso("Root!"), "nome que não é de usuário Linux vira o marcador");
        assertEquals("sudo usermod -aG systemd-journal <seu-usuario>", LeitorDeRegistroPorSsh.comandoParaDarAcesso(null));
        assertEquals("sudo usermod -aG systemd-journal deploy_01", LeitorDeRegistroPorSsh.comandoParaDarAcesso("deploy_01"));
    }

    @Test
    void servidorSemRegistroNenhumDizIndisponivelSemCulparAPermissao() throws Exception {
        Servidor s = new Servidor();
        s.resposta = c -> saiu(c.startsWith("journalctl") ? 127 : 1, "", c.startsWith("journalctl") ? "journalctl: not found" : "tail: No such file or directory");
        ResultadoDoRegistro.Indisponivel i = assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, leitor(s, "labuser").ler());
        assertTrue(i.frase().contains("journalctl") && i.frase().contains("/var/log/syslog"), i.frase());
    }

    @Test
    void semConexaoOuPrazoEstouradoELancaParaOSondadorDobrarOIntervalo() {
        Servidor s = new Servidor();
        s.falha = new IOException("Não há conexão SSH aberta com o servidor.");
        assertThrows(IOException.class, () -> leitor(s, "labuser").ler());
    }

    @Test
    void saidaTruncadaPeloTetoViraCortada() throws Exception {
        Servidor s = new Servidor();
        s.resposta = c -> new ResultadoExec(0, JOURNAL.getBytes(StandardCharsets.UTF_8), new byte[0], true);
        assertTrue(assertInstanceOf(ResultadoDoRegistro.Lido.class, leitor(s, "labuser").ler()).cortada());
    }

    // ---- local ----

    /** Um executor de processos falso que anota a lista de argumentos. */
    static final class Processos implements ExecutorDeProcesso {
        final List<List<String>> chamadas = new ArrayList<>();
        Resposta resposta;

        @FunctionalInterface
        interface Resposta {
            ResultadoExec de(List<String> argumentos) throws IOException;
        }

        @Override
        public ResultadoExec executar(List<String> argumentos, byte[] stdin, Duration prazo) throws IOException {
            chamadas.add(argumentos);
            return resposta.de(argumentos);
        }
    }

    @Test
    void localRodaDockerLogsComArgumentosEmListaFixosPorUmExecutorSoDeLeitura() throws Exception {
        Processos p = new Processos();
        p.resposta = a -> saiu(0, "2026-10-28T14:00:02Z node: dois\n", "2026-10-28T14:00:01Z [painel] um (do erro padrão)\n");
        LeitorDeRegistroLocal l = new LeitorDeRegistroLocal(new ExecutorSoDeLeitura(p), () -> "painel-lab");
        ResultadoDoRegistro.Lido lido = assertInstanceOf(ResultadoDoRegistro.Lido.class, l.ler());
        assertEquals(List.of(List.of("docker", "logs", "--tail", "200", "--timestamps", "painel-lab")), p.chamadas);
        assertTrue(ComandosLocais.ehLeitura(p.chamadas.get(0)), "o comando é uma LEITURA pela forma inteira");
        assertEquals(LeitorDeRegistroLocal.FONTE, lido.fonte());
        assertTrue(lido.fonte().contains("o registro do sistema deste computador não é lido"));
        assertEquals(2, lido.linhas().size());
        assertTrue(lido.linhas().get(0).mensagem().contains("dois"), "as duas saídas do Docker juntas, pela hora: a mais nova em cima");
        assertEquals("painel-lab", lido.linhas().get(0).origem());
    }

    @Test
    void localDizEmTextoSimplesPorQueNaoHaRegistro() throws Exception {
        Processos p = new Processos();
        LeitorDeRegistroLocal l = new LeitorDeRegistroLocal(p, () -> "painel-lab");
        p.resposta = a -> saiu(1, "", "Error response from daemon: No such container: painel-lab");
        assertTrue(assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, l.ler()).frase().contains("Preparar este computador"));
        p.resposta = a -> saiu(1, "", "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?");
        assertTrue(assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, l.ler()).frase().contains("Abra o Docker"));
        p.resposta = a -> saiu(1, "", "permission denied while trying to connect to the Docker daemon socket");
        assertTrue(assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, l.ler()).frase().contains("grupo docker"));
        p.resposta = a -> { throw new ProgramaNaoEncontradoException("docker"); };
        assertTrue(assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, l.ler()).frase().contains("Docker não está instalado"));
        p.resposta = a -> saiu(2, "", "algo que ninguém previu");
        assertEquals("Não consegui ler o registro do laboratório local agora.", assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, l.ler()).frase());
    }

    @Test
    void localComNomeDeContainerInvalidoNaoRodaNada() throws Exception {
        Processos p = new Processos();
        p.resposta = a -> saiu(0, "", "");
        for (String ruim : new String[] {"", "Painel Lab", "a;b", "../x", null, "-rf"}) {
            ResultadoDoRegistro r = new LeitorDeRegistroLocal(p, () -> ruim).ler();
            assertInstanceOf(ResultadoDoRegistro.Indisponivel.class, r, String.valueOf(ruim));
        }
        assertEquals(List.of(), p.chamadas, "nenhum processo nasceu");
        assertFalse(ComandosLocais.ehLeitura(List.of("docker", "logs", "--tail", "200", "--timestamps", "a;b")));
        assertFalse(ComandosLocais.ehLeitura(List.of("docker", "logs", "--tail", "9999", "--timestamps", "painel-lab")), "o 200 é fixo");
        assertFalse(ComandosLocais.ehLeitura(List.of("docker", "logs", "--follow", "--timestamps", "painel-lab")));
    }
}
