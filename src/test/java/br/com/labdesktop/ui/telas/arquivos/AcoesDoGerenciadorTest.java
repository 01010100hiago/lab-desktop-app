package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.RaizDoServidor;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.dialogos.DialogoApagarArquivos;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoNome;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Criar, renomear, mover, apagar e copiar caminho, com os diálogos de verdade; e o modo somente leitura. */
@ExtendWith(VerificaEdt.class)
class AcoesDoGerenciadorTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static CenaDeArquivos cena(Path tmp, boolean escrita) throws Exception {
        Visual.tema(Tema.CLARO);
        CenaDeArquivos c = new CenaDeArquivos(tmp, escrita, "orbita", CenaDeArquivos::projetoDeExemplo, true);
        c.esperarNormal();
        return c;
    }

    private static JMenuItem item(JPopupMenu menu, String texto) {
        return Visual.naEdt(() -> java.util.Arrays.stream(menu.getComponents())
            .filter(x -> x instanceof JMenuItem m && texto.equals(m.getText())).map(x -> (JMenuItem) x).findFirst()
            .orElseThrow(() -> new AssertionError("o menu não tem '" + texto + "': " + java.util.Arrays.stream(menu.getComponents())
                .filter(x -> x instanceof JMenuItem).map(x -> ((JMenuItem) x).getText()).toList())));
    }

    private static List<String> textosSemOrdenar(JPopupMenu menu) {
        return textosDoMenu(menu).stream().filter(t -> !t.equals("Ordenar por")).toList();
    }

    private static List<String> textosDoMenu(JPopupMenu menu) {
        return Visual.naEdt(() -> java.util.Arrays.stream(menu.getComponents())
            .filter(x -> x instanceof JMenuItem).map(x -> ((JMenuItem) x).getText()).toList());
    }

    // ---------------------------------------------------------------------------------------------
    // Criar
    // ---------------------------------------------------------------------------------------------

    @Test
    void novaPastaPeloDialogoComValidacaoEmFraseSimples(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.clicar("nova-pasta");
            DialogoNome d = c.dialogo(DialogoNome.class);
            assertEquals("Criar pasta", Visual.naEdt(() -> d.botaoConfirmar().getText()));
            assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "sem nome não confirma");
            Visual.naEdt(() -> d.campo().setText("a/b"));
            assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
            assertTrue(Visual.naEdt(d::textoDoErro).contains("não pode ter barra"));
            Visual.naEdt(() -> d.campo().setText(".."));
            assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
            Visual.naEdt(() -> d.campo().setText("relatórios de outubro"));
            assertTrue(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
            assertEquals("", Visual.naEdt(d::textoDoErro));
            Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
            c.esperarQue("pasta criada e listada", () -> c.nomes().contains("relatórios de outubro"));
            assertTrue(Files.isDirectory(c.sftp.orbita().resolve("relatórios de outubro")));
            assertEquals(List.of("relatórios de outubro"), Visual.naEdt(() -> c.g.tabela().selecionadas().stream().map(e -> e.nome()).toList()),
                "a pasta nova fica selecionada");
            assertTrue(c.avisos.disse("ok", "Criei a pasta relatórios de outubro"));
        }
    }

    @Test
    void novaPastaComNomeQueJaExisteDizEmFraseSimples(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Visual.naEdt(() -> c.g.criarPasta("src"));
            Espera.ate(CenaDeArquivos.PRAZO, "aviso", () -> c.avisos.disse("erro", "Já existe um item com este nome"));
            assertEquals(1, c.nomes().stream().filter(n -> n.equals("src")).count());
        }
    }

    @Test
    void novoArquivoCriaVazioEAbreNoEditor(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.clicar("novo-arquivo");
            DialogoNome d = c.dialogo(DialogoNome.class);
            assertEquals("Criar arquivo", Visual.naEdt(() -> d.botaoConfirmar().getText()));
            Visual.naEdt(() -> d.campo().setText("notas.txt"));
            Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
            c.esperarQue("editor do arquivo novo", () -> c.g.editoresAbertos().containsKey(O + "/notas.txt"));
            assertEquals(0, Files.size(c.sftp.orbita().resolve("notas.txt")));
            assertEquals("", Visual.naEdt(() -> c.g.editoresAbertos().get(O + "/notas.txt").area().getText()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Renomear
    // ---------------------------------------------------------------------------------------------

    @Test
    void f2RenomeiaESelecionaOnomeSemAExtensao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.teclaNaTabela(KeyEvent.VK_F2, 0, "README.md");
            DialogoNome d = c.dialogo(DialogoNome.class);
            assertEquals("README.md", Visual.naEdt(() -> d.campo().getText()));
            assertEquals("README", Visual.naEdt(() -> d.campo().getSelectedText()), "só o nome vem selecionado: a extensão fica");
            assertEquals("Renomear", Visual.naEdt(() -> d.botaoConfirmar().getText()));
            Visual.naEdt(() -> d.campo().setText("LEIA-ME.md"));
            Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
            c.esperarQue("renomeado", () -> c.nomes().contains("LEIA-ME.md") && !c.nomes().contains("README.md"));
            assertTrue(Files.exists(c.sftp.orbita().resolve("LEIA-ME.md")));
            assertFalse(Files.exists(c.sftp.orbita().resolve("README.md")));
        }
    }

    @Test
    void renomearParaNomeQueJaExisteNaoSobrescreve(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            var e = Visual.naEdt(() -> c.g.entrada("README.md").orElseThrow());
            Visual.naEdt(() -> c.g.renomear(e, "Dockerfile"));
            Espera.ate(CenaDeArquivos.PRAZO, "aviso", () -> c.avisos.disse("erro", "Já existe"));
            assertTrue(Files.readString(c.sftp.orbita().resolve("Dockerfile")).startsWith("FROM node"));
            assertTrue(Files.exists(c.sftp.orbita().resolve("README.md")));
        }
    }

    @Test
    void naoRenomeiaNemApagaNemMoveComEditorAbertoNele(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.duploClique("README.md");
            c.esperarQue("editor", () -> c.g.editoresAbertos().containsKey(O + "/README.md"));
            Visual.naEdt(() -> c.g.abas().selecionar("navegador"));
            var e = Visual.naEdt(() -> c.g.entrada("README.md").orElseThrow());
            Visual.naEdt(() -> c.g.renomear(e, "outro.md"));
            assertTrue(c.avisos.disse("atencao", "Feche o editor de README.md antes de renomear"));
            Visual.naEdt(() -> c.g.mover(List.of(e), O + "/docs"));
            assertTrue(c.avisos.disse("atencao", "antes de mover"));
            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "README.md");
            assertTrue(c.avisos.disse("atencao", "antes de apagar"));
            assertTrue(c.dialogos.mostrados.isEmpty());
            assertTrue(Files.exists(c.sftp.orbita().resolve("README.md")));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mover
    // ---------------------------------------------------------------------------------------------

    @Test
    void moverParaPeloMenuComAArvoreDePastas(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md", "Dockerfile"));
            JMenuItem mover = item(Visual.naEdt(c.g::menuDeContextoParaTeste), "Mover para…");
            assertTrue(Visual.naEdt(mover::isEnabled));
            Visual.naEdt(() -> mover.doClick(0));
            DialogoEscolherPasta d = c.dialogo(DialogoEscolherPasta.class);
            assertFalse(Visual.naEdt(() -> d.botaoMover().isEnabled()), "sem pasta escolhida não move");
            c.esperarQue("árvore do diálogo carregada", () -> d.arvore().temNo(O + "/docs"));
            Visual.naEdt(() -> d.escolherPorPrograma(O)); // é a pasta onde já estão
            assertFalse(Visual.naEdt(() -> d.botaoMover().isEnabled()));
            Visual.naEdt(() -> d.escolherPorPrograma(O + "/docs"));
            assertEquals("Mover para docs", Visual.naEdt(() -> d.botaoMover().getText()));
            Visual.naEdt(() -> d.botaoMover().doClick(0));
            c.esperarQue("moveu", () -> !c.nomes().contains("README.md") && !c.nomes().contains("Dockerfile"));
            assertTrue(Files.exists(c.sftp.orbita().resolve("docs/README.md")));
            assertTrue(Files.exists(c.sftp.orbita().resolve("docs/Dockerfile")));
            assertTrue(c.avisos.disse("ok", "Movi 2 itens"));
        }
    }

    @Test
    void moverNaoSobrescreveENaoPoeUmaPastaDentroDelaMesma(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.sftp.escrever(O + "/docs/README.md", "outro README");
            var readme = Visual.naEdt(() -> c.g.entrada("README.md").orElseThrow());
            Visual.naEdt(() -> c.g.mover(List.of(readme), O + "/docs"));
            Espera.ate(CenaDeArquivos.PRAZO, "aviso do que não deu", () -> c.avisos.disse("atencao", "README.md: Já existe"));
            assertEquals("outro README", Files.readString(c.sftp.orbita().resolve("docs/README.md")));
            assertTrue(Files.exists(c.sftp.orbita().resolve("README.md")));

            var src = Visual.naEdt(() -> c.g.entrada("src").orElseThrow());
            Visual.naEdt(() -> c.g.mover(List.of(src), O + "/src/componentes"));
            Espera.ate(CenaDeArquivos.PRAZO, "aviso", () -> c.avisos.disse("atencao", "dentro dela mesma"));
            assertTrue(Files.isDirectory(c.sftp.orbita().resolve("src/componentes")));
        }
    }

    @Test
    void arrastarItensDoServidorParaUmaPastaMove(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            var readme = Visual.naEdt(() -> c.g.entrada("README.md").orElseThrow());
            ManipuladorDeArraste h = Visual.naEdt(c.g::manipuladorParaTeste);
            Transferable t = new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {ManipuladorDeArraste.ENTRADAS};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return ManipuladorDeArraste.ENTRADAS.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) {
                    return List.of(readme);
                }
            };
            var suporte = new TransferHandler.TransferSupport(Visual.naEdt(c.g::tabela), t);
            assertTrue(Visual.naEdt(() -> h.canImport(suporte)), "aceita itens do próprio servidor");
            Visual.naEdt(() -> c.g.mover(List.of(readme), O + "/docs")); // o destino de uma soltura sobre a linha "docs"
            c.esperarQue("moveu", () -> !c.nomes().contains("README.md"));
            assertTrue(Files.exists(c.sftp.orbita().resolve("docs/README.md")));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Apagar
    // ---------------------------------------------------------------------------------------------

    @Test
    void apagarArquivoOBotaoTemONomeECancelarEOPadrao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "Dockerfile");
            DialogoApagarArquivos d = c.esperarDialogo(DialogoApagarArquivos.class);
            assertEquals("Apagar Dockerfile", Visual.naEdt(() -> d.botaoApagar().getText()));
            assertEquals(Botao.Variante.PRIMARIO, Visual.naEdt(() -> d.botaoCancelar().variante()), "Cancelar é o botão em destaque");
            assertEquals(Botao.Variante.PERIGO, Visual.naEdt(() -> d.botaoApagar().variante()));
            String texto = Visual.naEdt(() -> TextosDoTeste.de(d.raiz()));
            assertTrue(texto.contains("Apagar o arquivo Dockerfile?"), texto);
            assertTrue(texto.contains("não tem lixeira"), texto);
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            assertTrue(Files.exists(c.sftp.orbita().resolve("Dockerfile")), "cancelar não apaga");

            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "Dockerfile");
            c.esperarQue("segundo diálogo", () -> c.dialogos.mostrados.size() == 2);
            DialogoApagarArquivos d2 = c.dialogo(DialogoApagarArquivos.class);
            Visual.naEdt(() -> d2.botaoApagar().doClick(0));
            c.esperarQue("apagou", () -> !c.nomes().contains("Dockerfile"));
            assertFalse(Files.exists(c.sftp.orbita().resolve("Dockerfile")));
            assertTrue(c.avisos.disse("ok", "Apaguei Dockerfile"));
        }
    }

    @Test
    void apagarPastaDizQuantosItensETamanhoEApagaTudoSemSeguirLink(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Files.createSymbolicLink(c.sftp.orbita().resolve("src/para-outro"), Path.of("../../outro"));
            c.teclaNaTabela(KeyEvent.VK_BACK_SPACE, KeyEvent.META_DOWN_MASK, "src"); // Cmd+Backspace também apaga
            DialogoApagarArquivos d = c.esperarDialogo(DialogoApagarArquivos.class);
            String texto = Visual.naEdt(() -> TextosDoTeste.de(d.raiz()));
            assertTrue(texto.contains("Apagar a pasta src?"), texto);
            assertTrue(texto.contains("A pasta tem 4 itens"), texto); // servidor.js, componentes, cartao.js e o link
            assertEquals("Apagar src", Visual.naEdt(() -> d.botaoApagar().getText()));
            Visual.naEdt(() -> d.botaoApagar().doClick(0));
            c.esperarQue("apagou a pasta", () -> !c.nomes().contains("src"));
            assertFalse(Files.exists(c.sftp.orbita().resolve("src")));
            assertTrue(Files.exists(c.sftp.local(CenarioDeSftp.PROJETOS + "/outro/LEIA.md")), "o link foi apagado, o destino ficou");
        }
    }

    @Test
    void apagarVariosListaOsNomesEOBotaoDizQuantos(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.clicar("apagar"); // sem seleção: botão desabilitado, nada acontece
            assertTrue(c.dialogos.mostrados.isEmpty());
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md", "Dockerfile", "logo.png"));
            c.clicar("apagar");
            DialogoApagarArquivos d = c.esperarDialogo(DialogoApagarArquivos.class);
            assertEquals("Apagar 3 itens", Visual.naEdt(() -> d.botaoApagar().getText()));
            String texto = Visual.naEdt(() -> TextosDoTeste.de(d.raiz()));
            assertTrue(texto.contains("README.md") && texto.contains("Dockerfile") && texto.contains("logo.png"), texto);
        }
    }

    @Test
    void apagarUmLinkDizQueSoOLinkSome(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Files.createSymbolicLink(c.sftp.orbita().resolve("atalho"), Path.of("docs"));
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("link listado", () -> c.nomes().contains("atalho"));
            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "atalho");
            DialogoApagarArquivos d = c.esperarDialogo(DialogoApagarArquivos.class);
            assertTrue(Visual.naEdt(() -> TextosDoTeste.de(d.raiz())).contains("Só o link será apagado"));
            Visual.naEdt(() -> d.botaoApagar().doClick(0));
            c.esperarQue("apagou o link", () -> !c.nomes().contains("atalho"));
            assertTrue(Files.isDirectory(c.sftp.orbita().resolve("docs")), "a pasta para onde ele apontava ficou");
        }
    }

    @Test
    void naoApagaARaizDosProjetosNemOQueAContemNoModoTudo(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenaDeArquivos c = new CenaDeArquivos(tmp, true, null, CenaDeArquivos::projetoDeExemplo, true)) {
            c.esperarNormal();
            Visual.naEdt(() -> c.g.selecionarRaiz(RaizDoServidor.TUDO));
            Visual.naEdt(() -> c.dialogo(DialogoConfirmar.class).botaoConfirmar().doClick(0));
            c.esperarQue("na barra", () -> "/".equals(c.g.caminhoAtual()) && c.g.situacao() == GerenciadorDeArquivos.Situacao.NORMAL && c.nomes().contains("srv"));
            int antes = c.dialogos.mostrados.size();
            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "srv");
            assertTrue(c.avisos.disse("atencao", "sustenta o gerenciador"), "avisa em vez de perguntar");
            assertEquals(antes, c.dialogos.mostrados.size(), "nem abre o diálogo de apagar");
            assertTrue(Files.isDirectory(c.sftp.local("/srv/lab/projects")));
            c.irParaEEsperar("/srv/lab");
            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "projects");
            assertEquals(antes, c.dialogos.mostrados.size());
            assertTrue(Files.isDirectory(c.sftp.local("/srv/lab/projects/orbita")));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Copiar caminho
    // ---------------------------------------------------------------------------------------------

    @Test
    void copiarCaminhoDoSelecionadoOuDaPastaAtual(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md"));
            Visual.naEdt(() -> item(c.g.menuDeContextoParaTeste(), "Copiar caminho").doClick(0));
            assertEquals(O + "/README.md", c.area.getData(DataFlavor.stringFlavor));
            assertTrue(c.avisos.disse("ok", "Copiei o caminho"));
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md", "Dockerfile"));
            Visual.naEdt(c.g::copiarCaminho);
            String dois = (String) c.area.getData(DataFlavor.stringFlavor);
            assertEquals(2, dois.lines().count());
            Visual.naEdt(() -> c.g.tabela().clearSelection());
            Visual.naEdt(c.g::copiarCaminho);
            assertEquals(O, c.area.getData(DataFlavor.stringFlavor), "sem seleção, copia a pasta atual");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Menu de contexto
    // ---------------------------------------------------------------------------------------------

    @Test
    void menuDeContextoMudaComASelecao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Visual.naEdt(() -> c.g.tabela().clearSelection());
            assertEquals(List.of("Nova pasta", "Novo arquivo", "Enviar para esta pasta…", "Copiar o caminho desta pasta", "Atualizar"),
                textosSemOrdenar(Visual.naEdt(c.g::menuDeContextoParaTeste)));
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md"));
            assertEquals(List.of("Abrir", "Baixar…", "Renomear", "Mover para…", "Copiar caminho", "Apagar"),
                textosSemOrdenar(Visual.naEdt(c.g::menuDeContextoParaTeste)));
            Visual.naEdt(() -> c.g.selecionarNaTabela("src"));
            assertEquals("Abrir a pasta", textosSemOrdenar(Visual.naEdt(c.g::menuDeContextoParaTeste)).get(0));
            assertTrue(textosDoMenu(Visual.naEdt(c.g::menuDeContextoParaTeste)).contains("Ordenar por"), "o menu também ordena (para quem usa só o teclado)");
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md", "src"));
            assertFalse(Visual.naEdt(() -> item(c.g.menuDeContextoParaTeste(), "Renomear").isEnabled()), "renomear é de um item só");
            assertTrue(Visual.naEdt(() -> item(c.g.menuDeContextoParaTeste(), "Apagar").isEnabled()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Somente leitura
    // ---------------------------------------------------------------------------------------------

    @Test
    void semAsAcoesLigadasSoLeEBaixaEDizIssoEmUmaFrase(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, false)) {
            assertTrue(Visual.naEdt(() -> c.g.avisoDeSomenteLeitura().isVisible()));
            assertEquals("Somente leitura nesta versão: dá para navegar, abrir e baixar; enviar, editar, criar, renomear, mover e apagar ficam desligados.",
                Visual.naEdt(() -> c.g.avisoDeSomenteLeitura().getText()));
            for (String b : List.of("enviar", "nova-pasta", "novo-arquivo", "renomear", "apagar")) {
                assertFalse(Visual.naEdt(() -> c.g.botao(b).isVisible()), b + " escondido");
            }
            assertTrue(Visual.naEdt(() -> c.g.botao("baixar").isVisible()));
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md"));
            assertTrue(Visual.naEdt(() -> c.g.botao("baixar").isEnabled()), "baixar sempre vale");
            // as teclas de escrita não fazem nada
            c.teclaNaTabela(KeyEvent.VK_F2, 0, "README.md");
            c.teclaNaTabela(KeyEvent.VK_DELETE, 0, "README.md");
            assertTrue(c.dialogos.mostrados.isEmpty());
            // as chamadas de escrita são recusadas com um aviso, sem tocar no servidor
            Visual.naEdt(() -> c.g.criarPasta("nova"));
            Visual.naEdt(() -> c.g.apagar(List.of(Visual.naEdt(() -> c.g.entrada("README.md").orElseThrow()))));
            assertTrue(c.avisos.disse("atencao", "somente leitura"));
            assertFalse(Files.exists(c.sftp.orbita().resolve("nova")));
            assertTrue(Files.exists(c.sftp.orbita().resolve("README.md")));
            // soltar arquivos do Mac não envia
            Path local = Files.writeString(tmp.resolve("do-mac.txt"), "x");
            Visual.naEdt(() -> c.g.soltarArquivosLocais(List.of(local), null));
            Espera.naoAcontece(Duration.ofMillis(300), "enviou em somente leitura", () -> Files.exists(c.sftp.orbita().resolve("do-mac.txt")));
            // arrastar para dentro não é aceito
            var suporte = new TransferHandler.TransferSupport(Visual.naEdt(c.g::tabela), new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {DataFlavor.javaFileListFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return DataFlavor.javaFileListFlavor.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) {
                    return List.of(new File(local.toString()));
                }
            });
            assertFalse(Visual.naEdt(() -> c.g.manipuladorParaTeste().canImport(suporte)));
            // o menu de contexto: escrever desabilitado
            assertFalse(Visual.naEdt(() -> item(c.g.menuDeContextoParaTeste(), "Renomear").isEnabled()));
            assertFalse(Visual.naEdt(() -> item(c.g.menuDeContextoParaTeste(), "Apagar").isEnabled()));
            assertTrue(Visual.naEdt(() -> item(c.g.menuDeContextoParaTeste(), "Baixar…").isEnabled()));
            assertNotNull(c.g);
        }
    }

    /** Textos de um componente. */
    static final class TextosDoTeste {
        private TextosDoTeste() { }

        static String de(java.awt.Component c) {
            return br.com.labdesktop.ui.TextosDeTela.de(c);
        }
    }
}
