package br.com.labdesktop.arquivos;

import java.io.IOException;
import org.apache.sshd.sftp.client.SftpClient;

/** Abre um canal SFTP novo na sessão SSH atual (em produção, {@code conexao::abrirSftp}). */
@FunctionalInterface
public interface FabricaDeSftp {

    SftpClient abrir() throws IOException;
}
