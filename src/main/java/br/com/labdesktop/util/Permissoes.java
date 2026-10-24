package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Permissões POSIX do que o app grava: pasta 0700 e arquivos 0600 (ARQUITETURA 5.1). Em sistema de arquivos
 * sem POSIX (não é o caso do macOS) as operações viram no-op em vez de falhar.
 */
public final class Permissoes {

    /** {@code rwx------}. */
    public static final Set<PosixFilePermission> PASTA_PRIVADA = PosixFilePermissions.fromString("rwx------");
    /** {@code rw-------}. */
    public static final Set<PosixFilePermission> ARQUIVO_PRIVADO = PosixFilePermissions.fromString("rw-------");

    private Permissoes() { }

    /** Atributo para criar arquivo já com {@code rw-------} (sem janela em que ele existe mais aberto). */
    public static FileAttribute<Set<PosixFilePermission>> atributoDeArquivoPrivado() {
        return PosixFilePermissions.asFileAttribute(ARQUIVO_PRIVADO);
    }

    /** Cria a pasta (e as que faltarem) com {@code rwx------}. Se já existe, só confere e corrige. */
    public static void criarPastaPrivada(Path pasta) throws IOException {
        if (!Files.exists(pasta)) {
            try {
                Files.createDirectories(pasta, PosixFilePermissions.asFileAttribute(PASTA_PRIVADA));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(pasta);
                return;
            }
        }
        corrigirSeAberta(pasta, PASTA_PRIVADA);
    }

    /**
     * Garante que o arquivo ou pasta tem exatamente as permissões esperadas.
     *
     * @return {@code true} se estava diferente e foi corrigido (o app avisa o usuário), {@code false} se já estava certo
     */
    public static boolean corrigirSeAberta(Path caminho, Set<PosixFilePermission> esperadas) throws IOException {
        try {
            Set<PosixFilePermission> atuais = Files.getPosixFilePermissions(caminho);
            if (atuais.equals(esperadas)) {
                return false;
            }
            Files.setPosixFilePermissions(caminho, esperadas);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /** As permissões atuais em texto ({@code rw-------}), ou {@code "?"} se o sistema de arquivos não for POSIX. */
    public static String comoTexto(Path caminho) throws IOException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(caminho));
        } catch (UnsupportedOperationException e) {
            return "?";
        }
    }
}
