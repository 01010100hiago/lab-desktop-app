package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeTransferencia;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Enviar e baixar pela tela: botões, arrastar e soltar, o painel de transferências, o "já existe" e o aviso de arquivo grande. */
@ExtendWith(VerificaEdt.class)
class TransferenciasNoGerenciadorTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static CenaDeArquivos cena(Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", CenaDeArquivos::projetoDeExemplo, true);
        c.esperarNormal();
        return c;
    }

    private static Retrato primeiro(CenaDeArquivos c) {
        return Visual.naEdt(() -> c.g.fila() == null ? null : c.g.fila().retratos().isEmpty() ? null : c.g.fila().retratos().get(0));
    }

    private static void esperarFim(CenaDeArquivos c, int quantas) {
        Espera.ate(CenaDeArquivos.PRAZO, () -> "retratos=" + Visual.naEdt(() -> c.g.fila() == null ? List.of() : c.g.fila().retratos()),
            () -> Visual.naEdt(() -> c.g.fila() != null && c.g.fila().retratos().size() >= quantas
                && c.g.fila().retratos().stream().allMatch(r -> r.estado().terminou())));
    }

    private static Transferable arquivosDoFinder(List<Path> arquivos) {
        return new Transferable() {
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
                return arquivos.stream().map(p -> new File(p.toString())).toList();
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Enviar
    // ---------------------------------------------------------------------------------------------

    @Test
    void oBotaoEnviarPerguntaOQueEnviarMostraAFilaEAPastaSeAtualiza(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path local = Files.write(tmp.resolve("relatorio.pdf"), new byte[2 * 1024 * 1024]);
            c.escolhedor.paraEnviar = List.of(local);
            assertFalse(Visual.naEdt(() -> c.g.painelDeTransferencias().aberto()), "recolhido até a primeira transferência");
            c.clicar("enviar");
            assertEquals(1, c.escolhedor.vezesQuePerguntouOQueEnviar);
            esperarFim(c, 1);
            Retrato r = primeiro(c);
            assertEquals(Estado.CONCLUIDA, r.estado(), r.mensagem());
            assertEquals(2 * 1024 * 1024, Files.size(c.sftp.orbita().resolve("relatorio.pdf")));
            assertTrue(Visual.naEdt(() -> c.g.painelDeTransferencias().aberto()), "a primeira transferência abre o painel");
            c.esperarQue("a pasta mostra o arquivo novo sem o usuário apertar nada", () -> c.nomes().contains("relatorio.pdf"));
            var linha = Visual.naEdt(() -> c.g.painelDeTransferencias().linhas().get(0));
            assertEquals("Concluída", Visual.naEdt(linha::estadoEmTexto));
            assertEquals("100%", Visual.naEdt(linha::textoDoPorcento));
            assertEquals("relatorio.pdf para orbita", Visual.naEdt(linha::textoDoTitulo));
            assertEquals("1 arquivo enviado", Visual.naEdt(linha::textoDoDetalhe));
            assertFalse(Visual.naEdt(() -> linha.botaoCancelar().isVisible()), "terminada: sem Cancelar");
            assertEquals("1 concluída", Visual.naEdt(() -> c.g.painelDeTransferencias().textoDoResumo()));
            assertTrue(Visual.naEdt(() -> c.g.painelDeTransferencias().botaoDeLimpar().isVisible()));
            Visual.naEdt(() -> c.g.painelDeTransferencias().botaoDeLimpar().doClick(0));
            assertEquals(0, Visual.naEdt(() -> c.g.painelDeTransferencias().linhas().size()));
            assertEquals("Nenhuma por enquanto", Visual.naEdt(() -> c.g.painelDeTransferencias().textoDoResumo()));
        }
    }

    @Test
    void cancelarNoSeletorDeArquivosNaoEnviaNada(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            c.escolhedor.paraEnviar = List.of();
            c.clicar("enviar");
            assertEquals(1, c.escolhedor.vezesQuePerguntouOQueEnviar);
            assertNull(primeiro(c));
        }
    }

    @Test
    void arrastarArquivosDoFinderParaATabelaEnviaParaAPastaAtual(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path pasta = Files.createDirectories(tmp.resolve("meu-site"));
            Files.writeString(pasta.resolve("index.html"), "<h1>oi</h1>");
            Files.createDirectories(pasta.resolve("css"));
            Files.writeString(pasta.resolve("css/estilo.css"), "body{}");
            Path solto = Files.writeString(tmp.resolve("solto.txt"), "solto");
            var suporte = new TransferHandler.TransferSupport(Visual.naEdt(c.g::tabela), arquivosDoFinder(List.of(pasta, solto)));
            ManipuladorDeArraste h = Visual.naEdt(c.g::manipuladorParaTeste);
            assertTrue(Visual.naEdt(() -> h.canImport(suporte)));
            assertTrue(Visual.naEdt(() -> h.importData(suporte)));
            esperarFim(c, 1);
            assertEquals(Estado.CONCLUIDA, primeiro(c).estado(), primeiro(c).mensagem());
            assertEquals("<h1>oi</h1>", Files.readString(c.sftp.orbita().resolve("meu-site/index.html")));
            assertEquals("body{}", Files.readString(c.sftp.orbita().resolve("meu-site/css/estilo.css")));
            assertEquals("solto", Files.readString(c.sftp.orbita().resolve("solto.txt")));
        }
    }

    @Test
    void soltarSobreUmaPastaEnviaParaDentroDela(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path solto = Files.writeString(tmp.resolve("solto.txt"), "solto");
            Visual.naEdt(() -> c.g.soltarArquivosLocais(List.of(solto), O + "/docs"));
            esperarFim(c, 1);
            assertEquals("solto", Files.readString(c.sftp.orbita().resolve("docs/solto.txt")));
            assertFalse(Files.exists(c.sftp.orbita().resolve("solto.txt")));
        }
    }

    @Test
    void oQueSolta_nao_escapaDaRaizDoProjeto(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path solto = Files.writeString(tmp.resolve("solto.txt"), "solto");
            Visual.naEdt(() -> c.g.soltarArquivosLocais(List.of(solto), CenarioDeSftp.PROJETOS + "/outro"));
            esperarFim(c, 1);
            assertEquals(Estado.FALHOU, primeiro(c).estado());
            assertFalse(Files.exists(c.sftp.local(CenarioDeSftp.PROJETOS + "/outro/solto.txt")));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Já existe
    // ---------------------------------------------------------------------------------------------

    @Test
    void jaExistePerguntaSubstituirPularManterOsDoisOuCancelar(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path local = Files.writeString(tmp.resolve("README.md"), "novo README do computador");
            c.escolhedor.paraEnviar = List.of(local);
            c.clicar("enviar");
            DialogoConflitoDeTransferencia d = c.esperarDialogo(DialogoConflitoDeTransferencia.class);
            assertEquals("Manter os dois", Visual.naEdt(() -> d.botaoManterOsDois().getText()));
            assertEquals("Substituir", Visual.naEdt(() -> d.botaoSubstituir().getText()));
            assertEquals("Pular", Visual.naEdt(() -> d.botaoPular().getText()));
            assertEquals("Cancelar", Visual.naEdt(() -> d.botaoCancelar().getText()));
            String texto = Visual.naEdt(() -> TextosDoTeste.de(d.raiz()));
            assertTrue(texto.contains("\"README.md\" já existe"), texto);
            assertTrue(texto.contains("No servidor:") && texto.contains("O que você está enviando:"), texto);
            assertTrue(texto.contains("README (2).md"), "o texto mostra como o nome ficaria");
            assertEquals(Estado.ESPERANDO_VOCE, primeiro(c).estado(), "a transferência espera a resposta do usuário");
            assertEquals("Esperando sua resposta", Visual.naEdt(() -> c.g.painelDeTransferencias().linhas().get(0).estadoEmTexto()));
            Visual.naEdt(() -> d.botaoManterOsDois().doClick(0));
            esperarFim(c, 1);
            assertEquals("novo README do computador", Files.readString(c.sftp.orbita().resolve("README (2).md")));
            assertTrue(Files.readString(c.sftp.orbita().resolve("README.md")).startsWith("# Órbita"), "o original ficou");
        }
    }

    @Test
    void substituirEAplicarATodosPerguntaUmaVezSo(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path pasta = Files.createDirectories(tmp.resolve("docs"));
            Files.writeString(pasta.resolve("arquitetura.md"), "novo 1");
            Files.writeString(pasta.resolve("outro.md"), "novo 2");
            c.sftp.escrever(O + "/docs/outro.md", "velho 2");
            c.sftp.escrever(O + "/docs/arquitetura.md", "velho 1");
            c.escolhedor.paraEnviar = List.of(pasta);
            c.clicar("enviar");
            DialogoConflitoDeTransferencia d = c.esperarDialogo(DialogoConflitoDeTransferencia.class);
            Visual.naEdt(() -> d.caixaDeAplicarATodos().setSelected(true));
            Visual.naEdt(() -> d.botaoSubstituir().doClick(0));
            esperarFim(c, 1);
            assertEquals(1, c.dialogos.mostrados.size(), "'aplicar a todos' não pergunta de novo");
            assertEquals("novo 1", Files.readString(c.sftp.orbita().resolve("docs/arquitetura.md")));
            assertEquals("novo 2", Files.readString(c.sftp.orbita().resolve("docs/outro.md")));
        }
    }

    @Test
    void fecharOuCancelarODialogoCancelaATransferencia(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path local = Files.writeString(tmp.resolve("README.md"), "novo");
            c.escolhedor.paraEnviar = List.of(local);
            c.clicar("enviar");
            DialogoConflitoDeTransferencia d = c.esperarDialogo(DialogoConflitoDeTransferencia.class);
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            esperarFim(c, 1);
            assertEquals(Estado.CANCELADA, primeiro(c).estado());
            assertTrue(Files.readString(c.sftp.orbita().resolve("README.md")).startsWith("# Órbita"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cancelar e aviso de arquivo grande
    // ---------------------------------------------------------------------------------------------

    @Test
    void cancelarPelaLinhaDoPainelPara(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path grande = Files.write(tmp.resolve("grande.bin"), new byte[30 * 1024 * 1024]);
            c.escolhedor.paraEnviar = List.of(grande);
            c.clicar("enviar");
            Espera.ate(CenaDeArquivos.PRAZO, "andando", () -> {
                Retrato r = primeiro(c);
                return r != null && r.estado() == Estado.EM_ANDAMENTO && r.bytesFeitos() > 256 * 1024;
            });
            var linha = Visual.naEdt(() -> c.g.painelDeTransferencias().linhas().get(0));
            assertTrue(Visual.naEdt(() -> linha.botaoCancelar().isVisible()));
            assertEquals("Enviando", Visual.naEdt(linha::estadoEmTexto));
            Visual.naEdt(() -> linha.botaoCancelar().doClick(0));
            esperarFim(c, 1);
            assertEquals(Estado.CANCELADA, primeiro(c).estado());
            assertEquals("Cancelada", Visual.naEdt(linha::estadoEmTexto));
            try (var s = Files.list(c.sftp.orbita())) {
                assertTrue(s.noneMatch(p -> p.getFileName().toString().contains("grande")), "nada ficou no servidor");
            }
        }
    }

    @Test
    void acimaDe500MbPerguntaAntesECancelarEOPadrao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path enorme = tmp.resolve("enorme.bin");
            try (RandomAccessFile f = new RandomAccessFile(enorme.toFile(), "rw")) {
                f.setLength(600L * 1024 * 1024); // arquivo esparso: não gasta 600 MB de disco
            }
            c.escolhedor.paraEnviar = List.of(enorme);
            c.clicar("enviar");
            DialogoConfirmar d = c.esperarDialogo(DialogoConfirmar.class);
            String texto = Visual.naEdt(() -> TextosDoTeste.de(d.raiz()));
            assertTrue(texto.contains("600 MB"), texto);
            assertEquals("Enviar", Visual.naEdt(() -> d.botaoConfirmar().getText()));
            assertEquals(br.com.labdesktop.ui.componentes.Botao.Variante.PRIMARIO, Visual.naEdt(() -> d.botaoCancelar().variante()));
            assertNull(primeiro(c), "nada começou antes da resposta");
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            Espera.naoAcontece(Duration.ofMillis(400), "começou", () -> primeiro(c) != null);

            c.clicar("enviar");
            c.esperarQue("segundo aviso", () -> c.dialogos.mostrados.size() == 2);
            DialogoConfirmar d2 = c.dialogo(DialogoConfirmar.class);
            Visual.naEdt(() -> d2.botaoConfirmar().doClick(0));
            Espera.ate(CenaDeArquivos.PRAZO, "começou", () -> primeiro(c) != null);
            Visual.naEdt(() -> c.g.fila().cancelar(primeiro(c).id())); // não vamos mandar 600 MB
            esperarFim(c, 1);
        }
    }

    @Test
    void menosDe500MbNaoPergunta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            c.escolhedor.paraEnviar = List.of(Files.write(tmp.resolve("pequeno.bin"), new byte[1024]));
            c.clicar("enviar");
            esperarFim(c, 1);
            assertTrue(c.dialogos.mostrados.isEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Baixar
    // ---------------------------------------------------------------------------------------------

    @Test
    void baixarArquivoEPastaParaUmaPastaEscolhida(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path destino = tmp.resolve("Meus Downloads");
            c.escolhedor.pastaDeDownload = destino;
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md", "src"));
            c.clicar("baixar");
            assertEquals(ServicosDeArquivos.pastaDeDownloadsPadrao(), c.escolhedor.padraoVisto, "o seletor começa em Downloads");
            esperarFim(c, 1);
            assertEquals(Estado.CONCLUIDA, primeiro(c).estado(), primeiro(c).mensagem());
            assertTrue(Files.readString(destino.resolve("README.md")).startsWith("# Órbita"));
            assertTrue(Files.exists(destino.resolve("src/servidor.js")));
            assertTrue(Files.exists(destino.resolve("src/componentes/cartao.js")));
            var linha = Visual.naEdt(() -> c.g.painelDeTransferencias().linhas().get(0));
            assertEquals("Concluída", Visual.naEdt(linha::estadoEmTexto));
        }
    }

    @Test
    void cancelarOSeletorDeDestinoNaoBaixaNada(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            c.escolhedor.pastaDeDownload = null;
            Visual.naEdt(() -> c.g.selecionarNaTabela("README.md"));
            c.clicar("baixar");
            assertNull(primeiro(c));
        }
    }

    @Test
    void baixarComConflitoLocalPergunta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path destino = Files.createDirectories(tmp.resolve("Downloads"));
            Files.writeString(destino.resolve("Dockerfile"), "o que já estava no Mac");
            c.escolhedor.pastaDeDownload = destino;
            Visual.naEdt(() -> c.g.selecionarNaTabela("Dockerfile"));
            c.clicar("baixar");
            DialogoConflitoDeTransferencia d = c.esperarDialogo(DialogoConflitoDeTransferencia.class);
            assertTrue(Visual.naEdt(() -> TextosDoTeste.de(d.raiz())).contains("No seu computador:"));
            Visual.naEdt(() -> d.botaoManterOsDois().doClick(0));
            esperarFim(c, 1);
            assertEquals("o que já estava no Mac", Files.readString(destino.resolve("Dockerfile")));
            assertTrue(Files.readString(destino.resolve("Dockerfile (2)")).startsWith("FROM node"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // O painel, com retratos montados à mão
    // ---------------------------------------------------------------------------------------------

    @Test
    void oPainelDizOEstadoComPalavrasEIconeECadaDadoNoSeuRotulo(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        PainelDeTransferencias painel = Visual.naEdt(() -> new PainelDeTransferencias(new PainelDeTransferencias.Acoes() {
            @Override
            public void cancelar(long id) { }

            @Override
            public void limparConcluidas() { }
        }));
        List<Retrato> retratos = List.of(
            new Retrato(1, Tipo.ENVIO, "orbita.zip para orbita", Estado.EM_ANDAMENTO, 37_000_000, 100_000_000, 2_400_000, Duration.ofSeconds(26), 0, 1, 0, 0,
                "orbita.zip", ""),
            new Retrato(2, Tipo.DOWNLOAD, "3 itens para Downloads", Estado.NA_FILA, 0, 0, 0, null, 0, 0, 0, 0, "", ""),
            new Retrato(3, Tipo.ENVIO, "backup.sql para orbita", Estado.FALHOU, 1000, 5000, 0, null, 0, 1, 0, 1, "", "O servidor desconectou no meio."),
            new Retrato(4, Tipo.ENVIO, "logo.png para orbita", Estado.CANCELADA, 0, 10, 0, null, 0, 1, 0, 0, "", "Cancelada."),
            new Retrato(5, Tipo.DOWNLOAD, "docs para Downloads", Estado.COM_PROBLEMAS, 10, 10, 0, null, 2, 3, 0, 1, "", "2 arquivos baixados. Com problema: x.txt: Sem permissão."));
        Visual.naEdt(() -> painel.definir(retratos));
        var l = Visual.naEdt(painel::linhas);
        assertEquals(5, l.size());
        assertEquals("Enviando", Visual.naEdt(l.get(0)::estadoEmTexto));
        assertEquals("37%", Visual.naEdt(l.get(0)::textoDoPorcento));
        assertEquals("2,3 MB/s", Visual.naEdt(l.get(0)::textoDaVelocidade));
        assertEquals("faltam 26 s", Visual.naEdt(l.get(0)::textoDoRestante));
        assertEquals("orbita.zip: 35 MB de 95 MB", Visual.naEdt(l.get(0)::textoDoDetalhe));
        assertEquals("Na fila", Visual.naEdt(l.get(1)::estadoEmTexto));
        assertEquals("", Visual.naEdt(l.get(1)::textoDoPorcento));
        assertEquals("Falhou", Visual.naEdt(l.get(2)::estadoEmTexto));
        assertEquals("O servidor desconectou no meio.", Visual.naEdt(l.get(2)::textoDoDetalhe));
        assertEquals("Cancelada", Visual.naEdt(l.get(3)::estadoEmTexto));
        assertEquals("Concluída com problemas", Visual.naEdt(l.get(4)::estadoEmTexto));
        assertEquals("2 em andamento", Visual.naEdt(painel::textoDoResumo));
        assertTrue(Visual.naEdt(painel::aberto), "a primeira transferência abre o painel");
        // nenhum rótulo junta vários dados com " · "
        for (var linha : l) {
            for (var comp : Visual.naEdt(linha::componentesDeTexto)) {
                String t = comp instanceof javax.swing.JLabel jl ? jl.getText() : comp instanceof javax.swing.AbstractButton b ? b.getText() : "";
                assertFalse(t.contains(" · "), t);
            }
        }
        assertEquals("45 s", PainelDeTransferencias.duracao(Duration.ofSeconds(45)));
        assertEquals("3 min", PainelDeTransferencias.duracao(Duration.ofSeconds(170)));
        assertEquals("1 h 05 min", PainelDeTransferencias.duracao(Duration.ofSeconds(3900)));
        assertNotNull(painel);
    }

    /** Textos de um componente. */
    static final class TextosDoTeste {
        private TextosDoTeste() { }

        static String de(java.awt.Component c) {
            return br.com.labdesktop.ui.TextosDeTela.de(c);
        }
    }
}
