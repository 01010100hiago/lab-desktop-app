package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Permissões POSIX: pasta {@code rwx------} e arquivo {@code rw-------}. */
public final class ProtecaoPosix implements ProtecaoDeArquivos {

    /** {@code rwx------}. */
    public static final Set<PosixFilePermission> PASTA_PRIVADA = PosixFilePermissions.fromString("rwx------");
    /** {@code rw-------}. */
    public static final Set<PosixFilePermission> ARQUIVO_PRIVADO = PosixFilePermissions.fromString("rw-------");

    @Override
    public FileAttribute<?>[] atributosDeArquivo() {
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(ARQUIVO_PRIVADO)};
    }

    @Override
    public FileAttribute<?>[] atributosDePasta() {
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PASTA_PRIVADA)};
    }

    @Override
    public void restringirAoDono(Path caminho, boolean pasta) throws IOException {
        Files.setPosixFilePermissions(caminho, pasta ? PASTA_PRIVADA : ARQUIVO_PRIVADO);
    }

    @Override
    public boolean corrigirSeAberta(Path caminho, boolean pasta) throws IOException {
        Set<PosixFilePermission> esperadas = pasta ? PASTA_PRIVADA : ARQUIVO_PRIVADO;
        if (Files.getPosixFilePermissions(caminho).equals(esperadas)) {
            return false;
        }
        Files.setPosixFilePermissions(caminho, esperadas);
        return true;
    }

    @Override
    public String descrever(Path caminho) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(caminho));
    }
}
