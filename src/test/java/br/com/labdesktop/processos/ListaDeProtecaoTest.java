package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A lista do que nunca se oferece: cada categoria da ARQUITETURA, vista pelos nomes que o servidor e o computador mostram. */
class ListaDeProtecaoTest {

    private static final AmbienteDeProcessos SERVIDOR = AmbienteDeProcessos.servidor("labuser", Set.of());

    private static ProcessoAlvo comum(int pid, String nome) {
        return new ProcessoAlvo(pid, nome, "labuser", null, null);
    }

    private static Optional<String> motivo(ProcessoAlvo a) {
        return ListaDeProtecao.motivo(a, SERVIDOR);
    }

    @Test
    void oPrimeiroProcessoDoSistemaNuncaVale() {
        assertTrue(motivo(comum(1, "systemd")).orElseThrow().contains("primeiro do sistema"));
        assertTrue(motivo(comum(1, "qualquer-coisa")).isPresent(), "PID 1 por número, seja qual for o nome");
        assertTrue(motivo(comum(0, "swapper")).isPresent());
    }

    @Test
    void numeroInvalidoOuAusenteEhRecusado() {
        assertTrue(motivo(new ProcessoAlvo(null, "node", "labuser", null, null)).isPresent());
        assertTrue(motivo(comum(-3, "node")).isPresent());
        assertTrue(motivo(comum(5_000_000, "node")).isPresent());
    }

    @Test
    void nomeEmBrancoNaoSePodeConferir() {
        assertTrue(motivo(comum(500, "")).orElseThrow().contains("conferir"));
    }

    @Test
    void oSshdNuncaOdoServidorEmNenhumaVersao() {
        for (String n : new String[] {"sshd", "/usr/sbin/sshd", "sshd-session", "sshd: usuario@pts/0", "SSHD"}) {
            Optional<String> m = motivo(comum(700, n));
            assertTrue(m.isPresent(), n);
            assertTrue(m.get().contains("SSH"), m.get());
        }
    }

    @Test
    void oAgenteDoPainelEProtegidoPeloContainer() {
        ProcessoAlvo node = new ProcessoAlvo(900, "node", "root", "painel-lab", null);
        assertTrue(motivo(node).orElseThrow().contains("agente do painel"));
        ProcessoAlvo outraCaixa = new ProcessoAlvo(901, "node", "root", "PAINEL-LAB", null);
        assertTrue(motivo(outraCaixa).isPresent(), "o nome do container não diferencia maiúsculas");
    }

    @Test
    void oAgenteInstaladoPeloAppComOutroNomeTambemEProtegido() {
        AmbienteDeProcessos amb = AmbienteDeProcessos.servidor("labuser", Set.of("painel-teste"));
        assertTrue(ListaDeProtecao.motivo(new ProcessoAlvo(900, "node", "root", "painel-teste", null), amb).isPresent());
        assertTrue(ListaDeProtecao.motivo(new ProcessoAlvo(900, "node", "root", "painel-lab", null), amb).isPresent(), "o nome padrão segue protegido");
    }

    @Test
    void oProcessoDeOutroContainerPodeSerOferecido() {
        assertEquals(Optional.empty(), motivo(new ProcessoAlvo(1200, "node", "node", "app-loja-web", "app-loja")));
        // dentro de container o nome do programa não importa (age-se no container): um sshd de um container de teste não é o sshd do servidor
        assertEquals(Optional.empty(), motivo(new ProcessoAlvo(1201, "sshd", "root", "teste-ssh", null)));
    }

    @Test
    void oMotorDoDockerNuncaSeOferece() {
        for (String n : new String[] {"dockerd", "containerd", "containerd-shim-runc-v2", "docker-proxy", "runc", "com.docker.backend", "Docker Desktop", "vpnkit"}) {
            Optional<String> m = motivo(comum(800, n));
            assertTrue(m.isPresent(), n);
            assertTrue(m.get().contains("Docker"), m.get());
        }
    }

    @Test
    void processosDoSistemaNuncaSeOferecem() {
        for (String n : new String[] {"systemd", "systemd-journald", "systemd-logind", "kthreadd", "kworker/0:1", "ksoftirqd/0", "rcu_sched", "migration/0", "login", "agetty",
            "dbus-daemon", "NetworkManager", "cron", "rsyslogd", "polkitd", "cloudflared", "tailscaled", "launchd", "WindowServer", "loginwindow", "kernel_task"}) {
            assertTrue(motivo(comum(300, n)).isPresent(), n);
        }
    }

