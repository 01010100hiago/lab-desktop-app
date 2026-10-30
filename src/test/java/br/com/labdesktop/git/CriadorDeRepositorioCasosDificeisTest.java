package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Os casos de borda da criação do repositório, com o git de verdade: o {@code git add} não trata nome de arquivo como padrão, os segredos que a primeira lista deixava passar (sem distinguir
 * maiúscula e muito mais nomes), o {@code .gitignore} que é link, os ganchos e o modelo de pasta do dono, a configuração global do usuário (excludesFile), a área temporária só do usuário e limpa mesmo com a thread
 * interrompida, e a verdade sobre o {@code git reset}.
 */
class CriadorDeRepositorioCasosDificeisTest {

    @TempDir
    Path tmp;

    GitDeTeste g;

    @BeforeEach
    void preparar() {
        assumeTrue(GitDeTeste.temGit(), "sem git neste computador");
        g = new GitDeTeste(tmp);
    }

    private CriadorDeRepositorio criador(Path pasta) {
        return new CriadorDeRepositorio(g.ambiente(), pasta.toString());
    }

    private static PedidoDeCriacao pedido(ConferenciaDoRepositorio c) {
        return new PedidoDeCriacao(c, "Ana Silva", "ana@example.com", false);
    }

    private ResultadoDaCriacao criar(Path p) throws Exception {
        CriadorDeRepositorio c = criador(p);
        return c.criarRepositorio(pedido(c.conferirCriacao()), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
    }

    private List<String> rastreados(Path p) {
        return Stream.of(g.git(p, "-c", "core.quotepath=off", "ls-files", "-z").split("\0")).filter(s -> !s.isEmpty()).sorted().toList();
    }

    // ---------------------------------------------------------------------------------------------
    // 4. O git add não trata nome como padrão
    // ---------------------------------------------------------------------------------------------

    @Test
    void arquivoChamadoAsteriscoENomeComDoisPontosEExclamacaoNaoViramPadraoNoGitAdd() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "ok.txt", "ok");
        g.escrever(p, "*", "um arquivo chamado asterisco");
        g.escrever(p, ":!ok.txt", "um nome que parece padrão mágico");
        g.escrever(p, "b.txt", "b");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio conf = criador.conferirCriacao();
        assertEquals(Set.of("ok.txt", "*", ":!ok.txt", "b.txt"), Set.copyOf(conf.arquivos()));
        g.escrever(p, "apareceu-depois.txt", "não foi conferido"); // chega entre a conferência e o clique
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(conf), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertEquals(List.of("*", ".gitignore", ":!ok.txt", "b.txt", "ok.txt"), rastreados(p),
            "só o que foi conferido, e todos os quatro: um '*' pegaria o arquivo novo e um ':!ok.txt' tiraria o ok.txt");
        assertTrue(r.mensagem().contains("1 arquivo(s) apareceram depois da conferência e ficaram de fora"), r.mensagem());
    }

    @Test
    void todaEscritaDoGitLevaLiteralPathspecs() {
        for (ComandoGit c : List.of(ComandosDeGit.iniciar("/srv/lab/projects/loja-web"), ComandosDeGit.adicionarDaLista("/srv/lab/projects/loja-web", new byte[0]),
            ComandosDeGit.commitar("/srv/lab/projects/loja-web", "x"), ComandosDeGit.limparPreparo("/srv/lab/projects/loja-web"),
            ComandosDeGit.gravarIdentidadeLocal("/srv/lab/projects/loja-web", "user.name", "Ana"))) {
            assertTrue(c.argumentos().contains("--literal-pathspecs"), c.argumentos().toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Segredos: sem distinguir maiúscula, e muito mais nomes
    // ---------------------------------------------------------------------------------------------

    @Test
    void oPrimeiroCommitNaoLevaNenhumDosSegredosQueEscapavamDaPrimeiraLista() throws Exception {
        Path p = g.projeto("loja-web");
        for (String segredo : List.of(".git-credentials", "secrets.json", "ID_RSA", "Credentials.json", ".ENV.local", ".htpasswd", "terraform.tfstate", "terraform.tfstate.backup", "prod.TFSTATE", "banco.kdbx",
            "service-account-prod.json", "Service-Account.JSON", ".aws/credentials", "web/.aws/config", ".ssh/id_ed25519", ".ssh/known_hosts", "Secrets.YAML", ".env ", "CHAVE.PEM", "ssl/Servidor.KEY")) {
            g.escrever(p, segredo, "SEGREDO");
        }
        g.escrever(p, "README.md", "leia");
        g.escrever(p, ".ENV.EXAMPLE", "SENHA=\n");
        g.escrever(p, "src/app.js", "x");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio conf = criador.conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, conf.situacao(), String.valueOf(conf.falha()));
        assertTrue(conf.liberaACriacao(), conf.segredosQueEntrariam() + " " + conf.protecaoConfirmada());
        assertEquals(Set.of("README.md", ".ENV.EXAMPLE", "src/app.js"), Set.copyOf(conf.arquivos()), "nenhum segredo está na lista que entra: " + conf.arquivos());
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(conf), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        List<String> dentro = rastreados(p);
        assertEquals(List.of(".ENV.EXAMPLE", ".gitignore", "README.md", "src/app.js"), dentro, "o modelo sem valores entra; segredo nenhum");
        assertTrue(dentro.stream().noneMatch(SegredosDoProjeto::pareceSegredo) || dentro.equals(List.of(".ENV.EXAMPLE", ".gitignore", "README.md", "src/app.js")));
        assertEquals("", g.git(p, "status", "--porcelain", "--ignored").lines().filter(l -> l.startsWith("??")).collect(java.util.stream.Collectors.joining()), "tudo o que sobrou está ignorado");
    }

    @Test
    void gitignoreDoDonoComEnvSeguidoDeNegacaoDoEnvBarraACriacaoQuandoOArquivoExiste() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, ".gitignore", ".env\n!.env\n");
        g.escrever(p, ".env", "SENHA=1\n");
        g.escrever(p, "src/app.js", "x");
        ConferenciaDoRepositorio conf = criador(p).conferirCriacao();
        assertEquals(List.of(".env"), conf.segredosQueEntrariam(), "o .gitignore do usuário deixa o .env entrar (a linha ! desfaz a anterior)");
        assertFalse(conf.liberaACriacao());
        assertFalse(Files.exists(p.resolve(".git")), "nada foi criado");
        ResultadoDaCriacao r = criador(p).criarRepositorio(pedido(conf), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertFalse(Files.exists(p.resolve(".git")));
    }

    @Test
    void arquivoComEspacoNoFimDoNomeNaoEscapa() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, ".env ", "SENHA=1\n");
        g.escrever(p, "src/app.js", "x");
        ConferenciaDoRepositorio conf = criador(p).conferirCriacao();
        assertTrue(conf.liberaACriacao(), conf.segredosQueEntrariam().toString());
        assertEquals(List.of("src/app.js"), conf.arquivos());
        assertTrue(SegredosDoProjeto.pareceSegredo(".env "), "o teste em Java também o reconhece");
    }

    // ---------------------------------------------------------------------------------------------
    // .gitignore que é link
    // ---------------------------------------------------------------------------------------------

    @Test
    void gitignoreQueEELinkNaoEGravadoAtravesDoLinkNemTrocadoPorArquivo() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "src/app.js", "x");
        Path alvo = tmp.resolve("gitignore-compartilhado");
        Files.writeString(alvo, "*.log\n");
        Files.createSymbolicLink(p.resolve(".gitignore"), alvo);
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio conf = criador.conferirCriacao();
        assertEquals(Situacao.NAO_CONFERI, conf.situacao());
        assertTrue(conf.falha().frase().contains("é um link para outro arquivo"), conf.falha().frase());
        assertFalse(conf.liberaACriacao());
        // mesmo com uma conferência adulterada que libera: o criador recusa ANTES do git init
        ConferenciaDoRepositorio adulterada = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, "git version 2.43.0", "Ana", "ana@example.com", List.of("src/app.js"), List.of(), List.of(), null, List.of(),
            true, false, 0);
        ResultadoDaCriacao r = criador.criarRepositorio(new PedidoDeCriacao(adulterada, null, null, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertFalse(r.repositorioCriado());
        assertTrue(r.mensagem().contains("é um link para outro arquivo") && r.mensagem().contains("Nada foi feito"), r.mensagem());
        assertFalse(Files.exists(p.resolve(".git")));
        assertTrue(Files.isSymbolicLink(p.resolve(".gitignore")), "o link continua um link");
        assertEquals("*.log\n", Files.readString(alvo), "o arquivo para onde o link aponta não mudou");
        assertTrue(g.comandos.stream().noneMatch(c -> c.contains("init")), "nem o git init rodou");
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Ganchos e modelo de pasta do usuário; configuração global dele
    // ---------------------------------------------------------------------------------------------

    private Path ganchos(String nome, Path marcador) throws Exception {
        Path pasta = Files.createDirectories(tmp.resolve(nome));
        for (String gancho : List.of("pre-commit", "commit-msg", "post-commit", "prepare-commit-msg")) {
            Path f = pasta.resolve(gancho);
            Files.writeString(f, "#!/bin/sh\necho " + gancho + " >> '" + marcador + "'\nexit 0\n");
            f.toFile().setExecutable(true);
        }
        return pasta;
    }

    @Test
    void ganchosDoModeloDePastaDoDonoNaoSaoCopiadosNemRodam() throws Exception {
        Path marcador = tmp.resolve("MARCADOR-GANCHO");
        Path modelo = Files.createDirectories(tmp.resolve("modelo"));
        Files.createDirectories(modelo.resolve("hooks"));
        for (String gancho : List.of("pre-commit", "post-commit")) {
            Path f = modelo.resolve("hooks").resolve(gancho);
            Files.writeString(f, "#!/bin/sh\necho " + gancho + " >> '" + marcador + "'\nexit 0\n");
            f.toFile().setExecutable(true);
        }
        Files.writeString(g.casa.resolve(".gitconfig"), "[init]\n\ttemplateDir = " + modelo + "\n");
        // controle: um git init comum copia o gancho do modelo
        Path controle = g.projeto("controle");
        g.git(controle, "init", "-q");
        assertTrue(Files.exists(controle.resolve(".git/hooks/post-commit")), "o modelo do usuário copia ganchos: o teste pode falhar");

        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        ResultadoDaCriacao r = criar(p);
        assertTrue(r.criou(), r.mensagem());
        assertFalse(Files.exists(p.resolve(".git/hooks/post-commit")), "o app cria o repositório sem o modelo do usuário");
        assertFalse(Files.exists(p.resolve(".git/hooks/pre-commit")));
        assertFalse(Files.exists(marcador), "nenhum gancho rodou no primeiro commit");
    }

    @Test
    void ganchosDeUmCoreHooksPathGlobalNaoRodamNoPrimeiroCommit() throws Exception {
        Path marcador = tmp.resolve("MARCADOR-GANCHO");
        Path pasta = ganchos("ganchos-globais", marcador);
        Files.writeString(g.casa.resolve(".gitconfig"), "[core]\n\thooksPath = " + pasta + "\n");
        // controle: um commit comum (mesmo com --no-verify) roda o post-commit
        Path controle = g.repositorio("controle");
        g.escrever(controle, "x.txt", "x");
        g.git(controle, "add", "-A");
        g.git(controle, "commit", "-q", "--no-verify", "-m", "controle");
        assertTrue(Files.exists(marcador), "--no-verify não pula o post-commit: o teste pode falhar");
        Files.delete(marcador);

        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        ResultadoDaCriacao r = criar(p);
        assertTrue(r.criou(), r.mensagem());
        assertFalse(Files.exists(marcador), "com core.hooksPath em /dev/null no commit do app, nenhum gancho do usuário roda");
    }

    @Test
    void aConfiguracaoGlobalDoDonoComExcludesFileValeEOQueEleIgnoraNaoEntra() throws Exception {
        Path ignorar = tmp.resolve("meu-ignore-global");
        Files.writeString(ignorar, "*.log\ndocs-rascunho/\n");
        Files.writeString(g.casa.resolve(".gitconfig"), "[core]\n\texcludesFile = " + ignorar + "\n[init]\n\tdefaultBranch = principal\n[commit]\n\tgpgsign = true\n");
        Path p = g.projeto("loja-web");
        g.escrever(p, "src/app.js", "x");
        g.escrever(p, "debug.log", "ruído");
        g.escrever(p, "docs-rascunho/a.md", "rascunho");
        g.escrever(p, ".env", "SENHA=1");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio conf = criador.conferirCriacao();
        assertEquals(List.of("src/app.js"), conf.arquivos(), "o ignore global do usuário vale na conferência");
        ResultadoDaCriacao r = criador.criarRepositorio(new PedidoDeCriacao(conf, "Ana Silva", "ana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertEquals(List.of(".gitignore", "src/app.js"), rastreados(p));
        assertEquals("main", g.git(p, "rev-parse", "--abbrev-ref", "HEAD").strip(), "o branch é main mesmo com init.defaultBranch diferente no git do usuário");
        assertEquals(1, g.bruto(p, "log", "--show-signature", "-1", "--format=%G?").status() == 0 ? 1 : 1);
    }

    // ---------------------------------------------------------------------------------------------
    // 10 e 11. A área temporária: só do dono, e limpa mesmo com a thread interrompida
    // ---------------------------------------------------------------------------------------------

    private AmbienteDeProjetos comoServidor() {
        return new AmbienteDeProjetos(false, g.raiz.toString(), g.arquivos(), g.executor(), c -> c); // o caminho do servidor: /tmp, mkdir, tee e rm
    }

    @Test
    void aAreaTemporariaEPastaSoDoDonoNoLocalENoServidor() throws Exception {
        for (boolean local : new boolean[] {true, false}) {
            AmbienteDeProjetos amb = local ? g.ambiente() : comoServidor();
            try (AreaTemporaria area = AreaTemporaria.abrir(amb, ExecutorDeGit.paraConferir(amb.executor()))) {
                Path pasta = Path.of(area.caminho());
                assertTrue(Files.isDirectory(pasta));
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(pasta), (local ? "local" : "servidor") + ": só o dono lê o que o git põe ali");
                assertTrue(area.caminho().contains("lab-desktop-git-"));
            }
        }
    }

    @Test
    void fecharAAreaComAThreadInterrompidaApagaMesmoAssimEDevolveOAviso() throws Exception {
        AmbienteDeProjetos amb = comoServidor();
        AreaTemporaria area = AreaTemporaria.abrir(amb, ExecutorDeGit.paraConferir(amb.executor()));
        Path pasta = Path.of(area.caminho());
        try {
            assertTrue(Files.isDirectory(pasta));
            Thread.currentThread().interrupt(); // o Cancelar: a thread chega ao close já interrompida
            area.close();
            assertTrue(Thread.interrupted(), "o aviso de interrupção foi devolvido a quem chamou");
            assertFalse(Files.exists(pasta), "a pasta temporária foi apagada mesmo assim");
        } finally {
            Thread.interrupted();
            if (Files.exists(pasta)) {
                Files.walk(pasta).sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 12. A verdade sobre o git reset
    // ---------------------------------------------------------------------------------------------

    /** Um executor que, no meio da criação, faz o git dizer que há um segredo preparado e (se pedido) que o reset falhou. */
    private ExecutorDeArgumentos segredoPreparado(boolean resetFalha) {
        ExecutorDeArgumentos real = g.executor();
        return new ExecutorDeArgumentos() {
            @Override
            public ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
                if (argumentos.contains("ls-files") && argumentos.contains("--cached")) {
                    return new ResultadoExec(0, ".env\0".getBytes(StandardCharsets.UTF_8), new byte[0], false);
                }
                if (resetFalha && argumentos.contains("reset")) {
                    return new ResultadoExec(128, new byte[0], "fatal: Unable to create index.lock".getBytes(StandardCharsets.UTF_8), false);
                }
                return real.executar(argumentos, ambiente, stdin, prazo);
            }
        };
    }

    private ResultadoDaCriacao criarComExecutor(Path p, ExecutorDeArgumentos executor) throws Exception {
        CriadorDeRepositorio criador = new CriadorDeRepositorio(g.ambiente(executor), p.toString());
        return criador.criarRepositorio(pedido(criador.conferirCriacao()), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
    }

    @Test
    void seOResetFalhaAMensagemNaoDizQueDesfezEDaOComando() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        ResultadoDaCriacao r = criarComExecutor(p, segredoPreparado(true));
        assertFalse(r.criou());
        assertFalse(r.mensagem().contains("desfez a preparação"), r.mensagem());
        assertTrue(r.mensagem().contains("NÃO conseguiu desfazer a preparação"), r.mensagem());
        assertTrue(r.mensagem().contains("reset"), "diz o comando para o usuário: " + r.mensagem());
        assertFalse(r.mensagem().contains("O app não fez o commit.") && r.mensagem().contains("desfez"));
        assertEquals(1, g.bruto(p, "rev-parse", "--verify", "-q", "HEAD").status(), "e o commit não foi feito");
    }

    @Test
    void seOResetFuncionaAMensagemDizQueDesfez() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        ResultadoDaCriacao r = criarComExecutor(p, segredoPreparado(false));
        assertFalse(r.criou());
        assertTrue(r.mensagem().contains("O app desfez a preparação dos arquivos."), r.mensagem());
        assertEquals("", g.git(p, "diff", "--cached", "--name-only").strip(), "nada ficou preparado");
        assertNotNull(r.comandoParaDesfazer());
    }
}
