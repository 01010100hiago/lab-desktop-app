package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Leitura e gravação de arquivos pequenos da pasta de dados, sempre com {@code rw-------} (ARQUITETURA 5.1).
 * Serve às preferências e ao estado dos avisos (nenhum dos dois tem segredo, mas não há razão para ficarem
 * legíveis a outros usuários). O cofre tem o seu próprio caminho de gravação ({@code ArquivoCofre}), com
 * {@code .bak} e ordem de recuperação.
 */
public final class ArquivoPrivado {

    private ArquivoPrivado() { }

    /**
     * Grava de forma atômica: temporário {@code <nome>.tmp} já com {@code rw-------}, {@code fsync} e
     * {@code rename}. Uma queda no meio deixa o arquivo antigo inteiro ou o novo inteiro, nunca metade.
     * A pasta precisa existir ({@link CaminhosApp#prepararPastas()}).
     */
    public static void gravarAtomico(Path destino, byte[] conteudo) throws IOException {
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Set<StandardOpenOption> opcoes = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (FileChannel canal = abrirParaCriar(tmp, opcoes)) {
            ByteBuffer buf = ByteBuffer.wrap(conteudo);
            while (buf.hasRemaining()) {
                canal.write(buf);
            }
            canal.force(true);
        }
        try {
            Files.move(tmp, destino, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static FileChannel abrirParaCriar(Path tmp, Set<StandardOpenOption> opcoes) throws IOException {
        return Permissoes.abrirCanalPrivado(tmp, opcoes);
    }

    /**
     * Lê o arquivo inteiro, recusando acima de {@code maximo} bytes ANTES de alocar (arquivo corrompido ou
     * trocado por outro processo não pode provocar {@code OutOfMemoryError}).
     */
    public static byte[] lerLimitado(Path arquivo, long maximo) throws IOException {
        long tamanho = Files.size(arquivo);
        if (tamanho > maximo) {
            throw new IOException("Arquivo grande demais (" + tamanho + " bytes, o máximo é " + maximo + ").");
        }
        return Files.readAllBytes(arquivo);
    }
}
