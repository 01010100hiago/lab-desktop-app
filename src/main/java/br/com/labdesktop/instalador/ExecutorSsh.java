package br.com.labdesktop.instalador;

import br.com.labdesktop.ssh.ConexaoSsh;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;

/**
 * O {@link ExecutorDeComandos} sobre a sessão SSH que o app já mantém: o canal {@code exec} da {@link ConexaoSsh} para os comandos e
 * um canal SFTP (aberto e fechado a cada envio) para o pacote. Não abre outra conexão, não usa senha e não guarda nada.
 */
public final class ExecutorSsh implements ExecutorDeComandos {

    private final ConexaoSsh conexao;

    public ExecutorSsh(ConexaoSsh conexao) {
        this.conexao = conexao;
    }

    @Override
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        return conexao.executar(comando, stdin, prazo);
    }

    @Override
    public void enviarArquivo(String caminho, byte[] conteudo) throws IOException {
        try (SftpClient sftp = conexao.abrirSftp(); OutputStream saida = sftp.write(caminho, OpenMode.Create, OpenMode.Write, OpenMode.Truncate)) {
            saida.write(conteudo);
        }
    }
}
