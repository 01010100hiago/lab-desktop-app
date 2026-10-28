package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import br.com.labdesktop.arquivos.PlanoDeBackups.Backup;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeTransferencia;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Baixar backups pela fila, de ponta a ponta contra o SFTP de verdade do servidor SSH embutido: destino pelo seletor, tamanho conferido, cancelamento no meio sem deixar parcial, "já existe"
 * pergunta, nome perigoso recusado, sem espaço e arquivo grande avisam, "Mostrar na pasta" no fim. Só lê do servidor.
 */
@ExtendWith(VerificaEdt.class)
class BaixadorDeBackupsTest {

    private static final String O = CenarioDeSftp.ORBITA;
    private static final Instant QUANDO = Instant.parse("2026-10-24T20:00:00Z");

    private static CenaDeArquivos cena(Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        CenaDeArquivos c = new CenaDeArquivos(tmp, true, "orbita", s -> {
            CenaDeArquivos.projetoDeExemplo(s);
            Files.createDirectories(s.local(O + "/backups"));
        }, false);
        return c;
    }

    private static BaixadorDeBackups baixador(CenaDeArquivos c) {
        return Visual.naEdt(() -> new BaixadorDeBackups(c.ctx, () -> O));
    }

    private static void esperarFim(BaixadorDeBackups b, int quantas) {
        Espera.ate(CenaDeArquivos.PRAZO, () -> "retratos=" + Visual.naEdt(() -> b.fila() == null ? List.of() : b.fila().retratos()),
            () -> Visual.naEdt(() -> b.fila() != null && b.fila().retratos().size() >= quantas && b.fila().retratos().stream().allMatch(r -> r.estado().terminou())));
    }

    private static Retrato primeiro(BaixadorDeBackups b) {
        return Visual.naEdt(() -> b.fila().retratos().get(0));
    }

    private static void escreverBackup(CenaDeArquivos c, String nome, int bytes) throws Exception {
        Files.write(c.sftp.orbita().resolve("backups").resolve(nome), new byte[bytes]);
    }

