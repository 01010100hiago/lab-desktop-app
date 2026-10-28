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
        final List<String> consultasDeSudo = new ArrayList<>();
        /** Falha só do passo de atualizar a lista de pacotes (o update). */
        boolean falhaDoUpdate;

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
            if (comando.equals(InstaladorDeGit.lerUid())) {
                return ok((root ? 0 : 1000) + "\n");
            }
            if (comando.startsWith("sh -c 'if sudo -n -l ")) {
                consultasDeSudo.add(comando);
                return ok("sudo=" + (sudo ? "sim" : "nao") + "\n");
            }
            if (falhaDoUpdate && comando.endsWith(" update")) {
                return new ResultadoExec(100, new byte[0], "E: Failed to fetch http://deb.debian.org/debian/dists/trixie/InRelease  Temporary failure resolving 'deb.debian.org'".getBytes(StandardCharsets.UTF_8), false);
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
        assertEquals(List.of("env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update", "env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git"), p.comandos());
        assertEquals("Debian GNU/Linux 13 (trixie)", p.sistema());
        assertEquals(List.of("env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update", "env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git"), p.comandosParaODono(),
            "o usuário pode sempre rodar o mesmo no Terminal (ações de escrita desligadas, por exemplo)");
        assertTrue(s.consultasDeSudo.isEmpty(), "como root nem pergunta ao sudo");
    }

    @Test
    void gitAusenteComSudoSemSenhaUsaSudoN() throws Exception {
        Servidor s = new Servidor();
        InstaladorDeGit.Previa p = new InstaladorDeGit(s).conferir();
        assertEquals(InstaladorDeGit.Situacao.PODE_INSTALAR, p.situacao());
        assertEquals(List.of("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update", "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git"), p.comandos());
        assertTrue(p.comandos().stream().allMatch(c -> c.startsWith("sudo -n ")), "sudo que nunca pergunta senha");
        assertEquals(1, s.consultasDeSudo.size());
        assertEquals("sh -c 'if sudo -n -l env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git >/dev/null 2>&1; then echo sudo=sim; else echo sudo=nao; fi'", s.consultasDeSudo.get(0),
            "pergunta ao sudo se ESTE comando passa sem senha (sudo -n true não prova isso)");
    }

    @Test
    void sudoQueLiberaOutraCoisaNaoFazOAppExecutar() throws Exception {
        Servidor s = new Servidor();
        s.sudo = false; // o sudoers libera só outro comando: o -l deste diz que não
        InstaladorDeGit.Previa p = new InstaladorDeGit(s).conferir();
        assertEquals(InstaladorDeGit.Situacao.PRECISA_DO_DONO, p.situacao());
        assertFalse(p.podeExecutar());
        assertTrue(s.linhas.stream().noneMatch(l -> l.contains("sudo -n true")), "nunca usa a prova fraca");
    }

    @Test
    void updateQueFalhaViraAvisoEOAppTentaInstalarMesmoAssim() throws Exception {
        Servidor s = new Servidor();
        s.falhaDoUpdate = true;
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = i.conferir();
        assertEquals(1, p.tolerantes().size(), "só o update é tolerante");
        List<String> passos = new ArrayList<>();
        InstaladorDeGit.Resultado r = i.instalar(p, passos::add, new Cancelamento());
        assertTrue(r.instalou(), r.mensagem());
        assertTrue(r.aviso().contains("falhou") && r.aviso().contains("não alcançou o repositório"), r.aviso());
        assertTrue(s.linhas.stream().anyMatch(l -> l.contains("install -y git")), "o install rodou depois do update falhar");
        assertTrue(passos.stream().anyMatch(t -> t.startsWith("Aviso:")), passos.toString());
    }

    @Test
    void installQueFalhaNaoEToleradoNemDepoisDoUpdateBom() throws Exception {
        Servidor s = new Servidor();
        s.statusDaInstalacao = 100;
        s.erroDaInstalacao = "E: Unable to locate package git";
        InstaladorDeGit i = new InstaladorDeGit(s);
        InstaladorDeGit.Resultado r = i.instalar(i.conferir(), InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.instalou());
        assertTrue(r.mensagem().contains("apt-get update"), "no apt a frase sugere atualizar a lista: " + r.mensagem());
    }

    @Test
    void aFraseDeOutraDistribuicaoNaoMandaRodarAptGetUpdate() throws Exception {
        for (String[] c : new String[][] {{"fedora", "dnf"}, {"alpine", "apk"}, {"arch", "pacman"}}) {
            Servidor s = new Servidor();
            s.osRelease = "ID=" + c[0] + "\n";
            s.gerenciadores = List.of(c[1]);
            s.statusDaInstalacao = 1;
            s.erroDaInstalacao = "Error: Unable to locate package git no match for argument: git";
            InstaladorDeGit i = new InstaladorDeGit(s);
            InstaladorDeGit.Resultado r = i.instalar(i.conferir(), InstaladorDeGit.Observador.NENHUM, new Cancelamento());
            assertFalse(r.mensagem().contains("apt-get"), c[0] + ": " + r.mensagem());
        }
    }

    @Test
    void oUpdateEOLockTimeoutVisiveisNasNotasDaPrevia() throws Exception {
        InstaladorDeGit.Previa p = new InstaladorDeGit(new Servidor()).conferir();
        String notas = String.join(" ", p.notas());
        assertTrue(notas.contains("update só atualiza a lista"), notas);
        assertTrue(notas.contains("DEBIAN_FRONTEND=noninteractive") && notas.contains("DPkg::Lock::Timeout=60"), notas);
        Servidor fedora = new Servidor();
        fedora.osRelease = "ID=fedora\n";
        fedora.gerenciadores = List.of("dnf");
        assertTrue(new InstaladorDeGit(fedora).conferir().notas().isEmpty(), "dnf não tem update nem frontend");
    }

    @Test
    void semRootNemSudoOAppNaoExecutaEMostraOComandoParaODono() throws Exception {
        Servidor s = new Servidor();
        s.sudo = false;
        InstaladorDeGit instalador = new InstaladorDeGit(s);
        InstaladorDeGit.Previa p = instalador.conferir();
        assertEquals(InstaladorDeGit.Situacao.PRECISA_DO_DONO, p.situacao());
        assertFalse(p.podeExecutar());
        assertEquals(List.of("sudo env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update", "sudo env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git"), p.comandosParaODono(),
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
        assertEquals(List.of("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update", "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git", "git --version"), s.linhas);
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
        assertEquals(2, s.linhas.size(), "o update falhou só com aviso; parou no install, que é o que importa");
        assertTrue(r.passoQueFalhou().startsWith("Passo 2 de 2"));
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
