package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.ControleRemoto;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.teste.ShellDeTeste;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Encerrar e forçar de ponta a ponta pelo servidor SSH embutido (MINA): o comando fixo chega por um canal {@code exec} de uma conexão de verdade, roda
 * no {@code sh} do computador de teste e age sobre um processo de verdade (um {@code sleep} aberto pelo teste). SIGTERM ignorado, depois SIGKILL.
 */
class ProcessosPeloSshEmbutidoTest {

    @TempDir
    Path tmp;

    private static int abrir(boolean teimoso) throws Exception {
        String script = (teimoso ? "trap \"\" TERM; " : "") + "sleep 120 >/dev/null 2>&1 & echo $!";
        Process p = new ProcessBuilder("/bin/sh", "-c", script).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor(10, TimeUnit.SECONDS);
        return Integer.parseInt(s);
    }

    private static boolean vivo(int pid) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", "ps -o stat= -p " + pid).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor(10, TimeUnit.SECONDS);
        return !s.isEmpty() && !s.startsWith("Z");
    }

    private static void matar(int pid) throws Exception {
        new ProcessBuilder("/bin/sh", "-c", "kill -KILL " + pid + " 2>/dev/null").start().waitFor(10, TimeUnit.SECONDS);
    }

    @Test
    void sigtermIgnoradoDepoisSigkillPeloCanalExecDoSshEmbutido() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int pid = abrir(true);
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            ShellDeTeste shell = new ShellDeTeste(tmp, c.ssh);
            c.conexao.iniciar();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);

            String eu = System.getProperty("user.name");
            ControleRemoto controle = new ControleRemoto(c.conexao::executar, br.com.labdesktop.projetos.ExecutorDeArgumentos.porSsh(c.conexao::executar),
                AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, 2, 2);
            Previa previa = controle.preparar(new ProcessoAlvo(pid, "sleep", eu, null, null));

            Resultado educado = controle.encerrar(previa, ObservadorDeProcesso.NENHUM, new Cancelamento());
            assertEquals(Desfecho.AINDA_RODANDO, educado.desfecho(), educado.mensagem());
            assertTrue(vivo(pid), "o SIGTERM foi ignorado e o app não forçou sozinho");

            Resultado forcado = controle.forcar(previa, ObservadorDeProcesso.NENHUM, new Cancelamento());
            assertEquals(Desfecho.ENCERRADO, forcado.desfecho(), forcado.mensagem());
            assertFalse(vivo(pid));

            List<String> linhas = c.ssh.comandos;
            assertTrue(linhas.stream().anyMatch(l -> l.contains("kill -TERM " + pid)), linhas.toString());
            assertTrue(linhas.stream().anyMatch(l -> l.contains("kill -KILL " + pid)), linhas.toString());
            assertTrue(linhas.stream().noneMatch(l -> l.contains("sudo")), "nenhum sudo pelo canal");
            assertTrue(c.ssh.entradas.stream().anyMatch(e -> e.startsWith(pid + " ")), "a identidade esperada viajou pelo stdin, não pela linha de comando");
            assertTrue(shell.recebidos.size() >= 3);
        } finally {
            matar(pid);
        }
    }

    @Test
    void numeroReaproveitadoNaoEMortoPeloCanalExec() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int pid = abrir(false);
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            new ShellDeTeste(tmp, c.ssh);
            c.conexao.iniciar();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            String eu = System.getProperty("user.name");
            ControleRemoto controle = new ControleRemoto(c.conexao::executar, br.com.labdesktop.projetos.ExecutorDeArgumentos.porSsh(c.conexao::executar),
                AmbienteDeProcessos.servidor(eu, Set.of()), ms -> { }, 2, 2);
            Previa real = controle.preparar(new ProcessoAlvo(pid, "sleep", eu, null, null));
            Previa velha = new Previa(real.alvo(), real.oferta(),
                new br.com.labdesktop.processos.IdentidadeDoProcesso(pid, real.identidade().uid(), "Mon Jan 1 00:00:00 2001", real.identidade().nome()), null);
            Resultado r = controle.encerrar(velha, ObservadorDeProcesso.NENHUM, new Cancelamento());
            assertEquals(Desfecho.MUDOU, r.desfecho(), r.mensagem());
            assertTrue(vivo(pid), "o processo que ficou com o número continua vivo");
        } finally {
            matar(pid);
        }
    }
}
