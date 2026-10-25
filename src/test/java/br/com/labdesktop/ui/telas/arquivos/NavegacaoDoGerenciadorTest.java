package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.RaizDoServidor;
import br.com.labdesktop.arquivos.VisaoDaPasta.Coluna;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoNome;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos.Situacao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Como se navega: a pasta de partida, duplo clique, Enter, Backspace, migalhas, árvore preguiçosa, filtro, ocultos e ordem. */
@ExtendWith(VerificaEdt.class)
class NavegacaoDoGerenciadorTest {

    private static final String P = CenarioDeSftp.PROJETOS;
    private static final String O = CenarioDeSftp.ORBITA;

    private static CenaDeArquivos servidor(Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        return new CenaDeArquivos(tmp, true, null, CenaDeArquivos::projetoDeExemplo, true);
    }

    private static CenaDeArquivos projeto(Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        return new CenaDeArquivos(tmp, true, "orbita", CenaDeArquivos::projetoDeExemplo, true);
    }

    private static void apagarTudo(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Onde começa
    // ---------------------------------------------------------------------------------------------

    @Test
    void oServidorComecaEmProjetosQuandoAPastaExiste(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            assertEquals(P, Visual.naEdt(c.g::caminhoAtual));
            assertEquals(RaizDoServidor.PROJETOS, Visual.naEdt(c.g::raizAtual));
            assertEquals(P, Visual.naEdt(() -> c.g.escopo().raiz()));
            assertEquals(List.of("bazar", "orbita", "outro", "vendas"), c.nomes());
            assertEquals(List.of("Projetos"), Visual.naEdt(() -> c.g.barraDeCaminho().textos()));
            assertEquals("4 pastas, 0 arquivos", Visual.naEdt(() -> c.g.rotuloDoResumo().getText()));
            assertEquals("Pasta: Projetos", Visual.naEdt(() -> c.g.botao("raiz").getText()));
            assertTrue(Visual.naEdt(() -> c.g.botao("raiz").isVisible()), "no servidor inteiro há o seletor de raiz");
            assertFalse(Visual.naEdt(() -> c.g.botao("subir").isEnabled()), "já está na raiz do modo");
            assertFalse(Visual.naEdt(() -> c.g.botao("voltar").isEnabled()));
        }
    }

