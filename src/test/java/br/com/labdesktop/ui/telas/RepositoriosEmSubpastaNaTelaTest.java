package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.git.Branch;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.LeitorDeGitDoProjeto;
import br.com.labdesktop.git.RepositorioAbaixo;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.LeitorDeGitFalso;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O projeto cujo repositório mora numa SUBPASTA (a pasta principal não tem {@code .git}), nas duas telas que mostram o Git: o cartão "Código" da Visão geral e a aba Git. Usa o caminho de
 * verdade da página ({@code TelaProjeto} monta o {@link LeitorDeGitDoProjeto} sobre leitores de mentira, um para a pasta principal e um por subpasta, e guarda a escolha pela saída da página).
 * Cobre: a lista (com branch e último commit quando já lidos), o repositório único aberto direto com o rótulo, a escolha lida e lembrada, "Trocar repositório", NENHUM botão de criar na pasta
 * principal (só o link discreto, que abre a conferência), a trava, e as telas em 1100 x 700 sem rolagem, em PNG claro e escuro.
 */
@ExtendWith(VerificaEdt.class)
class RepositoriosEmSubpastaNaTelaTest {

    private static final String RAIZ = "/srv/lab/projects/loja-web";

    private static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private static Commit commitDe(int n, String assunto) {
        Commit c = LeitorDeGitFalso.commit(n);
        return new Commit(c.hash(), c.curto(), c.autor(), c.email(), c.quando(), c.pais(), assunto, c.arquivos());
    }

    /** Uma pasta principal sem Git com os repositórios dados (cada um com seus commits) e a página ligada a eles pelo caminho de verdade. */
    final class Cena {
        final TelaProjetoTest.Cena cena;
        final LeitorDeGitFalso raiz = new LeitorDeGitFalso();
        final Map<String, LeitorDeGitFalso> subs = new LinkedHashMap<>();
        final PaginaGit pagina;
        final LinhaDoCodigo linha;
        final List<String> pedidos = new java.util.concurrent.CopyOnWriteArrayList<>();

        Cena(boolean acoes, Aba aba, String... repositorios) {
            this.cena = new TelaProjetoTest.Cena(acoes, aba).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
            int i = 0;
            for (String r : repositorios) {
                LeitorDeGitFalso l = new LeitorDeGitFalso();
                int n = 3 + i++;
                l.commits = List.of(commitDe(n, "Segundo de " + r), commitDe(n - 1, "Primeiro de " + r));
                l.resumo = new ResultadoDoGit.Ok<>(LeitorDeGitFalso.comGit("main", l.commits.get(0), 1));
                l.branches = List.of(new Branch("main", true, "abc1234"));
                subs.put(r, l);
            }
            List<RepositorioAbaixo> lista = new java.util.ArrayList<>();
            for (Map.Entry<String, LeitorDeGitFalso> e : subs.entrySet()) {
                lista.add(subs.size() > 1 ? new RepositorioAbaixo(e.getKey(), "main", e.getValue().commits.get(0), true) : RepositorioAbaixo.semLeitura(e.getKey()));
            }
            raiz.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.repositoriosAbaixo(lista, false));
            cena.saida.leitoresDeGit = pasta -> pasta.equals(RAIZ) ? raiz : subs.get(pasta.substring(RAIZ.length() + 1));
            cena.tela.contexto().pastaDoProjeto = () -> RAIZ;
            cena.tela.contexto().criarRepositorio = (depois, ver) -> pedidos.add("criar");
            this.pagina = ed(cena.tela::git);
            this.linha = ed(() -> cena.tela.visaoGeral().linhaDoCodigo());
        }

        void lerAAba() {
            Visual.naEdt(pagina::recarregar);
            PaginaGitTest.esperar("terminar de ler", () -> !pagina.carregando());
        }

        void lerOCartao() {
            Visual.naEdt(linha::atualizar);
            PaginaGitTest.esperar("ler o cartão", () -> !linha.lendo() && linha.resumoAtual() != null);
        }

