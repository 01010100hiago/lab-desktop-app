package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.GitDeTeste;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O Git do projeto pelo {@link ServidorLab} de verdade (o SSH embutido do repo, um agente falso e o cofre em arquivo temporário; nunca o cofre real nem um servidor real): o leitor e as operações usam o
 * canal {@code exec} DA CONEXÃO DO SERVIDOR, e num servidor PROTEGIDO ler e criar o repositório valem (como Novo projeto) enquanto instalar o Git continua recusado.
 */
class GitPeloServidorLabTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private GitDeTeste g;

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell() && GitDeTeste.temGit());
        g = new GitDeTeste(tmp);
        agente = new AgenteDoLab();
        ssh = ServidorSshDeTeste.ed25519().comSftp(Path.of("/")); // o SFTP do SSH embutido enxerga o mesmo disco que o shell de teste: o .gitignore é lido e gravado pelo SFTP de verdade
        new ShellDeTeste(g.casa, ssh).outrosComandos(agente.comandosPorSsh()).comAmbiente("GIT_CEILING_DIRECTORIES", tmp.toAbsolutePath().toString());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        if (gerenciador != null) {
            gerenciador.fechar().join();
            cofre.close();
            agente.close();
            ssh.close();
        }
    }

    private ServidorLab servidor(boolean protegido) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
        cofre.adicionarChave(chave);
        ssh.aceitar(chave.paraKeyPair().getPublic());
        TokenAgente token = TokenAgente.gerar();
        agente.tokens.put(token.hashSha256Hex(), "mac-vps");
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), "vps-1", "127.0.0.1", ssh.porta(), "labuser", chave.id(), agente.porta(),
            List.of(ssh.pin()), Transporte.DIRETO, "mac-vps", Estado2fa.DESLIGADO, protegido, false);
        cofre.adicionarServidor(cfg, token);
        ServidorLab lab = gerenciador.adicionar(cfg.id());
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        return lab;
    }

    @Test
    void oLeitorEAsOperacoesUsamAConexaoDoServidor() throws Exception {
        ServidorLab lab = servidor(false);
        Path com = g.repositorio("loja-web");
        g.escrever(com, "src/app.js", "x\n");
        g.commitar(com, "Primeiro commit");
        ResumoDoRepositorio r = lab.leitorDeGit(com.toString()).resumo().valorOuNulo();
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, r.estado(), String.valueOf(r.falha()));
        assertEquals("Primeiro commit", r.ultimo().assunto());
        assertTrue(ssh.comandos.stream().anyMatch(l -> l.startsWith("env GIT_") && l.contains("rev-parse --show-prefix")), "a leitura chegou pelo canal exec do servidor: " + ssh.comandos);

        Path sem = g.projeto("app-exemplo");
        g.escrever(sem, ".env", "SENHA=1\n");
        g.escrever(sem, "index.js", "x\n");
        OperacoesDeGit op = lab.operacoesDeGit(sem.toString());
        ConferenciaDoRepositorio conf = op.conferirCriacao();
        assertEquals(ConferenciaDoRepositorio.Situacao.PODE_CRIAR, conf.situacao(), String.valueOf(conf.falha()));
        ResultadoDaCriacao criou = op.criarRepositorio(new PedidoDeCriacao(conf, "Ana Silva", "ana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(criou.criou(), criou.mensagem());
        assertEquals(List.of(".gitignore", "index.js"), List.of(g.git(sem, "ls-files").strip().split("\n")));
    }

    @Test
    void pastaInvalidaNuncaViraComando() throws Exception {
        ServidorLab lab = servidor(false);
        for (String ruim : List.of("relativa", "/srv/../etc", "-C/x", "")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> lab.leitorDeGit(ruim), ruim);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> lab.operacoesDeGit(ruim), ruim);
        }
        assertTrue(ssh.comandos.stream().noneMatch(l -> l.contains("etc")), "nada foi enviado ao servidor");
    }

    @Test
    void servidorProtegidoLeECriaMasNaoInstalaOGit() throws Exception {
        ServidorLab lab = servidor(true);
        assertTrue(lab.protegido());
        Path com = g.repositorio("loja-web");
        g.escrever(com, "a.txt", "a");
        g.commitar(com, "um");
        assertEquals(ResumoDoRepositorio.Estado.COM_GIT, lab.leitorDeGit(com.toString()).resumo().valorOuNulo().estado(), "ler o Git é permitido");
        assertTrue(lab.leitorDeGit(com.toString()).commits(null, 0, 10).ok());
        // criar o repositório de um projeto é como Novo projeto: não instala nada
        Path sem = g.projeto("app-exemplo");
        g.escrever(sem, "index.js", "x\n");
        OperacoesDeGit op = lab.operacoesDeGit(sem.toString());
        ConferenciaDoRepositorio conf = op.conferirCriacao();
        assertTrue(op.criarRepositorio(new PedidoDeCriacao(conf, "Ana", "ana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento()).criou());
        // instalar o git continua recusado, e conferir (ler) continua valendo
        InstaladorDeGit instalador = lab.instaladorDeGit();
        assertTrue(instalador.conferirOGit().instalado());
        InstaladorDeGit.Previa previa = new InstaladorDeGit.Previa(new InstaladorDeGit.Estado(false, ""), InstaladorDeGit.Situacao.PODE_INSTALAR, null, "Debian", true, false,
            List.of("apt-get install -y git"), List.of());
        InstaladorDeGit.Resultado r = instalador.instalar(previa, InstaladorDeGit.Observador.NENHUM, new br.com.labdesktop.arquivos.Cancelamento());
        assertFalse(r.instalou());
        assertTrue(r.mensagem().contains("Este servidor é protegido"), r.mensagem());
        assertTrue(ssh.comandos.stream().noneMatch(l -> l.contains("apt-get")), "nenhum comando de instalação foi enviado: " + ssh.comandos);
    }
}
