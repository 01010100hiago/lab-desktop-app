package br.com.labdesktop.cofre;

import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Permissoes;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Trava de instância única (ARQUITETURA 5.1 e 4.4 passo 3): {@code FileChannel.tryLock()} em
 * {@code cofre.lock}. Duas instâncias gravando o mesmo cofre se atropelariam; a segunda avisa e sai.
 *
 * <p>O lock é do sistema operacional: se o processo morrer (até com {@code kill -9}) ele é solto sozinho, sem
 * arquivo de PID para ficar velho. Dentro da MESMA JVM o Java não deixa travar duas vezes e lança
 * {@link OverlappingFileLockException}, que aqui também significa "em uso".
 */
public final class TravaDeInstancia implements AutoCloseable {

    private final FileChannel canal;
    private final FileLock trava;

    private TravaDeInstancia(FileChannel canal, FileLock trava) {
        this.canal = canal;
        this.trava = trava;
    }

    /**
     * Tenta pegar a trava; cria a pasta de dados (0700) se precisar.
     *
     * @throws CofreEmUsoException se outra instância (neste ou em outro processo) já a tem
     */
    public static TravaDeInstancia adquirir(CaminhosApp caminhos) throws CofreEmUsoException, IOException {
        caminhos.prepararPastas();
        Set<StandardOpenOption> opcoes = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileChannel canal;
        try {
            canal = FileChannel.open(caminhos.arquivoTrava(), opcoes, Permissoes.atributoDeArquivoPrivado());
        } catch (UnsupportedOperationException e) {
            canal = FileChannel.open(caminhos.arquivoTrava(), opcoes);
        }
        FileLock trava;
        try {
            trava = canal.tryLock();
        } catch (OverlappingFileLockException e) {
            trava = null;
        } catch (IOException | RuntimeException e) {
            canal.close();
            throw e;
        }
        if (trava == null) {
            canal.close();
            throw new CofreEmUsoException();
        }
        return new TravaDeInstancia(canal, trava);
    }

    /** Solta a trava. Idempotente. */
    @Override
    public void close() {
        try {
            if (trava.isValid()) {
                trava.release();
            }
        } catch (IOException e) {
            // Fechar o canal abaixo solta a trava de qualquer jeito.
        }
        try {
            canal.close();
        } catch (IOException e) {
            // Nada a fazer: o processo está encerrando a trava.
        }
    }
}
