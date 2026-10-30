package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.processos.ControleDeProcessos;
import br.com.labdesktop.processos.ControleRemoto;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O Git e os processos pelo {@code ServidorLab} de verdade: a sessão SSH é a do servidor embutido (MINA), o cofre e o usuário são os do cadastro, e os comandos que chegam ao servidor são os
 * FIXOS do app. Git: instalado, não instalado (com sudo sem senha) e sem sudo. Processos: o usuário e o agente protegido vêm do cadastro, e encerrar age sobre um processo de verdade.
 */
class GitEProcessosPeloServidorLabTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private ServidorLab lab;

    // o "servidor" de git (programável)
    private volatile boolean gitInstalado;
    private volatile boolean sudoSemSenha = true;
    private volatile boolean root;

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        InfoChave chave = assistente.gerarChave("lab");
        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        lab = assistente.cadastrar(new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", 7000), chave.id(), sonda.pin());
        ssh.aceitar(cofre.keyPairDoServidor(lab.id()).getPublic());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        ssh.close();
    }

    /**
     * Os comandos que chegaram ao servidor, SEM a leitura de fundo do monitor da conexão (a situação do agente: {@code command -v docker}...{@code docker inspect painel-lab}). Ela é só
     * leitura, fixa e do próprio {@code ServidorLab}, e chega a qualquer hora (quando a conexão sobe e de tempos em tempos): num computador lento ela cai no meio do que o teste mede.
     * Tudo o mais, e em especial o que o fluxo do Git manda, continua exigido exatamente.
     */
    private java.util.List<String> comandosDoFluxo() {
        java.util.Set<String> sondas = java.util.Set.of(ComandosRemotos.lerSituacaoDoAgente("painel-lab"), ComandosRemotos.lerSituacaoDoAgente("painel-lab", ComandosRemotos.DOCKER_COM_SUDO));
        return ssh.comandos.stream().filter(c -> !sondas.contains(c)).toList();
    }

    private void servidorDeGit() {
        ssh.comandoRemoto((linha, stdin, saida) -> {
            if (linha.equals("git --version")) {
                if (gitInstalado) {
                    saida.append("git version 2.43.0\n");
                    return 0;
                }
                saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("sh: 1: git: not found\n");
                return 127;
            }
            if (linha.equals("cat /etc/os-release")) {
                saida.append("PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n");
                return 0;
            }
            if (linha.equals(InstaladorDeGit.lerGerenciadores())) {
                saida.append("gerenciador=apt-get\n");
                return 0;
            }
            if (linha.equals(InstaladorDeGit.lerUid())) {
                saida.append(root ? 0 : 1000).append('\n');
                return 0;
            }
            if (linha.startsWith("sh -c 'if sudo -n -l ")) {
                saida.append("sudo=").append(sudoSemSenha ? "sim" : "nao").append('\n');
                return 0;
            }
            if (linha.contains("apt-get")) {
                gitInstalado = true;
                return 0;
            }
            return 127;
        });
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao().permiteExec());
    }

    @Test
    void gitJaInstaladoPeloSshDeVerdade() throws Exception {
        gitInstalado = true;
        servidorDeGit();
        InstaladorDeGit.Previa p = lab.instaladorDeGit().conferir();
        assertEquals(InstaladorDeGit.Situacao.JA_INSTALADO, p.situacao());
        assertEquals("git version 2.43.0", p.estado().versao());
        assertEquals(java.util.List.of("git --version"), ssh.comandos.stream().filter(c -> c.contains("git") || c.contains("os-release")).toList());
    }

    @Test
    void gitAusenteComSudoSemSenhaInstalaOsComandosMostradosEConfere() throws Exception {
        servidorDeGit();
        InstaladorDeGit instalador = lab.instaladorDeGit();
        InstaladorDeGit.Previa p = instalador.conferir();
        assertEquals(InstaladorDeGit.Situacao.PODE_INSTALAR, p.situacao());
        assertEquals("Debian GNU/Linux 13 (trixie)", p.sistema());
        ssh.comandos.clear();
        InstaladorDeGit.Resultado r = instalador.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.instalou(), r.mensagem());
        assertEquals(java.util.List.of("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update", "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git", "git --version"),
            comandosDoFluxo(), "exatamente o que a confirmação mostrou, e depois a conferência");
        assertTrue(ssh.comandos.stream().noneMatch(c -> c.contains("git config")));
    }

    @Test
    void gitAusenteSemSudoNaoEnviaNadaEMostraOComandoParaODono() throws Exception {
        sudoSemSenha = false;
        servidorDeGit();
        InstaladorDeGit instalador = lab.instaladorDeGit();
        InstaladorDeGit.Previa p = instalador.conferir();
        assertEquals(InstaladorDeGit.Situacao.PRECISA_DO_DONO, p.situacao());
        assertEquals("sudo env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git", p.comandosParaODono().get(1));
        ssh.comandos.clear();
        assertFalse(instalador.instalar(p, InstaladorDeGit.Observador.NENHUM, new Cancelamento()).instalou());
        assertTrue(comandosDoFluxo().isEmpty(), "nada foi enviado ao servidor: " + ssh.comandos);
        gitInstalado = true; // o usuário instalou no Terminal
        assertTrue(instalador.conferirOGit().instalado());
    }

    @Test
    void oUsuarioDoCadastroEOAgenteProtegidoVemDoServidorLab() throws Exception {
        servidorDeGit();
        ControleDeProcessos c = lab.controleDeProcessos();
        assertInstanceOf(ControleRemoto.class, c);
        assertEquals("labuser", c.ambiente().usuarioDoApp());
        assertTrue(c.ambiente().containersProtegidos().contains("painel-lab"));
        assertEquals(br.com.labdesktop.processos.Oferta.Situacao.LIBERADO, c.avaliar(new ProcessoAlvo(3000, "node", "labuser", null, null)).situacao());
        assertEquals(br.com.labdesktop.processos.Oferta.Situacao.SEM_PERMISSAO, c.avaliar(new ProcessoAlvo(3000, "node", "root", null, null)).situacao());
        assertTrue(c.avaliar(new ProcessoAlvo(900, "node", "root", "painel-lab", null)).protegido());
    }

    @Test
    void osContainersMarcadosPeloDonoEntramNaProtecaoELiberarTiraDeNovo() throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> marcados = new java.util.concurrent.atomic.AtomicReference<>(java.util.Set.of());
        lab.definirProtegidosDoDono(marcados::get);
        ProcessoAlvo alvo = new ProcessoAlvo(900, "node", "root", "app-loja-web", null);
        assertFalse(lab.controleDeProcessos().avaliar(alvo).protegido());
        marcados.set(java.util.Set.of("app-loja-web"));
        ControleDeProcessos depois = lab.controleDeProcessos();
        assertTrue(depois.avaliar(alvo).protegido(), "o controle é refeito quando o usuário marca");
        assertTrue(depois.ambiente().containersProtegidos().contains("painel-lab"), "o agente continua protegido");
        marcados.set(java.util.Set.of());
        assertFalse(lab.controleDeProcessos().avaliar(alvo).protegido(), "liberar tira a marca");
    }

    @Test
    void encerrarUmProcessoDeVerdadePeloSshDoServidorLab() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        new ShellDeTeste(tmp, ssh); // os comandos chegam ao sh de verdade
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao().permiteExec());
        Process p = new ProcessBuilder("/bin/sh", "-c", "sleep 120 >/dev/null 2>&1 & echo $!").start();
        int pid = Integer.parseInt(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        p.waitFor(10, TimeUnit.SECONDS);
        try {
            ControleDeProcessos c = lab.controleDeProcessos();
            ProcessoAlvo alvo = new ProcessoAlvo(pid, "sleep", "labuser", null, null);
            Previa previa = c.preparar(alvo);
            assertTrue(previa.identidade().inicio().length() > 10);
            Resultado r = c.encerrar(previa, ObservadorDeProcesso.NENHUM, new Cancelamento());
            assertEquals(Desfecho.ENCERRADO, r.desfecho(), r.mensagem());
            assertTrue(ssh.comandos.stream().anyMatch(l -> l.contains("kill -TERM " + pid)));
        } finally {
            new ProcessBuilder("/bin/sh", "-c", "kill -KILL " + pid + " 2>/dev/null").start().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
