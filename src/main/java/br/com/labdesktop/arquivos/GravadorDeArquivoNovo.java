package br.com.labdesktop.arquivos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Grava um arquivo NOVO escolhido pelo usuário (nunca sobrescreve). Fica fora da {@code ui}: é entrada e saída de disco. */
public final class GravadorDeArquivoNovo {

    private GravadorDeArquivoNovo() {
    }

    /** O destino sem sobrescrever nada: se o nome existe, vira {@code nome-2.ext}, {@code nome-3.ext}... */
    public static Path livre(Path pedido) {
        if (!Files.exists(pedido)) {
            return pedido;
        }
        String nome = pedido.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String base = ponto > 0 ? nome.substring(0, ponto) : nome;
        String ext = ponto > 0 ? nome.substring(ponto) : "";
        for (int i = 2; i < 1000; i++) {
            Path p = pedido.resolveSibling(base + "-" + i + ext);
            if (!Files.exists(p)) {
                return p;
            }
        }
        return pedido.resolveSibling(base + "-" + System.currentTimeMillis() + ext);
    }

    /**
     * Arquivo novo (falha se já existe), só para o usuário: o conteúdo vai para um temporário ao lado, que já nasce só do usuário (0600 onde há POSIX), e só quando está inteiro e no disco
     * ele ganha o nome pedido. Disco cheio no meio apaga o temporário e não deixa um arquivo cortado com o nome do relatório.
     */
    public static void gravar(Path destino, byte[] bytes) throws IOException {
        Path tmp = destino.resolveSibling("." + destino.getFileName() + ".tmp-" + Long.toHexString(System.nanoTime()));
        try {
            try (java.nio.channels.FileChannel canal = abrirPrivado(tmp)) {
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
                while (b.hasRemaining()) {
                    canal.write(b);
                }
                canal.force(true);
            }
            Files.move(tmp, destino); // sem REPLACE_EXISTING: se o nome apareceu no meio, falha como o CREATE_NEW falharia
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static java.nio.channels.FileChannel abrirPrivado(Path tmp) throws IOException {
        java.util.Set<StandardOpenOption> o = java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return java.nio.channels.FileChannel.open(tmp, o, br.com.labdesktop.util.Permissoes.atributoDeArquivoPrivado());
        } catch (UnsupportedOperationException e) {
            return java.nio.channels.FileChannel.open(tmp, o); // sistema de arquivos sem POSIX (Windows): o ACL do proprietário vem de Permissoes
        }
    }
}