    @Test
    void naoProtegeProgramaComum() {
        for (String n : new String[] {"node", "python3", "java", "nginx", "postgres", "redis-server", "vim", "sleep", "code", "chrome", "firefox", "npm", "tail"}) {
            assertEquals(Optional.empty(), motivo(comum(2000, n)), n);
        }
    }

    @Test
    void oProprioAppEQuemOAbriuNuncaSaoOferecidos() {
        AmbienteDeProcessos amb = new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_MAC_OU_LINUX, "ana", Set.of(), Set.of(5000, 4999));
        assertTrue(ListaDeProtecao.motivo(new ProcessoAlvo(5000, "java", "ana", null, null), amb).orElseThrow().contains("próprio app"));
        assertTrue(ListaDeProtecao.motivo(new ProcessoAlvo(4999, "gradle", "ana", null, null), amb).isPresent());
        assertFalse(ListaDeProtecao.motivo(new ProcessoAlvo(5001, "java", "ana", null, null), amb).isPresent());
    }

    @Test
    void windowsProtegeOsDoSistemaSemDiferenciarMaiusculas() {
        AmbienteDeProcessos win = new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS, "PC\\ana", Set.of(), Set.of());
        for (String n : new String[] {"C:\\Windows\\System32\\svchost.exe", "LSASS.EXE", "csrss.exe", "explorer.exe", "dwm.exe", "System", "Memory Compression", "vmmem", "Docker Desktop.exe"}) {
            assertTrue(ListaDeProtecao.motivo(new ProcessoAlvo(900, n, "PC\\ana", null, null), win).isPresent(), n);
        }
        assertFalse(ListaDeProtecao.motivo(new ProcessoAlvo(900, "notepad.exe", "PC\\ana", null, null), win).isPresent());
    }

    @Test
    void shellsESftpServerSaoDaConexaoENuncaSeOferecem() {
        for (String n : new String[] {"bash", "-bash", "sh", "zsh", "fish", "dash", "sftp-server", "/usr/lib/openssh/sftp-server", "internal-sftp", "sshd: ana@pts/0", "sshd: ana [priv]",
            "/usr/sbin/sshd -D", "sshd-session"}) {
            assertTrue(motivo(comum(300, n)).isPresent(), n);
        }
    }

    @Test
    void nomeEmLinhaDeComandoEComTruncamentoDe15LetrasTambemConfere() {
        assertTrue(motivo(comum(300, "/usr/sbin/NetworkManager --no-daemon")).isPresent(), "linha de comando: vale o primeiro pedaço");
        assertTrue(motivo(comum(300, "/lib/systemd/systemd-journald")).isPresent());
        assertTrue(motivo(comum(300, "networkd-dispat")).isPresent(), "o ps corta networkd-dispatcher em 15 letras");
        assertFalse(motivo(comum(300, "networkd-xx")).isPresent());
        assertEquals("sshd", ProcessoAlvo.nomeCurto("sshd: ana@pts/0"));
        assertEquals("sshd", ProcessoAlvo.nomeCurto("/usr/sbin/sshd -D"));
        assertEquals("node", ProcessoAlvo.nomeCurto("/usr/bin/node --inspect x.js"));
        assertEquals("kworker/0:1", ProcessoAlvo.nomeCurto("kworker/0:1"));
        assertEquals("Google Chrome", ProcessoAlvo.nomeCurto("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        assertEquals("nginx", ProcessoAlvo.nomeCurto("nginx: master process /usr/sbin/nginx"));
    }

    @Test
    void unidadesDoSistemaEDoAcessoSaoProtegidas() {
        for (String u : new String[] {"docker.service", "containerd.service", "cloudflared.service", "sshd.service", "ssh.service", "ssh.socket", "systemd-logind.service",
            "NetworkManager.service", "dbus.service", "user@1000.service", "docker.socket"}) {
            assertTrue(ListaDeProtecao.motivoDaUnidade(u).isPresent(), u);
        }
        for (String u : new String[] {"meu-app.service", "app-loja.service", "api@1.service"}) {
            assertFalse(ListaDeProtecao.motivoDaUnidade(u).isPresent(), u);
        }
    }
}
