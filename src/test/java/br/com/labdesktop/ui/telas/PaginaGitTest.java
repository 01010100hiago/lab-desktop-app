package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.git.ArquivoDoCommit;
import br.com.labdesktop.git.Branch;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.DetalheDoCommit;
import br.com.labdesktop.git.DiffDoArquivo;
import br.com.labdesktop.git.FalhaDoGit;
import br.com.labdesktop.git.LinhaDeDiff;
import br.com.labdesktop.git.Pendencia;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.git.StatusDoGit;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.LeitorDeGitFalso;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A aba Git da página do projeto, só leitura, com um leitor de mentira: o que a lista mostra, o detalhe de um commit (cabeçalho, arquivos, diff), as alterações e os branches, cada estado
 * (carregando, sem Git, Git ausente, dono diferente, erro, vazio, muitos commits, sem conexão), o Copiar e, sobretudo, QUANDO ela lê: ao abrir e no Atualizar, nunca de tempos em tempos.
 */
@ExtendWith(VerificaEdt.class)
class PaginaGitTest {

    private static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    static void esperar(String o_que, Supplier<Boolean> condicao) {
        Espera.ate(Duration.ofSeconds(10), o_que, () -> Visual.naEdt(condicao));
    }

    /** A página aberta na aba Git, com o leitor de mentira ligado. */
    static final class Cena {
        final TelaProjetoTest.Cena cena;
        final LeitorDeGitFalso leitor = new LeitorDeGitFalso();
        final PaginaGit pagina;
        final List<String> pedidos = new java.util.concurrent.CopyOnWriteArrayList<>();

        Cena(boolean acoes) {
            this.cena = new TelaProjetoTest.Cena(acoes, Aba.GIT).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
            cena.tela.contexto().leitorDeGit = () -> leitor;
            cena.tela.contexto().criarRepositorio = (depois, ver) -> pedidos.add("criar");
            cena.tela.contexto().instalarGit = depois -> pedidos.add("instalar");
            this.pagina = ed(cena.tela::git);
        }

        void ler() {
            Visual.naEdt(pagina::recarregar);
            esperar("terminar de ler", () -> !pagina.carregando());
        }

        Cena comCommits(int n) {
            leitor.commits = LeitorDeGitFalso.commits(n);
            leitor.resumo = new ResultadoDoGit.Ok<>(LeitorDeGitFalso.comGit("main", n == 0 ? null : LeitorDeGitFalso.commit(n), 2));
            for (Commit c : leitor.commits) {
                leitor.detalhes.put(c.hash(), LeitorDeGitFalso.detalhe(c, "Mensagem inteira do commit " + c.curto() + "\n\nCom corpo.",
                    new ArquivoDoCommit("src/app.js", null, 3, 1), new ArquivoDoCommit("logo.png", null, -1, -1), new ArquivoDoCommit("docs/leia.md", "LEIA.md", 2, 0)));
            }
            leitor.branches = List.of(new Branch("main", true, "abc1234"), new Branch("feature/entrega", false, "def5678"));
            return this;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Quando lê
    // ---------------------------------------------------------------------------------------------

    @Test
    void naoLeNadaAteQueAAbaAparecaOuODonoPecaEDepoisNuncaDeTemposEmTempos() throws Exception {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(3);
        assertEquals(0, c.leitor.chamadas.size(), "montar a página não lê nada");
        Visual.naEdt(() -> c.cena.tela.aoReceberDetalhe(FixturesDeApi.detalheDoProjetoSintetico())); // o detalhe do agente chega a cada 10 s
        Visual.naEdt(() -> c.cena.tela.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.CONECTADO)));
        assertEquals(0, c.leitor.chamadas.size(), "o detalhe e o estado que chegam sozinhos não fazem o Git ler");
        Visual.naEdt(c.pagina::assinar); // a aba apareceu
        esperar("ler ao abrir", () -> !c.pagina.carregando() && c.pagina.commitsCarregados() == 3);
        int lidasAoAbrir = c.leitor.chamadas.size();
        assertEquals(1, c.leitor.quantas("resumo"));
        assertEquals(1, c.leitor.quantas("commits"));
        assertEquals(1, c.leitor.quantas("branches"));
        assertEquals(1, c.leitor.quantas("status"));
        Thread.sleep(700);
        Visual.naEdt(() -> c.cena.tela.aoReceberDetalhe(FixturesDeApi.detalheDoProjetoSintetico()));
        assertEquals(lidasAoAbrir, c.leitor.chamadas.size(), "nenhuma leitura de fundo: só abrir e Atualizar leem");
        Visual.naEdt(() -> c.pagina.botaoAtualizar().doClick(0));
        esperar("ler no Atualizar", () -> c.leitor.quantas("resumo") == 2 && !c.pagina.carregando());
        // a aba escondida não lê e uma leitura em voo, quando ela some, é descartada
        Visual.naEdt(() -> c.pagina.soltar());
        int antes = c.leitor.chamadas.size();
        Thread.sleep(300);
        assertEquals(antes, c.leitor.chamadas.size());
    }

