package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.git.ClassificadorDeErrosDoGit;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.LeitorDeGitDoProjeto;
import br.com.labdesktop.git.RepositorioAbaixo;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.git.StatusDoGit;
import br.com.labdesktop.teste.LeitorDeGitFalso;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * As imagens da galeria do README sobre o Git do projeto, na JANELA INTEIRA (a barra lateral de servidores e a página do projeto {@code loja-web}), nos dois temas: a aba Git com os commits
 * e o diff, as alterações, os branches, o repositório numa subpasta (e a escolha entre vários), o aviso de arquivo com segredos e os estados da linha "Código" da Visão geral (sem Git, Git não
 * instalado, pasta de outro dono). O Git é o {@link LeitorDeGitFalso} ligado ao contexto da página aberta dentro da janela, com autores, mensagens e hashes inventados
 * ({@link DadosDaGaleria}); nada é lido de um repositório de verdade.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaGitTest {

    @TempDir
    Path pasta;

    private static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private static void esperar(String o_que, Supplier<Boolean> condicao) {
        Espera.ate(CenaDaGaleria.PRAZO, o_que, () -> Visual.naEdt(condicao));
    }

    /** A aba Git da janela terminou de ler (e, com commits, mostra todos). */
    private static void esperarALeitura(CenaDaGaleria.PaginaDaAba git, int commits) {
        esperar("a aba Git terminar de ler", () -> !git.<Boolean>chamar("carregando") && git.<Integer>chamar("commitsCarregados") == commits);
    }

    private static void selecionar(Object tabela, int linha) {
        JTable t = (JTable) tabela;
        Visual.naEdt(() -> t.setRowSelectionInterval(linha, linha));
    }

    private static void subAba(CenaDaGaleria.PaginaDaAba git, String id) {
        Abas abas = ed(() -> git.<Abas>chamar("subAbas"));
        Visual.naEdt(() -> abas.selecionar(id));
    }

    private static boolean mostraDiff(Object vistaDeDiff) {
        return Reflexo.chamar(vistaDeDiff, "mostraDiff");
    }

    // ---------------------------------------------------------------------------------------------
    // Commits, alterações e branches
    // ---------------------------------------------------------------------------------------------

    @Test
    void commitsAlteracoesEBranches() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            LeitorDeGitFalso leitor = DadosDaGaleria.leitorComHistorico(8, c.agora, 4);
            leitor.status = new StatusDoGit("main", false, leitor.commits.get(0).hash(), null, -1, -1, DadosDaGaleria.alteracoes());
            TelaProjeto t = c.abrirProjeto(Aba.GIT, leitor);
            CenaDaGaleria.PaginaDaAba git = CenaDaGaleria.pagina(t, Aba.GIT);
            esperarALeitura(git, 8);

            // 1. os commits, com o primeiro escolhido: arquivos mudados e o diff
            selecionar(ed(() -> git.chamar("tabelaDeCommits")), 0);
            esperar("o detalhe e o diff do commit", () -> leitor.commits.get(0).hash().equals(git.<String>chamar("hashDoDetalhe")) && mostraDiff(git.chamar("diffDoCommit")));
            c.conferir("Git, commits", "Commits", "Alterações (4)", "Branches (3)", "Corrige o cálculo do frete", "Ana Souza", "Beto Lima", "src/rotas/frete.js", "testes/frete.test.js");
            String diff = ed(() -> Reflexo.<String>chamar(git.chamar("diffDoCommit"), "textoParaCopiar"));
            assertTrue(diff.contains("+  if (base === undefined) {\n") && diff.contains("-  return 10;\n"), "o diff do commit tem linhas acrescentadas (+) e tiradas (-):\n" + diff);
            assertEquals(1, leitor.quantas("detalhe:" + leitor.commits.get(0).hash()), "o detalhe do commit foi lido uma vez");
            c.fotografar("projeto-git-commits");

            // 2. as alterações que ainda não viraram commit, com o diff da primeira
            subAba(git, "alteracoes");
            selecionar(ed(() -> git.chamar("tabelaDePendencias")), 0);
            esperar("o diff da alteração", () -> mostraDiff(git.chamar("diffPendente")));
            c.conferir("Git, alterações", "src/componentes/carrinho.js", "src/rotas/pedidos.js", "docs/frete.md", "modificado", "arquivo novo, ainda não preparado");
            assertTrue(ed(() -> Reflexo.<String>chamar(git.chamar("diffPendente"), "textoParaCopiar")).contains("+  quantidade: itens.length,\n"));
            c.fotografar("projeto-git-alteracoes");

            // 3. os branches
            subAba(git, "branches");
            c.conferir("Git, branches", "main", "feature/pagina-de-pedidos", "correcao/frete", "atual");
            c.fotografar("projeto-git-branches");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Repositório numa subpasta
    // ---------------------------------------------------------------------------------------------

    /** O Git do projeto como a página o monta: o leitor da pasta principal (sem {@code .git}) e um por subpasta, com a escolha guardada pelo próprio leitor do projeto. */
    private static LeitorDeGitDoProjeto leitorComSubpastas(Map<String, LeitorDeGitFalso> subs, boolean listaComLeitura) {
        LeitorDeGitFalso raiz = new LeitorDeGitFalso();
        List<RepositorioAbaixo> lista = new java.util.ArrayList<>();
        for (Map.Entry<String, LeitorDeGitFalso> e : subs.entrySet()) {
            Commit ultimo = e.getValue().commits.get(0);
            lista.add(listaComLeitura ? new RepositorioAbaixo(e.getKey(), "main", ultimo, true) : RepositorioAbaixo.semLeitura(e.getKey()));
        }
        raiz.resumo = new ResultadoDoGit.Ok<>(ResumoDoRepositorio.repositoriosAbaixo(lista, false));
        return new LeitorDeGitDoProjeto(() -> raiz, sub -> subs.get(sub), LeitorDeGitDoProjeto.Memoria.NENHUMA);
    }

    @Test
    void repositorioNumaSubpasta() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            Map<String, LeitorDeGitFalso> subs = new LinkedHashMap<>();
            subs.put("app", DadosDaGaleria.leitorComHistorico(6, c.agora, 2));
            TelaProjeto t = c.abrirProjeto(Aba.GIT, leitorComSubpastas(subs, false));
            CenaDaGaleria.PaginaDaAba git = CenaDaGaleria.pagina(t, Aba.GIT);
            esperarALeitura(git, 6);
            selecionar(ed(() -> git.chamar("tabelaDeCommits")), 0);
            esperar("o detalhe do commit", () -> mostraDiff(git.chamar("diffDoCommit")));
            c.conferir("Git, repositório em subpasta", "Repositório: app/", "Trocar repositório", "Commits", "Corrige o cálculo do frete");
            c.fotografar("projeto-git-subpasta");
        }
    }

    @Test
    void escolherEntreVariosRepositorios() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            Map<String, LeitorDeGitFalso> subs = new LinkedHashMap<>();
            subs.put("app", DadosDaGaleria.leitorComHistorico(6, c.agora, 2));
            subs.put("api", DadosDaGaleria.leitorComHistorico(6, c.agora, 0, 4));
            subs.put("docs", DadosDaGaleria.leitorComHistorico(6, c.agora, 0, 8));
            TelaProjeto t = c.abrirProjeto(Aba.GIT, leitorComSubpastas(subs, true));
            CenaDaGaleria.PaginaDaAba git = CenaDaGaleria.pagina(t, Aba.GIT);
            esperar("a lista de repositórios", () -> !git.<Boolean>chamar("carregando") && git.<Boolean>chamar("mostraOSeletor"));
            c.conferir("Git, escolher o repositório", "Este projeto não tem Git na pasta principal", "app/", "api/", "docs/", "main");
            assertEquals(3, ed(() -> ((JTable) git.chamar("tabelaDeRepositorios")).getRowCount()));
            c.fotografar("projeto-git-escolher");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Arquivo com cara de segredo
    // ---------------------------------------------------------------------------------------------

    @Test
    void arquivoComSegredosNaoMostraOConteudo() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            LeitorDeGitFalso leitor = DadosDaGaleria.leitorComHistorico(5, c.agora, 3);
            leitor.status = new StatusDoGit("main", false, leitor.commits.get(0).hash(), null, -1, -1, List.of(
                new br.com.labdesktop.git.Pendencia(".env", null, br.com.labdesktop.git.Pendencia.Tipo.NAO_RASTREADO, false, true),
                new br.com.labdesktop.git.Pendencia("src/componentes/carrinho.js", null, br.com.labdesktop.git.Pendencia.Tipo.MODIFICADO, false, true),
                new br.com.labdesktop.git.Pendencia("docs/frete.md", null, br.com.labdesktop.git.Pendencia.Tipo.NAO_RASTREADO, false, true)));
            // se o app pedisse o conteúdo deste arquivo ao git, o teste veria a chamada abaixo (e nada do valor aparece em tela de qualquer jeito)
            leitor.diffs = chave -> new ResultadoDoGit.Ok<>(LeitorDeGitFalso.diff("+VALOR=exemplo-nao-e-um-token"));
            TelaProjeto t = c.abrirProjeto(Aba.GIT, leitor);
            CenaDaGaleria.PaginaDaAba git = CenaDaGaleria.pagina(t, Aba.GIT);
            esperarALeitura(git, 5);
            subAba(git, "alteracoes");
            selecionar(ed(() -> git.chamar("tabelaDePendencias")), 0); // o .env
            esperar("o aviso no lugar do diff", () -> "Este arquivo parece conter segredos: o conteúdo não é exibido.".equals(Reflexo.<String>chamar(git.chamar("diffPendente"), "textoDaMensagem")));
            c.conferir("Git, segredo", ".env", "Este arquivo parece conter segredos: o conteúdo não é exibido.", "Mostrar mesmo assim");
            assertFalse(c.textos().contains("exemplo-nao-e-um-token"), "o conteúdo não foi desenhado");
            assertEquals(0, leitor.quantas("pendente:.env"), "o app nem pediu o conteúdo ao git");
            c.fotografar("projeto-git-segredo");
            assertEquals(0, leitor.quantas("pendente:.env"), "nem na hora de fotografar");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A linha "Código" da Visão geral: o que não é "tem Git"
    // ---------------------------------------------------------------------------------------------

    private void visaoGeralComResumo(String nome, ResultadoDoGit<ResumoDoRepositorio> resumo, String... esperados) throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta.resolve(nome))) {
            c.cenario.agente.detalhes.put(DadosDaGaleria.PROJETO, DadosDaGaleria.detalheDoProjeto(c.agora, false));
            LeitorDeGitFalso leitor = new LeitorDeGitFalso();
            leitor.resumo = resumo;
            TelaProjeto t = c.abrirProjeto(Aba.VISAO_GERAL, leitor);
            c.esperarODetalhe(t);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "a linha Código lida: " + c.textos(), () -> leitor.quantas("resumo") >= 1 && c.textos().contains(esperados[0]));
            c.conferir("Visão geral, " + nome, esperados);
            c.fotografar(nome);
        }
    }

    @Test
    void semGit() throws Exception {
        visaoGeralComResumo("projeto-git-sem-git", new ResultadoDoGit.Ok<>(ResumoDoRepositorio.semGit()), "Esta pasta, no servidor, não tem Git", "Criar repositório Git");
    }

    @Test
    void gitNaoInstalado() throws Exception {
        visaoGeralComResumo("projeto-git-nao-instalado",
            new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.GIT_NAO_INSTALADO, ClassificadorDeErrosDoGit.naoInstalado())), "Git não instalado", "Instalar o Git…");
    }

    @Test
    void pastaDeOutroDono() throws Exception {
        visaoGeralComResumo("projeto-git-outro-usuario", new ResultadoDoGit.Ok<>(ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.PROPRIEDADE_DUVIDOSA,
            ClassificadorDeErrosDoGit.propriedadeDuvidosa(DadosDaGaleria.PASTA))), "outro usuário", "git config --global --add safe.directory /srv/lab/projects/loja-web", "Copiar comando");
    }
}
