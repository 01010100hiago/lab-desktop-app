package br.com.labdesktop.ssh;

import java.io.IOException;

/**
 * A sessão SSH está de pé, mas o servidor não abriu o subsistema SFTP (desligado no {@code sshd_config}, ou o usuário só tem um
 * comando). Diferente de "sem conexão": reconectar não resolve, e a tela precisa dizer isso com todas as letras.
 */
public final class SftpIndisponivelException extends IOException {

    private static final long serialVersionUID = 1L;

    public SftpIndisponivelException(Throwable causa) {
        super("Este servidor não aceita transferência de arquivos (SFTP).", causa);
    }
}
