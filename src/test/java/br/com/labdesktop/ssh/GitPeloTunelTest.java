package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.CriadorDeRepositorio;
import br.com.labdesktop.git.GitDeTeste;
import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.git.LeitorDeGitPorExecutor;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.transporte.AbridorDeTuneis;
import br.com.labdesktop.transporte.ConfigDoTunel;
import br.com.labdesktop.transporte.LocalizadorDeFerramenta;
import br.com.labdesktop.transporte.PortasLocais;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O Git do projeto pelos DOIS túneis, o do AWS Session Manager e o da Cloudflare, de ponta a ponta com o SSH embutido do repo e um programa de túnel de mentira (um processo Java do teste que faz
 * o papel do {@code aws} e do {@code cloudflared}, rodado pelo lançador de produção): o endereço guardado no cofre nunca resolve, então se a leitura e a criação chegam é porque o comando do git passou pelo
 * canal {@code exec} da MESMA conexão do app, dentro do transporte. O modo de transporte não muda nada no que o Git faz.
 */
class GitPeloTunelTest {

    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final Transporte CLOUD = Transporte.cloudflared("ssh.example.com");
    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22);

    @TempDir
    Path tmp;

    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);

    private AbridorDeTuneis abridor(br.com.labdesktop.transporte.LancadorDeProcesso lancador) {
        LocalizadorDeFerramenta tudo = f -> Optional.of(switch (f) {
            case AWS -> "/ferramentas/aws";
            case PLUGIN_SSM -> "/ferramentas/plugin/session-manager-plugin";
            case CLOUDFLARED -> "/ferramentas/cloudflared";
        });
        return new AbridorDeTuneis(lancador, tudo, PortasLocais.doSistema(), registro, ConfigDoTunel.padrao().comPrazo(Duration.ofSeconds(25)),
            AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin:/bin")));
    }

    private void lerECriarPeloTunel(Transporte transporte, String programa) throws Exception {
        assumeTrue(ShellDeTeste.temShell() && GitDeTeste.temGit() && Files.isDirectory(Path.of("/tmp")));
        GitDeTeste g = new GitDeTeste(tmp);
        Path com = g.repositorio("loja-web");
        g.escrever(com, "src/app.js", "x\n");
        g.commitar(com, "Primeiro commit");
        Path sem = g.projeto("app-exemplo");
        g.escrever(sem, ".env", "SENHA=1\n");
        g.escrever(sem, "index.js", "x\n");

        AtomicInteger portaDoSsh = new AtomicInteger();
        ConexaoPeloTunelTest.LancadorDeFerramentaFalsaAdiado lancador = new ConexaoPeloTunelTest.LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(transporte, abridor(lancador), ConfigSsh.rapida(), null).sondaPorHttp()) {
            portaDoSsh.set(c.ssh.porta());
            new ShellDeTeste(g.casa, c.ssh).comAmbiente("GIT_CEILING_DIRECTORIES", tmp.toAbsolutePath().toString());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info() + " falha=" + c.info().falha(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertEquals(programa, lancador.real().lancados.get(0).argumentosDoApp().get(0), "o túnel do transporte está de pé");

            ExecutorDeArgumentos porSsh = ExecutorDeArgumentos.porSsh(c.conexao::executar);
            LeitorDeGit leitor = new LeitorDeGitPorExecutor(porSsh, com.toString());
            ResumoDoRepositorio r = leitor.resumo().valorOuNulo();
            assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado());
            assertEquals("Primeiro commit", r.ultimo().assunto());
            assertEquals(1, leitor.commits(null, 0, 50).valorOuNulo().commits().size());
            assertTrue(leitor.detalhe(r.ultimo().hash()).ok());

            AmbienteDeProjetos amb = new AmbienteDeProjetos(false, g.raiz.toString(), g.arquivos(), porSsh, caminho -> caminho);
            CriadorDeRepositorio criador = new CriadorDeRepositorio(amb, sem.toString());
            ConferenciaDoRepositorio conf = criador.conferirCriacao();
            assertEquals(ConferenciaDoRepositorio.Situacao.PODE_CRIAR, conf.situacao(), String.valueOf(conf.falha()));
            assertEquals(List.of("index.js"), conf.arquivos().stream().filter(a -> !a.equals(".env.example")).toList());
            ResultadoDaCriacao criou = criador.criarRepositorio(new PedidoDeCriacao(conf, "Ana Silva", "ana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
            assertTrue(criou.criou(), criou.mensagem());
            assertEquals(List.of(".gitignore", "index.js"), List.of(g.git(sem, "ls-files").strip().split("\n")), "o .env não entrou");
            assertFalse(c.ssh.comandos.isEmpty());
            assertTrue(c.ssh.comandos.stream().allMatch(l -> l.startsWith("env ") || l.startsWith("git ") || l.startsWith("mkdir -m 700 -- /tmp/lab-desktop-git-") || l.startsWith("tee /tmp/lab-desktop-git-") || l.startsWith("rm -rf -- /tmp/lab-desktop-git-")),
                "só linhas fixas do git pelo túnel: " + c.ssh.comandos);
            assertTrue(c.ssh.comandos.stream().noneMatch(l -> l.contains("sudo") || l.contains("--global")));
        }
    }

    @Test
    void peloSessionManagerDaAwsLeECria() throws Exception {
        lerECriarPeloTunel(SSM, "/ferramentas/aws");
    }

    @Test
    void peloTunelDaCloudflareLeECria() throws Exception {
        lerECriarPeloTunel(CLOUD, "/ferramentas/cloudflared");
    }
}
