package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.junit.jupiter.api.Test;

/** O canal com PTY de {@link ConexaoSsh} (ARQUITETURA 7.1) contra um servidor SSH embutido. */
class ConexaoSshPtyTest {

    private static final Duration PRAZO = Duration.ofSeconds(10);

    private static String ler(InputStream in, int quantos) throws IOException {
        byte[] b = in.readNBytes(quantos);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void sessaoDoClaudeRodaALinhaFixaComPtyXtermETamanhoInicial() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            PtyCapableChannelSession canal = c.conexao.abrirCanalPty("orbita", 100, 30);
            try {
                Espera.ate(PRAZO, "o servidor abriu o canal", () -> c.terminal.canaisAbertos.get() == 1);
                assertEquals("docker exec -it -e LANG=C.UTF-8 -e TERM=xterm-256color -e COLORTERM=truecolor lab-claude-orbita"
                    + " tmux -u set-option -t work mouse on ';' attach -t work", c.terminal.comandos.get(0));
                assertEquals("xterm-256color", c.terminal.tiposDeTerminal.get(0));
                assertEquals("100x30", c.terminal.tamanhosIniciais.get(0));
            } finally {
                canal.close(true);
            }
        }
    }

    @Test
    void donoExclusivoLigaAttachDeSoNaLinha() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            PtyCapableChannelSession canal = c.conexao.abrirCanalPty("orbita", true, 120, 32);
            try {
                Espera.ate(PRAZO, "o servidor abriu o canal", () -> c.terminal.canaisAbertos.get() == 1);
                assertTrue(c.terminal.comandos.get(0).endsWith("mouse on ';' attach -d -t work"), c.terminal.comandos.get(0));
            } finally {
                canal.close(true);
            }
        }
    }

    @Test
    void teclasIdaEVoltaEWindowChangeChegaComoSigwinch() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            PtyCapableChannelSession canal = c.conexao.abrirCanalPty("orbita", 120, 32);
            try {
                OutputStream teclado = canal.getInvertedIn();
                teclado.write("olá\n".getBytes(StandardCharsets.UTF_8));
                teclado.flush();
                assertEquals("olá\n", ler(canal.getInvertedOut(), "olá\n".getBytes(StandardCharsets.UTF_8).length));

                canal.sendWindowChange(90, 25);
                Espera.ate(PRAZO, "window-change chegou", () -> !c.terminal.janelas.isEmpty());
                assertEquals("90x25", c.terminal.janelas.get(0));
            } finally {
                canal.close(true);
            }
        }
    }

    @Test
    void nomeDeProjetoRuimNuncaChegaAoServidor() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            for (String ruim : new String[] {"a;b", "", "ORBITA", "-orbita", "a b", "a".repeat(50), "a$(id)", "lab\nid", null}) {
                assertThrows(IllegalArgumentException.class, () -> c.conexao.abrirCanalPty(ruim, 120, 32), "aceitou: " + ruim);
            }
            assertEquals(0, c.terminal.canaisAbertos.get());
            assertTrue(c.terminal.comandos.isEmpty(), "nada do nome ruim pode ter virado comando");
            // o limite da regra: 49 caracteres passam
            PtyCapableChannelSession ok = c.conexao.abrirCanalPty("a".repeat(49), 120, 32);
            ok.close(true);
            Espera.ate(PRAZO, "o servidor abriu o canal", () -> c.terminal.canaisAbertos.get() == 1);
            assertTrue(c.terminal.comandos.get(0).contains("lab-claude-" + "a".repeat(49) + " tmux"));
        }
    }

    @Test
    void shellDoServidorAbreUmLoginShellComPty() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            PtyCapableChannelSession canal = c.conexao.abrirShellPty(80, 24);
            try {
                Espera.ate(PRAZO, "o servidor abriu o shell", () -> c.terminal.shellsAbertos.get() == 1);
                assertTrue(c.terminal.comandos.isEmpty(), "o shell não é um exec");
                assertEquals("xterm-256color", c.terminal.tiposDeTerminal.get(0));
                assertEquals("80x24", c.terminal.tamanhosIniciais.get(0));
            } finally {
                canal.close(true);
            }
        }
    }

    @Test
    void semConexaoAbrirCanalFalhaComIoExceptionEOShellTambem() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.conexao.bloquear();
            Espera.ate(PRAZO, "bloqueou", () -> c.conexao.info().estado() == EstadoConexao.BLOQUEADO_COFRE);
            assertThrows(IOException.class, () -> c.conexao.abrirCanalPty("orbita", 120, 32));
            assertThrows(IOException.class, () -> c.conexao.abrirShellPty(120, 32));
        }
    }

    @Test
    void servidorQueRecusaMaisUmaSessaoViraLimiteDeSessoes() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.limitarSessoesA(1);
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
            PtyCapableChannelSession primeiro = c.conexao.abrirShellPty(80, 24);
            try {
                Espera.ate(PRAZO, "primeiro aberto", () -> c.terminal.shellsAbertos.get() == 1);
                LimiteDeSessoesException e = assertThrows(LimiteDeSessoesException.class, () -> c.conexao.abrirShellPty(80, 24));
                assertTrue(e.getMessage().contains("limite de sessões"), e.getMessage());
                assertEquals(EstadoConexao.CONECTADO, c.conexao.info().estado(), "recusar um canal não derruba a conexão");
            } finally {
                primeiro.close(true);
            }
        }
    }
}
