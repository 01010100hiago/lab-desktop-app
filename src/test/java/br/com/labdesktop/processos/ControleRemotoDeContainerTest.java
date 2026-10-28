package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Container e serviço do systemd: o app age no container (docker stop e restart), nunca no processo, e nunca no agente do painel. */
class ControleRemotoDeContainerTest {

    private final List<List<String>> comandos = new ArrayList<>();
    private final List<Map<String, String>> ambientes = new ArrayList<>();
    private Function<List<String>, ResultadoExec> resposta = c -> ok("");

    private static ResultadoExec ok(String saida) {
        return new ResultadoExec(0, saida.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    private static ResultadoExec erro(int status, String stderr) {
        return new ResultadoExec(status, new byte[0], stderr.getBytes(StandardCharsets.UTF_8), false);
    }

    private ControleRemoto controle() {
        ExecutorDeArgumentos args = (argumentos, ambiente, stdin, prazo) -> {
            comandos.add(argumentos);
            ambientes.add(ambiente);
            return resposta.apply(argumentos);
        };
        return new ControleRemoto((c, s, p) -> {
            throw new AssertionError("container nunca usa o comando de sinal: " + c);
        }, args, AmbienteDeProcessos.servidor("labuser", Set.of()), ms -> { }, 2, 1);
    }

    private static ProcessoAlvo doContainer() {
        return new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja");
    }

    @Test
    void encerrarUmProcessoDeContainerRodaDockerStopNoContainer() throws Exception {
        ControleRemoto c = controle();
        Previa p = c.preparar(doContainer());
        assertEquals(Via.CONTAINER, p.oferta().via());
        assertTrue(comandos.isEmpty(), "preparar container não roda nada");
        Resultado r = c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, r.desfecho());
        assertEquals(List.of(List.of("docker", "stop", "-t", "10", "app-loja-web")), comandos);
        assertTrue(r.mensagem().contains("app-loja-web"));
    }

    @Test
    void reiniciarRodaDockerRestartNoContainer() throws Exception {
        ControleRemoto c = controle();
        Resultado r = c.reiniciar(c.preparar(doContainer()), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.REINICIADO, r.desfecho());
        assertEquals(List.of(List.of("docker", "restart", "-t", "10", "app-loja-web")), comandos);
    }

    @Test
    void semAcessoAoDockerMostraOComandoParaODonoSemSudoDoApp() throws Exception {
        resposta = c -> erro(1, "permission denied while trying to connect to the Docker daemon socket");
        ControleRemoto c = controle();
        Resultado r = c.encerrar(c.preparar(doContainer()), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.SEM_PERMISSAO, r.desfecho());
        assertEquals("sudo docker stop app-loja-web", r.comandoParaODono());
        assertTrue(comandos.stream().noneMatch(l -> l.contains("sudo")), "o app nunca roda sudo");
        Resultado rr = c.reiniciar(c.preparar(doContainer()), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals("sudo docker restart app-loja-web", rr.comandoParaODono());
    }

    @Test
    void containerQueNaoExisteMaisEJaSaiu() throws Exception {
        resposta = c -> erro(1, "Error response from daemon: No such container: app-loja-web");
        ControleRemoto c = controle();
        Resultado r = c.encerrar(c.preparar(doContainer()), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.JA_SAIU, r.desfecho());
        assertTrue(r.desfecho().deuCerto());
    }

    @Test
    void erroDoDockerVemComoTextoLimpo() throws Exception {
        resposta = c -> erro(1, "Error: algo \u001b[31mruim\u001b[0m deu errado em ghp_" + "a".repeat(36));
        ControleRemoto c = controle();
        Resultado r = c.encerrar(c.preparar(doContainer()), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertFalse(r.mensagem().contains("\u001b"));
        assertFalse(r.mensagem().contains("ghp_" + "a".repeat(36)), "nenhum token passa para a tela");
    }

    @Test
    void oAgenteDoPainelNuncaEParadoNemReiniciado() {
        ControleRemoto c = controle();
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> c.preparar(new ProcessoAlvo(900, "node", "root", "painel-lab", null)));
        assertEquals(Desfecho.PROTEGIDO, f.desfecho());
        assertTrue(comandos.isEmpty());
        // mesmo que alguém monte a prévia à mão, as ações também recusam
        Oferta protegida = c.avaliar(new ProcessoAlvo(900, "node", "root", "painel-lab", null));
        Previa montada = new Previa(new ProcessoAlvo(900, "node", "root", "painel-lab", null), protegida, null, null);
        assertEquals(Desfecho.PROTEGIDO, assertDoesNotThrow(() -> c.encerrar(montada, ObservadorDeProcesso.NENHUM, new Cancelamento())).desfecho());
        assertEquals(Desfecho.PROTEGIDO, assertDoesNotThrow(() -> c.reiniciar(montada, ObservadorDeProcesso.NENHUM, new Cancelamento())).desfecho());
        assertTrue(comandos.isEmpty(), "nenhum docker stop foi para o servidor");
    }

    private static <T> T assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<T> s) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(s);
    }

