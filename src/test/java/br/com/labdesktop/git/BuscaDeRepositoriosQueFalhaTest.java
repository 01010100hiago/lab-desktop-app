package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ResultadoExec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * O {@code find} que o app manda pelo SSH pode falhar de um jeito que NÃO é "nenhum repositório": um servidor cujo {@code find} não conhece um predicado (BusyBox, outro Unix) sai com 1 e
 * uma saída vazia; sem {@code find} sai com 127. Tratar isso como "sem Git" liberaria criar um repositório por cima de um {@code app/.git} que existe. Aqui o {@code find} é um executor de mentira
 * que devolve o que o teste manda: só um erro de LEITURA de pasta (Permission denied, Not a directory) deixa o resultado valer. Também: nome de pasta que o app não mostra com segurança nunca some
 * em silêncio, e o cache de 60 s da procura.
 */
class BuscaDeRepositoriosQueFalhaTest {

    private static final String RAIZ = "/srv/lab/projects/loja-web";
    private static final String BIDI = String.valueOf((char) 0x202E);

    private final List<List<String>> comandos = new CopyOnWriteArrayList<>();
    private final AtomicInteger finds = new AtomicInteger();

    /** Um servidor sem Git na pasta principal, cujo {@code find} devolve o que se mandar. */
    private ExecutorDeArgumentos servidor(int status, String stdout, String stderr) {
        return (args, ambiente, stdin, prazo) -> {
            comandos.add(List.copyOf(args));
            if (args.get(0).equals("find")) {
                if (args.get(2).equals(RAIZ)) {
                    finds.incrementAndGet(); // só a procura da pasta principal (o leitor falso da subpasta também acaba procurando, porque a subpasta de mentira não é repositório)
                }
                return new ResultadoExec(status, stdout.getBytes(StandardCharsets.UTF_8), stderr.getBytes(StandardCharsets.UTF_8), false);
            }
            if (args.contains("--version")) {
                return new ResultadoExec(0, "git version 2.43.0\n".getBytes(StandardCharsets.UTF_8), new byte[0], false);
            }
            return new ResultadoExec(128, new byte[0], "fatal: not a git repository (or any of the parent directories): .git\n".getBytes(StandardCharsets.UTF_8), false);
        };
    }

    private ResumoDoRepositorio resumo(ExecutorDeArgumentos e) throws Exception {
        return new LeitorDeGitPorExecutor(e, RAIZ, false).resumo().valorOuNulo();
    }

    private CriadorDeRepositorio criador(ExecutorDeArgumentos e) {
        return new CriadorDeRepositorio(new AmbienteDeProjetos(false, "/srv/lab/projects", () -> null, e, x -> x), RAIZ);
    }

