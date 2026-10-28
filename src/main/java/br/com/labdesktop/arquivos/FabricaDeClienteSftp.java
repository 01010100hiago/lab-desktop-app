package br.com.labdesktop.arquivos;

/**
 * Cria o {@link ClienteSftp} de um gerenciador de arquivos: sobre o SFTP do SSH (servidor remoto) ou sobre o sistema de arquivos do próprio
 * computador ("Este computador"). A tela só conhece esta tomada e a interface {@link ClienteSftp}.
 */
@FunctionalInterface
public interface FabricaDeClienteSftp {

    ClienteSftp criar();

    /** O cliente sobre a sessão SSH atual (o que sempre foi). */
    static FabricaDeClienteSftp doSftp(FabricaDeSftp fabrica) {
        return () -> new ClienteSftpMina(fabrica);
    }
}
