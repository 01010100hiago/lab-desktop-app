package br.com.labdesktop.ui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.CanalDeTerminal;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.TamanhoDeTerminal;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.util.Agendador;
import com.jediterm.core.util.TermSize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.core.CoreModuleProperties;
import org.junit.jupiter.api.Test;

/** O {@link SshTtyConnector} contra um servidor SSH embutido do MINA: leitura UTF-8, escrita só ativa, vigia de 5 s e redimensionar. */
class SshTtyConnectorTest {

    private static final Duration PRAZO = Duration.ofSeconds(10);

    /** Ouvinte que conta o que o conector avisa. */
    private static final class Avisos implements SshTtyConnector.Ouvinte {
        final AtomicInteger descartadas = new AtomicInteger();
        final AtomicInteger travadas = new AtomicInteger();
        final List<TamanhoDeTerminal> tamanhos = new CopyOnWriteArrayList<>();

        @Override
        public void aoDescartarTecla() {
            descartadas.incrementAndGet();
        }

        @Override
        public void aoRedimensionar(TamanhoDeTerminal tamanho) {
            tamanhos.add(tamanho);
        }

        @Override
        public void aoEscritaTravada() {
            travadas.incrementAndGet();
        }
    }

    private static CanalDeTerminal abrir(CenarioDeTerminal c) throws IOException {
        return new TransporteSsh(c.conexao).abrir(new ComandoDeTerminal.SessaoDoClaude("orbita"), new TamanhoDeTerminal(100, 30));
    }