    private static PedidoDeCriacao pedidoComConferenciaAdulterada(boolean confirmou) {
        ConferenciaDoRepositorio adulterada = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, "git version 2.43.0", "Ana", "ana@example.com", List.of("docker-compose.yml"), List.of(), List.of(), null,
            List.of(), true, false, 0);
        return new PedidoDeCriacao(adulterada, null, null, false, confirmou);
    }

    private boolean rodouEscrita() {
        return comandos.stream().anyMatch(c -> c.contains("init") || c.contains("add") || c.contains("commit"));
    }

    // ---------------------------------------------------------------------------------------------
    // O status do find
    // ---------------------------------------------------------------------------------------------

    @Test
    void findQueSaiComUmPorErroDeUsoNaoViraSemGitNemLiberaCriarPorCima() throws Exception {
        ExecutorDeArgumentos e = servidor(1, "", "find: unknown predicate `-xdev'\n");
        ResumoDoRepositorio r = resumo(e);
        assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado(), "uma saída vazia de um find que não rodou NÃO é 'nenhum repositório'");
        assertTrue(r.falha().frase().contains("não consegui procurar repositórios nas subpastas"), r.falha().frase());
        assertTrue(r.falha().frase().contains("o find respondeu com erro (código 1): find: unknown predicate"), r.falha().frase());

        ConferenciaDoRepositorio c = criador(e).conferirCriacao();
        assertEquals(Situacao.NAO_CONFERI, c.situacao());
        assertFalse(c.liberaACriacao());
        assertTrue(c.falha().frase().contains("o find respondeu com erro"), c.falha().frase());

        // o motor repete a busca na hora de criar e também não cria
        ResultadoDaCriacao criacao = criador(e).criarRepositorio(pedidoComConferenciaAdulterada(false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(criacao.criou());
        assertFalse(criacao.repositorioCriado());
        assertFalse(rodouEscrita(), "nem init, add nem commit: " + comandos);
        // nem com a confirmação: sem conseguir procurar, o motor não sabe o que está confirmando
        ResultadoDaCriacao confirmado = criador(e).criarRepositorio(pedidoComConferenciaAdulterada(true), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(confirmado.criou());
        assertFalse(rodouEscrita(), comandos.toString());
    }

    @Test
    void saidaUmSoComErrosDeLeituraDePastaMantemOQueOFindAchou() throws Exception {
        String erro = "find: ‘/srv/lab/projects/loja-web/dados/pg’: Permission denied\nfind: /srv/lab/projects/loja-web/x: Not a directory\n";
        ResumoDoRepositorio r = resumo(servidor(1, RAIZ + "/app/.git\0", erro));
        assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, r.estado(), String.valueOf(r.falha()));
        assertEquals(List.of("app"), r.abaixo().stream().map(RepositorioAbaixo::caminho).toList());
        // e, sem achar nada, 'sem Git' de verdade (o find rodou e só não leu uma pasta)
        assertEquals(ResumoDoRepositorio.Estado.SEM_GIT, resumo(servidor(1, "", erro)).estado());
    }

    @Test
    void qualquerOutraCoisaNoErroOuOutroCodigoEErroDeBusca() throws Exception {
        String permissao = "find: /srv/lab/projects/loja-web/dados/pg: Permission denied\n";
        for (String[] caso : new String[][] {{"1", permissao + "find: unknown predicate `-xdev'\n"}, {"1", ""}, {"1", "   \n"}, {"1", "find: illegal option -- x\n"}, {"2", permissao}, {"4", ""},
            {"124", ""}, {"137", "Killed\n"}}) {
            ResumoDoRepositorio r = resumo(servidor(Integer.parseInt(caso[0]), RAIZ + "/app/.git\0", caso[1]));
            assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado(), "código " + caso[0] + " com '" + caso[1].strip() + "'");
        }
    }

    @Test
    void semFindNoServidorADizComPalavrasProprias() throws Exception {
        ExecutorDeArgumentos e = servidor(127, "", "env: 'find': No such file or directory\n");
        ResumoDoRepositorio r = resumo(e);
        assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado(), "não é 'Git não instalado': o git está lá");
        assertNotEquals(ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO, r.estado());
        assertTrue(r.falha().frase().contains("o comando find não existe neste servidor"), r.falha().frase());
        assertTrue(r.falha().frase().contains("não consegui procurar repositórios nas subpastas"), r.falha().frase());
        assertFalse(r.falha().frase().contains("Git não está instalado"));
        ConferenciaDoRepositorio c = criador(e).conferirCriacao();
        assertEquals(Situacao.NAO_CONFERI, c.situacao(), "a conferência não vira 'Git ausente'");
        assertTrue(c.falha().frase().contains("o comando find não existe neste servidor"), c.falha().frase());
    }

    @Test
    void soErrosDeLeituraConfereCadaLinha() {
        assertTrue(RepositoriosAbaixo.soErrosDeLeitura("find: /a/b: Permission denied"));
        assertTrue(RepositoriosAbaixo.soErrosDeLeitura("find: ‘/a/b’: Permission denied\nfind: ‘/a/c’: Not a directory\n"));
        assertTrue(RepositoriosAbaixo.soErrosDeLeitura("find: /a: Permission denied\r\nfind: /b: Not a directory\r\n"));
        assertFalse(RepositoriosAbaixo.soErrosDeLeitura(""));
        assertFalse(RepositoriosAbaixo.soErrosDeLeitura(null));
        assertFalse(RepositoriosAbaixo.soErrosDeLeitura("find: /a: Permission denied\nfind: unknown predicate `-xdev'"));
        assertFalse(RepositoriosAbaixo.soErrosDeLeitura("find: /a: No such file or directory"));
        assertFalse(RepositoriosAbaixo.soErrosDeLeitura("Permission denied"));
        assertFalse(RepositoriosAbaixo.soErrosDeLeitura("find: /a: Permission denied e mais um texto"));
    }

    // ---------------------------------------------------------------------------------------------
    // Nome de pasta que o app não mostra com segurança
    // ---------------------------------------------------------------------------------------------

    @Test
    void repositorioSoEmPastaDeNomeIlegivelNaoViraSemGitEBloqueiaCriar() throws Exception {
        ExecutorDeArgumentos e = servidor(0, RAIZ + "/pasta" + BIDI + "cod/.git\0", "");
        ResumoDoRepositorio r = resumo(e);
        assertEquals(ResumoDoRepositorio.Estado.ERRO, r.estado(), "existe repositório, só que o nome não se mostra: nunca 'sem Git'");
        assertTrue(r.falha().frase().contains("nome que o app não mostra com segurança (1)"), r.falha().frase());
        assertFalse(r.falha().frase().contains(BIDI), "o nome estranho não vai para a tela");

        ConferenciaDoRepositorio c = criador(e).conferirCriacao();
        assertEquals(Situacao.NAO_CONFERI, c.situacao());
        assertFalse(c.liberaACriacao());
        assertFalse(c.falha().frase().contains(BIDI));

        ResultadoDaCriacao criacao = criador(e).criarRepositorio(pedidoComConferenciaAdulterada(false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(criacao.criou());
        assertTrue(criacao.mensagem().contains("pasta com nome ilegível (1)"), criacao.mensagem());
        assertFalse(criacao.mensagem().contains(BIDI));
        assertFalse(rodouEscrita(), comandos.toString());
    }

    @Test
    void comNomesLegiveisEIlegiveisJuntosAListaTemOsLegiveisEODizOResto() throws Exception {
        ResumoDoRepositorio r = resumo(servidor(0, RAIZ + "/app/.git\0" + RAIZ + "/x" + BIDI + "y/.git\0" + RAIZ + "/z\\w/.git\0", ""));
        assertEquals(ResumoDoRepositorio.Estado.REPOSITORIOS_ABAIXO, r.estado());
        assertEquals(List.of("app"), r.abaixo().stream().map(RepositorioAbaixo::caminho).toList());
        assertEquals(2, r.ilegiveis(), "a pasta com bidi e a com barra invertida no nome");
    }

    // ---------------------------------------------------------------------------------------------
    // O cache de 60 s
    // ---------------------------------------------------------------------------------------------

    @Test
    void oCacheGuardaPor60sEAtualizarEOEsquecerPedemProcuraDeVerdade() throws Exception {
        ExecutorDeArgumentos e = servidor(0, RAIZ + "/app/.git\0", "");
        CacheDeBusca cache = new CacheDeBusca();
        LeitorDeGitPorExecutor leitor = new LeitorDeGitPorExecutor(e, RAIZ, false, cache, null);
        leitor.resumo();
        leitor.resumo();
        leitor.resumo();
        assertEquals(1, finds.get(), "três leituras seguidas, uma procura só");
        leitor.esquecerABusca();
        leitor.resumo();
        assertEquals(2, finds.get(), "Atualizar procura de verdade");

        // pelo leitor do projeto: esquecer chega até o cache do leitor da pasta principal
        LeitorDeGitDoProjeto lp = new LeitorDeGitDoProjeto(() -> new LeitorDeGitPorExecutor(e, RAIZ, false, cache, null), sub -> new LeitorDeGitPorExecutor(e, ValidadorDeGit.juntar(RAIZ, sub), false, null, sub),
            LeitorDeGitDoProjeto.Memoria.NENHUMA);
        lp.resumo();
        assertEquals(2, finds.get(), "ainda vale");
        lp.esquecerABusca();
        lp.resumo();
        assertEquals(3, finds.get());
    }

    @Test
    void oCacheComRelogioFalsoVence60sEFalhaNuncaFicaGuardada() throws Exception {
        Instant[] agora = {Instant.parse("2026-10-30T12:00:00Z")};
        CacheDeBusca cache = new CacheDeBusca(() -> agora[0], Duration.ofSeconds(60));
        AtomicInteger chamadas = new AtomicInteger();
        RepositoriosAbaixo.Buscador real = pasta -> {
            chamadas.incrementAndGet();
            return new RepositoriosAbaixo.Achados(List.of("app"), false);
        };
        cache.obter(RAIZ, real);
        agora[0] = agora[0].plusSeconds(59);
        cache.obter(RAIZ, real);
        assertEquals(1, chamadas.get(), "aos 59 s ainda vale");
        agora[0] = agora[0].plusSeconds(1);
        cache.obter(RAIZ, real);
        assertEquals(2, chamadas.get(), "aos 60 s venceu");
        cache.obter("/srv/lab/projects/outro", real);
        assertEquals(3, chamadas.get(), "o cache é de UMA pasta: outra pasta procura de verdade");
        // falha não fica guardada
        AtomicInteger tentativas = new AtomicInteger();
        RepositoriosAbaixo.Buscador falha = pasta -> {
            if (tentativas.incrementAndGet() == 1) {
                throw new ErroDoGit(new FalhaDoGit(FalhaDoGit.Tipo.DEMOROU, "demorou"));
            }
            return RepositoriosAbaixo.Achados.NENHUM;
        };
        CacheDeBusca c2 = new CacheDeBusca(() -> agora[0], Duration.ofSeconds(60));
        org.junit.jupiter.api.Assertions.assertThrows(ErroDoGit.class, () -> c2.obter(RAIZ, falha));
        c2.obter(RAIZ, falha);
        assertEquals(2, tentativas.get(), "a falha não ficou guardada: a segunda leitura procurou de novo");
        c2.obter(RAIZ, falha);
        assertEquals(2, tentativas.get());
    }

    @Test
    void aCriacaoNuncaUsaOCacheEProcuraDeVerdadeAntesDeEscrever() throws Exception {
        ExecutorDeArgumentos e = servidor(0, RAIZ + "/app/.git\0", "");
        criador(e).criarRepositorio(pedidoComConferenciaAdulterada(false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        criador(e).criarRepositorio(pedidoComConferenciaAdulterada(false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertEquals(2, finds.get(), "cada criação procura de novo");
    }
}
