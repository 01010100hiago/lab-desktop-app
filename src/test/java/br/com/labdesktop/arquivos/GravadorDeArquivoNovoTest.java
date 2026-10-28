package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O arquivo novo do relatório: só do usuário, inteiro ou nada, e nunca por cima de outro. */
class GravadorDeArquivoNovoTest {

    @Test
    void gravaOConteudoInteiroENaoDeixaTemporario(@TempDir Path pasta) throws IOException {
        Path d = pasta.resolve("relatorio.csv");
        byte[] bytes = "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8);
        GravadorDeArquivoNovo.gravar(d, bytes);
        assertArrayEquals(bytes, Files.readAllBytes(d));
        try (var s = Files.list(pasta)) {
            assertEquals(List.of("relatorio.csv"), s.map(p -> p.getFileName().toString()).toList(), "nenhum temporário sobrou");
        }
    }

    @Test
    void nasceSoParaODono(@TempDir Path pasta) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "permissões POSIX");
        Path d = pasta.resolve("r.txt");
        GravadorDeArquivoNovo.gravar(d, new byte[] {1});
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(d)));
    }

    @Test
    void nuncaSobrescreveEOQueJaEstavaFicaIntacto(@TempDir Path pasta) throws IOException {
        Path d = pasta.resolve("r.csv");
        Files.writeString(d, "antigo");
        assertThrows(FileAlreadyExistsException.class, () -> GravadorDeArquivoNovo.gravar(d, "novo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("antigo", Files.readString(d));
        try (var s = Files.list(pasta)) {
            assertEquals(1, s.count(), "e o temporário foi apagado");
        }
    }

    @Test
    void pastaQueNaoExisteFalhaSemDeixarNada(@TempDir Path pasta) {
        Path d = pasta.resolve("nao-existe").resolve("r.csv");
        assertThrows(IOException.class, () -> GravadorDeArquivoNovo.gravar(d, new byte[] {1}));
    }
}