    @Test
    void utf8CortadoEntrePacotesNaoViraLixo() throws Exception {
        String texto = "ação → ✓ 日本語 😀 fim";
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(s -> {
                // um byte por vez, descarregando a cada um: cada pacote SSH leva UM byte, então todo caractere multibyte é cortado
                for (byte b : bytes) {
                    s.escrever(new byte[] {b});
                    Thread.sleep(2);
                }
                s.sair(0);
            });
            CanalDeTerminal canal = abrir(c);
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", new Avisos());
            StringBuilder lido = new StringBuilder();
            char[] buf = new char[64];
            int n;
            while ((n = con.read(buf, 0, buf.length)) != -1) {
                lido.append(buf, 0, n);
            }
            assertEquals(texto, lido.toString());
            assertFalse(lido.toString().contains("�"), "nenhum caractere de substituição");
            assertEquals(0, con.waitFor());
        }
    }

    @Test
    void escreveBytesEStringsPeloCanalQuandoAtiva() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CanalDeTerminal canal = abrir(c);
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", new Avisos());
            con.write("olá ");
            con.write("mundo\n".getBytes(StandardCharsets.UTF_8));
            Espera.ate(PRAZO, () -> "recebido=" + c.terminal.recebidoComoTexto(), () -> c.terminal.recebidoComoTexto().equals("olá mundo\n"));
            assertTrue(con.isConnected());
            con.close();
            Espera.ate(PRAZO, "fechou", () -> !con.isConnected());
        }
    }

    @Test
    void foraDeAtivaWriteLancaEATeclaNuncaAparece() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CanalDeTerminal canal = abrir(c);
            AtomicBoolean ativa = new AtomicBoolean(true);
            Avisos avisos = new Avisos();
            SshTtyConnector con = new SshTtyConnector(canal, ativa::get, "t", avisos);

            con.write("a");
            Espera.ate(PRAZO, "a chegou", () -> c.terminal.recebidoComoTexto().equals("a"));

            ativa.set(false); // a queda: REANEXANDO
            for (String tecla : new String[] {"b", "c", "1\n"}) {
                assertThrows(IOException.class, () -> con.write(tecla));
            }
            assertThrows(IOException.class, () -> con.write(new byte[] {'\r'}));
            assertEquals(4, avisos.descartadas.get(), "cada tecla descartada é avisada à tela");

            ativa.set(true); // a sessão volta a ATIVA: o que foi digitado na queda NÃO volta
            con.write("d");
            Espera.ate(PRAZO, () -> "recebido=" + c.terminal.recebidoComoTexto(), () -> c.terminal.recebidoComoTexto().length() >= 2);
            Espera.naoAcontece(Duration.ofMillis(300), "chegou algo além de a e d", () -> c.terminal.recebidoComoTexto().length() > 2);
            assertEquals("ad", c.terminal.recebidoComoTexto(), "nada digitado na queda aparece depois");
        }
    }

    @Test
    void canalFechadoTambemDescartaMesmoComASessaoDizendoAtiva() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CanalDeTerminal canal = abrir(c);
            Avisos avisos = new Avisos();
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", avisos);
            canal.fechar();
            assertThrows(IOException.class, () -> con.write("x"));
            assertEquals(1, avisos.descartadas.get());
            assertFalse(con.isConnected());
        }
    }

    @Test
    void escritaQueTravaFechaOCanalEDestravaAThread() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CoreModuleProperties.WINDOW_SIZE.set(c.ssh.sshd, 4096L);
            CoreModuleProperties.MAX_PACKET_SIZE.set(c.ssh.sshd, 2048L);
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe); // o servidor não lê o teclado: a janela SSH enche
            CanalDeTerminal canal = abrir(c);
            Avisos avisos = new Avisos();
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", avisos, Duration.ofMillis(400), Agendador.doFundo());

            long inicio = System.nanoTime();
            IOException e = assertThrows(IOException.class, () -> con.write(new byte[512 * 1024]));
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            assertTrue(ms >= 350, "esperou o prazo: " + ms + " ms");
            assertTrue(ms < 8_000, "o vigia destravou a escrita: " + ms + " ms");
            assertTrue(e.getMessage().contains("travou"), e.getMessage());
            assertEquals(1, avisos.travadas.get());
            Espera.ate(PRAZO, "canal fechado", () -> !canal.aberto());
            assertThrows(IOException.class, () -> con.write("x"), "depois do fechamento, escrever é descartar");
        }
    }

    @Test
    void ovigiaDeEscritaPadraoEDeCincoSegundos() throws Exception {
        assertEquals(Duration.ofSeconds(5), SshTtyConnector.PRAZO_DE_ESCRITA);
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CoreModuleProperties.WINDOW_SIZE.set(c.ssh.sshd, 4096L);
            CoreModuleProperties.MAX_PACKET_SIZE.set(c.ssh.sshd, 2048L);
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
            CanalDeTerminal canal = abrir(c);
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", null); // construtor de produção: prazo e relógio padrão
            long inicio = System.nanoTime();
            assertThrows(IOException.class, () -> con.write(new byte[512 * 1024]));
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            assertTrue(ms >= 4_800 && ms < 12_000, "o canal fechou aos 5 s, não antes nem muito depois: " + ms + " ms");
            assertFalse(canal.aberto());
        }
    }

    @Test
    void escritaRapidaNaoDisparaOVigia() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CanalDeTerminal canal = abrir(c);
            Avisos avisos = new Avisos();
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", avisos, Duration.ofMillis(300), Agendador.doFundo());
            for (int i = 0; i < 50; i++) {
                con.write("x");
            }
            Espera.naoAcontece(Duration.ofMillis(700), "o vigia fechou um canal saudável", () -> avisos.travadas.get() > 0 || !canal.aberto());
        }
    }

    @Test
    void resizeChegaAoServidorComoWindowChange() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CanalDeTerminal canal = abrir(c);
            Avisos avisos = new Avisos();
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", avisos);
            con.resize(new TermSize(90, 25));
            Espera.ate(PRAZO, "window-change", () -> !c.terminal.janelas.isEmpty());
            assertEquals("90x25", c.terminal.janelas.get(0));
            assertEquals(List.of(new TamanhoDeTerminal(90, 25)), avisos.tamanhos);
            con.resize(new TermSize(132, 43));
            Espera.ate(PRAZO, "segundo window-change", () -> c.terminal.janelas.size() == 2);
            assertEquals("132x43", c.terminal.janelas.get(1));
        }
    }

    @Test
    void waitForDevolveOStatusEMenosUmSemStatus() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(ServidorDeTerminalFalso.imprimirESair("", 3));
            SshTtyConnector con = new SshTtyConnector(abrir(c), () -> true, "t", null);
            assertEquals(3, con.waitFor());
            assertEquals("t", con.getName());
        }
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
            CanalDeTerminal canal = abrir(c);
            SshTtyConnector con = new SshTtyConnector(canal, () -> true, "t", null);
            Espera.ate(PRAZO, "aberto", () -> c.terminal.canaisAbertos.get() == 1);
            con.close();
            assertEquals(-1, con.waitFor());
        }
    }

    @Test
    void readyDizSeHaTextoParaLer() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(s -> {
                Thread.sleep(300);
                s.escrever("oi");
                while (!s.saiu()) {
                    Thread.sleep(50);
                }
            });
            SshTtyConnector con = new SshTtyConnector(abrir(c), () -> true, "t", null);
            assertFalse(con.ready());
            Espera.ate(PRAZO, "texto disponível", () -> {
                try {
                    return con.ready();
                } catch (IOException e) {
                    return false;
                }
            });
            char[] buf = new char[8];
            assertEquals(2, con.read(buf, 0, buf.length));
            assertEquals("oi", new String(Arrays.copyOf(buf, 2)));
            con.close();
        }
    }
}
