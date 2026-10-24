package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A abertura automática do cofre de teste só pode valer em condições estreitas (nunca no cofre de verdade). */
class SenhaDeTesteTest {

    @TempDir
    Path tmp;

    private Path arquivo() {
        return tmp.resolve(SenhaDeTeste.NOME_DO_ARQUIVO);
    }

    private Optional<Path> consultar(StringBuilder motivo) {
        return SenhaDeTeste.arquivoSeguro(CaminhosApp.comPasta(tmp), motivo);
    }

    @Test
    void aOpcaoSoVaiQuandoFoiPedida() {
        assertTrue(SenhaDeTeste.pedida(new String[] {"--pasta", "x", "--senha-de-teste"}));
        assertFalse(SenhaDeTeste.pedida(new String[] {"--pasta", "x"}));
        assertFalse(SenhaDeTeste.pedida(null));
    }

    @Test
    void aceitaArquivoComumDoUsuarioComPermissao0600() throws Exception {
        Files.writeString(arquivo(), "senha\n");
        Files.setPosixFilePermissions(arquivo(), PosixFilePermissions.fromString("rw-------"));
        assertEquals(Optional.of(arquivo().toAbsolutePath().normalize()), consultar(null)
            .map(p -> p.toAbsolutePath().normalize()));
    }

    @Test
    void recusaNaPastaDeDadosPadrao() {
        StringBuilder motivo = new StringBuilder();
        assertTrue(SenhaDeTeste.arquivoSeguro(CaminhosApp.padrao(), motivo).isEmpty());
        assertTrue(motivo.toString().contains("--pasta"), motivo.toString());
    }

    @Test
    void recusaArquivoAusente() {
        StringBuilder motivo = new StringBuilder();
        assertTrue(consultar(motivo).isEmpty());
        assertTrue(motivo.toString().contains("não existe"), motivo.toString());
    }

    @Test
    void recusaPermissaoMaisAbertaQue0600() throws Exception {
        Files.writeString(arquivo(), "senha\n");
        Files.setPosixFilePermissions(arquivo(), PosixFilePermissions.fromString("rw-r--r--"));
        StringBuilder motivo = new StringBuilder();
        assertTrue(consultar(motivo).isEmpty());
        assertTrue(motivo.toString().contains("0600"), motivo.toString());
    }

    @Test
    void recusaLinkSimbolico() throws Exception {
        Path alvo = Files.writeString(tmp.resolve("outro.txt"), "senha\n");
        Files.setPosixFilePermissions(alvo, PosixFilePermissions.fromString("rw-------"));
        Files.createSymbolicLink(arquivo(), alvo);
        StringBuilder motivo = new StringBuilder();
        assertTrue(consultar(motivo).isEmpty());
        assertTrue(motivo.toString().contains("arquivo comum"), motivo.toString());
    }
}