        List<String> lembrados() {
            return cena.saida.pedidos.stream().filter(p -> p.startsWith("lembrar:")).toList();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A aba Git
    // ---------------------------------------------------------------------------------------------

    @Test
    void comDoisRepositoriosAAbaMostraAListaESemNenhumBotaoDeCriarNaPastaPrincipal() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.GIT, "api", "web");
        c.lerAAba();
        assertTrue(ed(c.pagina::mostraMensagem));
        assertEquals("Este projeto não tem Git na pasta principal", ed(c.pagina::tituloDaMensagem));
        assertTrue(ed(c.pagina::mostraOSeletor));
        TabelaDeDados<RepositorioAbaixo> t = c.pagina.tabelaDeRepositorios();
        assertEquals(2, ed(t::getRowCount));
        assertEquals("api/", ed(() -> t.textoDa(0, 0)));
        assertEquals("main", ed(() -> t.textoDa(0, 1)));
        assertEquals(LeitorDeGitFalso.commit(3).curto() + " Segundo de api", ed(() -> t.textoDa(0, 2)));
        assertEquals("web/", ed(() -> t.textoDa(1, 0)));
        assertFalse(ed(() -> c.pagina.botaoCriar().isVisible()), "com repositório abaixo, o botão 'Criar repositório Git' NÃO aparece na pasta principal");
        assertTrue(ed(() -> c.pagina.botaoCriarMesmoAssim().isVisible() && c.pagina.botaoCriarMesmoAssim().isEnabled()));
        assertEquals("Criar um repositório na pasta principal mesmo assim", ed(() -> c.pagina.botaoCriarMesmoAssim().getText()));
        assertEquals("", ed(c.pagina::textoDoRepositorioEmUso));
        assertEquals(0, c.raiz.quantas("commits"), "a lista não lê commits de ninguém ainda");
        assertTrue(c.lembrados().isEmpty());
    }

    @Test
    void escolherUmRepositorioLeOsCommitsDaquelaSubpastaMostraORotuloELembra() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.GIT, "api", "web");
        c.lerAAba();
        Visual.naEdt(() -> c.pagina.tabelaDeRepositorios().selecionarLinha(1)); // web/
        PaginaGitTest.esperar("ler web", () -> !c.pagina.carregando() && !c.pagina.mostraMensagem());
        assertEquals(List.of("lembrar:web"), c.lembrados());
        assertEquals("Repositório: web/", ed(c.pagina::textoDoRepositorioEmUso));
        assertTrue(ed(() -> c.pagina.botaoTrocarRepositorio().isVisible()));
        assertEquals(2, ed(c.pagina::commitsCarregados));
        assertEquals("Segundo de web", ed(() -> c.pagina.tabelaDeCommits().textoDa(0, 1)), "os commits são os de web, exatamente como na raiz");
        assertEquals(1, c.subs.get("web").quantas("commits"));
        assertEquals(0, c.subs.get("api").quantas("commits"), "o outro repositório não foi lido");
        assertEquals(0, c.raiz.quantas("commits"));
        assertEquals(0, c.raiz.quantas("status") + c.raiz.quantas("branches"), "alterações, branches e diff também vêm da subpasta");
        assertEquals(1, c.subs.get("web").quantas("status"));
        assertEquals(1, c.subs.get("web").quantas("branches"));

        // "Trocar repositório" volta à lista e esquece a escolha (também a lembrada)
        Visual.naEdt(() -> c.pagina.botaoTrocarRepositorio().doClick(0));
        PaginaGitTest.esperar("lista de novo", () -> !c.pagina.carregando() && c.pagina.mostraOSeletor());
        assertEquals(List.of("lembrar:web", "lembrar:null"), c.lembrados());
        assertNull(c.cena.saida.repositorioGitLembrado);
        assertEquals("", ed(c.pagina::textoDoRepositorioEmUso));
    }

    @Test
    void oRepositorioLembradoAbreDiretoNaProximaAbertura() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.GIT, "api", "web");
        c.cena.saida.repositorioGitLembrado = "api";
        c.lerAAba();
        assertFalse(ed(c.pagina::mostraMensagem), "com uma escolha lembrada a lista nem aparece");
        assertEquals("Repositório: api/", ed(c.pagina::textoDoRepositorioEmUso));
        assertEquals("Segundo de api", ed(() -> c.pagina.tabelaDeCommits().textoDa(0, 1)));
    }

    @Test
    void comUmSoRepositorioAbreDiretoComORotuloETrocarMostraAListaSemReabrir() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.GIT, "app");
        c.lerAAba();
        assertFalse(ed(c.pagina::mostraMensagem));
        assertEquals("Repositório: app/", ed(c.pagina::textoDoRepositorioEmUso));
        assertEquals("Segundo de app", ed(() -> c.pagina.tabelaDeCommits().textoDa(0, 1)));
        assertTrue(c.lembrados().isEmpty(), "abrir o único sozinho não grava escolha nenhuma");

        Visual.naEdt(() -> c.pagina.botaoTrocarRepositorio().doClick(0));
        PaginaGitTest.esperar("a lista", () -> !c.pagina.carregando() && c.pagina.mostraOSeletor());
        assertEquals(1, ed(() -> c.pagina.tabelaDeRepositorios().getRowCount()));
        Visual.naEdt(() -> c.pagina.botaoAtualizar().doClick(0)); // o Atualizar também respeita o "voltar à lista"
        assertTrue(ed(c.pagina::mostraOSeletor));
        Visual.naEdt(() -> c.pagina.tabelaDeRepositorios().selecionarLinha(0));
        PaginaGitTest.esperar("app de novo", () -> !c.pagina.carregando() && !c.pagina.mostraMensagem());
        assertEquals("Repositório: app/", ed(c.pagina::textoDoRepositorioEmUso));
    }

    @Test
    void oLinkDeCriarNaPastaPrincipalAbreAConferenciaEPrecisaDaTrava() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.GIT, "app");
        c.lerAAba();
        Visual.naEdt(() -> c.pagina.botaoTrocarRepositorio().doClick(0)); // volta à lista
        PaginaGitTest.esperar("a lista", () -> c.pagina.mostraOSeletor());
        Visual.naEdt(() -> c.pagina.botaoCriarMesmoAssim().doClick(0));
        assertEquals(List.of("criar"), c.pedidos, "o link abre a janela de conferência (que traz o aviso e a caixa de confirmação)");

        Cena sem = new Cena(false, Aba.GIT, "app");
        sem.lerAAba();
        Visual.naEdt(() -> sem.pagina.botaoTrocarRepositorio().doClick(0));
        PaginaGitTest.esperar("a lista", () -> sem.pagina.mostraOSeletor());
        assertFalse(ed(() -> sem.pagina.botaoCriarMesmoAssim().isEnabled()), "sem a trava das ações o link fica desligado");
        Visual.naEdt(() -> sem.pagina.botaoCriarMesmoAssim().doClick(0));
        assertTrue(sem.pedidos.isEmpty());
    }

    @Test
    void falhaNaLeituraDaSubpastaDizOMotivoEDeixaVoltarALista() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.GIT, "api", "web");
        c.subs.get("web").falhaDeCommits = new br.com.labdesktop.git.FalhaDoGit(br.com.labdesktop.git.FalhaDoGit.Tipo.SEM_PERMISSAO, "O usuário do app não tem permissão para ler esta pasta.");
        c.cena.saida.repositorioGitLembrado = "web";
        c.lerAAba();
        assertEquals("Não consegui ler os commits", ed(c.pagina::tituloDaMensagem));
        assertTrue(ed(() -> c.pagina.botaoListaDeRepositorios().isVisible()), "dá para voltar à lista a partir do erro");
        Visual.naEdt(() -> c.pagina.botaoListaDeRepositorios().doClick(0));
        PaginaGitTest.esperar("a lista", () -> !c.pagina.carregando() && c.pagina.mostraOSeletor());
    }

    // ---------------------------------------------------------------------------------------------
    // O cartão "Código" da Visão geral
    // ---------------------------------------------------------------------------------------------

    @Test
    void oCartaoDiaOndeEstaOGitESemCriarEComDoisLevaAAbaGit() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL, "api", "web");
        c.lerOCartao();
        assertEquals("Este projeto não tem Git na pasta principal, mas achei repositório em: api/ (e outro).", ed(c.linha::textoMostrado));
        assertFalse(ed(() -> c.linha.botaoCriar().isVisible()), "o cartão não oferece criar por cima de um repositório que existe");
        assertTrue(ed(() -> c.linha.botaoVerRepositorios().isVisible()));
        assertFalse(ed(() -> c.linha.botaoVerCommits().isVisible()));
        Visual.naEdt(() -> c.linha.botaoVerRepositorios().doClick(0));
        assertEquals(Aba.GIT, ed(c.cena.tela::abaAtual));
        assertEquals(0, c.subs.get("api").quantas("resumo") + c.subs.get("web").quantas("resumo"), "o cartão não escolhe por conta própria entre dois");
    }

    @Test
    void oCartaoComUmSoRepositorioMostraOSeuGitComORotulo() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL, "app");
        c.lerOCartao();
        String t = ed(c.linha::textoMostrado);
        assertTrue(t.startsWith("Repositório app/ · Branch main · " + LeitorDeGitFalso.commit(3).curto() + " Segundo de app"), t);
        assertTrue(ed(() -> c.linha.botaoVerCommits().isVisible()));
        assertFalse(ed(() -> c.linha.botaoCriar().isVisible() || c.linha.botaoVerRepositorios().isVisible()));
        ResumoDoRepositorio r = ed(c.linha::resumoAtual);
        assertEquals("app", r.subpasta());
        assertEquals(List.of("app"), r.abaixo().stream().map(RepositorioAbaixo::caminho).toList());
    }

    @Test
    void semNenhumRepositorioAbaixoOCartaoDizAPastaDoServidorNaoTemGitEOferecerCriar() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL);
        c.raiz.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
        c.lerOCartao();
        assertEquals(LinhaDoCodigo.SEM_GIT_NO_SERVIDOR, ed(c.linha::textoMostrado));
        assertTrue(LinhaDoCodigo.SEM_GIT_NO_SERVIDOR.startsWith("Esta pasta, no servidor, não tem Git."));
        assertTrue(LinhaDoCodigo.SEM_GIT_NO_SERVIDOR.contains("no seu computador ou no GitHub") && LinhaDoCodigo.SEM_GIT_NO_SERVIDOR.contains("clonado ou enviado"));
        assertFalse(LinhaDoCodigo.SEM_GIT_NO_SERVIDOR.toLowerCase().contains("sincroniz") || LinhaDoCodigo.SEM_GIT_NO_SERVIDOR.toLowerCase().contains("automatic"), "não promete o que não existe");
        assertTrue(LinhaDoCodigo.SEM_GIT_AQUI.startsWith("Esta pasta, neste computador, não tem Git."));
        assertTrue(ed(() -> c.linha.botaoCriar().isVisible() && c.linha.botaoCriar().isEnabled()));
    }

    // ---------------------------------------------------------------------------------------------
    // O que se vê: 1100 x 700 sem rolagem, PNGs claro e escuro
    // ---------------------------------------------------------------------------------------------

    private static String sufixo(Tema t) {
        return t == Tema.CLARO ? "claro" : "escuro";
    }

    private static javax.swing.JComponent largura(javax.swing.JComponent c) {
        c.setSize(1100 - BarraServidores.LARGURA, 672);
        return c;
    }

    @Test
    void asTelasCabemEm1100x700SemRolagemENosDoisTemasComPngs() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            // a aba Git: a lista com três repositórios
            Cena lista = new Cena(true, Aba.GIT, "api", "servicos/web", "worker");
            lista.lerAAba();
            Visual.renderizar(lista.cena.tela, 1100 - BarraServidores.LARGURA, 672);
            assertFalse(ed(() -> lista.pagina.rolagem().getVerticalScrollBar().isVisible()), "a aba Git não rola o corpo: " + t);
            assertEquals(List.of(), ed(() -> SemTransbordo.achados(largura(lista.cena.tela))), "lista, tema " + t);
            Visual.salvar(Visual.renderizar(lista.cena.tela, 1100 - BarraServidores.LARGURA, 672), "projeto-git-repositorios-abaixo-" + sufixo(t));

            // a aba Git: o repositório único, com o rótulo e o "Trocar repositório"
            Cena unico = new Cena(true, Aba.GIT, "app");
            unico.lerAAba();
            Visual.renderizar(unico.cena.tela, 1100 - BarraServidores.LARGURA, 672);
            assertFalse(ed(() -> unico.pagina.rolagem().getVerticalScrollBar().isVisible()));
            assertEquals(List.of(), ed(() -> SemTransbordo.achados(largura(unico.cena.tela))), "repositório único, tema " + t);
            assertEquals("Repositório: app/", ed(unico.pagina::textoDoRepositorioEmUso));
            Visual.salvar(Visual.renderizar(unico.cena.tela, 1100 - BarraServidores.LARGURA, 672), "projeto-git-repositorio-unico-" + sufixo(t));

            // o cartão da Visão geral nos dois casos
            for (String[] caso : new String[][] {{"api", "web"}, {"app"}}) {
                Cena v = new Cena(true, Aba.VISAO_GERAL, caso);
                Visual.naEdt(() -> v.cena.tela.relogio(() -> LeitorDeGitFalso.AGORA, java.time.ZoneId.of("America/Sao_Paulo")));
                v.lerOCartao();
                Visual.renderizar(v.cena.tela, 1100 - BarraServidores.LARGURA, 672);
                var p = ed(() -> v.cena.tela.visaoGeral());
                assertFalse(ed(() -> p.rolagem().getVerticalScrollBar().isVisible()), "a Visão geral não rola: " + String.join(",", caso));
                assertEquals(List.of(), ed(() -> SemTransbordo.achados(largura(v.cena.tela))));
                Visual.salvar(Visual.renderizar(v.cena.tela, 1100 - BarraServidores.LARGURA, 672), caso.length == 2 ? "visao-geral-git-repositorios-abaixo-" + sufixo(t) : "visao-geral-git-repositorio-unico-" + sufixo(t));
                String texto = TelaProjetoTest.textos(v.cena.tela.visaoGeral());
                assertFalse(java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]").matcher(texto).find(), "sem emoji: " + texto);
            }
            // sem Git em lugar nenhum: a frase nova, inteira
            Cena sem = new Cena(true, Aba.VISAO_GERAL);
            sem.raiz.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
            sem.lerOCartao();
            Visual.renderizar(sem.cena.tela, 1100 - BarraServidores.LARGURA, 672);
            assertEquals(List.of(), ed(() -> SemTransbordo.achados(largura(sem.cena.tela))));
            Visual.salvar(Visual.renderizar(sem.cena.tela, 1100 - BarraServidores.LARGURA, 672), "visao-geral-git-sem-git-frase-nova-" + sufixo(t));
            Cena semAba = new Cena(true, Aba.GIT);
            semAba.raiz.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
            semAba.lerAAba();
            Visual.salvar(Visual.renderizar(semAba.cena.tela, 1100 - BarraServidores.LARGURA, 672), "projeto-git-sem-git-frase-nova-" + sufixo(t));
        }
        Visual.tema(Tema.CLARO);
    }
}
