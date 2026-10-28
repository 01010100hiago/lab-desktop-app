package br.com.labdesktop.instalador;

import br.com.labdesktop.ssh.ProvisionadorRemoto;
import java.io.IOException;

/**
 * Por onde o instalador do agente age sobre o servidor: roda um comando e envia um arquivo. É a "tomada" do instalador (como o
 * {@code TransporteDeTerminal} é a do terminal): hoje há uma implementação, {@link ExecutorSsh} (canal {@code exec} e SFTP da sessão
 * SSH que o app já mantém), e o laboratório local ("Este computador") traz outra que roda um processo no mesmo
 * computador e copia o arquivo com {@code java.nio}. O instalador, as verificações e os passos só falam com esta interface, então
 * nada neles supõe SSH, Mac ou caminho local.
 *
 * <p>Os comandos são sempre os FIXOS de {@link ComandosRemotos} (sem texto do usuário ou do servidor na linha). Tudo aqui bloqueia:
 * chame de thread de fundo, nunca da EDT. Interromper a thread (cancelar) interrompe o comando em andamento.
 */
public interface ExecutorDeComandos extends ProvisionadorRemoto.Executor {

    /**
     * Grava {@code conteudo} em {@code caminho}, sobrescrevendo. O caminho é RELATIVO à pasta pessoal do usuário (no SFTP, o
     * diretório inicial; num executor local, a pasta pessoal) e já foi montado pelo instalador com nomes validados; a pasta de
     * destino já existe (o instalador a cria antes, por comando).
     *
     * @throws IOException o envio falhou (sem permissão, disco cheio, conexão caiu)
     */
    void enviarArquivo(String caminho, byte[] conteudo) throws IOException;
}
