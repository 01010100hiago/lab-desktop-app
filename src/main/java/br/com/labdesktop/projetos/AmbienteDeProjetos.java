package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import java.util.function.UnaryOperator;

/**
 * Onde os projetos moram e como mexer neles: a raiz dos projetos (um caminho do app, sempre com barra), um jeito de abrir o cliente de arquivos
 * (o SFTP do servidor ou o sistema de arquivos deste computador, atrás da MESMA interface {@link ClienteSftp}) e um jeito de rodar um programa
 * ({@link ExecutorDeArgumentos}). Criar do zero, importar e adicionar container só falam com isto: não sabem se o servidor é o computador local ou
 * um Debian por SSH. Tudo bloqueia (rede ou disco): thread de fundo, nunca a EDT.
 *
 * @param local           é "Este computador" (comandos de lista, caminhos convertidos para o sistema)
 * @param raizDosProjetos a pasta dos projetos ({@code /srv/lab/projects}; no local, a pasta do laboratório, no caminho do app)
 * @param arquivos        abre um cliente de arquivos novo (cada operação o fecha)
 * @param executor        roda um programa
 * @param paraOSistema    o caminho do app como o programa o entende (no Windows, {@code /C:/Users/ana} vira {@code C:\Users\ana}); igual no resto
 */
public record AmbienteDeProjetos(boolean local, String raizDosProjetos, FabricaDeClienteSftp arquivos, ExecutorDeArgumentos executor,
                                 UnaryOperator<String> paraOSistema) {

    /** Abre o cliente de arquivos; quem pede o fecha ({@code try-with-resources}). */
    public ClienteSftp abrirArquivos() {
        return arquivos.criar();
    }

    /** Um servidor por SSH: arquivos pelo SFTP, programas pela linha de comando remota (argumentos só seguros). */
    public static AmbienteDeProjetos remoto(String raizDosProjetos, FabricaDeClienteSftp arquivos, ProvisionadorRemoto.Executor remoto) {
        return new AmbienteDeProjetos(false, raizDosProjetos, arquivos, ExecutorDeArgumentos.porSsh(remoto), caminho -> caminho);
    }

    /** O computador local: arquivos por {@code java.nio}, programas por processo com argumentos em lista. */
    public static AmbienteDeProjetos local(String raizDosProjetos, FabricaDeClienteSftp arquivos, ExecutorDeProcesso processos, CaminhosLocais caminhos) {
        return new AmbienteDeProjetos(true, raizDosProjetos, arquivos, ExecutorDeArgumentos.deProcessos(processos), caminhos::paraTextoDoSistema);
    }
}