    @Test
    void baixaParaAPastaEscolhidaComOTamanhoConferidoEMostraNaPasta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "db-2026-10-24.sql.gz", 3 * 1024 * 1024);
            Path destino = Files.createDirectories(tmp.resolve("meus-downloads"));
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("db-2026-10-24.sql.gz", 3L * 1024 * 1024, QUANDO))));
            esperarFim(b, 1);
            Retrato r = primeiro(b);
            assertEquals(Estado.CONCLUIDA, r.estado(), r.mensagem());
            assertEquals(3L * 1024 * 1024, Files.size(destino.resolve("db-2026-10-24.sql.gz")), "o tamanho local é o do servidor");
            assertTrue(Visual.naEdt(b::mostraResultado));
            assertTrue(Visual.naEdt(b::textoDoResultado).startsWith("Pronto: 1 arquivo baixado"), Visual.naEdt(b::textoDoResultado));
            assertTrue(Visual.naEdt(b::textoDoResultado).contains("tamanho de cada arquivo foi conferido"));
            assertTrue(Visual.naEdt(() -> b.botaoMostrarNaPasta().isVisible()));
            Visual.naEdt(() -> b.botaoMostrarNaPasta().doClick(0));
            assertEquals(List.of(destino), c.escolhedor.mostradas, "Mostrar na pasta abre a pasta do download");
            assertEquals(ServicosDeArquivosPadrao.pastaDeDownloads(), c.escolhedor.padraoVisto, "o seletor começa em Downloads");
            assertTrue(Visual.naEdt(() -> b.painelDeTransferencias().aberto()), "a fila abre sozinha");
            try (var s = Files.list(destino)) {
                assertEquals(1, s.count(), "nenhum parcial sobrou");
            }
            b.encerrar();
        }
    }

    @Test
    void baixaVariosDeUmaVezTodosComOTamanhoCerto(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "a.sql", 1000);
            escreverBackup(c, "b.sql", 2000);
            escreverBackup(c, "c.sql", 3000);
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("a.sql", 1000L, QUANDO), new Backup("b.sql", 2000L, QUANDO), new Backup("c.sql", 3000L, QUANDO))));
            esperarFim(b, 1);
            assertEquals(Estado.CONCLUIDA, primeiro(b).estado(), primeiro(b).mensagem());
            assertEquals(1000, Files.size(destino.resolve("a.sql")));
            assertEquals(2000, Files.size(destino.resolve("b.sql")));
            assertEquals(3000, Files.size(destino.resolve("c.sql")));
            assertTrue(Visual.naEdt(b::textoDoResultado).startsWith("Pronto: 3 arquivos baixados"));
            b.encerrar();
        }
    }

    @Test
    void cancelarNoMeioNaoDeixaArquivoNemParcial(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "grande.sql.gz", 30 * 1024 * 1024);
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("grande.sql.gz", 30L * 1024 * 1024, QUANDO))));
            Espera.ate(CenaDeArquivos.PRAZO, "andando", () -> Visual.naEdt(() -> b.fila() != null && !b.fila().retratos().isEmpty()
                && b.fila().retratos().get(0).estado() == Estado.EM_ANDAMENTO && b.fila().retratos().get(0).bytesFeitos() > 256 * 1024));
            var linha = Visual.naEdt(() -> b.painelDeTransferencias().linhas().get(0));
            assertTrue(Visual.naEdt(() -> linha.botaoCancelar().isVisible()), "Cancelar aparece enquanto baixa");
            Visual.naEdt(() -> linha.botaoCancelar().doClick(0));
            esperarFim(b, 1);
            assertEquals(Estado.CANCELADA, primeiro(b).estado());
            assertTrue(Visual.naEdt(b::textoDoResultado).startsWith("Download cancelado"), Visual.naEdt(b::textoDoResultado));
            assertFalse(Visual.naEdt(() -> b.botaoMostrarNaPasta().isVisible()), "nada foi baixado: nada a mostrar");
            try (var s = Files.list(destino)) {
                assertEquals(0, s.count(), "nem o arquivo nem o parcial oculto ficaram");
            }
            b.encerrar();
        }
    }

    @Test
    void arquivoQueJaExistePerguntaESubstituirSoComConfirmacao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "db.sql", 500);
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            Files.writeString(destino.resolve("db.sql"), "arquivo antigo do usuário");
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("db.sql", 500L, QUANDO))));
            DialogoConflitoDeTransferencia d = c.esperarDialogo(DialogoConflitoDeTransferencia.class);
            assertEquals("arquivo antigo do usuário", Files.readString(destino.resolve("db.sql")), "nada foi sobrescrito antes de o usuário responder");
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            esperarFim(b, 1);
            assertEquals(Estado.CANCELADA, primeiro(b).estado());
            assertEquals("arquivo antigo do usuário", Files.readString(destino.resolve("db.sql")));
            b.encerrar();
        }
    }

    @Test
    void manterOsDoisGuardaOAntigoEOnovoComNumero(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "db.sql", 500);
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            Files.writeString(destino.resolve("db.sql"), "antigo");
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("db.sql", 500L, QUANDO))));
            DialogoConflitoDeTransferencia d = c.esperarDialogo(DialogoConflitoDeTransferencia.class);
            Visual.naEdt(() -> d.botaoDoEnter().doClick(0)); // o botão em destaque é "Manter os dois"
            esperarFim(b, 1);
            assertEquals("antigo", Files.readString(destino.resolve("db.sql")));
            try (var s = Files.list(destino)) {
                assertEquals(2, s.count());
            }
            b.encerrar();
        }
    }

    @Test
    void nomePerigosoVindoDoServidorNaoEBaixadoNemEscreveForaDaPasta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("../../fora.txt", 10L, QUANDO), new Backup("/etc/passwd", 10L, QUANDO))));
            assertEquals(null, b.fila(), "nada foi enfileirado");
            assertTrue(c.avisos.ultimo().toString().contains("não é um nome de arquivo seguro"), c.avisos.ultimo().toString());
            assertFalse(Files.exists(tmp.resolve("fora.txt")));
            assertEquals(0, c.escolhedor.vezesQuePerguntouOQueEnviar);
        }
    }

    @Test
    void semConexaoOuSemPastaDoProjetoAvisaENaoBaixa(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            BaixadorDeBackups semPasta = Visual.naEdt(() -> new BaixadorDeBackups(c.ctx, () -> null));
            c.escolhedor.pastaDeDownload = tmp;
            Visual.naEdt(() -> semPasta.baixar(List.of(new Backup("a.sql", 1L, QUANDO))));
            assertEquals(null, semPasta.fila());
            assertTrue(c.avisos.ultimo().toString().contains("Sem conexão"));
        }
    }

    @Test
    void cancelarOSeletorDeDestinoNaoBaixaNada(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "a.sql", 100);
            c.escolhedor.pastaDeDownload = null; // o usuário fechou o seletor
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("a.sql", 100L, QUANDO))));
            assertEquals(null, b.fila());
        }
    }

    @Test
    void arquivoGrandePedeConfirmacaoAntes(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            escreverBackup(c, "pequeno.sql", 100); // o arquivo real é pequeno; o tamanho que o servidor DISSE é que conta no aviso
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            Visual.naEdt(() -> b.baixar(List.of(new Backup("pequeno.sql", 600L * 1024 * 1024, QUANDO))));
            DialogoConfirmar d = (DialogoConfirmar) c.dialogos.ultimo();
            assertEquals("Backup grande", d.titulo());
            assertEquals(null, b.fila(), "nada começou antes do clique");
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            assertEquals(null, b.fila());
            assertEquals(1, c.dialogos.mostrados.size());
        }
    }

    @Test
    void semEspacoNoDiscoNaoComecaEDizQuantoFalta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp)) {
            Path destino = Files.createDirectories(tmp.resolve("dl"));
            c.escolhedor.pastaDeDownload = destino;
            BaixadorDeBackups b = baixador(c);
            long absurdo = Files.getFileStore(destino).getUsableSpace() + 10L * 1024 * 1024 * 1024; // mais do que cabe, de verdade
            Visual.naEdt(() -> b.baixar(List.of(new Backup("enorme.sql", absurdo, QUANDO))));
            DialogoConfirmar d = (DialogoConfirmar) c.dialogos.ultimo();
            assertEquals("Sem espaço no disco", d.titulo());
            assertEquals(null, b.fila(), "não começou");
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            assertEquals(null, b.fila());
        }
    }

    /** O mesmo padrão que o app usa (Downloads, ou a pasta do usuário). */
    private static final class ServicosDeArquivosPadrao {
        static Path pastaDeDownloads() {
            return br.com.labdesktop.arquivos.ServicosDeArquivos.pastaDeDownloadsPadrao();
        }
    }
}
