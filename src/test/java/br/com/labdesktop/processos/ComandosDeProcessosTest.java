package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Os comandos de Processos são fixos: só um inteiro, um nome de container e um nome de unidade entram, e nenhum usa sudo. */
class ComandosDeProcessosTest {

    @Test
    void leituraTemOPidValidadoEPassaNaRegraDaLinhaRemota() {
        List<String> c = ComandosDeProcessos.lerIdentidade(4242);
        assertEquals(List.of("ps", "-o", "pid=,uid=,lstart=,comm=", "-p", "4242"), c);
        assertEquals("env LC_ALL=C ps -o pid=,uid=,lstart=,comm= -p 4242", ExecutorDeArgumentos.linhaRemota(c, ComandosDeProcessos.ambienteDaLeitura()));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.lerIdentidade(1));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.lerIdentidade(-1));
    }

    @Test
    void oComandoDoSinalConfereAIdentidadeEEnviaNoMesmoComando() {
        String t = ComandosDeProcessos.enviarSinal(4242, Sinal.TERM);
        assertTrue(t.startsWith("sh -c '") && t.endsWith("'"));
        assertTrue(t.contains("kill -TERM 4242") && t.contains("ps -o pid=,uid=,lstart=,comm= -p 4242"));
        assertTrue(t.indexOf("ps -o") < t.indexOf("kill -TERM"), "primeiro confere, depois envia");
        assertTrue(t.contains("read -r e"), "a identidade esperada vem pelo stdin, nunca pela linha");
        assertTrue(ComandosDeProcessos.enviarSinal(4242, Sinal.KILL).contains("kill -KILL 4242"));
        assertFalse(t.substring(7, t.length() - 1).contains("'"), "sem aspas simples por dentro: funciona em qualquer shell de login");
        assertFalse(t.contains("sudo"), "nunca sudo");
        assertTrue(t.chars().noneMatch(ch -> ch < 0x20), "sem caractere de controle (o ExecRemoto recusa)");
    }

    @Test
    void sinalComPidInvalidoNemMontaALinha() {
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.enviarSinal(1, Sinal.KILL));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.enviarSinal(0, Sinal.TERM));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.enviarSinal(-1, Sinal.KILL));
    }

    @Test
    void containerPassaPelaRegraDoDocker() {
        assertEquals(List.of("docker", "stop", "-t", "10", "app-loja-web"), ComandosDeProcessos.pararContainer("app-loja-web"));
        assertEquals(List.of("docker", "restart", "-t", "10", "lab-claude-x"), ComandosDeProcessos.reiniciarContainer("lab-claude-x"));
        for (String ruim : new String[] {"", "a b", "a;b", "$(id)", "-rf", "a/b", "nome\nnovo", "`x`", "a&b", "a|b", "x".repeat(200)}) {
            assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.pararContainer(ruim), ruim);
            assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.reiniciarContainer(ruim), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.pararContainer(null));
    }

    @Test
    void servicoDoUsuarioPassaPelaRegraDeUnidade() {
        assertEquals(List.of("systemctl", "--user", "restart", "meu-app.service"), ComandosDeProcessos.reiniciarServicoDoUsuario("meu-app.service"));
        assertEquals("env XDG_RUNTIME_DIR=/run/user/1000 systemctl --user restart meu-app.service",
            ExecutorDeArgumentos.linhaRemota(ComandosDeProcessos.reiniciarServicoDoUsuario("meu-app.service"), ComandosDeProcessos.ambienteDoServicoDoUsuario("1000")));
        for (String ruim : new String[] {"meu app.service", "x;y.service", "../x.service", "app", "app.timer", "$(id).service", "a\\x2db.service"}) {
            assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.reiniciarServicoDoUsuario(ruim), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.ambienteDoServicoDoUsuario("1000; rm"));
    }

    @Test
    void detectaOServicoDoUsuarioPeloGrupoDeControle() {
        String cg = "0::/user.slice/user-1000.slice/user@1000.service/app.slice/meu-app.service\n";
        assertEquals(Optional.of("meu-app.service"), ComandosDeProcessos.unidadeDoUsuario(cg, "1000"));
        assertEquals(Optional.empty(), ComandosDeProcessos.unidadeDoUsuario(cg, "1001"), "serviço de outro usuário não vale");
        assertEquals(Optional.empty(), ComandosDeProcessos.unidadeDoUsuario("0::/system.slice/nginx.service\n", "1000"), "serviço do sistema não é do usuário");
        assertEquals(Optional.empty(), ComandosDeProcessos.unidadeDoUsuario("0::/system.slice/docker-abc.scope\n", "1000"));
        assertEquals(Optional.empty(), ComandosDeProcessos.unidadeDoUsuario("0::/user.slice/user-1000.slice/user@1000.service/app.slice/x y.service\n", "1000"));
        assertEquals(Optional.empty(), ComandosDeProcessos.unidadeDoUsuario(null, "1000"));
    }

    @Test
    void taskkillSoComNumeroValidadoELista() {
        assertEquals(List.of("taskkill", "/PID", "4242", "/T"), ComandosDeProcessos.taskkill(4242, false));
        assertEquals(List.of("taskkill", "/PID", "4242", "/T", "/F"), ComandosDeProcessos.taskkill(4242, true));
        assertThrows(IllegalArgumentException.class, () -> ComandosDeProcessos.taskkill(1, false));
    }

    @Test
    void comandosParaODonoNuncaSaoExecutadosENuncaPedemSenhaDoApp() {
        assertEquals("sudo kill -TERM 77", ComandosDeProcessos.comandoParaODono(77, Sinal.TERM, AmbienteDeProcessos.Tipo.SERVIDOR_LINUX));
        assertEquals("sudo kill -KILL 77", ComandosDeProcessos.comandoParaODono(77, Sinal.KILL, AmbienteDeProcessos.Tipo.COMPUTADOR_MAC_OU_LINUX));
        assertTrue(ComandosDeProcessos.comandoParaODono(77, Sinal.KILL, AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS).startsWith("taskkill /PID 77 /T /F"));
        assertEquals("sudo docker stop meu-app", ComandosDeProcessos.comandoDoContainerParaODono(Acao.ENCERRAR, "meu-app"));
        assertEquals("sudo docker restart meu-app", ComandosDeProcessos.comandoDoContainerParaODono(Acao.REINICIAR, "meu-app"));
    }
}
