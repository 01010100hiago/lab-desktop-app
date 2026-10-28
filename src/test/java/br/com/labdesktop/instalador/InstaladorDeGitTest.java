package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Instalar o Git no servidor: instalado, não instalado, sem sudo; só roda o que a confirmação mostrou; nunca mexe na configuração do git. */
class InstaladorDeGitTest {

    /** Um servidor de mentira: responde as linhas fixas e anota tudo. */
    private static final class Servidor implements ProvisionadorRemoto.Executor {
        boolean gitInstalado;
        String versao = "git version 2.43.0";
        String osRelease = "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n";
        List<String> gerenciadores = List.of("apt-get");
        boolean root;
        boolean sudo = true;
        int statusDaInstalacao;
        String erroDaInstalacao = "";
        boolean quedaNaInstalacao;
        boolean demoraNaInstalacao;
        boolean gitAparecePorDepois = true;
        final List<String> linhas = new ArrayList<>();

        @Override
        public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException {
            linhas.add(comando);
            if (comando.equals("git --version")) {
                return gitInstalado ? ok(versao + "\n") : new ResultadoExec(127, new byte[0], "sh: git: command not found".getBytes(StandardCharsets.UTF_8), false);
            }
            if (comando.equals("cat /etc/os-release")) {
                return ok(osRelease);
            }
            if (comando.equals(InstaladorDeGit.lerGerenciadores())) {
                return ok(gerenciadores.stream().map(g -> "gerenciador=" + g + "\n").reduce("", String::concat));
            }
            if (comando.equals(InstaladorDeGit.lerPermissoes())) {
                return ok("uid=" + (root ? 0 : 1000) + "\nsudo=" + (sudo ? "sim" : "nao") + "\n");
            }
            if (quedaNaInstalacao) {
                throw new IOException("canal fechado");
            }
            if (demoraNaInstalacao) {
                throw new ComandoDemorouException(600);
            }
            if (statusDaInstalacao != 0) {
                return new ResultadoExec(statusDaInstalacao, new byte[0], erroDaInstalacao.getBytes(StandardCharsets.UTF_8), false);
            }
            gitInstalado = gitAparecePorDepois; // qualquer comando de instalar que chegue aqui "instala" o git de mentira
            return ok("");
        }

        private static ResultadoExec ok(String saida) {
            return new ResultadoExec(0, saida.getBytes(StandardCharsets.UTF_8), new byte[0], false);
        }
    }

    @Test
    void gitJaInstaladoNaoPropoeNada() throws Exception {
        Servidor s = new Servidor();
        s.gitInstalado = true;
        InstaladorDeGit.Previa p = new InstaladorDeGit(s).conferir();
        assertEquals(InstaladorDeGit.Situacao.JA_INSTALADO, p.situacao());
        assertTrue(p.estado().instalado());
        assertEquals("git version 2.43.0", p.estado().versao());
        assertEquals(List.of("git --version"), s.linhas, "instalado: nem lê o sistema");
        assertTrue(p.comandos().isEmpty());
    }

    @Test
    void gitAusenteComRootMostraOsComandosSemSudoEPodeExecutar() throws Exception {
        Servidor s = new Servidor();
        s.root = true;
        s.sudo = false;
        InstaladorDeGit.Previa p = new InstaladorDeGit(s).conferir();
        assertEquals(InstaladorDeGit.Situacao.PODE_INSTALAR, p.situacao());
        assertEquals(GerenciadorDePacotes.APT_GET, p.gerenciador());
        assertEquals(List.of("env DEBIAN_FRONTEND=noninteractive apt-get update", "env DEBIAN_FRONTEND=noninteractive apt-get install -y git"), p.comandos());
        assertEquals("Debian GNU/Linux 13 (trixie)", p.sistema());
        assertTrue(p.comandosParaODono().isEmpty());
    }

    @Test
    void gitAusenteComSudoSemSenhaUsaSudoN() throws Exception {
        Servidor s = new Servidor();
        InstaladorDeGit.Previa p = new InstaladorDeGit(s).conferir();
        assertEquals(InstaladorDeGit.Situacao.PODE_INSTALAR, p.situacao());
        assertEquals(List.of("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get update", "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y git"), p.comandos());
        assertTrue(p.comandos().stream().allMatch(c -> c.startsWith("sudo -n ")), "sudo que nunca pergunta senha");
    }

