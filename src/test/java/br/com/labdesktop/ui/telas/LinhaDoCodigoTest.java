package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.git.ClassificadorDeErrosDoGit;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.FalhaDoGit;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.git.ResumoDoRepositorio.Estado;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.LeitorDeGitFalso;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A linha "Código" da Visão geral nos estados que o usuário vê: sem Git (com o botão que cria), com Git (branch, último commit, alterações, "Ver commits"), Git não instalado (que leva ao fluxo de
 * sempre), dono diferente (a frase e o comando para COPIAR, nunca executado), mais "dentro de outro repositório", erro e lendo. Respeita a trava e o servidor protegido, e a Visão geral continua sem
 * rolagem em 1100 x 700 em todos.
 */
@ExtendWith(VerificaEdt.class)
class LinhaDoCodigoTest {

    private static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    static List<java.awt.Component> todos(java.awt.Component c) {
        List<java.awt.Component> l = new java.util.ArrayList<>();
        l.add(c);
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                l.addAll(todos(f));
            }
        }
        return l;
    }

    private static final Commit ULTIMO = new Commit("1000007".repeat(6).substring(0, 40), "1000007", "Ana Silva", "ana@example.com", LeitorDeGitFalso.AGORA.minusSeconds(2 * 3600 + 300), List.of(),
        "Corrige o cálculo do frete", 3);

    final class Cena {
        final TelaProjetoTest.Cena cena;
        final LeitorDeGitFalso leitor = new LeitorDeGitFalso();
        final LinhaDoCodigo linha;
        final List<String> pedidos = new java.util.concurrent.CopyOnWriteArrayList<>();

        Cena(boolean acoes) {
            this.cena = new TelaProjetoTest.Cena(acoes, Aba.VISAO_GERAL);
            cena.tela.contexto().leitorDeGit = () -> leitor;
            cena.tela.contexto().criarRepositorio = (depois, ver) -> pedidos.add("criar");
            cena.tela.contexto().instalarGit = depois -> pedidos.add("instalar");
            cena.com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
            this.linha = ed(() -> cena.tela.visaoGeral().linhaDoCodigo());
        }

        void mostrar(ResumoDoRepositorio r) {
            Visual.naEdt(() -> linha.mostrar(r));
        }

        void ler(ResumoDoRepositorio r) {
            leitor.resumo = new ResultadoDoGit.Ok<>(r);
            Visual.naEdt(linha::atualizar);
            PaginaGitTest.esperar("ler o git", () -> !linha.lendo() && linha.resumoAtual() != null);
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void semLeitorMostraOTextoDoAgenteComoAntesDoPacoteR() {
        Visual.tema(Tema.CLARO);
        TelaProjetoTest.Cena c = new TelaProjetoTest.Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        LinhaDoCodigo l = ed(() -> c.tela.visaoGeral().linhaDoCodigo());
        assertEquals("Branch main, com mudanças que ainda não foram salvas no git", ed(l::textoMostrado));
        assertFalse(ed(() -> l.botaoCriar().isVisible() || l.botaoVerCommits().isVisible() || l.botaoInstalar().isVisible()));
        Visual.naEdt(l::atualizar); // sem leitor: não faz nada
        assertEquals("Branch main, com mudanças que ainda não foram salvas no git", ed(l::textoMostrado));
        assertTrue(ed(() -> c.tela.visaoGeral().ficha().pares().contains(List.of("Código", "Branch main, com mudanças que ainda não foram salvas no git"))));
    }

    @Test
    void semGitDizSemGitEOferecerCriar() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.ler(ResumoDoRepositorio.semGit());
        assertEquals("Sem Git", ed(c.linha::textoMostrado));
        assertTrue(ed(() -> c.linha.botaoCriar().isVisible() && c.linha.botaoCriar().isEnabled()));
        assertEquals("Criar repositório Git", ed(() -> c.linha.botaoCriar().getText()));
        assertFalse(ed(() -> c.linha.botaoVerCommits().isVisible()));
        Visual.naEdt(() -> c.linha.botaoCriar().doClick(0));
        assertEquals(List.of("criar"), c.pedidos);
    }

    @Test
    void semAsAcoesOBotaoDeCriarAparecePorEmBaixoDesligadoComARazao() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(false);
        c.ler(ResumoDoRepositorio.semGit());
        assertTrue(ed(() -> c.linha.botaoCriar().isVisible()), "não some");
        assertFalse(ed(() -> c.linha.botaoCriar().isEnabled()));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> c.linha.botaoCriar().getToolTipText()));
        Visual.naEdt(() -> c.linha.botaoCriar().doClick(0));
        assertTrue(c.pedidos.isEmpty());
    }

    @Test
    void comGitMostraBranchUltimoCommitQuandoEAlteracoesEVerCommitsAbreAAbaGit() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        Visual.naEdt(() -> c.cena.tela.relogio(() -> LeitorDeGitFalso.AGORA, java.time.ZoneId.of("America/Sao_Paulo")));
        c.ler(LeitorDeGitFalso.comGit("main", ULTIMO, 3));
        assertEquals("Branch main · 1000007 Corrige o cálculo do frete · há 2 horas · 3 alterações ainda não salvas", ed(c.linha::textoMostrado));
        assertTrue(ed(() -> c.linha.botaoVerCommits().isVisible()));
        assertFalse(ed(() -> c.linha.botaoCriar().isVisible()));
        Visual.naEdt(() -> c.linha.botaoVerCommits().doClick(0));
        assertEquals(Aba.GIT, ed(c.cena.tela::abaAtual), "leva à aba Git");
    }

    @Test
    void comGitSemAlteracoesDizTudoSalvoEUmaSoNoSingular() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        Visual.naEdt(() -> c.cena.tela.relogio(() -> LeitorDeGitFalso.AGORA, java.time.ZoneId.of("America/Sao_Paulo")));
        c.mostrar(LeitorDeGitFalso.comGit("main", ULTIMO, 0));
        assertTrue(ed(c.linha::textoMostrado).endsWith("tudo salvo"));
        c.mostrar(LeitorDeGitFalso.comGit("main", ULTIMO, 1));
        assertTrue(ed(c.linha::textoMostrado).endsWith("1 alteração ainda não salva"));
        c.mostrar(LeitorDeGitFalso.comGit("main", null, 2));
        assertEquals("Branch main · ainda sem commits · 2 alterações ainda não salvas", ed(c.linha::textoMostrado));
        c.mostrar(new ResumoDoRepositorio(Estado.COM_GIT, null, true, ULTIMO, 0, null, null));
        assertTrue(ed(c.linha::textoMostrado).startsWith("Commit solto (sem branch)"));
    }

    @Test
    void gitNaoInstaladoLevaAoFluxoQueJaExisteEServidorProtegidoDesliga() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.ler(ResumoDoRepositorio.de(Estado.GIT_NAO_INSTALADO, ClassificadorDeErrosDoGit.naoInstalado()));
        assertEquals("Git não instalado", ed(c.linha::textoMostrado));
        assertTrue(ed(() -> c.linha.botaoInstalar().isVisible() && c.linha.botaoInstalar().isEnabled()));
        assertEquals("Instalar o Git…", ed(() -> c.linha.botaoInstalar().getText()));
        Visual.naEdt(() -> c.linha.botaoInstalar().doClick(0));
        assertEquals(List.of("instalar"), c.pedidos, "o fluxo de sempre: a linha só chama, não duplica");

        Cena prot = new Cena(true);
        Visual.naEdt(() -> prot.cena.tela.aoMudarEstado(PaginaGitTest.estadoProtegido()));
        prot.ler(ResumoDoRepositorio.de(Estado.GIT_NAO_INSTALADO, ClassificadorDeErrosDoGit.naoInstalado()));
        assertTrue(ed(prot.linha::textoMostrado).contains("servidor protegido"));
        assertFalse(ed(() -> prot.linha.botaoInstalar().isEnabled()));
        Visual.naEdt(() -> prot.linha.botaoInstalar().doClick(0));
        assertTrue(prot.pedidos.isEmpty());
        // ler continua valendo no servidor protegido: a linha leu
        assertEquals(1, prot.leitor.quantas("resumo"));
    }

    @Test
    void donoDiferenteDaAFraseEOComandoExatoComoTextoParaCopiarENuncaOExecuta() throws Exception {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.ler(ResumoDoRepositorio.de(Estado.PROPRIEDADE_DUVIDOSA, ClassificadorDeErrosDoGit.propriedadeDuvidosa("/srv/lab/projects/loja-web")));
        assertEquals("O Git recusou a pasta porque ela é de outro usuário.", ed(c.linha::textoMostrado));
        assertEquals("git config --global --add safe.directory /srv/lab/projects/loja-web", ed(c.linha::comandoMostrado));
        assertTrue(ed(() -> c.linha.botaoTentarDeNovo().isVisible()));
        int antes = c.leitor.chamadas.size();
        // copiar o comando: o botão Copiar comando da linha
        javax.swing.AbstractButton copiar = ed(() -> todos(c.linha).stream().filter(x -> x instanceof javax.swing.AbstractButton b && "Copiar comando".equals(b.getText()))
            .map(x -> (javax.swing.AbstractButton) x).findFirst().orElseThrow());
        Visual.naEdt(() -> copiar.doClick(0));
        assertEquals("git config --global --add safe.directory /srv/lab/projects/loja-web", PaginaGitTest.clipboard(c.cena));
        assertEquals(antes, c.leitor.chamadas.size(), "copiar não roda nada");
        assertTrue(c.leitor.chamadas.stream().noneMatch(x -> x.contains("safe")));
    }

    @Test
    void dentroDeOutroRepositorioErroELendo() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.mostrar(new ResumoDoRepositorio(Estado.DENTRO_DE_OUTRO, null, false, null, 0, null, "loja-web/"));
        assertTrue(ed(c.linha::textoMostrado).startsWith("Sem Git próprio: a pasta está dentro de um repositório"));
        assertFalse(ed(() -> c.linha.botaoCriar().isVisible()), "não cria um repositório dentro de outro");
        c.mostrar(ResumoDoRepositorio.de(Estado.ERRO, new FalhaDoGit(FalhaDoGit.Tipo.SEM_CONEXAO, "Não consegui falar com o servidor para rodar o Git agora.")));
        assertEquals("Não consegui ler o Git: Não consegui falar com o servidor para rodar o Git agora.", ed(c.linha::textoMostrado));
        assertTrue(ed(() -> c.linha.botaoTentarDeNovo().isVisible()));
        c.leitor.segurar = new java.util.concurrent.CountDownLatch(1);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit());
        Visual.naEdt(() -> c.linha.botaoTentarDeNovo().doClick(0));
        assertEquals("Lendo o Git do projeto…", ed(c.linha::textoMostrado));
        c.leitor.segurar.countDown();
        PaginaGitTest.esperar("terminar", () -> !c.linha.lendo() && c.linha.resumoAtual().estado() == Estado.SEM_GIT);
    }

    @Test
    void falhaDeLeituraDoResumoViraErroEmFrase() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.leitor.resumo = new ResultadoDoGit.Falhou<>(new FalhaDoGit(FalhaDoGit.Tipo.DEMOROU, "O Git demorou mais que o prazo (30 s) e o app parou de esperar."));
        Visual.naEdt(c.linha::atualizar);
        PaginaGitTest.esperar("erro", () -> !c.linha.lendo() && c.linha.resumoAtual() != null);
        assertTrue(ed(c.linha::textoMostrado).contains("O Git demorou"));
    }

    // ---------------------------------------------------------------------------------------------
    // Quando lê
    // ---------------------------------------------------------------------------------------------

    @Test
    void leQuandoAVisaoGeralApareceELogoQuandoOGitDoAgenteMudaNuncaDeTemposEmTempos() throws Exception {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(LeitorDeGitFalso.comGit("main", ULTIMO, 0));
        assertEquals(0, c.leitor.chamadas.size(), "montar não lê");
        Visual.naEdt(() -> c.cena.tela.visaoGeral().assinar()); // a Visão geral apareceu
        PaginaGitTest.esperar("primeira leitura", () -> c.leitor.quantas("resumo") == 1 && !c.linha.lendo());
        // o detalhe chega de novo igual (a cada 10 s): nada de ler
        Visual.naEdt(() -> c.cena.tela.aoReceberDetalhe(FixturesDeApi.detalheDoProjetoSintetico()));
        Visual.naEdt(() -> c.cena.tela.aoReceberDetalhe(FixturesDeApi.detalheDoProjetoSintetico()));
        Thread.sleep(500);
        assertEquals(1, c.leitor.quantas("resumo"), "o mesmo git do agente: não relê");
        // o git do agente mudou (um commit novo): lê uma vez
        Visual.naEdt(() -> c.cena.tela.aoReceberDetalhe(TelaProjetoTest.detalheCom("\"sujo\": true", "\"sujo\": false")));
        PaginaGitTest.esperar("releitura por mudança", () -> c.leitor.quantas("resumo") == 2 && !c.linha.lendo());
        // sumir descarta o que está em voo
        c.leitor.segurar = new java.util.concurrent.CountDownLatch(1);
        Visual.naEdt(c.linha::atualizar);
        Visual.naEdt(() -> c.cena.tela.visaoGeral().soltar());
        c.leitor.segurar.countDown();
        Thread.sleep(300);
        assertFalse(ed(c.linha::lendo));
    }

    @Test
    void oAtualizarDaPaginaLeOGitDeNovo() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true);
        c.leitor.resumo = new ResultadoDoGit.Ok<>(LeitorDeGitFalso.comGit("main", ULTIMO, 0));
        Visual.naEdt(() -> c.cena.tela.botaoAtualizar().doClick(0));
        PaginaGitTest.esperar("leu", () -> c.leitor.quantas("resumo") == 1 && !c.linha.lendo());
        assertNull(ed(() -> c.linha.comandoMostrado().isEmpty() ? null : "x"));
    }

    // ---------------------------------------------------------------------------------------------
    // Sem rolagem em 1100 x 700 e PNGs
    // ---------------------------------------------------------------------------------------------

    private static void conferirQueCabe(Cena c, String estado) {
        int largura = 1100 - BarraServidores.LARGURA;
        int altura = 700 - 28;
        int pedida = Visual.alturaPreferida(c.cena.tela, largura);
        assertTrue(pedida <= altura, estado + ": a Visão geral pede " + pedida + " px, a janela de 1100x700 dá " + altura);
        Visual.renderizar(c.cena.tela, largura, altura);
        PaginaVisaoGeral p = ed(c.cena.tela::visaoGeral);
        assertFalse(ed(() -> p.rolagem().getVerticalScrollBar().isVisible()), estado + ": a Visão geral não rola");
        assertFalse(ed(() -> p.rolagem().getHorizontalScrollBar().isVisible()));
        assertFalse(ed(() -> p.rolagem().rola()));
    }

    @Test
    void osQuatroEstadosCabemSemRolagemEm1100x700NosDoisTemasComPngs() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            String sufixo = t == Tema.CLARO ? "claro" : "escuro";
            Cena c = new Cena(true);
            Visual.naEdt(() -> c.cena.tela.relogio(() -> LeitorDeGitFalso.AGORA, java.time.ZoneId.of("America/Sao_Paulo")));
            c.mostrar(ResumoDoRepositorio.semGit());
            conferirQueCabe(c, "sem git");
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - BarraServidores.LARGURA, 672), "visao-geral-git-sem-git-" + sufixo);
            c.mostrar(LeitorDeGitFalso.comGit("main", ULTIMO, 3));
            conferirQueCabe(c, "com git");
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - BarraServidores.LARGURA, 672), "visao-geral-git-com-git-" + sufixo);
            c.mostrar(ResumoDoRepositorio.de(Estado.GIT_NAO_INSTALADO, ClassificadorDeErrosDoGit.naoInstalado()));
            conferirQueCabe(c, "git não instalado");
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - BarraServidores.LARGURA, 672), "visao-geral-git-nao-instalado-" + sufixo);
            c.mostrar(ResumoDoRepositorio.de(Estado.PROPRIEDADE_DUVIDOSA, ClassificadorDeErrosDoGit.propriedadeDuvidosa("/srv/lab/projects/loja-web")));
            conferirQueCabe(c, "dono diferente");
            Visual.salvar(Visual.renderizar(c.cena.tela, 1100 - BarraServidores.LARGURA, 672), "visao-geral-git-dono-diferente-" + sufixo);
            String texto = TelaProjetoTest.textos(c.cena.tela.visaoGeral());
            assertFalse(java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]").matcher(texto).find(), "sem emoji: " + texto);
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void oPiorCasoDaVisaoGeralContinuaCabendoComAFraseDoGit() {
        Visual.tema(Tema.CLARO);
        String falta = "imagem antiga (Claude desatualizado), plugins do harness fora da versão, extensão do projeto sem atualizar";
        String json = TelaProjetoTest.detalheEmUmaLinha()
            .replace("\"atualizada\": true, \"faltando\": []", "\"atualizada\": false, \"faltando\": [\"" + falta + "\"]")
            .replace("[ \"orbita-lab.example.com\" ]", "[ \"orbita-lab.example.com\", \"orbita-api.example.com\", \"orbita-painel-de-controle.example.com\", \"d.example.com\" ]");
        br.com.labdesktop.api.modelo.DetalheProjeto base = FixturesDeApi.de(json, br.com.labdesktop.api.modelo.DetalheProjeto.class);
        TelaProjetoTest.Cena tc = new TelaProjetoTest.Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, base);
        LeitorDeGitFalso leitor = new LeitorDeGitFalso();
        tc.tela.contexto().leitorDeGit = () -> leitor;
        LinhaDoCodigo l = ed(() -> tc.tela.visaoGeral().linhaDoCodigo());
        Visual.naEdt(() -> l.mostrar(LeitorDeGitFalso.comGit("main", ULTIMO, 3)));
        int largura = 1100 - BarraServidores.LARGURA;
        assertTrue(Visual.alturaPreferida(tc.tela, largura) <= 700 - 28, "mesmo com três endereços, a sessão desatualizada e a frase mais longa do Git");
    }
}