    @Test
    void umaLeituraEmVooQueTerminaDepoisDaAbaSumirNaoMexeNaTela() throws Exception {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(3);
        c.leitor.segurar = new CountDownLatch(1);
        Visual.naEdt(c.pagina::assinar);
        esperar("começar a ler", () -> c.leitor.quantas("resumo") == 1);
        Visual.naEdt(() -> c.pagina.soltar());
        c.leitor.segurar.countDown();
        Thread.sleep(400);
        assertEquals(0, ed(c.pagina::commitsCarregados), "o resultado de antes de a aba sumir foi descartado");
        assertTrue(ed(c.pagina::mostraMensagem), "e a tela continua como estava");
    }

    // ---------------------------------------------------------------------------------------------
    // A lista
    // ---------------------------------------------------------------------------------------------

    @Test
    void mostraOCarregandoEDepoisOsCommitsComHashAutorQuandoEArquivos() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(3);
        c.leitor.segurar = new CountDownLatch(1);
        Visual.naEdt(c.pagina::recarregar);
        assertTrue(ed(c.pagina::mostraMensagem));
        assertEquals("Lendo o Git do projeto…", ed(c.pagina::tituloDaMensagem));
        c.leitor.segurar.countDown();
        esperar("commits", () -> c.pagina.commitsCarregados() == 3);
        assertFalse(ed(c.pagina::mostraMensagem));
        TabelaDeDados<Commit> t = c.pagina.tabelaDeCommits();
        assertEquals(3, ed(t::getRowCount));
        Commit primeiro = LeitorDeGitFalso.commit(3);
        assertEquals(primeiro.curto(), ed(() -> t.textoDa(0, 0)));
        assertEquals("Commit número 3: ajusta o carrinho da loja", ed(() -> t.textoDa(0, 1)));
        assertEquals("Beto Souza", ed(() -> t.textoDa(0, 2)));
        assertTrue(ed(() -> t.textoDa(0, 3)).startsWith("há "), ed(() -> t.textoDa(0, 3)));
        assertEquals("1", ed(() -> t.textoDa(0, 4)));
        assertEquals("Commits", ed(() -> c.pagina.subAbas().botao("commits").getText()));
        assertEquals("Alterações", ed(() -> c.pagina.subAbas().botao("alteracoes").getText()), "sem alterações no status de teste");
        assertEquals("Branches (2)", ed(() -> c.pagina.subAbas().botao("branches").getText()));
        assertEquals("3 commits carregados", ed(c.pagina::textoDaContagemDeCommits));
        assertFalse(ed(() -> c.pagina.botaoCarregarMais().isVisible()));
    }

    @Test
    void cinquentaPorVezECarregarMaisTrazOsMaisAntigosEsomeNoFim() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(120);
        c.ler();
        assertEquals(50, ed(c.pagina::commitsCarregados));
        assertTrue(ed(() -> c.pagina.botaoCarregarMais().isVisible()));
        assertEquals("50 commits carregados (há mais antigos)", ed(c.pagina::textoDaContagemDeCommits));
        Visual.naEdt(() -> c.pagina.botaoCarregarMais().doClick(0));
        esperar("segunda fatia", () -> c.pagina.commitsCarregados() == 100);
        assertTrue(ed(() -> c.pagina.botaoCarregarMais().isVisible()));
        Visual.naEdt(() -> c.pagina.botaoCarregarMais().doClick(0));
        esperar("terceira fatia", () -> c.pagina.commitsCarregados() == 120);
        assertFalse(ed(() -> c.pagina.botaoCarregarMais().isVisible()), "acabaram");
        assertEquals(List.of("commits:null:0:50", "commits:null:50:50", "commits:null:100:50"), c.leitor.chamadas.stream().filter(x -> x.startsWith("commits")).toList());
        assertEquals(LeitorDeGitFalso.commit(1).curto(), ed(() -> c.pagina.tabelaDeCommits().textoDa(119, 0)));
    }

    @Test
    void repositorioSemCommitsDizOFatoNaoEstaQuebrado() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(0);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(LeitorDeGitFalso.comGit("main", null, 1));
        c.ler();
        assertFalse(ed(c.pagina::mostraMensagem));
        assertTrue(ed(c.pagina::semCommitsVisivel));
        assertEquals(0, ed(() -> c.pagina.tabelaDeCommits().getRowCount()));
        assertTrue(TelaProjetoTest.textos(c.pagina).contains("Este repositório ainda não tem commits."));
    }

    @Test
    void textoDeCommitComHtmlNaoViraHtml() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(1);
        // o texto passa pelo interpretador do log (que neutraliza o início html) e a célula da tabela ainda desliga o HTML do Swing
        String hash = LeitorDeGitFalso.commit(1).hash();
        String saida = "\u001e" + hash + "\u001f" + hash.substring(0, 7) + "\u001f<html><b>Ana</b>\u001fa@example.com\u001f1793304758\u001f\u001f<html><img src='x'>oi\n";
        Commit h = br.com.labdesktop.git.InterpretadorDoLog.interpretar(saida).get(0);
        c.leitor.commits = List.of(h);
        c.ler();
        TabelaDeDados<Commit> t = c.pagina.tabelaDeCommits();
        assertFalse(ed(() -> t.textoDa(0, 1)).startsWith("<html"), "o texto de um servidor nunca começa como HTML");
        assertTrue(ed(() -> t.textoDa(0, 1)).contains("oi"));
        assertFalse(ed(() -> t.textoDa(0, 2)).startsWith("<html"));
        java.awt.Component celula = ed(() -> t.getCellRenderer(0, 1).getTableCellRendererComponent(t, h, false, false, 0, 1));
        assertEquals(Boolean.TRUE, ed(() -> ((javax.swing.JComponent) celula).getClientProperty("html.disable")), "a célula desliga o HTML do Swing");
        assertEquals(Boolean.TRUE, ed(() -> t.getClientProperty("html.disable")));
    }

    // ---------------------------------------------------------------------------------------------
    // Estados que não são "tem commits"
    // ---------------------------------------------------------------------------------------------

    @Test
    void semGitOferecerCriarERespeitaATrava() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
        c.ler();
        assertTrue(ed(c.pagina::mostraMensagem));
        assertEquals("Este projeto ainda não é um repositório Git", ed(c.pagina::tituloDaMensagem));
        assertTrue(ed(() -> c.pagina.botaoCriar().isVisible() && c.pagina.botaoCriar().isEnabled()));
        Visual.naEdt(() -> c.pagina.botaoCriar().doClick(0));
        assertEquals(List.of("criar"), c.pedidos);

        Cena sem = new Cena(false);
        sem.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
        sem.ler();
        assertTrue(ed(() -> sem.pagina.botaoCriar().isVisible()), "não some: aparece desligado");
        assertFalse(ed(() -> sem.pagina.botaoCriar().isEnabled()));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> sem.pagina.botaoCriar().getToolTipText()));
        Visual.naEdt(() -> sem.pagina.botaoCriar().doClick(0));
        assertTrue(sem.pedidos.isEmpty(), "desligado: o clique não faz nada");
    }

    @Test
    void gitNaoInstaladoLevaAoFluxoQueJaExisteEServidorProtegidoDesligaOBotao() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO, br.com.labdesktop.git.ClassificadorDeErrosDoGit.naoInstalado()));
        c.ler();
        assertEquals("O Git não está instalado", ed(c.pagina::tituloDaMensagem));
        assertTrue(ed(() -> c.pagina.botaoInstalar().isVisible() && c.pagina.botaoInstalar().isEnabled()));
        assertFalse(ed(() -> c.pagina.botaoCriar().isVisible()));
        Visual.naEdt(() -> c.pagina.botaoInstalar().doClick(0));
        assertEquals(List.of("instalar"), c.pedidos, "é o fluxo que já existia, não um instalador novo");

        Cena prot = new Cena(true);
        prot.leitor.resumo = c.leitor.resumo;
        Visual.naEdt(() -> prot.cena.tela.aoMudarEstado(estadoProtegido()));
        prot.ler();
        assertFalse(ed(() -> prot.pagina.botaoInstalar().isEnabled()));
        assertTrue(ed(() -> prot.pagina.botaoInstalar().getToolTipText()).contains("protegido"));
        assertTrue(ed(prot.pagina::textoDaMensagem).contains("O servidor é protegido"));
        Visual.naEdt(() -> prot.pagina.botaoInstalar().doClick(0));
        assertTrue(prot.pedidos.isEmpty());
    }

    static br.com.labdesktop.servidor.EstadoServidor estadoProtegido() {
        br.com.labdesktop.servidor.EstadoServidor e = TelaProjetoTest.estado(EstadoConexao.CONECTADO);
        return new br.com.labdesktop.servidor.EstadoServidor(e.servidorId(), e.nome(), e.conexao(), e.falha(), e.tentativa(), e.proximaTentativaEm(), e.semConexaoDesde(), e.geracao(),
            e.estado2fa(), e.nomeDoDispositivo(), e.versao(), e.limites(), e.situacaoDoAgente(), false, true, e.transporte());
    }

    @Test
    void donoDiferenteMostraAFraseEOComandoCopiavelENuncaOExecuta() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        FalhaDoGit f = br.com.labdesktop.git.ClassificadorDeErrosDoGit.propriedadeDuvidosa("/srv/lab/projects/loja-web");
        c.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA, f));
        c.ler();
        assertEquals("O Git recusou a pasta", ed(c.pagina::tituloDaMensagem));
        assertTrue(ed(c.pagina::textoDaMensagem).contains("outro usuário"));
        assertEquals("git config --global --add safe.directory /srv/lab/projects/loja-web", ed(c.pagina::comandoDaMensagem));
        Visual.naEdt(() -> c.pagina.botaoCopiarComando().doClick(0));
        assertEquals("git config --global --add safe.directory /srv/lab/projects/loja-web", clipboard(c.cena));
        assertEquals(1, c.leitor.quantas("resumo"), "copiar não lê nem executa nada");
        assertEquals(1, c.leitor.chamadas.size());
    }

    static String clipboard(TelaProjetoTest.Cena c) {
        try {
            return (String) c.area.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void falhaDeLeituraDizOMotivoETentarDeNovoLeDeNovo() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(3);
        c.leitor.falhaDeCommits = new FalhaDoGit(FalhaDoGit.Tipo.SEM_PERMISSAO, "O usuário do app não tem permissão para ler esta pasta.");
        c.ler();
        assertEquals("Não consegui ler os commits", ed(c.pagina::tituloDaMensagem));
        assertTrue(ed(c.pagina::textoDaMensagem).contains("não tem permissão"));
        assertTrue(ed(() -> c.pagina.botaoTentarDeNovo().isVisible()));
        c.leitor.falhaDeCommits = null;
        Visual.naEdt(() -> c.pagina.botaoTentarDeNovo().doClick(0));
        esperar("ler de novo", () -> !c.pagina.carregando() && c.pagina.commitsCarregados() == 3);
        assertFalse(ed(c.pagina::mostraMensagem));
    }

    @Test
    void dentroDeOutroRepositorioESemConexao() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(new ResumoDoRepositorio(ResumoDoRepositorio.Estado.DENTRO_DE_OUTRO, null, false, null, 0, null, "loja-web/"));
        c.ler();
        assertEquals("Esta pasta está dentro de outro repositório", ed(c.pagina::tituloDaMensagem));
        assertFalse(ed(() -> c.pagina.botaoCriar().isVisible()));

        Cena off = new Cena(true).comCommits(2);
        Visual.naEdt(() -> off.cena.tela.aoMudarEstado(TelaProjetoTest.estado(EstadoConexao.RECONECTANDO)));
        Visual.naEdt(off.pagina::recarregar);
        assertEquals("Sem conexão com o servidor", ed(off.pagina::tituloDaMensagem));
        assertEquals(0, off.leitor.chamadas.size(), "sem conexão não tenta ler");
        assertTrue(ed(() -> off.pagina.botaoTentarDeNovo().isVisible()));
    }

    // ---------------------------------------------------------------------------------------------
    // O detalhe de um commit
    // ---------------------------------------------------------------------------------------------

    @Test
    void escolherUmCommitMostraOCabecalhoOsArquivosEODiffDoPrimeiro() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(5);
        Commit alvo = LeitorDeGitFalso.commit(5);
        c.leitor.diffs = chave -> new ResultadoDoGit.Ok<>(LeitorDeGitFalso.diff(" contexto", "-linha velha", "+linha nova", "+outra nova"));
        c.ler();
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(0));
        esperar("detalhe", () -> alvo.hash().equals(c.pagina.hashDoDetalhe()) && c.pagina.diffDoCommit().mostraDiff());
        assertEquals(alvo.hash(), ed(c.pagina::hashDoDetalhe), "o hash INTEIRO");
        assertTrue(ed(c.pagina::autorEDataDoDetalhe).contains("Beto Souza <autor5@example.com>"));
        assertTrue(ed(c.pagina::autorEDataDoDetalhe).contains("/2026"), ed(c.pagina::autorEDataDoDetalhe));
        assertEquals("Mensagem inteira do commit " + alvo.curto() + "\n\nCom corpo.", ed(c.pagina::mensagemDoDetalhe));
        TabelaDeDados<ArquivoDoCommit> arq = c.pagina.tabelaDeArquivos();
        assertEquals(3, ed(arq::getRowCount));
        assertEquals("src/app.js", ed(() -> arq.textoDa(0, 0)));
        assertEquals("+3", ed(() -> arq.textoDa(0, 1)));
        assertEquals("-1", ed(() -> arq.textoDa(0, 2)));
        assertEquals("bin", ed(() -> arq.textoDa(1, 1)), "binário não tem contagem");
        assertEquals("LEIA.md → docs/leia.md", ed(() -> arq.textoDa(2, 0)));
        assertTrue(ed(c.pagina::textoDosArquivos).contains("3 arquivos"));
        assertEquals("+3 acrescentadas  -1 tiradas".replace("+3", "+2").replace("-1", "-1"), ed(() -> c.pagina.diffDoCommit().textoDaContagem()));
        // as linhas do diff: símbolo e palavra, não só cor
        VistaDeDiff v = c.pagina.diffDoCommit();
        List<LinhaDeDiff.Tipo> tipos = ed(() -> java.util.stream.IntStream.range(0, v.quantasLinhas()).mapToObj(v::linha).map(LinhaDeDiff::tipo).toList());
        assertEquals(List.of(LinhaDeDiff.Tipo.TRECHO, LinhaDeDiff.Tipo.CONTEXTO, LinhaDeDiff.Tipo.TIRADA, LinhaDeDiff.Tipo.ACRESCENTADA, LinhaDeDiff.Tipo.ACRESCENTADA), tipos);
        assertTrue(ed(v::textoParaCopiar).contains("+linha nova\n"));
        assertTrue(ed(v::textoParaCopiar).contains("-linha velha\n"));
        assertEquals(1, c.leitor.quantas("detalhe:"));
        assertEquals(1, c.leitor.quantas("diff:" + alvo.hash() + ":src/app.js"));
    }

    @Test
    void escolherOutroArquivoLeOSeuDiffEVoltarAoMesmoUsaOGuardado() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(2);
        c.ler();
        Commit alvo = LeitorDeGitFalso.commit(2);
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(0));
        esperar("diff do primeiro arquivo", () -> c.pagina.diffDoCommit().mostraDiff());
        Visual.naEdt(() -> c.pagina.tabelaDeArquivos().selecionarLinha(2));
        esperar("diff do terceiro", () -> c.leitor.quantas("diff:" + alvo.hash() + ":docs/leia.md") == 1);
        Visual.naEdt(() -> c.pagina.tabelaDeArquivos().selecionarLinha(0));
        esperar("o primeiro de volta", () -> c.pagina.diffDoCommit().mostraDiff());
        assertEquals(1, c.leitor.quantas("diff:" + alvo.hash() + ":src/app.js"), "o diff que já veio não é lido de novo");
        // outro commit e volta: o detalhe também fica guardado
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(1));
        esperar("detalhe do outro", () -> LeitorDeGitFalso.commit(1).hash().equals(c.pagina.hashDoDetalhe()));
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(0));
        esperar("de volta", () -> alvo.hash().equals(c.pagina.hashDoDetalhe()));
        assertEquals(1, c.leitor.quantas("detalhe:" + alvo.hash()));
    }

    @Test
    void arquivoBinarioDizArquivoBinarioEDiffCortadoAvisa() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(1);
        StringBuilder sb = new StringBuilder("--- a/x\n+++ b/x\n@@ -0,0 +1,3000 @@\n");
        for (int i = 0; i < 3000; i++) {
            sb.append("+l").append(i).append('\n');
        }
        c.leitor.diffs = chave -> new ResultadoDoGit.Ok<>(br.com.labdesktop.git.InterpretadorDeDiff.interpretar(sb.toString(), false));
        c.ler();
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(0));
        esperar("diff cortado", () -> c.pagina.diffDoCommit().mostraDiff());
        assertTrue(ed(() -> c.pagina.diffDoCommit().textoDoAviso()).startsWith("diff cortado"), ed(() -> c.pagina.diffDoCommit().textoDoAviso()));
        assertEquals(2000, ed(() -> c.pagina.diffDoCommit().quantasLinhas()));
        Visual.naEdt(() -> c.pagina.tabelaDeArquivos().selecionarLinha(1)); // logo.png, binário
        esperar("mensagem de binário", () -> !c.pagina.diffDoCommit().mostraDiff());
        assertTrue(ed(() -> c.pagina.diffDoCommit().textoDaMensagem()).startsWith("arquivo binário"));
        assertFalse(ed(() -> c.pagina.diffDoCommit().botaoCopiar().isEnabled()));
        assertEquals(0, c.leitor.quantas("diff:" + LeitorDeGitFalso.commit(1).hash() + ":logo.png"), "binário nem pede o diff");
    }

    @Test
    void falhaAoLerODetalheEOdiffViraFrase() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(2);
        c.ler();
        c.leitor.falhaDoDetalhe = new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "O Git respondeu com erro (código 128): bad object");
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(0));
        esperar("frase de erro", () -> c.pagina.diffDoCommit().textoDaMensagem().contains("bad object"));
        c.leitor.falhaDoDetalhe = null;
        c.leitor.diffs = chave -> new ResultadoDoGit.Falhou<>(new FalhaDoGit(FalhaDoGit.Tipo.DEMOROU, "O Git demorou mais que o prazo."));
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(1));
        esperar("diff com erro", () -> c.pagina.diffDoCommit().textoDaMensagem().contains("O Git demorou"));
    }

    @Test
    void copiarHashECopiarDiffPonhamNaAreaDeTransferencia() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(2);
        c.leitor.diffs = chave -> new ResultadoDoGit.Ok<>(LeitorDeGitFalso.diff("-velho", "+novo"));
        c.ler();
        assertFalse(ed(() -> c.pagina.botaoCopiarHash().isEnabled()), "sem commit escolhido não há o que copiar");
        Commit alvo = LeitorDeGitFalso.commit(2);
        Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(0));
        esperar("detalhe", () -> c.pagina.diffDoCommit().mostraDiff());
        Visual.naEdt(() -> c.pagina.botaoCopiarHash().doClick(0));
        assertEquals(alvo.hash(), clipboard(c.cena));
        Visual.naEdt(() -> c.pagina.diffDoCommit().botaoCopiar().doClick(0));
        assertTrue(clipboard(c.cena).contains("-velho\n+novo\n"), clipboard(c.cena));
    }

    // ---------------------------------------------------------------------------------------------
    // Alterações e branches
    // ---------------------------------------------------------------------------------------------

    @Test
    void alteracoesNaoCommitadasComDiffEAVistaDoQueJaFoiPreparado() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(2);
        c.leitor.status = new StatusDoGit("main", false, LeitorDeGitFalso.commit(2).hash(), null, -1, -1, List.of(
            new Pendencia("c.txt", null, Pendencia.Tipo.MODIFICADO, true, true),
            new Pendencia("novo.txt", null, Pendencia.Tipo.NAO_RASTREADO, false, true),
            new Pendencia("src/pronto.js", null, Pendencia.Tipo.ADICIONADO, true, false)));
        c.leitor.diffs = chave -> new ResultadoDoGit.Ok<>(LeitorDeGitFalso.diff(chave.endsWith("true") ? "+no índice" : "+na árvore"));
        c.ler();
        assertEquals("Alterações (3)", ed(() -> c.pagina.subAbas().botao("alteracoes").getText()));
        Visual.naEdt(() -> c.pagina.subAbas().selecionar("alteracoes"));
        TabelaDeDados<Pendencia> t = c.pagina.tabelaDePendencias();
        assertEquals(3, ed(t::getRowCount));
        assertEquals("preparado e alterado depois", ed(() -> t.textoDa(0, 2)));
        assertEquals("arquivo novo, ainda não preparado", ed(() -> t.textoDa(1, 2)));
        assertEquals("preparado para o commit", ed(() -> t.textoDa(2, 2)));
        Visual.naEdt(() -> t.selecionarLinha(0));
        esperar("diff da árvore", () -> c.pagina.diffPendente().mostraDiff());
        assertEquals("+na árvore", ed(() -> c.pagina.diffPendente().linha(1).simbolo() + c.pagina.diffPendente().linha(1).texto()));
        assertEquals(1, c.leitor.quantas("pendente:c.txt:false"));
        // os dois lados: o usuário escolhe "Preparado"
        Visual.naEdt(() -> c.pagina.botaoVerIndice().doClick(0));
        esperar("diff do índice", () -> c.leitor.quantas("pendente:c.txt:true") == 1);
        esperar("mostrando", () -> c.pagina.diffPendente().mostraDiff() && c.pagina.diffPendente().linha(1).texto().equals("no índice"));
        Visual.naEdt(() -> t.selecionarLinha(2)); // só preparado: lê o do índice sozinho
        esperar("diff só do índice", () -> c.leitor.quantas("pendente:src/pronto.js:true") == 1);
        Visual.naEdt(() -> t.selecionarLinha(1)); // não rastreado
        esperar("diff do novo", () -> c.leitor.quantas("pendente:novo.txt:false") == 1);
    }

    @Test
    void semAlteracoesDizTudoSalvo() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(1);
        c.ler();
        assertTrue(ed(c.pagina::semAlteracoesVisivel));
        assertEquals("Alterações", ed(() -> c.pagina.subAbas().botao("alteracoes").getText()));
    }

    @Test
    void branchesMarcamOAtualEEscolherUmFiltraOsCommitsEVoltaAoAtual() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(3);
        c.leitor.commitsPorBranch.put("feature/entrega", List.of(LeitorDeGitFalso.commit(9), LeitorDeGitFalso.commit(8)));
        c.ler();
        TabelaDeDados<Branch> t = c.pagina.tabelaDeBranches();
        assertEquals("atual", ed(() -> t.textoDa(0, 1)));
        assertEquals("", ed(() -> t.textoDa(1, 1)));
        Visual.naEdt(() -> t.selecionarLinha(1));
        esperar("commits da feature", () -> "feature/entrega".equals(c.pagina.branchEscolhido()) && c.pagina.commitsCarregados() == 2);
        assertEquals(LeitorDeGitFalso.commit(9).curto(), ed(() -> c.pagina.tabelaDeCommits().textoDa(0, 0)));
        assertTrue(ed(() -> c.pagina.botaoVoltarAoAtual().isVisible()));
        assertEquals("commits:feature/entrega:0:50", c.leitor.chamadas.stream().filter(x -> x.contains("feature")).findFirst().orElseThrow());
        assertEquals("commits", ed(() -> c.pagina.subAbas().selecionada()));
        Visual.naEdt(() -> c.pagina.botaoVoltarAoAtual().doClick(0));
        esperar("de volta ao atual", () -> c.pagina.branchEscolhido() == null && c.pagina.commitsCarregados() == 3);
        assertFalse(ed(() -> c.pagina.botaoVoltarAoAtual().isVisible()));
    }

    // ---------------------------------------------------------------------------------------------
    // O que se vê: sem rolagem do corpo, sem transbordo, PNGs
    // ---------------------------------------------------------------------------------------------

    private static String sufixo(Tema t) {
        return t == Tema.CLARO ? "claro" : "escuro";
    }

    @Test
    void abaGitCabeEm1100x700SemRolagemDoCorpoENosDoisTemasComPngs() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Cena c = new Cena(true).comCommits(120);
            c.leitor.status = new StatusDoGit("main", false, LeitorDeGitFalso.commit(120).hash(), null, -1, -1, List.of(new Pendencia("c.txt", null, Pendencia.Tipo.MODIFICADO, false, true),
                new Pendencia("novo.txt", null, Pendencia.Tipo.NAO_RASTREADO, false, true)));
            c.leitor.diffs = chave -> new ResultadoDoGit.Ok<>(LeitorDeGitFalso.diff(" const total = soma(itens);", "-const frete = 10;", "+const frete = calcularFrete(cep);", "+if (!frete) {", "+  throw new Error('cep inválido');", "+}", " return total + frete;"));
            c.ler();
            Visual.naEdt(() -> c.pagina.tabelaDeCommits().selecionarLinha(2));
            esperar("detalhe e diff", () -> c.pagina.diffDoCommit().mostraDiff());
            Visual.renderizar(c.cena.tela, 1100 - 224, 672); // o layout de uma janela de 1100 x 700
            assertFalse(ed(() -> c.pagina.rolagem().getVerticalScrollBar().isVisible()), "a aba Git não rola o corpo: " + t);
            assertFalse(ed(() -> c.pagina.rolagem().getHorizontalScrollBar().isVisible()));
            assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(prepararLargura(c.cena.tela, 1100 - 224, 672))), "tema " + t);
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - 224, 672), "projeto-git-commits-" + sufixo(t));
            String texto = TelaProjetoTest.textos(c.pagina);
            assertFalse(java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]").matcher(texto).find(), "sem emoji: " + texto);
            Visual.naEdt(() -> c.pagina.subAbas().selecionar("alteracoes"));
            Visual.naEdt(() -> c.pagina.tabelaDePendencias().selecionarLinha(0));
            esperar("diff da pendência", () -> c.pagina.diffPendente().mostraDiff());
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - 224, 672), "projeto-git-alteracoes-" + sufixo(t));
            Visual.naEdt(() -> c.pagina.subAbas().selecionar("branches"));
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - 224, 672), "projeto-git-branches-" + sufixo(t));
        }
        Visual.tema(Tema.CLARO);
    }

    private static javax.swing.JComponent prepararLargura(javax.swing.JComponent c, int largura, int altura) {
        c.setSize(largura, altura);
        return c;
    }

    @Test
    void osEstadosDeMensagemSaemEmPngClaroEEscuroSemTransbordo() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            // sem Git
            Cena sem = new Cena(true);
            sem.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
            sem.ler();
            assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(prepararLargura(sem.cena.tela, 1100 - 224, 672))));
            Visual.salvar(Visual.renderizar(sem.cena.tela, 1100 - 224, 672), "projeto-git-sem-git-" + sufixo(t));
            // dono diferente
            Cena dono = new Cena(true);
            dono.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA,
                br.com.labdesktop.git.ClassificadorDeErrosDoGit.propriedadeDuvidosa("/srv/lab/projects/loja-web")));
            dono.ler();
            assertEquals(List.of(), Visual.naEdt(() -> SemTransbordo.achados(prepararLargura(dono.cena.tela, 1100 - 224, 672))));
            Visual.salvar(Visual.renderizar(dono.cena.tela, 1100 - 224, 672), "projeto-git-dono-diferente-" + sufixo(t));
            // erro
            Cena erro = new Cena(true).comCommits(2);
            erro.leitor.falhaDeCommits = new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "O Git respondeu com erro (código 128): fatal: bad object HEAD");
            erro.ler();
            Visual.salvar(Visual.renderizar(erro.cena.tela, 1100 - 224, 672), "projeto-git-erro-" + sufixo(t));
            // carregando
            Cena lendo = new Cena(true).comCommits(2);
            lendo.leitor.segurar = new CountDownLatch(1);
            Visual.naEdt(lendo.pagina::recarregar);
            Visual.salvar(Visual.renderizar(lendo.cena.tela, 1100 - 224, 672), "projeto-git-carregando-" + sufixo(t));
            lendo.leitor.segurar.countDown();
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void oContadorDeLeiturasNaoPassaDeUmaPorAbertura() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true).comCommits(2);
        AtomicInteger aberturas = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            Visual.naEdt(c.pagina::assinar);
            esperar("ler", () -> !c.pagina.carregando() && c.pagina.commitsCarregados() == 2);
            aberturas.incrementAndGet();
            Visual.naEdt(() -> c.pagina.soltar());
        }
        assertEquals(3, c.leitor.quantas("resumo"), "uma leitura por abertura da aba");
        assertNotNull(aberturas);
    }
}