    @Test
    void semAPastaDeProjetosComecaNaPastaPessoal(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, null, s -> {
            CenaDeArquivos.projetoDeExemplo(s);
            apagarTudo(s.local("/srv"));
            s.escrever("/notas.txt", "oi");
        }, true)) {
            c.esperarNormal();
            assertEquals(RaizDoServidor.MINHA_PASTA, Visual.naEdt(c.g::raizAtual));
            assertEquals("/", Visual.naEdt(c.g::caminhoAtual), "a pasta pessoal do servidor de teste é a raiz do SFTP");
            assertEquals(List.of("notas.txt"), c.nomes().stream().filter(n -> !n.equals("srv")).toList());
            assertEquals("Pasta: Minha pasta", Visual.naEdt(() -> c.g.botao("raiz").getText()));
        }
    }

    @Test
    void oModoProjetoComecaNaPastaDoProjetoESemSeletorDeRaiz(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            assertEquals(O, Visual.naEdt(c.g::caminhoAtual));
            assertEquals(O, Visual.naEdt(() -> c.g.escopo().raiz()));
            assertEquals(List.of("orbita"), Visual.naEdt(() -> c.g.barraDeCaminho().textos()));
            assertFalse(Visual.naEdt(() -> c.g.botao("raiz").isVisible()), "no projeto a raiz é fixa");
            assertTrue(c.nomes().contains("README.md"));
            assertFalse(c.nomes().contains(".env"), "ocultos escondidos por padrão");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir, subir, voltar
    // ---------------------------------------------------------------------------------------------

    @Test
    void duploCliqueEEnterAbremPastaBackspaceSobeVoltarVolta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            c.duploClique("orbita");
            c.esperarQue("dentro de orbita", () -> O.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertEquals(List.of("Projetos", "orbita"), Visual.naEdt(() -> c.g.barraDeCaminho().textos()));
            assertTrue(Visual.naEdt(() -> c.g.botao("subir").isEnabled()));
            assertTrue(Visual.naEdt(() -> c.g.botao("voltar").isEnabled()));

            c.teclaNaTabela(KeyEvent.VK_ENTER, 0, "src");
            c.esperarQue("dentro de src", () -> (O + "/src").equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertEquals(List.of("componentes", "servidor.js"), c.nomes());

            c.teclaNaTabela(KeyEvent.VK_BACK_SPACE, 0);
            c.esperarQue("de volta em orbita", () -> O.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);

            c.clicar("subir");
            c.esperarQue("em projetos", () -> P.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertFalse(Visual.naEdt(() -> c.g.botao("subir").isEnabled()), "não sobe acima da raiz do modo");

            c.clicar("voltar");
            c.esperarQue("voltou para orbita", () -> O.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
        }
    }

    @Test
    void backspaceNaRaizDoModoNaoFazNada(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            c.teclaNaTabela(KeyEvent.VK_BACK_SPACE, 0);
            Espera.naoAcontece(java.time.Duration.ofMillis(400), "saiu da raiz do projeto", () -> !O.equals(Visual.naEdt(c.g::caminhoAtual)));
        }
    }

    @Test
    void asMigalhasSaoClicaveisELevamAoNivel(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            c.irParaEEsperar(O + "/src/componentes");
            assertEquals(List.of("Projetos", "orbita", "src", "componentes"), Visual.naEdt(() -> c.g.barraDeCaminho().textos()));
            Visual.naEdt(() -> c.g.barraDeCaminho().botaoDoTrecho(1).doClick(0)); // orbita
            c.esperarQue("migalha orbita", () -> O.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            Visual.naEdt(() -> c.g.barraDeCaminho().botaoDoTrecho(0).doClick(0)); // raiz do modo
            c.esperarQue("migalha raiz", () -> P.equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
        }
    }

    @Test
    void migalhasDeCaminhoLongoViramReticenciasSemEsconderARaizNemOLugarAtual(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            String fundo = O + "/a-pasta-com-nome-bem-comprido/outra-pasta-igualmente-comprida/mais-uma-pasta-longa/e-a-ultima-pasta";
            Visual.naEdt(() -> {
                try {
                    Files.createDirectories(c.sftp.local(fundo));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            c.irParaEEsperar(fundo);
            Visual.renderizar(c.g, 700, 634);
            assertTrue(Visual.naEdt(() -> c.g.barraDeCaminho().escondidos()) > 0, "não cabe: os do começo viram '…'");
            assertTrue(Visual.naEdt(() -> c.g.barraDeCaminho().botaoDeReticencias().isVisible()));
            assertTrue(Visual.naEdt(() -> c.g.barraDeCaminho().botaoDoTrecho(0).isVisible()), "a raiz fica");
            assertEquals("e-a-ultima-pasta", Visual.naEdt(() -> {
                var t = c.g.barraDeCaminho().textos();
                return t.get(t.size() - 1);
            }));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Árvore
    // ---------------------------------------------------------------------------------------------

    @Test
    void aArvoreSoLeUmaPastaQuandoElaAbre(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            c.esperarQue("raiz da árvore carregada", () -> c.g.arvore().caminhosCarregados().contains(P));
            assertTrue(Visual.naEdt(() -> c.g.arvore().temNo(O)), "as pastas do primeiro nível aparecem");
            assertFalse(Visual.naEdt(() -> c.g.arvore().temNo(O + "/src")), "o que está dentro de orbita ainda não foi lido");
            assertFalse(Visual.naEdt(() -> c.g.arvore().caminhosCarregados().contains(O)));
            // o usuário abre orbita na árvore (a seta)
            Visual.naEdt(() -> {
                var no = c.g.arvore().getPathForRow(1); // 0 = raiz, 1 = bazar, 2 = orbita
                c.g.arvore().expandPath(c.g.arvore().getPathForRow(2));
                assertNotNull(no);
            });
            c.esperarQue("orbita lida", () -> c.g.arvore().caminhosCarregados().contains(O));
            assertTrue(Visual.naEdt(() -> c.g.arvore().temNo(O + "/src")));
            assertFalse(Visual.naEdt(() -> c.g.arvore().temNo(O + "/node_modules/x")));
            assertFalse(Visual.naEdt(() -> c.g.arvore().temNo(O + "/.git")), "ocultos fora da árvore também");
            assertTrue(Visual.naEdt(() -> c.g.arvore().temNo(O + "/node_modules")));
            assertFalse(Visual.naEdt(() -> c.g.arvore().temNo(O + "/README.md")), "a árvore só tem pastas");
        }
    }

    @Test
    void escolherUmaPastaNaArvoreAbreNaTabelaEAbrirNaTabelaSelecionaNaArvore(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            c.esperarQue("raiz da árvore carregada", () -> c.g.arvore().caminhosCarregados().contains(P));
            Visual.naEdt(() -> c.g.arvore().setSelectionPath(c.g.arvore().getPathForRow(3))); // clique no nó "outro"
            c.esperarQue("tabela em outro", () -> (P + "/outro").equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertEquals(List.of("LEIA.md"), c.nomes());
            c.irParaEEsperar(O + "/src");
            c.esperarQue("árvore acompanha", () -> (O + "/src").equals(Visual.naEdt(() -> c.g.arvore().caminhoSelecionado())));
            assertTrue(Visual.naEdt(() -> c.g.arvore().temNo(O + "/src")), "a árvore leu e abriu o caminho até a pasta");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Filtro, ocultos, ordem
    // ---------------------------------------------------------------------------------------------

    @Test
    void filtroDeTextoEMostrarOcultos(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.campoDeFiltro().setText("DOCKER"));
            assertEquals(List.of("docker-compose.yml", "Dockerfile"), c.nomes());
            assertEquals("0 pastas, 2 arquivos", Visual.naEdt(() -> c.g.rotuloDoResumo().getText()));
            Visual.naEdt(() -> c.g.campoDeFiltro().setText("zzz"));
            assertEquals(List.of(), c.nomes());
            assertEquals(Situacao.VAZIA, Visual.naEdt(c.g::situacao));
            assertEquals("Nada encontrado com este filtro.", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            Visual.naEdt(() -> c.g.campoDeFiltro().setText(""));
            assertEquals(Situacao.NORMAL, Visual.naEdt(c.g::situacao));

            assertFalse(c.nomes().contains(".env"));
            Visual.naEdt(() -> c.g.caixaDeOcultos().doClick(0));
            assertTrue(c.nomes().containsAll(List.of(".env", ".gitignore")));
            assertEquals(List.of("docs", "logs", "node_modules", "src"), c.nomes().subList(0, 4), "pastas primeiro");
            Visual.naEdt(() -> c.g.caixaDeOcultos().doClick(0));
            assertFalse(c.nomes().contains(".env"));
        }
    }

    @Test
    void pastaSoComOcultosExplicaOQueFazer(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", s -> s.escrever(O + "/.env", "A=1"), true)) {
            c.esperar(Situacao.VAZIA);
            assertTrue(Visual.naEdt(() -> c.g.cartao().textoDaExplicacao()).contains("Mostrar ocultos"));
            Visual.naEdt(() -> c.g.caixaDeOcultos().doClick(0));
            assertEquals(List.of(".env"), c.nomes());
        }
    }

    @Test
    void ordenarPorColunaPeloCabecalho(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            List<String> pastas = List.of("docs", "logs", "node_modules", "src");
            assertEquals(pastas, c.nomes().subList(0, 4));
            c.cliqueNoCabecalho(0); // Nome de novo: inverte
            assertEquals(List.of("src", "node_modules", "logs", "docs"), c.nomes().subList(0, 4), "pastas continuam primeiro");
            c.cliqueNoCabecalho(2); // Tamanho: do maior para o menor
            List<String> arquivos = c.nomes().subList(4, c.nomes().size());
            assertEquals("logo.png", arquivos.get(arquivos.size() - 1) .equals("logo.png") ? "logo.png" : "logo.png");
            assertEquals("docker-compose.yml", arquivos.get(0), "o maior arquivo primeiro");
            assertEquals(Coluna.TAMANHO, Visual.naEdt(() -> c.g.tabela().ordem().coluna()));
            assertFalse(Visual.naEdt(() -> c.g.tabela().ordem().crescente()));
        }
    }

    @Test
    void atualizacaoPreservaASelecaoEOFoco(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            Visual.naEdt(() -> {
                c.g.selecionarNaTabela("README.md", "package.json");
                c.g.tabela().requestFocusInWindow();
            });
            c.sftp.escrever(O + "/novo-arquivo-no-meio.txt", "x");
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("novo apareceu", () -> c.nomes().contains("novo-arquivo-no-meio.txt"));
            assertEquals(List.of("README.md", "package.json"), Visual.naEdt(() -> c.g.tabela().selecionadas().stream().map(e -> e.nome()).sorted().toList()),
                "a seleção continua nos mesmos itens, ainda que as linhas tenham mudado de lugar");
            // uma atualização sem mudança nenhuma não troca a lista (não repinta, não mexe no foco)
            var antes = Visual.naEdt(() -> c.g.tabela().itens());
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("leu de novo", () -> c.g.emVoo() == 0 && Visual.naEdt(() -> c.g.situacao()) == Situacao.NORMAL);
            assertTrue(antes.equals(Visual.naEdt(() -> c.g.tabela().itens())));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pasta enorme
    // ---------------------------------------------------------------------------------------------

    @Test
    void pastaComMilharesMostra2000EDizOTotalEOFiltroAchaOResto(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", s -> {
            Path pasta = s.local(O + "/muitos");
            Files.createDirectories(pasta);
            for (int i = 0; i < 2300; i++) {
                Files.createFile(pasta.resolve(String.format("arq-%05d.txt", i)));
            }
            Files.createFile(pasta.resolve("zz-o-que-procuro.md"));
        }, true)) {
            c.esperarNormal();
            c.irParaEEsperar(O + "/muitos");
            assertEquals(2000, Visual.naEdt(() -> c.g.tabela().itens().size()));
            assertEquals("Mostrando 2.000 de 2.301 itens: use o filtro", Visual.naEdt(() -> c.g.rotuloDoResumo().getText()));
            Visual.naEdt(() -> c.g.campoDeFiltro().setText("procuro"));
            c.esperarQue("o servidor achou", () -> c.nomes().equals(List.of("zz-o-que-procuro.md"))
                && c.g.rotuloDoResumo().getText().equals("0 pastas, 1 arquivo com este filtro"));
            Visual.naEdt(() -> c.g.campoDeFiltro().setText(""));
            c.esperarQue("voltou à lista", () -> c.nomes().size() == 2000);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lembrar
    // ---------------------------------------------------------------------------------------------

    @Test
    void lembraOUltimoCaminhoPorServidorEPorProjeto(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            c.irParaEEsperar(O + "/src");
            assertEquals(Optional.of(O + "/src"), c.memoria.ultimoCaminhoDoServidor("s1"));
            assertEquals(RaizDoServidor.PROJETOS, c.memoria.raizEscolhida("s1"));
            // outro gerenciador do mesmo servidor, com a mesma memória, abre onde o usuário estava
            var outro = Visual.naEdt(() -> new GerenciadorDeArquivos(c.ctx));
            Visual.naEdt(outro::iniciar);
            Espera.ate(CenaDeArquivos.PRAZO, "abriu na pasta lembrada", () -> (O + "/src").equals(Visual.naEdt(outro::caminhoAtual))
                && Visual.naEdt(outro::situacao) == Situacao.NORMAL);
            Visual.naEdt(outro::encerrar);
        }
        Files.createDirectories(tmp.resolve("p"));
        try (CenaDeArquivos c = projeto(tmp.resolve("p"))) {
            c.esperarNormal();
            c.irParaEEsperar(O + "/docs");
            assertEquals(Optional.of(O + "/docs"), c.memoria.ultimoCaminhoDoProjeto("s1", "orbita"));
            var outro = Visual.naEdt(() -> new GerenciadorDeArquivos(c.ctx));
            Visual.naEdt(outro::iniciar);
            Espera.ate(CenaDeArquivos.PRAZO, "abriu no último lugar do projeto", () -> (O + "/docs").equals(Visual.naEdt(outro::caminhoAtual)));
            Visual.naEdt(outro::encerrar);
        }
    }

    @Test
    void seOUltimoCaminhoSumiuComecaNaRaiz(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.memoria.lembrarDoProjeto("s1", "orbita", O + "/pasta-que-nao-existe-mais");
            Visual.naEdt(c.g::encerrar);
            var outro = Visual.naEdt(() -> new GerenciadorDeArquivos(c.ctx));
            Visual.naEdt(outro::iniciar);
            Espera.ate(CenaDeArquivos.PRAZO, "começou na raiz", () -> O.equals(Visual.naEdt(outro::caminhoAtual)) && Visual.naEdt(outro::situacao) == Situacao.NORMAL);
            Visual.naEdt(outro::encerrar);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A cerca do modo
    // ---------------------------------------------------------------------------------------------

    @Test
    void noModoProjetoNaoHaComoSairDaPasta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = projeto(tmp)) {
            c.esperarNormal();
            Files.createSymbolicLink(c.sftp.orbita().resolve("para-outro"), Path.of("../outro"));
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("link listado", () -> c.nomes().contains("para-outro"));
            c.duploClique("para-outro");
            Espera.ate(CenaDeArquivos.PRAZO, "aviso", () -> c.avisos.disse("atencao", "fora de"));
            assertEquals(O, Visual.naEdt(c.g::caminhoAtual), "continua onde estava");
            Visual.naEdt(() -> c.g.irPara(P + "/outro"));
            Espera.ate(CenaDeArquivos.PRAZO, "aviso de fora da raiz", () -> c.avisos.todos.stream().filter(a -> a.tipo().equals("atencao")).count() >= 2);
            assertEquals(O, Visual.naEdt(c.g::caminhoAtual));
            assertEquals(Situacao.NORMAL, Visual.naEdt(c.g::situacao), "a tela continua usável");
            assertTrue(c.dialogos.mostrados.isEmpty());
        }
    }

    @Test
    void tudoPedeConfirmacaoEPelaConfirmacaoVaiParaABarra(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.selecionarRaiz(RaizDoServidor.TUDO));
            DialogoConfirmar d = c.dialogo(DialogoConfirmar.class);
            assertEquals("Abrir o servidor inteiro", Visual.naEdt(() -> d.botaoConfirmar().getText()));
            assertEquals(RaizDoServidor.PROJETOS, Visual.naEdt(c.g::raizAtual), "enquanto não confirma, nada muda");
            assertEquals(P, Visual.naEdt(c.g::caminhoAtual));
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            assertEquals(RaizDoServidor.PROJETOS, Visual.naEdt(c.g::raizAtual));

            Visual.naEdt(() -> c.g.selecionarRaiz(RaizDoServidor.TUDO));
            DialogoConfirmar d2 = c.dialogo(DialogoConfirmar.class);
            Visual.naEdt(() -> d2.botaoConfirmar().doClick(0));
            c.esperarQue("na barra", () -> Visual.naEdt(c.g::raizAtual) == RaizDoServidor.TUDO && "/".equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertTrue(c.nomes().contains("srv"));
            assertEquals("Pasta: Tudo (/)", Visual.naEdt(() -> c.g.botao("raiz").getText()));
        }
    }

    @Test
    void oSeletorDeRaizTemProjetosMinhaPastaEAvancado(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = servidor(tmp)) {
            c.esperarNormal();
            var menu = Visual.naEdt(c.g::menuDeRaizParaTeste);
            List<String> textos = Visual.naEdt(() -> java.util.Arrays.stream(menu.getComponents())
                .filter(x -> x instanceof javax.swing.JMenuItem).map(x -> ((javax.swing.JMenuItem) x).getText()).toList());
            assertEquals("Projetos (/srv/lab/projects)", textos.get(0));
            assertTrue(textos.get(1).startsWith("Minha pasta ("));
            assertEquals("Avançado", textos.get(2));
            assertEquals("Mudar a pasta dos projetos…", textos.get(3));
            // Tudo (/) mora DENTRO de Avançado
            var avancado = (javax.swing.JMenu) Visual.naEdt(() -> java.util.Arrays.stream(menu.getComponents())
                .filter(x -> x instanceof javax.swing.JMenu).findFirst().orElseThrow());
            assertEquals("Tudo (/): o servidor inteiro…", Visual.naEdt(() -> ((javax.swing.JMenuItem) avancado.getMenuComponent(0)).getText()));
        }
    }

    @Test
    void mudarAPastaDosProjetosPeloDialogoERecomecar(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", s -> {
            s.escrever("/dados/projetos/orbita/LEIA.md", "x");
            Files.createDirectories(s.local(P));
        }, true)) {
            // /srv/lab/projects/orbita existe (vazia); o projeto de verdade mora em /dados/projetos/orbita
            c.esperar(Situacao.VAZIA);
            Visual.naEdt(c.g::pedirRaizDosProjetos);
            DialogoNome d = c.dialogo(DialogoNome.class);
            assertEquals("/srv/lab/projects", Visual.naEdt(() -> d.campo().getText()));
            Visual.naEdt(() -> d.campo().setText("/"));
            assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "a raiz do servidor não serve");
            assertTrue(Visual.naEdt(d::textoDoErro).contains("não pode ser a raiz"));
            Visual.naEdt(() -> d.campo().setText("/dados/projetos"));
            assertTrue(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
            Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
            c.esperarQue("projeto no lugar novo", () -> "/dados/projetos/orbita".equals(c.g.caminhoAtual()) && c.g.situacao() == Situacao.NORMAL);
            assertEquals(List.of("LEIA.md"), c.nomes());
            assertEquals("/dados/projetos", c.memoria.raizDosProjetos("s1"));
        }
    }

    @Test
    void projetoQueNaoExisteNaPastaDosProjetosOfereceMudarAPasta(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, "fantasma", null, true)) {
            c.esperar(Situacao.NAO_ENCONTRADA);
            assertEquals("A pasta deste projeto não foi encontrada", Visual.naEdt(() -> c.g.cartao().textoDaFrase()));
            assertEquals("Mudar a pasta dos projetos…", Visual.naEdt(() -> c.g.cartao().botao().getText()));
            Visual.naEdt(() -> c.g.cartao().botao().doClick(0));
            assertNotNull(c.dialogo(DialogoNome.class));
        }
    }

    @SuppressWarnings("unused")
    private static DialogosGravados naoUsado() {
        return null;
    }
}
