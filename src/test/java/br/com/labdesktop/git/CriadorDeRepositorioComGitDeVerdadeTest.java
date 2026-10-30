package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Criar o repositório de um projeto com o {@code git} de verdade, por "Este computador": a conferência (só lê), a criação (init, identidade local, .gitignore, check-ignore, add da lista, commit),
 * os segredos que NUNCA entram, o repositório dentro do projeto, o arquivo grande, a identidade, criar duas vezes, sem Git, Git antigo e a falha no meio (que não apaga nada).
 */
class CriadorDeRepositorioComGitDeVerdadeTest {

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

    /** Um projeto parecido com os de verdade: segredos, node_modules, um arquivo grande e um clone de dentro. */
    private Path projetoCompleto() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, ".env", "SENHA=123\n");
        g.escrever(p, ".env.local", "SENHA=456\n");
        g.escrever(p, ".env.example", "SENHA=\n");
        g.escrever(p, "src/app.js", "console.log('oi');\n");
        g.escrever(p, "docs/leia.md", "# Leia\n");
        g.escrever(p, "ação com espaço.txt", "olá\n");
        g.escrever(p, "node_modules/x/index.js", "module.exports = 1;\n");
        g.escrever(p, "certs/chave.pem", "-----BEGIN-----\n");
        g.escrever(p, "id_rsa", "chave\n");
        g.escreverBytes(p, "dados/grande.sql", new byte[6 * 1024 * 1024]);
        // um clone de dentro (repositório com histórico próprio)
        Path clone = p.resolve("clone-externo");
        Files.createDirectories(clone);
        g.git(clone, "init", "-q", "-b", "main");
        g.git(clone, "config", "--local", "user.name", "Outro");
        g.git(clone, "config", "--local", "user.email", "outro@example.com");
        g.escrever(clone, "lib.js", "x\n");
        g.commitar(clone, "do clone");
        return p;
    }

    /** O projeto de teste tem um clone de dentro: a criação só vale com a confirmação explícita (a recusa sem ela tem testes próprios). */
    private static PedidoDeCriacao pedido(ConferenciaDoRepositorio c, boolean grandes) {
        return new PedidoDeCriacao(c, "Ana Silva", "ana@example.com", grandes, true);
    }

    private List<String> rastreados(Path p) {
        return Stream.of(g.git(p, "-c", "core.quotepath=off", "ls-files", "-z").split("\0")).filter(s -> !s.isEmpty()).sorted().toList();
    }

    // ---------------------------------------------------------------------------------------------
    // A conferência (só lê)
    // ---------------------------------------------------------------------------------------------

    @Test
    void aConferenciaMostraOQueEntraEOQueFicaDeForaSemEscreverNadaNoProjeto() throws Exception {
        Path p = projetoCompleto();
        String antes = fotografia(p);
        long areasAntes = areasTemporarias();
        ConferenciaDoRepositorio c = criador(p).conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, c.situacao(), String.valueOf(c.falha()));
        assertTrue(c.liberaACriacao());
        assertEquals(Set.of(".env.example", "src/app.js", "docs/leia.md", "ação com espaço.txt", "dados/grande.sql"), Set.copyOf(c.arquivos()), "node_modules, .env, .env.local, chave.pem e id_rsa não entram: " + c.arquivos());
        assertEquals(List.of("clone-externo"), c.aninhados());
        assertEquals(List.of("dados/grande.sql"), c.caminhosGrandes());
        assertEquals(6 * 1024 * 1024, c.grandes().get(0).tamanho());
        assertTrue(c.protecaoConfirmada());
        assertTrue(c.segredosQueEntrariam().isEmpty());
        assertNull(c.gitignoreAtual());
        assertFalse(c.temIdentidade(), "o git isolado do teste não tem nome nem e-mail");
        assertTrue(c.versaoDoGit().startsWith("git version"));
        assertEquals(4, c.quantosEntram(false));
        assertEquals(5, c.quantosEntram(true));
        assertTrue(c.primeirasPastas(3).contains("(raiz) (2)"), c.primeirasPastas(3).toString());
        // o plano do .gitignore: segredos, lixo, o repositório de dentro e o arquivo grande, nesta ordem
        PlanejadorDoGitignore.Plano plano = c.planoDoGitignore(false);
        assertTrue(plano.linhasNovas().containsAll(SegredosDoProjeto.todasAsLinhas()));
        assertTrue(plano.linhasNovas().contains("/clone-externo/"));
        assertTrue(plano.linhasNovas().contains("/dados/grande.sql"));
        assertFalse(c.planoDoGitignore(true).linhasNovas().contains("/dados/grande.sql"), "com a caixa marcada o arquivo grande entra");
        // nada foi escrito no projeto, e a área temporária sumiu
        assertEquals(antes, fotografia(p));
        assertFalse(Files.exists(p.resolve(".git")));
        assertFalse(Files.exists(p.resolve(".gitignore")));
        assertEquals(areasAntes, areasTemporarias(), "a área temporária foi apagada");
        assertTrue(g.comandos.stream().allMatch(cmd -> ComandosDeGit.naturezaPelaForma(cmd) != ComandoGit.Natureza.ESCRITA), "a conferência só lê (e usa a área temporária): " + g.comandos);
    }

    @Test
    void umGitignoreDeDentroQueLiberaOEnvImpedeACriacaoEDizOArquivo() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "src/app.js", "x");
        g.escrever(p, "sub/.gitignore", "!.env\n");
        g.escrever(p, "sub/.env", "SENHA=1\n");
        ConferenciaDoRepositorio c = criador(p).conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, c.situacao());
        assertEquals(List.of("sub/.env"), c.segredosQueEntrariam());
        assertFalse(c.liberaACriacao(), "um segredo entraria: não libera");
        ResultadoDaCriacao r = criador(p).criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertFalse(Files.exists(p.resolve(".git")), "nem o git init rodou");
    }

    @Test
    void umGitignoreQueLiberaOEnvImpedeMesmoSemOArquivoPorqueOGitNaoConfirmaAProtecao() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "src/app.js", "x");
        g.escrever(p, ".gitignore", "!.env\n!*.pem\n");
        ConferenciaDoRepositorio c = criador(p).conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, c.situacao());
        assertTrue(c.segredosQueEntrariam().isEmpty(), "não há arquivo de segredo na pasta");
        assertFalse(c.protecaoConfirmada(), "mas o git diz que um .env novo entraria: o app não confirma a proteção");
        assertFalse(c.liberaACriacao());
        assertFalse(Files.exists(p.resolve(".git")));
    }

    @Test
    void mesmoComUmaConferenciaAdulteradaOAppConfereOSegredoDeNovoAntesDoAdd() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "src/app.js", "x");
        g.escrever(p, "sub/.gitignore", "!.env\n");
        g.escrever(p, "sub/.env", "SENHA=1\n");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio real = criador.conferirCriacao();
        assertFalse(real.liberaACriacao(), "a conferência de verdade já barra");
        // alguém adultera uma conferência que libera e lista o segredo como arquivo comum
        ConferenciaDoRepositorio adulterada = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, real.versaoDoGit(), "Ana", "ana@example.com", List.of("src/app.js", "sub/.env", "sub/.gitignore"), List.of(),
            List.of(), null, List.of(), true, false, 0);
        ResultadoDaCriacao r = criador.criarRepositorio(new PedidoDeCriacao(adulterada, null, null, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertTrue(r.mensagem().contains("sub/.env"), r.mensagem());
        assertEquals(List.of(), rastreados(p), "nada foi adicionado");
        assertTrue(g.comandos.stream().noneMatch(cmd -> cmd.contains("add")), "o git add nem rodou: " + g.comandos);
    }

    @Test
    void projetoSemNenhumArquivoGanhaSoOGitignoreNoPrimeiroCommit() throws Exception {
        Path p = g.projeto("loja-web");
        ConferenciaDoRepositorio c = criador(p).conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, c.situacao());
        assertTrue(c.arquivos().isEmpty());
        ResultadoDaCriacao r = criador(p).criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertEquals(1, r.arquivosNoCommit());
        assertEquals(List.of(".gitignore"), rastreados(p));
    }

    @Test
    void muitosArquivosPassamDoTetoDaSaidaEAConferenciaListaTodosPorPasta() throws Exception {
        Path p = g.projeto("app-exemplo");
        int total = 0;
        for (int d = 0; d < 12; d++) {
            for (int i = 0; i < 150; i++) {
                g.escrever(p, "modulo-numero-" + d + "/subpasta-com-nome-comprido-" + i + "/arquivo-de-codigo-" + i + ".txt", "x");
                total++;
            }
        }
        ConferenciaDoRepositorio c = criador(p).conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, c.situacao(), String.valueOf(c.falha()));
        assertFalse(c.listaIncompleta(), "a lista foi pedida pasta por pasta");
        assertEquals(total, c.arquivos().size());
        assertTrue(c.liberaACriacao());
    }

    // ---------------------------------------------------------------------------------------------
    // A criação
    // ---------------------------------------------------------------------------------------------

    @Test
    void criaORepositorioSemOEnvSemNodeModulesSemOCloneESemOArquivoGrande() throws Exception {
        Path p = projetoCompleto();
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        List<String> passos = new ArrayList<>();
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(c, false), passos::add, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertTrue(r.repositorioCriado());
        assertEquals(5, r.arquivosNoCommit(), "os quatro conferidos mais o .gitignore");
        assertNull(r.comandoParaDesfazer());
        assertNull(r.falha());
        assertTrue(r.naoFeito().isEmpty());
        assertFalse(r.hashCurto().isEmpty());

        assertEquals(List.of(".env.example", ".gitignore", "ação com espaço.txt", "docs/leia.md", "src/app.js"), rastreados(p), "os quatro conferidos mais o .gitignore que o app ajustou");
        List<String> dentro = rastreados(p);
        assertTrue(dentro.containsAll(List.of(".env.example", "src/app.js", "docs/leia.md", "ação com espaço.txt")), dentro.toString());
        for (String fora : List.of(".env", ".env.local", "certs/chave.pem", "id_rsa", "node_modules/x/index.js", "dados/grande.sql", "clone-externo", "clone-externo/lib.js")) {
            assertFalse(dentro.contains(fora), "NÃO pode ter entrado: " + fora + " em " + dentro);
        }
        assertFalse(dentro.stream().anyMatch(SegredosDoProjeto::pareceSegredo));
        assertEquals("main", g.git(p, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertEquals("Primeiro commit", g.git(p, "log", "-1", "--format=%s").strip());
        assertEquals("1", g.git(p, "rev-list", "--count", "HEAD").strip());
        assertEquals("", g.git(p, "status", "--porcelain").strip(), "tudo o que sobrou está ignorado: o status fica limpo");
        // o .gitignore tem as linhas, inclusive as do arquivo grande e do clone
        String gi = Files.readString(p.resolve(".gitignore"));
        assertTrue(gi.contains(SegredosDoProjeto.LINHA_DO_ENV) && gi.contains(SegredosDoProjeto.LINHA_DA_EXCECAO) && gi.contains("node_modules/") && gi.contains("/clone-externo/") && gi.contains("/dados/grande.sql"), gi);
        // os arquivos continuam no disco
        assertTrue(Files.exists(p.resolve(".env")) && Files.exists(p.resolve("clone-externo/.git")) && Files.exists(p.resolve("dados/grande.sql")));
        assertEquals(8, passos.size(), passos.toString());
    }

    @Test
    void comACaixaMarcadaOArquivoGrandeEntraENaoGanhaLinhaNoGitignore() throws Exception {
        Path p = projetoCompleto();
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(c, true), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertEquals(6, r.arquivosNoCommit());
        assertTrue(rastreados(p).contains("dados/grande.sql"));
        assertFalse(Files.readString(p.resolve(".gitignore")).contains("grande.sql"));
        assertFalse(rastreados(p).contains(".env"));
    }

    @Test
    void aIdentidadeSoVaiParaORepositorioNuncaParaAConfiguracaoGlobal() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        assertFalse(c.temIdentidade());
        ResultadoDaCriacao r = criador.criarRepositorio(new PedidoDeCriacao(c, "Joana D'Ávila", "joana@example.com", false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertEquals("Joana D'Ávila", g.git(p, "config", "--local", "user.name").strip());
        assertEquals("joana@example.com", g.git(p, "config", "--local", "user.email").strip());
        assertEquals("Joana D'Ávila", g.git(p, "log", "-1", "--format=%an").strip());
        assertFalse(Files.exists(g.casa.resolve(".gitconfig")), "a configuração global do git não foi tocada");
        assertTrue(g.comandos.stream().noneMatch(cmd -> cmd.contains("--global") || cmd.contains("--system")), "nenhum comando global: " + g.comandos);
        assertTrue(g.comandos.stream().filter(cmd -> cmd.contains("config") && cmd.contains("user.name")).anyMatch(cmd -> cmd.contains("--local")));
    }

    @Test
    void comIdentidadeJaConfiguradaOAppNaoGravaNada() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        Files.writeString(g.casa.resolve(".gitconfig"), "[user]\n\tname = Dona Global\n\temail = global@example.com\n");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        assertTrue(c.temIdentidade());
        assertEquals("Dona Global", c.nomeAtual());
        ResultadoDaCriacao r = criador.criarRepositorio(new PedidoDeCriacao(c, null, null, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertTrue(r.criou(), r.mensagem());
        assertEquals("Dona Global", g.git(p, "log", "-1", "--format=%an").strip());
        assertEquals(1, g.bruto(p, "config", "--local", "user.name").status(), "nada local");
        assertTrue(g.comandos.stream().noneMatch(cmd -> cmd.contains("--local") && cmd.contains("config")));
    }

    @Test
    void semIdentidadeENadaNaJanelaRecusaSemCriarNada() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        for (PedidoDeCriacao ruim : List.of(new PedidoDeCriacao(c, null, null, false), new PedidoDeCriacao(c, "Ana", "isso-nao-e-email", false), new PedidoDeCriacao(c, "", "ana@example.com", false),
            new PedidoDeCriacao(c, "Ana <x@y>", "ana@example.com", false))) {
            ResultadoDaCriacao r = criador.criarRepositorio(ruim, OperacoesDeGit.Observador.NENHUM, new Cancelamento());
            assertFalse(r.criou());
            assertFalse(r.repositorioCriado());
            assertTrue(r.mensagem().contains("Nada foi feito"), r.mensagem());
        }
        assertFalse(Files.exists(p.resolve(".git")));
    }

    @Test
    void criarDuasVezesARecusaNaSegundaENaoMudaNada() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        assertTrue(criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento()).criou());
        String head = g.git(p, "rev-parse", "HEAD").strip();
        // a mesma conferência de antes, clicada de novo
        ResultadoDaCriacao de = criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(de.criou());
        assertFalse(de.repositorioCriado());
        assertTrue(de.mensagem().contains("já é um repositório"), de.mensagem());
        assertEquals(head, g.git(p, "rev-parse", "HEAD").strip());
        assertEquals("1", g.git(p, "rev-list", "--count", "HEAD").strip());
        // e uma conferência nova diz o mesmo
        ConferenciaDoRepositorio nova = criador.conferirCriacao();
        assertEquals(Situacao.JA_E_REPOSITORIO, nova.situacao());
        assertFalse(nova.liberaACriacao());
    }

    @Test
    void pastaDentroDeOutroRepositorioNaoCria() throws Exception {
        Path pai = g.repositorio("pai");
        Path filho = pai.resolve("loja-web");
        Files.createDirectories(filho);
        g.escrever(filho, "a.txt", "a");
        ConferenciaDoRepositorio c = criador(filho).conferirCriacao();
        assertEquals(Situacao.DENTRO_DE_OUTRO, c.situacao());
        assertFalse(c.liberaACriacao());
        assertFalse(Files.exists(filho.resolve(".git")));
    }

    @Test
    void gitignoreDoDonoContinuaInteiroEComCrlfEOAppSoAcrescenta() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "a.txt", "a");
        String dono = "# do usuário\r\ndist/\r\nnode_modules/\r\n";
        Files.writeString(p.resolve(".gitignore"), dono);
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        assertEquals(dono, c.gitignoreAtual());
        assertFalse(c.planoDoGitignore(false).linhasNovas().contains("node_modules/"));
        assertTrue(criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento()).criou());
        String depois = Files.readString(p.resolve(".gitignore"));
        assertTrue(depois.startsWith(dono + "\r\n"), depois.replace("\r", "\\r"));
        assertFalse(depois.replace("\r\n", "").contains("\n"), "continua CRLF");
        assertTrue(rastreados(p).contains(".gitignore"));
    }

    @Test
    void arquivoComNomeEstranhoNaoQuebraAConferenciaEFicaDeFora() throws Exception {
        Path p = g.projeto("loja-web");
        g.escrever(p, "bom.txt", "a");
        Files.writeString(p.resolve("duas\nlinhas.txt"), "x");
        ConferenciaDoRepositorio c = criador(p).conferirCriacao();
        assertEquals(Situacao.PODE_CRIAR, c.situacao());
        assertEquals(List.of("bom.txt"), c.arquivos());
        assertEquals(1, c.ignoradosPorNome());
    }

    // ---------------------------------------------------------------------------------------------
    // Quando algo falta ou falha
    // ---------------------------------------------------------------------------------------------

    private static ExecutorDeArgumentos executorSemGit() {
        return (argumentos, ambiente, stdin, prazo) -> {
            throw new ProgramaNaoEncontradoException("git");
        };
    }

    @Test
    void semGitAConferenciaDizGitAusenteENaoEscreveNada() throws Exception {
        Path p = g.projeto("loja-web");
        CriadorDeRepositorio criador = new CriadorDeRepositorio(g.ambiente(executorSemGit()), p.toString());
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        assertEquals(Situacao.GIT_AUSENTE, c.situacao());
        assertEquals(FalhaDoGit.Tipo.GIT_NAO_INSTALADO, c.falha().tipo());
        assertFalse(c.liberaACriacao());
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertFalse(Files.exists(p.resolve(".git")));
    }

    @Test
    void semGitPorSshAFraseDoShellTambemVira() throws Exception {
        Path p = g.projeto("loja-web");
        ExecutorDeArgumentos shell = (argumentos, ambiente, stdin, prazo) -> new ResultadoExec(127, new byte[0], "sh: 1: git: not found\n".getBytes(StandardCharsets.UTF_8), false);
        ConferenciaDoRepositorio c = new CriadorDeRepositorio(g.ambiente(shell), p.toString()).conferirCriacao();
        assertEquals(Situacao.GIT_AUSENTE, c.situacao());
    }

    @Test
    void gitAntigoDemaisNaoCria() throws Exception {
        Path p = g.projeto("loja-web");
        ExecutorDeArgumentos antigo = (argumentos, ambiente, stdin, prazo) -> new ResultadoExec(0, "git version 2.20.1\n".getBytes(StandardCharsets.UTF_8), new byte[0], false);
        ConferenciaDoRepositorio c = new CriadorDeRepositorio(g.ambiente(antigo), p.toString()).conferirCriacao();
        assertEquals(Situacao.GIT_ANTIGO, c.situacao());
        assertEquals(FalhaDoGit.Tipo.GIT_ANTIGO, c.falha().tipo());
        assertTrue(c.falha().frase().contains("2.28"));
    }

    /** Um fabricante de arquivos em que gravar o {@code .gitignore} falha (permissão). */
    private FabricaDeClienteSftp fabricaQueNaoGrava() {
        FabricaDeClienteSftp real = g.arquivos();
        return () -> {
            ClienteSftp c = real.criar();
            return (ClienteSftp) Proxy.newProxyInstance(ClienteSftp.class.getClassLoader(), new Class<?>[] {ClienteSftp.class}, (proxy, metodo, args) -> {
                if (metodo.getName().equals("gravarAtomico")) {
                    throw new ErroSftp(ErroSftp.Tipo.SEM_PERMISSAO, "Sem permissão para gravar aqui.");
                }
                try {
                    return metodo.invoke(c, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        };
    }

    @Test
    void seOGitignoreNaoGravaOAppDizOQueFezOQueNaoFezEOComandoEOGitContinuaLa() throws Exception {
        Path p = projetoCompleto();
        AmbienteDeProjetos amb = new AmbienteDeProjetos(true, g.raiz.toString(), fabricaQueNaoGrava(), g.executor(), c -> c);
        CriadorDeRepositorio criador = new CriadorDeRepositorio(amb, p.toString());
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertTrue(r.repositorioCriado(), "o git init já tinha rodado");
        assertTrue(r.feito().get(0).contains("git init"));
        assertTrue(r.naoFeito().stream().anyMatch(s -> s.contains(".gitignore")), r.naoFeito().toString());
        assertTrue(r.naoFeito().stream().anyMatch(s -> s.contains("git add")));
        assertTrue(r.naoFeito().stream().anyMatch(s -> s.contains("primeiro commit")));
        assertEquals("rm -rf " + p + "/.git", r.comandoParaDesfazer());
        assertTrue(r.mensagem().contains("NÃO apaga nada sozinho"), r.mensagem());
        assertTrue(r.mensagem().contains("Sem permissão para gravar aqui."), r.mensagem());
        assertTrue(Files.isDirectory(p.resolve(".git")), "o app NÃO apagou o .git");
        assertEquals(List.of(), rastreados(p), "e não adicionou nenhum arquivo (nem o .env)");
        assertEquals(1, g.bruto(p, "rev-parse", "--verify", "-q", "HEAD").status(), "nem fez commit");
        assertTrue(g.comandos.stream().noneMatch(cmd -> cmd.contains("add")), "nenhum git add rodou: " + g.comandos);
        assertTrue(g.comandos.stream().noneMatch(cmd -> cmd.contains("rm")), "o app não rodou rm");
    }

    @Test
    void cancelarNoMeioDeixaORepositorioPelaMetadeEDizComoDesfazer() throws Exception {
        Path p = projetoCompleto();
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        Cancelamento cancelamento = new Cancelamento();
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(c, false), frase -> {
            if (frase.startsWith("Gravando quem assina")) {
                cancelamento.cancelar();
            }
        }, cancelamento);
        assertFalse(r.criou());
        assertTrue(r.repositorioCriado());
        assertTrue(r.mensagem().contains("cancelou"));
        assertFalse(r.naoFeito().isEmpty());
        assertNotNull(r.comandoParaDesfazer());
        assertTrue(Files.isDirectory(p.resolve(".git")));
        assertEquals(List.of(), rastreados(p));
    }

    @Test
    void comandoParaDesfazerUsaAspasQuandoAPastaTemEspaco() throws Exception {
        Path p = g.projeto("meu projeto");
        g.escrever(p, "a.txt", "a");
        AmbienteDeProjetos amb = new AmbienteDeProjetos(true, g.raiz.toString(), fabricaQueNaoGrava(), g.executor(), c -> c);
        CriadorDeRepositorio criador = new CriadorDeRepositorio(amb, p.toString());
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        ResultadoDaCriacao r = criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertEquals("rm -rf '" + p + "/.git'", r.comandoParaDesfazer());
    }

    @Test
    void nenhumComandoDeEscritaTemForcaPushRemotoOuGlobal() throws Exception {
        Path p = projetoCompleto();
        CriadorDeRepositorio criador = criador(p);
        ConferenciaDoRepositorio c = criador.conferirCriacao();
        assertTrue(criador.criarRepositorio(pedido(c, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento()).criou());
        for (List<String> cmd : g.comandos) {
            for (String proibido : List.of("-f", "--force", "push", "remote", "--global", "--system", "-A", "--all", ".")) {
                assertFalse(cmd.contains(proibido), proibido + " em " + cmd);
            }
        }
        List<List<String>> adds = g.comandos.stream().filter(cmd -> cmd.contains("add")).toList();
        assertEquals(1, adds.size());
        assertTrue(adds.get(0).contains("--pathspec-from-file=-"));
    }

    // ---------------------------------------------------------------------------------------------

    private String fotografia(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            return s.sorted().map(x -> {
                try {
                    return p.relativize(x) + ":" + (Files.isDirectory(x) ? "d" : Files.size(x) + ":" + Files.getLastModifiedTime(x).toMillis());
                } catch (IOException e) {
                    return x.toString();
                }
            }).collect(Collectors.joining("\n"));
        }
    }

    private long areasTemporarias() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(x -> x.getFileName().toString().startsWith("lab-desktop-git-")).count();
        }
    }

}