    @Test
    void semRootNemSudoOAppNaoExecutaEMostraOComandoParaODono() throws Exception {
        Servidor s = new Servidor();
        s.sudo = false;
        InstaladorDeGit instalador = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = instalador.conferir();
        assertEquals(InstaladorDeGit.Situacao.PRECISA_DO_DONO, p.situacao());
        assertFalse(p.podeExecutar());
        assertEquals(List.of("sudo env DEBIAN_FRONTEND=noninteractive apt-get update", "sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y git"), p.comandosParaODono(),
            "o usuário roda com sudo e responde a senha ele mesmo");
        int antes = s.linhas.size();
        InstaladorDeGit.Resultado r = instalador.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.instalou());
        assertEquals(antes, s.linhas.size(), "nada foi enviado ao servidor");
        assertTrue(r.mensagem().contains("Terminal"));
    }

    @Test
    void instalarRodaExatamenteOsComandosMostradosNaOrdemEConfereGitVersion() throws Exception {
        Servidor s = new Servidor();
        InstaladorDeGit instalador = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = instalador.conferir();
        s.linhas.clear();
        List<String> passos = new ArrayList<>();
        InstaladorDeGit.Resultado r = instalador.instalar(p, passos::add, new Cancelamento());
        assertTrue(r.instalou(), r.mensagem());
        assertEquals("git version 2.43.0", r.estado().versao());
        assertEquals(List.of("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get update", "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y git", "git --version"), s.linhas);
        assertEquals(3, passos.size());
        assertTrue(passos.get(0).startsWith("Passo 1 de 2"));
        assertTrue(s.linhas.stream().noneMatch(l -> l.contains("git config")), "nunca mexe na configuração do git");
    }

    @Test
    void cadaGerenciadorRodaOSeuComando() throws Exception {
        String[][] casos = {{"fedora", "dnf", "sudo -n dnf install -y git"}, {"alpine", "apk", "sudo -n apk add git"}, {"arch", "pacman", "sudo -n pacman -S --noconfirm git"},
            {"opensuse-leap", "zypper", "sudo -n zypper --non-interactive install git"}, {"centos", "yum", "sudo -n yum install -y git"}};
        for (String[] c : casos) {
            Servidor s = new Servidor();
            s.osRelease = "ID=" + c[0] + "\n";
            s.gerenciadores = List.of(c[1]);
            InstaladorDeGit i = new InstaladorDeGit(s);
            InstaladorDeGit.Previa p = i.conferir();
            assertEquals(List.of(c[2]), p.comandos(), c[0]);
            assertTrue(i.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento()).instalou(), c[0]);
        }
    }

    @Test
    void semNenhumGerenciadorSoOrienta() throws Exception {
        Servidor s = new Servidor();
        s.gerenciadores = List.of();
        InstaladorDeGit.Previa p = new InstaladorDeGit(s).conferir();
        assertEquals(InstaladorDeGit.Situacao.GERENCIADOR_NAO_ACHADO, p.situacao());
        assertTrue(p.comandos().isEmpty());
        assertFalse(p.podeExecutar());
    }

    @Test
    void falhaDoGerenciadorVemEmFraseSimplesEPara() throws Exception {
        Servidor s = new Servidor();
        s.statusDaInstalacao = 100;
        s.erroDaInstalacao = "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 1234";
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = i.conferir();
        s.linhas.clear();
        InstaladorDeGit.Resultado r = i.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.instalou());
        assertTrue(r.mensagem().contains("Outro instalador está rodando"), r.mensagem());
        assertEquals(1, s.linhas.size(), "parou no primeiro comando que falhou");
        assertTrue(r.passoQueFalhou().startsWith("Passo 1 de 2"));
    }

    @Test
    void sudoQuePedeSenhaViraUmaFraseNuncaSenhaDigitada() throws Exception {
        Servidor s = new Servidor();
        s.statusDaInstalacao = 1;
        s.erroDaInstalacao = "sudo: a password is required";
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Resultado r = i.instalar(i.conferir(), InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.mensagem().contains("sudo pediu senha"), r.mensagem());
    }

    @Test
    void erroDesconhecidoSaiLimpoERedigido() throws Exception {
        Servidor s = new Servidor();
        s.statusDaInstalacao = 1;
        s.erroDaInstalacao = "falhou \u001b[31mfeio\u001b[0m ghp_" + "b".repeat(36);
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Resultado r = i.instalar(i.conferir(), InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.mensagem().contains("\u001b"));
        assertFalse(r.mensagem().contains("ghp_" + "b".repeat(36)));
    }

    @Test
    void quedaEPrazoNaoSaoDadosComoInstalado() throws Exception {
        Servidor s = new Servidor();
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = i.conferir();
        s.quedaNaInstalacao = true;
        InstaladorDeGit.Resultado r = i.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.instalou());
        assertTrue(r.mensagem().contains("conexão caiu"));
        s.quedaNaInstalacao = false;
        s.demoraNaInstalacao = true;
        assertFalse(i.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento()).instalou());
    }

    @Test
    void comandosTerminamBemMasGitNaoApareceDizSemMentir() throws Exception {
        Servidor s = new Servidor();
        s.gitAparecePorDepois = false;
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Resultado r = i.instalar(i.conferir(), InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.instalou());
        assertTrue(r.mensagem().contains("ainda não responde"));
    }

    @Test
    void jaInstaleiConferirDeNovo() throws Exception {
        Servidor s = new Servidor();
        InstaladorDeGit i = new InstaladorDeGit(s);
        assertFalse(i.conferirOGit().instalado());
        s.gitInstalado = true; // o usuário instalou no Terminal
        InstaladorDeGit.Estado agora = i.conferirOGit();
        assertTrue(agora.instalado());
        assertEquals("git version 2.43.0", agora.versao());
    }

    @Test
    void cancelarAntesDeUmComandoNaoRodaOResto() throws Exception {
        Servidor s = new Servidor();
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = i.conferir();
        s.linhas.clear();
        Cancelamento c = new Cancelamento();
        c.cancelar();
        InstaladorDeGit.Resultado r = i.instalar(p, InstaladorDeGit.Observador.NENHUM, c);
        assertFalse(r.instalou());
        assertTrue(s.linhas.isEmpty(), "cancelado antes: nada rodou");
    }

    @Test
    void todaLinhaPassaPelaRegraDePalavrasSeguras() {
        for (GerenciadorDePacotes g : GerenciadorDePacotes.values()) {
            for (var c : g.comandosDeInstalar()) {
                String linha = InstaladorDeGit.linha(c, "sudo -n ");
                assertTrue(linha.chars().noneMatch(ch -> ch < 0x20 || ch == '\'' || ch == '"' || ch == ';' || ch == '|' || ch == '&' || ch == '$' || ch == '`'), linha);
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> InstaladorDeGit.linha(List.of("apt-get", "install", "git; rm -rf /"), ""));
    }
}
