package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.TransporteDeTerminal.Disponibilidade;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** O {@link TransporteSsh} contra o servidor SSH embutido: o mesmo canal que a interface usa, sem o JediTerm. */
class TransporteSshTest {

    private static final Duration PRAZO = Duration.ofSeconds(10);

    @Test
    void abreASessaoDoClaudeComALinhaFixaETecladoIdaEVolta() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            TransporteSsh t = new TransporteSsh(c.conexao);
            CanalDeTerminal canal = t.abrir(new ComandoDeTerminal.SessaoDoClaude("orbita"), new TamanhoDeTerminal(100, 30));
            try {
                Espera.ate(PRAZO, "o servidor abriu o canal", () -> c.terminal.canaisAbertos.get() == 1);
                assertTrue(c.terminal.comandos.get(0).contains("lab-claude-orbita tmux -u attach -t work"));
                assertEquals("100x30", c.terminal.tamanhosIniciais.get(0));
                canal.entrada().write("é\n".getBytes(StandardCharsets.UTF_8));
                canal.entrada().flush();
                assertEquals("é\n", new String(canal.saida().readNBytes(3), StandardCharsets.UTF_8));
                canal.redimensionar(new TamanhoDeTerminal(88, 22));
                Espera.ate(PRAZO, "window-change", () -> !c.terminal.janelas.isEmpty());
                assertEquals("88x22", c.terminal.janelas.get(0));
            } finally {
                canal.fechar();
            }
        }
    }

    @Test
    void donoExclusivoVemDaPreferenciaACadaAbertura() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            boolean[] exclusivo = {false};
            TransporteSsh t = new TransporteSsh(c.conexao, () -> exclusivo[0]);
            t.abrir(new ComandoDeTerminal.SessaoDoClaude("a"), TamanhoDeTerminal.PADRAO).fechar();
            exclusivo[0] = true;
            t.abrir(new ComandoDeTerminal.SessaoDoClaude("a"), TamanhoDeTerminal.PADRAO).fechar();
            Espera.ate(PRAZO, "dois comandos", () -> c.terminal.comandos.size() == 2);
            assertFalse(c.terminal.comandos.get(0).contains(" -d "));
            assertTrue(c.terminal.comandos.get(1).contains("attach -d -t work"));
        }
    }

    @Test
    void abreOShellDoServidor() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            CanalDeTerminal canal = new TransporteSsh(c.conexao).abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
            try {
                Espera.ate(PRAZO, "shell", () -> c.terminal.shellsAbertos.get() == 1);
                assertTrue(canal.aberto());
            } finally {
                canal.fechar();
            }
            Espera.ate(PRAZO, "fechou", () -> !canal.aberto());
        }
    }

    @Test
    void aguardarFimDevolveOStatusDeSaidaDoServidor() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(ServidorDeTerminalFalso.imprimirESair("adeus\r\n", 7));
            CanalDeTerminal canal = new TransporteSsh(c.conexao).abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
            assertEquals("adeus\r\n", new String(canal.saida().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(7, canal.aguardarFim());
            assertEquals(7, canal.statusDeSaida());
            assertFalse(canal.aberto());
        }
    }

    @Test
    void fecharAvisaOServidorParaOTmuxNaoFicarOrfao() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
            CanalDeTerminal canal = new TransporteSsh(c.conexao).abrir(new ComandoDeTerminal.SessaoDoClaude("orbita"), TamanhoDeTerminal.PADRAO);
            Espera.ate(PRAZO, "aberto", () -> c.terminal.canaisAbertos.get() == 1);
            canal.fechar();
            assertFalse(canal.aberto());
            // o servidor ficou sabendo (EOF e CLOSE chegaram): o docker exec e o cliente do tmux recebem o hangup e saem
            Espera.ate(PRAZO, () -> "encerrados=" + c.terminal.canaisEncerrados.get(), () -> c.terminal.canaisEncerrados.get() == 1);
            assertTrue(c.terminal.sessoes.get(0).saiu());
            canal.fechar(); // idempotente
        }
    }

    @Test
    void abortarFechaAgoraDoLadoDoApp() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
            CanalDeTerminal canal = new TransporteSsh(c.conexao).abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
            Espera.ate(PRAZO, "aberto", () -> c.terminal.canaisAbertos.get() == 1);
            canal.abortar();
            assertFalse(canal.aberto());
            assertEquals(null, canal.aguardarFim(), "sem status: foi arrancado, não terminou");
        }
    }

    @Test
    void canalFechadoSemStatusDevolveNulo() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
            CanalDeTerminal canal = new TransporteSsh(c.conexao).abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
            Espera.ate(PRAZO, "shell", () -> c.terminal.shellsAbertos.get() == 1);
            canal.fechar();
            assertEquals(null, canal.aguardarFim());
        }
    }

    @Test
    void recusaDoServidorViraTerminalRecusadoSemLaco() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            c.terminal.limitarSessoesA(0);
            IOException e = assertThrows(IOException.class,
                () -> new TransporteSsh(c.conexao).abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO));
            assertInstanceOf(TerminalRecusadoException.class, e);
            assertTrue(e.getMessage().contains("limite de sessões"));
        }
    }

    @Test
    void disponibilidadeAcompanhaAConexaoESoOBloqueioEDefinitivo() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.conectado()) {
            List<Disponibilidade> vistas = new CopyOnWriteArrayList<>();
            Runnable desfaz = new TransporteSsh(c.conexao).observarDisponibilidade(vistas::add);
            Espera.ate(PRAZO, "primeiro valor", () -> !vistas.isEmpty());
            assertEquals(Disponibilidade.DISPONIVEL, vistas.get(0));
            c.conexao.bloquear();
            Espera.ate(PRAZO, () -> "vistas=" + vistas, () -> vistas.contains(Disponibilidade.BLOQUEADO));
            desfaz.run();
        }
    }

    @Test
    void oTerminalAbreEEscreveComOAgenteForaDoAr() throws Exception {
        try (CenarioDeTerminal c = CenarioDeTerminal.semAgente()) {
            assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, c.conexao.info().estado());
            List<Disponibilidade> vistas = new CopyOnWriteArrayList<>();
            TransporteSsh t = new TransporteSsh(c.conexao);
            t.observarDisponibilidade(vistas::add);
            Espera.ate(PRAZO, "primeiro valor", () -> !vistas.isEmpty());
            assertEquals(Disponibilidade.DISPONIVEL, vistas.get(0), "sem agente o terminal continua disponível");

            CanalDeTerminal canal = t.abrir(new ComandoDeTerminal.SessaoDoClaude("orbita"), TamanhoDeTerminal.PADRAO);
            canal.entrada().write("oi\n".getBytes(StandardCharsets.UTF_8));
            canal.entrada().flush();
            assertEquals("oi\n", new String(canal.saida().readNBytes(3), StandardCharsets.UTF_8));
            canal.fechar();
            assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, c.conexao.info().estado(), "abrir e fechar o terminal não mexe na conexão");
        }
    }

    @Test
    void oTerminalNaoDependeDoAgente() {
        assertEquals(Disponibilidade.DISPONIVEL, TransporteSsh.deEstado(EstadoConexao.SSH_OK_SEM_AGENTE));
        assertEquals(Disponibilidade.DISPONIVEL, TransporteSsh.deEstado(EstadoConexao.CONECTADO));
        assertEquals(Disponibilidade.DISPONIVEL, TransporteSsh.deEstado(EstadoConexao.TOKEN_RECUSADO));
        assertEquals(Disponibilidade.DISPONIVEL, TransporteSsh.deEstado(EstadoConexao.AGUARDANDO_2FA));
        assertEquals(Disponibilidade.INDISPONIVEL, TransporteSsh.deEstado(EstadoConexao.RECONECTANDO));
        assertEquals(Disponibilidade.INDISPONIVEL, TransporteSsh.deEstado(EstadoConexao.SUSPENSO));
        assertEquals(Disponibilidade.INDISPONIVEL, TransporteSsh.deEstado(EstadoConexao.ERRO_AUTENTICACAO));
        assertEquals(Disponibilidade.BLOQUEADO, TransporteSsh.deEstado(EstadoConexao.BLOQUEADO_COFRE));
    }
}