    @Test
    void nomeDeContainerForaDaRegraNaoChegaAoComando() {
        ControleRemoto c = controle();
        ProcessoAlvo ruim = new ProcessoAlvo(3100, "node", "node", "x; rm -rf /", null);
        assertThrows(IllegalArgumentException.class, () -> c.encerrar(new Previa(ruim, c.avaliar(ruim), null, null), ObservadorDeProcesso.NENHUM, new Cancelamento()));
        assertTrue(comandos.isEmpty());
    }

    @Test
    void servicoDoSystemdDoUsuarioPodeSerReiniciadoPeloSystemd() throws Exception {
        resposta = cmd -> {
            if (cmd.get(0).equals("ps")) {
                return ok("  4242  1000 Tue Oct 28 12:00:00 2026 meu-app\n");
            }
            if (cmd.get(0).equals("cat")) {
                return ok("0::/user.slice/user-1000.slice/user@1000.service/app.slice/meu-app.service\n");
            }
            return ok("");
        };
        ControleRemoto c = controle();
        Previa p = c.preparar(new ProcessoAlvo(4242, "meu-app", "labuser", null, null));
        assertEquals("meu-app.service", p.unidade());
        assertTrue(p.temServico());
        Resultado r = c.reiniciar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.REINICIADO, r.desfecho());
        assertEquals(List.of("systemctl", "--user", "restart", "meu-app.service"), comandos.get(comandos.size() - 1));
        assertEquals(Map.of("XDG_RUNTIME_DIR", "/run/user/1000"), ambientes.get(ambientes.size() - 1));
    }

    @Test
    void processoComumSemServicoNaoTemReiniciarEATelaDizPorque() throws Exception {
        resposta = cmd -> cmd.get(0).equals("ps") ? ok("  4242  1000 Tue Oct 28 12:00:00 2026 meu-app\n") : cmd.get(0).equals("cat") ? ok("0::/user.slice/session-3.scope\n") : ok("");
        ControleRemoto c = controle();
        Previa p = c.preparar(new ProcessoAlvo(4242, "meu-app", "labuser", null, null));
        assertFalse(p.temServico());
        Resultado r = c.reiniciar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(r.mensagem().contains("não sabe como religá-lo"));
    }

    @Test
    void detectarOServicoNaoDerrubaAPreviaSeOCatFalha() throws Exception {
        resposta = cmd -> {
            if (cmd.get(0).equals("ps")) {
                return ok("  4242  1000 Tue Oct 28 12:00:00 2026 meu-app\n");
            }
            if (cmd.get(0).equals("cat")) {
                throw new IllegalArgumentException("x");
            }
            return ok("");
        };
        assertNotNull(controle().preparar(new ProcessoAlvo(4242, "meu-app", "labuser", null, null)));
    }

    @Test
    void conexaoQueCaiNoMeioDaPreviaViraFalhaClara() {
        ExecutorDeArgumentos cai = (a, amb, in, prazo) -> {
            throw new IOException("canal fechado");
        };
        ControleRemoto c = new ControleRemoto((l, s, p) -> {
            throw new IOException("canal fechado");
        }, cai, AmbienteDeProcessos.servidor("labuser", Set.of()), ms -> { }, 2, 1);
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> c.preparar(new ProcessoAlvo(4242, "meu-app", "labuser", null, null)));
        assertEquals(Desfecho.FALHOU, f.desfecho());
        assertTrue(f.getMessage().contains("conexão"), f.getMessage());
    }

    @Test
    void prazoDoDockerEstouradoNaoEDadoComoParado() throws Exception {
        ExecutorDeArgumentos demora = (a, amb, in, prazo) -> {
            throw new br.com.labdesktop.ssh.ComandoDemorouException(60);
        };
        ControleRemoto c = new ControleRemoto((l, s, p) -> ok(""), demora, AmbienteDeProcessos.servidor("labuser", Set.of()), ms -> { }, 2, 1);
        Resultado r = c.encerrar(new Previa(doContainer(), c.avaliar(doContainer()), null, null), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(Duration.ofSeconds(60).toSeconds(), 60);
    }
}
